# Release Notes

## Version 1.2.0 - Up to date with Mifos Version 1.0.0-SNAPSHOT
    *  Horizontal scaling
        * [FD-1931] - Fixed Kafka consumer group (KAFKA_CONSUMER_GROUP) and configurable
          concurrency (KAFKA_CONCURRENCY) so replicas share the topic partitions; in-memory
          caches are cleared on rebalance; database errors are retried instead of committed
          past; batch CSV rows are not re-inserted on replay; a redelivered record for a
          completed instance reuses its row instead of opening a new generation; unique
          keys per (instance, generation) with db/FD-1931-unique-keys.sql for existing schemas;
          a database error is retried without limit (the partition stalls, lag grows) instead
          of the record being skipped after ten attempts; transfers, transaction requests and
          batches carry an optimistic lock (VERSION column) so a stale copy held by another
          replica cannot overwrite newer values
        * [FD-1931] - Review follow-ups: routing state no longer lives only in one pod's memory.
          Process definition to bpmnProcessId mappings from DEPLOYMENT records are stored in
          tenants.process_definitions (db/FD-1931-tenants-db.sql) and a record that still cannot
          be routed is retried instead of being parked in memory and committed; call-activity
          child to parent links are stored in call_activity_links; batch completion falls back
          to the batch row's REQUEST_FILE and BATCH_ID when the variables were consumed by another
          replica; a replayed record is matched to the generation that was running at its
          timestamp instead of the latest one; the variable history row and the entity update
          are written in one transaction; transfers get a generated TRANSFER_KEY column so an
          ordinary transfer without a transaction id yet still has a unique identity; the
          migration's duplicate cleanup uses null-safe equality on TRANSACTION_ID

## Version 1.1.1 - Up to date with Mifos Version 1.0.0-SNAPSHOT
    *  DB improvements
        * [FD-1782] - Make zeebe prosess more resilient to duplicate transactions being created in the database

## Version 1.1.0 - Up to date with Mifos Version 1.0.0-SNAPSHOT
    * Payment Hub OPs
        *  Elastic APM + OpenTelemetry Integration
            * [CP-3980] - Integrate Elastic APM with PH
