package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ZeebeRecordReaderTest {

    @Test
    public void readsCurrentFormatFields() {
        DocumentContext record = JsonPathReader.parse("{"
                + "\"timestamp\":100,"
                + "\"valueType\":\"VARIABLE\","
                + "\"value\":{"
                + "\"processDefinitionKey\":11,"
                + "\"processInstanceKey\":22,"
                + "\"parentProcessInstanceKey\":33"
                + "}"
                + "}");

        assertEquals(Long.valueOf(11L), ZeebeRecordReader.readProcessDefinitionKey(record));
        assertEquals(Long.valueOf(22L), ZeebeRecordReader.readProcessInstanceKey(record));
        assertEquals(Long.valueOf(33L), ZeebeRecordReader.readParentProcessInstanceKey(record));
    }

    @Test
    public void readsLegacyFormatFields() {
        DocumentContext record = JsonPathReader.parse("{"
                + "\"timestamp\":200,"
                + "\"valueType\":\"WORKFLOW_INSTANCE\","
                + "\"value\":{"
                + "\"workflowKey\":44,"
                + "\"workflowInstanceKey\":55,"
                + "\"parentWorkflowInstanceKey\":66,"
                + "\"bpmnElementType\":\"PROCESS\""
                + "}"
                + "}");

        assertEquals(Long.valueOf(44L), ZeebeRecordReader.readProcessDefinitionKey(record));
        assertEquals(Long.valueOf(55L), ZeebeRecordReader.readProcessInstanceKey(record));
        assertEquals(Long.valueOf(66L), ZeebeRecordReader.readParentProcessInstanceKey(record));
        assertTrue(ZeebeRecordReader.isRootProcessElement(record));
    }

    @Test
    public void ignoresNonPositiveParentKeys() {
        DocumentContext record = JsonPathReader.parse("{"
                + "\"value\":{\"parentProcessInstanceKey\":-1}"
                + "}");

        assertNull(ZeebeRecordReader.readParentProcessInstanceKey(record));
    }

    @Test
    public void resolvesBpmnFromRecordBeforeResolvedFallback() {
        DocumentContext record = JsonPathReader.parse("{"
                + "\"value\":{\"bpmnProcessId\":\"mpesa_flow-burundi\"}"
                + "}");

        assertEquals("mpesa_flow", ZeebeRecordReader.resolveBpmnProcessBaseId(record, "inbound_bancobu_fineract"));
    }

    @Test
    public void resolvesBpmnFromResolvedFallbackWhenRecordMissing() {
        DocumentContext record = JsonPathReader.parse("{\"value\":{}}");

        assertEquals("inbound_bancobu_fineract",
                ZeebeRecordReader.resolveBpmnProcessBaseId(record, "inbound_bancobu_fineract"));
    }

    @Test
    public void processLifecycleRecordsHaveHighestPriority() {
        DocumentContext variable = JsonPathReader.parse("{\"valueType\":\"VARIABLE\",\"timestamp\":3}");
        DocumentContext process = JsonPathReader.parse("{\"valueType\":\"WORKFLOW_INSTANCE\",\"timestamp\":2}");

        assertTrue(ZeebeRecordReader.processingPriority(process) < ZeebeRecordReader.processingPriority(variable));
    }
}
