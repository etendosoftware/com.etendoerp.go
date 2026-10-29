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

import static com.etendoerp.go.mcp.McpJsonSchema.KEY_REQUIRED;
import static com.etendoerp.go.mcp.McpJsonSchema.booleanProp;
import static com.etendoerp.go.mcp.McpJsonSchema.buildObjectSchema;
import static com.etendoerp.go.mcp.McpJsonSchema.enumProp;
import static com.etendoerp.go.mcp.McpJsonSchema.numericProp;
import static com.etendoerp.go.mcp.McpJsonSchema.objectArrayProp;
import static com.etendoerp.go.mcp.McpJsonSchema.objectProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringArrayProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringEnumArrayProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringProp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.oauth2.ApiScopes;
import com.etendoerp.go.schemaforge.BatchJobService;
import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.NeoVectorSearchEndpoint;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;
import com.etendoerp.go.schemaforge.util.NeoReportContract;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Generates MCP tool definitions dynamically based on ETGO_SF_SPEC configuration
 * and the current user's RBAC permissions.
 * <p>
 * For each active spec, the registry checks:
 * <ol>
 *   <li>RBAC — does the current role have access to the linked AD_Window or AD_Process?</li>
 *   <li>OAuth2 scopes — does the session have the required scope (etendo:read, etendo:write, etc.)?</li>
 * </ol>
 * <p>
 * Tool generation strategy:
 * <ul>
 *   <li><b>CRUD tools</b> (etendo_list, etendo_get, etendo_create, etendo_update, etendo_delete, etendo_selectors,
 *       etendo_defaults): registered ONCE with a required {@code spec} parameter that has an enum
 *       listing all accessible window spec names. This avoids MCP tool name collisions.</li>
 *   <li><b>Process tools</b>: one per process spec, named by spec (e.g. "complete_order")</li>
 *   <li><b>Report tools</b>: one per report spec, prefixed with "generate_"</li>
 *   <li><b>etendo_discover</b>: always included when the user has read access</li>
 * </ul>
 */
public class ToolRegistry {

  private static final Logger log = LogManager.getLogger(ToolRegistry.class);

  /** JSON-schema numeric type used by integer-valued MCP arguments. */
  private static final String TYPE_INTEGER = "integer";

  /**
   * Generate all MCP tools the authenticated user can access.
   *
   * @param scopes OAuth2 scopes granted to this session
   * @return list of tool definitions filtered by RBAC and scopes
   */
  public List<McpToolDefinition> generateTools(Set<String> scopes) {
    List<McpToolDefinition> tools = new ArrayList<>();
    ScopePermissions permissions = resolvePermissions(scopes);

    // Always add etendo_discover if user can read
    if (permissions.canRead) {
      tools.add(buildDiscoverTool());
      tools.add(buildDocsTool());
      // etendo_widget wraps the handler-backed business widgets (gap G4, ETP-4284). It is a
      // built-in read tool, not gated on any accessible window spec.
      tools.add(buildWidgetTool());
      tools.add(buildVectorSearchTool());
      tools.add(buildFeedbackTool());
    }

    // Query all active specs
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SHOWINMCP, true));
    criteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    List<SFSpec> specs = criteria.list();

    // Collect the spec enum each CRUD tool can actually satisfy. Read tools share every
    // accessible W spec; write tools are split per verb so a mixed spec is never advertised
    // by a tool for which it has no enabled entity method (ETP-4254 AC#4).
    List<String> accessibleWindowSpecs = new ArrayList<>();
    List<String> creatableWindowSpecs = new ArrayList<>();
    List<String> updatableWindowSpecs = new ArrayList<>();
    List<String> deletableWindowSpecs = new ArrayList<>();

    // ETP-5468: report specs whose handler declares named actions (bank-reconciliation). They
    // are not window specs — etendo_list/etendo_get cannot serve them — so they join ONLY the enums of
    // the two tools that can: etendo_schema (to read the action contracts) and etendo_action.
    List<String> actionReportSpecs = new ArrayList<>();

    for (SFSpec spec : specs) {
      processSpec(spec, accessibleWindowSpecs, creatableWindowSpecs, updatableWindowSpecs,
          deletableWindowSpecs, tools, permissions);
      if (ToolRegistryActionSpecs.isActionReportSpec(spec)) {
        actionReportSpecs.add(spec.getName());
      }
    }

    registerCrudTools(tools, accessibleWindowSpecs, creatableWindowSpecs,
        updatableWindowSpecs, deletableWindowSpecs, permissions, actionReportSpecs);

    // ETP-5184: the image-upload tools are built-in and type-driven, not spec-driven — they create
    // an AD_Image row and nothing else, and the same three tools serve every image-typed field in
    // the instance. Gated on write scope because they do write a row.
    if (permissions.canWrite) {
      tools.add(McpImageToolDefinitions.requestImageUpload());
      tools.add(McpImageToolDefinitions.uploadImage());
      tools.add(McpImageToolDefinitions.getImageUpload());
    }

    // IMP-53: every published tool, whatever its kind, accepts the presentation flag.
    tools.replaceAll(McpIndentResponse::declare);
    log.debug("Generated {} MCP tools for scopes {}", tools.size(), scopes);
    return tools;
  }

  private ScopePermissions resolvePermissions(Set<String> scopes) {
    return new ScopePermissions(
        ApiScopes.grants(scopes, ApiScopes.READ),
        ApiScopes.grants(scopes, ApiScopes.WRITE),
        ApiScopes.grants(scopes, ApiScopes.PROCESS),
        ApiScopes.grants(scopes, ApiScopes.REPORT));
  }

  private void processSpec(SFSpec spec, List<String> accessibleWindowSpecs,
      List<String> creatableWindowSpecs, List<String> updatableWindowSpecs,
      List<String> deletableWindowSpecs, List<McpToolDefinition> tools,
      ScopePermissions permissions) {
    try {
      String specType = spec.getSpecType();
      if ("W".equals(specType)) {
        addWindowSpec(spec, accessibleWindowSpecs, creatableWindowSpecs,
            updatableWindowSpecs, deletableWindowSpecs);
        return;
      }
      if ("P".equals(specType) && hasProcessAccess(spec) && permissions.canProcess) {
        tools.add(buildProcessTool(spec.getName(), spec));
        return;
      }
      // A generate_ tool is emitted only for a report spec that clears two independent gates.
      // ETP-4596: the role must be able to reach it — a process-less report spec gates on its
      // constituent windows (AD_TAB_ID) via hasReportSpecAccess instead of always being offered.
      // ETP-4793 / IMP-19: the spec's NEO-native handler must declare a report contract.
      // Anything else — no handler, or a handler that serves the entity for some other purpose —
      // gets no tool and surfaces as not configured via neo discover. The contract is resolved
      // once here and carried into the schema so the tool an agent reads and the parameters the
      // router enforces cannot diverge. The access gate runs first because it is the security
      // one: both predicates are pure, so the order changes only which cost is paid on a spec
      // that fails both, and a spec the role cannot see should not have its handlers looked up.
      if ("R".equals(specType) && permissions.canReport
          && NeoAccessUtils.hasReportSpecAccess(spec, "GET")) {
        NeoReportCallability.resolveReportContract(spec)
            .ifPresent(contract -> tools.add(buildReportTool(spec.getName(), spec, contract)));
      }
    } catch (Exception e) {
      log.warn("Error generating tools for spec '{}': {}", spec.getName(), e.getMessage());
    }
  }

  private void addWindowSpec(SFSpec spec, List<String> accessibleWindowSpecs,
      List<String> creatableWindowSpecs, List<String> updatableWindowSpecs,
      List<String> deletableWindowSpecs) {
    // A spec with neither a CRUD nor an action surface (the dashboard's widgets) is exposed
    // via the etendo_widget tool and must not pollute the CRUD spec enum (ETP-4284 / G4).
    // ETP-4254 made this data-driven instead of matching the literal "dashboard" name. A
    // tab-less spec that still serves actions (not-posted-documents) is NOT excluded — it
    // belongs in accessibleWindowSpecs so etendo_action can still offer it.
    if (McpToolRouterSupport.isCatalogExcludedSpec(spec)) {
      return;
    }
    if (!NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")
        || McpToolRouterSupport.isHiddenAccountingSpec(spec)) {
      return;
    }
    accessibleWindowSpecs.add(spec.getName());

    // Split the write catalog per method. A spec with one PUT/PATCH entity but no POST or
    // DELETE entity (monitor-verifactu) belongs only in etendo_update; a shared "writable"
    // enum would incorrectly advertise it to etendo_create and etendo_delete.
    if (McpToolRouterSupport.hasEntityWithMethod(spec, "POST")
        && NeoAccessUtils.hasWindowAccessForSpec(spec, "POST")) {
      creatableWindowSpecs.add(spec.getName());
    }
    if (McpToolRouterSupport.hasEntityWithMethod(spec, "PUT")
        && NeoAccessUtils.hasWindowAccessForSpec(spec, "PUT")) {
      updatableWindowSpecs.add(spec.getName());
    }
    if (McpToolRouterSupport.hasEntityWithMethod(spec, "DELETE")
        && NeoAccessUtils.hasWindowAccessForSpec(spec, "DELETE")) {
      deletableWindowSpecs.add(spec.getName());
    }
  }

  private boolean hasProcessAccess(SFSpec spec) {
    Process adProcess = spec.getProcess();
    return adProcess == null || NeoAccessUtils.hasProcessAccess(adProcess.getId());
  }

  /**
   * Register the shared CRUD tools once, with the spec enum each group is entitled to.
   *
   * <p>ETP-4254 splits the enum by capability:</p>
   * <ul>
   *   <li>{@code accessibleWindowSpecs} — every readable window spec. Used by the read tools
   *       AND by {@code etendo_action}: button actions/processes are served by the
   *       {@code /action/*} sub-endpoint, which is deliberately NOT gated by the
   *       {@code ETGO_SF_ENTITY} method flags, so a read-only-CRUD monitor window can still
   *       legitimately fire an action.</li>
   *   <li>{@code creatableWindowSpecs}/{@code updatableWindowSpecs}/
   *       {@code deletableWindowSpecs} — specs with at least one entity enabling the exact
   *       verb each MCP write tool uses. This matters for mixed specs: monitor-verifactu has
   *       PUT/PATCH on one entity but no POST or DELETE, so only etendo_update may offer it.</li>
   * </ul>
   *
   * <p>{@code etendo_batch} takes no spec enum (its operations name their spec inline); its
   * per-entity gate is enforced at runtime in {@code BatchService#createRecord}.</p>
   */
  private void registerCrudTools(List<McpToolDefinition> tools, List<String> accessibleWindowSpecs,
      List<String> creatableWindowSpecs, List<String> updatableWindowSpecs,
      List<String> deletableWindowSpecs, ScopePermissions permissions,
      List<String> actionReportSpecs) {
    // Register the amortization plan tool independently of window specs availability:
    // it is a built-in endpoint that does not require a window spec to be accessible.
    if (permissions.canProcess) {
      tools.add(buildGenerateAmortizationPlanTool());
    }

    if (accessibleWindowSpecs.isEmpty()) {
      return;
    }
    if (permissions.canRead) {
      tools.add(buildListTool(accessibleWindowSpecs,
          McpNamedFilterCatalog.summary(accessibleWindowSpecs)));
      tools.add(buildGetTool(accessibleWindowSpecs));
      tools.add(buildSelectorsTool(accessibleWindowSpecs));
      tools.add(buildDefaultsTool(accessibleWindowSpecs,
          McpDefaultsContextCatalog.summary(accessibleWindowSpecs)));
      tools.add(buildSchemaTool(ToolRegistryActionSpecs.withActionSpecs(accessibleWindowSpecs, actionReportSpecs)));
    }
    if (permissions.canWrite) {
      if (!creatableWindowSpecs.isEmpty()) {
        tools.add(buildCreateTool(creatableWindowSpecs));
      }
      if (!updatableWindowSpecs.isEmpty()) {
        tools.add(buildUpdateTool(updatableWindowSpecs));
      }
      if (!deletableWindowSpecs.isEmpty()) {
        tools.add(buildDeleteTool(deletableWindowSpecs));
      }
      // Published only while the flag is on. ETP-5335 switched it off because etendo_batch was a
      // second create implementation that had drifted from etendo_create in both directions, and
      // ETP-5415 closed those divergences and turned it back on. The accessor is a method, not
      // the constant, so a partial rebuild cannot leave this class publishing a tool the router
      // still refuses. See McpConstants#batchToolEnabled.
      if (McpConstants.batchToolEnabled()) {
        tools.add(buildBatchTool());
        // ETP-5677: the follow-up of etendo_batch {async:true}; published with it.
        tools.add(buildJobStatusTool());
        tools.add(buildJobCancelTool());
      }
    }
    // ETP-5783: published under the scope its call requires (etendo:process), not under write.
    if (permissions.canProcess) {
      tools.add(buildActionTool(ToolRegistryActionSpecs.withActionSpecs(accessibleWindowSpecs, actionReportSpecs)));
      tools.add(McpActionBatch.definition(
          ToolRegistryActionSpecs.withActionSpecs(accessibleWindowSpecs, actionReportSpecs)));
    }
    // ETP-5715: the attachment tools — list on the read scope, upload and delete on the write scope.
    McpAttachmentToolDefinitions.addTo(tools, accessibleWindowSpecs, permissions.canRead,
        permissions.canWrite);
  }

  // ── Amortization plan tool ─────────────────────────────────────────────

  private McpToolDefinition buildGenerateAmortizationPlanTool() {
    Map<String, Object> properties = new LinkedHashMap<>();
    Map<String, Object> assetIdProp = new HashMap<>();
    assetIdProp.put(McpConstants.KEY_DESCRIPTION,
        "The ID of the asset to generate the amortization plan for");
    assetIdProp.put("type", McpConstants.TYPE_STRING);
    properties.put("assetId", assetIdProp);

    return new McpToolDefinition(
        McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN,
        "Generate an amortization plan for an asset. Fires the native A_Asset_Post process "
            + "and returns the resulting plan summary (periods, amounts, dates). "
            + "The asset must be configured for depreciation and must not already have a plan.",
        buildObjectSchema(properties, java.util.Arrays.asList("assetId"))
    );
  }

  // ── Tool name resolution ──────────────────────────────────────────────

  /**
   * Resolve the spec name associated with a tool name.
   * <p>
   * For CRUD tools (etendo_list, etc.), the spec comes from the "spec" argument.
   * For process tools, the tool name IS the snake_case version of the spec name.
   * For report tools, strip the "generate_" prefix and convert back to kebab.
   *
   * @param toolName  the MCP tool name
   * @param arguments the tool call arguments (may contain "spec")
   * @return the spec name, or null if not resolvable
   */
  public static String resolveSpecName(String toolName, org.codehaus.jettison.json.JSONObject arguments) {
    // Static tools (e.g. docs) are not tied to any spec
    if ("docs".equals(toolName) || McpConstants.TOOL_NEO_VECTOR_SEARCH.equals(toolName)
        || McpConstants.TOOL_NEO_FEEDBACK.equals(toolName)) {
      return null;
    }
    // IMP-53: etendo_discover's optional 'spec' narrows the catalog, it does not address a spec.
    // The handler checks each requested name against the specs this role reaches and refuses an
    // unknown one with the reachable names, so it must not go through the single-spec gate (which
    // would answer 404 without them, and cannot read an array of names).
    if (McpConstants.TOOL_NEO_DISCOVER.equals(toolName)) {
      return null;
    }

    // CRUD tools carry spec in arguments
    if (isCrudTool(toolName)) {
      return arguments != null ? arguments.optString("spec", null) : null;
    }

    // Report tools: strip "generate_" prefix and convert back to kebab
    if (toolName.startsWith(McpConstants.GENERATE_PREFIX)) {
      return snakeToKebab(toolName.substring(McpConstants.GENERATE_PREFIX.length()));
    }

    // Process tools: tool name is snake_case of spec name
    return snakeToKebab(toolName);
  }

  /**
   * Check if a tool name is a CRUD tool (shared across specs).
  *
  * @param toolName the MCP tool name
  * @return true when the tool is one of the shared CRUD tools
   */
  public static boolean isCrudTool(String toolName) {
    switch (toolName) {
      case "etendo_discover":
      case McpConstants.TOOL_NEO_LIST:
      case McpConstants.TOOL_NEO_GET:
      case McpConstants.TOOL_NEO_CREATE:
      case McpConstants.TOOL_NEO_UPDATE:
      case McpConstants.TOOL_NEO_DELETE:
      case McpConstants.TOOL_NEO_SELECTORS:
      case McpConstants.TOOL_NEO_DEFAULTS:
      case McpConstants.TOOL_NEO_SCHEMA:
      case "etendo_batch":
      case "etendo_action":
      case McpConstants.TOOL_NEO_ACTION_BATCH:
      case McpConstants.TOOL_NEO_WIDGET:
      case McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN:
      // ETP-5184: listed here so resolveSpecName does not derive a spec name from the tool name.
      // These tools address no spec at all — they create an AD_Image row — and "etendo-upload-image"
      // would be looked up as a spec and denied.
      case McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD:
      case McpConstants.TOOL_NEO_UPLOAD_IMAGE:
      case McpConstants.TOOL_NEO_GET_IMAGE_UPLOAD:
      // ETP-5715: these address a spec by the `spec` argument like every CRUD tool.
      case McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD:
      case McpConstants.TOOL_NEO_LIST_ATTACHMENTS:
      case McpConstants.TOOL_NEO_DELETE_ATTACHMENT:
      case McpConstants.TOOL_NEO_GET_DOCUMENT_PDF:
      case McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL:
      case McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD:
      case McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT:
      // ETP-5677: they address a job, not a spec.
      case McpConstants.TOOL_NEO_JOB_STATUS:
      case McpConstants.TOOL_NEO_JOB_CANCEL:
        return true;
      default:
        return false;
    }
  }

  // ── Discovery tool ─────────────────────────────────────────────────────

  private McpToolDefinition buildDiscoverTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    // IMP-53: optional. The full catalog runs to tens of KB; an agent that already knows the spec
    // it needs pays only for that one.
    props.put(McpConstants.PARAM_SPEC, stringProp(
        "Optional spec name. Pass it to get only that spec's entities, parent links and actions "
            + "instead of the whole catalog. An unknown name is refused with the names you can use."));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", McpConstants.TYPE_OBJECT);
    schema.put(McpConstants.KEY_DESCRIPTION,
        "Discover the available Etendo API specs and their entities");
    schema.put(McpConstants.KEY_PROPERTIES, props);
    return new McpToolDefinition(
        McpConstants.TOOL_NEO_DISCOVER,
        "List all available Etendo API specs the current user can access. "
            + "Returns spec names, types, entities, and available HTTP methods. "
            + "Use this first to discover what specs and entities are available. "
            + "Pass spec to get only that spec's entities, parent links and actions. "
            + "The answer is always complete, never paged: count is the number of specs returned. "
            + "If your client shows it cut, call it again with spec for the one you need.",
        schema);
  }

  // ── Docs tool (Context7 documentation lookup) ─────────────────────────

  private McpToolDefinition buildDocsTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("topic", stringProp(
        "Term/topic to search in the Etendo docs (e.g. 'finance', 'payment')."));
    props.put("tokens", numericProp(TYPE_INTEGER,
        "Approximate max size of the returned docs (default 5000, clamped to 500-20000)."));
    props.put("type", stringProp(
        "Response format: 'txt' (default) or 'json'."));

    return new McpToolDefinition(
        "docs",
        "Search the Etendo documentation (via Context7) "
            + "for a given topic and return the relevant documentation text inline. "
            + "Use this to look up how-tos, concepts, and reference material before "
            + "answering questions about Etendo.",
        buildObjectSchema(props, List.of("topic")));
  }

  // ── Feedback tool (B3) ─────────────────────────────────────────────────

  /**
   * The {@code etendo_feedback} tool definition.
   *
   * <p>The description does real work here. An agent will not volunteer feedback it was never
   * invited to give, so the text says plainly that reporting friction is wanted, that it costs
   * nothing, and that it is never held against the agent. That invitation, plus the pointer on
   * error envelopes, is the whole adoption mechanism (B3).</p>
   */
  private McpToolDefinition buildFeedbackTool() {
    Map<String, Object> friction = new LinkedHashMap<>();
    friction.put("what", stringProp("What was difficult, ambiguous or unclear."));
    friction.put("cost", stringProp("What it cost, e.g. '3 wasted calls'."));
    friction.put("phase", enumProp("Where in the task the difficulty occurred.",
        McpFeedbackVerdict.PHASES));

    Map<String, Object> failure = new LinkedHashMap<>();
    failure.put("tool", stringProp("Exact name of the tool that failed."));
    failure.put("payload", objectProp("The exact arguments sent on the failing call."));
    failure.put("error", stringProp("The error returned, verbatim."));
    failure.put("recovered", booleanProp(
        "Whether you afterwards worked around it. A failure you fixed yourself is still a defect "
            + "on our side — report it either way."));
    failure.put("howRecovered", stringProp("How you worked around it, if you did."));

    // v2: the call that returned 200 and got you nowhere. It has its own list rather than being
    // folded into frictions so it can be counted — nothing in the transcript marks it, because
    // nothing went wrong.
    Map<String, Object> wastedCall = new LinkedHashMap<>();
    wastedCall.put("tool", stringProp("Exact name of the tool you called."));
    wastedCall.put("expected", stringProp(
        "What you expected this call to return, as you expected it BEFORE you made the call."));
    wastedCall.put("whatHappened", stringProp(
        "What it actually returned, and why that was of no use to you."));

    // v3: suggestions carry a shape so they can be counted and grouped, instead of being prose
    // nobody can aggregate.
    Map<String, Object> suggestion = new LinkedHashMap<>();
    suggestion.put("what", stringProp("What should exist or change."));
    suggestion.put("kind", enumProp(
        "Which kind of thing you are asking for. Pick 'other' whenever nothing here fits — it is "
            + "a normal answer, not a last resort, and it is how we find out which category we "
            + "are missing. Do not force your suggestion into a category it does not belong in.",
        McpFeedbackVerdict.SUGGESTION_KINDS));
    suggestion.put("wouldHaveSaved", stringProp(
        "What this would have saved you on THIS task specifically — the calls, the guesswork or "
            + "the dead end it would have removed. If it would have saved you nothing here, say "
            + "so plainly: a suggestion that helps somebody else is still worth having, and an "
            + "invented payoff is worse than none."));

    Map<String, Object> props = new LinkedHashMap<>();
    props.put("outcome", enumProp(
        "OKAY only if the task was fully completed; MIXED if partially; ERROR if not completed.",
        McpFeedbackVerdict.OUTCOMES));
    props.put("summary", stringProp("One sentence: what happened."));
    props.put("achieved", stringProp("What you actually accomplished, not what you attempted."));
    // v2: what you MEANT to do, and where that came from. Both descriptions say outright that
    // "no plan" and "I guessed" are acceptable — a schema that makes the honest answer feel wrong
    // gets fiction back, and fiction here is worse than a blank.
    props.put("plannedApproach", stringProp(
        "Recall the plan you had BEFORE you started: the sequence of tools you intended to use, "
            + "as you understood the task at the time — for example 'list the entities, then read "
            + "the schema of the right one, then create the record'. Report what you actually "
            + "believed then, not the route that turned out to work. If you had no plan and "
            + "worked it out as you went, say exactly that: it is a complete and acceptable "
            + "answer."));
    props.put("howKnown", stringProp(
        "Where that plan came from. Name the specific source: the output of a particular tool "
            + "(name the tool and say what in its output told you), the description of a "
            + "particular tool, knowledge you already had, or guesswork. 'I guessed' is a valid "
            + "and valuable answer — say so plainly rather than inventing a source. A vague "
            + "answer such as 'from the tools' is of no use."));
    props.put("frictions", objectArrayProp(
        "Everything that was hard, ambiguous, or had to be guessed at.", friction,
        List.of("what", "phase")));
    props.put("failures", objectArrayProp(
        "Tool calls that failed, including ones you later fixed yourself.", failure,
        List.of("tool", "error")));
    props.put("wastedCalls", objectArrayProp(
        "Calls that SUCCEEDED but got you nowhere: they returned no error, and the answer turned "
            + "out to be of no use for the task. They cost you just as much as a failure did, and "
            + "nothing in the record shows them unless you report them here.",
        wastedCall, List.of("tool", "expected", "whatHappened")));
    props.put("suggestions", objectArrayProp(
        "What would have made this task easy. This is the one field that looks forward rather "
            + "than back, so it is worth spending a sentence on each entry.",
        // `kind` is deliberately NOT required: an unclassified suggestion is stored with
        // kind:null, which is a different fact from a deliberate `other`.
        suggestion, List.of("what", "wouldHaveSaved")));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_FEEDBACK,
        "Report what this API was like to use: what confused you, what you could not find, what "
            + "you had to guess at, and what failed. This is wanted, it costs you nothing, and it "
            + "is never held against you — it is the only way the people who build this API find "
            + "out where it gets in the way. Send one consolidated report per task, ideally when "
            + "you finish or when you give up. Report failures you worked around too: a problem "
            + "you solved yourself is still a problem on our side.",
        buildObjectSchema(props, List.of("outcome", "summary", "achieved")));
  }

  // ── Widget tool (business widgets enum, gap G4) ───────────────────────

  /**
   * Canonical mapping of {@code etendo_widget} enum value → backing {@code dashboard}
   * spec entity name (whose {@code Java_Qualifier} resolves the {@code NeoHandler}).
   * Single source of truth shared with {@link McpToolRouter#handleWidget}.
   * Order is preserved for a stable enum/description listing.
   */
  static final Map<String, String> WIDGET_ENTITY_BY_NAME;
  /** Per-widget semantic descriptions surfaced to the agent in the enum. */
  static final Map<String, String> WIDGET_DESCRIPTION_BY_NAME;
  static {
    Map<String, String> entities = new LinkedHashMap<>();
    entities.put(McpConstants.WIDGET_KPIS, McpConstants.WIDGET_KPIS);
    entities.put(McpConstants.WIDGET_REVENUE_TREND, "trends");
    entities.put(McpConstants.WIDGET_PENDING_TASKS, McpConstants.WIDGET_PENDING_TASKS);
    entities.put(McpConstants.WIDGET_ACTIVITY, McpConstants.WIDGET_ACTIVITY);
    entities.put(McpConstants.WIDGET_RECENT_INVOICES, McpConstants.WIDGET_RECENT_INVOICES);
    entities.put(McpConstants.WIDGET_BEST_PRODUCTS, McpConstants.WIDGET_BEST_PRODUCTS);
    entities.put(McpConstants.WIDGET_BEST_SELLERS, McpConstants.WIDGET_BEST_SELLERS);
    entities.put(McpConstants.WIDGET_PENDING_AMOUNTS, McpConstants.WIDGET_PENDING_AMOUNTS);
    entities.put(McpConstants.WIDGET_TOP_CLIENTS, McpConstants.WIDGET_TOP_CLIENTS);
    WIDGET_ENTITY_BY_NAME = java.util.Collections.unmodifiableMap(entities);

    Map<String, String> desc = new LinkedHashMap<>();
    // ETP-5852 (M-24 / M-38): what each figure measures, so figures of different bases are not
    // compared. The card keys keep their historical names; the period is in response.period.
    desc.put(McpConstants.WIDGET_KPIS, "Headline KPI cards for the period in response.period "
        + "(default 'ytd', year to date — the keys revenueThisMonth/expensesThisMonth keep their "
        + "historical names but cover that period): revenue and expenses = NET amounts without "
        + "VAT (invoice totallines) of completed/closed sales and purchase invoices dated in the "
        + "period; netProfit = revenue - expenses; pendingInvoices = completed sales invoices "
        + "still unpaid, counted now whatever their date. trend = % change against the previous "
        + "period of the same length. With hasPrevious:false there is no previous period to "
        + "compare with and trend is 0 as a placeholder, not a change: do not report it.");
    desc.put(McpConstants.WIDGET_REVENUE_TREND, "Revenue series (parallel labels/values arrays): "
        + "NET sales without VAT of completed/closed invoices per bucket — months by default (the "
        + "rolling last 12 months), days for mtd/last30d, weeks for last90d.");
    desc.put(McpConstants.WIDGET_PENDING_TASKS, "Actionable pending tasks and alerts (overdue invoices, "
        + "pending receptions/shipments, collections/payments due).");
    desc.put(McpConstants.WIDGET_ACTIVITY, "Recent activity feed (invoices paid, documents posted, notes).");
    desc.put(McpConstants.WIDGET_RECENT_INVOICES, "The 5 most recent completed/closed sales "
        + "invoices (newest first); amount is the total WITH VAT.");
    // ETP-5852 (M-164): the only sales-by-product figures, as on the dashboard card.
    desc.put(McpConstants.WIDGET_BEST_PRODUCTS, "Top 10 products ranked by NET sales amount "
        + "without VAT (invoice line net amounts) of completed/closed sales invoices in the period "
        + "of params.range. Sales by product for a period are read here (amounts) and in "
        + "best-sellers (quantities); only the listed ranges exist, as on the dashboard, so a "
        + "period outside them (one month, a quarter) cannot be read: tell the user.");
    desc.put(McpConstants.WIDGET_BEST_SELLERS, "Top 10 products ranked by quantity sold "
        + "(invoiced quantity of completed/closed sales invoices) in the period of params.range, "
        + "with their unit of measure.");
    desc.put(McpConstants.WIDGET_PENDING_AMOUNTS, "Open receivables and payables NOW: count and "
        + "outstanding amount WITH VAT of completed sales/purchase invoices not fully paid. A "
        + "balance, not a period flow: not comparable with the net revenue of 'kpis'.");
    desc.put(McpConstants.WIDGET_TOP_CLIENTS, "Top 10 clients ranked by invoiced total WITH VAT "
        + "of completed/closed sales invoices.");
    WIDGET_DESCRIPTION_BY_NAME = java.util.Collections.unmodifiableMap(desc);
  }

  /**
   * Build the {@code etendo_widget} tool: a single enum tool wrapping the 9 handler-backed
   * business widgets (gap G4, ETP-4284). The enum value selects the widget; {@code params}
   * is a free-form object forwarded to the handler (e.g. {@code {"range": "last30d"}}).
   */
  private McpToolDefinition buildWidgetTool() {
    StringBuilder enumDesc = new StringBuilder(
        "Business widget to invoke. Available widgets:\n");
    for (Map.Entry<String, String> e : WIDGET_DESCRIPTION_BY_NAME.entrySet()) {
      enumDesc.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
    }
    // ETP-5852 (M-95): the card a user names when asking about "the dashboard".
    enumDesc.append("Dashboard card each widget feeds: kpis = Financial summary (Resumen "
        + "financiero); revenue-trend = Financial trend (Evolución financiera); pending-tasks = "
        + "Pending tasks (Tareas pendientes); recent-invoices = Recent sales (Ventas recientes); "
        + "best-products and best-sellers = Top selling products (Productos más vendidos), by "
        + "amount and by quantity; pending-amounts = Collections & payments (Cobros y pagos); "
        + "top-clients = Top clients (Clientes destacados). activity has no card of its own, and "
        + "Quick actions (Accesos rápidos) are links, not data, so no widget serves them.\n");

    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_WIDGET,
        enumProp(enumDesc.toString(), new ArrayList<>(WIDGET_ENTITY_BY_NAME.keySet())));
    props.put(McpConstants.PARAM_PARAMS, objectProp(
        "Optional parameters forwarded to the widget. Most widgets accept "
            + "'range' to scope the period. Valid values: 'ytd', 'mtd', 'last30d', "
            + "'last90d', 'lastYear'. Omit for the widget's default window "
            + "(for 'kpis', an omitted range means 'ytd'; for 'revenue-trend' it means "
            + "the rolling last 12 months, bucketed by month); any other value is refused with "
            + "a 422. Every answer carries response.period: the effective range with its from/to "
            + "dates (or the widget's default window) and asOf, the moment it was computed."));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_WIDGET,
        "Get pre-computed business analytics from an Etendo dashboard widget "
            + "(KPIs, revenue trend, pending tasks, activity, top clients, best sellers/products, "
            + "recent invoices, pending amounts). Returns the widget's JSON payload "
            + "{response:{data,count}}. Use this for business analysis instead of etendo_list; "
            + "these widgets aggregate data that has no single CRUD entity.",
        buildObjectSchema(props, List.of(McpConstants.PARAM_WIDGET)));
  }

  // ── Global vector search tool ─────────────────────────────────────────

  /** Build the read-only DB Extended semantic-search tool. */
  McpToolDefinition buildVectorSearchTool() {
    return buildVectorSearchTool(
        NeoVectorSearchEndpoint.configuredTargetKeys().orElse(List.of()));
  }

  /**
   * IMP-41: {@code targets} carries the configured keys as an enum instead of being a free string
   * array. Nothing on the MCP surface used to name a single legal key — not the input schema, not
   * {@code etendo_discover} — so guessing was the only strategy available, and a wrong guess came back
   * as {@code 403 "Access denied"}, which reads as "not for you" rather than "not that name".
   *
   * <p>An empty catalogue deliberately keeps the free-form array: an empty {@code enum} makes the
   * parameter impossible to satisfy, which would turn "nothing is configured" into a tool no model
   * can call at all. The endpoint answers that case honestly on its own.</p>
   *
   * @param targetKeys the configured search-target keys; {@code null} or empty leaves the
   *                   parameter free-form
   * @return the tool definition
   */
  McpToolDefinition buildVectorSearchTool(List<String> targetKeys) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_QUERY,
        stringProp("Natural-language search query"));
    props.put("targets", targetKeys == null || targetKeys.isEmpty()
        ? stringArrayProp("DB Extended search-target keys to query. No search target is configured "
            + "on this instance, so semantic search is unavailable here.")
        : stringEnumArrayProp("Optional. Which indexes to search. These are the only valid values — "
            + "a key that is not listed here does not exist, however plausible it looks. Each one "
            + "is the name of the spec that owns it, so a match found in target X is read with "
            + "etendo_get(spec:X, entity:<that spec's primaryEntity, from etendo_discover>, "
            + "id:<match.id>) — the match itself carries no pointer to where its record lives. "
            + "Omit it to search every "
            + "index you have access to, which is the right choice when you do not already know "
            + "where the answer lives.", targetKeys));
    props.put("topK", numericProp(TYPE_INTEGER, "Maximum results (default 10, maximum 50)"));
    props.put("minScore", numericProp("number", "Minimum similarity score from 0 to 1 (default 0.60)"));
    props.put("maxScore", numericProp("number", "Maximum similarity score from 0 to 1 (default 1.0)"));
    return new McpToolDefinition(
        McpConstants.TOOL_NEO_VECTOR_SEARCH,
        "Search indexed business records by semantic similarity using DB Extended. "
            + "Only 'query' is required: with no 'targets' it searches every index the current role "
            + "can read. Targets are authorized against their physical source entity for the "
            + "current role. Scores are ranking signals, not confidence probabilities.",
        buildObjectSchema(props, List.of(McpConstants.PARAM_QUERY)));
  }

  // ── CRUD tools (registered once with spec enum) ───────────────────────

  /**
   * The argument names a fixed-shape tool declares, for the unknown-argument guard (IMP-40).
   *
   * <p>Derived from the very builders that produce the published schema, never from a
   * hand-maintained list: a second copy of an argument set is how the guard and the contract drift
   * apart, and a guard that disagrees with the schema is worse than none — it would refuse calls
   * the tool documents.</p>
   *
   * <p>The spec enum is irrelevant here (only the property KEYS are read), so the builders are
   * invoked with an empty spec list. Tools whose argument set is spec-dependent — the process and
   * report tools, whose parameters come from the AD process definition — are deliberately absent
   * and are therefore not guarded.</p>
   *
   * @param toolName the tool being called
   * @return the declared argument names, or {@link Optional#empty()} when this tool is not guarded.
   *     Empty and absent are different answers here: an empty set would mean "this tool declares no
   *     arguments, so reject every one", which is the opposite of "do not check this tool".
   */
  static Optional<Set<String>> declaredArgumentNames(String toolName) {
    ToolRegistry registry = new ToolRegistry();
    McpToolDefinition definition;
    switch (toolName) {
      case McpConstants.TOOL_NEO_LIST: definition = registry.buildListTool(List.of(), null); break;
      case McpConstants.TOOL_NEO_GET: definition = registry.buildGetTool(List.of()); break;
      case McpConstants.TOOL_NEO_CREATE: definition = registry.buildCreateTool(List.of()); break;
      case McpConstants.TOOL_NEO_UPDATE: definition = registry.buildUpdateTool(List.of()); break;
      case McpConstants.TOOL_NEO_DELETE: definition = registry.buildDeleteTool(List.of()); break;
      case McpConstants.TOOL_NEO_SELECTORS: definition = registry.buildSelectorsTool(List.of()); break;
      case McpConstants.TOOL_NEO_DEFAULTS: definition = registry.buildDefaultsTool(List.of(), null); break;
      case McpConstants.TOOL_NEO_SCHEMA: definition = registry.buildSchemaTool(List.of()); break;
      case McpConstants.TOOL_NEO_DISCOVER: definition = registry.buildDiscoverTool(); break;
      // ETP-5669: a top-level docAction was silently ignored and the call fell through to the
      // generic executor; the action's own parameters belong under 'parameters'.
      case McpConstants.TOOL_NEO_ACTION: definition = registry.buildActionTool(List.of()); break;
      case McpConstants.TOOL_NEO_ACTION_BATCH: definition = McpActionBatch.definition(List.of()); break;
      case McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD:
        definition = McpAttachmentToolDefinitions.requestAttachmentUpload(List.of()); break;
      case McpConstants.TOOL_NEO_LIST_ATTACHMENTS:
        definition = McpAttachmentToolDefinitions.listAttachments(List.of()); break;
      case McpConstants.TOOL_NEO_DELETE_ATTACHMENT:
        definition = McpAttachmentToolDefinitions.deleteAttachment(List.of()); break;
      case McpConstants.TOOL_NEO_GET_DOCUMENT_PDF:
        definition = McpDocumentPdfToolDefinition.definition(List.of()); break;
      case McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL:
        definition = McpDocumentEmailToolDefinition.definition(List.of()); break;
      case McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD:
        definition = McpOcrToolDefinitions.requestUpload(List.of()); break;
      case McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT:
        definition = McpOcrToolDefinitions.extract(List.of()); break;
      default: return Optional.empty();
    }
    Object props = definition.getInputSchema().get(McpConstants.KEY_PROPERTIES);
    if (!(props instanceof Map)) {
      return Optional.empty();
    }
    Set<String> names = new java.util.LinkedHashSet<>();
    for (Object key : ((Map<?, ?>) props).keySet()) {
      names.add(String.valueOf(key));
    }
    return Optional.of(names);
  }

  private McpToolDefinition buildListTool(List<String> specNames, String namedFilterSummary) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name (use etendo_discover to find available specs)", specNames));
    props.put(McpConstants.PARAM_ENTITY,
      stringProp(McpConstants.LABEL_ENTITY_NAME_WITH_EXAMPLE));
    props.put("filters", objectProp(
        "Filter criteria. Five shapes, combinable: (1) exact match {\"column\": value}; "
            + "(2) range operators {\"column\": {\"gt\"|\"gte\"|\"lt\"|\"lte\": value}} or "
            + "{\"column\": {\"between\": [from, to]}} (dates as \"YYYY-MM-DD\"); "
            + "(3) partial text, case-insensitive, text fields only "
            + "{\"column\": {\"contains\": \"text\"}}; "
            + "(4) any of several exact values {\"column\": {\"in\": [v1, v2]}} (ids for a "
            + "reference field); "
            + "(5) named business filter {\"status\": \"<name>\"} — a hand-authored business "
            + "state; an unknown name returns the valid list with what each one means."
            + (namedFilterSummary == null ? ""
                : " Configured named filters (spec/entity: name (meaning)):\n" + namedFilterSummary)));
    // IMP-40: etendo_discover already advertises "parentRequiredFor":["list",...] on every child
    // entity, and until now this tool had no argument that could satisfy it — so the only way to
    // scope a list to one parent was a filter on a field name the agent had to work out itself.
    props.put(McpConstants.PARAM_PARENT_ID, stringProp(
        "Parent record ID — REQUIRED for a child/line entity (e.g. the order ID when listing that "
            + "order's lines). A child's records are read through their parent. Omit it on a child "
            + "entity and the call is refused, naming the parent entity to fetch first. Not needed "
            + "for a spec's top-level entity, and equivalent to filtering on the parent field "
            + "yourself."));
    props.put(McpConstants.PARAM_ALL_PARENTS, booleanProp(
        "Optional, default false. true searches a child entity across EVERY parent instead of "
            + "one (e.g. the sales order lines of a product, whatever the order) — only on the "
            + "entities whose etendo_schema shows allParentsAllowed:true; refused elsewhere and "
            + "together with parentId. The rows stay within this spec (sales-order lines are lines "
            + "of sales orders only) and each names its parent. Use parentId to read one parent."));
    props.put("limit", numericProp(TYPE_INTEGER, "Maximum number of records to return (default 100)"));
    props.put("offset", numericProp(TYPE_INTEGER, "Number of records to skip for pagination"));
    props.put("orderBy", stringProp("Column name to sort by, prefix with '-' for descending"));
    props.put(McpFieldProjection.PARAM_FIELDS, stringArrayProp(
        "Optional projection: return only these field names per row (e.g. "
            + "[\"documentNo\",\"businessPartner\",\"grandTotalAmount\"]). A FK's $_identifier "
            + "label is included automatically. Names this entity cannot return come back in "
            + "\"unknownFields\" alongside \"data\" — check it if a field you expected is missing, "
            + "including when \"data\" is empty. Omit to return every column."));
    props.put(McpFieldProjection.PARAM_VIEW, enumProp(
        "Optional curated view. \"summary\" returns only the spec's business-critical fields — a "
            + "compact row for compliance-heavy specs. Ignored when `fields` is given; omit for the "
            + "full row.", List.of(McpFieldProjection.VIEW_SUMMARY)));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_LIST,
        "List records from an Etendo API spec. "
            + "Supports filtering (exact match, range operators, named document status), "
            + "pagination, sorting, and field projection (`fields` / view:\"summary\"). "
            + "`totalRows` is the number of records matching the filters, whatever page you ask for. "
            + LIST_CODES_NOTE,
          buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY)));
  }

  /**
   * ETP-5852 (M-184): a list field (documentStatus, …) is read as its code; the label the user
   * sees is in the schema, which agents did not know.
   */
  private static final String LIST_CODES_NOTE = "A field with a fixed list of values "
      + "(documentStatus, paymentStatus, ...) comes as its code (CO, DR, ...); etendo_schema lists "
      + "each code with its meaning under that field's options (CO = Booked, VO = Voided) — tell "
      + "the user the meaning, not the code.";

  private McpToolDefinition buildGetTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to retrieve"));
    props.put(McpFieldProjection.PARAM_FIELDS, stringArrayProp(
        "Optional projection: return only these field names (e.g. "
            + "[\"documentNo\",\"grandTotalAmount\"]). A FK's $_identifier label is included "
            + "automatically. Names this entity cannot return come back in \"unknownFields\" "
            + "alongside \"data\" — check it if a field you expected is missing. Omit to return "
            + "every column."));
    props.put(McpFieldProjection.PARAM_VIEW, enumProp(
        "Optional curated view. \"summary\" returns only the spec's business-critical fields. "
            + "Ignored when `fields` is given; omit for the full record.",
        List.of(McpFieldProjection.VIEW_SUMMARY)));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_GET,
        "Get a single record by ID from an Etendo API spec. The record comes back as an object "
            + "in `data` ({\"data\": {…}}), not a list. Supports field projection "
            + "(`fields` / view:\"summary\"). " + LIST_CODES_NOTE + " "
            + McpConstants.RECORD_URL_NOTE,
          buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY, "id")));
  }

  private McpToolDefinition buildCreateTool(List<String> specNames) {
    // IMP-18: the write verbs used to drop an unrecognised key in silence, so a create carrying a
    // misspelt field returned 201 and no later read could contradict it. They now name it in
    // `unknownFields`, the way etendo_schema/etendo_list/etendo_get already did - and the description says
    // so, because a warning nobody is told to look for is only marginally better than silence.
    String unknownFieldsNote = "A name this entity does not recognise comes back in "
        + "\"unknownFields\" on the response - check it if a value you sent is not on the record, "
        + "because the write still succeeds without it. ";
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put(McpConstants.PARAM_FIELDS, objectProp("Field values for the new record"));
    // IMP-40: parentId was accepted ONLY inside `fields` and was declared nowhere. Every other
    // parent-aware tool (etendo_defaults, etendo_list, etendo_get) takes it as a top-level argument and
    // says so at length, so an agent learns that shape from three tools and applies it to this
    // one — where it was silently discarded. Nothing errored: the parent link simply never
    // arrived, so parent-derived values (a line's order date, its price-list version, its running
    // line number) could not resolve, and the create was refused for "missing" fields the server
    // was supposed to derive. Declared here so the contract is uniform; `fields.parentId` still
    // works, and this argument wins when both are present.
    props.put(McpConstants.PARAM_PARENT_ID, stringProp(
        "Parent record ID — REQUIRED when creating a child/line record (e.g. the order ID when "
            + "creating an order line). It links the new record to its parent AND is what lets the "
            + "server derive the parent-dependent values for you (the line's date, its price-list "
            + "version, its line number). Omit it on a child entity and those values cannot be "
            + "resolved, so the create is refused for fields you were never asked to supply. "
            + "Not needed for a spec's top-level entity."));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_CREATE,
        "Create a new record in an Etendo API spec. The created record comes back as an object in "
            + "`data` ({\"data\": {…}}), like etendo_get. "
            + "Creating a child/line record? Pass parentId with the parent's id — without it the "
            + "server cannot derive the values it inherits from the parent. "
            + "Recommended: call etendo_defaults first to get the initial/base set of field values "
            + "for this record type, then build the fields object by overriding only the values "
            + "the user actually wants to change on top of that base — instead of asking the "
            + "user for every field or guessing values that already have a sensible default "
            + "(document number, dates, prices, etc.). Send back only what the user chose or what "
            + "you need on the record: a value you send is deliberate, and it is protected from "
            + "the callouts that would otherwise derive it from this record's real context — a "
            + "generic default echoed back can pin the wrong one (etendo_defaults resolves before "
            + "there is a business partner). Any field where that happened comes back in "
            + "\"supersededDefaults\" with the value the callout had resolved. "
            + "Dates must be ISO-8601: 'YYYY-MM-DD' for date fields and "
            + "'YYYY-MM-DDTHH:MM:SS' for datetime fields. No other format is supported. "
            + unknownFieldsNote
            + McpConstants.RECORD_URL_NOTE,
        buildObjectSchema(props,
          List.of("spec", McpConstants.PARAM_ENTITY, McpConstants.PARAM_FIELDS)));
  }

  private McpToolDefinition buildUpdateTool(List<String> specNames) {
    // IMP-18: the write verbs used to drop an unrecognised key in silence, so a create carrying a
    // misspelt field returned 201 and no later read could contradict it. They now name it in
    // `unknownFields`, the way etendo_schema/etendo_list/etendo_get already did - and the description says
    // so, because a warning nobody is told to look for is only marginally better than silence.
    String unknownFieldsNote = "A name this entity does not recognise comes back in "
        + "\"unknownFields\" on the response - check it if a value you sent is not on the record, "
        + "because the write still succeeds without it. ";
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to update"));
    props.put(McpConstants.PARAM_FIELDS, objectProp("Field values to update"));
    // ETP-5073 / DOC-04: required, and described in terms of where to obtain it. The schema alone
    // would only tell an agent that something is missing; naming etendo_get as the source is what
    // lets it recover on the first retry instead of guessing a timestamp (which cannot work — any
    // value other than the one actually stored is rejected as a conflict).
    props.put(McpConstants.PARAM_UPDATED, stringProp(
        "The record's 'updated' value exactly as etendo_get returned it. Required: it is how the "
            + "server verifies nobody else changed the record since you read it. Copy it verbatim "
            + "— do not reformat, round or invent it. If you do not have it, call etendo_get first."));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_UPDATE,
        "Update an existing record in an Etendo API spec. The updated record comes back as an object "
            + "in `data` ({\"data\": {…}}), like etendo_get. "
            + "Read the record with etendo_get first: its 'updated' value is a required argument and "
            + "guards against overwriting somebody else's concurrent edit. A 409 with "
            + "error 'stale_record' means the record changed since that read — re-read it, reapply "
            + "your changes and retry; re-sending the same payload will fail identically. "
            + "Dates must be ISO-8601: 'YYYY-MM-DD' for date fields and "
            + "'YYYY-MM-DDTHH:MM:SS' for datetime fields. No other format is supported. "
            + unknownFieldsNote,
        buildObjectSchema(props,
          List.of("spec", McpConstants.PARAM_ENTITY, "id", McpConstants.PARAM_FIELDS,
            McpConstants.PARAM_UPDATED)));
  }

  private McpToolDefinition buildDeleteTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to delete"));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_DELETE,
        "Delete a record from an Etendo API spec.",
          buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY, "id")));
  }

  private McpToolDefinition buildSelectorsTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put(McpConstants.PARAM_COLUMN,
      stringProp("Field name (e.g. 'businessPartner') or DB column name (e.g. 'C_BPartner_ID') to get selector values for"));
    props.put(McpConstants.PARAM_FIELD,
        stringProp("Compatibility-only field name alias; use column for selector lookup"));
    props.put(McpConstants.PARAM_QUERY, stringProp(
        "Optional search text, matched case-insensitively against the item label, whole or "
            + "as a substring. Omit it to list unfiltered."));
    props.put(McpConstants.PARAM_RECORD_CONTEXT, objectProp(
        "Optional context from the current record to resolve dependent selectors. "
            + "For example: {\"businessPartner\": \"<id>\"} for partnerAddress, "
            + "or {\"invoiceDate\": \"2026-05-12\"} for line tax selectors."));
    props.put(McpConstants.PARAM_PARENT_CONTEXT, objectProp(
        "Optional parent/header record context for child selectors. "
            + "For example: {\"businessPartner\": \"<id>\", \"orderDate\": \"2026-05-12\", "
            + "\"priceList\": \"<id>\"} when resolving line selectors."));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_SELECTORS,
        "Get foreign-key selector values for a column. "
            + "Use this to discover valid values for FK reference fields. "
            + "Pass recordContext when the selector depends on other field values "
            + "(e.g. partnerAddress requires businessPartner). "
            + "Pass parentContext for line selectors that depend on header values "
            + "(e.g. tax requires orderDate/invoiceDate and priceList). "
            + "Returns {items:[{id,label}], totalCount, hasMore}, capped at 50 items with no "
            + "paging: when hasMore is true, narrow with query.",
        buildObjectSchema(props,
          List.of("spec", McpConstants.PARAM_ENTITY, McpConstants.PARAM_COLUMN)));
  }

  private McpToolDefinition buildDefaultsTool(List<String> specNames, String requiredContextSummary) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME_WITH_EXAMPLE));
    props.put(McpConstants.PARAM_PARENT_ID, stringProp(
        "Parent record ID — REQUIRED for a child/line entity (e.g. the order ID when getting "
            + "line defaults, or the inventory header ID when getting inventory-line defaults). "
            + "Without it, fields whose default depends on the parent record (a storage bin scoped "
            + "to the parent's warehouse, a price-list version, a running line number) are silently "
            + "left out of the result rather than erroring — they simply do not appear, so a missing "
            + "value here is easy to miss unless you know to expect it. Pass the parent's id "
            + "whenever entity is not the spec's top-level entity."));
    props.put(McpConstants.PARAM_ASSET_ID, stringProp(
        "Optional asset ID for computing dynamic defaults that depend on a specific asset "
            + "(e.g. the amortization header name derived from the asset name and start date)"));
    props.put(McpConstants.PARAM_RECORD_CONTEXT, objectProp(
        "Optional current field values of the record you are drafting, for defaults that depend "
            + "on them: {\"<field>\": <value>}, ids for reference fields. Each one is applied to "
            + "the record before the dependent defaults are derived, as when the field is set in "
            + "the UI — e.g. {\"businessPartner\": \"<id>\"} on a document header returns that "
            + "partner's payment terms, payment method and price list; "
            + "{\"productType\": \"S\"} on product/product returns the defaults of a Service. "
            + "Scalar entries only (string, number, boolean; at most 32 entries of 256 "
            + "characters); a name that is not a field of the entity is ignored. parentId, "
            + "assetId, view, spec and entity cannot be overridden by it."
            + (requiredContextSummary == null ? ""
                : " REQUIRED context per entity — send these or the defaults that depend on them "
                    + "come back generic (spec/entity: fields — why):\n" + requiredContextSummary)));
    props.put(McpDefaultsView.PARAM_VIEW, enumProp(
        "Optional response shape. Omit (or \"full\") for the historical flat map of every default. "
            + "\"grouped\" splits the result into `confirm` (writable fields you should review or "
            + "override before etendo_create) and `systemManaged` (compliance/audit flags the server "
            + "owns — leave them alone). \"minimal\" returns only the `confirm` block. Use "
            + "grouped/minimal on compliance-heavy specs (invoices, orders) to avoid wading "
            + "through ~65 fields when only ~5 matter. In both grouped views a field the server "
            + "knows but could not resolve a value for is listed in `metadata.unresolvedFields` "
            + "instead of appearing in `confirm` with an empty value — those are the fields you "
            + "must supply yourself.",
        List.of(McpDefaultsView.VIEW_FULL, McpDefaultsView.VIEW_GROUPED,
            McpDefaultsView.VIEW_MINIMAL)));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_DEFAULTS,
        "Get the initial/base set of field values for a new record — field types, which fields "
            + "are required vs optional, and computed/system defaults (document number, dates, "
            + "prices, etc.). Recommended: call this BEFORE etendo_create, then use its result as "
            + "the starting point and only override the fields the user actually wants to set — "
            + "instead of asking the user for every value from scratch. etendo_create only auto-fills "
            + "what it needs to satisfy a NOT-NULL column or a computed value (sequence numbers, "
            + "dates, currency, ...) — an optional field this call resolved (a price list, payment "
            + "terms, a financial account, ...) is NOT copied into the record unless you send it "
            + "explicitly in fields, even though it showed a value here. Copy across the fields "
            + "from this result you want on the record; do not assume omitting one lets etendo_create "
            + "fill it in the same way. Defaults that depend on another field of the record (the "
            + "payment terms of a document follow its business partner) are only right when that "
            + "field is sent in recordContext — the entities that need it are listed under "
            + "recordContext. Without it they are generic. A value you send to etendo_create is "
            + "treated as deliberate and is protected from the callout that would have corrected "
            + "it, so re-send a value from here only when it came from the right context or the "
            + "user chose it — not as a blanket echo. etendo_create reports anything your value "
            + "displaced in \"supersededDefaults\"; read it. "
            + "When entity is a child/line tab (not the spec's top-level "
            + "entity), pass parentId with the parent record's id — omitting it does not resolve "
            + "parent-dependent fields (a storage bin scoped to the parent's warehouse, a "
            + "price-list version, a running line number); they are silently absent rather than "
            + "erroring. When a default depends on another field's current value (for example "
            + "the product's tax category follows its productType), pass those current values "
            + "in recordContext to get the dependent default. Pass view:\"minimal\" (or "
            + "\"grouped\") to collapse server-managed compliance flags and focus on the "
            + "fields you actually confirm. "
            + "Date values come back ISO-8601 ('YYYY-MM-DD', or 'YYYY-MM-DDTHH:MM:SS' for "
            + "datetime fields) and can be passed straight back to etendo_create unchanged.",
        buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY)));
  }

  // ── Batch tool (cross-spec, sequential — not atomic, see IMP-23) ──────

  /**
   * Build the {@code etendo_batch} tool definition. Unlike the per-spec CRUD tools,
   * each operation in the batch carries its own {@code spec}, so this tool is
   * registered once with no top-level enum.
   *
   * <p>The description states plainly that the tool is not atomic (IMP-23). It used to promise
   * all-or-nothing, which is worse than saying nothing: an agent that believes a failed batch
   * wrote nothing has no reason to look for the records it left behind, and one such orphan
   * survived five days in this project's own benchmark evidence.</p>
   */
  McpToolDefinition buildBatchTool() {
    Map<String, Object> opProps = new LinkedHashMap<>();
    Map<String, Object> idProp = new LinkedHashMap<>();
    idProp.put("type", McpConstants.TYPE_STRING);
    idProp.put(McpConstants.KEY_DESCRIPTION,
        "Local op identifier, unique within this batch. Used as the target of $ref:<id> "
            + "and parentRef.");
    opProps.put("id", idProp);

    Map<String, Object> specProp = new LinkedHashMap<>();
    specProp.put("type", McpConstants.TYPE_STRING);
    specProp.put(McpConstants.KEY_DESCRIPTION,
        "Spec name (e.g. 'sales-order'). Each op may target a different spec.");
    opProps.put("spec", specProp);

    Map<String, Object> entityProp = new LinkedHashMap<>();
    entityProp.put("type", McpConstants.TYPE_STRING);
    entityProp.put(McpConstants.KEY_DESCRIPTION,
        "Entity name within the spec (e.g. 'header', 'lines'; use the exact names etendo_discover lists).");
    opProps.put(McpConstants.PARAM_ENTITY, entityProp);

    Map<String, Object> parentRefProp = new LinkedHashMap<>();
    parentRefProp.put("type", McpConstants.TYPE_STRING);
    parentRefProp.put(McpConstants.KEY_DESCRIPTION,
        "Optional id of an earlier op whose recordId becomes this op's parent FK.");
    opProps.put("parentRef", parentRefProp);

    Map<String, Object> bodyProp = new LinkedHashMap<>();
    bodyProp.put("type", McpConstants.TYPE_OBJECT);
    bodyProp.put(McpConstants.KEY_DESCRIPTION,
        "Field values for the new record, in the same format etendo_create accepts: a foreign key "
            + "may be a record id (32-char hex or a legacy numeric one such as '102') or a display "
            + "name resolved server-side (e.g. currency:'EUR'). String values of the form "
            + "'$ref:<opId>' are replaced with the resolved recordId of an earlier op.");
    opProps.put("body", bodyProp);

    Map<String, Object> opItem = new LinkedHashMap<>();
    opItem.put("type", McpConstants.TYPE_OBJECT);
    opItem.put(McpConstants.KEY_PROPERTIES, opProps);
    opItem.put(KEY_REQUIRED, List.of("id", "spec", McpConstants.PARAM_ENTITY));

    Map<String, Object> operationsProp = new LinkedHashMap<>();
    operationsProp.put("type", "array");
    operationsProp.put(McpConstants.KEY_DESCRIPTION,
        "Ordered list of create operations, run in the given order.");
    operationsProp.put("items", opItem);

    Map<String, Object> props = new LinkedHashMap<>();
    props.put("operations", operationsProp);
    // ETP-5676: same name and meaning as the REST /sws/neo/batch body option.
    props.put(BatchService.PARAM_ONLY_RETURN_IDS, booleanProp(
        "Optional, default false. true = a bulk-write hint: each create skips decorating the record "
            + "it would echo back (identifier enrichment, display flags), which only the id is read "
            + "from anyway. The response is the same either way."));
    props.put(BatchService.PARAM_SKIP_REDUNDANT_CALLOUTS, booleanProp(
        "Optional, default false. true = a bulk-write hint for many similar creates: a field-change "
            + "callout is skipped once an earlier operation of this batch, with the same field and "
            + "value, showed that every value it sets is overwritten by fields you send yourself. "
            + "The response is the same either way; the server may ignore it."));

    // ETP-5677: the asynchronous mode, next to the synchronous one.
    props.put(BatchJobService.PARAM_ASYNC, booleanProp(
        "Optional, default TRUE. By default the call does not wait: it returns at once with "
            + "{jobId, state:'QUEUED', totalRows, totalOps} and a background job writes the "
            + "operations, so any volume is safe (a synchronous call can time out although the "
            + "server keeps writing). Follow the job with etendo_job_status. The async job commits "
            + "ROW BY ROW (a row = an op plus everything it references through parentRef/$ref), "
            + "not as one atomic unit: a rejected row does not undo the others, and the status "
            + "reports for every row whether it committed (with its recordIds) or why not. Resend "
            + "only rows reported committed:false AND safeToResend:true. Send async:false to run "
            + "the whole batch as ONE atomic transaction and wait for its answer: only for small "
            + "batches that must be all-or-nothing."));

    return new McpToolDefinition(
        "etendo_batch",
        "Run a sequence of cross-spec create operations in order. By default it is asynchronous: it "
            + "returns a jobId at once and the job commits row by row (see 'async' and "
            + "etendo_job_status). With async:false it runs atomically instead: a failure rolls "
            + "the whole batch back, so retry the whole batch after fixing the reported operation. "
            + "One exception, and the response states it explicitly: if an operation triggers an "
            + "Etendo process, that process commits internally and cannot be rolled back — the "
            + "failure response then carries 'atomic':false plus 'persisted', listing the "
            + "recordIds that survived, which you must delete or reuse before retrying (a plain "
            + "retry duplicates them). Always check 'atomic' before retrying. Each op carries its own "
            + "'spec' and 'entity', so a single batch can mix windows (e.g. create a "
            + "Business Partner, a Location, then a Purchase Invoice referencing both). "
            + "Use 'parentRef':<earlierOpId> to set the parent FK on a child-tab op, "
            + "and string values of the form '$ref:<earlierOpId>' anywhere in 'body' "
            + "to substitute the resolved recordId of an earlier op. Typically call "
            + "etendo_list / etendo_selectors first to look up existing records and only "
            + "include create ops for what is genuinely new. "
            + "With async:false it returns {committed:true, operations:[{id,ok:true,recordId}]} on success "
            + "or {committed:false, atomic:true, failedAt:{id,index}, persisted:[], hint, "
            + "error:{status,error,detail,seeAlso}} on failure, where 'error' is a stable code "
            + "(validation_error, not_found, method_not_allowed, server_error) naming what to fix "
            + "and 'persisted' is non-empty only in the process-commit case above.",
        buildObjectSchema(props, List.of("operations")));
  }

  // ── Async batch job tools (ETP-5677) ───────────────────────────────────

  private static Map<String, Object> jobIdSchema(String description) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("jobId", stringProp(description));
    return buildObjectSchema(props, List.of("jobId"));
  }

  McpToolDefinition buildJobStatusTool() {
    return new McpToolDefinition(
        McpConstants.TOOL_NEO_JOB_STATUS,
        "Status of an asynchronous batch job started with etendo_batch {async:true}. Returns "
            + "{jobId, state, done, totalRows, processedRows, committedRows, failedRows, rows:[{index, "
            + "ids, committed, operations:[{id,ok,recordId}] | failedAt, error, safeToResend}], hint?}. state is "
            + "QUEUED, RUNNING, SUCCEEDED (every row committed), PARTIAL (some rows rejected), "
            + "FAILED, CANCELLED or INTERRUPTED (the server running it stopped: rows listed are "
            + "final, rows not listed may or may not exist — look them up before resending). Poll "
            + "until done is true (every few seconds). A committed row exists. Resend only rows "
            + "with committed:false AND safeToResend:true; a row with safeToResend:false (atomic:false, "
            + "or no outcome) may have left records — see its 'persisted' and look them up first. "
            + "Only the user who started the job can read it.",
        jobIdSchema("The jobId returned by etendo_batch with async:true."));
  }

  McpToolDefinition buildJobCancelTool() {
    return new McpToolDefinition(
        McpConstants.TOOL_NEO_JOB_CANCEL,
        "Ask an asynchronous batch job to stop. It stops before its next row: rows already "
            + "committed stay committed and are listed by etendo_job_status; rows not started are "
            + "never written. Answers with the job's status. Only the user who started the job "
            + "can cancel it; a finished job cannot be cancelled.",
        jobIdSchema("The jobId returned by etendo_batch with async:true."));
  }

  // ── Schema tool ────────────────────────────────────────────────────────

  private McpToolDefinition buildSchemaTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name (use etendo_discover to find available specs)", specNames));
    props.put(McpConstants.PARAM_ENTITY,
      stringProp("Entity name within the spec (e.g. 'header', 'lines'; use the exact names etendo_discover lists)"));
    // IMP-44: REQUIRED, and "full" is now a value you ask for rather than what you get for not
    // choosing. The full dump is 39.5 kB on sales-order/header against 5.4 kB for "create", and
    // the caller used to be told about the cheaper projection by a hint at the bottom of the
    // response it had already paid for. Recommending "create" here is not new — that wording has
    // shipped since 2026-08-06 and three independent blind agents still took the full route, so
    // the lever is the argument, not more prose.
    props.put(McpActionsView.PARAM_VIEW, enumProp(
        "REQUIRED — which projection you want. \"create\": ONLY the fields you may send to "
            + "etendo_create/etendo_update, split into required/optional. This is the one you want "
            + "before a write, and it is by far the smallest (~5 kB on sales-order/header). "
            + "\"actions\": only the buttons/processes ({name, label, action, processName, "
            + "processId, ...}) — use it when you need to know what can be triggered on this "
            + "entity, not every column. Fire only the ones carrying invokeVia:\"etendo_action\"; the "
            + "rest report invokable:false plus a notInvokableReason, and \"invokableCount\" next "
            + "to \"actionCount\" tells you the split up front. \"full\": every field, including "
            + "read-only and system ones — ~40 kB on sales-order/header and more on "
            + "compliance-heavy windows, where it may not fit your context. Ask for it when you "
            + "are reading, filtering or projecting, not when you are about to write.",
        List.of(McpSchemaCreateView.VIEW_CREATE, McpActionsView.VIEW_ACTIONS,
            McpSchemaCreateView.VIEW_FULL)));
    props.put(McpConstants.PARAM_ID, stringProp(
        "Optional, only for view:\"actions\": the id of ONE record. The catalogue then also says, "
            + "per action, whether it applies to that record RIGHT NOW (availableNow true/false), "
            + "why not (unavailableReason) and, for documentAction, which values apply "
            + "(availableValuesNow). Without it you get each action's static availableWhen / "
            + "valuesByStatus only. Advice only: it never blocks a call. Ask for it before "
            + "offering the user an action on a document."));
    props.put(McpSchemaCreateView.PARAM_FIELDS, stringArrayProp(
        "Optional whitelist of field names to describe (e.g. [\"businessPartner\",\"invoiceDate\"]). "
            + "Returns only those descriptors instead of all of them. Names that match nothing come "
            + "back in \"unknownFields\" — check it if a field you expected is missing. Applies to "
            + "view:\"full\" only; ignored under view:\"create\" and view:\"actions\", which "
            + "already define their own projection."));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_SCHEMA,
        "Get the field schema for an entity: field names, types, required flag, "
            + "read-only flag, default values, visibility (editable/readOnly/system/discarded), "
            + "and which fields have FK selectors. Call this BEFORE etendo_create to know which "
            + "fields exist and which are required. \"view\" is REQUIRED and decides the size of "
            + "the answer: use view:\"create\" before a write — only the fields you may send, "
            + "already split into required/optional, and several times smaller than the full "
            + "dump. Only fields "
            + "with userRequired=true need to be provided: a field that is mandatory but that the "
            + "server can already resolve a value for — from an AD default, a session preference, "
            + "the business partner's configuration, or a callout — is filled by the server, so it "
            + "is NOT userRequired. In view:\"create\" those appear under optional with "
            + "serverDefaulted=true. In the full dump userRequired is a static approximation — it "
            + "reads the column's own default only, so it over-reports fields the server resolves "
            + "from elsewhere; view:\"create\" cross-checks against the real defaults and is the "
            + "authoritative answer to \"must I ask the user for this?\". System fields are "
            + "auto-derived by Etendo callouts. Pass view:\"actions\" for the callable "
            + "buttons/processes instead, and view:\"full\" when you are reading or filtering "
            + "and genuinely need every column.",
        buildObjectSchema(props,
            List.of("spec", "entity", McpActionsView.PARAM_VIEW)));
  }

  // ── Action tool ────────────────────────────────────────────────────────

  private McpToolDefinition buildActionTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to act upon"));
    props.put("action", stringProp(
        "The 'action' value of the button from etendo_schema view:\"actions\" (its field name, "
            + "e.g. 'documentAction'; the DB column name, e.g. 'DocAction', is also accepted)"));
    props.put(McpConstants.PARAM_PARAMETERS, objectProp(
        "Process parameters. For a list-backed button, put the chosen value under the key "
            + "named by the field's 'actionParameter' — e.g. {\"docAction\": \"CO\"}"));

    return new McpToolDefinition(
        "etendo_action",
        "Fire a type:button action on a record and return the process result. Every result carries "
            + "`success` (boolean) and `message` (the outcome, translated); other keys depend on the "
            + "action. "
            + "Call etendo_schema with view:\"actions\" first: each button field carries 'action' "
            + "(the name to pass "
            + "here), and list-backed buttons also carry 'actionValues' (the values it "
            + "accepts, e.g. CO=Book / VO=Void / RE=Reactivate for documentAction) and "
            + "'actionParameter' (the key to put the chosen value under in 'parameters'). "
            + "Example — complete a draft sales order: {spec:'sales-order', entity:'header', "
            + "id:'<orderId>', action:'documentAction', parameters:{docAction:'CO'}}. "
            + "Which values are legal depends on the record's current state (e.g. "
            + "documentStatus): read the field's 'agentPrompt' for the document's workflow "
            + "rules, and etendo_get the record first if unsure. "
            + "Reactivating (docAction 'RE') a POSTED document whose entity declares an unpost action "
            + "(the invoices) un-posts it first, as the app does, and the answer then also carries "
            + "steps:['unpost','documentAction']; if the reactivation fails after that, the un-post "
            + "stays done ('partial' says so). "
            + "Returns {processResult: success|error|warning, processMessage: ...}. "
            + "Handler-served actions are listed by etendo_schema view:\"actions\" with a JSON "
            + "Schema for 'parameters' and an 'idDescription' saying what 'id' is; they return "
            + "the handler's own JSON.",
        buildObjectSchema(props,
            List.of("spec", McpConstants.PARAM_ENTITY, "id", "action")));
  }

  // ── Process tool ───────────────────────────────────────────────────────

  private McpToolDefinition buildProcessTool(String specName, SFSpec spec) {
    String toolName = kebabToSnake(specName);
    String desc = String.format("Execute the '%s' process", specName);
    if (spec.getDescription() != null) {
      desc += ". " + spec.getDescription();
    }

    // Build parameter schema from spec entities/fields
    Map<String, Object> paramProps = buildProcessParamSchema(spec);

    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_PARAMETERS, objectProp("Process input parameters", paramProps));

    return new McpToolDefinition(toolName, desc, buildObjectSchema(props, List.of()),
        McpToolTitles.forSpec(spec));
  }

  // ── Report tool ────────────────────────────────────────────────────────

  /**
   * Build the {@code generate_*} tool schema from the handler's declared contract
   * (ETP-4793 / IMP-19).
   *
   * <p>The parameters used to come from {@code buildProcessParamSchema}, which emits a property
   * only for a field backed by an {@code AD_Column}. Every active report spec has zero
   * {@code ETGO_SF_FIELD} rows — report inputs are not AD columns — so that produced an empty map
   * and, because {@code objectProp} omits the key when the map is empty, a bare
   * {@code parameters:{type:"object"}} with no properties and no {@code required} list. An agent
   * had to guess {@code dateFrom} and its date shape, then learn from a 400 that it had guessed
   * wrong. The handler declares the truth, so the schema is built from that instead.</p>
   */
  private McpToolDefinition buildReportTool(String specName, SFSpec spec,
      NeoReportContract contract) {
    String toolName = McpConstants.GENERATE_PREFIX + kebabToSnake(specName);
    String desc = String.format("Generate the '%s' report", specName);
    if (spec.getDescription() != null) {
      desc += ". " + spec.getDescription();
    }
    // ETP-5852 (M-228): a report's own guidance (what its columns mean, how to combine it with
    // other data) reaches the agent the way a window's does, from the spec's AGENT_PROMPT.
    String reportPrompt = spec.getAgentPrompt();
    if (reportPrompt != null && !reportPrompt.isBlank()) {
      desc += (desc.endsWith(".") ? " " : ". ") + reportPrompt.trim();
    }

    Map<String, Object> paramProps = new LinkedHashMap<>();
    for (NeoReportParam param : contract.getParameters()) {
      paramProps.put(param.getName(), reportParamProp(param));
    }

    Map<String, Object> parametersProp = objectProp("Report input parameters",
        paramProps);
    // An empty `properties` map is itself a statement — "this report takes no inputs" — where an
    // absent one reads as "any object", which is the very ambiguity IMP-19 removes. The shared
    // helper omits it when empty (right for process specs, whose parameters come from the
    // AD_Process definition), so pin it explicitly here.
    parametersProp.putIfAbsent(McpConstants.KEY_PROPERTIES, paramProps);
    List<String> requiredParams = contract.getRequiredParameterNames();
    if (!requiredParams.isEmpty()) {
      parametersProp.put(KEY_REQUIRED, requiredParams);
    }

    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_PARAMETERS, parametersProp);
    // Only the formats the handler actually serves. The previous schema advertised
    // "pdf, xlsx, csv (default: pdf)" while the router never read the argument, so a request
    // for a PDF was answered with JSON and nothing said the format had been ignored.
    props.put(McpConstants.PARAM_FORMAT, enumProp(
        "Output format (default: " + contract.getDefaultFormat() + ")", contract.getFormats()));

    return new McpToolDefinition(toolName, desc, buildObjectSchema(props, List.of()),
        McpToolTitles.forSpec(spec));
  }

  /**
   * Render one declared report parameter as a JSON-schema property.
   *
   * <p>{@code date} is carried as a string with the expected shape stated in the description:
   * JSON Schema's own {@code format:"date"} is an annotation most MCP clients do not enforce, and
   * IMP-16 traced silent corruption to date values whose shape was never written down where an
   * agent could read it.</p>
   */
  private Map<String, Object> reportParamProp(NeoReportParam param) {
    String description = param.getDescription();
    if (!param.getAllowedValues().isEmpty()) {
      return enumProp(description, param.getAllowedValues());
    }
    if (NeoReportParam.TYPE_DATE.equals(param.getType())) {
      Map<String, Object> prop = stringProp(description + " Format: yyyy-MM-dd.");
      prop.put("format", "date");
      return prop;
    }
    if (NeoReportParam.TYPE_INTEGER.equals(param.getType())) {
      return numericProp(TYPE_INTEGER, description);
    }
    if (NeoReportParam.TYPE_BOOLEAN.equals(param.getType())) {
      Map<String, Object> prop = new LinkedHashMap<>();
      prop.put("type", "boolean");
      prop.put(McpConstants.KEY_DESCRIPTION, description);
      return prop;
    }
    return stringProp(description);
  }

  // ── Process/report parameter introspection ─────────────────────────────

  /**
   * Build a properties map from the spec's entities and fields.
   * For process and report specs, fields represent input parameters.
   */
  private Map<String, Object> buildProcessParamSchema(SFSpec spec) {
    Map<String, Object> paramProps = new LinkedHashMap<>();

    try {
      OBCriteria<SFEntity> entityCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      List<SFEntity> entities = entityCriteria.list();

      for (SFEntity entity : entities) {
        OBCriteria<SFField> fieldCriteria = OBDal.getInstance().createCriteria(SFField.class);
        fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entity.getId()));
        fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
        List<SFField> fields = fieldCriteria.list();

        for (SFField field : fields) {
          // Field inclusion goes through McpFieldView, never a criteria: the MCP_CONFIG
          // fields.included override lives in JSON the database does not join, so a restriction
          // here would advertise a different parameter set than etendo_schema reports. The entity
          // restriction above stays a criteria on purpose - MCP_CONFIG overrides field inclusion
          // only, so there is nothing for a resolver to add at the entity level.
          if (field.getADColumn() != null && McpFieldView.of(field).isIncluded()) {
            String fieldName = field.getADColumn().getDBColumnName();
            String label = field.getADColumn().getName();
            paramProps.put(fieldName, stringProp(label));
          }
        }
      }
    } catch (Exception e) {
      log.warn("Error building parameter schema for spec '{}': {}", spec.getName(), e.getMessage());
    }

    return paramProps;
  }

  // ── Naming helpers ─────────────────────────────────────────────────────

  /**
   * Convert kebab-case to snake_case (e.g. "complete-order" to "complete_order").
   */
  static String kebabToSnake(String kebab) {
    return kebab.replace('-', '_');
  }

  /**
   * Convert snake_case to kebab-case (e.g. "complete_order" to "complete-order").
   */
  static String snakeToKebab(String snake) {
    return snake.replace('_', '-');
  }

  private static final class ScopePermissions {
    private final boolean canRead;
    private final boolean canWrite;
    private final boolean canProcess;
    private final boolean canReport;

    private ScopePermissions(boolean canRead, boolean canWrite, boolean canProcess,
        boolean canReport) {
      this.canRead = canRead;
      this.canWrite = canWrite;
      this.canProcess = canProcess;
      this.canReport = canReport;
    }
  }
}
