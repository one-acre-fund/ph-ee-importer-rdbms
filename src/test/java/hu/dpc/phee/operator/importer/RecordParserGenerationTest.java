package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.task.Task;
import hu.dpc.phee.operator.entity.task.TaskRepository;
import hu.dpc.phee.operator.entity.variable.Variable;
import hu.dpc.phee.operator.entity.variable.VariableRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RecordParserGenerationTest {

    private static final Long KEY = 6755399441058311L;
    private static final Long PARENT_KEY = 2251799813686963L;
    /** Timestamps must exceed Integer.MAX_VALUE so JsonPath returns Long (matches Zeebe event sizes). */
    private static final long TS = 1609459200000L;
    private static final long TS_OLDER = 1609459100000L;
    private static final long TS_NEWER = 1609459300000L;

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private VariableRepository variableRepository;
    @Mock
    private BpmnProcessProperties bpmnProcessProperties;
    @Mock
    private InflightTransferManager inflightTransferManager;
    @Mock
    private InflightTransactionRequestManager inflightTransactionRequestManager;
    @Mock
    private InflightBatchManager inflightBatchManager;
    @Mock
    private WorkflowGenerationResolver workflowGenerationResolver;

    @InjectMocks
    private RecordParser recordParser;

    @Before
    public void setUp() throws Exception {
        setField(recordParser, "transferType", "TRANSFER");
        setField(recordParser, "transactionRequestType", "TRANSACTION-REQUEST");
        setField(recordParser, "batchType", "BATCH");
        setField(recordParser, "outgoingDirection", "OUTGOING");
    }

    @Test
    public void processVariableStampsGenerationAndSaves() {
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", KEY)).thenReturn(2L);
        when(variableRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 2L)).thenReturn(Collections.emptyList());

        DocumentContext result = recordParser.processVariable(variableJson(KEY, "transactionId", TS), "PayerFundTransfer");

        assertNotNull(result);
        ArgumentCaptor<Variable> captor = ArgumentCaptor.forClass(Variable.class);
        verify(variableRepository).save(captor.capture());
        Variable saved = captor.getValue();
        assertEquals(KEY, saved.getWorkflowInstanceKey());
        assertEquals(Long.valueOf(2L), saved.getZeebeGeneration());
        assertEquals("transactionId", saved.getName());
        assertEquals(Long.valueOf(TS), saved.getTimestamp());
        verify(workflowGenerationResolver).ensureGeneration("PayerFundTransfer", KEY);
    }

    @Test
    public void processVariableSkipsOlderDuplicateWithinSameGeneration() {
        Variable existing = new Variable();
        existing.setName("transactionId");
        existing.setTimestamp(TS_NEWER);
        existing.setZeebeGeneration(1L);
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", KEY)).thenReturn(1L);
        when(variableRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 1L))
                .thenReturn(Collections.singletonList(existing));

        DocumentContext result = recordParser.processVariable(variableJson(KEY, "transactionId", TS_OLDER), "PayerFundTransfer");

        assertNull(result);
        verify(variableRepository, never()).save(any(Variable.class));
    }

    @Test
    public void processVariableAllowsSameNameInNewGeneration() {
        Variable oldEra = new Variable();
        oldEra.setName("transactionId");
        oldEra.setTimestamp(TS_NEWER);
        oldEra.setZeebeGeneration(0L);
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", KEY)).thenReturn(1L);
        when(variableRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 1L)).thenReturn(Collections.emptyList());

        DocumentContext result = recordParser.processVariable(variableJson(KEY, "transactionId", TS), "PayerFundTransfer");

        assertNotNull(result);
        ArgumentCaptor<Variable> captor = ArgumentCaptor.forClass(Variable.class);
        verify(variableRepository).save(captor.capture());
        assertEquals(Long.valueOf(1L), captor.getValue().getZeebeGeneration());
    }

    @Test
    public void processVariableUsesParentKeyForGenerationWhenCallActivityMapped() throws Exception {
        callActivities().put(KEY, PARENT_KEY);
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", PARENT_KEY)).thenReturn(5L);
        when(variableRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 5L)).thenReturn(Collections.emptyList());

        recordParser.processVariable(variableJson(KEY, "amount", TS), "PayerFundTransfer");

        ArgumentCaptor<Variable> captor = ArgumentCaptor.forClass(Variable.class);
        verify(variableRepository).save(captor.capture());
        assertEquals(KEY, captor.getValue().getWorkflowInstanceKey());
        assertEquals(Long.valueOf(5L), captor.getValue().getZeebeGeneration());
        verify(workflowGenerationResolver).ensureGeneration("PayerFundTransfer", PARENT_KEY);
    }

    @Test
    public void processTaskStampsGenerationAndSaves() {
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", KEY)).thenReturn(3L);
        when(taskRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 3L)).thenReturn(Collections.emptyList());

        recordParser.processTask(taskJson(KEY, "Task_1", "CREATED", TS), "PayerFundTransfer");

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(captor.capture());
        Task saved = captor.getValue();
        assertEquals(KEY, saved.getWorkflowInstanceKey());
        assertEquals(Long.valueOf(3L), saved.getZeebeGeneration());
        assertEquals("Task_1", saved.getElementId());
        assertEquals("CREATED", saved.getIntent());
    }

    @Test
    public void processTaskSkipsDuplicateElementAndIntentWithinGeneration() {
        Task existing = new Task();
        existing.setElementId("Task_1");
        existing.setIntent("CREATED");
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", KEY)).thenReturn(0L);
        when(taskRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 0L))
                .thenReturn(Collections.singletonList(existing));

        recordParser.processTask(taskJson(KEY, "Task_1", "CREATED", TS), "PayerFundTransfer");

        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    public void processTaskAllowsSameElementInNewGeneration() {
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", KEY)).thenReturn(1L);
        when(taskRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 1L)).thenReturn(Collections.emptyList());

        recordParser.processTask(taskJson(KEY, "Task_1", "CREATED", TS), "PayerFundTransfer");

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(captor.capture());
        assertEquals(Long.valueOf(1L), captor.getValue().getZeebeGeneration());
    }

    @Test
    public void processTaskIgnoresNullType() {
        DocumentContext json = JsonPathReader.parse("{"
                + "\"value\":{\"type\":null,\"processInstanceKey\":" + KEY + ",\"elementId\":\"x\"},"
                + "\"timestamp\":" + TS + ",\"intent\":\"CREATED\",\"recordType\":\"EVENT\""
                + "}");

        recordParser.processTask(json, "PayerFundTransfer");

        verify(workflowGenerationResolver, never()).ensureGeneration(any(), anyLong());
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    public void processWorkflowInstanceStartsAndEndsTransfer() {
        BpmnProcess process = new BpmnProcess();
        process.setId("PayerFundTransfer");
        process.setType("TRANSFER");
        process.setDirection("OUTGOING");
        when(bpmnProcessProperties.getById("PayerFundTransfer")).thenReturn(process);

        recordParser.processWorkflowInstance(processJson(KEY, "PayerFundTransfer-tn01", "ELEMENT_ACTIVATING", -1L));
        verify(inflightTransferManager).transferStarted(KEY, TS, "OUTGOING");

        recordParser.processWorkflowInstance(processJson(KEY, "PayerFundTransfer-tn01", "ELEMENT_COMPLETED", -1L));
        verify(inflightTransferManager).transferEnded(KEY, TS);
    }

    @Test
    public void processWorkflowInstanceTransactionRequestLifecycle() {
        BpmnProcess process = new BpmnProcess();
        process.setId("mpesa_flow");
        process.setType("TRANSACTION-REQUEST");
        process.setDirection("INCOMING");
        when(bpmnProcessProperties.getById("mpesa_flow")).thenReturn(process);

        recordParser.processWorkflowInstance(processJson(KEY, "mpesa_flow-tn01", "ELEMENT_ACTIVATING", -1L));
        verify(inflightTransactionRequestManager).transactionRequestStarted(KEY, TS, "INCOMING");

        recordParser.processWorkflowInstance(processJson(KEY, "mpesa_flow-tn01", "ELEMENT_COMPLETED", -1L));
        verify(inflightTransactionRequestManager).transactionRequestEnded(KEY, TS);
    }

    @Test
    public void processWorkflowInstanceBatchLifecycle() {
        BpmnProcess process = new BpmnProcess();
        process.setId("bulk_processor");
        process.setType("BATCH");
        process.setDirection("UNKNOWN");
        when(bpmnProcessProperties.getById("bulk_processor")).thenReturn(process);

        recordParser.processWorkflowInstance(processJson(KEY, "bulk_processor-tn01", "ELEMENT_ACTIVATING", -1L));
        verify(inflightBatchManager).batchStarted(KEY, TS, "UNKNOWN");

        recordParser.processWorkflowInstance(processJson(KEY, "bulk_processor-tn01", "ELEMENT_COMPLETED", -1L));
        verify(inflightBatchManager).batchEnded(KEY, TS);
    }

    @Test
    public void processWorkflowInstanceMapsCallActivityToParent() throws Exception {
        BpmnProcess process = new BpmnProcess();
        process.setId("PayerFundTransfer");
        process.setType("TRANSFER");
        process.setDirection("OUTGOING");
        when(bpmnProcessProperties.getById("PayerFundTransfer")).thenReturn(process);

        Long childKey = 6755399441058999L;
        DocumentContext activating = JsonPathReader.parse("{"
                + "\"value\":{"
                + "\"bpmnProcessId\":\"PayerFundTransfer-tn01\","
                + "\"processInstanceKey\":" + childKey + ","
                + "\"parentProcessInstanceKey\":" + PARENT_KEY + ","
                + "\"bpmnElementType\":\"PROCESS\","
                + "\"elementId\":\"call\""
                + "},"
                + "\"timestamp\":" + TS + ","
                + "\"intent\":\"ELEMENT_ACTIVATING\","
                + "\"key\":" + childKey
                + "}");

        recordParser.processWorkflowInstance(activating);

        verify(inflightTransferManager).transferStarted(PARENT_KEY, TS, "OUTGOING");
        assertEquals(PARENT_KEY, callActivities().get(childKey));
    }

    private static DocumentContext variableJson(Long key, String name, long timestamp) {
        return JsonPathReader.parse("{"
                + "\"value\":{"
                + "\"processInstanceKey\":" + key + ","
                + "\"name\":\"" + name + "\","
                + "\"value\":\"\\\"abc\\\"\","
                + "\"processDefinitionKey\":2251799813687425"
                + "},"
                + "\"timestamp\":" + timestamp
                + "}");
    }

    private static DocumentContext taskJson(Long key, String elementId, String intent, long timestamp) {
        return JsonPathReader.parse("{"
                + "\"value\":{"
                + "\"type\":\"worker\","
                + "\"processInstanceKey\":" + key + ","
                + "\"elementId\":\"" + elementId + "\","
                + "\"processDefinitionKey\":2251799813687425"
                + "},"
                + "\"timestamp\":" + timestamp + ","
                + "\"intent\":\"" + intent + "\","
                + "\"recordType\":\"EVENT\""
                + "}");
    }

    private static DocumentContext processJson(Long key, String bpmnProcessId, String intent, long parentKey) {
        return JsonPathReader.parse("{"
                + "\"value\":{"
                + "\"bpmnProcessId\":\"" + bpmnProcessId + "\","
                + "\"processInstanceKey\":" + key + ","
                + "\"parentProcessInstanceKey\":" + parentKey + ","
                + "\"bpmnElementType\":\"PROCESS\","
                + "\"elementId\":\"root\""
                + "},"
                + "\"timestamp\":" + TS + ","
                + "\"intent\":\"" + intent + "\","
                + "\"key\":" + key
                + "}");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> callActivities() throws Exception {
        Field field = RecordParser.class.getDeclaredField("inflightCallActivities");
        field.setAccessible(true);
        return (Map<Long, Long>) field.get(recordParser);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
