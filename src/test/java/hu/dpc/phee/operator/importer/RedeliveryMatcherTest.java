package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transfer.Transfer;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class RedeliveryMatcherTest {
    private static final Long KEY = 7L;

    private final Transfer generation0 = completed(0L, 5_000L);
    private final Transfer generation1 = completed(1L, 9_000L);
    private final List<Transfer> rows = Arrays.asList(generation0, generation1);

    @Test
    public void recordInsideAnOlderGenerationPicksThatGeneration() {
        assertSame(generation0, RedeliveryMatcher.match(rows, 4_000L));
    }

    @Test
    public void recordBetweenCompletionsPicksTheNextGeneration() {
        assertSame(generation1, RedeliveryMatcher.match(rows, 6_000L));
    }

    @Test
    public void recordInTheSameSecondAsCompletionStillMatches() {
        assertSame(generation0, RedeliveryMatcher.match(rows, 5_900L));
    }

    @Test
    public void recordAfterEveryCompletionIsNotARedelivery() {
        assertNull(RedeliveryMatcher.match(rows, 10_000L));
    }

    @Test
    public void openRowsAndMissingInputsDoNotMatch() {
        Transfer open = new Transfer(KEY, 2L);
        assertNull(RedeliveryMatcher.match(Collections.singletonList(open), 1_000L));
        assertNull(RedeliveryMatcher.match(rows, null));
        assertNull(RedeliveryMatcher.match(null, 1_000L));
    }

    private static Transfer completed(Long generation, long completedAt) {
        Transfer transfer = new Transfer(KEY, generation);
        transfer.setCompletedAt(new Date(completedAt));
        return transfer;
    }
}
