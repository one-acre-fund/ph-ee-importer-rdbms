package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.parent.GenerationRow;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory cache of the generation row each workflow instance key is currently writing to, and
 * the rules that pick a row for every incoming record (see {@link GenerationWindows}).
 * <p>
 * Only the newest, open row of a key is cached. A record older than that row, or one belonging to
 * a completed generation (Kafka redelivery, replay), is written to its own row without touching
 * the cache, so it can never overwrite the live generation and the live generation's records can
 * never land on a historical row.
 */
abstract class InflightGenerationManager<T extends GenerationRow> {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Map<Long, T> inflight = new HashMap<>();

    /**
     * An open row with no activity for longer than this, when a new record for its key arrives,
     * belongs to a process Zeebe lost (broker reset) and is closed; the record opens a new
     * generation. 0 disables the check.
     */
    @Value("${importer.generation.max-idle-minutes:10080}")
    private long maxIdleMinutes;

    /** Every row of the key, ordered by generation ascending; an empty list when there are none. */
    protected abstract List<T> loadRows(Long workflowInstanceKey);

    protected abstract T newRow(Long workflowInstanceKey, long zeebeGeneration);

    protected abstract T persist(T row);

    protected abstract Long instanceKey(T row);

    protected abstract String kind();

    /**
     * The row's business status is final. Such a row gets its COMPLETED_AT from the status variable,
     * before the ELEMENT_COMPLETED of its process arrives.
     */
    protected boolean hasTerminalStatus(T row) {
        return false;
    }

    /** Row for the record being processed on this thread, created when it opens a new generation. */
    protected T getOrCreate(Long workflowInstanceKey) {
        return resolveRow(workflowInstanceKey, ThreadLocalContextUtil.getRecordTimestamp(), false);
    }

    /**
     * @param rootStart the record is the root process ELEMENT_ACTIVATING, the only record that can
     *                  prove an open row belongs to an earlier process instance with the same key
     */
    protected T resolveRow(Long workflowInstanceKey, Long recordTimestamp, boolean rootStart) {
        synchronized (inflight) {
            T live = inflight.get(workflowInstanceKey);
            if (live != null && GenerationWindows.acceptsLiveRow(live, recordTimestamp, rootStart, maxIdleMillis())) {
                GenerationWindows.touch(live, recordTimestamp);
                return live;
            }

            List<T> rows = withLiveRow(loadRows(workflowInstanceKey), live);
            GenerationWindows.Resolution<T> resolution =
                    GenerationWindows.resolve(rows, recordTimestamp, rootStart, live, maxIdleMillis());

            if (resolution.rowToClose != null) {
                closeLostRow(workflowInstanceKey, resolution.rowToClose, recordTimestamp);
            }

            T row;
            if (resolution.opensNewGeneration()) {
                row = newRow(workflowInstanceKey, resolution.nextGeneration);
                inflight.put(workflowInstanceKey, row);
                logger.debug("started in-flight {} {} with generation {}", kind(), workflowInstanceKey, resolution.nextGeneration);
            } else {
                row = resolution.row;
                if (row.getZeebeGeneration() == null) {
                    row.setZeebeGeneration(0L);
                }
                if (row != live) {
                    if (resolution.latest && row.getCompletedAt() == null) {
                        inflight.put(workflowInstanceKey, row);
                    } else {
                        logger.info("record at {} for {} {} belongs to earlier generation {}, writing to that row",
                                recordTimestamp, kind(), workflowInstanceKey, row.getZeebeGeneration());
                    }
                }
            }
            GenerationWindows.touch(row, recordTimestamp);
            return row;
        }
    }

    /**
     * Row the record belongs to without creating or caching anything; null when the record is
     * newer than every generation of the key.
     */
    protected T findExisting(Long workflowInstanceKey, Long recordTimestamp) {
        synchronized (inflight) {
            T live = inflight.get(workflowInstanceKey);
            if (live != null && (recordTimestamp == null || !GenerationWindows.precedesStart(live, recordTimestamp))) {
                return live;
            }
            List<T> rows = withLiveRow(loadRows(workflowInstanceKey), live);
            return GenerationWindows.resolve(rows, recordTimestamp, false, live, 0).row;
        }
    }

    /**
     * Completes the generation the ELEMENT_COMPLETED record belongs to. Returns the completed row,
     * or null when there was none, it was already completed (redelivery) or it started after the
     * record (completion of a run that has no row).
     */
    protected T end(Long workflowInstanceKey, Long timestamp) {
        synchronized (inflight) {
            T live = inflight.get(workflowInstanceKey);
            T row;
            if (live != null && (timestamp == null || !GenerationWindows.precedesStart(live, timestamp))) {
                row = live;
            } else {
                List<T> rows = withLiveRow(loadRows(workflowInstanceKey), live);
                // An idle row is still the one this completion closes, so the idle rule is off here.
                row = GenerationWindows.resolve(rows, timestamp, false, null, 0).row;
                boolean provisional = false;
                if (row == null) {
                    row = provisionallyCompleted(rows, timestamp);
                    provisional = row != null;
                }
                if (row == null || (timestamp != null && GenerationWindows.startsAfter(row, timestamp))
                        || (!provisional && row != live && row.getCompletedAt() != null)) {
                    logger.error("completed event at {} arrived for non existent {} {} or it was already finished!",
                            timestamp, kind(), workflowInstanceKey);
                    return null;
                }
            }
            if (inflight.get(workflowInstanceKey) == row) {
                inflight.remove(workflowInstanceKey);
            }
            row.setCompletedAt(new Date(timestamp == null ? System.currentTimeMillis() : timestamp));
            GenerationWindows.touch(row, timestamp);
            T saved = persist(row);
            logger.debug("{} {} generation {} finished", kind(), workflowInstanceKey, row.getZeebeGeneration());
            return saved == null ? row : saved;
        }
    }

    /**
     * Persists the entity and keeps the managed copy in the cache. EclipseLink's merge leaves the
     * passed object's version untouched, so saving the same instance twice would fail the
     * optimistic lock on the second save.
     */
    public T save(T entity) {
        GenerationWindows.touch(entity, ThreadLocalContextUtil.getRecordTimestamp());
        T saved = persist(entity);
        if (saved != null) {
            synchronized (inflight) {
                if (inflight.get(instanceKey(entity)) == entity) {
                    inflight.put(instanceKey(entity), saved);
                }
            }
            return saved;
        }
        return entity;
    }

    public void clear() {
        synchronized (inflight) {
            inflight.clear();
        }
    }

    /**
     * Memory-only lookup. Used on the hot path so child events do not query the DB
     * after the process row is already inflight.
     */
    public Long peekGeneration(Long workflowInstanceKey) {
        synchronized (inflight) {
            T row = inflight.get(workflowInstanceKey);
            return row == null ? null : row.getZeebeGeneration();
        }
    }

    void setMaxIdleMinutes(long maxIdleMinutes) {
        this.maxIdleMinutes = maxIdleMinutes;
    }

    private long maxIdleMillis() {
        return maxIdleMinutes * 60_000L;
    }

    private void closeLostRow(Long workflowInstanceKey, T row, long recordTimestamp) {
        GenerationWindows.closeLost(row, recordTimestamp);
        logger.warn("{} {} generation {} (started {}, last activity {}) never completed and a newer process "
                        + "instance with the same key started at {}; closing it at {} and opening a new generation",
                kind(), workflowInstanceKey, row.getZeebeGeneration(), row.getStartedAt(),
                row.getLastEventAt(), recordTimestamp, row.getCompletedAt());
        persist(row);
        if (inflight.get(workflowInstanceKey) == row) {
            inflight.remove(workflowInstanceKey);
        }
    }

    /**
     * The newest row when its COMPLETED_AT came from a terminal status variable and the real
     * completion is only arriving now, with the row no longer cached (rebalance). A completion never
     * starts a generation, so one that is newer than every row can only belong to the newest; a
     * replayed completion is never later than the COMPLETED_AT it set itself. Beyond the idle limit
     * it is the completion of another run of the key.
     */
    private T provisionallyCompleted(List<T> rows, Long timestamp) {
        if (rows.isEmpty() || timestamp == null) {
            return null;
        }
        T newest = rows.get(rows.size() - 1);
        boolean tooLate = maxIdleMillis() > 0 && timestamp - newest.getCompletedAt().getTime() > maxIdleMillis();
        return hasTerminalStatus(newest) && !tooLate ? newest : null;
    }

    /**
     * The database rows with the cached instance in place of its stored copy: the cached one has
     * the current optimistic-lock version and the in-memory LAST_EVENT_AT, and a new row may not
     * have been saved yet.
     */
    private List<T> withLiveRow(List<T> rows, T live) {
        List<T> result = new ArrayList<>(rows);
        if (live == null) {
            return result;
        }
        for (int i = 0; i < result.size(); i++) {
            T row = result.get(i);
            if (row == live || (live.getId() != null && Objects.equals(live.getId(), row.getId()))) {
                result.set(i, live);
                return result;
            }
        }
        result.add(live);
        return result;
    }
}
