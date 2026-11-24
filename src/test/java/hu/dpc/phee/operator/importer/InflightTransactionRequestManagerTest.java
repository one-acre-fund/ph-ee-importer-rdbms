package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class InflightTransactionRequestManagerTest {

    private static final Long KEY = 200L;

    @Mock
    private TransactionRequestRepository transactionRequestRepository;

    @Mock
    private TempDocumentStore tempDocumentStore;

    @InjectMocks
    private InflightTransactionRequestManager manager;

    @Test
    public void firstCreateUsesGenerationZero() {
        TransactionRequest request = manager.getOrCreateTransactionRequest(KEY);

        assertEquals(Long.valueOf(0L), request.getZeebeGeneration());
        assertEquals(KEY, request.getWorkflowInstanceKey());
    }

    @Test
    public void secondCallReusesInflightWithoutExtraDbLookups() {
        TransactionRequest first = manager.getOrCreateTransactionRequest(KEY);
        TransactionRequest second = manager.getOrCreateTransactionRequest(KEY);

        assertSame(first, second);
        verify(transactionRequestRepository, times(1)).findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY);
    }

    @Test
    public void completedThenNewStartAutoBumpsGeneration() {
        TransactionRequest completed = new TransactionRequest(KEY, 0L);
        completed.setCompletedAt(new Date(2_000L));
        when(transactionRequestRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(completed));
        hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil.setRecordTimestamp(10_000L);

        TransactionRequest next = manager.getOrCreateTransactionRequest(KEY);

        assertEquals(Long.valueOf(1L), next.getZeebeGeneration());
    }

    @Test
    public void reusesOpenDbRowAndBackfillsNullGeneration() {
        TransactionRequest open = new TransactionRequest(KEY);
        open.setZeebeGeneration(null);
        when(transactionRequestRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(open));

        TransactionRequest request = manager.getOrCreateTransactionRequest(KEY);

        assertSame(open, request);
        assertEquals(Long.valueOf(0L), request.getZeebeGeneration());
    }

    @Test
    public void variableBeforeStartThenStartReusesSameGeneration() {
        TransactionRequest fromVariable = manager.getOrCreateTransactionRequest(KEY);
        manager.transactionRequestStarted(KEY, 1_000L, "INCOMING");

        assertSame(fromVariable, manager.getOrCreateTransactionRequest(KEY));
        assertEquals("INCOMING", fromVariable.getDirection());
        assertEquals(1_000L, fromVariable.getStartedAt().getTime());
        verify(transactionRequestRepository).save(fromVariable);
    }

    @Test
    public void recycledKeyVariableBeforeStartUsesBumpedGeneration() {
        TransactionRequest completed = new TransactionRequest(KEY, 4L);
        completed.setCompletedAt(new Date(2_000L));
        when(transactionRequestRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(completed));
        hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil.setRecordTimestamp(10_050L);

        TransactionRequest fromVariable = manager.getOrCreateTransactionRequest(KEY);
        assertEquals(Long.valueOf(5L), fromVariable.getZeebeGeneration());

        manager.transactionRequestStarted(KEY, 10_000L, "INCOMING");
        assertSame(fromVariable, manager.getOrCreateTransactionRequest(KEY));
    }

    @Test
    public void startedIsIdempotentWhenAlreadyStarted() {
        TransactionRequest request = manager.getOrCreateTransactionRequest(KEY);
        request.setStartedAt(new Date(100L));

        manager.transactionRequestStarted(KEY, 200L, "OUTGOING");

        assertEquals(100L, request.getStartedAt().getTime());
        verify(transactionRequestRepository, never()).save(request);
    }

    @Test
    public void endedCompletesInflightClearsPeekAndDeletesTempDocs() {
        TransactionRequest request = manager.getOrCreateTransactionRequest(KEY);
        manager.transactionRequestStarted(KEY, 1_000L, "INCOMING");

        manager.transactionRequestEnded(KEY, 2_000L);

        assertEquals(2_000L, request.getCompletedAt().getTime());
        assertNull(manager.peekGeneration(KEY));
        verify(tempDocumentStore).deleteDocument(KEY);
        verify(transactionRequestRepository, times(2)).save(request);
    }

    @Test
    public void endedLoadsOpenRowWhenNotInflight() {
        TransactionRequest open = new TransactionRequest(KEY, 1L);
        when(transactionRequestRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(open));

        manager.transactionRequestEnded(KEY, 3_000L);

        assertEquals(3_000L, open.getCompletedAt().getTime());
        verify(transactionRequestRepository).save(open);
        verify(tempDocumentStore).deleteDocument(KEY);
    }

    @Test
    public void endedIgnoresMissingOrAlreadyCompleted() {
        manager.transactionRequestEnded(KEY, 3_000L);
        verify(transactionRequestRepository, never()).save(org.mockito.ArgumentMatchers.any(TransactionRequest.class));
        verify(tempDocumentStore, never()).deleteDocument(KEY);

        TransactionRequest completed = new TransactionRequest(KEY, 0L);
        completed.setCompletedAt(new Date(4_000L));
        when(transactionRequestRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(completed));
        manager.transactionRequestEnded(KEY, 4_000L);
        verify(transactionRequestRepository, never()).save(completed);
    }

    @Test
    public void peekGenerationIsMemoryOnly() {
        assertNull(manager.peekGeneration(KEY));
        manager.getOrCreateTransactionRequest(KEY);
        assertEquals(Long.valueOf(0L), manager.peekGeneration(KEY));
    }

    @Test
    public void laterRootStartClosesTheLostOpenRowAndOpensANewGeneration() {
        TransactionRequest lost = new TransactionRequest(KEY, 0L);
        lost.setStartedAt(new Date(1_000L));
        lost.setLastEventAt(new Date(3_000L));
        when(transactionRequestRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));

        manager.transactionRequestStarted(KEY, 600_000L, "INCOMING");

        assertEquals(3_000L, lost.getCompletedAt().getTime());
        verify(transactionRequestRepository).save(lost);
        assertEquals(Long.valueOf(1L), manager.peekGeneration(KEY));
    }

    @org.junit.After
    public void clearThreadLocals() {
        hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil.clear();
    }
}
