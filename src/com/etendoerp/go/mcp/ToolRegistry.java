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
 * Tool generation by spec type:
 * <ul>
 *   <li><b>W (Window)</b>: CRUD tools (neo_list, neo_get, neo_create, neo_update, neo_delete)
 *       plus neo_selectors and neo_defaults</li>
 *   <li><b>P (Process)</b>: A named process tool (e.g. complete_order)</li>
 *   <li><b>R (Report)</b>: A named report tool (e.g. generate_invoice_report)</li>
 * </ul>
 * The neo_discover tool is always included when the user has read access.
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
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ACTIVE, true));
    criteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    List<SFSpec> specs = criteria.list();

    for (SFSpec spec : specs) {
      try {
        addToolsForSpec(spec, canRead, canWrite, canProcess, canReport, tools);
      } catch (Exception e) {
        log.warn("Error generating tools for spec '{}': {}", spec.getName(), e.getMessage());
      }
    }

    log.debug("Generated {} MCP tools for scopes {}", tools.size(), scopes);
    return tools;
  }

  // ── Per-spec tool generation ───────────────────────────────────────────

  private void addToolsForSpec(SFSpec spec, boolean canRead, boolean canWrite,
      boolean canProcess, boolean canReport, List<McpToolDefinition> tools) {

    String specType = spec.getSpecType();
    String specName = spec.getName();

    if ("W".equals(specType)) {
      // RBAC check for windows
      Window window = spec.getADWindow();
      if (window != null && !NeoAccessUtils.hasWindowAccess(window.getId())) {
        return;
      }

      // CRUD tools gated by read/write scopes
      if (canRead) {
        tools.add(buildListTool(specName, spec));
        tools.add(buildGetTool(specName, spec));
        tools.add(buildSelectorsTool(specName, spec));
        tools.add(buildDefaultsTool(specName, spec));
      }
      if (canWrite) {
        tools.add(buildCreateTool(specName, spec));
        tools.add(buildUpdateTool(specName, spec));
        tools.add(buildDeleteTool(specName, spec));
      }

    } else if ("P".equals(specType)) {
      // RBAC check for processes
      Process adProcess = spec.getProcess();
      if (adProcess != null && !NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
        return;
      }
      if (canProcess) {
        tools.add(buildProcessTool(specName, spec));
      }

    } else if ("R".equals(specType)) {
      // RBAC check for reports
      Process adProcess = spec.getProcess();
      if (adProcess != null && !NeoAccessUtils.hasProcessAccess(adProcess.getId())) {
        return;
      }
      if (canReport) {
        tools.add(buildReportTool(specName, spec));
      }
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
            + "Returns spec names, types, entities, and available HTTP methods.",
        schema);
  }

  // ── CRUD tools (window specs) ──────────────────────────────────────────

  private McpToolDefinition buildListTool(String specName, SFSpec spec) {
    String desc = String.format("List records from '%s'", specName);
    if (spec.getDescription() != null) {
      desc += ". " + spec.getDescription();
    }

    Map<String, Object> props = new LinkedHashMap<>();
    props.put("entity", stringProp("Entity name within the spec (e.g. 'header', 'lines')", true));
    props.put("filters", objectProp("Filter criteria as key-value pairs (column=value)"));
    props.put("limit", intProp("Maximum number of records to return (default 100)"));
    props.put("offset", intProp("Number of records to skip for pagination"));
    props.put("orderBy", stringProp("Column name to sort by, prefix with '-' for descending", false));

    return new McpToolDefinition(
        "neo_list",
        desc,
        buildObjectSchema(props, List.of("entity")));
  }

  private McpToolDefinition buildGetTool(String specName, SFSpec spec) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("id", stringProp("Record ID to retrieve", true));

    return new McpToolDefinition(
        "neo_get",
        String.format("Get a single record by ID from '%s'", specName),
        buildObjectSchema(props, List.of("entity", "id")));
  }

  private McpToolDefinition buildCreateTool(String specName, SFSpec spec) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("fields", objectProp("Field values for the new record"));

    return new McpToolDefinition(
        "neo_create",
        String.format("Create a new record in '%s'", specName),
        buildObjectSchema(props, List.of("entity", "fields")));
  }

  private McpToolDefinition buildUpdateTool(String specName, SFSpec spec) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("id", stringProp("Record ID to update", true));
    props.put("fields", objectProp("Field values to update"));

    return new McpToolDefinition(
        "neo_update",
        String.format("Update an existing record in '%s'", specName),
        buildObjectSchema(props, List.of("entity", "id", "fields")));
  }

  private McpToolDefinition buildDeleteTool(String specName, SFSpec spec) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("id", stringProp("Record ID to delete", true));

    return new McpToolDefinition(
        "neo_delete",
        String.format("Delete a record from '%s'", specName),
        buildObjectSchema(props, List.of("entity", "id")));
  }

  private McpToolDefinition buildSelectorsTool(String specName, SFSpec spec) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("entity", stringProp("Entity name within the spec", true));
    props.put("column", stringProp("Column name to get selector values for", true));
    props.put("query", stringProp("Search query to filter selector values", false));

    return new McpToolDefinition(
        "neo_selectors",
        String.format("Get foreign-key selector values for a column in '%s'", specName),
        buildObjectSchema(props, List.of("entity", "column")));
  }

  private McpToolDefinition buildDefaultsTool(String specName, SFSpec spec) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("entity", stringProp("Entity name within the spec", true));

    return new McpToolDefinition(
        "neo_defaults",
        String.format("Get default field values for creating a new record in '%s'", specName),
        buildObjectSchema(props, List.of("entity")));
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
        fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ACTIVE, true));
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
}
