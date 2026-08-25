package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import hu.dpc.phee.operator.entity.transfer.TransferStatus;
import hu.dpc.phee.operator.entity.variable.VariableRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.util.Pair;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RecordParserTimestampTest {

    private static final Long KEY = 2251799813793852L;
    private static final long TS = 1_782_808_205_631L;

    @Mock
    private VariableRepository variableRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private BpmnProcessProperties bpmnProcessProperties;
    @Mock
    private InflightTransferManager inflightTransferManager;
    @Mock
    private WorkflowGenerationResolver workflowGenerationResolver;
    @Mock
    private VariableParser variableParser;

    @InjectMocks
    private RecordParser recordParser;

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(recordParser, "transferType", "TRANSFER");
        ReflectionTestUtils.setField(recordParser, "transactionRequestType", "TRANSACTION-REQUEST");
        ReflectionTestUtils.setField(recordParser, "batchType", "BATCH");

        BpmnProcess process = new BpmnProcess();
        process.setId("inbound_bancobu_fineract");
        process.setType("TRANSFER");
        process.setDirection("INCOMING");
        when(bpmnProcessProperties.getById("inbound_bancobu_fineract")).thenReturn(process);
        when(workflowGenerationResolver.ensureGeneration(anyString(), anyLong())).thenReturn(0L);
        when(variableRepository.findByWorkflowInstanceKeyAndZeebeGeneration(anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());

        Transfer transfer = new Transfer(KEY, 0L);
        when(inflightTransferManager.getOrCreateTransfer(KEY)).thenReturn(transfer);

        Map<String, Consumer<Pair<Transfer, String>>> parsers = new HashMap<>();
        parsers.put("transferCreateFailed", pair -> pair.getFirst().setStatus(
                "false".equals(pair.getSecond()) ? TransferStatus.COMPLETED : TransferStatus.FAILED));
        when(variableParser.getTransferParsers()).thenReturn(parsers);
    }

    @Test
    public void processWorkflowInstanceUsesResolvedBpmnWhenRecordHasNoBpmnProcessId() {
        DocumentContext event = JsonPathReader.parse("{"
                + "\"valueType\":\"WORKFLOW_INSTANCE\","
                + "\"timestamp\":" + TS + ","
                + "\"intent\":\"ELEMENT_ACTIVATING\","
                + "\"key\":" + KEY + ","
                + "\"value\":{"
                + "\"bpmnElementType\":\"PROCESS\","
                + "\"workflowInstanceKey\":" + KEY + ","
                + "\"parentWorkflowInstanceKey\":-1"
                + "}"
                + "}");

        recordParser.processWorkflowInstance(event, "inbound_bancobu_fineract");

        verify(inflightTransferManager).transferStarted(KEY, TS, "INCOMING");
    }

    @Test
    public void addVariableToEntitySetsTimestampsWhenStatusBecomesCompleted() {
        DocumentContext variable = JsonPathReader.parse("{"
                + "\"timestamp\":" + TS + ","
                + "\"value\":{"
                + "\"processInstanceKey\":" + KEY + ","
                + "\"name\":\"transferCreateFailed\","
                + "\"value\":\"false\""
                + "}"
                + "}");

        recordParser.addVariableToEntity(variable, "inbound_bancobu_fineract");

        verify(transferRepository).save(any(Transfer.class));
        Transfer saved = inflightTransferManager.getOrCreateTransfer(KEY);
        assertEquals(TransferStatus.COMPLETED, saved.getStatus());
        assertNotNull(saved.getStartedAt());
        assertNotNull(saved.getCompletedAt());
    }
}
