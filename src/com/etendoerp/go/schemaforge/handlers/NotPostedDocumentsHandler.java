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

import java.util.ArrayList;
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
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.domain.ReferencedItem;

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

  /** AD_Reference ID for the Accounting status selector. */
  static final String ACCOUNTING_STATUS_REF_ID = "D674E22A40DE4CEE931AB96F4CD914F9";

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
    body.put("accountingStatuses", refListOptions(ACCOUNTING_STATUS_REF_ID));
    return NeoResponse.ok(body);
  }

  private NeoResponse buildDocumentGrid(Map<String, String> params) throws Exception {
    Map<String, String> dsParams = buildDsParams(params);
    NoPostedDocumentDS ds = new NoPostedDocumentDS();
    List<Map<String, Object>> rows = ds.getData(dsParams, 0, -1);

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
        j.put("tableId", tableId != null ? tableId : JSONObject.NULL);
      }
      array.put(j);
    }
    JSONObject body = new JSONObject();
    body.put("rows", array);
    body.put("total", array.length());
    return NeoResponse.ok(body);
  }

  /**
   * Translates the flat frontend query params into the SmartClient AdvancedCriteria format
   * expected by {@link NoPostedDocumentDS#getData}.
   */
  private Map<String, String> buildDsParams(Map<String, String> params) throws Exception {
    Map<String, String> dsParams = new HashMap<>();
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    dsParams.put("org", orgId);

    JSONArray criteria = new JSONArray();

    String document = params.get("document");
    if (document != null && !document.isEmpty()) {
      criteria.put(criterion("document", "iEquals", document));
    }

    String accountingStatus = params.get("accountingStatus");
    if (accountingStatus != null && !accountingStatus.isEmpty()) {
      // May be a comma-separated list from multi-select
      String[] statuses = accountingStatus.split(",");
      if (statuses.length == 1) {
        criteria.put(criterion("accountingStatus", "iEquals", accountingStatus.trim()));
      } else {
        JSONArray values = new JSONArray();
        for (String s : statuses) {
          values.put(s.trim());
        }
        criteria.put(criterion("accountingStatus", "inSet", values));
      }
    }

    String dateFrom = params.get("dateFrom");
    if (dateFrom != null && !dateFrom.isEmpty()) {
      criteria.put(criterion("accountingDate", "greaterOrEqual", dateFrom));
    }

    String dateTo = params.get("dateTo");
    if (dateTo != null && !dateTo.isEmpty()) {
      criteria.put(criterion("accountingDate", "lessOrEqual", dateTo));
    }

    if (criteria.length() > 0) {
      JSONObject advancedCriteria = new JSONObject();
      advancedCriteria.put("_constructor", "AdvancedCriteria");
      advancedCriteria.put("operator", "and");
      advancedCriteria.put("criteria", criteria);
      dsParams.put("criteria", advancedCriteria.toString());
    }

    return dsParams;
  }

  private static JSONObject criterion(String field, String operator, Object value) throws Exception {
    JSONObject c = new JSONObject();
    c.put("fieldName", field);
    c.put("operator", operator);
    c.put("value", value);
    return c;
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
    String tableId = body.getString("tableId");
    String recordId = body.getString("recordId");
    DocumentPostingService.PostResult result = postingService.post(tableId, recordId);
    JSONObject resp = new JSONObject();
    resp.put("success", result.ok());
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
      String tableId = row.getString("tableId");
      String recordId = row.getString("recordId");
      DocumentPostingService.PostResult result = postingService.post(tableId, recordId);
      if (result.ok()) {
        ok++;
      }
      JSONObject rowResult = new JSONObject();
      rowResult.put("recordId", recordId);
      rowResult.put("tableId", tableId);
      rowResult.put("success", result.ok());
      rowResult.put("message", result.message());
      results.put(rowResult);
    }

    JSONObject resp = new JSONObject();
    resp.put("ok", ok);
    resp.put("total", total);
    resp.put("results", results);
    resp.put("success", ok == total);
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
    for (ReferencedItem item : ref.getADReferenceValueList()) {
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

  private String getTranslatedName(ReferencedItem item, String lang) {
    try {
      for (var trl : item.getADRefListTrlList()) {
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
