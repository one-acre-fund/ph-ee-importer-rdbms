-- FD-1931: unique keys that make importer writes safe under replicas and replays.
-- New schemas get these from the entity annotations; existing schemas need this script.
-- Run once per tenant schema (every schema_name in tenants.tenant_server_connections).
-- Review duplicates before deleting: the DELETEs keep the row with the lowest ID.

SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, TRANSACTION_ID, COUNT(*) AS n
  FROM transfers GROUP BY 1, 2, 3 HAVING n > 1;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, COUNT(*) AS n
  FROM transaction_requests GROUP BY 1, 2 HAVING n > 1;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, COUNT(*) AS n
  FROM batches GROUP BY 1, 2 HAVING n > 1;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, NAME, TIMESTAMP, COUNT(*) AS n
  FROM variables GROUP BY 1, 2, 3, 4 HAVING n > 1;
SELECT WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, ELEMENT_ID, INTENT, COUNT(*) AS n
  FROM tasks GROUP BY 1, 2, 3, 4 HAVING n > 1;

DELETE a FROM transfers a JOIN transfers b
  ON a.WORKFLOW_INSTANCE_KEY = b.WORKFLOW_INSTANCE_KEY
 AND a.ZEEBE_GENERATION <=> b.ZEEBE_GENERATION
 AND a.TRANSACTION_ID = b.TRANSACTION_ID
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

-- Optimistic lock column for the rows the importer updates in place. Run this before the
-- 1.2.0 image starts: new rows are written with version 1, existing rows must read as 0 (not NULL).
ALTER TABLE transfers ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;
ALTER TABLE transaction_requests ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;
ALTER TABLE batches ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;

ALTER TABLE transfers ADD UNIQUE KEY uk_transfers_instance_generation_transaction
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, TRANSACTION_ID);
ALTER TABLE transaction_requests ADD UNIQUE KEY uk_transaction_requests_instance_generation
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION);
ALTER TABLE batches ADD UNIQUE KEY uk_batches_instance_generation
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION);
ALTER TABLE variables ADD UNIQUE KEY uk_variables_instance_generation_name_timestamp
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, NAME, TIMESTAMP);
ALTER TABLE tasks ADD UNIQUE KEY uk_tasks_instance_generation_element_intent
  (WORKFLOW_INSTANCE_KEY, ZEEBE_GENERATION, ELEMENT_ID, INTENT);
