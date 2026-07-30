-- ============================================================
-- Migration script (system): ETP-4690
-- Renames the NEO Headless bridge spec/entity from "financial-account-psd2"
-- to "financial-account-bank-connection".
--
-- The entity's JAVA_QUALIFIER must match the @Named qualifier on
-- com.etendoerp.go.schemaforge.FinancialAccountBankConnectionHandler. Without
-- this rename, existing installations keep the old qualifier, NeoServlet finds
-- no handler for the new route and /sws/neo/financial-account-bank-connection
-- returns 404.
--
-- Scope: records where ad_client_id=0 AND ad_org_id=0
-- Idempotent: matched by primary key, guarded on the old value.
-- ============================================================

BEGIN;

-- ETGO_SF_SPEC: 39C8096CCA3D49969EF46D33BB075D58
UPDATE etgo_sf_spec
   SET name        = 'financial-account-bank-connection',
       description = 'Bank connection (PSD2 / Salt Edge) bridge for financial accounts (ETP-4097)',
       updated     = now()
 WHERE etgo_sf_spec_id = '39C8096CCA3D49969EF46D33BB075D58'
   AND name = 'financial-account-psd2';

-- ETGO_SF_ENTITY: 6FC45AC1A1EA437D96F2871FD71DD242
UPDATE etgo_sf_entity
   SET name            = 'financial-account-bank-connection',
       java_qualifier  = 'financial-account-bank-connection',
       updated         = now()
 WHERE etgo_sf_entity_id = '6FC45AC1A1EA437D96F2871FD71DD242'
   AND name = 'financial-account-psd2';

COMMIT;
