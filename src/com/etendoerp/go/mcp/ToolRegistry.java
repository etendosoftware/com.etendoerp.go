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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * Generates MCP tool definitions dynamically based on ETGO_SF_SPEC configuration
 * and the current user's RBAC permissions.
 * <p>
 * For each active spec, the registry checks:
 * <ol>
 *   <li>RBAC — does the current role have access to the linked AD_Window or AD_Process?</li>
 *   <li>OAuth2 scopes — does the session have the required scope (neo:read, neo:write, etc.)?</li>
 * </ol>
 * <p>
 * Tool generation strategy:
 * <ul>
 *   <li><b>CRUD tools</b> (neo_list, neo_get, neo_create, neo_update, neo_delete, neo_selectors,
 *       neo_defaults): registered ONCE with a required {@code spec} parameter that has an enum
 *       listing all accessible window spec names. This avoids MCP tool name collisions.</li>
 *   <li><b>Process tools</b>: one per process spec, named by spec (e.g. "complete_order")</li>
 *   <li><b>Report tools</b>: one per report spec, prefixed with "generate_"</li>
 *   <li><b>neo_discover</b>: always included when the user has read access</li>
 * </ul>
 */
public class ToolRegistry {

  private static final Logger log = LogManager.getLogger(ToolRegistry.class);

  /**
   * Generate all MCP tools the authenticated user can access.
   *
   * @param scopes OAuth2 scopes granted to this session
   * @return list of tool definitions filtered by RBAC and scopes
   */
  public List<McpToolDefinition> generateTools(Set<String> scopes) {
    List<McpToolDefinition> tools = new ArrayList<>();
    ScopePermissions permissions = resolvePermissions(scopes);

    // Always add neo_discover if user can read
    if (permissions.canRead) {
      tools.add(buildDiscoverTool());
      tools.add(buildDocsTool());
      // neo_widget wraps the handler-backed business widgets (gap G4, ETP-4284). It is a
      // built-in read tool, not gated on any accessible window spec.
      tools.add(buildWidgetTool());
    }

    // Query all active specs
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SHOWINMCP, true));
    criteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    List<SFSpec> specs = criteria.list();

    // Collect accessible window spec names for CRUD tool enum
    List<String> accessibleWindowSpecs = new ArrayList<>();

    for (SFSpec spec : specs) {
      processSpec(spec, accessibleWindowSpecs, tools, permissions);
    }

    // Register CRUD tools once with enum of accessible spec names
    registerCrudTools(tools, accessibleWindowSpecs, permissions);

    log.debug("Generated {} MCP tools for scopes {}", tools.size(), scopes);
    return tools;
  }

  private ScopePermissions resolvePermissions(Set<String> scopes) {
    boolean hasAll = scopes.contains("neo:*");
    return new ScopePermissions(
        hasAll || scopes.contains("neo:read"),
        hasAll || scopes.contains("neo:write"),
        hasAll || scopes.contains("neo:process"),
        hasAll || scopes.contains("neo:report"));
  }

  private void processSpec(SFSpec spec, List<String> accessibleWindowSpecs,
      List<McpToolDefinition> tools, ScopePermissions permissions) {
    try {
      String specType = spec.getSpecType();
      if ("W".equals(specType)) {
        addWindowSpec(spec, accessibleWindowSpecs);
        return;
      }
      if ("P".equals(specType) && hasProcessAccess(spec) && permissions.canProcess) {
        tools.add(buildProcessTool(spec.getName(), spec));
        return;
      }
      // A generate_ tool is emitted only for NEO-native callable report specs backed by a
      // Java qualifier handler. Non-callable report specs get no tool and surface as
      // not configured via neo discover.
      if ("R".equals(specType) && permissions.canReport
          && NeoReportCallability.isReportCallable(spec)) {
        tools.add(buildReportTool(spec.getName(), spec));
      }
    } catch (Exception e) {
      log.warn("Error generating tools for spec '{}': {}", spec.getName(), e.getMessage());
    }
  }

  private void addWindowSpec(SFSpec spec, List<String> accessibleWindowSpecs) {
    // The dashboard/widget spec is handler-backed (no AD_Tab) and is exposed via the
    // neo_widget tool, so it must not pollute the CRUD spec enum (ETP-4284 / G4).
    if (McpToolRouterSupport.isWidgetSpec(spec)) {
      return;
    }
    if (NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")) {
      accessibleWindowSpecs.add(spec.getName());
    }
  }

  private boolean hasProcessAccess(SFSpec spec) {
    Process adProcess = spec.getProcess();
    return adProcess == null || NeoAccessUtils.hasProcessAccess(adProcess.getId());
  }

  private void registerCrudTools(List<McpToolDefinition> tools, List<String> accessibleWindowSpecs,
      ScopePermissions permissions) {
    // Register the amortization plan tool independently of window specs availability:
    // it is a built-in endpoint that does not require a window spec to be accessible.
    if (permissions.canProcess) {
      tools.add(buildGenerateAmortizationPlanTool());
    }

    if (accessibleWindowSpecs.isEmpty()) {
      return;
    }
    if (permissions.canRead) {
      tools.add(buildListTool(accessibleWindowSpecs));
      tools.add(buildGetTool(accessibleWindowSpecs));
      tools.add(buildSelectorsTool(accessibleWindowSpecs));
      tools.add(buildDefaultsTool(accessibleWindowSpecs));
      tools.add(buildSchemaTool(accessibleWindowSpecs));
    }
    if (permissions.canWrite) {
      tools.add(buildCreateTool(accessibleWindowSpecs));
      tools.add(buildUpdateTool(accessibleWindowSpecs));
      tools.add(buildDeleteTool(accessibleWindowSpecs));
      tools.add(buildBatchTool());
      tools.add(buildActionTool(accessibleWindowSpecs));
    }
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
   * For CRUD tools (neo_list, etc.), the spec comes from the "spec" argument.
   * For process tools, the tool name IS the snake_case version of the spec name.
   * For report tools, strip the "generate_" prefix and convert back to kebab.
   *
   * @param toolName  the MCP tool name
   * @param arguments the tool call arguments (may contain "spec")
   * @return the spec name, or null if not resolvable
   */
  public static String resolveSpecName(String toolName, org.codehaus.jettison.json.JSONObject arguments) {
    // Static tools (e.g. docs) are not tied to any spec
    if ("docs".equals(toolName)) {
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
      case "neo_discover":
      case "neo_list":
      case "neo_get":
      case "neo_create":
      case "neo_update":
      case "neo_delete":
      case "neo_selectors":
      case "neo_defaults":
      case "neo_schema":
      case "neo_batch":
      case "neo_action":
      case McpConstants.TOOL_NEO_WIDGET:
      case McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN:
        return true;
      default:
        return false;
    }
  }

  // ── Discovery tool ─────────────────────────────────────────────────────

  private McpToolDefinition buildDiscoverTool() {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", McpConstants.TYPE_OBJECT);
    schema.put(McpConstants.KEY_DESCRIPTION,
        "Discover all available NEO Headless API specs and their entities");
    schema.put(McpConstants.KEY_PROPERTIES, new HashMap<>());
    return new McpToolDefinition(
        "neo_discover",
        "List all available NEO Headless API specs the current user can access. "
            + "Returns spec names, types, entities, and available HTTP methods. "
            + "Use this first to discover what specs and entities are available.",
        schema);
  }

  // ── Docs tool (Context7 documentation lookup) ─────────────────────────

  private McpToolDefinition buildDocsTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("topic", stringProp(
        "Term/topic to search in the Etendo Go docs (e.g. 'finance', 'payment')."));
    props.put("tokens", intProp(
        "Approximate max size of the returned docs (default 5000, clamped to 500-20000)."));
    props.put("type", stringProp(
        "Response format: 'txt' (default) or 'json'."));

    return new McpToolDefinition(
        "docs",
        "Search the Etendo Go documentation (etendosoftware/etendo-go-docs via Context7) "
            + "for a given topic and return the relevant documentation text inline. "
            + "Use this to look up how-tos, concepts, and reference material before "
            + "answering questions about Etendo Go.",
        buildObjectSchema(props, List.of("topic")));
  }

  // ── Widget tool (business widgets enum, gap G4) ───────────────────────

  /**
   * Canonical mapping of {@code neo_widget} enum value → backing {@code dashboard}
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
    desc.put(McpConstants.WIDGET_KPIS, "Summary KPI cards: revenue this month, pending invoices, and "
        + "other headline business metrics with trend percentages.");
    desc.put(McpConstants.WIDGET_REVENUE_TREND, "Monthly revenue series (parallel labels/values arrays) "
        + "for charting the revenue trend over the last 12 months.");
    desc.put(McpConstants.WIDGET_PENDING_TASKS, "Actionable pending tasks and alerts (overdue invoices, "
        + "pending receptions/shipments, collections/payments due).");
    desc.put(McpConstants.WIDGET_ACTIVITY, "Recent activity feed (invoices paid, documents posted, notes).");
    desc.put(McpConstants.WIDGET_RECENT_INVOICES, "Most recent completed sales invoices (newest first).");
    desc.put(McpConstants.WIDGET_BEST_PRODUCTS, "Best-performing products by revenue/quantity.");
    desc.put(McpConstants.WIDGET_BEST_SELLERS, "Best-selling sales reps / sellers ranking.");
    desc.put(McpConstants.WIDGET_PENDING_AMOUNTS, "Outstanding receivable/payable amounts pending collection.");
    desc.put(McpConstants.WIDGET_TOP_CLIENTS, "Top clients ranked by revenue.");
    WIDGET_DESCRIPTION_BY_NAME = java.util.Collections.unmodifiableMap(desc);
  }

  /**
   * Build the {@code neo_widget} tool: a single enum tool wrapping the 9 handler-backed
   * business widgets (gap G4, ETP-4284). The enum value selects the widget; {@code params}
   * is a free-form object forwarded to the handler (e.g. {@code {"range": "30d"}}).
   */
  private McpToolDefinition buildWidgetTool() {
    StringBuilder enumDesc = new StringBuilder(
        "Business widget to invoke. Available widgets:\n");
    for (Map.Entry<String, String> e : WIDGET_DESCRIPTION_BY_NAME.entrySet()) {
      enumDesc.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
    }

    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_WIDGET,
        enumProp(enumDesc.toString(), new ArrayList<>(WIDGET_ENTITY_BY_NAME.keySet())));
    props.put(McpConstants.PARAM_PARAMS, objectProp(
        "Optional parameters forwarded to the widget. Most widgets accept "
            + "'range' (e.g. '7d', '30d', '90d', '12m') to scope the period; "
            + "omit for the widget's default window."));

    return new McpToolDefinition(
        McpConstants.TOOL_NEO_WIDGET,
        "Get pre-computed business analytics from an Etendo Go dashboard widget "
            + "(KPIs, revenue trend, pending tasks, activity, top clients, best sellers/products, "
            + "recent invoices, pending amounts). Returns the widget's JSON payload "
            + "{response:{data,count}}. Use this for business analysis instead of neo_list; "
            + "these widgets aggregate data that has no single CRUD entity.",
        buildObjectSchema(props, List.of(McpConstants.PARAM_WIDGET)));
  }

  // ── CRUD tools (registered once with spec enum) ───────────────────────

  private McpToolDefinition buildListTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name (use neo_discover to find available specs)", specNames));
    props.put(McpConstants.PARAM_ENTITY,
      stringProp(McpConstants.LABEL_ENTITY_NAME_WITH_EXAMPLE));
    props.put("filters", objectProp(
        "Filter criteria. Three shapes, combinable: (1) exact match {\"column\": value}; "
            + "(2) range operators {\"column\": {\"gt\"|\"gte\"|\"lt\"|\"lte\": value}} or "
            + "{\"column\": {\"between\": [from, to]}} (dates as \"YYYY-MM-DD\"); "
            + "(3) named business filter {\"status\": \"<name>\"} — the spec's own hand-authored "
            + "statuses (e.g. \"pending\", \"partial\", \"completed\"). Call neo_schema to see the "
            + "named filters available for a given spec; an unknown name returns the valid list."));
    props.put("limit", intProp("Maximum number of records to return (default 100)"));
    props.put("offset", intProp("Number of records to skip for pagination"));
    props.put("orderBy", stringProp("Column name to sort by, prefix with '-' for descending"));
    props.put(McpFieldProjection.PARAM_FIELDS, stringArrayProp(
        "Optional projection: return only these field names per row (e.g. "
            + "[\"documentNo\",\"businessPartner\",\"grandTotalAmount\"]). A FK's $_identifier "
            + "label is included automatically. Omit to return every column."));
    props.put(McpFieldProjection.PARAM_VIEW, enumProp(
        "Optional curated view. \"summary\" returns only the spec's business-critical fields — a "
            + "compact row for compliance-heavy specs. Ignored when `fields` is given; omit for the "
            + "full row.", List.of(McpFieldProjection.VIEW_SUMMARY)));

    return new McpToolDefinition(
        "neo_list",
        "List records from a NEO Headless API spec. "
            + "Supports filtering (exact match, range operators, named document status), "
            + "pagination, sorting, and field projection (`fields` / view:\"summary\").",
          buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY)));
  }

  private McpToolDefinition buildGetTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to retrieve"));
    props.put(McpFieldProjection.PARAM_FIELDS, stringArrayProp(
        "Optional projection: return only these field names (e.g. "
            + "[\"documentNo\",\"grandTotalAmount\"]). A FK's $_identifier label is included "
            + "automatically. Omit to return every column."));
    props.put(McpFieldProjection.PARAM_VIEW, enumProp(
        "Optional curated view. \"summary\" returns only the spec's business-critical fields. "
            + "Ignored when `fields` is given; omit for the full record.",
        List.of(McpFieldProjection.VIEW_SUMMARY)));

    return new McpToolDefinition(
        "neo_get",
        "Get a single record by ID from a NEO Headless API spec. Supports field projection "
            + "(`fields` / view:\"summary\").",
          buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY, "id")));
  }

  private McpToolDefinition buildCreateTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put(McpConstants.PARAM_FIELDS, objectProp("Field values for the new record"));

    return new McpToolDefinition(
        "neo_create",
        "Create a new record in a NEO Headless API spec. "
            + "Recommended: call neo_defaults first to get the initial/base set of field values "
            + "for this record type, then build the fields object by overriding only the values "
            + "the user actually wants to change on top of that base — instead of asking the "
            + "user for every field or guessing values that already have a sensible default "
            + "(document number, dates, prices, etc.).",
        buildObjectSchema(props,
          List.of("spec", McpConstants.PARAM_ENTITY, McpConstants.PARAM_FIELDS)));
  }

  private McpToolDefinition buildUpdateTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to update"));
    props.put(McpConstants.PARAM_FIELDS, objectProp("Field values to update"));

    return new McpToolDefinition(
        "neo_update",
        "Update an existing record in a NEO Headless API spec.",
        buildObjectSchema(props,
          List.of("spec", McpConstants.PARAM_ENTITY, "id", McpConstants.PARAM_FIELDS)));
  }

  private McpToolDefinition buildDeleteTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to delete"));

    return new McpToolDefinition(
        "neo_delete",
        "Delete a record from a NEO Headless API spec.",
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
    props.put(McpConstants.PARAM_QUERY, stringProp("Search query to filter selector values"));
    props.put(McpConstants.PARAM_RECORD_CONTEXT, objectProp(
        "Optional context from the current record to resolve dependent selectors. "
            + "For example: {\"businessPartner\": \"<id>\"} for partnerAddress, "
            + "or {\"invoiceDate\": \"2026-05-12\"} for line tax selectors."));
    props.put(McpConstants.PARAM_PARENT_CONTEXT, objectProp(
        "Optional parent/header record context for child selectors. "
            + "For example: {\"businessPartner\": \"<id>\", \"orderDate\": \"2026-05-12\", "
            + "\"priceList\": \"<id>\"} when resolving line selectors."));

    return new McpToolDefinition(
        "neo_selectors",
        "Get foreign-key selector values for a column. "
            + "Use this to discover valid values for FK reference fields. "
            + "Pass recordContext when the selector depends on other field values "
            + "(e.g. partnerAddress requires businessPartner). "
            + "Pass parentContext for line selectors that depend on header values "
            + "(e.g. tax requires orderDate/invoiceDate and priceList).",
        buildObjectSchema(props,
          List.of("spec", McpConstants.PARAM_ENTITY, McpConstants.PARAM_COLUMN)));
  }

  private McpToolDefinition buildDefaultsTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME_WITH_EXAMPLE));
    props.put(McpConstants.PARAM_PARENT_ID, stringProp(
        "Optional parent record ID for child entities (e.g. order ID when getting line defaults)"));
    props.put(McpConstants.PARAM_ASSET_ID, stringProp(
        "Optional asset ID for computing dynamic defaults that depend on a specific asset "
            + "(e.g. the amortization header name derived from the asset name and start date)"));
    props.put(McpDefaultsView.PARAM_VIEW, enumProp(
        "Optional response shape. Omit (or \"full\") for the historical flat map of every default. "
            + "\"grouped\" splits the result into `confirm` (writable fields you should review or "
            + "override before neo_create) and `systemManaged` (compliance/audit flags the server "
            + "owns — leave them alone). \"minimal\" returns only the `confirm` block. Use "
            + "grouped/minimal on compliance-heavy specs (invoices, payments) to avoid wading "
            + "through ~65 fields when only ~5 matter.",
        List.of(McpDefaultsView.VIEW_FULL, McpDefaultsView.VIEW_GROUPED,
            McpDefaultsView.VIEW_MINIMAL)));

    return new McpToolDefinition(
        "neo_defaults",
        "Get the initial/base set of field values for a new record — field types, which fields "
            + "are required vs optional, and computed/system defaults (document number, dates, "
            + "prices, etc.). Recommended: call this BEFORE neo_create, then use its result as "
            + "the starting point and only override the fields the user actually wants to set — "
            + "instead of asking the user for every value from scratch. neo_create will still "
            + "auto-fill any field you omit, but calling this first lets you see the full base "
            + "dataset up front. Pass view:\"minimal\" (or \"grouped\") to collapse server-managed "
            + "compliance flags and focus on the fields you actually confirm.",
        buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY)));
  }

  // ── Batch tool (cross-spec, atomic) ───────────────────────────────────

  /**
   * Build the {@code neo_batch} tool definition. Unlike the per-spec CRUD tools,
   * each operation in the batch carries its own {@code spec}, so this tool is
   * registered once with no top-level enum.
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
        "Entity name within the spec (e.g. 'Header', 'Lines').");
    opProps.put(McpConstants.PARAM_ENTITY, entityProp);

    Map<String, Object> parentRefProp = new LinkedHashMap<>();
    parentRefProp.put("type", McpConstants.TYPE_STRING);
    parentRefProp.put(McpConstants.KEY_DESCRIPTION,
        "Optional id of an earlier op whose recordId becomes this op's parent FK.");
    opProps.put("parentRef", parentRefProp);

    Map<String, Object> bodyProp = new LinkedHashMap<>();
    bodyProp.put("type", McpConstants.TYPE_OBJECT);
    bodyProp.put(McpConstants.KEY_DESCRIPTION,
        "Field values for the new record. String values of the form '$ref:<opId>' are "
            + "replaced with the resolved recordId of an earlier op.");
    opProps.put("body", bodyProp);

    Map<String, Object> opItem = new LinkedHashMap<>();
    opItem.put("type", McpConstants.TYPE_OBJECT);
    opItem.put(McpConstants.KEY_PROPERTIES, opProps);
    opItem.put("required", List.of("id", "spec", McpConstants.PARAM_ENTITY));

    Map<String, Object> operationsProp = new LinkedHashMap<>();
    operationsProp.put("type", "array");
    operationsProp.put(McpConstants.KEY_DESCRIPTION,
        "Ordered list of create operations to run as a single transaction.");
    operationsProp.put("items", opItem);

    Map<String, Object> props = new LinkedHashMap<>();
    props.put("operations", operationsProp);

    return new McpToolDefinition(
        "neo_batch",
        "Run a sequence of cross-spec create operations atomically. All operations "
            + "share one OBDal transaction: success commits everything, any failure "
            + "rolls back everything (no partial writes). Each op carries its own "
            + "'spec' and 'entity', so a single batch can mix windows (e.g. create a "
            + "Business Partner, a Location, then a Purchase Invoice referencing both). "
            + "Use 'parentRef':<earlierOpId> to set the parent FK on a child-tab op, "
            + "and string values of the form '$ref:<earlierOpId>' anywhere in 'body' "
            + "to substitute the resolved recordId of an earlier op. Typically call "
            + "neo_list / neo_selectors first to look up existing records and only "
            + "include create ops for what is genuinely new. "
            + "Returns {committed:true, operations:[{id,ok:true,recordId}]} on success "
            + "or {committed:false, failedAt:{id,index}, error:{status,message,detail?}} "
            + "on failure.",
        buildObjectSchema(props, List.of("operations")));
  }

  // ── Schema tool ────────────────────────────────────────────────────────

  private McpToolDefinition buildSchemaTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name (use neo_discover to find available specs)", specNames));
    props.put(McpConstants.PARAM_ENTITY,
      stringProp("Entity name within the spec (e.g. 'Header', 'Lines')"));
    props.put(McpActionsView.PARAM_VIEW, enumProp(
        "Optional response shape. Omit for the full field dump (default, unchanged) — but note it "
            + "can exceed 60 kB on compliance-heavy windows and may not fit your context. "
            + "\"create\" returns ONLY the fields you may send to neo_create, split into "
            + "required/optional — this is what you want before a create. "
            + "\"actions\" returns only the callable buttons/processes ({name, label, "
            + "invokeVia:\"neo_action\", action, processName, processId, ...}) — use it when you "
            + "only need to know what can be triggered on this entity, not every column.",
        List.of(McpSchemaCreateView.VIEW_CREATE, McpActionsView.VIEW_ACTIONS)));
    props.put(McpSchemaCreateView.PARAM_FIELDS, stringArrayProp(
        "Optional whitelist of field names to describe (e.g. [\"businessPartner\",\"invoiceDate\"]). "
            + "Returns only those descriptors instead of all of them. Names that match nothing come "
            + "back in \"unknownFields\" — check it if a field you expected is missing. Ignored when "
            + "\"view\" is set."));

    return new McpToolDefinition(
        "neo_schema",
        "Get the field schema for an entity: field names, types, required flag, "
            + "read-only flag, default values, visibility (editable/readOnly/system/discarded), "
            + "and which fields have FK selectors. Call this BEFORE neo_create to know which "
            + "fields exist and which are required — and prefer view:\"create\", which returns "
            + "only the fields you may send, already split into required/optional. Only fields "
            + "with userRequired=true need to be provided: a field that is mandatory but that the "
            + "server can already resolve a value for — from an AD default, a session preference, "
            + "the business partner's configuration, or a callout — is filled by the server, so it "
            + "is NOT userRequired. In view:\"create\" those appear under optional with "
            + "serverDefaulted=true. System fields are "
            + "auto-derived by Etendo callouts. Pass view:\"actions\" for the callable "
            + "buttons/processes instead.",
        buildObjectSchema(props, List.of("spec", "entity")));
  }

  // ── Action tool ────────────────────────────────────────────────────────

  private McpToolDefinition buildActionTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to act upon"));
    props.put("action", stringProp(
        "Column name of the button field to trigger (e.g. 'Processed', 'Processing')"));
    props.put(McpConstants.PARAM_PARAMETERS, objectProp(
        "Process parameters. For a list-backed button, put the chosen value under the key "
            + "named by the field's 'actionParameter' — e.g. {\"docAction\": \"CO\"}"));

    return new McpToolDefinition(
        "neo_action",
        "Fire a type:button action on a record and return the process result. "
            + "Call neo_schema first: each button field carries 'action' (the name to pass "
            + "here), and list-backed buttons also carry 'actionValues' (the values it "
            + "accepts, e.g. CO=Book / VO=Void / RE=Reactivate for documentAction) and "
            + "'actionParameter' (the key to put the chosen value under in 'parameters'). "
            + "Example — complete a draft sales order: {spec:'sales-order', entity:'header', "
            + "id:'<orderId>', action:'documentAction', parameters:{docAction:'CO'}}. "
            + "Which values are legal depends on the record's current state (e.g. "
            + "documentStatus): read the field's 'agentPrompt' for the document's workflow "
            + "rules, and neo_get the record first if unsure. "
            + "Returns {processResult: success|error|warning, processMessage: ...}.",
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
    props.put(McpConstants.PARAM_PARAMETERS, objectPropWithProperties("Process input parameters", paramProps));

    return new McpToolDefinition(toolName, desc, buildObjectSchema(props, List.of()));
  }

  // ── Report tool ────────────────────────────────────────────────────────

  private McpToolDefinition buildReportTool(String specName, SFSpec spec) {
    String toolName = McpConstants.GENERATE_PREFIX + kebabToSnake(specName);
    String desc = String.format("Generate the '%s' report", specName);
    if (spec.getDescription() != null) {
      desc += ". " + spec.getDescription();
    }

    Map<String, Object> paramProps = buildProcessParamSchema(spec);

    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_PARAMETERS, objectPropWithProperties("Report input parameters", paramProps));
    props.put("format", stringProp("Output format: pdf, xlsx, csv (default: pdf)", false));

    return new McpToolDefinition(toolName, desc, buildObjectSchema(props, List.of()));
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
        fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
        List<SFField> fields = fieldCriteria.list();

        for (SFField field : fields) {
          if (field.getADColumn() != null) {
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

  // ── JSON Schema builder helpers ────────────────────────────────────────

  private Map<String, Object> buildObjectSchema(Map<String, Object> properties,
      List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", McpConstants.TYPE_OBJECT);
    schema.put(McpConstants.KEY_PROPERTIES, properties);
    if (required != null && !required.isEmpty()) {
      schema.put("required", required);
    }
    return schema;
  }

  private Map<String, Object> stringProp(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_STRING);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    return prop;
  }

  private Map<String, Object> stringProp(String description, boolean required) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_STRING);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    if (!required) {
      prop.put("optional", true);
    }
    return prop;
  }

  private Map<String, Object> enumProp(String description, List<String> values) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_STRING);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    prop.put("enum", values);
    return prop;
  }

  private Map<String, Object> intProp(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "integer");
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    return prop;
  }

  private Map<String, Object> objectProp(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_OBJECT);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    return prop;
  }

  /** A JSON-schema array of strings, used for the IMP-2 {@code fields} projection whitelist. */
  private Map<String, Object> stringArrayProp(String description) {
    Map<String, Object> items = new LinkedHashMap<>();
    items.put("type", McpConstants.TYPE_STRING);
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "array");
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    prop.put("items", items);
    return prop;
  }

  private Map<String, Object> objectPropWithProperties(String description,
      Map<String, Object> nestedProps) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", McpConstants.TYPE_OBJECT);
    prop.put(McpConstants.KEY_DESCRIPTION, description);
    if (nestedProps != null && !nestedProps.isEmpty()) {
      prop.put(McpConstants.KEY_PROPERTIES, nestedProps);
    }
    return prop;
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
