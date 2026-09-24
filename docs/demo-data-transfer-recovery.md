# Recover a paid demo data transfer that was never requested

Use this procedure only for a purchase that is already `PROVISIONED` and whose transfer status is
`NOT_REQUESTED` because an older checkout did not persist the Products/Contacts choice. It starts
the existing transfer worker; it does not create a checkout or charge the account. A failed job
already has `POST /sws/go/demo-data-transfer/retry` and must not be recovered this way.

A selected Contacts transfer includes the Business Partner fields, its Contact/Person (`AD_User`)
rows, and its Location/Address rows with their `C_Location` address data. Contact login credentials
and passwords are not transferred. Product and contact progress reads use the most recently updated
status preference because older retries may leave duplicate preference rows.

Run after deploying the corrected Go code with `demo-data-transfer` enabled. Confirm the account's
original choice with the buyer before setting `products` and `contacts`; do not infer it from the
presence of source rows. Use a transaction and keep the purchase ID and client IDs in the operator
record. The values below are placeholders, never application defaults.

## Verify first

```sql
SELECT request_id, account_email, client_name, checkout_status,
       paid_at, created_client_id
FROM etgo_checkout_request
WHERE request_id = '<purchase-request-id>';

SELECT ad_client_id, name FROM ad_client
WHERE ad_client_id IN ('<source-demo-client-id>', '<target-productive-client-id>');

SELECT attribute, value, visibleat_client_id
FROM ad_preference
WHERE (attribute = 'ETGO_DDTSelection.<purchase-request-id>'
       AND visibleat_client_id = '0')
   OR (attribute LIKE 'ETGO_DemoDataTransfer%'
       AND visibleat_client_id = '<target-productive-client-id>');
```

The purchase must be paid and `PROVISIONED`, `created_client_id` must be the target, source and
target IDs must differ, and the final query must return no rows. Verify the source is the account's
demo tenant in the environment list. The guarded transaction below also requires its explicit
`ETGO_EnvironmentType=DEMO` lifecycle marker and rejects a productive plan marker. A legacy demo
without that lifecycle marker needs separate investigation; do not bypass the guard. Confirm target
product/contact search keys that already exist:
the worker updates matching keys instead of inserting duplicates.

## Backfill the missing selection and start projection

In `psql`, set the three verified IDs and the confirmed choices. The guards abort the whole
transaction if the purchase, account link, or transfer state changed since verification.

```sql
\set purchase_request_id '<purchase-request-id>'
\set source_demo_client_id '<source-demo-client-id>'
\set target_productive_client_id '<target-productive-client-id>'
\set transfer_products 'Y'
\set transfer_contacts 'Y'

BEGIN;
CREATE TEMP TABLE transfer_recovery_input ON COMMIT DROP AS
SELECT :'purchase_request_id'::text AS request_id,
       :'source_demo_client_id'::text AS source_id,
       :'target_productive_client_id'::text AS target_id,
       :'transfer_products'::text AS products,
       :'transfer_contacts'::text AS contacts;

DO $$
DECLARE
  input transfer_recovery_input%ROWTYPE;
  purchase etgo_checkout_request%ROWTYPE;
BEGIN
  SELECT * INTO STRICT input FROM transfer_recovery_input;
  IF input.source_id = input.target_id OR input.products NOT IN ('Y', 'N')
      OR input.contacts NOT IN ('Y', 'N') THEN
    RAISE EXCEPTION 'Invalid source, target, or selection';
  END IF;
  SELECT * INTO STRICT purchase FROM etgo_checkout_request
   WHERE request_id = input.request_id FOR UPDATE;
  IF purchase.checkout_status <> 'PROVISIONED' OR purchase.paid_at IS NULL
      OR purchase.created_client_id <> input.target_id THEN
    RAISE EXCEPTION 'Purchase is not paid and provisioned for this target';
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM ad_user u
       WHERE u.ad_client_id = input.source_id AND u.isactive = 'Y'
         AND (lower(u.email) = lower(purchase.account_email)
              OR lower(u.username) = lower(purchase.account_email))) THEN
    RAISE EXCEPTION 'Source demo is not linked to the purchasing account';
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM ad_preference p
       WHERE p.ad_client_id = input.source_id AND p.isactive = 'Y'
         AND p.attribute = 'ETGO_EnvironmentType'
         AND upper(p.value) = 'DEMO')
      OR EXISTS (
      SELECT 1 FROM ad_preference p
       WHERE p.visibleat_client_id = input.source_id AND p.isactive = 'Y'
         AND p.attribute = 'ETGO_TenantPlan'
         AND lower(p.value) = 'productive') THEN
    RAISE EXCEPTION 'Source is not an explicitly marked demo tenant';
  END IF;
  IF EXISTS (SELECT 1 FROM ad_preference p
             WHERE p.isactive = 'Y'
               AND ((p.attribute = 'ETGO_DDTSelection.' || input.request_id
                     AND p.visibleat_client_id = '0')
                    OR (p.attribute LIKE 'ETGO_DemoDataTransfer%'
                        AND p.visibleat_client_id = input.target_id))) THEN
    RAISE EXCEPTION 'Transfer selection or target state already exists';
  END IF;

  INSERT INTO ad_preference
      (ad_preference_id, ad_client_id, ad_org_id, isactive, created, createdby,
       updated, updatedby, attribute, value, ispropertylist, visibleat_client_id, selected)
  SELECT get_uuid(), '0', '0', 'Y', now(), '0', now(), '0', item.attribute,
         item.value, 'N', item.scope, 'N'
    FROM (VALUES
      ('ETGO_DDTSelection.' || input.request_id, input.products || input.contacts, '0'),
      ('ETGO_DemoDataTransferSource', input.source_id, input.target_id),
      ('ETGO_DemoDataTransferProducts', input.products, input.target_id),
      ('ETGO_DemoDataTransferContacts', input.contacts, input.target_id),
      ('ETGO_DemoDataTransferStatus', 'RUNNING', input.target_id)
    ) AS item(attribute, value, scope);
END $$;
COMMIT;
```

Open `GET /sws/go/demo-data-transfer` with an authenticated session in the target environment.
That read resumes a persisted `RUNNING` job. Poll until `COMPLETED` or `FAILED`. On `FAILED`,
inspect `failureReason`, correct the cause, and use the normal retry endpoint. Check source and
target product/contact counts and prices after completion. The transfer does not include sales
orders.
