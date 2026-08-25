package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class InflightTransactionRequestManager {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Map<Long, TransactionRequest> inflightTransactionRequests = new HashMap<>();

    @Autowired
    private TransactionRequestRepository transactionRequestRepository;

    @Autowired
    private TempDocumentStore tempDocumentStore;

    public void transactionRequestStarted(Long workflowInstanceKey, Long timestamp, String direction) {
        TransactionRequest transactionRequest = getOrCreateTransactionRequest(workflowInstanceKey);
        if (transactionRequest.getStartedAt() == null) {
            transactionRequest.setDirection(direction);
            transactionRequest.setStartedAt(new Date(timestamp));
            transactionRequestRepository.save(transactionRequest);
            logger.debug("started in-flight {} transactionRequest {}", transactionRequest.getDirection(), transactionRequest.getWorkflowInstanceKey());
        } else {
            logger.debug("transactionRequest {} already started at {}", workflowInstanceKey, transactionRequest.getStartedAt());
        }
    }

    public void transactionRequestEnded(Long workflowInstanceKey, Long timestamp) {
        synchronized (inflightTransactionRequests) {
            TransactionRequest transactionRequest = inflightTransactionRequests.remove(workflowInstanceKey);
            if (transactionRequest == null) {
                logger.error("failed to remove in-flight transactionRequest {}", workflowInstanceKey);
                transactionRequest = transactionRequestRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(workflowInstanceKey);
                if (transactionRequest == null || transactionRequest.getCompletedAt() != null) {
                    logger.error("completed event arrived for non existent transactionRequest {} or it was already finished!", workflowInstanceKey);
                    return;
                }
            }

            transactionRequest.setCompletedAt(new Date(timestamp));

            transactionRequestRepository.save(transactionRequest);
            tempDocumentStore.deleteDocument(workflowInstanceKey);
            logger.debug("transactionRequest {} finished", transactionRequest.getWorkflowInstanceKey());
        }
    }

    public TransactionRequest getOrCreateTransactionRequest(Long workflowInstanceKey) {
        synchronized (inflightTransactionRequests) {
            TransactionRequest transactionRequest = inflightTransactionRequests.get(workflowInstanceKey);
            if (transactionRequest == null) {
                transactionRequest = transactionRequestRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(workflowInstanceKey);
                if (transactionRequest == null) {
                    Long nextGeneration = getNextGeneration(workflowInstanceKey);
                    transactionRequest = new TransactionRequest(workflowInstanceKey, nextGeneration);
                } else if (transactionRequest.getZeebeGeneration() == null) {
                    transactionRequest.setZeebeGeneration(0L);
                }
                inflightTransactionRequests.put(workflowInstanceKey, transactionRequest);
            }
            return transactionRequest;
        }
    }

    /**
     * Memory-only lookup. Used on the hot path so child events do not query the DB
     * after the process row is already inflight.
     */
    public Long peekGeneration(Long workflowInstanceKey) {
        synchronized (inflightTransactionRequests) {
            TransactionRequest transactionRequest = inflightTransactionRequests.get(workflowInstanceKey);
            return transactionRequest == null ? null : transactionRequest.getZeebeGeneration();
        }
    }

    private Long getNextGeneration(Long workflowInstanceKey) {
        // Only called when creating a new row (first event for this key in this run).
        TransactionRequest latestTransactionRequest = transactionRequestRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(workflowInstanceKey);
        if (latestTransactionRequest == null || latestTransactionRequest.getZeebeGeneration() == null) {
            return 0L;
        }
        return latestTransactionRequest.getZeebeGeneration() + 1;
    }
}
