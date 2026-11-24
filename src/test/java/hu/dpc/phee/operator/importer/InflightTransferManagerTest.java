package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class InflightTransferManagerTest {

    private static final Long KEY = 100L;
    private static final long MINUTE = 60_000L;

    @Mock
    private TransferRepository transferRepository;

    @InjectMocks
    private InflightTransferManager inflightTransferManager;

    @After
    public void clearThreadLocals() {
        ThreadLocalContextUtil.clear();
    }

    // --- unchanged behaviour ---

    @Test
    public void firstCreateUsesGenerationZero() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);

        assertEquals(Long.valueOf(0L), transfer.getZeebeGeneration());
        assertEquals(KEY, transfer.getWorkflowInstanceKey());
        verify(transferRepository).findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY);
    }

    @Test
    public void secondCallReusesInflightWithoutExtraDbLookups() {
        ThreadLocalContextUtil.setRecordTimestamp(1_000L);
        Transfer first = inflightTransferManager.getOrCreateTransfer(KEY);
        ThreadLocalContextUtil.setRecordTimestamp(2_000L);
        Transfer second = inflightTransferManager.getOrCreateTransfer(KEY);

        assertSame(first, second);
        verify(transferRepository, times(1)).findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY);
    }

    @Test
    public void completedThenNewStartAutoBumpsGeneration() {
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY))
                .thenReturn(Collections.singletonList(row(0L, 1_000L, 2_000L)));
        ThreadLocalContextUtil.setRecordTimestamp(10_000L);

        assertEquals(Long.valueOf(1L), inflightTransferManager.getOrCreateTransfer(KEY).getZeebeGeneration());
    }

    @Test
    public void peekGenerationIsMemoryOnly() {
        assertNull(inflightTransferManager.peekGeneration(KEY));
        verify(transferRepository, never()).findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY);

        inflightTransferManager.getOrCreateTransfer(KEY);
        assertEquals(Long.valueOf(0L), inflightTransferManager.peekGeneration(KEY));
    }

    @Test
    public void variableBeforeStartThenStartReusesSameGeneration() {
        ThreadLocalContextUtil.setRecordTimestamp(1_050L);
        Transfer fromVariable = inflightTransferManager.getOrCreateTransfer(KEY);

        inflightTransferManager.transferStarted(KEY, 1_000L, "INCOMING");

        assertSame(fromVariable, inflightTransferManager.getOrCreateTransfer(KEY));
        assertEquals(Long.valueOf(0L), fromVariable.getZeebeGeneration());
        verify(transferRepository).save(fromVariable);
    }

    @Test
    public void recycledKeyVariableBeforeStartUsesBumpedGeneration() {
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY))
                .thenReturn(Collections.singletonList(row(2L, 1_000L, 2_000L)));
        ThreadLocalContextUtil.setRecordTimestamp(10_050L);

        Transfer fromVariable = inflightTransferManager.getOrCreateTransfer(KEY);
        assertEquals(Long.valueOf(3L), fromVariable.getZeebeGeneration());

        inflightTransferManager.transferStarted(KEY, 10_000L, "INCOMING");
        assertSame(fromVariable, inflightTransferManager.getOrCreateTransfer(KEY));
        assertEquals(Long.valueOf(3L), fromVariable.getZeebeGeneration());
    }

    @Test
    public void reusesOpenDbRowAndBackfillsNullGeneration() {
        Transfer open = new Transfer(KEY);
        open.setZeebeGeneration(null);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(open));

        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);

        assertSame(open, transfer);
        assertEquals(Long.valueOf(0L), transfer.getZeebeGeneration());
    }

    @Test
    public void transferStartedIsIdempotentWhenAlreadyStarted() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);
        transfer.setStartedAt(new Date(500L));
        transfer.setDirection("INCOMING");

        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");

        assertEquals(500L, transfer.getStartedAt().getTime());
        assertEquals("INCOMING", transfer.getDirection());
        verify(transferRepository, never()).save(transfer);
    }

    @Test
    public void transferStartedSetsDirectionWhenVariableAlreadyFilledStartedAt() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);
        transfer.setStartedAt(new Date(500L));

        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");

        assertEquals(500L, transfer.getStartedAt().getTime());
        assertEquals("OUTGOING", transfer.getDirection());
        verify(transferRepository).save(transfer);
    }

    @Test
    public void transferStartedOnNewTransferSetsDirectionAndStartedAtInOneSave() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);

        inflightTransferManager.transferStarted(KEY, 1_000L, "INCOMING");

        assertEquals("INCOMING", transfer.getDirection());
        assertEquals(1_000L, transfer.getStartedAt().getTime());
        verify(transferRepository, times(1)).save(transfer);
    }

    @Test
    public void transferStartedWithNullDirectionStillRecordsStart() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);

        inflightTransferManager.transferStarted(KEY, 1_000L, null);

        assertNull(transfer.getDirection());
        assertEquals(1_000L, transfer.getStartedAt().getTime());
        verify(transferRepository, times(1)).save(transfer);
    }

    @Test
    public void transferStartedDoesNotOverwriteDirectionFromVariable() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);
        transfer.setDirection("INCOMING");

        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");

        assertEquals("INCOMING", transfer.getDirection());
        assertEquals(1_000L, transfer.getStartedAt().getTime());
    }

    @Test
    public void transferEndedCompletesInflightAndClearsPeek() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);
        inflightTransferManager.transferStarted(KEY, 1_000L, "INCOMING");

        inflightTransferManager.transferEnded(KEY, 2_000L);

        assertEquals(2_000L, transfer.getCompletedAt().getTime());
        assertEquals(2_000L, transfer.getLastEventAt().getTime());
        assertNull(inflightTransferManager.peekGeneration(KEY));
        verify(transferRepository, times(2)).save(transfer);
    }

    @Test
    public void transferEndedLoadsOpenRowWhenNotInflight() {
        Transfer open = row(1L, 1_000L, null);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(open));

        inflightTransferManager.transferEnded(KEY, 3_000L);

        assertEquals(3_000L, open.getCompletedAt().getTime());
        verify(transferRepository).save(open);
    }

    @Test
    public void transferEndedIgnoresMissingOrAlreadyCompleted() {
        inflightTransferManager.transferEnded(KEY, 3_000L);
        verify(transferRepository, never()).save(any(Transfer.class));

        Transfer completed = row(0L, 500L, 1_000L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(completed));
        inflightTransferManager.transferEnded(KEY, 1_000L);
        verify(transferRepository, never()).save(completed);
        assertEquals(1_000L, completed.getCompletedAt().getTime());
    }

    @Test
    public void clearDropsCachedTransferSoNextLookupReadsRepository() {
        Transfer first = inflightTransferManager.getOrCreateTransfer(KEY);
        inflightTransferManager.clear();
        Transfer second = inflightTransferManager.getOrCreateTransfer(KEY);

        assertNotSame(first, second);
        verify(transferRepository, times(2)).findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY);
    }

    @Test
    public void redeliveredRecordForCompletedTransferReusesItsRow() {
        Transfer completed = row(2L, 1_000L, 5_000L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(completed));
        ThreadLocalContextUtil.setRecordTimestamp(4_000L);

        assertSame(completed, inflightTransferManager.getOrCreateTransfer(KEY));
    }

    @Test
    public void redeliveredRecordInTheSameSecondAsCompletionReusesRow() {
        // completedAt comes back from MySQL rounded to the second; the record keeps its milliseconds
        Transfer completed = row(0L, 1_000L, 5_000L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(completed));
        ThreadLocalContextUtil.setRecordTimestamp(5_900L);

        assertSame(completed, inflightTransferManager.getOrCreateTransfer(KEY));
    }

    @Test
    public void replayedRecordOfOlderGenerationReusesThatGenerationNotTheLatest() {
        Transfer first = row(0L, 1_000L, 5_000L);
        Transfer second = row(1L, 6_000L, 9_000L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Arrays.asList(first, second));

        ThreadLocalContextUtil.setRecordTimestamp(4_000L);
        assertSame(first, inflightTransferManager.getOrCreateTransfer(KEY));

        ThreadLocalContextUtil.setRecordTimestamp(8_000L);
        assertSame(second, inflightTransferManager.getOrCreateTransfer(KEY));
    }

    // --- bug 1: open rows of lost processes ---

    @Test
    public void laterRootStartClosesTheLostOpenRowAndOpensANewGeneration() {
        Transfer lost = row(0L, 1_000L, null);
        lost.setLastEventAt(new Date(3_000L));
        lost.setAmount(java.math.BigDecimal.TEN);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));

        inflightTransferManager.transferStarted(KEY, 10 * MINUTE, "OUTGOING");

        assertEquals(3_000L, lost.getCompletedAt().getTime());
        assertEquals(java.math.BigDecimal.TEN, lost.getAmount());
        verify(transferRepository).save(lost);
        assertEquals(Long.valueOf(1L), inflightTransferManager.peekGeneration(KEY));
    }

    @Test
    public void laterRootStartAlsoReplacesTheCachedLostRow() {
        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");
        Transfer lost = inflightTransferManager.getOrCreateTransfer(KEY);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));

        inflightTransferManager.transferStarted(KEY, 10 * MINUTE, "OUTGOING");

        Transfer next = inflightTransferManager.getOrCreateTransfer(KEY);
        assertNotSame(lost, next);
        assertEquals(Long.valueOf(1L), next.getZeebeGeneration());
        assertEquals(10 * MINUTE, next.getStartedAt().getTime());
        assertEquals(1_000L, lost.getCompletedAt().getTime());
    }

    @Test
    public void callActivityStartInsideTheParentDoesNotOpenANewGeneration() {
        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");
        Transfer parent = inflightTransferManager.getOrCreateTransfer(KEY);

        inflightTransferManager.transferStarted(KEY, 10 * MINUTE, "OUTGOING", false);

        assertSame(parent, inflightTransferManager.getOrCreateTransfer(KEY));
        assertNull(parent.getCompletedAt());
    }

    // --- bug 2: the cache no longer mixes generations ---

    @Test
    public void redeliveredHistoricalRowIsNotCachedForTheNextGeneration() {
        Transfer completed = row(0L, 1_000L, 5_000L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(completed));

        ThreadLocalContextUtil.setRecordTimestamp(4_000L);
        assertSame(completed, inflightTransferManager.getOrCreateTransfer(KEY));
        assertNull(inflightTransferManager.peekGeneration(KEY));

        ThreadLocalContextUtil.setRecordTimestamp(10 * MINUTE);
        Transfer next = inflightTransferManager.getOrCreateTransfer(KEY);
        assertNotSame(completed, next);
        assertEquals(Long.valueOf(1L), next.getZeebeGeneration());
    }

    @Test
    public void olderRecordDoesNotWriteIntoTheCachedNewerGeneration() {
        Transfer previous = row(0L, 1_000L, 5_000L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(previous));
        inflightTransferManager.transferStarted(KEY, 10 * MINUTE, "OUTGOING");
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        assertEquals(Long.valueOf(1L), live.getZeebeGeneration());
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Arrays.asList(previous, live));

        ThreadLocalContextUtil.setRecordTimestamp(4_000L);
        assertSame(previous, inflightTransferManager.getOrCreateTransfer(KEY));

        ThreadLocalContextUtil.setRecordTimestamp(10 * MINUTE + 1_000L);
        assertSame(live, inflightTransferManager.getOrCreateTransfer(KEY));
    }

    @Test
    public void redeliveredCompletionOfAnOlderGenerationLeavesTheLiveOneRunning() {
        Transfer previous = row(0L, 1_000L, 5_000L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(previous));
        inflightTransferManager.transferStarted(KEY, 10 * MINUTE, "OUTGOING");
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Arrays.asList(previous, live));

        inflightTransferManager.transferEnded(KEY, 5_000L);

        assertNull(live.getCompletedAt());
        assertEquals(Long.valueOf(1L), inflightTransferManager.peekGeneration(KEY));
        verify(transferRepository, never()).save(previous);
    }

    @Test
    public void statusVariableCompletionDoesNotSplitTheLiveGeneration() {
        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        live.setCompletedAt(new Date(5_000L)); // set from a COMPLETED status variable

        ThreadLocalContextUtil.setRecordTimestamp(7_000L);
        assertSame(live, inflightTransferManager.getOrCreateTransfer(KEY));

        inflightTransferManager.transferEnded(KEY, 8_000L);
        assertEquals(8_000L, live.getCompletedAt().getTime());
    }

    // --- step 4: idle rows ---

    @Test
    public void variableAfterTheIdleLimitClosesTheLostRowAndOpensANewGeneration() {
        inflightTransferManager.setMaxIdleMinutes(60);
        Transfer lost = row(0L, 1_000L, null);
        lost.setLastEventAt(new Date(2_000L));
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));

        ThreadLocalContextUtil.setRecordTimestamp(2_000L + 61 * MINUTE);
        Transfer next = inflightTransferManager.getOrCreateTransfer(KEY);

        assertEquals(Long.valueOf(1L), next.getZeebeGeneration());
        assertEquals(2_000L, lost.getCompletedAt().getTime());
        verify(transferRepository).save(lost);
    }

    @Test
    public void everyRecordKeepsTheCachedRowAliveInMemory() {
        inflightTransferManager.setMaxIdleMinutes(60);
        inflightTransferManager.transferStarted(KEY, 1_000L, "OUTGOING");
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);

        for (long t = 1; t <= 4; t++) {
            ThreadLocalContextUtil.setRecordTimestamp(1_000L + t * 50 * MINUTE);
            assertSame(live, inflightTransferManager.getOrCreateTransfer(KEY));
        }
        assertEquals(1_000L + 200 * MINUTE, live.getLastEventAt().getTime());
        verify(transferRepository, times(1)).findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY);
    }

    @Test
    public void saveStampsLastEventAtFromTheRecord() {
        Transfer transfer = inflightTransferManager.getOrCreateTransfer(KEY);
        ThreadLocalContextUtil.setRecordTimestamp(42_000L);

        inflightTransferManager.save(transfer);

        assertEquals(42_000L, transfer.getLastEventAt().getTime());
    }

    // --- remaining branches of InflightGenerationManager ---

    @Test
    public void recordOlderThanTheOnlyLiveRowStillUsesIt() {
        inflightTransferManager.transferStarted(KEY, 60_000L, "OUTGOING");
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);

        ThreadLocalContextUtil.setRecordTimestamp(3_000L);
        assertSame(live, inflightTransferManager.getOrCreateTransfer(KEY));
        assertEquals(Long.valueOf(0L), inflightTransferManager.peekGeneration(KEY));
        verify(transferRepository, times(2)).findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY);
    }

    @Test
    public void completionWithoutTimestampCompletesTheLiveRowNow() {
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        long before = System.currentTimeMillis();

        inflightTransferManager.transferEnded(KEY, null);

        long completedAt = live.getCompletedAt().getTime();
        org.junit.Assert.assertTrue(completedAt >= before && completedAt <= System.currentTimeMillis());
        assertNull(inflightTransferManager.peekGeneration(KEY));
    }

    @Test
    public void completionWithoutTimestampCompletesTheNewestOpenStoredRow() {
        Transfer open = row(0L, 1_000L, null);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(open));

        inflightTransferManager.transferEnded(KEY, null);

        org.junit.Assert.assertNotNull(open.getCompletedAt());
        verify(transferRepository).save(open);
    }

    @Test
    public void completionOlderThanTheOnlyLiveRowStillCompletesIt() {
        inflightTransferManager.transferStarted(KEY, 60_000L, "OUTGOING");
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        live.setCompletedAt(new Date(70_000L)); // from a terminal status variable

        inflightTransferManager.transferEnded(KEY, 3_000L);

        assertEquals(3_000L, live.getCompletedAt().getTime());
        assertNull(inflightTransferManager.peekGeneration(KEY));
    }

    @Test
    public void savingARowThatIsNotCachedLeavesTheCacheAlone() {
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        when(transferRepository.save(any(Transfer.class))).thenAnswer(i -> row(9L, null, null));

        inflightTransferManager.save(new Transfer(KEY, 9L)); // e.g. a batch CSV row

        assertSame(live, inflightTransferManager.getOrCreateTransfer(KEY));
        assertEquals(Long.valueOf(0L), inflightTransferManager.peekGeneration(KEY));
    }

    @Test
    public void cachedRowStandsInForItsStoredCopy() {
        inflightTransferManager.setMaxIdleMinutes(60);
        ThreadLocalContextUtil.setRecordTimestamp(1_000L);
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        live.setId(7L);
        Transfer storedCopy = row(0L, null, null);
        storedCopy.setId(7L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(storedCopy));

        ThreadLocalContextUtil.setRecordTimestamp(1_000L + 61 * MINUTE);
        Transfer next = inflightTransferManager.getOrCreateTransfer(KEY);

        // the cached instance, with the current optimistic-lock version, is the one closed and saved
        verify(transferRepository).save(live);
        verify(transferRepository, never()).save(storedCopy);
        assertEquals(1_000L, live.getCompletedAt().getTime());
        assertEquals(Long.valueOf(1L), next.getZeebeGeneration());
    }

    @Test
    public void cachedRowMissingFromTheStoredRowsIsStillConsidered() {
        Transfer previous = row(0L, 1_000L, 5_000L);
        previous.setId(1L);
        when(transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(previous));
        inflightTransferManager.transferStarted(KEY, 60_000L, "OUTGOING");
        Transfer live = inflightTransferManager.getOrCreateTransfer(KEY);
        live.setId(7L); // saved, but not yet visible to the query

        ThreadLocalContextUtil.setRecordTimestamp(3_000L);
        assertSame(previous, inflightTransferManager.getOrCreateTransfer(KEY));

        ThreadLocalContextUtil.setRecordTimestamp(61_000L);
        assertSame(live, inflightTransferManager.getOrCreateTransfer(KEY));
        assertEquals(Long.valueOf(1L), inflightTransferManager.peekGeneration(KEY));
    }

    private static Transfer row(Long generation, Long startedAt, Long completedAt) {
        Transfer transfer = new Transfer(KEY, generation);
        transfer.setStartedAt(startedAt == null ? null : new Date(startedAt));
        transfer.setCompletedAt(completedAt == null ? null : new Date(completedAt));
        return transfer;
    }
}
