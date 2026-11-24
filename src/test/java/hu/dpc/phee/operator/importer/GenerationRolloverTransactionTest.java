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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Closing a lost row and saving its replacement commit or roll back together. */
public class GenerationRolloverTransactionTest {

    private static final Long KEY = 800L;

    private AnnotationConfigApplicationContext context;
    private PlatformTransactionManager transactionManager;

    @Before
    public void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        transactionManager = context.getBean(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus(true));
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
        verify(transactionManager, times(1)).commit(any());
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

    private void expectRollback(Runnable start) {
        try {
            start.run();
            fail("expected the save to fail");
        } catch (DataAccessResourceFailureException expected) {
            verify(transactionManager, times(1)).rollback(any());
            verify(transactionManager, never()).commit(any());
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
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
