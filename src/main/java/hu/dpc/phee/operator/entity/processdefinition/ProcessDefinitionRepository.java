package hu.dpc.phee.operator.entity.processdefinition;

import org.springframework.data.repository.CrudRepository;

public interface ProcessDefinitionRepository extends CrudRepository<ProcessDefinition, Long> {
    ProcessDefinition findByProcessDefinitionKey(Long processDefinitionKey);
}
