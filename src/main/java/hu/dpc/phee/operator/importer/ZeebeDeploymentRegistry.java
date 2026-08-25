package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public void registerFromDeployment(DocumentContext deploymentRecord) {
        List<Map<String, Object>> deployedWorkflows = deploymentRecord.read("$.value.deployedWorkflows");
        if (deployedWorkflows == null || deployedWorkflows.isEmpty()) {
            return;
        }
        for (Map<String, Object> workflow : deployedWorkflows) {
            if (workflow == null) {
                continue;
            }
            Long processDefinitionKey = toLong(workflow.get("processDefinitionKey"));
            if (processDefinitionKey == null) {
                processDefinitionKey = toLong(workflow.get("workflowKey"));
            }
            String bpmnProcessId = toString(workflow.get("bpmnProcessId"));
            if (processDefinitionKey == null || bpmnProcessId == null) {
                continue;
            }
            processDefinitionKeyToBpmn.put(processDefinitionKey, bpmnProcessId);
            logger.info("Registered deployment mapping {} -> {}", processDefinitionKey, bpmnProcessId);
        }
    }

    public String resolveBpmnProcessId(Long processDefinitionKey) {
        if (processDefinitionKey == null) {
            return null;
        }
        return processDefinitionKeyToBpmn.get(processDefinitionKey);
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
