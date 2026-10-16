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

## Version 1.1.1 - Up to date with Mifos Version 1.0.0-SNAPSHOT
    *  DB improvements
        * [FD-1782] - Make zeebe prosess more resilient to duplicate transactions being created in the database

## Version 1.1.0 - Up to date with Mifos Version 1.0.0-SNAPSHOT
    * Payment Hub OPs
        *  Elastic APM + OpenTelemetry Integration
            * [CP-3980] - Integrate Elastic APM with PH
