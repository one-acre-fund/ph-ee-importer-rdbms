package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class InflightTransferManager {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Map<Long, Transfer> inflightTransfers = new HashMap<>();

    @Autowired
    private TransferRepository transferRepository;

    public void transferStarted(Long workflowInstanceKey, Long timestamp, String direction) {
        Transfer transfer = getOrCreateTransfer(workflowInstanceKey);
        if (transfer.getStartedAt() == null) {
            transfer.setDirection(direction);
            transfer.setStartedAt(new Date(timestamp));
            transferRepository.save(transfer);
        } else {
            logger.debug("transfer {} already started at {}", workflowInstanceKey, transfer.getStartedAt());
        }
    }

    public void transferEnded(Long workflowInstanceKey, Long timestamp) {
        synchronized (inflightTransfers) {
            Transfer transfer = inflightTransfers.remove(workflowInstanceKey);
            if (transfer == null) {
                logger.error("failed to remove in-flight transfer {}", workflowInstanceKey);
                transfer = transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(workflowInstanceKey);
                if (transfer == null || transfer.getCompletedAt() != null) {
                    logger.error("completed event arrived for non existent transfer {} or it was already finished!", workflowInstanceKey);
                    return;
                }
            }
            transfer.setCompletedAt(new Date(timestamp));
            transferRepository.save(transfer);
            logger.debug("transfer finished {}", transfer.getWorkflowInstanceKey());
        }
    }

    public Transfer getOrCreateTransfer(Long workflowInstanceKey) {
        synchronized (inflightTransfers) {
            Transfer transfer = inflightTransfers.get(workflowInstanceKey);
            if (transfer == null) {
                transfer = transferRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(workflowInstanceKey);
                if (transfer == null) {
                    Long nextGeneration = getNextGeneration(workflowInstanceKey);
                    transfer = new Transfer(workflowInstanceKey, nextGeneration);
                    logger.debug("started in-flight transfer {} with generation {}", transfer.getWorkflowInstanceKey(), nextGeneration);
                } else if (transfer.getZeebeGeneration() == null) {
                    transfer.setZeebeGeneration(0L);
                }
                inflightTransfers.put(workflowInstanceKey, transfer);
            }
            return transfer;
        }
    }

    /**
     * Memory-only lookup. Used on the hot path so child events do not query the DB
     * after the process row is already inflight.
     */
    public Long peekGeneration(Long workflowInstanceKey) {
        synchronized (inflightTransfers) {
            Transfer transfer = inflightTransfers.get(workflowInstanceKey);
            return transfer == null ? null : transfer.getZeebeGeneration();
        }
    }

    private Long getNextGeneration(Long workflowInstanceKey) {
        // Only called when creating a new row (first event for this key in this run).
        Transfer latestTransfer = transferRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(workflowInstanceKey);
        if (latestTransfer == null || latestTransfer.getZeebeGeneration() == null) {
            return 0L;
        }
        return latestTransfer.getZeebeGeneration() + 1;
    }
}