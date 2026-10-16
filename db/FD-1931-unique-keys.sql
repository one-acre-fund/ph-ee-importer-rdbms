-- FD-1931: schema changes that make importer writes safe under replicas and replays.
-- Run once per TENANT schema (every schema_name in tenants.tenant_server_connections), before the
-- 1.2.0 image starts. New rows are written with VERSION 1, existing rows must read as 0 (not NULL).
-- The shared tenants database gets db/FD-1931-tenants-db.sql instead.
-- Review the duplicate reports before running the DELETEs: they keep the row with the lowest ID.

-- Transfer identity: the transaction id, or '' for an ordinary transfer that has none yet
-- (two such rows for one instance are duplicates). A batch CSV row without a request id keeps
-- NULL, which never compares equal, so distinct CSV rows are left alone. The TRANSFER_KEY
-- generated column below uses the same expression.
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION,
       IFNULL(TRANSACTION_ID, IF(BATCH_ID IS NULL, '', NULL)) AS TRANSFER_KEY, COUNT(*) AS n
  FROM transfers GROUP BY 1, 2, 3 HAVING n > 1 AND TRANSFER_KEY IS NOT NULL;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, COUNT(*) AS n
  FROM transaction_requests GROUP BY 1, 2 HAVING n > 1;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, COUNT(*) AS n
  FROM batches GROUP BY 1, 2 HAVING n > 1;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, NAME, TIMESTAMP, COUNT(*) AS n
  FROM variables GROUP BY 1, 2, 3, 4 HAVING n > 1;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, ELEMENT_ID, INTENT, COUNT(*) AS n
  FROM tasks GROUP BY 1, 2, 3, 4 HAVING n > 1;

-- Ordinary transfers are inserted before their transactionId variable arrives, so their
-- duplicates have TRANSACTION_ID NULL on both rows; plain = would never match them.
DELETE a FROM transfers a JOIN transfers b
  ON a.WORKFLOW_INSTANCE_KEY = b.WORKFLOW_INSTANCE_KEY
 AND a.ZEEBE_GENERATION <=> b.ZEEBE_GENERATION
 AND IFNULL(a.TRANSACTION_ID, IF(a.BATCH_ID IS NULL, '', NULL))
     = IFNULL(b.TRANSACTION_ID, IF(b.BATCH_ID IS NULL, '', NULL))
 AND a.ID > b.ID;
DELETE a FROM transaction_requests a JOIN transaction_requests b
  ON a.WORKFLOW_INSTANCE_KEY = b.WORKFLOW_INSTANCE_KEY
 AND a.ZEEBE_GENERATION <=> b.ZEEBE_GENERATION
 AND a.ID > b.ID;
DELETE a FROM batches a JOIN batches b
  ON a.WORKFLOW_INSTANCE_KEY = b.WORKFLOW_INSTANCE_KEY
 AND a.ZEEBE_GENERATION <=> b.ZEEBE_GENERATION
 AND a.ID > b.ID;
DELETE a FROM variables a JOIN variables b
  ON a.WORKFLOW_INSTANCE_KEY = b.WORKFLOW_INSTANCE_KEY
 AND a.ZEEBE_GENERATION <=> b.ZEEBE_GENERATION
 AND a.NAME = b.NAME AND a.TIMESTAMP = b.TIMESTAMP
 AND a.ID > b.ID;
DELETE a FROM tasks a JOIN tasks b
  ON a.WORKFLOW_INSTANCE_KEY = b.WORKFLOW_INSTANCE_KEY
 AND a.ZEEBE_GENERATION <=> b.ZEEBE_GENERATION
 AND a.ELEMENT_ID = b.ELEMENT_ID AND a.INTENT = b.INTENT
 AND a.ID > b.ID;

-- Optimistic lock column for the rows the importer updates in place.
ALTER TABLE transfers ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;
ALTER TABLE transaction_requests ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;
ALTER TABLE batches ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;

-- Row identity for transfers. MySQL lets any number of NULLs through a unique index, and an
-- ordinary transfer has TRANSACTION_ID NULL until its variable arrives, so two consumers could
-- insert the same (instance, generation) twice. The generated column gives such a row the
-- identity '' and keeps the transaction id for batch CSV rows (NULL, hence unchecked, only for a
-- CSV row without a request id). The application never writes this column. Needs MySQL 5.7.6+.
ALTER TABLE transfers ADD COLUMN TRANSFER_KEY VARCHAR(255)
  GENERATED ALWAYS AS (IFNULL(TRANSACTION_ID, IF(BATCH_ID IS NULL, '', NULL))) STORED;

ALTER TABLE transfers ADD UNIQUE KEY uk_transfers_instance_generation_key
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, TRANSFER_KEY);
ALTER TABLE transaction_requests ADD UNIQUE KEY uk_transaction_requests_instance_generation
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION);
ALTER TABLE batches ADD UNIQUE KEY uk_batches_instance_generation
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION);
ALTER TABLE variables ADD UNIQUE KEY uk_variables_instance_generation_name_timestamp
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, NAME, TIMESTAMP);
ALTER TABLE tasks ADD UNIQUE KEY uk_tasks_instance_generation_element_intent
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, ELEMENT_ID, INTENT);

-- Child process instance (call activity) to parent transfer. The importer used to keep this
-- only in memory, so child variables lost their parent after a rebalance or a pod loss.
CREATE TABLE IF NOT EXISTS call_activity_links (
  ID BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  CHILD_INSTANCE_KEY BIGINT NOT NULL,
  PARENT_INSTANCE_KEY BIGINT NOT NULL,
  UNIQUE KEY uk_call_activity_links_child (CHILD_INSTANCE_KEY)
);
