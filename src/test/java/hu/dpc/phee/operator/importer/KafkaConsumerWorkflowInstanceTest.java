package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.entity.tenant.TenantServerConnection;
import hu.dpc.phee.operator.entity.tenant.TenantServerConnectionRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class KafkaConsumerWorkflowInstanceTest {

    private static final Long PROCESS_DEFINITION_KEY = 2251799813793627L;
    private static final Long PROCESS_INSTANCE_KEY = 2251799813793852L;

    @Mock
    private RecordParser recordParser;

    @Mock
    private TenantServerConnectionRepository repository;

    @Mock
    private TempDocumentStore tempDocumentStore;

    @Mock
    private ZeebeDeploymentRegistry deploymentRegistry;

    @InjectMocks
    private KafkaConsumer kafkaConsumer;

    @Before
    public void setUp() throws Exception {
        Field resetField = KafkaConsumer.class.getDeclaredField("reset");
        resetField.setAccessible(true);
        resetField.set(kafkaConsumer, false);
    }

    @Test
    public void processesLegacyWorkflowInstanceActivatingBeforeVariablesFromDeploymentLookup() throws Exception {
        TenantServerConnection tenant = new TenantServerConnection();
        tenant.setSchemaName("burundi");
        when(repository.findOneBySchemaName("burundi")).thenReturn(tenant);

        String variable = "{"
                + "\"valueType\":\"VARIABLE\","
                + "\"timestamp\":2,"
                + "\"key\":2,"
                + "\"value\":{"
                + "\"name\":\"transactionId\","
                + "\"value\":\"\\\"abc\\\"\","
                + "\"workflowKey\":" + PROCESS_DEFINITION_KEY + ","
                + "\"workflowInstanceKey\":" + PROCESS_INSTANCE_KEY
                + "}"
                + "}";

        String activating = "{"
                + "\"valueType\":\"WORKFLOW_INSTANCE\","
                + "\"timestamp\":1,"
                + "\"intent\":\"ELEMENT_ACTIVATING\","
                + "\"key\":" + PROCESS_INSTANCE_KEY + ","
                + "\"value\":{"
                + "\"bpmnElementType\":\"PROCESS\","
                + "\"bpmnProcessId\":\"inbound_bancobu_fineract-burundi\","
                + "\"workflowKey\":" + PROCESS_DEFINITION_KEY + ","
                + "\"workflowInstanceKey\":" + PROCESS_INSTANCE_KEY + ","
                + "\"parentWorkflowInstanceKey\":-1"
                + "}"
                + "}";

        when(tempDocumentStore.takeStoredDocuments(PROCESS_DEFINITION_KEY))
                .thenReturn(java.util.Collections.singletonList(JsonPathReader.parse(variable)));

        kafkaConsumer.listen(activating);

        verify(recordParser).processWorkflowInstance(any(DocumentContext.class), eq("inbound_bancobu_fineract"));
        verify(recordParser).importVariable(any(DocumentContext.class), eq("inbound_bancobu_fineract"));
    }

    @Test
    public void unresolvedRecordIsRetriedInsteadOfBeingParkedInMemory() {
        String variable = "{"
                + "\"valueType\":\"VARIABLE\","
                + "\"timestamp\":2,"
                + "\"key\":2251799813793999,"
                + "\"value\":{"
                + "\"name\":\"transactionId\","
                + "\"value\":\"\\\"abc\\\"\","
                + "\"workflowKey\":" + PROCESS_DEFINITION_KEY + ","
                + "\"workflowInstanceKey\":" + PROCESS_INSTANCE_KEY
                + "}"
                + "}";
        when(deploymentRegistry.resolveBpmnProcessId(PROCESS_DEFINITION_KEY)).thenReturn(null);
        try {
            kafkaConsumer.listen(variable);
            fail("expected the record to be retried");
        } catch (RetryableRecordException expected) {
            // the listener container re-polls the record until the mapping is in the database
        }
        verify(tempDocumentStore, never()).storeDocument(anyLong(), any(DocumentContext.class));
        verify(recordParser, never()).importVariable(any(DocumentContext.class), any());
    }

    @Test
    public void registersDeploymentWithoutProcessingBusinessRecords() {
        String deployment = "{"
                + "\"valueType\":\"DEPLOYMENT\","
                + "\"value\":{\"deployedWorkflows\":[{\"workflowKey\":" + PROCESS_DEFINITION_KEY + ","
                + "\"bpmnProcessId\":\"inbound_bancobu_fineract-burundi\"}]}"
                + "}";

        kafkaConsumer.listen(deployment);

        verify(deploymentRegistry).registerFromDeployment(any(DocumentContext.class));
        verify(recordParser, never()).processWorkflowInstance(any(DocumentContext.class), any());
        verify(recordParser, never()).importVariable(any(DocumentContext.class), any());
        verify(repository, never()).findOneBySchemaName(any());
        verify(tempDocumentStore, never()).takeStoredDocuments(anyLong());
    }
}
