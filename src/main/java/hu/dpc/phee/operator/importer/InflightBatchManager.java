package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Component
public class InflightBatchManager extends InflightGenerationManager<Batch> {

    @Autowired
    private BatchRepository batchRepository;

    // One transaction: closing the lost previous row and saving its replacement succeed together.
    @Transactional
    public void batchStarted(Long workflowInstanceKey, Long timestamp, String direction) {
        Batch batch = resolveRow(workflowInstanceKey, timestamp, true);
        if (batch.getStartedAt() == null) {
            batch.setStartedAt(new Date(timestamp));
            save(batch);
            logger.debug("saving batch {}", batch.getWorkflowInstanceKey());
        } else {
            logger.debug("batch {} already started at {}", workflowInstanceKey, batch.getStartedAt());
        }
    }

    public void batchEnded(Long workflowInstanceKey, Long timestamp) {
        end(workflowInstanceKey, timestamp);
    }

    public Batch getOrCreateBatch(Long workflowInstanceKey) {
        return getOrCreate(workflowInstanceKey);
    }

    /** Batch row the record at {@code timestamp} belongs to, without creating one. */
    public Batch findBatch(Long workflowInstanceKey, Long timestamp) {
        return findExisting(workflowInstanceKey, timestamp);
    }

    @Override
    protected List<Batch> loadRows(Long workflowInstanceKey) {
        return batchRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(workflowInstanceKey);
    }

    @Override
    protected Batch newRow(Long workflowInstanceKey, long zeebeGeneration) {
        return new Batch(workflowInstanceKey, zeebeGeneration);
    }

    @Override
    protected Batch persist(Batch row) {
        return batchRepository.save(row);
    }

    @Override
    protected Long instanceKey(Batch row) {
        return row.getWorkflowInstanceKey();
    }

    @Override
    protected String kind() {
        return "batch";
    }
}
