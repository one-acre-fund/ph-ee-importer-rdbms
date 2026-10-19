package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.parent.GenerationRow;

import java.util.List;

/**
 * Picks the historical generation a redelivered record belongs to. Generations of one key run
 * one after another, so the record belongs to the earliest generation that completed at or
 * after the record's timestamp. A record newer than every completion is not a redelivery.
 */
final class RedeliveryMatcher {
    private RedeliveryMatcher() {
    }

    /**
     * @param rowsByGenerationAsc all rows of the key ordered by generation ascending
     * @param recordTimestamp     Zeebe timestamp of the record in milliseconds
     * @return the matching completed row, or null when the record is newer than every completion
     */
    static <T extends GenerationRow> T match(List<T> rowsByGenerationAsc, Long recordTimestamp) {
        if (rowsByGenerationAsc == null || recordTimestamp == null) {
            return null;
        }
        // COMPLETED_AT is a DATETIME with second precision (MySQL rounds the milliseconds), so compare whole seconds.
        long recordSecond = recordTimestamp / 1000;
        for (T row : rowsByGenerationAsc) {
            if (row.getCompletedAt() != null && recordSecond <= row.getCompletedAt().getTime() / 1000) {
                return row;
            }
        }
        return null;
    }
}
