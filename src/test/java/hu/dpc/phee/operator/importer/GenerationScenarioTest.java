package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import hu.dpc.phee.operator.entity.callactivity.CallActivityLinkRepository;
import hu.dpc.phee.operator.entity.task.TaskRepository;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import hu.dpc.phee.operator.entity.variable.Variable;
import hu.dpc.phee.operator.entity.variable.VariableRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * End-to-end through RecordParser with the real generation resolver and in-flight managers over an
 * in-memory database, fed records the way KafkaConsumer does (record timestamp in the thread local).
 * Process ids and variable names follow the flows deployed from helm-paymenthub
 * (mpesa_flow_tupande-oaf: transaction request, bulk_processor-ibank-usa: batch) and the transfer
 * flows configured in application.yml.
 */
public class GenerationScenarioTest {

    private static final long KEY = 2251799813690001L;
    private static final long CHILD_KEY = 2251799813690099L;
    private static final long T = 1_760_000_000_000L;
    private static final long DAY = 86_400_000L;

    private final Map<Class<?>, List<Object>> db = new HashMap<>();
    private long nextId = 1;

    private RecordParser recordParser;
    private TempDocumentStore tempDocumentStore;

    @Before
    public void setUp() throws Exception {
        TransferRepository transferRepository = inMemory(TransferRepository.class, Transfer.class);
        TransactionRequestRepository transactionRequestRepository = inMemory(TransactionRequestRepository.class, TransactionRequest.class);
        BatchRepository batchRepository = inMemory(BatchRepository.class, Batch.class);
        VariableRepository variableRepository = inMemory(VariableRepository.class, Variable.class);
        TaskRepository taskRepository = inMemory(TaskRepository.class, hu.dpc.phee.operator.entity.task.Task.class);
        CallActivityLinkRepository callActivityLinkRepository = inMemory(CallActivityLinkRepository.class,
                hu.dpc.phee.operator.entity.callactivity.CallActivityLink.class);
        tempDocumentStore = Mockito.mock(TempDocumentStore.class);

        BpmnProcessProperties bpmnProcessProperties = Mockito.mock(BpmnProcessProperties.class);
        Map<String, BpmnProcess> processes = new HashMap<>();
        processes.put("mpesa_flow_tupande", process("mpesa_flow_tupande", "TRANSACTION-REQUEST", "INCOMING"));
        processes.put("PayerFundTransfer", process("PayerFundTransfer", "TRANSFER", "OUTGOING"));
        processes.put("bulk_processor", process("bulk_processor", "BATCH", "UNKNOWN"));
        Mockito.when(bpmnProcessProperties.getById(Mockito.anyString()))
                .thenAnswer(i -> processes.getOrDefault(i.getArgument(0), new BpmnProcess("UNKNOWN")));

        InflightTransferManager transfers = new InflightTransferManager();
        set(transfers, "transferRepository", transferRepository);
        InflightTransactionRequestManager transactionRequests = new InflightTransactionRequestManager();
        set(transactionRequests, "transactionRequestRepository", transactionRequestRepository);
        set(transactionRequests, "tempDocumentStore", tempDocumentStore);
        InflightBatchManager batches = new InflightBatchManager();
        set(batches, "batchRepository", batchRepository);

        WorkflowGenerationResolver resolver = new WorkflowGenerationResolver();
        resolver.setTransferType("TRANSFER");
        resolver.setTransactionRequestType("TRANSACTION-REQUEST");
        resolver.setBatchType("BATCH");
        resolver.setBpmnProcessProperties(bpmnProcessProperties);
        resolver.setInflightTransferManager(transfers);
        resolver.setInflightTransactionRequestManager(transactionRequests);
        resolver.setInflightBatchManager(batches);

        VariableParser variableParser = new VariableParser();
        set(variableParser, "incomingDirection", "INCOMING");
        set(variableParser, "outgoingDirection", "OUTGOING");

        recordParser = new RecordParser();
        set(recordParser, "transferType", "TRANSFER");
        set(recordParser, "transactionRequestType", "TRANSACTION-REQUEST");
        set(recordParser, "batchType", "BATCH");
        set(recordParser, "outgoingDirection", "OUTGOING");
        set(recordParser, "taskRepository", taskRepository);
        set(recordParser, "variableRepository", variableRepository);
        set(recordParser, "transferRepository", transferRepository);
        set(recordParser, "transactionRequestRepository", transactionRequestRepository);
        set(recordParser, "batchRepository", batchRepository);
        set(recordParser, "bpmnProcessProperties", bpmnProcessProperties);
        set(recordParser, "inflightTransferManager", transfers);
        set(recordParser, "inflightTransactionRequestManager", transactionRequests);
        set(recordParser, "inflightBatchManager", batches);
        set(recordParser, "variableParser", variableParser);
        set(recordParser, "callActivityLinkRepository", callActivityLinkRepository);
        set(recordParser, "workflowGenerationResolver", resolver);
    }

    @After
    public void tearDown() {
        ThreadLocalContextUtil.clear();
    }

    // ---------- behaviour that must be the same before and after the change ----------

    @Test
    public void transactionRequestLifecycleWritesOneRow() {
        mpesaRun(KEY, T, "tx-1", true);

        List<TransactionRequest> rows = rows(TransactionRequest.class);
        assertEquals(1, rows.size());
        TransactionRequest row = rows.get(0);
        assertEquals(Long.valueOf(0L), row.getZeebeGeneration());
        assertEquals("tx-1", row.getTransactionId());
        assertEquals("INCOMING", row.getDirection());
        assertEquals(T, row.getStartedAt().getTime());
        assertEquals(T + 60_000L, row.getCompletedAt().getTime());
        assertEquals(2, rows(Variable.class).size());
        verify(tempDocumentStore, times(1)).deleteDocument(KEY);
    }

    @Test
    public void redeliveredTransactionRequestRunAddsNothing() {
        mpesaRun(KEY, T, "tx-1", true);
        recordParser.clearInflightState(); // as after a rebalance
        mpesaRun(KEY, T, "tx-1", true);

        assertEquals(1, rows(TransactionRequest.class).size());
        assertEquals(T + 60_000L, rows(TransactionRequest.class).get(0).getCompletedAt().getTime());
        assertEquals(2, rows(Variable.class).size());
    }

    @Test
    public void completedRunThenRecycledKeyOpensGenerationOne() {
        mpesaRun(KEY, T, "tx-1", true);
        mpesaRun(KEY, T + 30 * DAY, "tx-2", true);

        List<TransactionRequest> rows = rows(TransactionRequest.class);
        assertEquals(2, rows.size());
        assertEquals("tx-1", generation(rows, 0L).getTransactionId());
        assertEquals("tx-2", generation(rows, 1L).getTransactionId());
    }

    @Test
    public void variableProcessedBeforeTheStartStillLandsInTheSameRow() {
        importVariable(KEY, "mpesa_flow_tupande", "transactionId", "\"tx-1\"", T + 1_000L);
        process(KEY, "mpesa_flow_tupande-oaf", "ELEMENT_ACTIVATING", T, -1L);
        process(KEY, "mpesa_flow_tupande-oaf", "ELEMENT_COMPLETED", T + 60_000L, -1L);

        List<TransactionRequest> rows = rows(TransactionRequest.class);
        assertEquals(1, rows.size());
        assertEquals("tx-1", rows.get(0).getTransactionId());
        assertEquals("INCOMING", rows.get(0).getDirection());
        assertEquals(T + 60_000L, rows.get(0).getCompletedAt().getTime());
    }

    @Test
    public void transferWithCallActivityChildKeepsOneParentRow() {
        process(KEY, "PayerFundTransfer-oaf", "ELEMENT_ACTIVATING", T, -1L);
        process(CHILD_KEY, "PayerFundTransfer-oaf", "ELEMENT_ACTIVATING", T + 5_000L, KEY);
        importVariable(CHILD_KEY, "PayerFundTransfer", "transactionId", "\"tx-1\"", T + 6_000L);
        importVariable(KEY, "PayerFundTransfer", "quoteId", "\"q-1\"", T + 7_000L);
        process(KEY, "PayerFundTransfer-oaf", "ELEMENT_COMPLETED", T + 20_000L, -1L);

        // RecordParser.touchEntityStartedAt uses the child's own key, so a child variable also
        // leaves a bare transfer row under CHILD_KEY. That predates the generation change (same
        // result on the previous implementation), so only the parent row is asserted here.
        List<Transfer> rows = rows(Transfer.class).stream()
                .filter(r -> r.getWorkflowInstanceKey() == KEY).collect(Collectors.toList());
        assertEquals(1, rows.size());
        Transfer row = rows.get(0);
        assertEquals(Long.valueOf(0L), row.getZeebeGeneration());
        assertEquals("tx-1", row.getTransactionId());
        assertEquals("q-1", row.getPayeeQuoteCode());
        assertEquals("OUTGOING", row.getDirection());
        assertEquals(T, row.getStartedAt().getTime());
        assertEquals(T + 20_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void batchLifecycleWritesOneRow() {
        bulkRun(KEY, T, "batch-1", true);

        List<Batch> rows = rows(Batch.class);
        assertEquals(1, rows.size());
        assertEquals("batch-1", rows.get(0).getBatchId());
        assertEquals("note batch-1", rows.get(0).getNote());
        assertEquals(T, rows.get(0).getStartedAt().getTime());
        assertEquals(T + 600_000L, rows.get(0).getCompletedAt().getTime());
    }

    // ---------- the reported overwrite: these fail on the previous implementation ----------

    @Test
    public void lostTransactionRequestIsNotOverwrittenByTheNextRunOfItsKey() {
        mpesaRun(KEY, T, "tx-1", false); // Zeebe wiped before ELEMENT_COMPLETED
        mpesaRun(KEY, T + 30 * DAY, "tx-2", true);

        List<TransactionRequest> rows = rows(TransactionRequest.class);
        assertEquals(2, rows.size());
        assertEquals("tx-1", generation(rows, 0L).getTransactionId());
        assertEquals("tx-2", generation(rows, 1L).getTransactionId());
        assertEquals(T + 30 * DAY + 60_000L, generation(rows, 1L).getCompletedAt().getTime());
    }

    @Test
    public void lostTransferIsNotOverwrittenByTheNextRunOfItsKey() {
        process(KEY, "PayerFundTransfer-oaf", "ELEMENT_ACTIVATING", T, -1L);
        importVariable(KEY, "PayerFundTransfer", "transactionId", "\"tx-1\"", T + 1_000L);

        process(KEY, "PayerFundTransfer-oaf", "ELEMENT_ACTIVATING", T + 30 * DAY, -1L);
        importVariable(KEY, "PayerFundTransfer", "transactionId", "\"tx-2\"", T + 30 * DAY + 1_000L);

        List<Transfer> rows = rows(Transfer.class);
        assertEquals(2, rows.size());
        assertEquals("tx-1", generation(rows, 0L).getTransactionId());
        assertEquals("tx-2", generation(rows, 1L).getTransactionId());
    }

    @Test
    public void lostBatchIsNotOverwrittenByTheNextRunOfItsKey() {
        bulkRun(KEY, T, "batch-1", false);
        bulkRun(KEY, T + 30 * DAY, "batch-2", true);

        List<Batch> rows = rows(Batch.class);
        assertEquals(2, rows.size());
        assertEquals("batch-1", generation(rows, 0L).getBatchId());
        assertEquals("batch-2", generation(rows, 1L).getBatchId());
    }

    @Test
    public void redeliveredRecordOfACompletedRunDoesNotCaptureTheNextRun() {
        mpesaRun(KEY, T, "tx-1", true);
        // a late Kafka redelivery of the first run's variable
        importVariable(KEY, "mpesa_flow_tupande", "transactionId", "\"tx-1\"", T + 1_000L);
        mpesaRun(KEY, T + 30 * DAY, "tx-2", true);

        List<TransactionRequest> rows = rows(TransactionRequest.class);
        assertEquals(2, rows.size());
        assertEquals("tx-1", generation(rows, 0L).getTransactionId());
        assertEquals(T + 60_000L, generation(rows, 0L).getCompletedAt().getTime());
        assertEquals("tx-2", generation(rows, 1L).getTransactionId());
    }

    // ---------- record helpers ----------

    private void mpesaRun(long key, long start, String transactionId, boolean completes) {
        process(key, "mpesa_flow_tupande-oaf", "ELEMENT_ACTIVATING", start, -1L);
        importVariable(key, "mpesa_flow_tupande", "transactionId", "\"" + transactionId + "\"", start + 1_000L);
        task(key, "mpesa_flow_tupande", "Task_send", "CREATED", start + 2_000L);
        importVariable(key, "mpesa_flow_tupande", "authType", "\"NONE\"", start + 3_000L);
        if (completes) {
            process(key, "mpesa_flow_tupande-oaf", "ELEMENT_COMPLETED", start + 60_000L, -1L);
        }
    }

    private void bulkRun(long key, long start, String batchId, boolean completes) {
        process(key, "bulk_processor-ibank-usa", "ELEMENT_ACTIVATING", start, -1L);
        importVariable(key, "bulk_processor", "batchId", "\"" + batchId + "\"", start + 1_000L);
        importVariable(key, "bulk_processor", "note", "\"note " + batchId + "\"", start + 2_000L);
        if (completes) {
            process(key, "bulk_processor-ibank-usa", "ELEMENT_COMPLETED", start + 600_000L, -1L);
        }
    }

    private void importVariable(long key, String bpmnProcessId, String name, String value, long ts) {
        run(ts, () -> recordParser.importVariable(JsonPathReader.parse("{\"valueType\":\"VARIABLE\",\"value\":{"
                + "\"processInstanceKey\":" + key + ",\"processDefinitionKey\":1,"
                + "\"name\":\"" + name + "\",\"value\":" + quote(value) + "},"
                + "\"timestamp\":" + ts + "}"), bpmnProcessId));
    }

    private void task(long key, String bpmnProcessId, String elementId, String intent, long ts) {
        run(ts, () -> recordParser.processTask(JsonPathReader.parse("{\"valueType\":\"JOB\",\"value\":{"
                + "\"type\":\"worker\",\"processInstanceKey\":" + key + ",\"processDefinitionKey\":1,"
                + "\"elementId\":\"" + elementId + "\"},"
                + "\"timestamp\":" + ts + ",\"intent\":\"" + intent + "\",\"recordType\":\"EVENT\"}"), bpmnProcessId));
    }

    private void process(long key, String bpmnProcessIdWithTenant, String intent, long ts, long parentKey) {
        run(ts, () -> recordParser.processWorkflowInstance(JsonPathReader.parse("{\"valueType\":\"PROCESS_INSTANCE\",\"value\":{"
                + "\"bpmnProcessId\":\"" + bpmnProcessIdWithTenant + "\",\"processInstanceKey\":" + key + ","
                + "\"parentProcessInstanceKey\":" + parentKey + ",\"bpmnElementType\":\"PROCESS\",\"elementId\":\"root\"},"
                + "\"timestamp\":" + ts + ",\"intent\":\"" + intent + "\",\"key\":" + key + "}")));
    }

    /** As KafkaConsumer.processDocument: the record's timestamp is visible to the managers. */
    private void run(long ts, Runnable action) {
        ThreadLocalContextUtil.setRecordTimestamp(ts);
        try {
            action.run();
        } finally {
            ThreadLocalContextUtil.clear();
        }
    }

    private static String quote(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static BpmnProcess process(String id, String type, String direction) {
        BpmnProcess process = new BpmnProcess();
        process.setId(id);
        process.setType(type);
        process.setDirection(direction);
        return process;
    }

    // ---------- in-memory database ----------

    @SuppressWarnings("unchecked")
    private <T> List<T> rows(Class<T> type) {
        return (List<T>) new ArrayList<>(db.getOrDefault(type, new ArrayList<>()));
    }

    private static <T> T generation(List<T> rows, Long zeebeGeneration) {
        return rows.stream().filter(r -> Objects.equals(read(r, "getZeebeGeneration"), zeebeGeneration))
                .findFirst().orElseThrow(() -> new AssertionError("no row for generation " + zeebeGeneration));
    }

    /** Spring Data derived queries the old and the new managers use, answered from an in-memory table. */
    private <R> R inMemory(Class<R> repositoryType, Class<?> entityType) {
        db.put(entityType, new ArrayList<>());
        return Mockito.mock(repositoryType, invocation -> answer(invocation, entityType));
    }

    private Object answer(InvocationOnMock invocation, Class<?> entityType) throws Throwable {
        String method = invocation.getMethod().getName();
        List<Object> table = db.get(entityType);
        Object[] args = invocation.getArguments();
        if (method.equals("save")) {
            Object entity = args[0];
            if (read(entity, "getId") == null) {
                entity.getClass().getMethod("setId", Long.class).invoke(entity, nextId++);
            }
            if (!table.contains(entity)) {
                table.add(entity);
            }
            return entity;
        }
        if (method.equals("findByChildInstanceKey")) {
            return table.stream().filter(r -> Objects.equals(read(r, "getChildInstanceKey"), args[0])).findFirst().orElse(null);
        }
        List<Object> byKey = table.stream()
                .filter(r -> Objects.equals(read(r, "getWorkflowInstanceKey"), args[0]))
                .collect(Collectors.toList());
        Comparator<Object> byGeneration = Comparator.comparing(r -> (Long) read(r, "getZeebeGeneration"),
                Comparator.nullsFirst(Comparator.naturalOrder()));
        switch (method) {
            case "findByWorkflowInstanceKeyOrderByZeebeGenerationAsc":
                return byKey.stream().sorted(byGeneration).collect(Collectors.toList());
            case "findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc":
                return byKey.stream().filter(r -> read(r, "getCompletedAt") == null)
                        .max(Comparator.comparing(r -> (Long) read(r, "getId"))).orElse(null);
            case "findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc":
                return byKey.stream().max(byGeneration).orElse(null);
            case "findByWorkflowInstanceKeyAndZeebeGeneration":
                List<Object> matching = byKey.stream()
                        .filter(r -> Objects.equals(read(r, "getZeebeGeneration"), args[1]))
                        .collect(Collectors.toList());
                return List.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? matching : matching.stream().findFirst().orElse(null);
            default:
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }
    }

    private static Object read(Object target, String getter) {
        try {
            Method method = target.getClass().getMethod(getter);
            return method.invoke(target);
        } catch (Exception e) {
            throw new IllegalStateException(getter + " on " + target.getClass(), e);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep looking in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
