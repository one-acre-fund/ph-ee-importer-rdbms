package hu.dpc.phee.operator.entity.processdefinition;

import hu.dpc.phee.operator.entity.parent.AbstractPersistableCustom;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * Process definition key to {@code bpmnProcessId-tenant} mapping learned from DEPLOYMENT records.
 * Lives in the shared tenants database (no tenant is bound when it is read or written), so a
 * replica that never consumed the deployment can still route VARIABLE and JOB records that
 * carry no bpmnProcessId.
 */
@Entity
@Table(name = "process_definitions", uniqueConstraints = @UniqueConstraint(name = "uk_process_definitions_key", columnNames = {"PROCESS_DEFINITION_KEY"}))
public class ProcessDefinition extends AbstractPersistableCustom<Long> {

    @Column(name = "PROCESS_DEFINITION_KEY", nullable = false)
    private Long processDefinitionKey;

    @Column(name = "BPMN_PROCESS_ID", nullable = false)
    private String bpmnProcessId;

    public ProcessDefinition() {
    }

    public ProcessDefinition(Long processDefinitionKey, String bpmnProcessId) {
        this.processDefinitionKey = processDefinitionKey;
        this.bpmnProcessId = bpmnProcessId;
    }

    public Long getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(Long processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
    }

    public String getBpmnProcessId() {
        return bpmnProcessId;
    }

    public void setBpmnProcessId(String bpmnProcessId) {
        this.bpmnProcessId = bpmnProcessId;
    }
}
