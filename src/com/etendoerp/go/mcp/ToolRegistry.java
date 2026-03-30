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

    boolean hasAll = scopes.contains("neo:*");
    boolean canRead = hasAll || scopes.contains("neo:read");
    boolean canWrite = hasAll || scopes.contains("neo:write");
    boolean canProcess = hasAll || scopes.contains("neo:process");
    boolean canReport = hasAll || scopes.contains("neo:report");

    // Always add neo_discover if user can read
    if (canRead) {
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
      try {
        String specType = spec.getSpecType();
        String specName = spec.getName();

        if ("W".equals(specType)) {
          Window window = spec.getADWindow();
          if (window != null && !NeoAccessUtils.hasWindowAccess(window.getId())) {
            continue;
          }
          accessibleWindowSpecs.add(specName);

        } else if ("P".equals(specType)) {
          Process adProcess = spec.getProcess();
          if (adProcess != null && !NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
            continue;
          }
          if (canProcess) {
            tools.add(buildProcessTool(specName, spec));
          }

        } else if ("R".equals(specType)) {
          Process adProcess = spec.getProcess();
          if (adProcess != null && !NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
            continue;
          }
          if (canReport) {
            tools.add(buildReportTool(specName, spec));
          }
        }
      } catch (Exception e) {
        log.warn("Error generating tools for spec '{}': {}", spec.getName(), e.getMessage());
      }
    }

    // Register CRUD tools once with enum of accessible spec names
    if (!accessibleWindowSpecs.isEmpty()) {
      if (canRead) {
        tools.add(buildListTool(accessibleWindowSpecs));
        tools.add(buildGetTool(accessibleWindowSpecs));
        tools.add(buildSelectorsTool(accessibleWindowSpecs));
        tools.add(buildDefaultsTool(accessibleWindowSpecs));
      }
      if (canWrite) {
        tools.add(buildCreateTool(accessibleWindowSpecs));
        tools.add(buildUpdateTool(accessibleWindowSpecs));
        tools.add(buildDeleteTool(accessibleWindowSpecs));
      }
    }

    log.debug("Generated {} MCP tools for scopes {}", tools.size(), scopes);
    return tools;
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
    if (toolName.startsWith("generate_")) {
      return snakeToKebab(toolName.substring("generate_".length()));
    }

    // Process tools: tool name is snake_case of spec name
    return snakeToKebab(toolName);
  }

  /**
   * Check if a tool name is a CRUD tool (shared across specs).
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
        return true;
      default:
        return false;
    }
  }

  // ── Discovery tool ─────────────────────────────────────────────────────

  private McpToolDefinition buildDiscoverTool() {
    Map<String, Object> schema = buildSchema("object",
        "Discover all available NEO Headless API specs and their entities");
    schema.put("properties", new HashMap<>());
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
    props.put("entity", stringProp("Entity name within the spec (e.g. 'header', 'lines')", true));
    props.put("filters", objectProp("Filter criteria as key-value pairs (column=value)"));
    props.put("limit", intProp("Maximum number of records to return (default 100)"));
    props.put("offset", intProp("Number of records to skip for pagination"));
    props.put("orderBy", stringProp("Column name to sort by, prefix with '-' for descending", false));

    return new McpToolDefinition(
        "neo_list",
        "List records from a NEO Headless API spec. "
            + "Supports filtering, pagination, and sorting.",
        buildObjectSchema(props, List.of("spec", "entity")));
  }

  private McpToolDefinition buildGetTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name", specNames));
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("id", stringProp("Record ID to retrieve", true));

    return new McpToolDefinition(
        "neo_get",
        "Get a single record by ID from a NEO Headless API spec.",
        buildObjectSchema(props, List.of("spec", "entity", "id")));
  }

  private McpToolDefinition buildCreateTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name", specNames));
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("fields", objectProp("Field values for the new record"));

    return new McpToolDefinition(
        "neo_create",
        "Create a new record in a NEO Headless API spec.",
        buildObjectSchema(props, List.of("spec", "entity", "fields")));
  }

  private McpToolDefinition buildUpdateTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name", specNames));
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("id", stringProp("Record ID to update", true));
    props.put("fields", objectProp("Field values to update"));

    return new McpToolDefinition(
        "neo_update",
        "Update an existing record in a NEO Headless API spec.",
        buildObjectSchema(props, List.of("spec", "entity", "id", "fields")));
  }

  private McpToolDefinition buildDeleteTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name", specNames));
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("id", stringProp("Record ID to delete", true));

    return new McpToolDefinition(
        "neo_delete",
        "Delete a record from a NEO Headless API spec.",
        buildObjectSchema(props, List.of("spec", "entity", "id")));
  }

  private McpToolDefinition buildSelectorsTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name", specNames));
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("column", stringProp("Column name to get selector values for", true));
    props.put("query", stringProp("Search query to filter selector values", false));

    return new McpToolDefinition(
        "neo_selectors",
        "Get foreign-key selector values for a column. "
            + "Use this to discover valid values for FK reference fields.",
        buildObjectSchema(props, List.of("spec", "entity", "column")));
  }

  private McpToolDefinition buildDefaultsTool(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("spec", enumProp("Spec name", specNames));
    props.put("entity", stringProp("Entity name within the spec", true));

    return new McpToolDefinition(
        "neo_defaults",
        "Get default field values for creating a new record. "
            + "Call this before neo_create to know which fields are pre-populated.",
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
    String toolName = "generate_" + kebabToSnake(specName);
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
            boolean required = field.getADColumn().isMandatory();
            paramProps.put(fieldName, stringProp(label, required));
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
      schema.put("description", description);
    }
    return schema;
  }

  private Map<String, Object> buildObjectSchema(Map<String, Object> properties,
      List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (required != null && !required.isEmpty()) {
      schema.put("required", required);
    }
    return schema;
  }

  private Map<String, Object> stringProp(String description, boolean required) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "string");
    prop.put("description", description);
    return prop;
  }

  private Map<String, Object> enumProp(String description, List<String> values) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "string");
    prop.put("description", description);
    prop.put("enum", values);
    return prop;
  }

  private Map<String, Object> intProp(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "integer");
    prop.put("description", description);
    return prop;
  }

  private Map<String, Object> objectProp(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "object");
    prop.put("description", description);
    return prop;
  }

  private Map<String, Object> objectPropWithProperties(String description,
      Map<String, Object> nestedProps) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "object");
    prop.put("description", description);
    if (nestedProps != null && !nestedProps.isEmpty()) {
      prop.put("properties", nestedProps);
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
}
