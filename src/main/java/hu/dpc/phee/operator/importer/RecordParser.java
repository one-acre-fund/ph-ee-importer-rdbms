package hu.dpc.phee.operator.importer;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import hu.dpc.phee.operator.entity.batch.Transaction;
import hu.dpc.phee.operator.entity.task.Task;
import hu.dpc.phee.operator.entity.task.TaskRepository;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestState;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferStatus;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import hu.dpc.phee.operator.entity.variable.Variable;
import hu.dpc.phee.operator.entity.variable.VariableRepository;
import hu.dpc.phee.operator.file.FileTransferService;
import hu.dpc.phee.operator.util.BatchFormatToTransferMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static hu.dpc.phee.operator.OperatorUtils.strip;

@Component
public class RecordParser {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${bpmn.transfer-type}")
    private String transferType;

    @Value("${bpmn.transaction-request-type}")
    private String transactionRequestType;

    @Value("${bpmn.batch-type}")
    private String batchType;

    @Value("${bpmn.outgoing-direction}")
    private String outgoingDirection;

    @Value("${application.bucket-name}")
    private String bucketName;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransactionRequestRepository transactionRequestRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private BpmnProcessProperties bpmnProcessProperties;

    @Autowired
    private InflightTransferManager inflightTransferManager;

    @Autowired
    private InflightTransactionRequestManager inflightTransactionRequestManager;

    @Autowired
    private InflightBatchManager inflightBatchManager;

    @Autowired
    private VariableParser variableParser;

    @Autowired
    private TempDocumentStore tempDocumentStore;

    @Autowired
    private FileTransferService fileTransferService;

    @Autowired
    private CsvMapper csvMapper;

    @Autowired
    private WorkflowGenerationResolver workflowGenerationResolver;

    private final Map<Long, Long> inflightCallActivities = new ConcurrentHashMap<>();

    public void addVariableToEntity(DocumentContext newVariable, String bpmnProcessId) {

        if (newVariable == null) {
            return;
        }
        logger.debug("newVariable in RecordParser: {}", newVariable.jsonString()); //
        String name = newVariable.read("$.value.name");
        Long workflowInstanceKey = ZeebeRecordReader.readProcessInstanceKey(newVariable);
        if (inflightCallActivities.containsKey(workflowInstanceKey)) {
            Long parentInstanceKey = inflightCallActivities.get(workflowInstanceKey);
            logger.debug("variable {} in instance {} has parent workflowInstance {}", name, workflowInstanceKey, parentInstanceKey);
            workflowInstanceKey = parentInstanceKey;
        }

        BpmnProcess bpmnProcess = bpmnProcessProperties.getById(bpmnProcessId);
        if (transferType.equals(bpmnProcess.getType())) {
            if (variableParser.getTransferParsers().containsKey(name)) {
                logger.debug("add variable {} to transfer for workflow {}", name, workflowInstanceKey);
                String value = newVariable.read("$.value.value");

                Transfer transfer = inflightTransferManager.getOrCreateTransfer(workflowInstanceKey);
                variableParser.getTransferParsers().get(name).accept(Pair.of(transfer, value));
                applyTransferTimestamps(transfer, newVariable);
                transferRepository.save(transfer);
            }
        } else if (transactionRequestType.equals(bpmnProcess.getType())) {
            if (variableParser.getTransactionRequestParsers().containsKey(name)) {
                logger.debug("add variable to transactionRequest {} for workflow {}", name, workflowInstanceKey);
                String value = newVariable.read("$.value.value");

                TransactionRequest transactionRequest = inflightTransactionRequestManager.getOrCreateTransactionRequest(workflowInstanceKey);
                variableParser.getTransactionRequestParsers().get(name).accept(Pair.of(transactionRequest, value));
                if(transactionRequest.getDirection() == null) {
                    transactionRequest.setDirection(bpmnProcess.getDirection());
                }
                applyTransactionRequestTimestamps(transactionRequest, newVariable);
                transactionRequestRepository.save(transactionRequest);
            }
        } else if (batchType.equals(bpmnProcess.getType())) {
            if (variableParser.getBatchParsers().containsKey(name)) {
                logger.debug("add variable {} to batch for workflow {}", name, workflowInstanceKey);
                String value = newVariable.read("$.value.value");

                Batch batch = inflightBatchManager.getOrCreateBatch(workflowInstanceKey);
                variableParser.getBatchParsers().get(name).accept(Pair.of(batch, value));
                batchRepository.save(batch);

                if (!bpmnProcess.getId().equalsIgnoreCase("bulk_processor")) {
                    logger.info("Inside if condition {}", name);
                    if (name.equals("filename")) {
                        logger.info("store filename {} in tempDocStore for instance {}", strip(value), workflowInstanceKey);
                        tempDocumentStore.storeBatchFileName(workflowInstanceKey, value);
                    }
                    if (name.equals("batchId")) {
                        logger.info("store batchid {} in tempDocStore for instance {}", strip(value), workflowInstanceKey);
                        tempDocumentStore.storeBatchId(workflowInstanceKey, value);
                    }
                }
            }
        }
        else {
            logger.debug("Skip adding variable to {} and type is {}", bpmnProcessId, bpmnProcess.getType()); // xx
        }
    }

    public DocumentContext processVariable(DocumentContext json, String bpmnProcessId) {
        Long workflowInstanceKey = ZeebeRecordReader.readProcessInstanceKey(json);
        Long generationKey = workflowInstanceKey;
        if (inflightCallActivities.containsKey(workflowInstanceKey)) {
            generationKey = inflightCallActivities.get(workflowInstanceKey);
        }
        String name = json.read("$.value.name");
        Long newTimestamp = ZeebeRecordReader.readTimestamp(json);
        Long zeebeGeneration = workflowGenerationResolver.ensureGeneration(bpmnProcessId, generationKey);
        List<Variable> existingVariables = variableRepository.findByWorkflowInstanceKeyAndZeebeGeneration(workflowInstanceKey, zeebeGeneration);
        if (existingVariables != null && !existingVariables.isEmpty()) {
            if (existingVariables.stream().filter(existing -> {
                return name.equals(existing.getName()) && newTimestamp <= existing.getTimestamp(); // variable already inserted before
            }).findFirst().orElse(null) != null) {
                logger.info("Variable {} already inserted at {} for instance {} generation {}, skip processing!",
                        name, newTimestamp, workflowInstanceKey, zeebeGeneration);
                return null;
            }
        }

        Variable variable = new Variable();
        variable.setWorkflowInstanceKey(workflowInstanceKey);
        variable.setZeebeGeneration(zeebeGeneration);
        variable.setTimestamp(newTimestamp);
        variable.setWorkflowKey(ZeebeRecordReader.readProcessDefinitionKey(json));
        variable.setName(name);
        String value = json.read("$.value.value");
        variable.setValue(value);
        variableRepository.save(variable);
        touchEntityStartedAt(workflowInstanceKey, bpmnProcessId, json);
        return json;
    }

    public void processWorkflowInstance(DocumentContext json, String resolvedBpmnProcessId) {
        String bpmnProcessBaseId = ZeebeRecordReader.resolveBpmnProcessBaseId(json, resolvedBpmnProcessId);
        if (bpmnProcessBaseId == null) {
            logger.error("Cannot resolve bpmnProcessId for process instance event {}", json.jsonString());
            return;
        }
        BpmnProcess bpmnProcess = bpmnProcessProperties.getById(bpmnProcessBaseId);
        Long workflowInstanceKey = ZeebeRecordReader.readProcessInstanceKey(json);
        Long timestamp = ZeebeRecordReader.readTimestamp(json);
        String intent = json.read("$.intent");
        Long parentWorkflowInstanceKey = ZeebeRecordReader.readParentProcessInstanceKey(json);
        boolean hasParent = parentWorkflowInstanceKey != null;

        String elementId = json.read("$.value.elementId");
        Long callActivityKey = json.read("$.key");

        if (transferType.equals(bpmnProcess.getType())) {
            if ("ELEMENT_ACTIVATING".equals(intent)) {
                if (hasParent) {
                    logger.info("Sub process {} with key {} started from parent instance {}", bpmnProcessBaseId, callActivityKey, parentWorkflowInstanceKey);
                    inflightCallActivities.put(callActivityKey, parentWorkflowInstanceKey);
                    inflightTransferManager.transferStarted(parentWorkflowInstanceKey, timestamp, outgoingDirection);
                } else {
                    logger.info("Transfer process {} started for instance {}", bpmnProcessBaseId, workflowInstanceKey);
                    inflightTransferManager.transferStarted(workflowInstanceKey, timestamp, bpmnProcess.getDirection());
                }
            } else if ("ELEMENT_COMPLETED".equals(intent)) {
                if (inflightCallActivities.containsKey(workflowInstanceKey)) {
                    Long parentInstanceKey = inflightCallActivities.remove(workflowInstanceKey);
                    logger.info("Sub process {} with key {} ended from parent instance {}", bpmnProcessBaseId, callActivityKey, parentInstanceKey);
                    workflowInstanceKey = parentInstanceKey;
                }
                logger.info("Transfer process {} completed for instance {}", bpmnProcessBaseId, workflowInstanceKey);
                inflightTransferManager.transferEnded(workflowInstanceKey, timestamp);
            }
        } else if (transactionRequestType.equals(bpmnProcess.getType())) {
            if ("ELEMENT_ACTIVATING".equals(intent)) {
                logger.info("Transaction request process {} started for instance {}", bpmnProcessBaseId, workflowInstanceKey);
                inflightTransactionRequestManager.transactionRequestStarted(workflowInstanceKey, timestamp, bpmnProcess.getDirection());
            } else if ("ELEMENT_COMPLETED".equals(intent)) {
                logger.info("Transaction request process {} completed for instance {}", bpmnProcessBaseId, workflowInstanceKey);
                inflightTransactionRequestManager.transactionRequestEnded(workflowInstanceKey, timestamp);
            }
        } else if (batchType.equals(bpmnProcess.getType())) {
            if ("ELEMENT_ACTIVATING".equals(intent)) {
                inflightBatchManager.batchStarted(workflowInstanceKey, timestamp, bpmnProcess.getDirection());
            } else if ("ELEMENT_COMPLETED".equals(intent)) {
                if (!bpmnProcess.getId().equalsIgnoreCase("bulk_processor")) {
                    logger.info("Inside if condition PROCESS_INSTANCE, json {}", json.jsonString());
                    checkWorkerIdAndUpdateTransferData(workflowInstanceKey, timestamp);
                }
                inflightBatchManager.batchEnded(workflowInstanceKey, timestamp);
            }
        } else {
            logger.error("Skip parsing bpmnProcess: {}, resolvedBpmnProcessId: {}, document: {} as bpmn isn't set",
                    bpmnProcess, bpmnProcessBaseId, json.jsonString());
        }
    }

    public void processTask(DocumentContext json, String bpmnProcessId) {
        String type = json.read("$.value.type");
        if (type == null) {
            return;
        }

        Long workflowInstanceKey = ZeebeRecordReader.readProcessInstanceKey(json);
        Long generationKey = workflowInstanceKey;
        if (inflightCallActivities.containsKey(workflowInstanceKey)) {
            generationKey = inflightCallActivities.get(workflowInstanceKey);
        }
        String newElementId = json.read("$.value.elementId");
        Long newTimestamp = ZeebeRecordReader.readTimestamp(json);
        String newIntent = json.read("$.intent");
        Long zeebeGeneration = workflowGenerationResolver.ensureGeneration(bpmnProcessId, generationKey);
        List<Task> existingTasks = taskRepository.findByWorkflowInstanceKeyAndZeebeGeneration(workflowInstanceKey, zeebeGeneration);
        if (existingTasks != null && !existingTasks.isEmpty()) {
            if (existingTasks.stream().filter(existing -> {
                return newElementId.equals(existing.getElementId()) && newIntent.equals(existing.getIntent()); // task intent inserts happens for only once
            }).findFirst().orElse(null) != null) {
                logger.info("Task {} with intent {} already inserted at {} for instance {} generation {}, skip processing!",
                        newElementId,
                        newIntent,
                        newTimestamp,
                        workflowInstanceKey,
                        zeebeGeneration);
                return;
            }
        }

        Task task = new Task();
        task.setWorkflowInstanceKey(workflowInstanceKey);
        task.setZeebeGeneration(zeebeGeneration);
        task.setWorkflowKey(ZeebeRecordReader.readProcessDefinitionKey(json));
        task.setTimestamp(newTimestamp);
        task.setIntent(newIntent);
        task.setRecordType(json.read("$.recordType"));
        task.setType(type);
        task.setElementId(newElementId);
        taskRepository.save(task);
    }


    private void checkWorkerIdAndUpdateTransferData(Long workflowInstanceKey, Long completeTimestamp) {
        updateTransferTableForBatch(workflowInstanceKey, completeTimestamp);
    }

    // reads data from csv file and write data to transfers table
    private void updateTransferTableForBatch(Long workflowInstanceKey, Long completeTimestamp) {
        String filename = tempDocumentStore.getBatchFileName(workflowInstanceKey);
        logger.info("Filename {}", filename);
        if (filename == null) {
            return;
        }
        filename = strip(filename);
        String localFilePath = fileTransferService.downloadFile(filename, bucketName);
        if (localFilePath == null) {
            logger.error("Null localFilePath, Error updating transfer table for batch with instance key {} and batch filename {}", workflowInstanceKey, filename);
            return;
        }
        List<Transaction> transactionList;
        try {
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            FileReader reader = new FileReader(filename);
            MappingIterator<Transaction> readValues = csvMapper.readerWithSchemaFor(Transaction.class).with(schema).readValues(reader);
            transactionList = new ArrayList<>();
            while (readValues.hasNext()) {
                Transaction current = readValues.next();
                transactionList.add(current);
            }
        } catch (IOException e) {
            e.printStackTrace();
            logger.error("Error building TransactionList for batch with instance key {} and batch filename {}", workflowInstanceKey, filename);
            return;
        }

        Batch batch = batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(workflowInstanceKey);
        if (batch == null) {
            batch = batchRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(workflowInstanceKey);
        }
        if (batch == null) {
            logger.error("No batch found for workflow instance key {}, skip transfer update", workflowInstanceKey);
            return;
        }
        for (Transaction transaction: transactionList) {
            Transfer transfer = BatchFormatToTransferMapper.mapToTransferEntity(transaction);
            transfer.setWorkflowInstanceKey(workflowInstanceKey);
            transfer.setZeebeGeneration(batch.getZeebeGeneration());
            transfer.setBatchId(strip(tempDocumentStore.getBatchId(workflowInstanceKey)));
            transfer.setCompletedAt(new Date(completeTimestamp));
            transfer.setTransactionId(transaction.getRequestId());

            transfer.setPayeeDfspId(batch.getPaymentMode());
            transfer.setPayerDfspId(ThreadLocalContextUtil.getTenant().getSchemaName());

            transfer.setPayeeFeeCurrency(transaction.getCurrency());
            transfer.setPayeeFee(BigDecimal.ZERO);
            transfer.setPayerFeeCurrency(transaction.getCurrency());
            transfer.setPayerFee(BigDecimal.ZERO);

            BatchFormatToTransferMapper.updateTransferUsingBatchDetails(transfer, batch);

            transferRepository.save(transfer);
        }

    }

    public void processWorkflowInstance(DocumentContext json) {
        processWorkflowInstance(json, ZeebeRecordReader.resolveBpmnProcessBaseId(json, null));
    }

    private void touchEntityStartedAt(Long workflowInstanceKey, String bpmnProcessId, DocumentContext event) {
        BpmnProcess bpmnProcess = bpmnProcessProperties.getById(bpmnProcessId);
        if (bpmnProcess == null || bpmnProcess.getType() == null) {
            return;
        }
        Date eventTime = toEventTime(event);
        if (eventTime == null) {
            return;
        }
        if (transferType.equals(bpmnProcess.getType())) {
            Transfer transfer = inflightTransferManager.getOrCreateTransfer(workflowInstanceKey);
            if (transfer != null && transfer.getStartedAt() == null) {
                transfer.setStartedAt(eventTime);
                transferRepository.save(transfer);
            }
        } else if (transactionRequestType.equals(bpmnProcess.getType())) {
            TransactionRequest transactionRequest = inflightTransactionRequestManager.getOrCreateTransactionRequest(workflowInstanceKey);
            if (transactionRequest != null && transactionRequest.getStartedAt() == null) {
                transactionRequest.setStartedAt(eventTime);
                transactionRequestRepository.save(transactionRequest);
            }
        } else if (batchType.equals(bpmnProcess.getType())) {
            Batch batch = inflightBatchManager.getOrCreateBatch(workflowInstanceKey);
            if (batch != null && batch.getStartedAt() == null) {
                batch.setStartedAt(eventTime);
                batchRepository.save(batch);
            }
        }
    }

    private void applyTransferTimestamps(Transfer transfer, DocumentContext variableEvent) {
        Date eventTime = toEventTime(variableEvent);
        if (eventTime == null) {
            return;
        }
        if (transfer.getStartedAt() == null) {
            transfer.setStartedAt(eventTime);
        }
        if (transfer.getCompletedAt() == null && isTerminalTransferStatus(transfer.getStatus())) {
            transfer.setCompletedAt(eventTime);
        }
    }

    private void applyTransactionRequestTimestamps(TransactionRequest transactionRequest, DocumentContext variableEvent) {
        Date eventTime = toEventTime(variableEvent);
        if (eventTime == null) {
            return;
        }
        if (transactionRequest.getStartedAt() == null) {
            transactionRequest.setStartedAt(eventTime);
        }
        if (transactionRequest.getCompletedAt() == null && isTerminalTransactionRequestState(transactionRequest.getState())) {
            transactionRequest.setCompletedAt(eventTime);
        }
    }

    private Date toEventTime(DocumentContext event) {
        Long timestamp = ZeebeRecordReader.readTimestamp(event);
        return timestamp == null ? null : new Date(timestamp);
    }

    private boolean isTerminalTransferStatus(TransferStatus status) {
        return TransferStatus.COMPLETED.equals(status) || TransferStatus.FAILED.equals(status);
    }

    private boolean isTerminalTransactionRequestState(TransactionRequestState state) {
        return TransactionRequestState.ACCEPTED.equals(state)
                || TransactionRequestState.FAILED.equals(state)
                || TransactionRequestState.REJECTED.equals(state)
                || TransactionRequestState.NOT_AUTOSAVED.equals(state);
    }
}
