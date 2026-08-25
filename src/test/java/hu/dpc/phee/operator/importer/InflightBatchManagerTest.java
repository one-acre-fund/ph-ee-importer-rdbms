package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class InflightBatchManagerTest {

    private static final Long KEY = 300L;

    @Mock
    private BatchRepository batchRepository;

    @InjectMocks
    private InflightBatchManager manager;

    @Before
    public void setUp() {
        when(batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(null);
        when(batchRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY)).thenReturn(null);
    }

    @Test
    public void firstCreateUsesGenerationZero() {
        Batch batch = manager.getOrCreateBatch(KEY);

        assertEquals(Long.valueOf(0L), batch.getZeebeGeneration());
        assertEquals(KEY, batch.getWorkflowInstanceKey());
    }

    @Test
    public void secondCallReusesInflightWithoutExtraDbLookups() {
        Batch first = manager.getOrCreateBatch(KEY);
        Batch second = manager.getOrCreateBatch(KEY);

        assertSame(first, second);
        verify(batchRepository, times(1)).findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY);
        verify(batchRepository, times(1)).findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY);
    }

    @Test
    public void completedThenNewStartAutoBumpsGeneration() {
        Batch completed = new Batch(KEY, 0L);
        completed.setCompletedAt(new Date());
        when(batchRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY)).thenReturn(completed);

        Batch next = manager.getOrCreateBatch(KEY);

        assertEquals(Long.valueOf(1L), next.getZeebeGeneration());
    }

    @Test
    public void reusesOpenDbRowAndBackfillsNullGeneration() {
        Batch open = new Batch(KEY);
        open.setZeebeGeneration(null);
        when(batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(open);

        Batch batch = manager.getOrCreateBatch(KEY);

        assertSame(open, batch);
        assertEquals(Long.valueOf(0L), batch.getZeebeGeneration());
        verify(batchRepository, never()).findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY);
    }

    @Test
    public void variableBeforeStartThenStartReusesSameGeneration() {
        Batch fromVariable = manager.getOrCreateBatch(KEY);
        manager.batchStarted(KEY, 1_000L, "UNKNOWN");

        assertSame(fromVariable, manager.getOrCreateBatch(KEY));
        assertEquals(1_000L, fromVariable.getStartedAt().getTime());
        verify(batchRepository).save(fromVariable);
    }

    @Test
    public void recycledKeyVariableBeforeStartUsesBumpedGeneration() {
        Batch completed = new Batch(KEY, 8L);
        completed.setCompletedAt(new Date());
        when(batchRepository.findTopByWorkflowInstanceKeyOrderByZeebeGenerationDesc(KEY)).thenReturn(completed);

        Batch fromVariable = manager.getOrCreateBatch(KEY);
        assertEquals(Long.valueOf(9L), fromVariable.getZeebeGeneration());

        manager.batchStarted(KEY, 2_000L, "UNKNOWN");
        assertSame(fromVariable, manager.getOrCreateBatch(KEY));
    }

    @Test
    public void startedIsIdempotentWhenAlreadyStarted() {
        Batch batch = manager.getOrCreateBatch(KEY);
        batch.setStartedAt(new Date(50L));

        manager.batchStarted(KEY, 100L, "UNKNOWN");

        assertEquals(50L, batch.getStartedAt().getTime());
        verify(batchRepository, never()).save(batch);
    }

    @Test
    public void endedCompletesInflightAndClearsPeek() {
        Batch batch = manager.getOrCreateBatch(KEY);
        manager.batchStarted(KEY, 1_000L, "UNKNOWN");

        manager.batchEnded(KEY, 2_000L);

        assertEquals(2_000L, batch.getCompletedAt().getTime());
        assertNull(manager.peekGeneration(KEY));
        verify(batchRepository, times(2)).save(batch);
    }

    @Test
    public void endedLoadsOpenRowWhenNotInflight() {
        Batch open = new Batch(KEY, 1L);
        when(batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(open);

        manager.batchEnded(KEY, 3_000L);

        assertEquals(3_000L, open.getCompletedAt().getTime());
        verify(batchRepository).save(open);
    }

    @Test
    public void endedIgnoresMissingOrAlreadyCompleted() {
        when(batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(null);
        manager.batchEnded(KEY, 3_000L);
        verify(batchRepository, never()).save(org.mockito.ArgumentMatchers.any(Batch.class));

        Batch completed = new Batch(KEY, 0L);
        completed.setCompletedAt(new Date(1L));
        when(batchRepository.findFirstByWorkflowInstanceKeyAndCompletedAtIsNullOrderByIdDesc(KEY)).thenReturn(completed);
        manager.batchEnded(KEY, 4_000L);
        verify(batchRepository, never()).save(completed);
    }

    @Test
    public void peekGenerationIsMemoryOnly() {
        assertNull(manager.peekGeneration(KEY));
        manager.getOrCreateBatch(KEY);
        assertEquals(Long.valueOf(0L), manager.peekGeneration(KEY));
    }
}
