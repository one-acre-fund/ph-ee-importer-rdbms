package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.entity.processdefinition.ProcessDefinition;
import hu.dpc.phee.operator.entity.processdefinition.ProcessDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps Zeebe process definition keys to {@code bpmnProcessId-tenant} strings learned from
 * DEPLOYMENT records, so VARIABLE / JOB events without {@code bpmnProcessId} can be routed.
 */
@Component
public class ZeebeDeploymentRegistry {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<Long, String> processDefinitionKeyToBpmn = new ConcurrentHashMap<>();

    // Shared tenants database: the DEPLOYMENT record may be consumed by another replica.
    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    public void registerFromDeployment(DocumentContext deploymentRecord) {
        // Zeebe 8.x lists the deployed processes under processesMetadata; 0.x used deployedWorkflows.
        List<Map<String, Object>> deployed = deploymentRecord.read("$.value.processesMetadata");
        if (deployed == null || deployed.isEmpty()) {
            deployed = deploymentRecord.read("$.value.deployedWorkflows");
        }
        if (deployed == null || deployed.isEmpty()) {
            return;
        }
        for (Map<String, Object> workflow : deployed) {
            if (workflow == null) {
                continue;
            }
            Long processDefinitionKey = toLong(workflow.get("processDefinitionKey"));
            if (processDefinitionKey == null) {
                processDefinitionKey = toLong(workflow.get("workflowKey"));
            }
            register(processDefinitionKey, toString(workflow.get("bpmnProcessId")));
        }
    }

    /** Zeebe 8.x also exports one PROCESS record per deployed definition. */
    public void registerFromProcess(DocumentContext processRecord) {
        register(ZeebeRecordReader.readProcessDefinitionKey(processRecord), ZeebeRecordReader.readBpmnProcessIdWithTenant(processRecord));
    }

    /**
     * Learns the mapping from any record that carries both fields. Definitions deployed before
     * this registry existed never produce a DEPLOYMENT record we can consume, so this is what
     * fills process_definitions for them. Only the first sighting per pod touches the database.
     * <p>
     * A failed write propagates so Kafka redelivers this record: it may be the only one carrying
     * the mapping, and records without bpmnProcessId would otherwise wait on it indefinitely.
     */
    public void remember(Long processDefinitionKey, String bpmnProcessId) {
        if (processDefinitionKey == null || bpmnProcessId == null
                || bpmnProcessId.equals(processDefinitionKeyToBpmn.get(processDefinitionKey))) {
            return;
        }
        register(processDefinitionKey, bpmnProcessId);
    }

    private void register(Long processDefinitionKey, String bpmnProcessId) {
        if (processDefinitionKey == null || bpmnProcessId == null) {
            return;
        }
        try {
            persist(processDefinitionKey, bpmnProcessId);
        } catch (DataIntegrityViolationException ex) {
            if (!isStored(processDefinitionKey, bpmnProcessId)) {
                throw ex;
            }
            // Another replica inserted the same mapping between our lookup and insert.
            logger.debug("process definition mapping {} -> {} already stored by another replica", processDefinitionKey, bpmnProcessId);
        }
        processDefinitionKeyToBpmn.put(processDefinitionKey, bpmnProcessId);
        logger.info("Registered process definition mapping {} -> {}", processDefinitionKey, bpmnProcessId);
    }

    /** Confirms a unique-key violation was a concurrent insert of this exact mapping. */
    private boolean isStored(Long processDefinitionKey, String bpmnProcessId) {
        ProcessDefinition stored = processDefinitionRepository.findByProcessDefinitionKey(processDefinitionKey);
        return stored != null && bpmnProcessId.equals(stored.getBpmnProcessId());
    }

    public String resolveBpmnProcessId(Long processDefinitionKey) {
        if (processDefinitionKey == null) {
            return null;
        }
        String bpmnProcessId = processDefinitionKeyToBpmn.get(processDefinitionKey);
        if (bpmnProcessId == null) {
            ProcessDefinition stored = processDefinitionRepository.findByProcessDefinitionKey(processDefinitionKey);
            if (stored != null) {
                bpmnProcessId = stored.getBpmnProcessId();
                processDefinitionKeyToBpmn.put(processDefinitionKey, bpmnProcessId);
            }
        }
        return bpmnProcessId;
    }

    private void persist(Long processDefinitionKey, String bpmnProcessId) {
        ProcessDefinition stored = processDefinitionRepository.findByProcessDefinitionKey(processDefinitionKey);
        if (stored == null) {
            stored = new ProcessDefinition(processDefinitionKey, bpmnProcessId);
        } else if (bpmnProcessId.equals(stored.getBpmnProcessId())) {
            return;
        } else {
            stored.setBpmnProcessId(bpmnProcessId);
        }
        processDefinitionRepository.save(stored);
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private static String toString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
