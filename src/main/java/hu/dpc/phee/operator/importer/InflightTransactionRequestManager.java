package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
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
            save(transactionRequest);
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

            save(transactionRequest);
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
                    transactionRequest = redeliveredTransactionRequest(workflowInstanceKey);
                }
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
     * Persists the entity and keeps the managed copy in the cache. EclipseLink's merge leaves the
     * passed object's version untouched, so saving the same instance twice would fail the
     * optimistic lock on the second save.
     */
    public TransactionRequest save(TransactionRequest entity) {
        TransactionRequest saved = transactionRequestRepository.save(entity);
        if (saved != null) {
            synchronized (inflightTransactionRequests) {
                if (inflightTransactionRequests.get(entity.getWorkflowInstanceKey()) == entity) {
                    inflightTransactionRequests.put(entity.getWorkflowInstanceKey(), saved);
                }
            }
            return saved;
        }
        return entity;
    }

    public void clear() {
        synchronized (inflightTransactionRequests) {
            inflightTransactionRequests.clear();
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

    /**
     * A record whose Zeebe timestamp is not after the completion of the latest generation is a
     * redelivery (Kafka at-least-once), not a new process instance reusing the key. Reuse that row
     * instead of opening a new generation.
     */
    private TransactionRequest redeliveredTransactionRequest(Long workflowInstanceKey) {
        Long recordTimestamp = ThreadLocalContextUtil.getRecordTimestamp();
        if (recordTimestamp == null) {
            return null;
        }
        TransactionRequest latest = transactionRequestRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(workflowInstanceKey);
        // COMPLETED_AT is a DATETIME with second precision (MySQL rounds the milliseconds), so compare whole seconds.
        if (latest != null && latest.getCompletedAt() != null && recordTimestamp / 1000 <= latest.getCompletedAt().getTime() / 1000) {
            logger.info("record for completed transactionRequest {} generation {} redelivered, reusing row", workflowInstanceKey, latest.getZeebeGeneration());
            return latest;
        }
        return null;
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
