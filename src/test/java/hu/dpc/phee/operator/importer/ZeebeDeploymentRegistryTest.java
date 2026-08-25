package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ZeebeDeploymentRegistryTest {

    private ZeebeDeploymentRegistry registry;

    @Before
    public void setUp() {
        registry = new ZeebeDeploymentRegistry();
    }

    @Test
    public void registersAndResolvesLegacyWorkflowKey() {
        DocumentContext deployment = JsonPathReader.parse("{"
                + "\"valueType\":\"DEPLOYMENT\","
                + "\"value\":{\"deployedWorkflows\":[{\"workflowKey\":2251799813793627,"
                + "\"bpmnProcessId\":\"inbound_bancobu_fineract-burundi\"}]}"
                + "}");

        registry.registerFromDeployment(deployment);

        assertEquals("inbound_bancobu_fineract-burundi",
                registry.resolveBpmnProcessId(2251799813793627L));
    }

    @Test
    public void registersAndResolvesCurrentProcessDefinitionKey() {
        DocumentContext deployment = JsonPathReader.parse("{"
                + "\"valueType\":\"DEPLOYMENT\","
                + "\"value\":{\"deployedWorkflows\":[{\"processDefinitionKey\":99,"
                + "\"bpmnProcessId\":\"mpesa_flow-burundi\"}]}"
                + "}");

        registry.registerFromDeployment(deployment);

        assertEquals("mpesa_flow-burundi", registry.resolveBpmnProcessId(99L));
    }

    @Test
    public void returnsNullForUnknownKey() {
        assertNull(registry.resolveBpmnProcessId(1L));
    }
}
