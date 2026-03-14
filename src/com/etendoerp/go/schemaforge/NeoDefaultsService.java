package com.etendoerp.go.schemaforge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Service for resolving default values when creating a new record via NEO Headless.
 *
 * Reads AD_Column.DefaultValue for each included field and resolves:
 * - Literal values ("DR", "N", "0")
 * - Session context variables (@#AD_Org_ID@, @#Date@, etc.)
 * - IsActive = true (always)
 * - Link-to-parent columns (from parentId query parameter)
 * - Sequence/DocumentNo previews (Phase 2)
 *
 * Endpoint: GET /sws/neo/{specName}/{entityName}/defaults
 */
public class NeoDefaultsService {

  private static final Logger log = LogManager.getLogger(NeoDefaultsService.class);
  private static final Pattern CONTEXT_VAR_PATTERN = Pattern.compile("@([^@]+)@");
  private static final String DATE_FORMAT = "yyyy-MM-dd";

  private NeoDefaultsService() {
  }

  /**
   * Resolve default values for all included fields of an entity.
   *
   * @param ctx      the NeoContext with spec/entity/tab info
   * @param parentId optional parent record ID for child entities
   * @return NeoResponse with defaults map and metadata
   */
  public static NeoResponse resolveDefaults(NeoContext ctx, String parentId) {
    try {
      OBContext.setAdminMode();
      try {
        JSONObject defaults = new JSONObject();
        JSONArray unresolvedFields = new JSONArray();
        JSONArray sequenceFields = new JSONArray();

        // Build context variables from OBContext
        Map<String, String> contextVars = buildContextVars(ctx.getObContext());

        // Load all active, included SFField records for this entity
        OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
        fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id",
            ctx.getSfEntity().getId()));
        fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
        fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
        List<SFField> fields = fieldCrit.list();

        for (SFField sfField : fields) {
          Column adColumn = sfField.getADColumn();
          if (adColumn == null) {
            continue;
          }

          String dbColumnName = adColumn.getDBColumnName();
          String cleanName = NeoCalloutService.toCleanFieldName(dbColumnName);

          try {
            Object resolvedValue = resolveFieldDefault(adColumn, parentId, contextVars);
            if (resolvedValue != null) {
              defaults.put(cleanName, resolvedValue);

              // Track sequence fields
              if (isSequenceField(adColumn) && resolvedValue instanceof String
                  && ((String) resolvedValue).startsWith("<")) {
                sequenceFields.put(cleanName);
              }
            }
          } catch (Exception e) {
            log.debug("Could not resolve default for column {}: {}",
                dbColumnName, e.getMessage());
            unresolvedFields.put(cleanName);
          }
        }

        // Build response
        JSONObject response = new JSONObject();
        response.put("defaults", defaults);

        JSONObject metadata = new JSONObject();
        metadata.put("unresolvedFields", unresolvedFields);
        metadata.put("sequenceFields", sequenceFields);
        response.put("metadata", metadata);

        return NeoResponse.ok(response);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error resolving defaults: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Failed to resolve defaults: " + e.getMessage());
    }
  }

  /**
   * Resolve the default value for a single AD_Column.
   *
   * @return the resolved value, or null if no default is configured
   */
  private static Object resolveFieldDefault(Column adColumn, String parentId,
      Map<String, String> contextVars) {

    String dbColumnName = adColumn.getDBColumnName();

    // IsActive always defaults to true
    if ("IsActive".equalsIgnoreCase(dbColumnName)) {
      return true;
    }

    // Link-to-parent columns use the parentId
    if (adColumn.isLinkToParentColumn() && parentId != null && !parentId.isEmpty()) {
      return parentId;
    }

    // Sequence/DocumentNo fields
    if (isSequenceField(adColumn)) {
      String preview = resolveSequencePreview(adColumn, contextVars);
      if (preview != null) {
        return preview;
      }
    }

    // Get the default value expression from AD_Column
    String defaultExpr = adColumn.getDefaultValue();
    if (defaultExpr == null || defaultExpr.trim().isEmpty()) {
      return null;
    }

    defaultExpr = defaultExpr.trim();

    // SQL expressions (Phase 3 — skip for now)
    if (defaultExpr.startsWith("@SQL=")) {
      return null; // Will be added to unresolvedFields by caller
    }

    // Check for @#Date@ specifically
    if ("@#Date@".equals(defaultExpr)) {
      return new SimpleDateFormat(DATE_FORMAT).format(new Date());
    }

    // Check if it contains context variables (@...@)
    if (defaultExpr.contains("@")) {
      return resolveContextExpression(defaultExpr, contextVars);
    }

    // Literal value — return as-is
    return defaultExpr;
  }

  /**
   * Resolve context variable expressions like @#AD_Org_ID@, @AD_CLIENT_ID@.
   * Supports multiple comma-separated values — returns the first that resolves.
   */
  private static String resolveContextExpression(String expression,
      Map<String, String> contextVars) {

    // Handle comma-separated alternatives
    String[] alternatives = expression.split("[,;]");
    for (String alt : alternatives) {
      alt = alt.trim();
      Matcher matcher = CONTEXT_VAR_PATTERN.matcher(alt);
      if (matcher.matches()) {
        String varName = matcher.group(1);
        String value = contextVars.get(varName);
        if (value != null && !value.isEmpty()) {
          return value;
        }
      }
    }

    // If no pure variable match, try inline replacement
    Matcher matcher = CONTEXT_VAR_PATTERN.matcher(expression);
    StringBuffer result = new StringBuffer();
    boolean allResolved = true;

    while (matcher.find()) {
      String varName = matcher.group(1);
      String value = contextVars.get(varName);
      if (value != null) {
        matcher.appendReplacement(result, Matcher.quoteReplacement(value));
      } else {
        allResolved = false;
        break;
      }
    }

    if (allResolved) {
      matcher.appendTail(result);
      return result.toString();
    }

    return null;
  }

  /**
   * Check if a column is a sequence/DocumentNo field.
   */
  private static boolean isSequenceField(Column adColumn) {
    String dbName = adColumn.getDBColumnName();
    return "DocumentNo".equalsIgnoreCase(dbName)
        || "Value".equalsIgnoreCase(dbName) && Boolean.TRUE.equals(adColumn.isUseAutomaticSequence());
  }

  /**
   * Generate a preview of the next sequence value without consuming it.
   * Returns the value wrapped in angle brackets (e.g., "&lt;1000234&gt;").
   */
  private static String resolveSequencePreview(Column adColumn, Map<String, String> contextVars) {
    try {
      // For now, return a placeholder indicating auto-generation
      // Full Utility.getDocumentNo() integration requires more context
      // (document type, transaction type) that is not available at init time
      return "<auto>";
    } catch (Exception e) {
      log.debug("Could not generate sequence preview for {}: {}",
          adColumn.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  /**
   * Build a map of context variables from OBContext for resolving @...@ expressions.
   */
  private static Map<String, String> buildContextVars(OBContext obContext) {
    Map<String, String> vars = new HashMap<>();
    if (obContext == null) {
      return vars;
    }

    // Organization
    String orgId = obContext.getCurrentOrganization().getId();
    vars.put("#AD_Org_ID", orgId);
    vars.put("AD_Org_ID", orgId);
    vars.put("AD_ORG_ID", orgId);

    // Client
    String clientId = obContext.getCurrentClient().getId();
    vars.put("#AD_Client_ID", clientId);
    vars.put("AD_Client_ID", clientId);
    vars.put("AD_CLIENT_ID", clientId);

    // User
    String userId = obContext.getUser().getId();
    vars.put("#AD_User_ID", userId);
    vars.put("AD_User_ID", userId);

    // Role
    String roleId = obContext.getRole().getId();
    vars.put("#AD_Role_ID", roleId);
    vars.put("AD_Role_ID", roleId);

    // Warehouse
    if (obContext.getWarehouse() != null) {
      String warehouseId = obContext.getWarehouse().getId();
      vars.put("#M_Warehouse_ID", warehouseId);
      vars.put("M_Warehouse_ID", warehouseId);
    }

    // Language
    vars.put("#AD_Language", obContext.getLanguage().getLanguage());

    // Date
    String today = new SimpleDateFormat(DATE_FORMAT).format(new Date());
    vars.put("#Date", today);

    return vars;
  }
}
