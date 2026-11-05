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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    public void registersFromZeebe8ProcessesMetadata() {
        registry.registerFromDeployment(JsonPathReader.parse("{\"valueType\":\"DEPLOYMENT\",\"value\":{"
                + "\"processesMetadata\":[{\"bpmnProcessId\":\"inbound_bancobu_fineract-burundi\","
                + "\"version\":1,\"processDefinitionKey\":2251799813685423,\"duplicate\":false}]}}"));
        assertEquals("inbound_bancobu_fineract-burundi", registry.resolveBpmnProcessId(2251799813685423L));
        verify(processDefinitionRepository).save(any(ProcessDefinition.class));
    }

    @Test
    public void registersFromProcessRecord() {
        registry.registerFromProcess(JsonPathReader.parse("{\"valueType\":\"PROCESS\",\"value\":{"
                + "\"bpmnProcessId\":\"inbound_bancobu_fineract-burundi\",\"processDefinitionKey\":7}}"));
        assertEquals("inbound_bancobu_fineract-burundi", registry.resolveBpmnProcessId(7L));
    }

    @Test
    public void rememberPersistsOncePerPod() {
        registry.remember(7L, "inbound_bancobu_fineract-burundi");
        registry.remember(7L, "inbound_bancobu_fineract-burundi");
        verify(processDefinitionRepository).save(any(ProcessDefinition.class));
        assertEquals("inbound_bancobu_fineract-burundi", registry.resolveBpmnProcessId(7L));
    }

    @Test
    public void rememberAcceptsConfirmedDuplicateInsertFromAnotherReplica() {
        // Lookup before insert finds nothing, the insert loses the race, the re-read finds the row.
        when(processDefinitionRepository.findByProcessDefinitionKey(7L))
                .thenReturn(null)
                .thenReturn(new ProcessDefinition(7L, "inbound_bancobu_fineract-burundi"));
        when(processDefinitionRepository.save(any(ProcessDefinition.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        registry.remember(7L, "inbound_bancobu_fineract-burundi");
        registry.remember(7L, "inbound_bancobu_fineract-burundi");

        assertEquals("inbound_bancobu_fineract-burundi", registry.resolveBpmnProcessId(7L));
        verify(processDefinitionRepository, times(1)).save(any(ProcessDefinition.class));
    }

    @Test
    public void integrityViolationWithoutStoredRowPropagates() {
        when(processDefinitionRepository.save(any(ProcessDefinition.class)))
                .thenThrow(new DataIntegrityViolationException("not a duplicate"));
        try {
            registry.remember(7L, "x-burundi");
            fail("expected the violation to propagate");
        } catch (DataIntegrityViolationException expected) {
            // Kafka redelivers the record that carries the mapping
        }
        assertNull(registry.resolveBpmnProcessId(7L));
    }

    @Test
    public void integrityViolationWithDifferentStoredMappingPropagates() {
        when(processDefinitionRepository.findByProcessDefinitionKey(7L))
                .thenReturn(null)
                .thenReturn(new ProcessDefinition(7L, "other-burundi"));
        when(processDefinitionRepository.save(any(ProcessDefinition.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        try {
            registry.remember(7L, "x-burundi");
            fail("expected the violation to propagate");
        } catch (DataIntegrityViolationException expected) {
            // the retry goes through persist again and updates the row
        }
    }

    @Test
    public void fallsBackToDeployedWorkflowsWhenProcessesMetadataIsEmpty() {
        registry.registerFromDeployment(JsonPathReader.parse("{\"valueType\":\"DEPLOYMENT\",\"value\":{"
                + "\"processesMetadata\":[],"
                + "\"deployedWorkflows\":[{\"workflowKey\":8,\"bpmnProcessId\":\"legacy-burundi\"}]}}"));
        assertEquals("legacy-burundi", registry.resolveBpmnProcessId(8L));
    }

    @Test
    public void registersEveryProcessOfAMultiProcessDeployment() {
        registry.registerFromDeployment(JsonPathReader.parse("{\"valueType\":\"DEPLOYMENT\",\"value\":{"
                + "\"processesMetadata\":["
                + "{\"bpmnProcessId\":\"a-burundi\",\"processDefinitionKey\":1},"
                + "{\"bpmnProcessId\":\"b-burundi\",\"processDefinitionKey\":2},"
                + "{\"bpmnProcessId\":\"no-key-burundi\"}]}}"));
        assertEquals("a-burundi", registry.resolveBpmnProcessId(1L));
        assertEquals("b-burundi", registry.resolveBpmnProcessId(2L));
        verify(processDefinitionRepository, times(2)).save(any(ProcessDefinition.class));
    }

    @Test
    public void deploymentWithoutProcessesWritesNothing() {
        registry.registerFromDeployment(JsonPathReader.parse("{\"valueType\":\"DEPLOYMENT\",\"value\":{"
                + "\"processesMetadata\":[],\"decisionsMetadata\":[]}}"));
        verify(processDefinitionRepository, never()).save(any(ProcessDefinition.class));
    }

    @Test
    public void registerFromProcessIgnoresIncompleteRecord() {
        registry.registerFromProcess(JsonPathReader.parse("{\"valueType\":\"PROCESS\",\"value\":{\"processDefinitionKey\":7}}"));
        registry.registerFromProcess(JsonPathReader.parse("{\"valueType\":\"PROCESS\",\"value\":{\"bpmnProcessId\":\"x-burundi\"}}"));
        verify(processDefinitionRepository, never()).save(any(ProcessDefinition.class));
    }

    @Test
    public void rememberIgnoresMissingFields() {
        registry.remember(null, "x-burundi");
        registry.remember(7L, null);
        verify(processDefinitionRepository, never()).findByProcessDefinitionKey(any());
        verify(processDefinitionRepository, never()).save(any(ProcessDefinition.class));
    }

    @Test
    public void rememberDoesNotRewriteRowAnotherReplicaAlreadyStored() {
        when(processDefinitionRepository.findByProcessDefinitionKey(7L)).thenReturn(new ProcessDefinition(7L, "x-burundi"));

        registry.remember(7L, "x-burundi");
        registry.remember(7L, "x-burundi");

        verify(processDefinitionRepository, never()).save(any(ProcessDefinition.class));
        verify(processDefinitionRepository, times(1)).findByProcessDefinitionKey(7L);
    }

    @Test
    public void rememberUpdatesAChangedMapping() {
        when(processDefinitionRepository.findByProcessDefinitionKey(7L)).thenReturn(new ProcessDefinition(7L, "old-burundi"));

        registry.remember(7L, "new-burundi");

        ArgumentCaptor<ProcessDefinition> captor = ArgumentCaptor.forClass(ProcessDefinition.class);
        verify(processDefinitionRepository).save(captor.capture());
        assertEquals("new-burundi", captor.getValue().getBpmnProcessId());
        assertEquals("new-burundi", registry.resolveBpmnProcessId(7L));
    }

    @Test
    public void nonDuplicateWriteFailurePropagatesAndIsNotCached() {
        when(processDefinitionRepository.save(any(ProcessDefinition.class)))
                .thenThrow(new QueryTimeoutException("timeout"))
                .thenReturn(null);
        try {
            registry.remember(7L, "x-burundi");
            fail("expected the failure to propagate so Kafka redelivers the record");
        } catch (QueryTimeoutException expected) {
            // not cached, so the redelivered record writes again
        }

        registry.remember(7L, "x-burundi");

        verify(processDefinitionRepository, times(2)).save(any(ProcessDefinition.class));
        assertEquals("x-burundi", registry.resolveBpmnProcessId(7L));
    }

    @Test
    public void deploymentWriteFailurePropagates() {
        when(processDefinitionRepository.save(any(ProcessDefinition.class))).thenThrow(new QueryTimeoutException("timeout"));
        try {
            registry.registerFromDeployment(deployment("\"processDefinitionKey\":99", "mpesa_flow-burundi"));
            fail("expected the failure to propagate");
        } catch (QueryTimeoutException expected) {
            // the DEPLOYMENT record is redelivered
        }
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
