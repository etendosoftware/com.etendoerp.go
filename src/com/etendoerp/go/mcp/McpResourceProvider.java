package com.etendoerp.go.mcp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Provides MCP Resources — read-only schema information that AI agents can browse
 * without invoking tool calls.
 * <p>
 * Resource URIs:
 * <ul>
 *   <li>{@code neo://specs} — List of all active specs (name, type, description)</li>
 *   <li>{@code neo://specs/{specName}} — Full spec schema (entities and their fields)</li>
 *   <li>{@code neo://specs/{specName}/{entityName}} — Single entity detail (fields, types, FK refs)</li>
 *   <li>{@code neo://processes/{specName}} — Process parameters and description</li>
 * </ul>
 */
public class McpResourceProvider {

  private static final Logger log = LogManager.getLogger(McpResourceProvider.class);

  // AD_Reference IDs for selector types
  private static final Set<String> SELECTOR_REFS = new HashSet<>();
  static {
    SELECTOR_REFS.add("19"); // TableDir
    SELECTOR_REFS.add("18"); // Table
    SELECTOR_REFS.add("30"); // Search
    SELECTOR_REFS.add("95E2A8B50A254B2AAE6774B8C2F28120"); // OBUISEL
  }

  /**
   * List all available MCP resources.
   * Returns one static resource (neo://specs) plus one resource per active spec.
   *
   * @return a JSONArray of resource descriptors
   */
  public JSONArray listResources() throws Exception {
    JSONArray resources = new JSONArray();

    // Static resource: list of all specs
    JSONObject specsList = new JSONObject();
    specsList.put("uri", "neo://specs");
    specsList.put("name", "Available NEO Specs");
    specsList.put("description",
        "List of all NEO Headless API specs configured in this instance");
    specsList.put("mimeType", "application/json");
    resources.put(specsList);

    // Dynamic resources: one per active spec
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ACTIVE, true));
    criteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));

    for (SFSpec spec : criteria.list()) {
      String specType = spec.getSpecType();
      String specName = spec.getName();

      // Spec schema resource
      JSONObject specResource = new JSONObject();
      specResource.put("uri", "neo://specs/" + specName);
      specResource.put("name", "Spec: " + specName);
      specResource.put("description",
          spec.getDescription() != null ? spec.getDescription() : "Schema for " + specName);
      specResource.put("mimeType", "application/json");
      resources.put(specResource);

      // Process description resource (for process and report specs)
      if ("P".equals(specType) || "R".equals(specType)) {
        JSONObject processResource = new JSONObject();
        processResource.put("uri", "neo://processes/" + specName);
        processResource.put("name", "Process: " + specName);
        processResource.put("description",
            ("R".equals(specType) ? "Report" : "Process") + " parameters for " + specName);
        processResource.put("mimeType", "application/json");
        resources.put(processResource);
      }
    }

    return resources;
  }

  /**
   * Read a specific resource by URI.
   *
   * @param uri the MCP resource URI (e.g. "neo://specs/purchase-order")
   * @return the resource content as a JSONObject
   * @throws IllegalArgumentException if the URI is unknown or the resource is not found
   */
  public JSONObject readResource(String uri) throws Exception {
    if ("neo://specs".equals(uri)) {
      return readSpecsList();
    }

    if (uri.startsWith("neo://specs/")) {
      String path = uri.substring("neo://specs/".length());
      String[] parts = path.split("/", 2);
      if (parts.length == 1) {
        return readSpec(parts[0]);
      } else {
        return readEntity(parts[0], parts[1]);
      }
    }

    if (uri.startsWith("neo://processes/")) {
      String specName = uri.substring("neo://processes/".length());
      return readProcess(specName);
    }

    throw new IllegalArgumentException("Unknown resource URI: " + uri);
  }

  // ── Resource readers ────────────────────────────────────────────────────

  /**
   * Read neo://specs — a summary of all active specs.
   */
  private JSONObject readSpecsList() throws Exception {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ACTIVE, true));
    criteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    List<SFSpec> specs = criteria.list();

    JSONArray specsArray = new JSONArray();
    for (SFSpec spec : specs) {
      JSONObject specObj = new JSONObject();
      specObj.put("name", spec.getName());
      specObj.put("type", spec.getSpecType());
      specObj.put("description", spec.getDescription());

      // Include linked AD object info
      if ("W".equals(spec.getSpecType())) {
        Window window = spec.getADWindow();
        if (window != null) {
          specObj.put("windowName", window.getName());
        }
      } else if ("P".equals(spec.getSpecType()) || "R".equals(spec.getSpecType())) {
        Process process = spec.getProcess();
        if (process != null) {
          specObj.put("processName", process.getName());
        }
        if ("R".equals(spec.getSpecType())) {
          specObj.put("isReport", true);
        }
      }

      // Count entities
      OBCriteria<SFEntity> entityCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      specObj.put("entityCount", entityCriteria.list().size());

      specsArray.put(specObj);
    }

    JSONObject result = new JSONObject();
    result.put("specs", specsArray);
    result.put("count", specsArray.length());
    return result;
  }

  /**
   * Read neo://specs/{specName} — full spec schema with entities and their fields.
   */
  private JSONObject readSpec(String specName) throws Exception {
    SFSpec spec = findSpecByName(specName);

    JSONObject result = new JSONObject();
    result.put("name", spec.getName());
    result.put("type", spec.getSpecType());
    result.put("description", spec.getDescription());

    // Linked AD object
    if ("W".equals(spec.getSpecType())) {
      Window window = spec.getADWindow();
      if (window != null) {
        result.put("windowName", window.getName());
        result.put("windowId", window.getId());
      }
    } else if ("P".equals(spec.getSpecType()) || "R".equals(spec.getSpecType())) {
      Process process = spec.getProcess();
      if (process != null) {
        result.put("processName", process.getName());
        result.put("processId", process.getId());
      }
    }

    // Query entities
    OBCriteria<SFEntity> entityCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    entityCriteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
    List<SFEntity> entities = entityCriteria.list();

    JSONArray entitiesArray = new JSONArray();
    for (SFEntity entity : entities) {
      JSONObject entityObj = buildEntityJson(entity, true);
      entitiesArray.put(entityObj);
    }

    result.put("entities", entitiesArray);
    return result;
  }

  /**
   * Read neo://specs/{specName}/{entityName} — detailed view of a single entity.
   */
  private JSONObject readEntity(String specName, String entityName) throws Exception {
    SFSpec spec = findSpecByName(specName);

    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFEntity> entities = criteria.list();

    if (entities.isEmpty()) {
      throw new IllegalArgumentException(
          "Entity '" + entityName + "' not found in spec '" + specName + "'");
    }

    SFEntity entity = entities.get(0);
    JSONObject result = buildEntityJson(entity, true);
    result.put("specName", specName);
    result.put("specType", spec.getSpecType());
    return result;
  }

  /**
   * Read neo://processes/{specName} — process parameters and metadata.
   */
  private JSONObject readProcess(String specName) throws Exception {
    SFSpec spec = findSpecByName(specName);
    String specType = spec.getSpecType();

    if (!"P".equals(specType) && !"R".equals(specType)) {
      throw new IllegalArgumentException(
          "Spec '" + specName + "' is not a process or report (type: " + specType + ")");
    }

    Process adProcess = spec.getProcess();
    if (adProcess == null) {
      throw new IllegalArgumentException(
          "Spec '" + specName + "' has no linked AD_Process");
    }

    JSONObject result = new JSONObject();
    result.put("specName", specName);
    result.put("specType", specType);
    result.put("isReport", "R".equals(specType));
    result.put("processName", adProcess.getName());
    result.put("processId", adProcess.getId());
    result.put("description", adProcess.getDescription());
    result.put("helpComment", adProcess.getHelpComment());
    result.put("uiPattern", adProcess.getUIPattern());

    // Build parameter list from AD_Process_Para
    JSONArray parameters = new JSONArray();
    List<ProcessParameter> paramList = adProcess.getADProcessParameterList();
    for (ProcessParameter param : paramList) {
      if (!Boolean.TRUE.equals(param.isActive())) {
        continue;
      }
      JSONObject paramObj = new JSONObject();
      paramObj.put("name", param.getName());
      paramObj.put("dbColumnName", param.getDBColumnName());
      paramObj.put("sequenceNumber", param.getSequenceNumber());
      paramObj.put("mandatory", Boolean.TRUE.equals(param.isMandatory()));
      paramObj.put("defaultValue", param.getDefaultValue());
      paramObj.put("description", param.getDescription());

      if (param.getReference() != null) {
        paramObj.put("referenceId", param.getReference().getId());
        paramObj.put("referenceType", param.getReference().getName());
      }
      if (param.getReferenceSearchKey() != null) {
        paramObj.put("referenceSearchKeyId", param.getReferenceSearchKey().getId());
      }

      paramObj.put("isRange", Boolean.TRUE.equals(param.isRange()));
      paramObj.put("length", param.getLength());
      parameters.put(paramObj);
    }

    result.put("parameters", parameters);
    result.put("parameterCount", parameters.length());
    return result;
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  /**
   * Find an active SFSpec by name.
   *
   * @throws IllegalArgumentException if spec not found
   */
  private SFSpec findSpecByName(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, specName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    if (results.isEmpty()) {
      throw new IllegalArgumentException("Spec not found: " + specName);
    }
    return results.get(0);
  }

  /**
   * Build a JSON representation of an entity, optionally including its fields.
   */
  private JSONObject buildEntityJson(SFEntity entity, boolean includeFields) throws Exception {
    JSONObject obj = new JSONObject();
    obj.put("name", entity.getName());
    obj.put("isIncluded", Boolean.TRUE.equals(entity.isIncluded()));

    // HTTP methods
    JSONArray methods = new JSONArray();
    if (Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID())) {
      methods.put("GET");
    }
    if (Boolean.TRUE.equals(entity.isPost())) {
      methods.put("POST");
    }
    if (Boolean.TRUE.equals(entity.isPut())) {
      methods.put("PUT");
    }
    if (Boolean.TRUE.equals(entity.isPatch())) {
      methods.put("PATCH");
    }
    if (Boolean.TRUE.equals(entity.isDelete())) {
      methods.put("DELETE");
    }
    obj.put("methods", methods);

    if (includeFields) {
      obj.put("fields", buildFieldsArray(entity.getId()));
    }

    return obj;
  }

  /**
   * Build the fields array for a given entity, resolving AD_Column metadata
   * to provide type information, selector hints, and default values.
   */
  private JSONArray buildFieldsArray(String entityId) throws Exception {
    OBCriteria<SFField> criteria = OBDal.getInstance().createCriteria(SFField.class);
    criteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    criteria.add(Restrictions.eq(SFField.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
    criteria.addOrder(Order.asc(SFField.PROPERTY_SEQNO));
    List<SFField> fields = criteria.list();

    JSONArray arr = new JSONArray();
    for (SFField field : fields) {
      Column column = field.getADColumn();
      if (column == null) {
        continue;
      }

      String refId = column.getReference() != null
          ? (String) column.getReference().getId() : null;

      JSONObject fieldObj = new JSONObject();
      fieldObj.put("name", column.getDBColumnName());
      fieldObj.put("label", column.getName());
      fieldObj.put("type", mapReferenceToType(refId));
      fieldObj.put("readOnly", Boolean.TRUE.equals(field.isReadOnly()));
      fieldObj.put("required", column.isMandatory());

      // Include default value if present
      String defaultValue = field.getDefaultValue();
      if (defaultValue != null && !defaultValue.isEmpty()) {
        fieldObj.put("defaultValue", defaultValue);
      }

      // Selector info for FK references
      boolean hasSelector = refId != null && SELECTOR_REFS.contains(refId);
      if (hasSelector) {
        fieldObj.put("hasSelector", true);
        fieldObj.put("selectorType", mapSelectorType(refId));
      }

      arr.put(fieldObj);
    }
    return arr;
  }

  /**
   * Map AD_Reference_ID to a simple type name.
   * Mirrors the mapping in NeoServlet for consistency.
   */
  private String mapReferenceToType(String refId) {
    if (refId == null) {
      return "string";
    }
    switch (refId) {
      case "10": case "14": case "34":
        return "string";
      case "11": case "22": case "29": case "12":
      case "800008": case "800019":
        return "number";
      case "20":
        return "boolean";
      case "15":
        return "date";
      case "16":
        return "datetime";
      case "24":
        return "time";
      case "28":
        return "button";
      case "17":
        return "list";
      case "13":
        return "id";
      default:
        return "string";
    }
  }

  /**
   * Map AD_Reference_ID to a selector type label.
   */
  private String mapSelectorType(String refId) {
    if (refId == null) {
      return null;
    }
    switch (refId) {
      case "19": return "TableDir";
      case "18": return "Table";
      case "30": return "Search";
      case "95E2A8B50A254B2AAE6774B8C2F28120": return "OBUISEL";
      default: return null;
    }
  }
}
