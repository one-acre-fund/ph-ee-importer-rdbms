package hu.dpc.phee.operator.entity.transactionrequest;

public enum TransactionRequestState {

    IN_PROGRESS,
    RECEIVED,
    NOT_AUTOSAVED,
    ACCEPTED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == ACCEPTED || this == FAILED || this == REJECTED || this == NOT_AUTOSAVED;
    }
}