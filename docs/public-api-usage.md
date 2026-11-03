# Public API usage per tenant (ETP-6057)

How Etendo GO counts the requests every tenant makes through the public API, per API key, and
turns them into a daily billing figure. Design and reference in one page: what is counted and what
is not, the data model, how counts reach the database and what can be lost, multi-instance
behaviour, retention, the opt-out, and the queries an operator runs.

Audience: the developer who touches the public API path or the usage tables, and the operator who
reads per-tenant consumption or puts a quota on it.

| For | Read |
|---|---|
| The public API itself (gateway, keys, JSON:API contract) | `schema_forge/docs/public-api.md` |
| How a key is created and what its token carries | [`public-api-key-provisioning.md`](public-api-key-provisioning.md) |
| `ETGO_USAGE_EVENT`, the recorder and the other event types | [`neo-headless.md`](neo-headless.md) §4.15 |
| Billing resources, quotas, the usage job | [`plan-quotas.md`](plan-quotas.md) (the resource: §4.8) |

---

## 1. The unit: one answered request

**One public API request is one request answered by NEO with a public API key's token**, whatever
the status. A request is a *public API request* when its OAuth2 token belongs to a user-owned public
API key: the key row (`ETGO_OAUTH2_CLIENT.SCOPES`) carries `neo:public-api-key`. That is decided on
the **key's** stored scopes, not the token's: the token exchange keeps only grantable scopes
(`OAuth2ClientPolicy.parseScopes`), so the marker never reaches a token. Shared NEO code decides it
from that scope and from the shape of the path only; no spec, entity or tenant is named anywhere.

### 1.1 Counted

| Where | What | Status |
|---|---|---|
| NEO, any `/sws/neo/*` request authenticated with a public key's token | Accepted requests: reads, lists, creates, updates, deletes, actions | 2xx |
| NEO, `POST /sws/neo/batch` | **One request per operation** (kind `batchOperation`), each with its own spec and entity, method `POST` (every operation is a create) and the batch's status: a synchronous batch is one transaction, so its operations share its outcome; an asynchronous one answers `202` before they run. A batch refused before its operations are read (empty or malformed body) counts once. Decided so a batch cannot be used to dodge per-tier request limits | as answered |
| NEO, refused after the token is identified | Commercial access refused (402), token without the scope (403), expired, revoked or deactivated key (401), RBAC refusal (403), any handler 4xx/5xx, a classified exception | as answered |
| `/oauth2/token`, `grant_type=client_credentials` | A public key presented with a **wrong secret** — the key row and its tenant are known, no token exists. **Recorded but not billed** (§2.2) | 401 |

The status counted is the one the client got: `NeoServlet.processRequest` records in a `finally`
around the whole request and reads `response.getStatus()`, so every write path — authentication,
RBAC, a handler, `NeoErrorResponses` — is covered without each of them knowing about it.

### 1.2 Not counted

- **Refusals the gateway answers without calling NEO**: rate limit (429), a missing or malformed
  key, request validation (400), content negotiation (406/415), missing `If-Match` (428), unknown
  resource or operation (404 answered by the gateway). They never reach Etendo, so Etendo cannot
  attribute them. They belong to the per-tier limits task, which counts at the gateway.
- **The re-read of an action** (§3): the gateway answers one public action with two NEO calls; only
  the action counts.
- **A caller without a tenant**: the one fallback chain (owner organization's client, else the key
  role's client — the same on the token path and the key-row path) ends on nothing or on the System
  client `0` (a System-role key on organization `*`). Ignored and reported at WARN (throttled),
  instead of a row billed to nobody.
- **A token exchange that succeeds**, or one for an unknown `client_id` (no key to attribute).
- **In the billed total, the wrong-secret 401 of the token exchange** — recorded, not billed (§2.2).
- **The MCP channel** (`/sws/mcp`, `/mcp`): a public API key's token is **refused there with 403**
  (`insufficient_scope`), both by `OAuth2Filter` on the filtered mappings and by `McpServlet`'s own
  token validation. A public key is for the public API only, where it is counted and (later) limited;
  an MCP agent connects with its own OAuth client. Without the refusal the MCP would be an uncounted
  way around billing. Declared divergence: [`neo-headless.md`](neo-headless.md) §4.12.9.
- Requests of a session cookie or a legacy JWT: they are not public API requests.

---

## 2. Data model

### 2.1 Raw rows: `ETGO_USAGE_EVENT`, type `publicapi.request`

Requests are never written one row per request. They are counted in memory
(`PublicApiUsageCounter`) per **bucket** and written once a minute as one row per bucket:

| Bucket dimension | Column / property |
|---|---|
| Tenant | `AD_CLIENT_ID` — the client of the key's owner organization (the token's `AD_ORG_ID`); when that organization is `*` or unknown, the client of the key's role (`PublicApiKeyAttribution.tenant`, one chain for the token and the key-row path). The System client `0` is never a tenant |
| Owner organization | `AD_ORG_ID` |
| Key's user and role | `AD_USER_ID`, `AD_ROLE_ID` |
| Key | `properties.keyId` — the key's public `CLIENT_IDENTIFIER`. Never the secret, a token or a hash |
| Kind | `properties.kind`: `request` (a NEO request), `batchOperation` (one operation of a batch), `tokenExchange` (a wrong-secret exchange). Set only by the backend's factories, never from the path |
| Spec | `TARGET` (first NEO path segment, or the operation's spec in a batch); `/oauth2/token` for a wrong-secret exchange — a target no path can produce, since `/` never survives normalization |
| Entity | `properties.entity` (second segment) |
| Action | `properties.actionName`, on a NEO action route only: `/{spec}/{entity}/{recordId}/action/{name}` (`NeoServletSupport.parseActionOrRecordPath`: fourth segment `action`, fifth the name) — what the gateway's `/actions/{action}` becomes |
| HTTP method | `ACTION` (`GET`, `POST`, …) |
| Status | `properties.status`; also `OUTCOME` (`ok` below 400, `error` from 400) and `ERROR_CODE` (the status, on errors) |
| Local day | the day of `OCCURRED_AT` (a bucket never straddles midnight) |

Plus `EVENT_TYPE = 'publicapi.request'`, `SOURCE = 'backend'`, and **`properties.count`, the
number of requests the row stands for**. `OCCURRED_AT` is the flush time, clamped to the bucket's
day (a retried bucket of yesterday is dated at 23:59:59.999 of yesterday).

No record id, query string or body is ever stored. The path segments are normalized
(`PublicApiCall.of`: only `[A-Za-z0-9_.-]`, at most 60 characters), and at most 10 000 buckets are
held: past that, a new bucket is counted in the caller's overflow bucket (`TARGET = '_other'`), so a
client walking random paths cannot grow memory and per-tenant/per-key totals stay right.

**Only `SOURCE = 'backend'` rows are real.** `publicapi.request` is a server-only type
(`UsageEventTypes.isServerOnly`): the UI usage endpoint (`POST /sws/neo/usage`,
[`neo-headless.md`](neo-headless.md) §4.15) drops it and counts it in `dropped`, never a 4xx. As a
second line, the daily counter and every query below also filter on `backend` (the only source the
UI endpoint can never write), so a browser cannot add to a tenant's figure.

### 2.2 Daily totals: the `publicApiRequests` billing resource

`PublicApiRequestCounter` (`@Named("publicApiRequests")`, a `UsageResourceCounter`) sums
`properties.count` of the `backend` rows per tenant per day. Every request NEO answered counts,
refusals included, and every operation of a batch; the per-status split stays in the raw rows. It
also excludes rows marked `"actor":"support"`, like `AiSpendCounter`; that filter only takes effect if
a row is ever written for the tenant's support user, which a public API key does not act as today.

**The wrong-secret 401 of the token exchange is not billed.** Its rows (`properties.kind =
'tokenExchange'`) are written and kept for visibility, but the counter leaves them out: a key's
`client_identifier` is not a secret, so anyone who knows it could otherwise inflate the tenant's bill
by sending wrong secrets. The exclusion is on the kind, which no request path can set — never on the
target. Read them with the per-key query of §8 to spot a key under attack. Recording one costs no
I/O: the key's tenant comes in the same query that reads the key row.

**Nothing is aggregated until an operator adds the catalog row.** The module ships no Billing
Resource row (the catalog is the operator's, [`plan-quotas.md`](plan-quotas.md) §4.1). Until a
`PUBLIC_API_REQUESTS` row with Strategy Qualifier `publicApiRequests` exists, the
`publicapi.request` events are **stored** in `ETGO_USAGE_EVENT` (and purged after the retention, §6)
but **not aggregated** into `ETGO_USAGE_DAILY`, and no quota can be put on them. The row:
[`plan-quotas.md`](plan-quotas.md) §4.8. Once it exists, the usage job backfills only the settling
window; older days still inside the retention can be stored with a backfill run of the process
(Starting / Ending Date).

---

## 3. One public action counts once

`POST /api/v1/{resource}/{id}/actions/{action}` makes the gateway call NEO twice
(`public-api.controller.ts` `runAction`): the action, then a `GET` of the record, because a NEO action
answers only `{status, message}` (`NeoProcessService`) and the public contract returns the record.
The re-read cannot be dropped without changing that contract, so NEO does not count it:

- a **successful (2xx) action** on the NEO action route (`/{spec}/{entity}/{recordId}/action/{name}`)
  leaves a single-use *re-read credit* for that tenant, key, spec, entity and record id, valid
  **30 s** (`NeoPublicApiUsage`), kept as a SHA-256 digest so a client-chosen id never sits in memory
  at its own length;
- the first `GET` of exactly that record by the same key inside the window consumes the credit and
  is not counted; any later `GET` is.

Nothing the client sends decides it — no header, no parameter. The credit exists only because this
instance answered the action, so a client calling NEO directly gets at most one uncounted read per
successful action, the same as the gateway. Credits are capped at 10 000 per instance; past that no
new credit is granted (an over-count, never an under-count).

---

## 4. Reaching the database

```
request thread ──record()──► ConcurrentHashMap<bucket, count>   (one merge, no I/O, never throws)
                                   │  every 60 s (daemon thread "etgo-publicapi-usage")
                                   │  and once more in NeoServlet.destroy()
                                   ▼
                 remove each bucket ─► INSERT batch on its own pooled connection, own transaction
                                   │       (UsageEventWriter.insertEach)
                 not committed? ◄──┘  merged back into the map, written with the next flush
```

- **Nothing on the request thread can block or throw.** Recording is one `ConcurrentHashMap.merge`
  after the answer is written; the flush thread starts with the first public API request.
- **A failed flush loses nothing.** The rows go through `UsageEventWriter.insertEach` synchronously
  on the counter's thread, not through the asynchronous writer queue (which cannot tell its producer
  that an insert failed). Two failures are told apart (`UsageEventWriter.insertEach`):
  - **outage** — no pool, no connection, or the connection stops working (checked with
    `Connection.isValid` after a refused row): the insert throws, the whole chunk is kept and no
    attempt is charged, however long the outage lasts;
  - **row refusal** — the connection works and the database refuses individual rows (an FK to a
    deleted tenant): each refused row is charged an attempt, even when no row of its chunk was
    written, and is dropped with a WARN after 5. A poisoned bucket alone in its chunk cannot loop.
- **Shutdown.** `NeoServlet.destroy()` calls `PublicApiUsageCounter.shutdown()` **before**
  `UsageEventRecorder.shutdown()`: it stops the thread, waits up to 5 s for a running flush, and
  writes what is left on the undeploy thread. A normal undeploy loses nothing; if that last write
  fails, the requests left are logged at WARN as lost.
- **Loss window on a hard crash** (kill -9, OOM, power): at most the last minute of counts of that
  instance.
- Losses are counted and logged at WARN (first one at once, then at most once a minute):
  `Public API usage: … request(s) not recorded …`.

## 5. Multi-instance

Each JVM counts and flushes its own requests; rows from several instances add up in SQL, so totals
are exact across nodes. Two things are per instance: the re-read credit (a re-read that the load
balancer sends to another node is counted — at most one extra request per action) and the loss
window above.

## 6. Retention

Raw `publicapi.request` rows older than the retention are deleted at the end of every
settling-window run of the usage job (`UsageAggregationProcess` → `PublicApiUsageRetention`), on the
purge's own connection and transaction, so a failure (logged at WARN, retried next run) never fails
the aggregation. A backfill run never purges.

- Only `EVENT_TYPE = 'publicapi.request'` rows; never another event type, never `ETGO_USAGE_DAILY`.
- Retention: System-level preference **`ETGO_PublicApiUsageRetentionDays`** (a list property of the
  *Property Configuration* reference), default **90** days. It never goes below the longest settling
  window plus one day, so a day the job still recomputes keeps its raw rows.
- It runs only while the usage job runs (`plan-quotas.md` §5.1). With the schedule off, nothing is
  purged.
- **Known follow-up:** the purge is one `DELETE` per run. At the expected volume (one row per tenant,
  key, spec, entity, method, status and minute) that is fine; if it grows, delete in bounded batches
  so one run does not hold a long transaction.

## 6.1 Who can mark a key

The `neo:public-api-key` and `neo:public-api-owner-org:<org>` scopes decide that a client is a
public key (attribution, MCP refusal) and whose tenant it bills. Only the public API key flow
(`PublicApiKeyStore`, through `PublicApiKeyPolicy`) writes them. The generic client CRUD of
`/oauth2/clients` (create and update) refuses any `neo:public-api-*` scope with `400 invalid_scope`.
The owner-organization marker is read anchored on a whole scope (`(?:^| )neo:public-api-owner-org:`),
so a scope that merely contains the text is not an owner marker.

## 6.2 Known limits (accepted, ETP-6057)

- **The bucket cap is global, not per caller.** One caller walking many specs and statuses can fill
  the 10 000 buckets and push other callers' new buckets into their overflow buckets. Totals per
  tenant and key stay right; only the spec/entity/status split of the late callers is lost.
- **The re-read credit cap is global, not per caller.** One caller running many actions can use up
  the 10 000 credits; past it no credit is granted and re-reads are counted (an over-count).
- **The retention purge is not batched** (§6).

## 7. Opt-out

`usage.events.enabled=false` in `Openbravo.properties` — the switch every usage event honours — stops
counting (nothing is added to the map) and stops flushing (what was counted is discarded).

## 8. Queries

Per-tenant daily totals, as the usage job stores them (needs a Billing Resource row, §2.2):

```sql
SELECT d.measured_client_id AS tenant, d.usage_day, d.qty, d.is_settled
  FROM etgo_usage_daily d
  JOIN etgo_billing_resource r ON r.etgo_billing_resource_id = d.etgo_billing_resource_id
 WHERE r.strategy_qualifier = 'publicApiRequests'
 ORDER BY d.usage_day DESC, d.measured_client_id;
```

The same, straight from the raw rows (any day still inside the retention, catalog row or not),
with the billing rule of §2.2 — token-exchange refusals (and support-actor rows, if any) left out:

```sql
SELECT ad_client_id, date_trunc('day', occurred_at) AS day,
       sum(cast(cast(properties AS jsonb) ->> 'count' AS bigint)) AS requests
  FROM etgo_usage_event
 WHERE event_type = 'publicapi.request' AND source = 'backend'
   AND coalesce(cast(properties AS jsonb) ->> 'kind', '') <> 'tokenExchange'
   AND coalesce(cast(properties AS jsonb) ->> 'actor', '') <> 'support'
 GROUP BY 1, 2 ORDER BY 2 DESC, 1;
```

Per-key detail of one tenant, by status:

```sql
SELECT cast(properties AS jsonb) ->> 'keyId'  AS key_id,
       cast(properties AS jsonb) ->> 'kind'   AS kind,
       target AS spec, cast(properties AS jsonb) ->> 'entity' AS entity,
       action AS method, cast(properties AS jsonb) ->> 'status' AS status,
       sum(cast(cast(properties AS jsonb) ->> 'count' AS bigint)) AS requests
  FROM etgo_usage_event
 WHERE event_type = 'publicapi.request' AND source = 'backend'
   AND ad_client_id = :tenant AND occurred_at >= :from AND occurred_at < :to
 GROUP BY 1, 2, 3, 4, 5, 6 ORDER BY requests DESC;
```

`key_id` is the public `client_identifier`; join `etgo_oauth2_client` on it for the key's name.

## 9. Files

| File | Role |
|---|---|
| `usageevents/PublicApiUsageCounter.java` | In-memory buckets, flush thread, retry, shutdown flush |
| `usageevents/PublicApiCaller.java`, `PublicApiCall.java` | Bucket dimensions (who; what and how answered) |
| `usageevents/UsageEventWriter.java` (`insertEach`) | Synchronous insert reporting each row |
| `usageevents/UsageEventTypes.java` | `publicapi.request`; `isServerOnly` (types the UI endpoint drops) |
| `schemaforge/NeoUsageEventEndpoint.java` | Drops server-only types sent by a client |
| `oauth2/PublicApiKeyAttribution.java` | Which key and tenant a request belongs to; the request attribute |
| `oauth2/OAuth2Filter.java` | Token lookup selects the key (`client_identifier`), its role's client and whether it is a public key; `checkToken` answers, in one query, the identity of a usable token or the owner of a refused one |
| `oauth2/PublicApiKeyPolicy.java` (`isPublicApiKey`, `carriesPublicApiMarker`) | The scope marker tests; the CRUD refusal of marker scopes |
| `mcp/McpServlet.java` (`authenticate`), `oauth2/OAuth2Filter.java` (`doFilter`) | Refuse a public API key's token on the MCP (403) |
| `oauth2/OAuth2Servlet.java` (`countRefusedKeyExchange`, `SQL_FIND_CLIENT`) | The wrong-secret 401 at the token exchange; the key row query also reads its tenant |
| `auth/EnvironmentRequestAuthenticator.java` (`fromOAuth2`) | Attaches the caller before any refusal |
| `schemaforge/NeoPublicApiUsage.java` | Counts the answered NEO request; one request per batch operation; the action re-read credit |
| `schemaforge/BatchService.java` (`handle`) | Hands a public API batch's operations to the counter |
| `schemaforge/NeoServlet.java` | `processRequest` `finally`; `destroy()` flushes first |
| `usage/PublicApiRequestCounter.java` | `publicApiRequests` daily strategy (excludes kind `tokenExchange`) |
| `usage/PublicApiUsageRetention.java`, `UsageAggregationProcess.java` | Retention purge |
| `src-db/database/sourcedata/AD_REF_LIST.xml` | `ETGO_PublicApiUsageRetentionDays` preference key |
