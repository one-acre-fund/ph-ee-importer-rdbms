package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;

/**
 * Reads Zeebe exporter fields from both legacy ({@code workflowKey}, {@code workflowInstanceKey})
 * and current ({@code processDefinitionKey}, {@code processInstanceKey}) record shapes.
 */
final class ZeebeRecordReader {

    private ZeebeRecordReader() {
    }

    static Long readProcessDefinitionKey(DocumentContext record) {
        Long key = readLong(record, "$.value.processDefinitionKey");
        if (key != null) {
            return key;
        }
        return readLong(record, "$.value.workflowKey");
    }

    static Long readProcessInstanceKey(DocumentContext record) {
        Long key = readLong(record, "$.value.processInstanceKey");
        if (key != null) {
            return key;
        }
        return readLong(record, "$.value.workflowInstanceKey");
    }

    static Long readParentProcessInstanceKey(DocumentContext record) {
        Long key = readLong(record, "$.value.parentProcessInstanceKey");
        if (key != null && key > 0) {
            return key;
        }
        key = readLong(record, "$.value.parentWorkflowInstanceKey");
        if (key != null && key > 0) {
            return key;
        }
        return null;
    }

    static String readBpmnProcessIdWithTenant(DocumentContext record) {
        return record.read("$.value.bpmnProcessId", String.class);
    }

    static boolean isRootProcessElement(DocumentContext record) {
        String elementType = record.read("$.value.bpmnElementType", String.class);
        if ("PROCESS".equals(elementType)) {
            return true;
        }
        // Some exporter versions omit bpmnElementType on the root process lifecycle event.
        return elementType == null && readParentProcessInstanceKey(record) == null;
    }

    static String resolveBpmnProcessBaseId(DocumentContext record, String resolvedBpmnProcessId) {
        String recordBpmnProcessId = readBpmnProcessIdWithTenant(record);
        if (recordBpmnProcessId != null) {
            return recordBpmnProcessId.split("-")[0];
        }
        return resolvedBpmnProcessId;
    }

    static boolean isProcessLifecycleRecord(String valueType) {
        return "PROCESS_INSTANCE".equals(valueType) || "WORKFLOW_INSTANCE".equals(valueType);
    }

    static int processingPriority(DocumentContext record) {
        String valueType = record.read("$.valueType", String.class);
        if (isProcessLifecycleRecord(valueType)) {
            return 0;
        }
        if ("JOB".equals(valueType)) {
            return 1;
        }
        if ("VARIABLE".equals(valueType)) {
            return 2;
        }
        return 3;
    }

    static Long readTimestamp(DocumentContext record) {
        return readLong(record, "$.timestamp");
    }

    private static Long readLong(DocumentContext record, String path) {
        Object value = record.read(path);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }
}
