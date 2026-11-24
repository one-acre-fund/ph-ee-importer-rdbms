package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestState;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import hu.dpc.phee.operator.entity.transfer.TransferStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Boundaries between two generations of one key: replayed completions, starts and early completions. */
public class GenerationBoundaryTest {

    private static final Long KEY = 700L;

    private final TransferRepository transfers = Mockito.mock(TransferRepository.class);
    private final TransactionRequestRepository requests = Mockito.mock(TransactionRequestRepository.class);
    private final BatchRepository batches = Mockito.mock(BatchRepository.class);
    private final TempDocumentStore tempDocumentStore = Mockito.mock(TempDocumentStore.class);
    private final InflightTransferManager transferManager = new InflightTransferManager();
    private final InflightTransactionRequestManager requestManager = new InflightTransactionRequestManager();
    private final InflightBatchManager batchManager = new InflightBatchManager();

    @Before
    public void setUp() throws Exception {
        set(transferManager, "transferRepository", transfers);
        set(requestManager, "transactionRequestRepository", requests);
        set(requestManager, "tempDocumentStore", tempDocumentStore);
        set(batchManager, "batchRepository", batches);
        transferManager.setMaxIdleMinutes(10080);
        requestManager.setMaxIdleMinutes(10080);
        batchManager.setMaxIdleMinutes(10080);
    }

    @After
    public void tearDown() {
        ThreadLocalContextUtil.clear();
    }

    // --- a replayed completion within the rounding distance of the next start ---

    @Test
    public void replayedCompletionOneSecondBeforeTheNextStartLeavesTheLiveGenerationOpen() {
        Transfer g0 = transfer(0L, 1_000L, 6_000L);
        stored(g0);
        transferManager.transferStarted(KEY, 7_000L, "OUTGOING");
        Transfer live = transferManager.getOrCreateTransfer(KEY);

        transferManager.transferEnded(KEY, 6_000L);

        assertNull(live.getCompletedAt());
        assertEquals(Long.valueOf(1L), transferManager.peekGeneration(KEY));
    }

    @Test
    public void replayedCompletionInTheRoundingDistanceOfAStoredStartStaysWithItsOwnGeneration() {
        Transfer g0 = transfer(0L, 1_000L, 7_000L); // really 6.600, stored rounded up
        Transfer g1 = transfer(1L, 7_000L, null);
        stored(g0, g1);

        transferManager.transferEnded(KEY, 6_600L);

        assertNull(g1.getCompletedAt());
        verify(transfers, never()).save(any(Transfer.class));
    }

    @Test
    public void findBatchForARecordJustBeforeTheCachedStartReturnsTheEarlierBatch() {
        Batch g0 = new Batch(KEY, 0L);
        g0.setStartedAt(new Date(1_000L));
        g0.setCompletedAt(new Date(59_000L));
        when(batches.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(g0));
        batchManager.batchStarted(KEY, 60_000L, "UNKNOWN");

        assertSame(g0, batchManager.findBatch(KEY, 59_000L));
        assertSame(batchManager.getOrCreateBatch(KEY), batchManager.findBatch(KEY, 60_000L));
    }

    @Test
    public void redeliveredStartRoundedUpInStorageStaysWithItsOwnGeneration() {
        Transfer g0 = transfer(0L, 1_000L, 1_000L);
        Transfer g1 = transfer(1L, 2_000L, null); // started at 1.600, stored rounded up
        stored(g0, g1);

        transferManager.transferStarted(KEY, 1_600L, "OUTGOING");

        assertNull(g1.getCompletedAt());
        assertEquals(Long.valueOf(1L), transferManager.peekGeneration(KEY));
    }

    // --- root start ---

    @Test
    public void rootStartDelayedAfterItsFirstVariableDoesNotSplitThePayment() {
        ThreadLocalContextUtil.setRecordTimestamp(100_000L);
        Transfer fromVariable = transferManager.getOrCreateTransfer(KEY);
        fromVariable.setStartedAt(new Date(100_000L));

        // a busy broker appends ELEMENT_ACTIVATING seconds after the creation variables
        transferManager.transferStarted(KEY, 105_000L, "OUTGOING");

        assertNull(fromVariable.getCompletedAt());
        assertSame(fromVariable, transferManager.getOrCreateTransfer(KEY));
    }

    // --- completion older than every start ---

    @Test
    public void completionBeforeTheOnlyStartLeavesTheRowOpen() {
        transferManager.transferStarted(KEY, 60_000L, "OUTGOING");
        Transfer live = transferManager.getOrCreateTransfer(KEY);

        transferManager.transferEnded(KEY, 3_000L);

        assertNull(live.getCompletedAt());
        assertEquals(Long.valueOf(0L), transferManager.peekGeneration(KEY));
    }

    @Test
    public void completionReplacesAProvisionalOneOnTheLiveRow() {
        transferManager.transferStarted(KEY, 1_000L, "OUTGOING");
        Transfer live = transferManager.getOrCreateTransfer(KEY);
        live.setStatus(TransferStatus.COMPLETED);
        live.setCompletedAt(new Date(5_000L));

        transferManager.transferEnded(KEY, 8_000L);

        assertEquals(8_000L, live.getCompletedAt().getTime());
    }

    // --- provisional completion after the cache was cleared ---

    @Test
    public void realCompletionAfterARebalanceReplacesTheProvisionalOneAndCleansUp() {
        TransactionRequest row = request(TransactionRequestState.ACCEPTED, 5_000L);
        ThreadLocalContextUtil.setRecordTimestamp(4_000L);
        requestManager.getOrCreateTransactionRequest(KEY); // replayed job

        requestManager.transactionRequestEnded(KEY, 8_000L);

        assertEquals(8_000L, row.getCompletedAt().getTime());
        verify(tempDocumentStore).deleteDocument(KEY);
    }

    @Test
    public void replayedRealCompletionIsStillIgnored() {
        TransactionRequest row = request(TransactionRequestState.ACCEPTED, 8_000L);

        requestManager.transactionRequestEnded(KEY, 8_000L);

        assertEquals(8_000L, row.getCompletedAt().getTime());
        verify(requests, never()).save(any(TransactionRequest.class));
        verify(tempDocumentStore, never()).deleteDocument(KEY);
    }

    @Test
    public void laterCompletionDoesNotTouchARowWithoutTerminalStatus() {
        TransactionRequest row = request(TransactionRequestState.IN_PROGRESS, 5_000L);

        requestManager.transactionRequestEnded(KEY, 8_000L);

        assertEquals(5_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void completionOfAMuchLaterRunDoesNotTouchTheOldProvisionalRow() {
        TransactionRequest row = request(TransactionRequestState.ACCEPTED, 5_000L);

        requestManager.transactionRequestEnded(KEY, 5_000L + 30L * 86_400_000L);

        assertEquals(5_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void transferCompletionReplacesAProvisionalOneOnlyForATerminalStatus() {
        for (TransferStatus status : Arrays.asList(TransferStatus.COMPLETED, TransferStatus.FAILED)) {
            Transfer row = transfer(0L, 1_000L, 5_000L);
            row.setStatus(status);
            stored(row);
            transferManager.transferEnded(KEY, 8_000L);
            assertEquals(8_000L, row.getCompletedAt().getTime());
        }
        for (TransferStatus status : Arrays.asList(TransferStatus.IN_PROGRESS, null)) {
            Transfer row = transfer(0L, 1_000L, 5_000L);
            row.setStatus(status);
            stored(row);
            transferManager.transferEnded(KEY, 8_000L);
            assertEquals(5_000L, row.getCompletedAt().getTime());
        }
    }

    @Test
    public void transactionRequestWithoutAStateHasNoProvisionalCompletion() {
        TransactionRequest row = request(null, 5_000L);

        requestManager.transactionRequestEnded(KEY, 8_000L);

        assertEquals(5_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void batchNeverHasAProvisionalCompletion() {
        Batch row = new Batch(KEY, 0L);
        row.setStartedAt(new Date(1_000L));
        row.setCompletedAt(new Date(5_000L));
        when(batches.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(row));

        batchManager.batchEnded(KEY, 8_000L);

        assertEquals(5_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void provisionalCompletionIsMeasuredFromTheLastActivity() {
        requestManager.setMaxIdleMinutes(1);
        TransactionRequest row = request(TransactionRequestState.ACCEPTED, 5_000L);
        row.setLastEventAt(new Date(65_000L)); // kept receiving records after its terminal status

        requestManager.transactionRequestEnded(KEY, 66_000L);

        assertEquals(66_000L, row.getCompletedAt().getTime());
        verify(tempDocumentStore).deleteDocument(KEY);
    }

    @Test
    public void provisionalCompletionAfterTheIdleLimitIsIgnored() {
        requestManager.setMaxIdleMinutes(1);
        TransactionRequest row = request(TransactionRequestState.ACCEPTED, 5_000L);
        row.setLastEventAt(new Date(65_000L));

        requestManager.transactionRequestEnded(KEY, 65_000L + 60_001L);

        assertEquals(5_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void disabledIdleLimitDoesNotLimitTheProvisionalCompletion() {
        requestManager.setMaxIdleMinutes(0);
        TransactionRequest row = request(TransactionRequestState.REJECTED, 5_000L);

        requestManager.transactionRequestEnded(KEY, 5_000L + 30L * 86_400_000L);

        assertEquals(5_000L + 30L * 86_400_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void completionWithoutTimestampIgnoresCompletedRows() {
        TransactionRequest row = request(TransactionRequestState.ACCEPTED, 5_000L);

        requestManager.transactionRequestEnded(KEY, null);

        assertEquals(5_000L, row.getCompletedAt().getTime());
    }

    @Test
    public void recordInTheRoundingDistanceBelongsToTheNewRowOnceThePreviousOneIsOver() {
        Transfer over = transfer(0L, 1_000L, 5_000L);
        Transfer g1 = transfer(1L, 7_000L, null);
        stored(over, g1);
        ThreadLocalContextUtil.setRecordTimestamp(6_700L);
        assertSame(g1, transferManager.getOrCreateTransfer(KEY));

        transferManager.clear();
        Transfer neverCompleted = transfer(0L, 1_000L, null);
        stored(neverCompleted, g1);
        assertSame(g1, transferManager.getOrCreateTransfer(KEY));
    }

    @Test
    public void completionInTheRoundingDistanceOfItsOwnStartCompletesTheLiveRow() {
        Transfer stored = transfer(0L, 7_000L, null); // started at 6.600, stored rounded up
        stored(stored);
        ThreadLocalContextUtil.setRecordTimestamp(7_100L);
        Transfer live = transferManager.getOrCreateTransfer(KEY);
        live.setCompletedAt(new Date(7_200L)); // from a terminal status variable

        transferManager.transferEnded(KEY, 6_800L);

        assertEquals(6_800L, live.getCompletedAt().getTime());
        assertNull(transferManager.peekGeneration(KEY));
    }

    private TransactionRequest request(TransactionRequestState state, long completedAt) {
        TransactionRequest row = new TransactionRequest(KEY, 0L);
        row.setStartedAt(new Date(1_000L));
        row.setCompletedAt(new Date(completedAt));
        row.setState(state);
        when(requests.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(row));
        return row;
    }

    private void stored(Transfer... rows) {
        when(transfers.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Arrays.asList(rows));
    }

    private static Transfer transfer(Long generation, Long startedAt, Long completedAt) {
        Transfer row = new Transfer(KEY, generation);
        row.setStartedAt(startedAt == null ? null : new Date(startedAt));
        row.setCompletedAt(completedAt == null ? null : new Date(completedAt));
        return row;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
