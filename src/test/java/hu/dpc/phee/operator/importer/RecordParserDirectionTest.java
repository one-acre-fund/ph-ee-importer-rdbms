package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.callactivity.CallActivityLinkRepository;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.variable.VariableRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.util.Pair;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A transfer must get its direction even when a VARIABLE record is processed before the
 * process ELEMENT_ACTIVATING record (which used to be the only place direction was written).
 */
@RunWith(MockitoJUnitRunner.class)
public class RecordParserDirectionTest {

    private static final Long KEY = 11258999074964344L;
    private static final long TS = 1793875160353L;
    private static final String BPMN = "inbound_bancobu_fineract";

    @Mock
    private BpmnProcessProperties bpmnProcessProperties;
    @Mock
    private InflightTransferManager inflightTransferManager;
    @Mock
    private VariableParser variableParser;
    @Mock
    private VariableRepository variableRepository;
    @Mock
    private WorkflowGenerationResolver workflowGenerationResolver;
    @Mock
    private CallActivityLinkRepository callActivityLinkRepository;

    @InjectMocks
    private RecordParser recordParser;

    @Before
    public void setUp() throws Exception {
        setField(recordParser, "transferType", "TRANSFER");
        setField(recordParser, "transactionRequestType", "TRANSACTION-REQUEST");
        setField(recordParser, "batchType", "BATCH");
        setField(recordParser, "outgoingDirection", "OUTGOING");

        BpmnProcess process = new BpmnProcess();
        process.setId(BPMN);
        process.setType("TRANSFER");
        process.setDirection("INCOMING");
        when(bpmnProcessProperties.getById(BPMN)).thenReturn(process);
    }

    @Test
    public void variableBeforeProcessStartSetsDirectionAndStartedAt() {
        Transfer transfer = new Transfer(KEY, 0L);
        when(inflightTransferManager.getOrCreateTransfer(KEY)).thenReturn(transfer);

        recordParser.processVariable(settlementFailedVariable(), BPMN);

        assertEquals("INCOMING", transfer.getDirection());
        assertEquals(TS, transfer.getStartedAt().getTime());
        verify(inflightTransferManager).save(transfer);
    }

    @Test
    public void variableBackfillsDirectionOnRowThatIsAlreadyStarted() {
        // Shape of the rows written before the fix: startedAt set, direction NULL.
        Transfer transfer = new Transfer(KEY, 0L);
        transfer.setStartedAt(new Date(500L));
        when(inflightTransferManager.getOrCreateTransfer(KEY)).thenReturn(transfer);

        recordParser.processVariable(settlementFailedVariable(), BPMN);

        assertEquals("INCOMING", transfer.getDirection());
        assertEquals(500L, transfer.getStartedAt().getTime());
        verify(inflightTransferManager).save(transfer);
    }

    @Test
    public void variableDoesNotSaveTransferThatAlreadyHasStartAndDirection() {
        Transfer transfer = new Transfer(KEY, 0L);
        transfer.setStartedAt(new Date(500L));
        transfer.setDirection("OUTGOING");
        when(inflightTransferManager.getOrCreateTransfer(KEY)).thenReturn(transfer);

        recordParser.processVariable(settlementFailedVariable(), BPMN);

        assertEquals("OUTGOING", transfer.getDirection());
        verify(inflightTransferManager, never()).save(any(Transfer.class));
    }

    @Test
    public void addVariableToEntityBackfillsMissingDirection() {
        Transfer transfer = new Transfer(KEY, 0L);
        when(inflightTransferManager.getOrCreateTransfer(KEY)).thenReturn(transfer);
        stubTransactionIdParser();

        recordParser.addVariableToEntity(transactionIdVariable(), BPMN);

        assertEquals("INCOMING", transfer.getDirection());
        assertEquals("766bf335", transfer.getTransactionId());
        verify(inflightTransferManager).save(transfer);
    }

    @Test
    public void addVariableToEntityKeepsDirectionAlreadySet() {
        // e.g. OUTGOING written for the parent when a call-activity child started.
        Transfer transfer = new Transfer(KEY, 0L);
        transfer.setDirection("OUTGOING");
        when(inflightTransferManager.getOrCreateTransfer(KEY)).thenReturn(transfer);
        stubTransactionIdParser();

        recordParser.addVariableToEntity(transactionIdVariable(), BPMN);

        assertEquals("OUTGOING", transfer.getDirection());
    }

    private void stubTransactionIdParser() {
        Map<String, Consumer<Pair<Transfer, String>>> parsers = Collections.singletonMap(
                "transactionId", pair -> pair.getFirst().setTransactionId(pair.getSecond()));
        when(variableParser.getTransferParsers()).thenReturn(parsers);
    }

    /** Taken from the inbound_bancobu_fineract-burundi export on partition 5. */
    private static DocumentContext settlementFailedVariable() {
        return JsonPathReader.parse("{"
                + "\"valueType\":\"VARIABLE\",\"intent\":\"CREATED\","
                + "\"timestamp\":" + TS + ",\"key\":11258999074964465,"
                + "\"value\":{"
                + "\"processInstanceKey\":" + KEY + ","
                + "\"processDefinitionKey\":2251799813685423,"
                + "\"bpmnProcessId\":\"inbound_bancobu_fineract-burundi\","
                + "\"scopeKey\":" + KEY + ","
                + "\"name\":\"transferSettlementFailed\",\"value\":\"false\""
                + "}}");
    }

    private static DocumentContext transactionIdVariable() {
        return JsonPathReader.parse("{"
                + "\"timestamp\":" + TS + ","
                + "\"value\":{"
                + "\"processInstanceKey\":" + KEY + ","
                + "\"name\":\"transactionId\",\"value\":\"766bf335\""
                + "}}");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
