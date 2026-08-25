package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class InflightTransferManagerTest {

    private static final Long KEY = 100L;

    @Mock
    private TransferRepository transferRepository;

    @InjectMocks
    private InflightTransferManager inflightTransferManager;

    @Before
    public void setUp() {
        when(transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(null);
        when(transferRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY)).thenReturn(null);
    }

    @Test
    public void firstCreateUsesGenerationZero() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);

        assertEquals(Long.valueOf(0L), transfer.getZeebeGeneration());
        assertEquals(KEY, transfer.getWorkflowInstanceKey());
        verify(transferRepository).findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY);
        verify(transferRepository).findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY);
    }

    @Test
    public void secondCallReusesInflightWithoutExtraDbLookups() {
        Transfer first = inflightTransferManager.getOrCreateTransfer(KEY);
        Transfer second = inflightTransferManager.getOrCreateTransfer(KEY);

        assertSame(first, second);
        verify(transferRepository, times(1)).findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY);
        verify(transferRepository, times(1)).findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY);
    }

    @Test
    public void completedThenNewStartAutoBumpsGeneration() {
        Transfer completed = new Transfer(KEY, 0L);
        completed.setCompletedAt(new Date());
        when(transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(null);
        when(transferRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY)).thenReturn(completed);

        Transfer next = inflightTransferManager.getOrCreateTransfer(KEY);

        assertEquals(Long.valueOf(1L), next.getZeebeGeneration());
    }

    @Test
    public void peekGenerationIsMemoryOnly() {
        assertNull(inflightTransferManager.peekGeneration(KEY));
        verify(transferRepository, never()).findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY);

        inflightTransferManager.getOrCreateTransfer(KEY);
        assertEquals(Long.valueOf(0L), inflightTransferManager.peekGeneration(KEY));
    }

    @Test
    public void variableBeforeStartThenStartReusesSameGeneration() {
        Transfer fromVariable = inflightTransferManager.getOrCreateTransfer(KEY);
        assertEquals(Long.valueOf(0L), fromVariable.getZeebeGeneration());

        inflightTransferManager.transferStarted(KEY, 1_000L, "INCOMING");

        assertSame(fromVariable, inflightTransferManager.getOrCreateTransfer(KEY));
        assertEquals(Long.valueOf(0L), fromVariable.getZeebeGeneration());
        verify(transferRepository).save(fromVariable);
    }

    @Test
    public void recycledKeyVariableBeforeStartUsesBumpedGeneration() {
        Transfer completed = new Transfer(KEY, 2L);
        completed.setCompletedAt(new Date());
        when(transferRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY)).thenReturn(completed);

        Transfer fromVariable = inflightTransferManager.getOrCreateTransfer(KEY);
        assertEquals(Long.valueOf(3L), fromVariable.getZeebeGeneration());

        inflightTransferManager.transferStarted(KEY, 2_000L, "INCOMING");
        assertSame(fromVariable, inflightTransferManager.getOrCreateTransfer(KEY));
        assertEquals(Long.valueOf(3L), fromVariable.getZeebeGeneration());
    }

    @Test
    public void reusesOpenDbRowAndBackfillsNullGeneration() {
        Transfer open = new Transfer(KEY);
        open.setZeebeGeneration(null);
        when(transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(open);

        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);

        assertSame(open, transfer);
        assertEquals(Long.valueOf(0L), transfer.getZeebeGeneration());
        verify(transferRepository, never()).findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY);
    }

    @Test
    public void transferStartedIsIdempotentWhenAlreadyStarted() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);
        transfer.setStartedAt(new Date(500L));

        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");

        assertEquals(500L, transfer.getStartedAt().getTime());
        verify(transferRepository, never()).save(transfer);
    }

    @Test
    public void transferEndedCompletesInflightAndClearsPeek() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);
        inflightTransferManager.transferStarted(KEY, 1_000L, "INCOMING");

        inflightTransferManager.transferEnded(KEY, 2_000L);

        assertEquals(2_000L, transfer.getCompletedAt().getTime());
        assertNull(inflightTransferManager.peekGeneration(KEY));
        verify(transferRepository, times(2)).save(transfer);
    }

    @Test
    public void transferEndedLoadsOpenRowWhenNotInflight() {
        Transfer open = new Transfer(KEY, 1L);
        when(transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(open);

        inflightTransferManager.transferEnded(KEY, 3_000L);

        assertEquals(3_000L, open.getCompletedAt().getTime());
        verify(transferRepository).save(open);
    }

    @Test
    public void transferEndedIgnoresMissingOrAlreadyCompleted() {
        when(transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(null);
        inflightTransferManager.transferEnded(KEY, 3_000L);
        verify(transferRepository, never()).save(org.mockito.ArgumentMatchers.any(Transfer.class));

        Transfer completed = new Transfer(KEY, 0L);
        completed.setCompletedAt(new Date(1L));
        when(transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(completed);
        inflightTransferManager.transferEnded(KEY, 4_000L);
        verify(transferRepository, never()).save(completed);
    }

    @Test
    public void nullLatestGenerationDefaultsToZero() {
        Transfer latest = new Transfer(KEY);
        latest.setZeebeGeneration(null);
        when(transferRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY)).thenReturn(latest);

        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);

        assertEquals(Long.valueOf(0L), transfer.getZeebeGeneration());
    }
}
