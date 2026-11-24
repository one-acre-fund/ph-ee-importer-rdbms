package hu.dpc.phee.operator.entity.parent;

import java.util.Date;

/** A per-run row of a process instance: transfers, transaction requests and batches. */
public interface GenerationRow {
    Long getId();

    Long getZeebeGeneration();

    void setZeebeGeneration(Long zeebeGeneration);

    Date getStartedAt();

    Date getCompletedAt();

    void setCompletedAt(Date completedAt);

    /**
     * Zeebe timestamp of the newest record applied to the row (LAST_EVENT_AT, operations app
     * tenant migration V58). Null on rows written before that migration.
     */
    Date getLastEventAt();

    void setLastEventAt(Date lastEventAt);
}
