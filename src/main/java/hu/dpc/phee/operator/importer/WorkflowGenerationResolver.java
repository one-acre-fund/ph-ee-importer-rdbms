package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves ZEEBE_GENERATION for variables/tasks.
 * <p>
 * Hot path: inflight map (memory). {@code getOrCreate*} is used so a variable/task
 * arriving before PROCESS start still opens the same generation the start event will reuse.
 * History lookup (max generation) happens only when a new process row is created.
 */
@Component
public class WorkflowGenerationResolver {

    @Value("${bpmn.transfer-type}")
    private String transferType;

    @Value("${bpmn.transaction-request-type}")
    private String transactionRequestType;

    @Value("${bpmn.batch-type}")
    private String batchType;

    @Autowired
    private BpmnProcessProperties bpmnProcessProperties;

    @Autowired
    private InflightTransferManager inflightTransferManager;

    @Autowired
    private InflightTransactionRequestManager inflightTransactionRequestManager;

    @Autowired
    private InflightBatchManager inflightBatchManager;

    public Long ensureGeneration(String bpmnProcessId, Long workflowInstanceKey) {
        BpmnProcess bpmnProcess = bpmnProcessProperties.getById(bpmnProcessId);
        String type = bpmnProcess.getType();
        if (transferType.equals(type)) {
            return inflightTransferManager.getOrCreateTransfer(workflowInstanceKey).getZeebeGeneration();
        }
        if (transactionRequestType.equals(type)) {
            return inflightTransactionRequestManager.getOrCreateTransactionRequest(workflowInstanceKey).getZeebeGeneration();
        }
        if (batchType.equals(type)) {
            return inflightBatchManager.getOrCreateBatch(workflowInstanceKey).getZeebeGeneration();
        }
        return peekInflightOrZero(workflowInstanceKey);
    }

    Long peekInflightOrZero(Long workflowInstanceKey) {
        Long generation = inflightTransferManager.peekGeneration(workflowInstanceKey);
        if (generation != null) {
            return generation;
        }
        generation = inflightTransactionRequestManager.peekGeneration(workflowInstanceKey);
        if (generation != null) {
            return generation;
        }
        generation = inflightBatchManager.peekGeneration(workflowInstanceKey);
        return generation == null ? 0L : generation;
    }

    void setTransferType(String transferType) {
        this.transferType = transferType;
    }

    void setTransactionRequestType(String transactionRequestType) {
        this.transactionRequestType = transactionRequestType;
    }

    void setBatchType(String batchType) {
        this.batchType = batchType;
    }

    void setBpmnProcessProperties(BpmnProcessProperties bpmnProcessProperties) {
        this.bpmnProcessProperties = bpmnProcessProperties;
    }

    void setInflightTransferManager(InflightTransferManager inflightTransferManager) {
        this.inflightTransferManager = inflightTransferManager;
    }

    void setInflightTransactionRequestManager(InflightTransactionRequestManager inflightTransactionRequestManager) {
        this.inflightTransactionRequestManager = inflightTransactionRequestManager;
    }

    void setInflightBatchManager(InflightBatchManager inflightBatchManager) {
        this.inflightBatchManager = inflightBatchManager;
    }
}
