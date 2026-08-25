package hu.dpc.phee.operator.entity.transactionrequest;

import org.springframework.data.repository.CrudRepository;

public interface TransactionRequestRepository extends CrudRepository<TransactionRequest, Long> {

    TransactionRequest findByWorkflowInstanceKey(Long workflowInstanceKey);

    TransactionRequest findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(Long workflowInstanceKey);

    TransactionRequest findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(Long workflowInstanceKey);

    TransactionRequest findByWorkflowInstanceKeyAndZeebeGeneration(Long workflowInstanceKey, Long zeebeGeneration);

}
