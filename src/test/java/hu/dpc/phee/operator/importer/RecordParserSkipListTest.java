package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.callactivity.CallActivityLinkRepository;
import hu.dpc.phee.operator.entity.variable.Variable;
import hu.dpc.phee.operator.entity.variable.VariableRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 * FD-1737: a variable whose name is on importer.variables.skip-names is dropped before any
 * database access, every other name still goes through the normal path.
 */
@RunWith(MockitoJUnitRunner.class)
public class RecordParserSkipListTest {

    private static final Long KEY = 6755399441058311L;
    private static final long TS = 1609459200000L;

    @Mock
    private VariableRepository variableRepository;
    @Mock
    private WorkflowGenerationResolver workflowGenerationResolver;
    @Mock
    private BpmnProcessProperties bpmnProcessProperties;
    @Mock
    private CallActivityLinkRepository callActivityLinkRepository;

    @InjectMocks
    private RecordParser recordParser;

    @Before
    public void setUp() throws Exception {
        Set<String> skip = new HashSet<>();
        skip.add("timer");
        skip.add("isCallbackReceived");
        Field field = RecordParser.class.getDeclaredField("skipVariableNames");
        field.setAccessible(true);
        field.set(recordParser, skip);
    }

    @Test
    public void skippedNameIsNotStoredAndTouchesNoRepository() {
        DocumentContext result = recordParser.processVariable(variableJson("timer"), "PayerFundTransfer");

        assertNull(result);
        verifyZeroInteractions(variableRepository, workflowGenerationResolver, callActivityLinkRepository);
    }

    @Test
    public void otherNamesAreStillStored() {
        when(workflowGenerationResolver.ensureGeneration("PayerFundTransfer", KEY)).thenReturn(0L);
        when(variableRepository.findByWorkflowInstanceKeyAndZeebeGeneration(KEY, 0L)).thenReturn(Collections.emptyList());

        DocumentContext result = recordParser.processVariable(variableJson("transactionId"), "PayerFundTransfer");

        assertNotNull(result);
        ArgumentCaptor<Variable> captor = ArgumentCaptor.forClass(Variable.class);
        verify(variableRepository).save(captor.capture());
        assertEquals("transactionId", captor.getValue().getName());
    }

    private static DocumentContext variableJson(String name) {
        return JsonPathReader.parse("{"
                + "\"value\":{"
                + "\"processInstanceKey\":" + KEY + ","
                + "\"name\":\"" + name + "\","
                + "\"value\":\"\\\"abc\\\"\","
                + "\"processDefinitionKey\":2251799813687425"
                + "},"
                + "\"timestamp\":" + TS
                + "}");
    }
}
