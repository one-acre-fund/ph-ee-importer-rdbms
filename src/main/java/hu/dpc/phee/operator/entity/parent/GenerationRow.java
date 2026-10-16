package hu.dpc.phee.operator.entity.parent;

import java.util.Date;

/** A per-run row of a process instance: transfers, transaction requests and batches. */
public interface GenerationRow {
    Long getZeebeGeneration();

    Date getCompletedAt();
}
