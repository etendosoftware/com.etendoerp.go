# ADR-0003 — Centralized attachment authorization for NEO Headless

- **Status:** Proposed
- **Date:** 2026-07-29
- **Deciders:** Etendo Go backend (ETP-4569 assessment)
- **Jira:** ETP-4569 (assessment / this ADR) · ETP-4570 (implementation, Backend 2/3) · ETP-4571 (upload/response hardening, Backend 3/3) · Epic ETP-3504
- **Source:** [PRD — Client & Delivery Security Hardening](https://etendoproject.atlassian.net/wiki/spaces/PYPI/pages/5106892804/), WS-4.2 / WS-4.3 — SEC-11b, SEC-12
- **Findings:** SEC-11b (High) — cross-org attachment IDOR · SEC-12 (High, partially fixed) — attachment responses served without `nosniff`, attacker-controlled MIME echo

> Part of the ETP-4569 assessment (PRD phase P0). The authorization matrix in §"Authorization matrix"
> **is** the specification for ETP-4570 — it is written here so the implementation can start red-first.

---

## Context

### The vulnerability (SEC-11b)

Three facts combine into a cross-organization IDOR:

1. **The whole built-in dispatch runs in admin mode.** `NeoServlet.service()` wraps dispatch in
   `OBContext.setAdminMode()` (`NeoServlet.java:139`, and again `setAdminMode(true)` at `:168`).
   Admin mode **bypasses the readable-organization filter** that DAL would otherwise apply.
2. **Bare-ID operations do no scoping at all.** `NeoAttachmentsHelper.handleDownload()` performs
   `OBDal.getInstance().get(Attachment.class, attachmentId)` (`:223`) with no client, organization or
   record check. Same shape in `handleDelete()` (`:321`) and `handleUpdateDescription()` (`:353`).
3. **Contextual operations explicitly disable the org filter.** `handleList()` sets
   `criteria.setFilterOnReadableOrganization(false)` (`:124`), and so does the sibling criteria at
   `:427`.

Net effect: **any authenticated user can read, delete or re-describe any attachment in the
installation by guessing or enumerating its ID**, across organizations and across clients.

### The safe pattern already exists in the same module

`NeoServlet.handleDocumentDownload` → `NeoDocumentDownloadService` serves email document downloads
from **signed links**: `DocumentDownloadTokenService.validate(token)` is called before anything is
served (`NeoDocumentDownloadService.java:52-53`). That is a different trust model (unauthenticated
recipient holding a capability token) and is **not** the right pattern for in-app attachment access,
but it establishes that authorization-before-serving is already an accepted idiom here.

### The platform already provides the right primitives

This is the decisive input: Etendo core exposes exactly the two checks needed, so no hand-rolled
authorization logic is required.

| Primitive | Location | Answers |
|---|---|---|
| `EntityAccessChecker.checkReadableAccess(Entity)` | `src/org/openbravo/dal/security/EntityAccessChecker.java:597` | "does the caller's **role** have access to this table/entity?" |
| `SecurityChecker.checkReadableAccess(OrganizationEnabled)` | `src/org/openbravo/dal/security/SecurityChecker.java:211` (uses `getReadableOrganizations()` at `:222`) | "is this **record** in the caller's readable client/org set?" |
| `OBContext.getReadableOrganizations()` / `getReadableClients()` | `OBContext` | raw sets, already used in-module (`NeoServletSupport.java:63`, `NeoAuthenticator.java:142`) |

And the attachment row itself carries its parent: `Attachment.PROPERTY_TABLE` (AD_Table FK) and
`Attachment.PROPERTY_RECORD` (the record's primary key as a string), both already used by
`handleList()` (`:120-121`). **The parent record is therefore always derivable server-side from the
attachment ID alone** — which is what makes the bare-ID route fixable without changing its URL.

### Current operation surface

| Operation | Method | Parameters | Scoped today? |
|---|---|---|---|
| List | `handleList` (`:112`) | `tableName`, `recordId` | ❌ org filter explicitly off (`:124`) |
| Upload | `handleUpload` (`:156`) | `tableName`, `recordId`, request | ❌ no record authorization |
| Download one | `handleDownload` (`:217`) | `attachmentId` | ❌ bare ID, no check |
| Download all (zip) | `handleDownloadAll` (`:265`) | `tableName`, `recordId` | ❌ no record authorization |
| Delete | `handleDelete` (`:316`) | `attachmentId` | ❌ bare ID, no check |
| Update description | `handleUpdateDescription` (`:348`) | `attachmentId`, description | ❌ bare ID, no check |

Call sites are all in `NeoBuiltInEndpointHandler` (`:312`, `:342`, `:359`).

---

## Decision

### D1 — Keep the bare-ID route; authorize its parent server-side

Ratifying PRD §7. The bare-ID URL is **actively used** by generic download/delete/description flows
and by OCR after a contextual list. Therefore:

- The route **keeps its URL** in this hardening change.
- For every bare-ID operation: load the attachment, **derive its table and record server-side**, and
  authorize *that business record*.
- A contextual route (`/attachments/{table}/{record}/{id}`) may be added later to verify
  parent-mismatch and migrate clients. **Removing the bare-ID route requires a versioned breaking
  release** — not this change.

> Rejected alternative: delete the bare-ID route now and force all clients onto a contextual route.
> Rejected because it breaks OCR and generic attachment actions in the same release as a security
> fix, making rollback ambiguous — if something breaks, we could not tell whether it was the
> authorization change or the route change.

### D2 — One centralized authorizer, used by all six operations

A single service — `NeoAttachmentAuthorizer` — is the only place attachment access is decided.
Six call sites, one policy.

```java
// Conceptual shape. Returns the authorized attachment or throws/returns a uniform not-found.
Attachment authorizeById(String attachmentId, Access access);          // bare-ID operations
void       authorizeByRecord(String tableName, String recordId, Access access); // contextual operations
// Access = READ | WRITE
```

**Client-supplied context is never the security proof.** For bare-ID operations the `tableName` /
`recordId` in the request (if present at all) are ignored for the decision; only the values read off
the attachment row are used. Where both a client-supplied context and the derived context exist, a
**mismatch is a rejection**, not a fallback.

### D3 — Authorize outside admin mode, then act inside a narrow admin scope

This is the core of the fix. Today the blanket `setAdminMode()` in `NeoServlet.service()` is what
disables the org filter. The authorizer must therefore run the checks with the **caller's real
privileges**:

```
1. OBContext.restorePreviousMode()   (or run the check in a non-admin scope)
2. EntityAccessChecker.checkReadableAccess(parentEntity)     ← role/table access
3. SecurityChecker.checkReadableAccess(parentRecord)         ← client/org access
   (for WRITE: the writable-access equivalent)
4. only then: setAdminMode() for the narrow file operation (AttachImplementationManager)
5. restorePreviousMode()
```

Two consequences worth stating explicitly:

- **Authorization targets the parent business record, not the attachment row.** An attachment's own
  client/org columns are not the boundary users reason about; the record it hangs off is.
- **Organization equality is insufficient.** The PRD is explicit: users in the same organization may
  still lack access to the object. That is why step 2 (role/entity access) is not optional — a
  same-org user without table access must be rejected.

### D4 — No hardcoded administrator exception

Legitimate administrator and multi-org access is preserved **through the normal permission model** —
a GOAdmin role that legitimately has broad readable organizations will pass the same checks. There is
**no** `if (isGOAdmin) allow` branch. Any such shortcut would reintroduce the finding for whoever
holds that role.

### D5 — Uniform `404` for nonexistent and unauthorized

Both cases return **the same status and the same body shape**. Distinguishing them leaks existence
and enables ID enumeration — which is precisely what the PRD's acceptance criteria test for
("A low-privilege black-box re-test confirms no ID enumeration").

Concretely: `handleDownload` today returns `404 ERR_ATTACHMENT_NOT_FOUND` for a missing row (`:225`).
Unauthorized must return **byte-identical** output. Response timing should not obviously diverge
either, though constant-time behavior is not a requirement here.

### D6 — Remove the explicit org-filter suppressions

`setFilterOnReadableOrganization(false)` at `:124` and `:427` is removed. Once the parent record is
authorized, the listing must reflect the caller's readable scope rather than overriding it. If a
legitimate flow breaks because of this, that flow was relying on the vulnerability and needs its own
authorized path.

### D7 — Response and upload hardening (SEC-12, ETP-4571)

Scoped here because it shares the same code path, but implemented in ETP-4571:

- **`X-Content-Type-Options: nosniff`** on both `handleDownload` and `handleDownloadAll`. Verified
  absent today: no `nosniff` anywhere in `NeoAttachmentsHelper.java`.
- **Default to `Content-Disposition: attachment` plus `application/octet-stream`.**
  `Content-Disposition` is already set (`:235`, RFC 5987-compliant via `buildContentDisposition` at
  `:566`) — that is the "partially fixed" part of SEC-12. The residual gap is `:234`:
  `resolveContentType(attachment.getDataType())` **echoes the stored MIME type**, which is
  attacker-controlled at upload time.
- **Any inline preview is a separate explicit allow-list**, serving safely re-encoded content or
  using an isolated/sandboxed origin. **SVG and PDF are not inherently passive** and do not qualify
  as safe-by-default inline types.
- **At upload:** normalize the filename; enforce filename and byte-size limits; allow only
  business-required extensions; **detect content from bytes**; require consistency among extension,
  declared MIME and detected type. Do **not** trust the multipart `Content-Type` (today
  `handleUpload` only checks that the outer type starts with `multipart/`, `:161-163`). Generate the
  stored filesystem name independently of the user-supplied filename.
- **Quarantine / AV / content-disarm** integration is defined for deployments accepting complex
  documents. If unavailable for the first release, the residual risk is recorded and downloads are
  kept inert.

---

## Authorization matrix (the specification for ETP-4570)

Every cell must have a test. This is the PRD's acceptance criterion, expressed concretely.

**Caller scenarios**

| # | Scenario | Expected |
|---|---|---|
| S1 | Owner: role has table access, record in readable org | ✅ allow |
| S2 | Same-org user **without** table/entity access for the parent table | ❌ `404` |
| S3 | Same-client user in a **non-readable organization** | ❌ `404` |
| S4 | **Cross-client** user | ❌ `404` |
| S5 | Record inactive / not accessible to the caller's role | ❌ `404` |
| S6 | Attachment ID that **does not exist** | ❌ `404` — byte-identical to S2–S5 |
| S7 | Client-supplied `tableName`/`recordId` **mismatching** the attachment's real parent | ❌ `404` (no fallback to client context) |
| S8 | Legitimate **multi-org / administrator** role with broad readable orgs | ✅ allow, via the normal permission model (D4) |

**Operations** — every scenario above is exercised for each:

| Operation | Access required |
|---|---|
| List | READ on parent record |
| Download one | READ |
| Download all (zip) | READ |
| Upload | WRITE |
| Delete | WRITE |
| Update description | WRITE |

That is 8 scenarios × 6 operations. Not all 48 need to be distinct tests — S1/S6 per operation plus
the full scenario sweep on `handleDownload` (the confirmed IDOR path) and on one WRITE operation is a
defensible minimum. **Whatever subset is chosen must be stated explicitly in the PR**, per the
"no silent caps" principle: a matrix that silently covers 12 of 48 cells reads as full coverage.

**Upload-specific cases (ETP-4571):** spoofed MIME, double extensions (`invoice.pdf.svg`), SVG/HTML
payloads, oversized and empty files, traversal and control-character filenames, and a valid allowed
file that must still succeed.

---

## Consequences

**Positive**
- Closes SEC-11b, the confirmed cross-org IDOR.
- Uses the platform's own permission model → administrators, multi-org roles and future role changes
  work without touching this code.
- One authorizer for six operations → the next attachment operation added inherits the policy.
- Uniform `404` removes the enumeration oracle.

**Negative / costs**
- **Flows that currently depend on the missing check will break.** This is the real risk: OCR and
  generic attachment actions must be re-tested end-to-end, because they may be reading attachments
  under a context that never had legitimate access.
- Running the check outside admin mode inside a servlet that globally enables admin mode requires
  care with `setAdminMode`/`restorePreviousMode` nesting — a mistake here silently re-opens the hole.
  This is the single most important thing for review to verify.
- `handleList` becoming org-scoped may reduce result sets that users currently see.

**Neutral**
- No schema change, no new table, no migration script.
- The bare-ID URL is unchanged, so no client migration in this release.

---

## Testing (TDD, red-first)

Per PRD §6: JUnit plus `OBBaseTest` integration coverage; unit tests alone are explicitly declared
insufficient for authorization.

**Red first — the abuse case.** An integration test where user B (different organization, or same
org without table access) requests user A's attachment ID via `handleDownload` and currently
**succeeds**. That failing-to-reject test *is* the reproduction of SEC-11b required by ETP-4569 (E5),
and it doubles as the red test for ETP-4570.

**Then the matrix.** Each cell above, with `OBBaseTest` fixtures providing: two organizations under
one client, a second client, a role without access to the parent table, and an inactive record.
Provisioning these fixtures is the main setup cost.

**Regression.** OCR flow, generic download/delete/description flows, and the contextual list →
download sequence must all still work for a legitimately authorized user.

> **Note on the low-privilege black-box re-test.** The PRD lists it as confirmation, and it is worth
> running, but it is **not** the proof — the code path already demonstrates the gap. The integration
> matrix is the authoritative artifact. Recommendation for ETP-4569: deliver the matrix as JUnit and
> attach the black-box run as supplementary evidence if a suitably provisioned staging environment is
> available.

---

## Open questions for ETP-4570

1. **`NeoServlet`'s blanket admin mode** (`:139`, `:168`) is the root enabler of this class of bug,
   not just of SEC-11b. Do we scope this ADR to attachments only, or open a follow-up to narrow the
   global admin-mode wrapper? Recommendation: **attachments only here**, plus a follow-up ticket —
   narrowing the global wrapper is a large blast-radius change that deserves its own risk assessment.
2. **WRITE semantics for `updateDescription`** — is editing an attachment's description a write on the
   attachment or on the parent record? Recommendation: authorize as WRITE on the parent, since the
   parent is the business object whose permissions users reason about.
3. **Does any legitimate flow genuinely need cross-org attachment reads?** If yes, it needs an
   explicit authorized path rather than the current implicit bypass. This must be answered before
   implementation, because it determines whether D6 breaks production behavior.
