-- FD-1931: run once against the shared tenants database (datasource.core.schema, normally
-- "tenants"), not per tenant schema. The importer creates this table itself on startup through
-- EclipseLink DDL generation; the statement is here for environments where that is disabled.
--
-- Process definition key to bpmnProcessId-tenant, learned from Zeebe DEPLOYMENT records. With
-- several replicas the deployment may be consumed by a different pod than the one that later
-- receives VARIABLE or JOB records without a bpmnProcessId, so the mapping has to be shared.
-- A partition that stays stuck on "is not registered yet" means the deployment record was never
-- seen (exported before the importer existed); insert the mapping here to release it.
CREATE TABLE IF NOT EXISTS process_definitions (
  ID BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  PROCESS_DEFINITION_KEY BIGINT NOT NULL,
  BPMN_PROCESS_ID VARCHAR(255) NOT NULL,
  UNIQUE KEY uk_process_definitions_key (PROCESS_DEFINITION_KEY)
);
