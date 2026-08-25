package hu.dpc.phee.operator.entity.batch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;

public interface BatchRepository extends JpaRepository<Batch, Long>, JpaSpecificationExecutor {

    Batch findByWorkflowInstanceKey(Long workflowInstanceKey);

    Batch findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(Long workflowInstanceKey);

    Batch findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(Long workflowInstanceKey);

    Batch findByWorkflowInstanceKeyAndZeebeGeneration(Long workflowInstanceKey, Long zeebeGeneration);

}
