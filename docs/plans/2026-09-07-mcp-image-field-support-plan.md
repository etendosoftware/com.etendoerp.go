# MCP support for image-typed fields (`Image BLOB`)

- **Date:** 2026-09-07
- **Repo:** `com.etendoerp.go` (MCP server) + `etendo_schema_forge` (contract/type metadata)
- **Status:** proposal — not implemented
- **Problem:** an agent talking to the Etendo GO MCP cannot set a product image. The
  field is exposed as an untyped string and there is no way to move bytes through the
  MCP channel.

---

## 1. Current state (verified)

### 1.1 The AD side

`Image BLOB` (`AD_Reference_ID = 4AA6C3BE9D3B4D84A3B80489505A23E5`) is an FK column
pointing at `AD_Image`, whose bytes live in `AD_Image.BinaryData` (`Binary`, ref `23`)
plus `Mimetype` / `Name`. Every image-typed column in the instance:

| Table | Column | Notes |
|---|---|---|
| `M_Product` | `AD_Image_ID` | **product image — the reported case** |
| `M_Product_Category` | `AD_Image_ID` | |
| `M_Product_Ch_Conf` | `AD_Image_ID` | product characteristic configuration |
| `AD_User` | `AD_Image_ID` | user / contact avatar |
| `AD_OrgInfo` | `Your_Company_Document_Image` | **org logo — the second live case** |
| `AD_ClientInfo` | `Your_Company_Menu_Image`, `Your_Company_Document_Image`, `Your_Company_Big_Image` | client branding |
| `AD_System_Info` | 6 branding columns | system scope, out of tenant reach |

`M_Product.ImageURL` / `M_Product_Category.ImageURL` are plain `varchar` URLs — an agent
can already write them today; they are NOT part of this problem, but they matter for the
UX decision in §4.5.

### 1.2 What Schema Forge already exposes

`ETGO_SF_FIELD` rows with an `Image BLOB` column, per spec:

| Spec | Column | Included | Visibility |
|---|---|---|---|
| `product` | `AD_Image_ID` | yes | editable |
| `organization` | `Your_Company_Document_Image` | yes | editable |
| `contacts` | `AD_Image_ID` | yes | system (read-only) |
| `product-category` | `AD_Image_ID` | no | discarded |
| `user` | `AD_Image_ID` | no | discarded |

So the solution must cover **two live editable fields today** (`product`,
`organization`) and be generic enough that enabling `product-category` / `user` later
needs no new code.

### 1.3 What the runtime already has

`NeoImageHelper` (`schemaforge/util/NeoImageHelper.java`), routed by
`NeoBuiltInEndpointHandler` under the built-in spec name `image`:

- `GET /sws/neo/image/{imageId}` → raw bytes + `Content-Type` from `AD_Image.Mimetype`
- `POST /sws/neo/image` → JSON `{name, mimeType, data}` (base64, 10 MB cap) → creates
  the `AD_Image` row → returns `{imageId, name}`

The React `ImageField.jsx` uses exactly that pair (30 MB / 7680×4320 / png+jpeg client
side validation, then base64 POST, then writes the returned `imageId` into the record).

**The bytes path therefore already exists and is proven.** What is missing is the MCP
surface on top of it.

### 1.4 Why the MCP cannot do it today

1. `McpSchemaFieldBuilder.mapColumnType()` has no case for
   `4AA6C3BE9D3B4D84A3B80489505A23E5`, so it falls through to `default → "string"`.
   Result: `neo_schema`/`formState` emit `"image": {}` — an untyped, undescribed string.
   Verified in `artifacts/product/contract.mcp.json` (`"image": {}`).
2. An agent that guesses will either write a bogus string (FK violation from DAL) or
   inline a base64 payload into `neo_create`/`neo_update` (which is not what the column
   stores, and which would burn ~100k output tokens for a 130 KB image).
3. There is no tool that creates an `AD_Image` row, and no MCP tool/resource that lets
   an agent *see* an existing image.
4. `grep -i image|blob|binary|base64` over `src/com/etendoerp/go/mcp/` returns zero
   functional hits — the whole MCP package is image-blind.

---

## 2. How the ecosystem does it (research, Sept 2026)

Key constraint from the spec side: **MCP still has no interoperable client→server file
upload primitive.** Tool arguments are model-generated JSON, so bytes in arguments are
paid for in output tokens (base64 ≈ 1.4–1.5 chars/token → a 126 KB image ≈ 120k output
tokens), and several SDK HTTP servers hard-reject bodies over 4 MiB.

Observed patterns in real servers:

| Pattern | Who | Trade-off |
|---|---|---|
| `source_url` — server fetches the bytes itself | PixelVault, mcp-upload-file, file-store-mcp | cheapest in tokens; needs SSRF controls; requires the image to be reachable |
| `file_path` — server reads the local filesystem | Desktop Commander, image-processor MCPs | only valid when server and client share a machine; useless for a remote ERP |
| `data_base64` inline | most servers offer it as a fallback | works for tiny images only; token/size cliff |
| **Opaque handle / out-of-band upload** | recommended in the MCP discussions | keeps the data plane out of JSON-RPC entirely; the agent gets a ticket, the bytes travel over plain HTTP |
| Output as `ImageContent` (`{type:"image", data, mimeType}`) or `resource_link` | spec-blessed for *results* | reading an image back IS standardized; only input is not |

Consensus: **URL or handle in, `ImageContent`/resource out; base64 only as a small
escape hatch.**

Sources:
- <https://github.com/modelcontextprotocol/modelcontextprotocol/discussions/1197>
- <https://github.com/modelcontextprotocol/python-sdk/issues/1823>
- <https://modelcontextprotocol.io/specification/2026-07-28/server/tools>
- <https://pixelvault.dev/blog/mcp-image-hosting/>
- <https://futuresearch.ai/blog/mcp-large-dataset-upload/>
- <https://github.com/makenotion/notion-mcp-server/issues/191>

---

## 3. Proposal

Generic, type-driven support for **every** `Image BLOB` field — never a
`product`-specific branch. Three additive layers.

### Layer A — make the type visible (mandatory, tiny)

1. `McpSchemaFieldBuilder.mapColumnType()`: add
   `case "4AA6C3BE9D3B4D84A3B80489505A23E5": return McpConstants.TYPE_IMAGE;`
   (`TYPE_IMAGE = "image"`, new constant in `McpConstants`).
2. For an `image` field, the emitted JSON Schema becomes an explicit, self-describing
   contract instead of `{}`:

```json
"image": {
  "type": "string",
  "format": "etendo-image-id",
  "description": "AD_Image ID. Do NOT send base64 or a URL here. Obtain the id with neo_upload_image, then write it to this field. Read the current image with neo_get_image."
}
```

3. Same treatment in `neo_schema`, `formState` and the create view
   (`McpSchemaCreateView`), so discovery, defaults and create all agree.
4. `neo_create`/`neo_update` reject a value that is not a 32-hex `AD_Image` id with a
   **self-correctable** error naming `neo_upload_image` (this is the M4 metric in the
   MCP comparison skill — the agent must be able to fix itself without a human).

Layer A alone turns a silent FK failure into a guided one. It is worth shipping first.

### Layer B — the write path: keep the bytes out of the model

**The constraint that drives everything here:** a tool argument is model *output*,
generated token by token. There is no way to "mark" an argument as not-for-the-LLM — no
MCP client elides argument content today. So base64-in-an-argument ALWAYS costs output
tokens (~1.4 chars/token → 100 KB image ≈ 100k tokens). The only way to not pay is to
**not put the bytes in the argument**: the tool passes a reference, the bytes travel over
plain HTTPS on the side.

Note that "no outbound HTTP from the ERP" (the reason `source_url` was rejected) does NOT
rule this out: an upload ticket is an **inbound** request to Etendo, the same direction as
every other NEO call. It is the `source_url` fetch that would have been outbound.

This is also where the spec is heading: the MCP **File Uploads working group** and
SEP-2356 (declarative file inputs for tools and elicitation) / SEP-1306 (binary-mode
elicitation) exist precisely because servers today "resort to prose instructions asking
for base64 strings or local paths". SEP-2356 explicitly **routes large files through
URL-mode elicitation** — i.e. the ticket pattern below is what the standard is
formalizing. Building it now means the eventual migration is a host-side file picker
sitting in front of an endpoint we already have.

#### B1 — `neo_request_image_upload` (PRIMARY, ~50 tokens)

```
neo_request_image_upload({ name?: string, mime_type?: string })
  -> { uploadUrl, expiresAt, maxBytes, curlExample }
```

- `uploadUrl` = `/sws/neo/image/upload/{opaque-one-shot-token}` (absolute, same host the
  MCP client already talks to).
- Whoever holds the file PUTs the raw bytes there. For an agent with a shell that is one
  `curl --upload-file ./photo.jpg "<uploadUrl>"` — **the file never enters the context; only
  its path and the URL do.** For a chat-only user, the same URL can be opened/dropped in a
  browser. Returning a ready-to-run `curlExample` is deliberate: it is what makes the
  cheap path the obvious one.
- The endpoint validates, calls `NeoImageHelper.createImage(...)`, consumes the token and
  responds `{ imageId, name, mimeType, bytes, width, height }`.
- Because the response of the PUT is what carries the `imageId`, the agent needs one more
  cheap step only if it did not see that output: `neo_get_image_upload({ token })` →
  `{ status, imageId }`. Same-token, read-only.

Token rules: single use, TTL ≤ 10 min, opaque and unguessable (>=128 bits), bound to the
MCP session's client/org/user, carries no session JWT, `maxBytes` enforced server-side
(reuse the servlet's 10 MB), MIME allowlist + magic-byte sniff identical to B2.

#### B2 — `neo_upload_image` (FALLBACK, small images only)

```
neo_upload_image({ data_base64: string, name?: string, mime_type?: string })
  -> { imageId, name, mimeType, bytes, width, height }
```

Kept because it is one code path away (both call
`NeoImageHelper.createImage(name, mimeType, byte[])`, extracted from the existing
`handlePostImage`) and because it is the only thing that works when the caller has the
bytes in memory and no shell. **Hard cap: 256 KB decoded** — low on purpose, so that
nobody discovers the token cost by paying it. Over the cap the error names B1.

Validation, in order, each with a self-correctable message: present/non-blank → strip a
`data:` URI prefix → decode (malformed → 400) → 256 KB cap → MIME allowlist
`image/png` + `image/jpeg`, sniffed from magic bytes and cross-checked against
`mime_type` so a lying `mime_type` cannot store an arbitrary blob.

Both tools create the `AD_Image` row **only** — wiring it to a record stays an explicit
`neo_update` of the image field, which keeps them generic and the audit trail obvious.

#### Tool descriptions (a deliverable, not a detail)

`neo_request_image_upload`:

> Returns a single-use URL to upload an image to Etendo, plus a ready-to-run curl command.
> **Prefer this over `neo_upload_image` whenever you can run a shell command or the user can
> open a link: the image bytes never pass through the conversation, so it costs almost no
> tokens.** After the upload succeeds you get an `imageId` — write it to any field of type
> `image` with `neo_update`.

`neo_upload_image`:

> Uploads an image inline as base64 and returns its `imageId`. **Use only for images under
> 256 KB: base64 in a tool argument is model output, so ~100 KB of image costs ~100k tokens.
> If you can run a shell command, use `neo_request_image_upload` instead.** `image/png` or
> `image/jpeg` only; resize to max 1024 px on the long side before encoding.

A doc test asserts both descriptions mention the cheap path and the cap, so the guidance
cannot silently drift from the validation.

#### What is still NOT possible

If the caller is a chat-only client AND the user will not open a link, base64 is the only
option and the token cost is unavoidable — there is no third mechanism today. That is a
protocol gap (SEP-2356), not something we can close in Etendo.

### Layer C — `neo_get_image` + a resource (the read path)

- `neo_get` on a record with an image field returns the id today. Additionally emit a
  `resource_link` to `neo://image/{imageId}` so a client can fetch it on demand without
  the agent paying for it.
- `McpResourceProvider`: register `neo://image/{id}`, returning a binary resource
  (`blob` = base64, `mimeType` from `AD_Image.Mimetype`) — the spec-standard way to move
  binaries server→client.
- `neo_get_image({ imageId, max_dimension?: 512 })` → MCP `ImageContent`
  (`{type:"image", data, mimeType}`) so a vision-capable agent can actually look at the
  picture. **Downscale server-side by default** (`max_dimension` 512, hard cap 1024) —
  returning a 4 MB original as base64 would blow the context (this is the ACE-v cost the
  `/mcp-ace-comparison` skill measures).

### 3.1 What this deliberately does NOT cover

- `AD_Attachment` / `C_File` document attachments — same "bytes over MCP" family, no
  `Image BLOB` reference, different tables and ACLs; explicitly out of scope here.
- `source_url` (server-side fetch) — **rejected by decision**: it would make the ERP an
  outbound HTTP client. The upload ticket in Layer B is inbound and is not affected by
  this.
- `Binary` (ref `23`) columns other than `AD_Image.BinaryData` — all Copilot/Quartz
  internals, never in a business window.
- `AD_System_Info` branding columns — system scope, not tenant-writable.
- `ImageURL` (plain varchar) — already writable; see §4.5.

---

## 4. Decisions

Resolved on 2026-09-07:

- **No `source_url`.** The ERP does not make outbound HTTP requests to fetch images.
- **Field to use for the product image → `AD_Image_ID`.** The window is the evidence:
  - `artifacts/product/decisions.json` → `entities.product.fields.image`
    = `{"type":"image","section":"principal","seq":8}` — the only image field it declares.
  - `artifacts/product/generated/web/product/ProductForm.jsx:12` →
    `{ key: 'image', column: 'AD_Image_ID', type: 'image', ... }`.
  - `ProductTable.jsx:6` → the list thumbnail is
    `media: {"field":"image","kind":"neoImage","fallback":"box"}`, i.e. it renders through
    the `AD_Image` bytes endpoint, not a URL.
  - `imageURL` (`M_Product.ImageURL`) is `visibility: "system"` in `contract.json` — never
    shown, never editable. Legacy/e-commerce metadata, NOT the product image.

  Consequence: Layer B is the real fix for the product case (not just the org logo), and
  Layer A's `description` must NOT offer `imageURL` as an alternative.

Also resolved on 2026-09-07:

- **Ticket-first, base64 as a capped fallback** (§Layer B). A base64-only design cannot
  satisfy "don't blow up the caller's tokens" — the cost is inherent to putting bytes in a
  tool argument. The ticket is inbound-only, so it does not reintroduce the `source_url`
  objection.
- **256 KB cap on the base64 fallback**, low on purpose so nobody discovers the token cost
  by paying it.
- **Ticket store: in-memory, single-node.** Etendo GO is not multi-node today, so a
  `ConcurrentHashMap<String, PendingUpload>` in an `@ApplicationScoped` bean is enough —
  no AD table, no DB round trip, no `update.database` impact. Requirements:
  - entries carry `{ client, org, user, name, mimeType, createdAt }`; expiry is checked on
    read and stale entries are also swept lazily on each `put` (bounded map, no timer
    thread);
  - a hard cap on concurrent pending tickets per session, so a loop cannot grow the map;
  - **the limitation is declared in a comment on the class itself** — this is for whoever
    opens the file, so it must be the first thing they read, not a line buried in a doc.
    Write it as the class Javadoc, verbatim:

    ```java
    /**
     * In-memory store of pending one-shot image-upload tickets.
     *
     * <p><b>Single-node only, by decision (2026-09-07).</b> Tickets live in this JVM's heap:
     * they are lost on restart/redeploy and are invisible to any other node. That is
     * acceptable while Etendo GO runs on a single node, because the TTL is 10 minutes and a
     * lost ticket surfaces as the same self-correctable error as an expired one — the agent
     * just requests another.
     *
     * <p><b>If Etendo GO ever runs multi-node, or behind a load balancer that does not pin a
     * client to a node, this class MUST be replaced</b> — a PUT can land on a node that never
     * saw the ticket, and the upload fails with no useful diagnosis. The replacement is a
     * small AD table (ETGO_SF_IMG_UPLOAD) keyed by the token, with the same fields and the
     * same TTL. Nothing else in the design changes: callers only need
     * "resolve token -&gt; pending upload".
     */
    ```

  Accepted consequence: a Tomcat restart between `neo_request_image_upload` and the PUT
  invalidates the ticket. With a ≤10 min TTL the agent just requests another one, and the
  error for an unknown token is the same self-correctable message as for an expired one.
- **Ticket TTL: 10 min, single use.**

Still open:

1. **`neo_get_image` default size** — 512 px is a guess; confirm against the real ACE
   budget (`/mcp-ace-comparison`).

## 5. Phasing

| Phase | Scope | Repos | Risk |
|---|---|---|---|
| **P1** | Layer A (type mapping + schema description + self-correctable write error) | `.go` (+ regenerate `contract.mcp.json` in schema_forge) | very low |
| **P2** | Extract `NeoImageHelper.createImage` + MIME sniff; `neo_upload_image` base64 fallback (256 KB cap) | `.go` | low |
| **P3** | `neo_request_image_upload` + one-shot upload endpoint + in-memory ticket store | `.go` | medium (new endpoint + security review) |
| **P4** | Layer C: `neo://image/{id}` resource + `resource_link` in `neo_get` | `.go` | low |
| **P5** | `neo_get_image` returning downscaled `ImageContent` | `.go` | low (watch payload) |

P1+P2 is the whole write path and closes the reported gap on its own. P3/P4 are the
read path and can ship independently.

---

## 6. Tests

- `mapColumnType("4AA6C3BE9D3B4D84A3B80489505A23E5")` → `"image"` (unit).
- `neo_schema` for `product` emits `type/format/description` for `image`, not `{}`.
- `neo_update` with a non-id value on an image field → 400 whose message names
  `neo_upload_image` (self-correctable-error assertion).
- `neo_upload_image` round trip: base64 in → `imageId` out → `neo_update` product →
  `neo_get` returns that id → `GET /sws/neo/image/{id}` serves the same bytes.
- `data_base64` over the 256 KB decoded cap → error that names
  `neo_request_image_upload` (assert the message, not just the status).
- Ticket lifecycle: single use (second PUT rejected), expiry after TTL, unknown/forged
  token rejected, token from another MCP session rejected, oversized body rejected,
  `curlExample` in the response actually matches `uploadUrl`.
- Ticket store hygiene: expired entries are swept (the map does not grow unbounded), the
  per-session pending cap is enforced, and an unknown token yields the same
  self-correctable error as an expired one (so a restart is indistinguishable from an
  expiry, by design).
- Ticket happy path: `neo_request_image_upload` → PUT bytes → `{imageId}` → `neo_update`
  product → `neo_get` returns that id → `GET /sws/neo/image/{id}` serves the same bytes.
- `data_base64` malformed / blank → distinct, self-correctable errors.
- `data:image/png;base64,...` prefix is stripped and still stores correct bytes.
- MIME guard table test: a `text/html` payload, a PDF, and a PNG declared as
  `image/jpeg` — all rejected by magic-byte sniffing; `mime_type` omitted → sniffed.
- Both tool descriptions mention the cheap path and the cap (a doc assertion, so the
  guidance cannot silently drift from the validation).
- `neo_get_image` payload stays under the agreed byte budget for a 4000×3000 source.
- Generic-by-type proof: the same flow works on `organization.Your_Company_Document_Image`
  with **zero** additional code.

---

## 7. Files likely touched (`.go`)

- `src/com/etendoerp/go/mcp/McpSchemaFieldBuilder.java` — type mapping + field schema
- `src/com/etendoerp/go/mcp/McpConstants.java` — `TYPE_IMAGE`, tool names, caps
- `src/com/etendoerp/go/mcp/McpSchemaCreateView.java`, `McpDefaultsView.java` — parity
- `src/com/etendoerp/go/mcp/ToolRegistry.java`, `McpToolRouter.java` — new tools
- `src/com/etendoerp/go/mcp/McpWriteRequestSupport.java` — image-id validation + error
- `src/com/etendoerp/go/mcp/McpResourceProvider.java` — `neo://image/{id}`
- `src/com/etendoerp/go/schemaforge/util/NeoImageHelper.java` — extract `createImage`,
  add the MIME sniff + cap shared by servlet, tool and ticket endpoint
- `src/com/etendoerp/go/schemaforge/NeoBuiltInEndpointHandler.java` — upload-ticket route
  under the existing built-in `image` spec name
- new: `src/com/etendoerp/go/schemaforge/util/NeoImageUploadTickets.java` — in-memory
  single-node ticket store, carrying the multi-node comment from §4
- `docs/neo-headless.md` — document the new tools/resource

`etendo_schema_forge` side: regenerate the affected `contract.mcp.json` and document the
`image` field type in `docs/decisions-reference.md`.
