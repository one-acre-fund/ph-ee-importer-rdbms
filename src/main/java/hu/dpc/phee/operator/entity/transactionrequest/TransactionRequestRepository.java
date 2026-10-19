package hu.dpc.phee.operator.entity.transactionrequest;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

public interface TransactionRequestRepository extends CrudRepository<TransactionRequest, Long> {

    TransactionRequest findByWorkflowInstanceKey(Long workflowInstanceKey);

    TransactionRequest findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(Long workflowInstanceKey);

    TransactionRequest findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(Long workflowInstanceKey);
    List<TransactionRequest> findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(Long workflowInstanceKey);

    TransactionRequest findByWorkflowInstanceKeyAndZeebeGeneration(Long workflowInstanceKey, Long zeebeGeneration);

}
