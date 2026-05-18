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
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

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
    }

    // Query all active specs
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
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
      if ("R".equals(specType) && hasProcessAccess(spec) && permissions.canReport) {
        tools.add(buildReportTool(spec.getName(), spec));
      }
    } catch (Exception e) {
      log.warn("Error generating tools for spec '{}': {}", spec.getName(), e.getMessage());
    }
  }

  private void addWindowSpec(SFSpec spec, List<String> accessibleWindowSpecs) {
    Window window = spec.getADWindow();
    if (window == null || NeoAccessUtils.hasWindowAccess(window.getId())) {
      accessibleWindowSpecs.add(spec.getName());
    }
  }

  private boolean hasProcessAccess(SFSpec spec) {
    Process adProcess = spec.getProcess();
    return adProcess == null || NeoAccessUtils.hasProcessAccess(adProcess.getId());
  }

  private void registerCrudTools(List<McpToolDefinition> tools, List<String> accessibleWindowSpecs,
      ScopePermissions permissions) {
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
    }
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
        return true;
      default:
        return false;
    }
  }

  // ── Discovery tool ─────────────────────────────────────────────────────

  private McpToolDefinition buildDiscoverTool() {
    Map<String, Object> schema = buildSchema(McpConstants.TYPE_OBJECT,
        "Discover all available NEO Headless API specs and their entities");
    schema.put(McpConstants.KEY_PROPERTIES, new HashMap<>());
    return new McpToolDefinition(
        "neo_discover",
        "List all available NEO Headless API specs the current user can access. "
            + "Returns spec names, types, entities, and available HTTP methods. "
            + "Use this first to discover what specs and entities are available.",
        schema);
  }

  // ── CRUD tools (registered once with spec enum) ───────────────────────

  private McpToolDefinition buildListTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name (use neo_discover to find available specs)", specNames));
    props.put(McpConstants.PARAM_ENTITY,
      stringProp(McpConstants.LABEL_ENTITY_NAME_WITH_EXAMPLE));
    props.put("filters", objectProp("Filter criteria as key-value pairs (column=value)"));
    props.put("limit", intProp("Maximum number of records to return (default 100)"));
    props.put("offset", intProp("Number of records to skip for pagination"));
    props.put("orderBy", stringProp("Column name to sort by, prefix with '-' for descending"));

    return new McpToolDefinition(
        "neo_list",
        "List records from a NEO Headless API spec. "
            + "Supports filtering, pagination, and sorting.",
          buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY)));
  }

  private McpToolDefinition buildGetTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put("id", stringProp("Record ID to retrieve"));

    return new McpToolDefinition(
        "neo_get",
        "Get a single record by ID from a NEO Headless API spec.",
          buildObjectSchema(props, List.of("spec", McpConstants.PARAM_ENTITY, "id")));
  }

  private McpToolDefinition buildCreateTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put(McpConstants.PARAM_FIELDS, objectProp("Field values for the new record"));

    return new McpToolDefinition(
        "neo_create",
        "Create a new record in a NEO Headless API spec.",
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
    props.put("query", stringProp("Search query to filter selector values"));

    return new McpToolDefinition(
        "neo_selectors",
        "Get foreign-key selector values for a column. "
            + "Use this to discover valid values for FK reference fields.",
        buildObjectSchema(props,
          List.of("spec", McpConstants.PARAM_ENTITY, McpConstants.PARAM_COLUMN)));
  }

  private McpToolDefinition buildDefaultsTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));

    return new McpToolDefinition(
        "neo_defaults",
        "Get default field values for creating a new record. "
            + "Optional — neo_create auto-fills defaults, so only call this if you need to inspect default values before creating.",
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

    return new McpToolDefinition(
        "neo_schema",
        "Get the field schema for an entity: field names, types, required flag, "
            + "read-only flag, default values, visibility (editable/readOnly/system/discarded), "
            + "and which fields have FK selectors. Call this BEFORE neo_create to know which "
            + "fields exist and which are required. Only fields with userRequired=true need to "
            + "be provided — system fields are auto-derived by Etendo callouts.",
        buildObjectSchema(props, List.of("spec", "entity")));
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
    props.put("parameters", objectPropWithProperties("Process input parameters", paramProps));

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
    props.put("parameters", objectPropWithProperties("Report input parameters", paramProps));
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

  private Map<String, Object> buildSchema(String type, String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", type);
    if (description != null) {
      schema.put(McpConstants.KEY_DESCRIPTION, description);
    }
    return schema;
  }

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
