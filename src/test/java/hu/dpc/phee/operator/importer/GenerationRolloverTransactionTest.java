package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Closing a lost row and saving its replacement commit or roll back together. */
public class GenerationRolloverTransactionTest {

    private static final Long KEY = 800L;

    private AnnotationConfigApplicationContext context;
    private RecordingTransactionManager transactionManager;

    @Before
    public void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        transactionManager = context.getBean(RecordingTransactionManager.class);
    }

    @After
    public void tearDown() {
        context.close();
    }

    @Test
    public void transferRolloverUsesOneTransaction() {
        Transfer lost = new Transfer(KEY, 0L);
        lost.setStartedAt(new Date(1_000L));
        TransferRepository repository = context.getBean(TransferRepository.class);
        when(repository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));

        context.getBean(InflightTransferManager.class).transferStarted(KEY, 600_000L, "OUTGOING");

        verify(repository, times(2)).save(any(Transfer.class));
        assertEquals(1, transactionManager.commits);
    }

    @Test
    public void transferRolloverRollsBackTheClosureWhenTheReplacementFails() {
        Transfer lost = new Transfer(KEY, 0L);
        lost.setStartedAt(new Date(1_000L));
        TransferRepository repository = context.getBean(TransferRepository.class);
        when(repository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));
        when(repository.save(any(Transfer.class))).thenAnswer(i -> i.getArgument(0))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        expectRollback(() -> context.getBean(InflightTransferManager.class).transferStarted(KEY, 600_000L, "OUTGOING"));
    }

    @Test
    public void transactionRequestRolloverRollsBackTheClosureWhenTheReplacementFails() {
        TransactionRequest lost = new TransactionRequest(KEY, 0L);
        lost.setStartedAt(new Date(1_000L));
        TransactionRequestRepository repository = context.getBean(TransactionRequestRepository.class);
        when(repository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));
        when(repository.save(any(TransactionRequest.class))).thenAnswer(i -> i.getArgument(0))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        expectRollback(() -> context.getBean(InflightTransactionRequestManager.class)
                .transactionRequestStarted(KEY, 600_000L, "INCOMING"));
    }

    @Test
    public void batchRolloverRollsBackTheClosureWhenTheReplacementFails() {
        Batch lost = new Batch(KEY, 0L);
        lost.setStartedAt(new Date(1_000L));
        BatchRepository repository = context.getBean(BatchRepository.class);
        when(repository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY)).thenReturn(Collections.singletonList(lost));
        when(repository.save(any(Batch.class))).thenAnswer(i -> i.getArgument(0))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        expectRollback(() -> context.getBean(InflightBatchManager.class).batchStarted(KEY, 600_000L, "UNKNOWN"));
    }

    // A commit failure that is not a DataAccessException does not make KafkaConsumer clear the caches.

    @Test
    public void transferStartThatFailsToCommitLeavesNothingInTheCache() {
        Transfer[] committed = {lostTransfer()};
        TransferRepository repository = context.getBean(TransferRepository.class);
        // the rolled back closure is not visible to the next read
        when(repository.findByWorkflowInstanceKeyOrderByZeebeGenerationAsc(KEY))
                .thenAnswer(i -> Collections.singletonList(committed[0]));
        InflightTransferManager manager = context.getBean(InflightTransferManager.class);

        expectCommitFailure(() -> manager.transferStarted(KEY, 600_000L, "OUTGOING"));
        assertNull(manager.peekGeneration(KEY));

        committed[0] = lostTransfer();
        manager.transferStarted(KEY, 600_000L, "OUTGOING"); // replay
        assertNotNull("the replay must close the lost row again", committed[0].getCompletedAt());
        assertEquals(Long.valueOf(1L), manager.peekGeneration(KEY));
    }

    @Test
    public void callActivityStartThatFailsToCommitLeavesNothingInTheCache() {
        InflightTransferManager manager = context.getBean(InflightTransferManager.class);

        expectCommitFailure(() -> manager.transferStarted(KEY, 600_000L, "OUTGOING", false));

        assertNull(manager.peekGeneration(KEY));
    }

    @Test
    public void transactionRequestStartThatFailsToCommitLeavesNothingInTheCache() {
        InflightTransactionRequestManager manager = context.getBean(InflightTransactionRequestManager.class);

        expectCommitFailure(() -> manager.transactionRequestStarted(KEY, 600_000L, "INCOMING"));

        assertNull(manager.peekGeneration(KEY));
    }

    @Test
    public void batchStartThatFailsToCommitLeavesNothingInTheCache() {
        InflightBatchManager manager = context.getBean(InflightBatchManager.class);

        expectCommitFailure(() -> manager.batchStarted(KEY, 600_000L, "UNKNOWN"));

        assertNull(manager.peekGeneration(KEY));
    }

    @Test
    public void variableTransactionThatFailsToCommitLeavesNothingInTheCache() {
        InflightTransferManager manager = context.getBean(InflightTransferManager.class);

        // as RecordParser.importVariable: the transaction is opened outside the manager
        expectCommitFailure(() -> new TransactionTemplate(transactionManager)
                .execute(status -> manager.getOrCreateTransfer(KEY)));

        assertNull(manager.peekGeneration(KEY));
    }

    @Test
    public void failedCommitOnlyEvictsItsOwnKey() {
        InflightTransferManager manager = context.getBean(InflightTransferManager.class);
        manager.transferStarted(KEY + 1, 600_000L, "OUTGOING");

        expectCommitFailure(() -> manager.transferStarted(KEY, 600_000L, "OUTGOING"));

        assertNull(manager.peekGeneration(KEY));
        assertEquals(Long.valueOf(0L), manager.peekGeneration(KEY + 1));
    }

    @Test
    public void committedStartStaysInTheCache() {
        InflightTransferManager manager = context.getBean(InflightTransferManager.class);

        manager.transferStarted(KEY, 600_000L, "OUTGOING");

        assertEquals(Long.valueOf(0L), manager.peekGeneration(KEY));
    }

    private static Transfer lostTransfer() {
        Transfer lost = new Transfer(KEY, 0L);
        lost.setStartedAt(new Date(1_000L));
        return lost;
    }

    private void expectCommitFailure(Runnable start) {
        transactionManager.failNextCommit = true;
        try {
            start.run();
            fail("expected the commit to fail");
        } catch (TransactionSystemException expected) {
            // the record would be replayed from memory
        }
    }

    private void expectRollback(Runnable start) {
        try {
            start.run();
            fail("expected the save to fail");
        } catch (DataAccessResourceFailureException expected) {
            assertEquals(1, transactionManager.rollbacks);
            assertEquals(0, transactionManager.commits);
        }
    }

    /** Spring's own transaction handling (including synchronizations) without a database. */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        private boolean failNextCommit;
        private boolean active;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failNextCommit) {
                failNextCommit = false;
                throw new TransactionSystemException("Could not commit");
            }
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active = false;
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        TransferRepository transferRepository() {
            return mock(TransferRepository.class);
        }

        @Bean
        TransactionRequestRepository transactionRequestRepository() {
            return mock(TransactionRequestRepository.class);
        }

        @Bean
        BatchRepository batchRepository() {
            return mock(BatchRepository.class);
        }

        @Bean
        TempDocumentStore tempDocumentStore() {
            return new TempDocumentStore();
        }

        @Bean
        InflightTransferManager inflightTransferManager() {
            return new InflightTransferManager();
        }

        @Bean
        InflightTransactionRequestManager inflightTransactionRequestManager() {
            return new InflightTransactionRequestManager();
        }

        @Bean
        InflightBatchManager inflightBatchManager() {
            return new InflightBatchManager();
        }
    }
}
