/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.ListTrl;
import org.openbravo.model.ad.domain.Reference;

import com.etendoerp.bulk.posting.datasource.NoPostedDocumentDS;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Serves the Not Posted Documents window.
 *
 * <p>CRUD endpoint:
 * <ul>
 *   <li>Query param {@code _mode=filter-options} → returns dropdown option lists for Document type
 *       and Accounting status from AD_Ref_List.</li>
 *   <li>Otherwise → returns the unposted document grid by delegating to
 *       {@link NoPostedDocumentDS}. Each row is enriched with {@code tableId} resolved from
 *       {@code documentType} via {@link #DOCUMENT_TYPE_TO_TABLE_ID}, so the frontend can call the
 *       Post action without knowing the table.</li>
 * </ul>
 *
 * <p>ACTION endpoint:
 * <ul>
 *   <li>{@code post} → body {@code {tableId, recordId}} → posts a single document.</li>
 *   <li>{@code bulk-post} → body {@code {rows:[{tableId,recordId,label}]}} → posts each row,
 *       returns per-row results.</li>
 * </ul>
 *
 * <p>{@code @Named} only — never a normal CDI scope (see CLAUDE.md NeoHandler rules).</p>
 */
@Named("not-posted-documents")
public class NotPostedDocumentsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(NotPostedDocumentsHandler.class);

  /** AD_Reference ID for the Document type selector (ETBLKP_Documents). */
  static final String DOCUMENT_TYPE_REF_ID = "DE94535164E741AB9B1A560EF3F72854";

  /** AD_Reference ID for the Accounting status selector (ETBLKP_All_Accounting Status). */
  static final String ACCOUNTING_STATUS_REF_ID = "D431058F6B7345598D1E0709DFF3B5DD";

  /**
   * Curated subset of accounting statuses shown in the UI filter.
   * Each entry is { value, fallbackLabel }. "E,C" is a composite: both Error (E) and
   * Error-No-Cost (C) are matched server-side when this option is selected.
   *
   * Excluded from UI (all present in ETBLKP_All_Accounting Status but not actionable
   * from the Not Posted Documents view):
   *   y  = Post Prepared        d  = Disabled For Background
   *   DT = No Document Type     L  = Document Locked
   *   AD = No Accounting Date   Y  = Posted
   *   D  = Document Disabled    NO = No Related PO
   *   l  = Pending Refresh      c  = Not Convertible (no rate)
   *   b  = Not Balanced         NC = Cost Not Calculated
   *   T  = Table Disabled
   */
  private static final String[][] ACCOUNTING_STATUS_FILTER_OPTIONS = {
      { "N",   "Unposted"        },
      { "E,C", "Error"           },   // E = Error, C = Error-No-Cost (unified)
      { "i",   "Invalid Account" },
      { "p",   "Period Closed"   },
  };

  private static final String KEY_TABLE_ID = "tableId";
  private static final String KEY_ACCOUNTING_STATUS = "accountingStatus";
  private static final String KEY_RECORD_ID = "recordId";
  private static final String KEY_SUCCESS = "success";

  /**
   * Maps the {@code documentType} string returned by {@link NoPostedDocumentDS} to the
   * corresponding {@code AD_Table_ID}. Used to enrich grid rows so the frontend can call
   * {@code POST /action/post} with {@code {tableId, recordId}} without extra lookups.
   *
   * <p>Values come from {@code NoPostedConstans} string constants (extracted from bytecode) and
   * the AD_Table query: {@code SELECT tablename, ad_table_id FROM ad_table WHERE tablename IN
   * ('C_Invoice','M_InOut','M_Movement','A_Amortization','GL_Journal','M_Inventory')}.</p>
   */
  private static final Map<String, String> DOCUMENT_TYPE_TO_TABLE_ID = new HashMap<>();

  static {
    DOCUMENT_TYPE_TO_TABLE_ID.put("Sales Invoice", "318");      // C_Invoice
    DOCUMENT_TYPE_TO_TABLE_ID.put("Purchase Invoice", "318");   // C_Invoice
    DOCUMENT_TYPE_TO_TABLE_ID.put("Invoice", "318");            // C_Invoice
    DOCUMENT_TYPE_TO_TABLE_ID.put("Goods Shipment", "319");     // M_InOut
    DOCUMENT_TYPE_TO_TABLE_ID.put("Goods Receipt", "319");      // M_InOut
    DOCUMENT_TYPE_TO_TABLE_ID.put("ShipmentInOut", "319");      // M_InOut
    DOCUMENT_TYPE_TO_TABLE_ID.put("Return to Vendor Shipment", "319"); // M_InOut
    DOCUMENT_TYPE_TO_TABLE_ID.put("Return Material Receipt", "319");   // M_InOut
    DOCUMENT_TYPE_TO_TABLE_ID.put("Movement", "323");           // M_Movement
    DOCUMENT_TYPE_TO_TABLE_ID.put("Amortization", "800060");    // A_Amortization
    DOCUMENT_TYPE_TO_TABLE_ID.put("GL Journal", "224");         // GL_Journal
    DOCUMENT_TYPE_TO_TABLE_ID.put("Inventory", "321");          // M_Inventory
  }

  @Inject
  private DocumentPostingService postingService;

  @Override
  public NeoResponse handle(NeoContext context) {
    try {
      if (context.getEndpointType() == NeoEndpointType.ACTION) {
        return handleAction(context);
      }
      if (context.getEndpointType() == NeoEndpointType.CRUD) {
        return handleCrud(context);
      }
      return null;
    } catch (Exception e) {
      log.error("NotPostedDocumentsHandler error", e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  // ── CRUD ─────────────────────────────────────────────────────────────────────

  private NeoResponse handleCrud(NeoContext context) throws Exception {
    Map<String, String> params = context.getQueryParams();
    if ("filter-options".equals(params.get("_mode"))) {
      return buildFilterOptions();
    }
    return buildDocumentGrid(params);
  }

  private NeoResponse buildFilterOptions() throws Exception {
    JSONObject body = new JSONObject();
    body.put("documentTypes", refListOptions(DOCUMENT_TYPE_REF_ID));
    body.put("accountingStatuses", buildAccountingStatusOptions());
    return NeoResponse.ok(body);
  }

  private JSONArray buildAccountingStatusOptions() throws Exception {
    Map<String, String> labels = refListLabels(ACCOUNTING_STATUS_REF_ID);
    JSONArray arr = new JSONArray();
    for (String[] opt : ACCOUNTING_STATUS_FILTER_OPTIONS) {
      String firstKey = opt[0].split(",")[0];
      JSONObject o = new JSONObject();
      o.put("value", opt[0]);
      o.put("label", labels.getOrDefault(firstKey, opt[1]));
      arr.put(o);
    }
    return arr;
  }

  private Map<String, String> refListLabels(String referenceId) throws Exception {
    Map<String, String> result = new HashMap<>();
    Reference ref = OBDal.getInstance().get(Reference.class, referenceId);
    if (ref == null) return result;
    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    for (org.openbravo.model.ad.domain.List item : ref.getADListList()) {
      if (!item.isActive()) continue;
      String label = getTranslatedName(item, lang);
      result.put(item.getSearchKey(), label != null ? label : item.getName());
    }
    return result;
  }

  /** Thin subclass that promotes {@code getData} from protected to package-accessible. */
  private static class AccessibleDS extends NoPostedDocumentDS {
    List<Map<String, Object>> fetchAll(Map<String, String> p) {
      return getData(p, 0, Integer.MAX_VALUE);
    }
  }

  private NeoResponse buildDocumentGrid(Map<String, String> params) throws Exception {
    Map<String, String> dsParams = buildDsParams(params);
    List<Map<String, Object>> rows = new AccessibleDS().fetchAll(dsParams);

    JSONArray array = new JSONArray();
    for (Map<String, Object> row : rows) {
      JSONObject j = new JSONObject();
      for (Map.Entry<String, Object> e : row.entrySet()) {
        j.put(e.getKey(), e.getValue() != null ? e.getValue() : JSONObject.NULL);
      }
      // Enrich with tableId so the frontend can call POST /action/post without extra lookups.
      Object docType = row.get("documentType");
      if (docType instanceof String) {
        String tableId = DOCUMENT_TYPE_TO_TABLE_ID.get(docType);
        j.put(KEY_TABLE_ID, tableId != null ? tableId : JSONObject.NULL);
      }
      array.put(j);
    }
    JSONObject body = new JSONObject();
    body.put("rows", array);
    body.put("total", array.length());
    return NeoResponse.ok(body);
  }

  /**
   * Translates frontend query params into the flat param map expected by
   * {@link NoPostedDocumentDS#getData}. Field names are read directly from the params
   * map by the datasource — no AdvancedCriteria wrapping.
   *
   * <p>Key mapping (datasource param → frontend param):
   * <ul>
   *   <li>{@code _org}            ← current OBContext organisation</li>
   *   <li>{@code accounting_status} ← {@code accountingStatus} (JSON string array)</li>
   *   <li>{@code document}        ← {@code document}</li>
   *   <li>{@code DateFrom}        ← {@code dateFrom}</li>
   *   <li>{@code DateTo}          ← {@code dateTo}</li>
   * </ul>
   */
  private Map<String, String> buildDsParams(Map<String, String> params) throws Exception {
    Map<String, String> dsParams = new HashMap<>();
    dsParams.put("_org", OBContext.getOBContext().getCurrentOrganization().getId());

    String document = params.get("document");
    if (document != null && !document.isEmpty()) {
      dsParams.put("document", document);
    }

    String accountingStatus = params.get(KEY_ACCOUNTING_STATUS);
    if (accountingStatus != null && !accountingStatus.isEmpty()) {
      JSONArray arr = new JSONArray();
      for (String s : accountingStatus.split(",")) {
        arr.put(s.trim());
      }
      dsParams.put("accounting_status", arr.toString());
    }

    String dateFrom = params.get("dateFrom");
    if (dateFrom != null && !dateFrom.isEmpty()) {
      dsParams.put("DateFrom", dateFrom);
    }

    String dateTo = params.get("dateTo");
    if (dateTo != null && !dateTo.isEmpty()) {
      dsParams.put("DateTo", dateTo);
    }

    return dsParams;
  }

  // ── ACTION ────────────────────────────────────────────────────────────────────

  private NeoResponse handleAction(NeoContext context) throws Exception {
    String action = context.getFieldName();
    JSONObject requestBody = context.getRequestBody();

    if ("post".equals(action)) {
      return handleSinglePost(requestBody);
    }
    if ("bulk-post".equals(action)) {
      return handleBulkPost(requestBody);
    }
    return null;
  }

  private NeoResponse handleSinglePost(JSONObject body) throws Exception {
    String tableId = body.getString(KEY_TABLE_ID);
    String recordId = body.getString(KEY_RECORD_ID);
    DocumentPostingService.PostResult result = postingService.post(tableId, recordId);
    JSONObject resp = new JSONObject();
    resp.put(KEY_SUCCESS, result.ok());
    resp.put("message", result.message());
    return result.ok() ? NeoResponse.ok(resp) : NeoResponse.error(422, resp.toString());
  }

  private NeoResponse handleBulkPost(JSONObject body) throws Exception {
    JSONArray rows = body.getJSONArray("rows");
    JSONArray results = new JSONArray();
    int ok = 0;
    int total = rows.length();

    for (int i = 0; i < total; i++) {
      JSONObject row = rows.getJSONObject(i);
      String tableId = row.getString(KEY_TABLE_ID);
      String recordId = row.getString(KEY_RECORD_ID);
      DocumentPostingService.PostResult result = postingService.post(tableId, recordId);
      if (result.ok()) {
        ok++;
      }
      JSONObject rowResult = new JSONObject();
      rowResult.put(KEY_RECORD_ID, recordId);
      rowResult.put(KEY_TABLE_ID, tableId);
      rowResult.put(KEY_SUCCESS, result.ok());
      rowResult.put("message", result.message());
      results.put(rowResult);
    }

    JSONObject resp = new JSONObject();
    resp.put("ok", ok);
    resp.put("total", total);
    resp.put("results", results);
    resp.put(KEY_SUCCESS, ok == total);
    return NeoResponse.ok(resp);
  }

  // ── AD_Ref_List helpers ───────────────────────────────────────────────────────

  private JSONArray refListOptions(String referenceId) throws Exception {
    JSONArray options = new JSONArray();
    Reference ref = OBDal.getInstance().get(Reference.class, referenceId);
    if (ref == null) {
      return options;
    }
    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    for (org.openbravo.model.ad.domain.List item : ref.getADListList()) {
      if (!item.isActive()) {
        continue;
      }
      JSONObject opt = new JSONObject();
      opt.put("value", item.getSearchKey());
      String label = getTranslatedName(item, lang);
      opt.put("label", label != null ? label : item.getName());
      options.put(opt);
    }
    return options;
  }

  private String getTranslatedName(org.openbravo.model.ad.domain.List item, String lang) {
    try {
      for (ListTrl trl : item.getADListTrlList()) {
        if (lang.equals(trl.getLanguage().getLanguage())) {
          String name = trl.getName();
          if (name != null && !name.isEmpty()) {
            return name;
          }
        }
      }
    } catch (Exception e) {
      log.debug("No translation for ref list item {}", item.getId());
    }
    return null;
  }

  /** Package-private seam for unit tests. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }
}
