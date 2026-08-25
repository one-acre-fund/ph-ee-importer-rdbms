package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.entity.tenant.TenantServerConnection;
import hu.dpc.phee.operator.entity.tenant.TenantServerConnectionRepository;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KafkaConsumer implements ConsumerSeekAware {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${importer.kafka.topic}")
    private String kafkaTopic;

    @Value("${importer.kafka.reset}")
    private boolean reset;

    @Autowired
    private RecordParser recordParser;

    @Autowired
    private TenantServerConnectionRepository repository;

    @Autowired
    private TempDocumentStore tempDocumentStore;

    @Autowired
    private ZeebeDeploymentRegistry deploymentRegistry;

    @KafkaListener(topics = "${importer.kafka.topic}")
    public void listen(String rawData) {
        Long startTime = System.currentTimeMillis();
        try {
            DocumentContext incomingRecord = JsonPathReader.parse(rawData);
            logger.debug("from kafka: {}", incomingRecord.jsonString());

            String valueType = incomingRecord.read("$.valueType", String.class);
            if ("DEPLOYMENT".equals(valueType)) {
                deploymentRegistry.registerFromDeployment(incomingRecord);
                return;
            }

            if ("VARIABLE_DOCUMENT".equals(valueType)) {
                logger.info("Skipping VARIABLE_DOCUMENT record ");
                return;
            }

            Long processDefinitionKey = ZeebeRecordReader.readProcessDefinitionKey(incomingRecord);
            Long recordKey = incomingRecord.read("$.key");
            String bpmnprocessIdWithTenant = resolveBpmnProcessId(incomingRecord, processDefinitionKey);
            logger.info("bpmnprocessIdWithTenant: " + bpmnprocessIdWithTenant);

            if (bpmnprocessIdWithTenant == null) {
                if (processDefinitionKey == null) {
                    logger.warn("Record with key {} has no processDefinitionKey, skip processing", recordKey);
                    return;
                }
                tempDocumentStore.storeDocument(processDefinitionKey, incomingRecord);
                logger.info("Record with key {} processDefinitionKey {} has no associated bpmn, stored temporarily",
                        recordKey, processDefinitionKey);
                return;
            }

            tempDocumentStore.setBpmnprocessId(processDefinitionKey, bpmnprocessIdWithTenant);

            String tenantName = bpmnprocessIdWithTenant.substring(bpmnprocessIdWithTenant.indexOf("-") + 1);
            String bpmnprocessId = bpmnprocessIdWithTenant.substring(0, bpmnprocessIdWithTenant.indexOf("-"));
            logger.info("Tenant name: " + tenantName);
            logger.info("bpmnprocessId: " + bpmnprocessId);
            TenantServerConnection tenant = repository.findOneBySchemaName(tenantName);
            ThreadLocalContextUtil.setTenant(tenant);
            logger.debug("Mid Time 1 {}", (System.currentTimeMillis() - startTime));

            List<DocumentContext> documents = new ArrayList<>();
            List<DocumentContext> storedDocuments = tempDocumentStore.takeStoredDocuments(processDefinitionKey);
            if (!storedDocuments.isEmpty()) {
                logger.info("Reprocessing {} previously stored records with processDefinitionKey {}",
                        storedDocuments.size(), processDefinitionKey);
                documents.addAll(storedDocuments);
            }
            documents.add(incomingRecord);
            documents.sort(Comparator
                    .comparingInt(ZeebeRecordReader::processingPriority)
                    .thenComparing(ZeebeRecordReader::readTimestamp, Comparator.nullsLast(Long::compareTo)));

            logger.debug("Start processing of {} documents.", documents.size());
            for (DocumentContext doc : documents) {
                processDocument(doc, bpmnprocessId, processDefinitionKey);
            }
            logger.debug("Total Time 1 {}", (System.currentTimeMillis() - startTime));
        } catch (Exception ex) {
            logger.error("Could not parse zeebe event:\n{}\nerror: {}\ntrace: {}",
                    rawData,
                    ex.getMessage(),
                    limitStackTrace(ex));
        } finally {
            ThreadLocalContextUtil.clear();
            logger.debug("Total Time 2 {}", (System.currentTimeMillis() - startTime));
        }
    }

    private String resolveBpmnProcessId(DocumentContext record, Long processDefinitionKey) {
        String bpmnprocessIdWithTenant = ZeebeRecordReader.readBpmnProcessIdWithTenant(record);
        if (bpmnprocessIdWithTenant != null) {
            return bpmnprocessIdWithTenant;
        }
        if (processDefinitionKey != null) {
            bpmnprocessIdWithTenant = tempDocumentStore.getBpmnprocessId(processDefinitionKey);
            if (bpmnprocessIdWithTenant != null) {
                return bpmnprocessIdWithTenant;
            }
            return deploymentRegistry.resolveBpmnProcessId(processDefinitionKey);
        }
        return null;
    }

    private void processDocument(DocumentContext doc, String bpmnprocessId, Long processDefinitionKey) {
        String valueType = null;
        try {
            valueType = doc.read("$.valueType", String.class);
            logger.info("Processing document of type {}", valueType);
            switch (valueType) {
                case "VARIABLE":
                    DocumentContext processedVariable = recordParser.processVariable(doc, bpmnprocessId);
                    if (processedVariable != null) {
                        recordParser.addVariableToEntity(processedVariable, bpmnprocessId);
                    }
                    break;
                case "JOB":
                    recordParser.processTask(doc, bpmnprocessId);
                    break;
                case "PROCESS_INSTANCE":
                case "WORKFLOW_INSTANCE":
                    if (ZeebeRecordReader.isRootProcessElement(doc)) {
                        recordParser.processWorkflowInstance(doc, bpmnprocessId);
                    } else {
                        logger.debug("Skipping non-root process element for {}", valueType);
                    }
                    break;
                case "INCIDENT":
                    logger.info("Doc {}", doc.jsonString());
                    break;
                default:
                    logger.debug("Skipping unsupported valueType {}", valueType);
                    break;
            }
        } catch (Exception ex) {
            logger.error("Failed to process document:\n{}\nof valueType:\n{}\nerror: {}\ntrace: {}",
                    doc.jsonString(),
                    valueType,
                    ex.getMessage(),
                    limitStackTrace(ex));
            tempDocumentStore.storeDocument(processDefinitionKey, doc);
        }
    }

    private String limitStackTrace(Exception ex) {
        return Arrays.stream(ex.getStackTrace())
                .limit(10)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining("\n"));
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekAware.ConsumerSeekCallback callback) {
        if (reset) {
            assignments.keySet().stream()
                    .filter(partition -> partition.topic().equals(kafkaTopic))
                    .forEach(partition -> {
                        callback.seekToBeginning(partition.topic(), partition.partition());
                        logger.info("seeked {} to beginning", partition);
                    });
        } else {
            logger.info("no reset, consuming kafka topics from latest");
        }
    }
}
