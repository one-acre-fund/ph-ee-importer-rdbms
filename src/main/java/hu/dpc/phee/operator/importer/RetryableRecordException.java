package hu.dpc.phee.operator.importer;

/**
 * The record cannot be processed yet but must not be skipped. It propagates out of the listener
 * like a database error, so the container re-polls the same offset instead of committing past it.
 */
public class RetryableRecordException extends RuntimeException {
    public RetryableRecordException(String message) {
        super(message);
    }
}
