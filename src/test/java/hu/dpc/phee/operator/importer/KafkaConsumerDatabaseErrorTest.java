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
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Field;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class KafkaConsumerDatabaseErrorTest {

    private static final String ACTIVATING = "{"
            + "\"valueType\":\"WORKFLOW_INSTANCE\","
            + "\"timestamp\":1,"
            + "\"intent\":\"ELEMENT_ACTIVATING\","
            + "\"key\":2251799813793852,"
            + "\"value\":{"
            + "\"bpmnElementType\":\"PROCESS\","
            + "\"bpmnProcessId\":\"inbound_bancobu_fineract-burundi\","
            + "\"workflowKey\":2251799813793627,"
            + "\"workflowInstanceKey\":2251799813793852,"
            + "\"parentWorkflowInstanceKey\":-1"
            + "}"
            + "}";

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
        TenantServerConnection tenant = new TenantServerConnection();
        tenant.setSchemaName("burundi");
        when(repository.findOneBySchemaName("burundi")).thenReturn(tenant);
    }

    @Test
    public void databaseErrorClearsCachesAndPropagatesSoTheRecordIsRetried() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(recordParser).processWorkflowInstance(any(DocumentContext.class), eq("inbound_bancobu_fineract"));

        try {
            kafkaConsumer.listen(ACTIVATING);
            fail("expected the database error to propagate");
        } catch (DataAccessResourceFailureException expected) {
            // the listener container re-polls the record
        }

        verify(recordParser).clearInflightState();
        verify(tempDocumentStore, never()).storeDocument(anyLong(), any(DocumentContext.class));
    }
}
