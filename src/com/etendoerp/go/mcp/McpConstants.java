/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.Set;

final class McpConstants {

  static final String PARAM_ENTITY = "entity";
  /** The spec-name argument every CRUD tool carries (ETP-4793 / IMP-17). */
  static final String PARAM_SPEC = "spec";
  static final String PARAM_FIELDS = "fields";
  static final String PARAM_COLUMN = "column";
  static final String PARAM_FIELD = "field";
  static final String PARAM_QUERY = "query";
  static final String PARAM_PARAMETERS = "parameters";
  /** Requested output format of a {@code generate_*} report tool (ETP-4793 / IMP-19). */
  static final String PARAM_FORMAT = "format";
  static final String PARAM_PARENT_ID = "parentId";
  static final String PARAM_ASSET_ID = "assetId";
  /** Widget enum key for the {@code etendo_widget} tool. */
  static final String PARAM_WIDGET = "widget";
  /** Free-form parameters object passed through to the widget handler (e.g. {@code range}). */
  static final String PARAM_PARAMS = "params";
  /** Current record context used to resolve dependent MCP selectors. */
  static final String PARAM_RECORD_CONTEXT = "recordContext";
  /** Parent/header record context used to resolve child MCP selectors. */
  static final String PARAM_PARENT_CONTEXT = "parentContext";
  static final String TYPE_STRING = "string";
  /**
   * The MCP field type of an {@code Image BLOB} AD column (ETP-5184).
   *
   * <p>Before this existed, {@link McpSchemaFieldBuilder#mapColumnType} had no case for the
   * {@code Image BLOB} reference, so the column fell through to {@code default → "string"} and
   * {@code etendo_schema} advertised it as an ordinary text field. An agent then either wrote a bogus
   * string (an FK violation from the DAL, with no hint of what the column really holds) or inlined a
   * base64 payload — which is not what the column stores and costs ~100k output tokens for a 130 KB
   * image. The type exists so the field can describe itself; see
   * {@link McpImageFieldSupport#IMAGE_FIELD_HINT}.
   */
  static final String TYPE_IMAGE = "image";
  /**
   * {@code AD_Reference_ID} of the {@code Image BLOB} reference — an FK column pointing at
   * {@code AD_Image}, whose bytes live in {@code AD_Image.BinaryData}. Live editable instances today:
   * {@code M_Product.AD_Image_ID} and {@code AD_OrgInfo.Your_Company_Document_Image}; support is
   * keyed off this reference alone, so enabling any other image column needs no new code.
   */
  static final String REF_IMAGE_BLOB = "4AA6C3BE9D3B4D84A3B80489505A23E5";
  /** JSON-schema {@code format} advertised for an {@link #TYPE_IMAGE} field. */
  static final String FORMAT_IMAGE_ID = "etendo-image-id";
  /** Tool name of the base64 fallback upload (ETP-5184, capped at {@link #IMAGE_BASE64_MAX_BYTES}). */
  static final String TOOL_NEO_UPLOAD_IMAGE = "etendo_upload_image";
  /** Tool name of the primary, out-of-band upload-ticket tool (ETP-5184). */
  static final String TOOL_NEO_REQUEST_IMAGE_UPLOAD = "etendo_request_image_upload";
  /** Tool name of the read-only ticket-status lookup (ETP-5184). */
  static final String TOOL_NEO_GET_IMAGE_UPLOAD = "etendo_get_image_upload";
  /**
   * Hard cap on the DECODED size of {@link #TOOL_NEO_UPLOAD_IMAGE}'s {@code data_base64}.
   *
   * <p>Deliberately far below the servlet endpoint's 10 MB: these bytes are model output, generated
   * token by token at roughly 1.4 characters per token, so 100 KB of image costs about 100k output
   * tokens. The cap is low on purpose, so nobody discovers that cost by paying it — over the cap the
   * error names {@link #TOOL_NEO_REQUEST_IMAGE_UPLOAD}, which moves the bytes out of the
   * conversation entirely.
   */
  static final int IMAGE_BASE64_MAX_BYTES = 256 * 1024;
  /**
   * Machine-detectable error code for a value written to an {@link #TYPE_IMAGE} field that is not an
   * existing {@code AD_Image} id (ETP-5184). Distinct from {@link #ERROR_VALIDATION} so an agent can
   * key on "this needs an upload first" rather than parsing the prose.
   */
  static final String ERROR_INVALID_IMAGE_REFERENCE = "invalid_image_reference";
  static final String TYPE_OBJECT = "object";
  static final String KEY_PROPERTIES = "properties";
  static final String KEY_DESCRIPTION = "description";
  static final String KEY_LABEL = "label";
  static final String GENERATE_PREFIX = "generate_";
  // Action result JSON keys
  static final String KEY_ERROR = "error";
  static final String KEY_STATUS = "status";
  static final String KEY_MESSAGE = "message";
  static final String KEY_PROCESS_RESULT = "processResult";
  static final String KEY_PROCESS_MESSAGE = "processMessage";
  /** Human-readable detail of a structured error object (IMP-5). */
  static final String KEY_DETAIL = "detail";
  /** Machine-detectable error code for a get-by-id that matched no record (IMP-5). */
  static final String ERROR_NOT_FOUND = "not_found";
  /** Machine-detectable error code for a write rejected on missing required fields (IMP-5). */
  static final String ERROR_VALIDATION = "validation_error";
  /** Machine-detectable error code for an FK-by-name resolution matching more than one record (IMP-4). */
  static final String ERROR_AMBIGUOUS_FK = "ambiguous_fk";
  /**
   * Machine-detectable error code for a failure the caller cannot fix by changing the request
   * (IMP-15). Distinct from {@link #ERROR_VALIDATION} on purpose: an agent must not retry-with-
   * corrections on this one.
   */
  static final String ERROR_SERVER = "server_error";
  /** Machine-detectable error code for a write on an entity whose method flag is off (IMP-15). */
  static final String ERROR_METHOD_NOT_ALLOWED = "method_not_allowed";

  /** A role-level refusal. Permanent for this role: re-sending the same call cannot succeed. */
  static final String ERROR_FORBIDDEN = "forbidden";
  /**
   * Machine-detectable error code for a filter key that resolves to no property on the entity
   * (ETP-5184). Distinct from {@link #ERROR_VALIDATION} because the fix is specific and known:
   * the key is wrong, and {@code available} names the ones that would have worked.
   *
   * <p>This case used to be logged and dropped. A caller filtering on a misspelled key therefore
   * got an unfiltered result set with a 200 on it — the worst possible answer, because it is
   * indistinguishable from "the filter matched everything". This is the same failure shape that
   * made {@code etendo_list} on a child entity return every row in the table.</p>
   */
  static final String ERROR_UNKNOWN_FILTER_FIELD = "unknown_filter_field";
  /** {@code etendo_selectors} on a column that is not a selector of the entity (ETP-5558). */
  static final String ERROR_UNKNOWN_SELECTOR_COLUMN = "unknown_selector_column";
  /** Machine-detectable code for a top-level argument the tool does not declare (IMP-40). */
  static final String ERROR_UNKNOWN_ARGUMENT = "unknown_argument";

  /**
   * IMP-44: {@code etendo_schema} was called without a {@code view}, or with a value that is not one
   * of its three projections. Distinct from {@link #ERROR_VALIDATION} because the request is
   * well-formed and the fix is a single named argument — and because the silent case it replaces
   * was worse than an error: an unrecognised view (e.g. {@code "summary"}, which belongs to
   * etendo_list/etendo_get, not here) used to fall through to the full dump, so the caller paid the
   * largest response in the tool for asking for the smallest.
   */
  static final String ERROR_VIEW_REQUIRED = "view_required";

  /**
   * A write carried a field the spec does not expose on this entity (IMP-39).
   *
   * <p>Named for what the caller may do, not for what exists: a field curated out of a window and
   * a field that was never a column of its table get this same code and the same message, so the
   * response cannot be used to probe which columns the underlying AD table really has.</p>
   */
  static final String ERROR_FIELD_NOT_ALLOWED = "field_not_allowed";

  /**
   * A write carried a value for a field the spec exposes as read-only (IMP-48).
   *
   * <p>Distinct from {@link #ERROR_FIELD_NOT_ALLOWED} on purpose, and the distinction leaks
   * nothing: {@code etendo_schema} already publishes this field with {@code readOnly: true}, so
   * naming the reason tells the caller only what it was told before it wrote. The other code
   * covers a field the surface never named, where saying more would be saying too much.</p>
   */
  static final String ERROR_READ_ONLY_FIELD = "read_only_field";
  /**
   * Machine-detectable error code for a call on a child entity that did not name its parent
   * (ETP-5184). In Etendo a child record is only ever browsed inside one parent record — there is
   * no global list — so a child call without {@code parentId} has no correct answer to give.
   */
  static final String ERROR_PARENT_REQUIRED = "parent_required";
  /**
   * Machine-detectable error code for a child write that named a parent the entity cannot be
   * linked to (ETP-5558). Distinct from {@link #ERROR_PARENT_REQUIRED}: the agent did pass
   * {@code parentId}, but no field of the entity points at the parent record, so the id has
   * nowhere to go. Writing on without it is what attached a {@code payment-out} line to an
   * unrelated collection.
   */
  static final String ERROR_PARENT_UNRESOLVABLE = "parent_unresolvable";
  /**
   * Machine-detectable error code for a tool that exists in this build but is switched off
   * (ETP-5335). Distinct from {@link #ERROR_NOT_FOUND}: the agent did not misspell anything and
   * will not find a working variant by retrying — the capability is deliberately unavailable, and
   * the answer says what to use instead.
   */
  static final String ERROR_TOOL_DISABLED = "tool_disabled";
  /**
   * Whether {@code etendo_batch} is published and routable (ETP-5335).
   *
   * <p><b>Off by decision, not by defect.</b> {@code etendo_batch} and {@code etendo_create} are two
   * different implementations of "create": {@code etendo_create} runs the MCP write pipeline in
   * {@code McpToolRouter#handleCreate}, while {@code etendo_batch} delegates each operation to the
   * shared REST path through {@code BatchService} → {@code NeoCrudHandler#handleDefault}. They had
   * drifted apart in both directions — {@code etendo_batch} misses the full mandatory-column sweep,
   * the unreadable-date 422, image-field validation, line-price derivation, FK-sentinel cleanup and
   * the entity pre-hook; {@code etendo_create} misses {@code injectCommercialAmounts}. Keeping one
   * write path correct is cheaper than keeping two in step, so the second one is switched off until
   * they converge.
   *
   * <p><b>What is given up.</b> Not the ability to create several records — an agent simply calls
   * {@code etendo_create} once per record — but <em>atomicity</em>: a batch rolls back as a unit
   * (IMP-23) and lets a later operation reference an earlier one's id through {@code $ref:}. With
   * it off, a run that fails halfway leaves the records already created in place, and the agent has
   * to carry the parent id forward itself.
   *
   * <p><b>Scope.</b> This flag governs the MCP tool only. The REST {@code /sws/neo/batch} endpoint
   * is untouched and keeps serving its callers (the OCR purchase-invoice ingest), so
   * {@code BatchService} stays live either way.
   *
   * <p><b>Re-enabled 2026-09-28 (ETP-5415).</b> The convergence this flag was waiting on is the
   * work this ticket did, so the list above is now mostly historical. What closed it:
   *
   * <ul>
   *   <li><b>the entity pre-hook</b> — already false when this text was written
   *       ({@code BatchService} has called {@code handleWithHooks} since ETP-4254), and now
   *       dispatched unconditionally: the blank-qualifier early return that guarded it was the
   *       last copy of one removed from every other path, and it made {@code @NeoExtension}
   *       work everywhere except here;</li>
   *   <li><b>line-price derivation</b>, <b>FK-sentinel cleanup</b> and <b>image-field
   *       validation</b> — added to the {@code etendo_batch} body transforms
   *       ({@code McpToolRouter#preprocessBatchOperation}), in {@code etendo_create}'s own order.
   *       Those transforms also moved from a pass over the whole operations array to one run per
   *       operation from inside the batch loop: before the batch starts, a {@code $ref} is an
   *       unresolved placeholder and a {@code parentRef}'s parent does not exist, so every
   *       parent-dependent injection abstained in silence and a batched line persisted at price
   *       0 while the identical single create priced correctly;</li>
   *   <li><b>the spec name</b> — {@code BatchService} built its {@code NeoContext} with the
   *       spec's UUID where every other path passes its name, so a customization branching on
   *       {@code getSpecName()} saw a different value here and {@code @NeoExtension} could not
   *       match at all (D10).</li>
   * </ul>
   *
   * <p><b>Still divergent, and deliberately not fixed here:</b> {@code etendo_create} does not run
   * {@code injectCommercialAmounts}, which {@code etendo_batch} gets through the shared path. That is
   * a defect on the <em>create</em> side, it is independent of this flag, and folding it in is its
   * own step — a create path that silently persists a zero gross amount is a data bug worth
   * landing on its own evidence rather than inside a re-enablement. ETP-5528 left this shared path
   * as it is: sales order and sales quotation lines get the amounts from their own customizations
   * ({@code OrderLineDiscountSupport}) on every create, and so do sales invoice lines
   * ({@code SalesInvoiceLineHandler} → {@code InvoiceLineAmountSupport}: {@code lineNetAmount} and
   * {@code grossAmount}). Every other entity still does not on {@code etendo_create}, as on
   * {@code develop} — purchase invoice lines and purchase order lines included (a purchase invoice
   * line still persists {@code lineNetAmount 0} / {@code grossAmount 0}) — see
   * {@code neo-headless.md} §4.12.9 and §4.12.20.
   *
   * <p>The guard against this drifting again is {@code NeoExtensionParityTest} (E4), which asserts
   * over the execution trace that one customization is reached by every channel. It cannot see a
   * missing generic pipeline step — only that the customization ran — so the list above still has
   * to be read by a human when either write path changes.
   *
   * <p>To switch off again: flip to {@code false}. The tool disappears from {@code tools/list} and
   * stops routing; nothing else has to change.
   */
  private static final boolean BATCH_TOOL_ENABLED_VALUE = true;

  /**
   * Whether {@code etendo_batch} is published and routable.
   *
   * <p><b>A method, not the constant, and that is the whole point.</b> A {@code static final
   * boolean} initialised to a literal is a <i>compile-time constant</i>: javac inlines its value
   * into every use site and deletes the dead branch. Recompiling this file alone therefore changes
   * nothing — the callers keep the value they were built against, and the only symptom is the
   * feature not behaving as the source says.
   *
   * <p>That is not hypothetical. Flipping the value to {@code true} and rebuilding produced
   * {@code McpConstants.class} at 13:13 and {@code ToolRegistry.class} still at 12:51, with
   * {@code buildBatchTool()} present as a declaration and called from nowhere: the tool was absent
   * from {@code tools/list} while the source said it was on.
   *
   * <p>The dangerous shape is the next one, not that one. An incremental build can refresh
   * <i>some</i> use sites and not others, leaving the tool published by {@link ToolRegistry} and
   * refused by {@code McpToolRouter} — or the reverse. That failure depends on compilation order,
   * survives a restart, and looks nothing like a flag.
   *
   * <p>A method call is not inlined into the caller's bytecode, so every reader sees whatever this
   * file was last compiled with. Read it through here, never through the field.
   *
   * @return {@code true} while the tool is enabled
   */
  static boolean batchToolEnabled() {
    return BATCH_TOOL_ENABLED_VALUE;
  }
  /**
   * How many names an {@code available} list may carry before it is truncated (ETP-5184). Twenty
   * is enough for the agent to spot its own typo; a wide entity has 150+ properties and dumping
   * them all turns a one-line correction into a context bill.
   */
  static final int MAX_AVAILABLE_NAMES = 20;
  /** HTTP-style status for a not-found result (IMP-5). */
  static final int STATUS_NOT_FOUND = 404;
  /** HTTP-style status for a refusal the caller cannot fix by rewriting the request (IMP-41). */
  static final int STATUS_FORBIDDEN = 403;
  /** HTTP-style status for a validation failure on a write (IMP-5). */
  static final int STATUS_UNPROCESSABLE = 422;
  /**
   * Machine-detectable error code for a write the current data refuses — a duplicate business key
   * (ETP-4793 / IMP-17). Distinct from {@link #ERROR_VALIDATION}: the request is well-formed, so
   * re-sending corrected values is not necessarily the remedy; the agent may need a different record.
   */
  static final String ERROR_CONFLICT = "conflict";
  /** HTTP-style status for a duplicate-key conflict (ETP-4793 / IMP-17). */
  static final int STATUS_CONFLICT = 409;
  /**
   * Machine-detectable error code for a write refused because the record changed underneath the
   * caller — core's optimistic-locking check (ETP-5073 / DOC-04).
   *
   * <p>Deliberately NOT {@link #ERROR_CONFLICT}, even though both answer 409: the two demand
   * opposite remedies. A duplicate key means "your data collides with an existing record, send
   * different values or target that record". A stale record means "your values are fine, your
   * baseline is not — re-read and reapply". An agent that cannot tell them apart retries the wrong
   * one, and on this branch retrying unchanged is an infinite loop.
   */
  static final String ERROR_STALE_RECORD = "stale_record";
  /**
   * The audit value {@code etendo_update} requires so core's concurrency check can run
   * (ETP-5073 / DOC-04). Read from {@code etendo_get}/{@code etendo_list} and echoed back verbatim.
   */
  static final String PARAM_UPDATED = "updated";
  /** HTTP-style status for a failure the caller cannot fix (ETP-4793 / IMP-17). */
  static final int STATUS_SERVER_ERROR = 500;
  /** HTTP-style status for a verb the entity does not enable (ETP-4793 / IMP-17). */
  static final int STATUS_METHOD_NOT_ALLOWED = 405;
  /**
   * Key listing the fields a write is missing. Established by IMP-5 for {@code etendo_create} and
   * reused verbatim by every later path that reports the same condition, so an agent parses one
   * key (ETP-4793 / IMP-17, IMP-24 §2).
   */
  static final String KEY_MISSING_FIELDS = "missingFields";
  /**
   * Key listing the valid values for a name the caller got wrong — the self-correcting shape IMP-3
   * established for unknown named filters, reused for an unknown entity name (ETP-4793 / IMP-17).
   */
  static final String KEY_AVAILABLE = "available";

  /** The {@code docs} tool name — surfaced by etendo_discover guidance and error pointers (IMP-10). */
  static final String TOOL_DOCS = "docs";
  /** Key for the etendo_discover guidance object that routes the agent to {@code docs} (IMP-10). */
  static final String KEY_GUIDANCE = "guidance";
  /** Key for the {@code tool} pointer inside the guidance object (IMP-10). */
  static final String KEY_TOOL = "tool";
  /** Key for the free-text hint inside the guidance object (IMP-10). */
  static final String KEY_HINT = "hint";
  /** Key that points a structured error at a relevant {@code docs} recipe (IMP-10). */
  static final String KEY_SEE_ALSO = "seeAlso";
  /**
   * Key inviting the agent to report what just went wrong through {@code etendo_feedback} (B3).
   * <p>
   * A sibling of {@link #KEY_SEE_ALSO} rather than a reuse of it: {@code seeAlso} is single-valued
   * and on the write paths it already carries a {@code docs} recipe, so writing the invitation
   * there would delete the more actionable pointer at exactly the moment the agent needs it.
   */
  static final String KEY_FEEDBACK = "feedback";
  /** The invitation itself. Present on error envelopes because that is when it is worth most. */
  static final String FEEDBACK_INVITATION =
      "If this error was confusing, or you had to guess at something, call etendo_feedback to say so. "
          + "It costs nothing, it is never charged against you, and it is the only way the people "
          + "who build this API find out what it is like to use.";
  /** Tool an agent calls to report friction in its own words (B3). */
  static final String TOOL_NEO_FEEDBACK = "etendo_feedback";
  /**
   * Told to the agent by etendo_get and etendo_create so it knows a ready-made link is in the response
   * and never has to invent one (ETP-5200). Emitted only for header records, and only when the
   * deployment has a public app base URL configured — see {@link McpRecordUrls}.
   */
  static final String RECORD_URL_NOTE =
      "When the record is a spec's primaryEntity, the response carries a `url` field: the Etendo "
          + "Go link to that record. Use it verbatim when referring the user to the record — never "
          + "build a link by hand.";
  /**
   * How a reference to a record is written, declared once instead of shipped on every row
   * (ETP-5306).
   *
   * <p>Records used to carry a prebuilt {@code $ref} field. It was removed because Gemini treats
   * {@code $ref} as a reserved pointer into {@code function_response.parts} and rejects the whole
   * response over it (see {@link McpResponseSanitizer}), and because it was pure redundancy —
   * {@code _entityName} and {@code id} are on the same row. Removing the value must not remove the
   * knowledge, so the construction rule is stated in the two places an agent learns shapes:
   * {@code etendo_schema}'s hint and the {@code docs} preamble.</p>
   */
  static final String RECORD_REF_NOTE =
      "A reference to a record is written `<entityName>/<id>` — build it yourself from the "
          + "`_entityName` and `id` fields that every row carries. No response ships a prebuilt "
          + "reference field.";

  /** Hint advertised by etendo_discover to route a cold agent to ready-to-run recipes (IMP-10). */
  static final String GUIDANCE_DOCS_HINT =
      "Call docs(topic:…) for ready-to-run recipes per task.";
  /** {@code docs} recipe an agent should read after a not-found on a get-by-id (IMP-10). */
  static final String SEE_ALSO_READING = "docs(topic:\"reading records\")";
  /** {@code docs} recipe an agent should read after a create/update validation failure (IMP-10). */
  static final String SEE_ALSO_WRITING = "docs(topic:\"creating records\")";

  // Button-action metadata surfaced by etendo_schema (ETP-4285)
  /**
   * Key under which a list-backed button's chosen value travels in {@code etendo_action}'s
   * {@code parameters}. Consumed by {@code NeoProcessService.setDocAction}, which writes it
   * onto the record before the process runs.
   */
  static final String PARAM_DOC_ACTION = "docAction";
  /** {@code etendo_schema} key listing the discrete values a button accepts. */
  static final String KEY_ACTION_VALUES = "actionValues";
  /** {@code etendo_schema} key naming the parameter the chosen value must go under. */
  static final String KEY_ACTION_PARAMETER = "actionParameter";
  /** The body object the SPA's process dialog posts a button's parameters under (ETP-5587). */
  static final String KEY_FIELD_VALUES = "fieldValues";

  static final String LABEL_SPEC_NAME = "Spec name";
  static final String LABEL_ENTITY_NAME = "Entity name within the spec";
  static final String LABEL_ENTITY_NAME_WITH_EXAMPLE =
      "Entity name within the spec (e.g. 'header', 'lines')";

  /** Tool name for the amortization plan generation tool. */
  static final String TOOL_GENERATE_AMORTIZATION_PLAN = "etendo_generate_amortization_plan";

  /**
   * The eight CRUD tool names. Declared here, beside the other tool names, so the enum in the
   * tool definitions, the CRUD test in {@code ToolRegistry} and the router's dispatch all read
   * the same spelling from one place.
   */
  static final String TOOL_NEO_LIST = "etendo_list";
  /** @see #TOOL_NEO_LIST */
  static final String TOOL_NEO_GET = "etendo_get";
  /** @see #TOOL_NEO_LIST */
  static final String TOOL_NEO_CREATE = "etendo_create";
  /** @see #TOOL_NEO_LIST */
  static final String TOOL_NEO_UPDATE = "etendo_update";
  /** @see #TOOL_NEO_LIST */
  static final String TOOL_NEO_DELETE = "etendo_delete";
  /** @see #TOOL_NEO_LIST */
  static final String TOOL_NEO_SELECTORS = "etendo_selectors";
  /** @see #TOOL_NEO_LIST */
  static final String TOOL_NEO_DEFAULTS = "etendo_defaults";
  /** @see #TOOL_NEO_LIST */
  static final String TOOL_NEO_SCHEMA = "etendo_schema";

  /** Tool name for the business-widget enum tool (gap G4, ETP-4284). */
  static final String TOOL_NEO_WIDGET = "etendo_widget";
  /** Global semantic vector-search tool backed by DB Extended. */
  static final String TOOL_NEO_VECTOR_SEARCH = "etendo_vector_search";
  /** Prefix the fixed tools carried before ETP-5602 renamed them to {@code etendo_*}. */
  static final String LEGACY_TOOL_PREFIX = "neo_";
  /**
   * The fixed tools renamed from {@code neo_<x>} to {@code etendo_<x>} in ETP-5602. A call to an
   * old name is answered with the new one ({@link McpRoutingException#toolRenamed}); it is never
   * executed under the old name.
   */
  static final Set<String> TOOLS_RENAMED_FROM_NEO = Set.of(
      "etendo_discover", TOOL_NEO_LIST, TOOL_NEO_GET, TOOL_NEO_CREATE, TOOL_NEO_UPDATE,
      TOOL_NEO_DELETE, TOOL_NEO_SELECTORS, TOOL_NEO_DEFAULTS, TOOL_NEO_SCHEMA, "etendo_batch",
      "etendo_action", TOOL_NEO_WIDGET, TOOL_NEO_VECTOR_SEARCH, TOOL_NEO_FEEDBACK,
      TOOL_NEO_UPLOAD_IMAGE, TOOL_NEO_REQUEST_IMAGE_UPLOAD, TOOL_NEO_GET_IMAGE_UPLOAD,
      TOOL_GENERATE_AMORTIZATION_PLAN);

  /** Spec name that backs the widget handler entities (type W, no AD_Tab). */
  static final String SPEC_DASHBOARD = "dashboard";

  // Widget enum keys / backing dashboard entity names (gap G4, ETP-4284).
  static final String WIDGET_KPIS = "kpis";
  static final String WIDGET_REVENUE_TREND = "revenue-trend";
  static final String WIDGET_PENDING_TASKS = "pending-tasks";
  static final String WIDGET_ACTIVITY = "activity";
  static final String WIDGET_RECENT_INVOICES = "recent-invoices";
  static final String WIDGET_BEST_PRODUCTS = "best-products";
  static final String WIDGET_BEST_SELLERS = "best-sellers";
  static final String WIDGET_PENDING_AMOUNTS = "pending-amounts";
  static final String WIDGET_TOP_CLIENTS = "top-clients";

  private McpConstants() {
  }
}
