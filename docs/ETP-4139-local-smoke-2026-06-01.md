# ETP-4139 Local Smoke Evidence - 2026-06-01

Local target:

- URL: `http://localhost:8080/etendo_sf2`
- SWS login URL: `http://localhost:8080/etendo_sf2/sws/login`
- Containers: `etendo_sf2-tomcat-1` and `etendo_sf2-db-1`
- Database: PostgreSQL `etendo`, user `tad`
- Branch: `feature/ETP-4139`

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Login page | Passed | `GET /security/Login` returned `200` with the Etendo login page. |
| NEO readiness | Passed | `GET /sws/neo/health/ready` returned `200` and `{"status":"ready"}`. |
| NEO token login | Passed | `POST http://localhost:8080/etendo_sf2/sws/login` with local admin credentials returned a JWT. |
| Preview file read when absent | Passed | `GET /sws/neo/preview-file?specName=sales-order&recordId=SMOKE-ETP-4139` returned `200` and `{}`. |
| Preview file save/read | Passed | `POST /sws/neo/preview-file` returned `200` with id `24877A8001BA49E1A63CCEF69B24CA86`; the follow-up `GET` returned `smoke-é-ETP-4139.pdf`, MIME type `application/pdf`, and the expected Base64 payload. |
| Document download missing token | Passed | `GET /sws/neo/document-download/` returned `400` with `Missing download token`. |
| Document download invalid token | Passed | `GET /sws/neo/document-download/not-a-valid-token` returned `403` with `Invalid or expired link`. |
| Sales order email contract without recipient | Passed | `POST /sws/neo/email-contracts/sales-order-send/send` for order `7AF257C48A8F4B56BAA3207FCC282016` returned `400` / `VALIDATION_FAILED` with `Email recipient is invalid`. |
| Sales order email contract with temporary recipient | Passed | After temporarily setting `C_BPARTNER.EM_ETGO_EMAIL` to `smoke.etp4139@example.com`, the same contract returned `400` / `VALIDATION_FAILED` with `Document download link is not configured`. This confirms the contract reaches download-link configuration validation after recipient resolution. |
| Runtime email provider configuration | Passed | Local `Openbravo.properties` was temporarily configured with a mock provider at `http://host.docker.internal:18999/email` and signed document download properties, then Tomcat was restarted. |
| Sales order happy path | Passed | With order `7AF257C48A8F4B56BAA3207FCC282016`, a PDF preview row was saved and `POST /sws/neo/email-contracts/sales-order-send/send` returned `200` / `SENT`, `duplicate=false`, and `providerStatus=202`. |
| Sales order idempotency | Passed | Repeating the same order send with the same idempotency key returned `200` / `DUPLICATE`; the mock provider did not receive a second order email payload. |
| Sales order download link | Passed | The mock provider payload included a contract-generated `download_link`; `GET` on the link returned `200`, `Content-Type: application/pdf`, and attachment filename `sales-order-smoke.pdf`. |
| Sales invoice happy path | Passed | With invoice `BF1D5905BBF344B1BC7CB8FEB0DF9CB4`, a PDF preview row was saved and `POST /sws/neo/email-contracts/sales-invoice-send/send` returned `200` / `SENT`, `duplicate=false`, and `providerStatus=202`. |
| Sales invoice download link | Passed | The mock provider payload included a contract-generated `download_link`; `GET` on the link returned `200`, `Content-Type: application/pdf`, and attachment filename `sales-invoice-smoke.pdf`. |
| Provider payload passthrough protection | Passed | Sending provider fields (`to`, `template`, `data`) directly in the contract command returned `400` with `Email contract commands cannot include provider field: template`. |
| Sales quotation subtype guard | Passed | Unit coverage confirms the quotation resolver only accepts quotation/proposal subtypes (`OB`/`ON`) and the order resolver rejects them. Runtime smoke with Standard Order `7AF257C48A8F4B56BAA3207FCC282016` against `sales-quotation-send` returned `404` / `VALIDATION_FAILED` with `Email document record was not found`. The local seed data did not include a real quotation row for a full happy-path smoke. |
| Smoke data cleanup | Passed | Temporary preview rows were deleted and `C_BPARTNER.EM_ETGO_EMAIL` for `0ABDA2D3D6C249598F3564C566B8C511` was restored to `null`; DB verification returned `remaining_smoke_preview = 0`. |

## Build and Test Evidence

- Focused unit tests passed from the Etendo root:

  ```bash
  ./gradlew test --tests com.etendoerp.go.schemaforge.email.contracts.SalesDocumentEmailContractsTest --tests com.etendoerp.go.schemaforge.email.EmailFrameworkValueObjectsTest --tests com.etendoerp.go.schemaforge.email.InitialEmailContractsTest --tests com.etendoerp.go.schemaforge.email.TransactionalEmailServiceTest
  ```

- `./gradlew smartbuild` was rerun with Java 17 after an initial Java 24 launcher build deployed class files incompatible with the local Tomcat Java 17 runtime.
- Deployed bytecode was verified as Java 17 (`major version: 61`), and local readiness returned `200` / `{"status":"ready"}` after Tomcat finished loading the redeployed app.
