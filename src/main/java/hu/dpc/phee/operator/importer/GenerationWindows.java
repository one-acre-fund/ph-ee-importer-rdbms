package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.parent.GenerationRow;

import java.util.Date;
import java.util.List;

/**
 * Decides which generation row of a workflow instance key a Zeebe record belongs to, from the
 * record's timestamp. Generations of one key run one after another (a key is only reused after
 * Zeebe lost its state), so each row owns the time window from its STARTED_AT up to the next
 * row's start, closed early by its COMPLETED_AT.
 * <p>
 * STARTED_AT and COMPLETED_AT are DATETIME columns that MySQL rounds to the second, so the
 * comparisons are made in whole seconds with one second of tolerance on the start.
 */
final class GenerationWindows {

    /** Rounding of STARTED_AT can move it up to one second past the record that set it. */
    static final long START_TOLERANCE_SECONDS = 1;

    private GenerationWindows() {
    }

    /** Outcome of {@link #resolve}. */
    static final class Resolution<T extends GenerationRow> {
        /** Row the record belongs to; null when a new generation must be opened. */
        final T row;
        /** True when {@link #row} is the newest generation of the key. */
        final boolean latest;
        /** Open row of a lost process that must be closed before the new generation is opened. */
        final T rowToClose;
        /** Generation number for a new row, when {@link #row} is null. */
        final long nextGeneration;

        private Resolution(T row, boolean latest, T rowToClose, long nextGeneration) {
            this.row = row;
            this.latest = latest;
            this.rowToClose = rowToClose;
            this.nextGeneration = nextGeneration;
        }

        boolean opensNewGeneration() {
            return row == null;
        }
    }

    /**
     * @param rowsByGenerationAsc every row of the key, ordered by generation ascending; never null
     *                            (Spring Data returns an empty list when the key has no rows)
     * @param recordTimestamp     Zeebe timestamp of the record in milliseconds, null when unknown
     * @param rootStart           the record is the ELEMENT_ACTIVATING of the root process element
     * @param liveRow             the in-memory row this replica is currently driving, or null. Its
     *                            COMPLETED_AT may have been set early from a terminal status variable,
     *                            so it is treated as open until its ELEMENT_COMPLETED arrives
     * @param maxIdleMillis       an open newest row with no activity for longer than this belongs to
     *                            a lost process; 0 or less disables the check
     */
    static <T extends GenerationRow> Resolution<T> resolve(List<T> rowsByGenerationAsc, Long recordTimestamp,
                                                           boolean rootStart, T liveRow, long maxIdleMillis) {
        long nextGeneration = nextGeneration(rowsByGenerationAsc);
        if (rowsByGenerationAsc.isEmpty()) {
            return new Resolution<>(null, true, null, nextGeneration);
        }
        int last = rowsByGenerationAsc.size() - 1;

        if (recordTimestamp == null) {
            // No timestamp to compare: the newest open row, as before generations were time based.
            for (int i = last; i >= 0; i--) {
                T row = rowsByGenerationAsc.get(i);
                if (completedAt(row, liveRow) == null) {
                    return new Resolution<>(row, i == last, null, nextGeneration);
                }
            }
            return new Resolution<>(null, true, null, nextGeneration);
        }

        // The newest generation that had started by the record's time.
        int index = -1;
        for (int i = last; i >= 0; i--) {
            if (!startsAfter(rowsByGenerationAsc.get(i), recordTimestamp)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // Older than every recorded start: the record can only belong to the first generation.
            return new Resolution<>(rowsByGenerationAsc.get(0), last == 0, null, nextGeneration);
        }

        T candidate = rowsByGenerationAsc.get(index);
        Date completedAt = completedAt(candidate, liveRow);
        if (completedAt != null && seconds(recordTimestamp) > seconds(completedAt.getTime())) {
            if (index < last) {
                // Between two generations: the next one had begun before its STARTED_AT was recorded.
                return new Resolution<>(rowsByGenerationAsc.get(index + 1), index + 1 == last, null, nextGeneration);
            }
            return new Resolution<>(null, true, null, nextGeneration);
        }

        if (index == last && completedAt == null
                && (isLaterStart(candidate, recordTimestamp, rootStart) || isIdle(candidate, recordTimestamp, maxIdleMillis))) {
            return new Resolution<>(null, true, candidate, nextGeneration);
        }
        return new Resolution<>(candidate, index == last, null, nextGeneration);
    }

    /**
     * Cheap check for the in-memory row: true when the record belongs to it and no database
     * lookup is needed.
     */
    static boolean acceptsLiveRow(GenerationRow liveRow, Long recordTimestamp, boolean rootStart, long maxIdleMillis) {
        if (recordTimestamp == null) {
            return true;
        }
        return !startsAfter(liveRow, recordTimestamp)
                && !isLaterStart(liveRow, recordTimestamp, rootStart)
                && !isIdle(liveRow, recordTimestamp, maxIdleMillis);
    }

    /** The record is older than the row's start: it belongs to an earlier generation. */
    static boolean startsAfter(GenerationRow row, long recordTimestamp) {
        Date startedAt = row.getStartedAt();
        return startedAt != null && seconds(recordTimestamp) < seconds(startedAt.getTime()) - START_TOLERANCE_SECONDS;
    }

    /**
     * A root process start later than the row's own start is a new process instance that reuses
     * the key. A redelivered start carries the same timestamp the row was started with.
     */
    static boolean isLaterStart(GenerationRow row, long recordTimestamp, boolean rootStart) {
        Date startedAt = row.getStartedAt();
        return rootStart && startedAt != null
                && seconds(recordTimestamp) > seconds(startedAt.getTime()) + START_TOLERANCE_SECONDS;
    }

    static boolean isIdle(GenerationRow row, long recordTimestamp, long maxIdleMillis) {
        if (maxIdleMillis <= 0) {
            return false;
        }
        Date lastActivity = lastActivity(row);
        return lastActivity != null && recordTimestamp - lastActivity.getTime() > maxIdleMillis;
    }

    /** Latest of STARTED_AT and LAST_EVENT_AT; LAST_EVENT_AT is null on rows older than V58. */
    static Date lastActivity(GenerationRow row) {
        Date startedAt = row.getStartedAt();
        Date lastEventAt = row.getLastEventAt();
        if (startedAt == null) {
            return lastEventAt;
        }
        if (lastEventAt == null) {
            return startedAt;
        }
        return lastEventAt.after(startedAt) ? lastEventAt : startedAt;
    }

    /** Moves LAST_EVENT_AT forward to the record's time; never backwards for a replayed record. */
    static void touch(GenerationRow row, Long recordTimestamp) {
        if (recordTimestamp == null) {
            return;
        }
        Date lastEventAt = row.getLastEventAt();
        if (lastEventAt == null || lastEventAt.getTime() < recordTimestamp) {
            row.setLastEventAt(new Date(recordTimestamp));
        }
    }

    /**
     * Closes the row of a process Zeebe lost. COMPLETED_AT is its last known activity, capped at the
     * record that replaced it, so the windows of the old and the new generation do not overlap. Only
     * reached with a record timestamp: {@link #resolve} never closes a row without one.
     */
    static void closeLost(GenerationRow row, long recordTimestamp) {
        Date lastActivity = lastActivity(row);
        long closedAt = lastActivity == null ? recordTimestamp : Math.min(lastActivity.getTime(), recordTimestamp);
        row.setCompletedAt(new Date(closedAt));
    }

    private static <T extends GenerationRow> Date completedAt(T row, T liveRow) {
        return row == liveRow ? null : row.getCompletedAt();
    }

    private static long nextGeneration(List<? extends GenerationRow> rows) {
        long max = -1;
        for (GenerationRow row : rows) {
            if (row.getZeebeGeneration() != null && row.getZeebeGeneration() > max) {
                max = row.getZeebeGeneration();
            }
        }
        return max + 1;
    }

    private static long seconds(long millis) {
        return Math.floorDiv(millis, 1000L);
    }
}
