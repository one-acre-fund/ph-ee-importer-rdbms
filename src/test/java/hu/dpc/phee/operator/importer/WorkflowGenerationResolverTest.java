package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class WorkflowGenerationResolverTest {

    private static final Long KEY = 55L;

    @Mock
    private BpmnProcessProperties bpmnProcessProperties;

    @Mock
    private InflightTransferManager inflightTransferManager;

    @Mock
    private InflightTransactionRequestManager inflightTransactionRequestManager;

    @Mock
    private InflightBatchManager inflightBatchManager;

    private WorkflowGenerationResolver resolver;

    @Before
    public void setUp() {
        resolver = new WorkflowGenerationResolver();
        resolver.setTransferType("TRANSFER");
        resolver.setTransactionRequestType("TRANSACTION-REQUEST");
        resolver.setBatchType("BATCH");
        resolver.setBpmnProcessProperties(bpmnProcessProperties);
        resolver.setInflightTransferManager(inflightTransferManager);
        resolver.setInflightTransactionRequestManager(inflightTransactionRequestManager);
        resolver.setInflightBatchManager(inflightBatchManager);
    }

    @Test
    public void transferTypeUsesGetOrCreateSoVariableBeforeStartOpensGeneration() {
        BpmnProcess process = new BpmnProcess();
        process.setId("mpesa_flow");
        process.setType("TRANSACTION-REQUEST");
        when(bpmnProcessProperties.getById("mpesa_flow")).thenReturn(process);

        TransactionRequest request = new TransactionRequest(KEY, 1L);
        when(inflightTransactionRequestManager.getOrCreateTransactionRequest(KEY)).thenReturn(request);

        Long generation = resolver.ensureGeneration("mpesa_flow", KEY);

        assertEquals(Long.valueOf(1L), generation);
        verify(inflightTransactionRequestManager).getOrCreateTransactionRequest(KEY);
        verify(inflightTransferManager, never()).getOrCreateTransfer(KEY);
        verify(inflightBatchManager, never()).getOrCreateBatch(KEY);
    }

    @Test
    public void transferTypeDelegatesToTransferManager() {
        BpmnProcess process = new BpmnProcess();
        process.setId("PayerFundTransfer");
        process.setType("TRANSFER");
        when(bpmnProcessProperties.getById("PayerFundTransfer")).thenReturn(process);
        when(inflightTransferManager.getOrCreateTransfer(KEY)).thenReturn(new Transfer(KEY, 0L));

        assertEquals(Long.valueOf(0L), resolver.ensureGeneration("PayerFundTransfer", KEY));
        verify(inflightTransferManager).getOrCreateTransfer(KEY);
    }

    @Test
    public void batchTypeDelegatesToBatchManager() {
        BpmnProcess process = new BpmnProcess();
        process.setId("bulk_processor");
        process.setType("BATCH");
        when(bpmnProcessProperties.getById("bulk_processor")).thenReturn(process);
        when(inflightBatchManager.getOrCreateBatch(KEY)).thenReturn(new Batch(KEY, 4L));

        assertEquals(Long.valueOf(4L), resolver.ensureGeneration("bulk_processor", KEY));
        verify(inflightBatchManager).getOrCreateBatch(KEY);
    }

    @Test
    public void unknownTypePeeksInflightOnlyAndDefaultsToZero() {
        when(bpmnProcessProperties.getById("unknown")).thenReturn(new BpmnProcess("UNKNOWN"));
        when(inflightTransferManager.peekGeneration(KEY)).thenReturn(null);
        when(inflightTransactionRequestManager.peekGeneration(KEY)).thenReturn(null);
        when(inflightBatchManager.peekGeneration(KEY)).thenReturn(null);

        assertEquals(Long.valueOf(0L), resolver.ensureGeneration("unknown", KEY));

        verify(inflightTransferManager, never()).getOrCreateTransfer(KEY);
        verify(inflightTransactionRequestManager, never()).getOrCreateTransactionRequest(KEY);
        verify(inflightBatchManager, never()).getOrCreateBatch(KEY);
        verify(inflightTransferManager).peekGeneration(KEY);
    }

    @Test
    public void unknownTypeUsesInflightPeekWhenPresent() {
        when(bpmnProcessProperties.getById("unknown")).thenReturn(new BpmnProcess("UNKNOWN"));
        when(inflightTransferManager.peekGeneration(KEY)).thenReturn(null);
        when(inflightTransactionRequestManager.peekGeneration(KEY)).thenReturn(7L);

        assertEquals(Long.valueOf(7L), resolver.ensureGeneration("unknown", KEY));
        verify(inflightBatchManager, never()).peekGeneration(KEY);
    }

    @Test
    public void unknownTypePrefersTransferPeekOverOthers() {
        when(bpmnProcessProperties.getById("unknown")).thenReturn(new BpmnProcess("UNKNOWN"));
        when(inflightTransferManager.peekGeneration(KEY)).thenReturn(3L);

        assertEquals(Long.valueOf(3L), resolver.ensureGeneration("unknown", KEY));
        verify(inflightTransactionRequestManager, never()).peekGeneration(KEY);
        verify(inflightBatchManager, never()).peekGeneration(KEY);
    }

    @Test
    public void unknownTypeFallsThroughToBatchPeek() {
        when(bpmnProcessProperties.getById("unknown")).thenReturn(new BpmnProcess("UNKNOWN"));
        when(inflightTransferManager.peekGeneration(KEY)).thenReturn(null);
        when(inflightTransactionRequestManager.peekGeneration(KEY)).thenReturn(null);
        when(inflightBatchManager.peekGeneration(KEY)).thenReturn(11L);

        assertEquals(Long.valueOf(11L), resolver.ensureGeneration("unknown", KEY));
    }

    @Test
    public void peekInflightOrZeroDefaultsWhenNothingInflight() {
        when(inflightTransferManager.peekGeneration(KEY)).thenReturn(null);
        when(inflightTransactionRequestManager.peekGeneration(KEY)).thenReturn(null);
        when(inflightBatchManager.peekGeneration(KEY)).thenReturn(null);

        assertEquals(Long.valueOf(0L), resolver.peekInflightOrZero(KEY));
    }
}
