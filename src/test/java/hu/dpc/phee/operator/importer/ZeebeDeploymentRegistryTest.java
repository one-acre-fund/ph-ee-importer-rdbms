package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.entity.processdefinition.ProcessDefinition;
import hu.dpc.phee.operator.entity.processdefinition.ProcessDefinitionRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ZeebeDeploymentRegistryTest {
    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;
    @InjectMocks
    private ZeebeDeploymentRegistry registry;

    @Test
    public void registersAndResolvesLegacyWorkflowKey() {
        registry.registerFromDeployment(deployment("\"workflowKey\":2251799813793627", "inbound_bancobu_fineract-burundi"));
        assertEquals("inbound_bancobu_fineract-burundi", registry.resolveBpmnProcessId(2251799813793627L));
        verify(processDefinitionRepository, never()).findByProcessDefinitionKey(2251799813793627L + 1);
    }

    @Test
    public void registersAndResolvesCurrentProcessDefinitionKey() {
        registry.registerFromDeployment(deployment("\"processDefinitionKey\":99", "mpesa_flow-burundi"));
        assertEquals("mpesa_flow-burundi", registry.resolveBpmnProcessId(99L));
    }

    @Test
    public void persistsTheMappingSoOtherReplicasCanRouteWithoutTheDeploymentRecord() {
        registry.registerFromDeployment(deployment("\"processDefinitionKey\":99", "mpesa_flow-burundi"));
        ArgumentCaptor<ProcessDefinition> captor = ArgumentCaptor.forClass(ProcessDefinition.class);
        verify(processDefinitionRepository).save(captor.capture());
        assertEquals(Long.valueOf(99L), captor.getValue().getProcessDefinitionKey());
        assertEquals("mpesa_flow-burundi", captor.getValue().getBpmnProcessId());
    }

    @Test
    public void doesNotRewriteAnUnchangedPersistedMapping() {
        when(processDefinitionRepository.findByProcessDefinitionKey(99L)).thenReturn(new ProcessDefinition(99L, "mpesa_flow-burundi"));
        registry.registerFromDeployment(deployment("\"processDefinitionKey\":99", "mpesa_flow-burundi"));
        verify(processDefinitionRepository, never()).save(any(ProcessDefinition.class));
    }

    @Test
    public void resolvesFromTheDatabaseWhenThisReplicaNeverSawTheDeployment() {
        when(processDefinitionRepository.findByProcessDefinitionKey(5L)).thenReturn(new ProcessDefinition(5L, "transfer-burundi"));
        assertEquals("transfer-burundi", registry.resolveBpmnProcessId(5L));
        assertEquals("transfer-burundi", registry.resolveBpmnProcessId(5L));
        verify(processDefinitionRepository).findByProcessDefinitionKey(5L);
    }

    @Test
    public void returnsNullForUnknownKey() {
        assertNull(registry.resolveBpmnProcessId(1L));
        assertNull(registry.resolveBpmnProcessId(null));
    }

    private static DocumentContext deployment(String keyField, String bpmnProcessId) {
        return JsonPathReader.parse("{"
                + "\"valueType\":\"DEPLOYMENT\","
                + "\"value\":{\"deployedWorkflows\":[{" + keyField + ","
                + "\"bpmnProcessId\":\"" + bpmnProcessId + "\"}]}"
                + "}");
    }
}
