package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transfer.Transfer;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GenerationWindowsTest {

    private static final Long KEY = 1L;
    private static final long HOUR = 3_600_000L;
    private static final long NO_IDLE = 0L;

    // --- redelivery of completed generations (formerly RedeliveryMatcher) ---

    @Test
    public void recordInsideAnOlderGenerationPicksThatGeneration() {
        Transfer g0 = row(0L, 1_000L, 5_000L);
        Transfer g1 = row(1L, 6_000L, 9_000L);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(g0, g1), 4_000L, false, null, NO_IDLE);

        assertSame(g0, r.row);
        assertFalse(r.latest);
    }

    @Test
    public void recordBetweenCompletionAndNextRecordedStartPicksTheNextGeneration() {
        Transfer g0 = row(0L, 1_000L, 5_000L);
        Transfer g1 = row(1L, 8_000L, 9_000L);

        assertSame(g1, GenerationWindows.resolve(Arrays.asList(g0, g1), 6_000L, false, null, NO_IDLE).row);
    }

    @Test
    public void recordInTheSameSecondAsCompletionBelongsToThatGeneration() {
        Transfer g0 = row(0L, 1_000L, 5_000L);

        assertSame(g0, GenerationWindows.resolve(Collections.singletonList(g0), 5_900L, false, null, NO_IDLE).row);
    }

    @Test
    public void recordAfterEveryCompletionOpensTheNextGeneration() {
        Transfer g0 = row(0L, 1_000L, 5_000L);
        Transfer g1 = row(1L, 6_000L, 9_000L);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(g0, g1), 10_000L, false, null, NO_IDLE);

        assertTrue(r.opensNewGeneration());
        assertEquals(2L, r.nextGeneration);
        assertNull(r.rowToClose);
    }

    @Test
    public void noRowsOpensGenerationZero() {
        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Collections.<Transfer>emptyList(), 1_000L, false, null, NO_IDLE);

        assertTrue(r.opensNewGeneration());
        assertEquals(0L, r.nextGeneration);
    }

    // --- bug 1: an open row no longer captures every later record ---

    @Test
    public void laterRootStartOnAnOpenRowClosesItAndOpensANewGeneration() {
        Transfer lost = row(0L, 1_000L, null);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Collections.singletonList(lost), 60_000L, true, null, NO_IDLE);

        assertTrue(r.opensNewGeneration());
        assertSame(lost, r.rowToClose);
        assertEquals(1L, r.nextGeneration);
    }

    @Test
    public void redeliveredRootStartOfTheOpenRowReusesIt() {
        Transfer earlier = row(0L, 1_000L, null);
        Transfer open = row(1L, 2_000L, null);

        // STARTED_AT came back rounded up from 1.6 s; the record keeps its milliseconds. The earlier
        // open row makes sure it is the tolerance that picks the row, not the oldest-row fallback.
        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(earlier, open), 1_600L, true, null, NO_IDLE);

        assertSame(open, r.row);
        assertNull(r.rowToClose);
    }

    @Test
    public void nonStartRecordOnAnOpenRowKeepsUsingItWhenNotIdle() {
        Transfer open = row(0L, 1_000L, null);

        assertSame(open, GenerationWindows.resolve(Collections.singletonList(open), 60_000L, false, null, HOUR).row);
    }

    @Test
    public void rootStartOnAnOlderOpenRowThatIsNotTheLatestDoesNotCloseIt() {
        Transfer staleOld = row(0L, 1_000L, null);
        Transfer current = row(1L, 100_000L, null);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(staleOld, current), 50_000L, true, null, NO_IDLE);

        assertSame(staleOld, r.row);
        assertNull(r.rowToClose);
    }

    // --- bug 2: records older than the live generation go to their own row ---

    @Test
    public void recordOlderThanTheLiveRowPicksTheCompletedGeneration() {
        Transfer g0 = row(0L, 1_000L, 5_000L);
        Transfer live = row(1L, 60_000L, null);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(g0, live), 3_000L, false, live, NO_IDLE);

        assertSame(g0, r.row);
        assertFalse(r.latest);
    }

    @Test
    public void liveRowCompletedEarlyByAStatusVariableStaysOpenForItsLaterRecords() {
        Transfer live = row(0L, 1_000L, 5_000L);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Collections.singletonList(live), 8_000L, false, live, NO_IDLE);

        assertSame(live, r.row);
        assertTrue(r.latest);
    }

    @Test
    public void liveRowAcceptsItsOwnRecordsAndRejectsOlderOnes() {
        Transfer live = row(1L, 60_000L, null);

        assertTrue(GenerationWindows.acceptsLiveRow(live, 61_000L, false, NO_IDLE));
        assertTrue(GenerationWindows.acceptsLiveRow(live, 60_000L, false, NO_IDLE));
        // within the rounding distance: not rejected for good, but the other generations are checked first
        assertFalse(GenerationWindows.acceptsLiveRow(live, 59_500L, false, NO_IDLE));
        assertFalse(GenerationWindows.acceptsLiveRow(live, 3_000L, false, NO_IDLE));
        assertTrue(GenerationWindows.acceptsLiveRow(live, null, true, NO_IDLE));
    }

    @Test
    public void liveRowRejectsALaterRootStart() {
        Transfer live = row(0L, 1_000L, null);

        assertFalse(GenerationWindows.acceptsLiveRow(live, 120_000L, true, NO_IDLE));
        assertTrue(GenerationWindows.acceptsLiveRow(live, 120_000L, false, NO_IDLE));
    }

    // --- step 4: idle open rows ---

    @Test
    public void idleOpenRowIsClosedForAVariableOfTheNextInstance() {
        Transfer lost = row(0L, 1_000L, null);
        lost.setLastEventAt(new Date(2_000L));

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Collections.singletonList(lost), 2_000L + HOUR + 1, false, null, HOUR);

        assertTrue(r.opensNewGeneration());
        assertSame(lost, r.rowToClose);
    }

    @Test
    public void lastEventAtKeepsALongRunningRowAlive() {
        Transfer live = row(0L, 1_000L, null);
        live.setLastEventAt(new Date(2 * HOUR));

        assertSame(live, GenerationWindows.resolve(Collections.singletonList(live), 2 * HOUR + 30 * 60_000L, false, null, HOUR).row);
        assertTrue(GenerationWindows.acceptsLiveRow(live, 2 * HOUR + 30 * 60_000L, false, HOUR));
        assertFalse(GenerationWindows.acceptsLiveRow(live, 3 * HOUR + 1, false, HOUR));
    }

    @Test
    public void idleCheckDisabledWithZero() {
        Transfer open = row(0L, 1_000L, null);

        assertSame(open, GenerationWindows.resolve(Collections.singletonList(open), 100 * HOUR, false, null, 0L).row);
    }

    @Test
    public void legacyRowWithoutLastEventAtUsesStartedAtForIdleness() {
        Transfer legacy = row(0L, 1_000L, null);

        assertTrue(GenerationWindows.isIdle(legacy, 1_000L + HOUR + 1, HOUR));
        assertFalse(GenerationWindows.isIdle(legacy, 1_000L + HOUR, HOUR));
    }

    // --- helpers ---

    @Test
    public void closeLostUsesTheLastActivityCappedAtTheNewRecord() {
        Transfer lost = row(0L, 1_000L, null);
        lost.setLastEventAt(new Date(9_000L));

        GenerationWindows.closeLost(lost, 60_000L);
        assertEquals(9_000L, lost.getCompletedAt().getTime());

        Transfer polluted = row(0L, 1_000L, null);
        polluted.setLastEventAt(new Date(70_000L));
        GenerationWindows.closeLost(polluted, 60_000L);
        assertEquals(60_000L, polluted.getCompletedAt().getTime());
    }

    @Test
    public void touchNeverMovesLastEventAtBackwards() {
        Transfer row = row(0L, 1_000L, null);

        GenerationWindows.touch(row, 5_000L);
        GenerationWindows.touch(row, 3_000L);
        GenerationWindows.touch(row, null);

        assertEquals(5_000L, row.getLastEventAt().getTime());
    }

    @Test
    public void withoutTimestampTheNewestOpenRowIsUsed() {
        Transfer g0 = row(0L, 1_000L, null);
        Transfer g1 = row(1L, 6_000L, 9_000L);
        List<Transfer> rows = Arrays.asList(g0, g1);

        assertSame(g0, GenerationWindows.resolve(rows, null, false, null, HOUR).row);
        g0.setCompletedAt(new Date(2_000L));
        assertTrue(GenerationWindows.resolve(rows, null, false, null, HOUR).opensNewGeneration());
    }

    // --- edges of the window search ---

    @Test
    public void recordOlderThanEveryRowBelongsToTheFirstGeneration() {
        Transfer g0 = row(0L, 10_000L, 20_000L);
        Transfer g1 = row(1L, 30_000L, null);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(g0, g1), 1_000L, false, null, HOUR);

        assertSame(g0, r.row);
        assertFalse(r.latest);
        assertNull(r.rowToClose);
    }

    @Test
    public void recordOlderThanTheOnlyRowStillUsesItAsTheLatest() {
        Transfer only = row(0L, 10_000L, null);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Collections.singletonList(only), 1_000L, true, null, HOUR);

        assertSame(only, r.row);
        assertTrue(r.latest);
    }

    @Test
    public void gapBeforeAMiddleGenerationPicksItWithoutMarkingItLatest() {
        Transfer g0 = row(0L, 1_000L, 5_000L);
        Transfer g1 = row(1L, 8_000L, 9_000L);
        Transfer g2 = row(2L, 20_000L, null);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(g0, g1, g2), 6_000L, false, null, NO_IDLE);

        assertSame(g1, r.row);
        assertFalse(r.latest);
    }

    @Test
    public void openRowWithoutAnyRecordedActivityIsNeverIdle() {
        Transfer blank = row(0L, null, null);

        assertFalse(GenerationWindows.isIdle(blank, 100 * HOUR, HOUR));
        assertSame(blank, GenerationWindows.resolve(Collections.singletonList(blank), 100 * HOUR, false, null, HOUR).row);
    }

    @Test
    public void lastActivityOfARowCreatedByAVariableBeforeItsStartIsItsLastEvent() {
        Transfer fromVariable = row(0L, null, null);
        fromVariable.setLastEventAt(new Date(7_000L));

        assertEquals(7_000L, GenerationWindows.lastActivity(fromVariable).getTime());
        assertTrue(GenerationWindows.isIdle(fromVariable, 7_000L + HOUR + 1, HOUR));
    }

    @Test
    public void lastActivityIsTheLaterOfStartAndLastEvent() {
        Transfer row = row(0L, 9_000L, null);
        row.setLastEventAt(new Date(4_000L));
        assertEquals(9_000L, GenerationWindows.lastActivity(row).getTime());

        row.setLastEventAt(new Date(12_000L));
        assertEquals(12_000L, GenerationWindows.lastActivity(row).getTime());

        assertNull(GenerationWindows.lastActivity(row(0L, null, null)));
    }

    @Test
    public void closeLostWithoutAnyActivityUsesTheNewRecordTime() {
        Transfer blank = row(0L, null, null);

        GenerationWindows.closeLost(blank, 60_000L);

        assertEquals(60_000L, blank.getCompletedAt().getTime());
    }

    @Test
    public void nextGenerationSkipsNullsAndRepeatedGenerations() {
        // a batch key holds one CSV transfer row per request id, all in the same generation
        Transfer legacy = row(null, 1_000L, 2_000L);
        Transfer csv1 = row(3L, 3_000L, 4_000L);
        Transfer csv2 = row(3L, 3_000L, 4_000L);

        GenerationWindows.Resolution<Transfer> r = GenerationWindows.resolve(Arrays.asList(legacy, csv1, csv2), 10_000L, false, null, NO_IDLE);

        assertTrue(r.opensNewGeneration());
        assertEquals(4L, r.nextGeneration);
        assertEquals(0L, GenerationWindows.resolve(Collections.singletonList(row(null, 1_000L, 2_000L)), 10_000L, false, null, NO_IDLE).nextGeneration);
    }

    @Test
    public void startChecksIgnoreRowsThatNeverStarted() {
        Transfer notStarted = row(0L, null, null);

        assertFalse(GenerationWindows.startsAfter(notStarted, 1_000L));
        assertFalse(GenerationWindows.isLaterStart(notStarted, 100 * HOUR, true));
        assertFalse(GenerationWindows.isLaterStart(row(0L, 1_000L, null), 100 * HOUR, false));
    }

    private static Transfer row(Long generation, Long startedAt, Long completedAt) {
        Transfer transfer = new Transfer(KEY, generation);
        transfer.setStartedAt(startedAt == null ? null : new Date(startedAt));
        transfer.setCompletedAt(completedAt == null ? null : new Date(completedAt));
        return transfer;
    }
}
