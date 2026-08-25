package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class InflightBatchManager {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Map<Long, Batch> inflightBatches = new HashMap<>();

    @Autowired
    private BatchRepository batchRepository;

    public void batchStarted(Long workflowInstanceKey, Long timestamp, String direction) {
        Batch batch = getOrCreateBatch(workflowInstanceKey);
        if (batch.getStartedAt() == null) {
            batch.setStartedAt(new Date(timestamp));
            batchRepository.save(batch);
            logger.debug("saving batch {}", batch.getWorkflowInstanceKey());
        } else {
            logger.debug("batch {} already started at {}", workflowInstanceKey, batch.getStartedAt());
        }
    }

    public void batchEnded(Long workflowInstanceKey, Long timestamp) {
        synchronized (inflightBatches) {
            Batch batch = inflightBatches.remove(workflowInstanceKey);
            if (batch == null) {
                logger.error("failed to remove in-flight batch {}", workflowInstanceKey);
                batch = batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(workflowInstanceKey);
                if (batch == null || batch.getCompletedAt() != null) {
                    logger.error("completed event arrived for non existent batch {} or it was already finished!", workflowInstanceKey);
                    return;
                }
            }

            batch.setCompletedAt(new Date(timestamp));
            batchRepository.save(batch);
            logger.debug("batch {} finished", batch.getWorkflowInstanceKey());
        }
    }

    public Batch getOrCreateBatch(Long workflowInstanceKey) {
        synchronized (inflightBatches) {
            Batch batch = inflightBatches.get(workflowInstanceKey);
            if (batch == null) {
                batch = batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(workflowInstanceKey);
                if (batch == null) {
                    Long nextGeneration = getNextGeneration(workflowInstanceKey);
                    batch = new Batch(workflowInstanceKey, nextGeneration);
                    logger.debug("started in-flight batch {} with generation {}", batch.getWorkflowInstanceKey(), nextGeneration);
                } else if (batch.getZeebeGeneration() == null) {
                    batch.setZeebeGeneration(0L);
                }
                inflightBatches.put(workflowInstanceKey, batch);
            }
            return batch;
        }
    }

    /**
     * Memory-only lookup. Used on the hot path so child events do not query the DB
     * after the process row is already inflight.
     */
    public Long peekGeneration(Long workflowInstanceKey) {
        synchronized (inflightBatches) {
            Batch batch = inflightBatches.get(workflowInstanceKey);
            return batch == null ? null : batch.getZeebeGeneration();
        }
    }

    private Long getNextGeneration(Long workflowInstanceKey) {
        // Only called when creating a new row (first event for this key in this run).
        Batch latestBatch = batchRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(workflowInstanceKey);
        if (latestBatch == null || latestBatch.getZeebeGeneration() == null) {
            return 0L;
        }
        return latestBatch.getZeebeGeneration() + 1;
    }
}
