package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Component
public class InflightTransferManager extends InflightGenerationManager<Transfer> {

    @Autowired
    private TransferRepository transferRepository;

    /** Start of the transfer's own root process. */
    // One transaction: closing the lost previous row and saving its replacement succeed together.
    @Transactional
    public void transferStarted(Long workflowInstanceKey, Long timestamp, String direction) {
        transferStarted(workflowInstanceKey, timestamp, direction, true);
    }

    /**
     * @param rootStart false when a call-activity child starts inside the parent transfer: the
     *                  child's start must not be mistaken for a new run of the parent's key
     */
    @Transactional
    public void transferStarted(Long workflowInstanceKey, Long timestamp, String direction, boolean rootStart) {
        Transfer transfer = resolveRow(workflowInstanceKey, timestamp, rootStart);
        // A variable record processed before ELEMENT_ACTIVATING already fills startedAt, so the
        // direction must not depend on startedAt being empty.
        boolean changed = false;
        if (transfer.getDirection() == null && direction != null) {
            transfer.setDirection(direction);
            changed = true;
        }
        if (transfer.getStartedAt() == null) {
            transfer.setStartedAt(new Date(timestamp));
            changed = true;
        } else {
            logger.debug("transfer {} already started at {}", workflowInstanceKey, transfer.getStartedAt());
        }
        if (changed) {
            save(transfer);
        }
    }

    public void transferEnded(Long workflowInstanceKey, Long timestamp) {
        end(workflowInstanceKey, timestamp);
    }

    public Transfer getOrCreateTransfer(Long workflowInstanceKey) {
        return getOrCreate(workflowInstanceKey);
    }

    @Override
    protected List<Transfer> loadRows(Long workflowInstanceKey) {
        return transferRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(workflowInstanceKey);
    }

    @Override
    protected Transfer newRow(Long workflowInstanceKey, long zeebeGeneration) {
        return new Transfer(workflowInstanceKey, zeebeGeneration);
    }

    @Override
    protected Transfer persist(Transfer row) {
        return transferRepository.save(row);
    }

    @Override
    protected Long instanceKey(Transfer row) {
        return row.getWorkflowInstanceKey();
    }

    @Override
    protected boolean hasTerminalStatus(Transfer row) {
        return row.getStatus() != null && row.getStatus().isTerminal();
    }

    @Override
    protected String kind() {
        return "transfer";
    }
}
