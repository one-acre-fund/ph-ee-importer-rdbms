package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Component
public class InflightTransactionRequestManager extends InflightGenerationManager<TransactionRequest> {

    @Autowired
    private TransactionRequestRepository transactionRequestRepository;

    @Autowired
    private TempDocumentStore tempDocumentStore;

    // One transaction: closing the lost previous row and saving its replacement succeed together.
    @Transactional
    public void transactionRequestStarted(Long workflowInstanceKey, Long timestamp, String direction) {
        TransactionRequest transactionRequest = resolveRow(workflowInstanceKey, timestamp, true);
        if (transactionRequest.getStartedAt() == null) {
            transactionRequest.setDirection(direction);
            transactionRequest.setStartedAt(new Date(timestamp));
            save(transactionRequest);
            logger.debug("started in-flight {} transactionRequest {}", transactionRequest.getDirection(), transactionRequest.getWorkflowInstanceKey());
        } else {
            logger.debug("transactionRequest {} already started at {}", workflowInstanceKey, transactionRequest.getStartedAt());
        }
    }

    public void transactionRequestEnded(Long workflowInstanceKey, Long timestamp) {
        if (end(workflowInstanceKey, timestamp) != null) {
            tempDocumentStore.deleteDocument(workflowInstanceKey);
        }
    }

    public TransactionRequest getOrCreateTransactionRequest(Long workflowInstanceKey) {
        return getOrCreate(workflowInstanceKey);
    }

    @Override
    protected List<TransactionRequest> loadRows(Long workflowInstanceKey) {
        return transactionRequestRepository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(workflowInstanceKey);
    }

    @Override
    protected TransactionRequest newRow(Long workflowInstanceKey, long zeebeGeneration) {
        return new TransactionRequest(workflowInstanceKey, zeebeGeneration);
    }

    @Override
    protected TransactionRequest persist(TransactionRequest row) {
        return transactionRequestRepository.save(row);
    }

    @Override
    protected Long instanceKey(TransactionRequest row) {
        return row.getWorkflowInstanceKey();
    }

    @Override
    protected boolean hasTerminalStatus(TransactionRequest row) {
        return row.getState() != null && row.getState().isTerminal();
    }

    @Override
    protected String kind() {
        return "transactionRequest";
    }
}
