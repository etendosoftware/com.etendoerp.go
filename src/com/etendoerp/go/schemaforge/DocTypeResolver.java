package com.etendoerp.go.schemaforge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Resolves default document types for transactional entities (orders, invoices, etc.).
 * Handles the mapping from table name + transaction direction (IsSOTrx) to the correct
 * C_DocType record, including DocSubTypeSO filtering from tab HQL where clauses.
 */
public class DocTypeResolver {

  private static final Logger log = LogManager.getLogger(DocTypeResolver.class);

  private DocTypeResolver() {
  }

  /**
   * Re-apply the correct document type from tab filters after a callout cascade.
   * Callouts may overwrite C_DocTypeTarget_ID / C_DocType_ID with incorrect values
   * (e.g., a wrong default coming from session). This method queries the canonical
   * default doctype for the tab and forces it back into the body.
   *
   * <p>If {@code clientSubmittedFields} contains the DAL property name for
   * {@code C_DocTypeTarget_ID}, the reapplication is skipped — the user explicitly chose a
   * document type and their selection must be preserved (callouts are already blocked from
   * overriding it via the same set).
   *
   * @param body                 the JSON request body (modified in place)
   * @param adTab                the AD_Tab containing doctype column info
   * @param ctx                  the NeoContext with spec/entity info for doctype resolution
   * @param clientSubmittedFields DAL property names the client explicitly sent; null = no skip
   */
  public static void reapplyDocTypeFromTabFilter(JSONObject body, Tab adTab, NeoContext ctx,
      Set<String> clientSubmittedFields) {
    if (body == null || adTab == null || ctx == null) {
      return;
    }
    try {
      Column docTypeTargetCol = findDocTypeTargetColumn(adTab);
      if (docTypeTargetCol == null) {
        return;
      }
      if (clientSubmittedFields != null && !clientSubmittedFields.isEmpty()) {
        Entity dalEntity = ModelProvider.getInstance()
            .getEntityByTableId(adTab.getTable().getId());
        if (dalEntity != null) {
          Property targetProp = dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID");
          if (targetProp != null && clientSubmittedFields.contains(targetProp.getName())) {
            log.debug("Skipping doctype reapply — client explicitly submitted {}={}",
                targetProp.getName(), body.optString(targetProp.getName()));
            return;
          }
        }
      }
      String correctId = resolveDefaultDocTypeId(docTypeTargetCol, ctx);
      if (correctId != null) {
        applyDocTypeToBody(body, adTab, correctId);
      }
    } catch (Exception e) {
      log.debug("Error reapplying doctype: {}", e.getMessage());
    }
  }

  /** Overload for callers without client-fields context — existing behavior preserved. */
  public static void reapplyDocTypeFromTabFilter(JSONObject body, Tab adTab, NeoContext ctx) {
    reapplyDocTypeFromTabFilter(body, adTab, ctx, null);
  }

  /**
   * Find the C_DocTypeTarget_ID column in the tab's table.
   */
  private static Column findDocTypeTargetColumn(Tab adTab) {
    for (Column col : adTab.getTable().getADColumnList()) {
      if ("C_DocTypeTarget_ID".equalsIgnoreCase(col.getDBColumnName())) {
        return col;
      }
    }
    return null;
  }

  /**
   * Apply the resolved doctype ID to both transactionDocument and documentType properties.
   */
  private static void applyDocTypeToBody(JSONObject body, Tab adTab, String docTypeId)
      throws Exception {
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());
    if (dalEntity == null) {
      return;
    }
    Property targetProp = dalEntity.getPropertyByColumnName("C_DocTypeTarget_ID");
    Property typeProp = dalEntity.getPropertyByColumnName("C_DocType_ID");
    if (targetProp != null) {
      body.put(targetProp.getName(), docTypeId);
      log.debug("Reapplied transactionDocument={}", docTypeId);
    }
    if (typeProp != null) {
      body.put(typeProp.getName(), docTypeId);
      log.debug("Reapplied documentType={}", docTypeId);
    }
  }

  /**
   * Resolve a default C_DocType ID for a column that references the C_DocType table.
   * Returns null if the column does not reference C_DocType or no suitable default is found.
   */
  static String resolveDefaultDocTypeId(Column col, NeoContext ctx) {
    String colName = col.getDBColumnName().toUpperCase();
    if (!colName.endsWith("_ID") || !colName.contains("DOCTYPE")) {
      return null;
    }

    try {
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String isSOTrx = resolveIsSOTrxDefault(col.getTable(), ctx);
      String docBaseType = resolveDocBaseType(col.getTable().getDBTableName(), isSOTrx);
      if (docBaseType == null) {
        log.debug("Could not determine DocBaseType for table {} — skipping doctype resolution",
            col.getTable().getDBTableName());
        return null;
      }

      String[] subTypeFilters = parseSubTypeFilters(ctx);
      return queryDefaultDocType(clientId, docBaseType, isSOTrx,
          subTypeFilters[0], subTypeFilters[1], colName);

    } catch (Exception e) {
      log.debug("Could not resolve default doctype for {}: {}", colName, e.getMessage());
    }
    return null;
  }

  /**
   * Parse DocSubTypeSO filter and exclude values from the tab's HQL where clause.
   * Returns a two-element array: [subTypeFilter, subTypeExclude], either may be null.
   */
  private static String[] parseSubTypeFilters(NeoContext ctx) {
    String subTypeFilter = null;
    String subTypeExclude = null;
    if (ctx != null && ctx.getSfEntity() != null && ctx.getSfEntity().getADTab() != null) {
      String tabWhere = ctx.getSfEntity().getADTab().getHqlwhereclause();
      if (tabWhere != null) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("sOSubType\\s+NOT\\s+LIKE\\s+'(\\w+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(tabWhere);
        if (m.find()) {
          subTypeExclude = m.group(1);
        } else {
          m = java.util.regex.Pattern
              .compile("sOSubType\\s+LIKE\\s+'(\\w+)'",
                  java.util.regex.Pattern.CASE_INSENSITIVE)
              .matcher(tabWhere);
          if (m.find()) {
            subTypeFilter = m.group(1);
          }
        }
      }
    }
    return new String[] { subTypeFilter, subTypeExclude };
  }

  /**
   * Query the C_DocType table for the default document type matching the given criteria.
   */
  private static String queryDefaultDocType(String clientId, String docBaseType,
      String isSOTrx, String subTypeFilter, String subTypeExclude, String colName)
      throws Exception {
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT dt.C_DocType_ID FROM C_DocType dt ");
    sql.append("WHERE dt.IsActive = 'Y' ");
    sql.append("AND dt.AD_Client_ID = ? ");
    sql.append("AND dt.DocBaseType = ? ");
    sql.append("AND dt.IsSOTrx = ? ");
    sql.append("AND (dt.AD_Org_ID = '0' OR AD_ISORGINCLUDED(?, dt.AD_Org_ID, ?) <> '-1') ");

    if (subTypeFilter != null) {
      sql.append("AND dt.DocSubTypeSO = ? ");
    } else if (subTypeExclude != null) {
      sql.append("AND (dt.DocSubTypeSO IS NULL OR dt.DocSubTypeSO != ?) ");
    }
    sql.append("ORDER BY dt.IsDefault DESC, dt.Name ASC");

    try (PreparedStatement ps = OBDal.getInstance().getConnection(false)
        .prepareStatement(sql.toString())) {
      int paramIndex = 1;
      ps.setString(paramIndex++, clientId);
      ps.setString(paramIndex++, docBaseType);
      ps.setString(paramIndex++, isSOTrx);
      ps.setString(paramIndex++, orgId);
      ps.setString(paramIndex++, clientId);
      if (subTypeFilter != null) {
        ps.setString(paramIndex, subTypeFilter);
      } else if (subTypeExclude != null) {
        ps.setString(paramIndex, subTypeExclude);
      }

      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String docTypeId = rs.getString(1);
          log.debug("Resolved default doctype for {} (DocBaseType={}, IsSOTrx={}): {}",
              colName, docBaseType, isSOTrx, docTypeId);
          return docTypeId;
        }
      }
    }

    log.debug("No matching doctype found for {} (DocBaseType={}, IsSOTrx={})",
        colName, docBaseType, isSOTrx);
    return null;
  }

  /**
   * Determine the IsSOTrx default value from the table's columns.
   */
  private static String resolveIsSOTrxDefault(
      org.openbravo.model.ad.datamodel.Table table, NeoContext ctx) {
    for (Column c : table.getADColumnList()) {
      if ("IsSOTrx".equalsIgnoreCase(c.getDBColumnName())) {
        return resolveIsSOTrxFromColumn(c, ctx);
      }
    }
    return "Y";
  }

  /**
   * Resolve the IsSOTrx value from the column's default expression.
   */
  private static String resolveIsSOTrxFromColumn(Column col, NeoContext ctx) {
    String defaultVal = col.getDefaultValue();
    if (defaultVal == null || defaultVal.trim().isEmpty()) {
      return "Y";
    }
    defaultVal = defaultVal.trim();

    String literal = parseIsSOTrxLiteral(defaultVal);
    if (literal != null) {
      return literal;
    }

    if (defaultVal.contains("@") && ctx != null) {
      String resolved = resolveIsSOTrxFromContext(defaultVal, ctx);
      if (resolved != null) {
        return resolved;
      }
    }

    return "Y";
  }

  /**
   * Parse a literal IsSOTrx value. Returns "Y" or "N" if recognized, null otherwise.
   */
  private static String parseIsSOTrxLiteral(String value) {
    if ("Y".equals(value) || "'Y'".equals(value)) {
      return "Y";
    }
    if ("N".equals(value) || "'N'".equals(value)) {
      return "N";
    }
    return null;
  }

  /**
   * Resolve an IsSOTrx context variable expression via Utility.getDefault.
   */
  private static String resolveIsSOTrxFromContext(String defaultVal, NeoContext ctx) {
    try {
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(ctx.getObContext());
      DalConnectionProvider conn = new DalConnectionProvider(false);
      String windowId = resolveWindowId(ctx.getSfEntity());
      String resolved = Utility.getDefault(conn, vars, "IsSOTrx", defaultVal, windowId, "");
      if ("Y".equals(resolved) || "N".equals(resolved)) {
        log.debug("Resolved IsSOTrx context var '{}' to '{}'", defaultVal, resolved);
        return resolved;
      }
    } catch (Exception e) {
      log.debug("Could not resolve IsSOTrx context var: {}", e.getMessage());
    }
    return null;
  }

  /**
   * Map a table name and transaction type to the corresponding document base type.
   */
  static String resolveDocBaseType(String tableName, String isSOTrx) {
    boolean isSales = "Y".equals(isSOTrx);
    String upper = tableName.toUpperCase();

    if ("C_ORDER".equals(upper)) {
      return isSales ? "SOO" : "POO";
    }
    if ("C_INVOICE".equals(upper)) {
      return isSales ? "ARI" : "API";
    }
    if ("M_INOUT".equals(upper)) {
      return isSales ? "MMS" : "MMR";
    }
    if ("C_PAYMENT".equals(upper)) {
      return isSales ? "ARR" : "APP";
    }
    if ("M_MOVEMENT".equals(upper)) {
      return "MMM";
    }
    if ("M_INVENTORY".equals(upper)) {
      return "MMI";
    }
    if ("C_BANKSTATEMENT".equals(upper)) {
      return "CMB";
    }

    return null;
  }

  /**
   * Resolve the window ID from the SFEntity -> SFSpec -> AD_Window chain.
   * Returns empty string if no window is linked.
   */
  private static String resolveWindowId(com.etendoerp.go.schemaforge.data.SFEntity sfEntity) {
    try {
      com.etendoerp.go.schemaforge.data.SFSpec spec = sfEntity.getETGOSFSpec();
      if (spec != null) {
        org.openbravo.model.ad.ui.Window window = spec.getADWindow();
        if (window != null) {
          return window.getId();
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve window ID: {}", e.getMessage());
    }
    return "";
  }
}
