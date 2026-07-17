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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  /**
   * Maps each document type code (AD_Ref_List.value for {@link #DOCUMENT_TYPE_REF_ID}) to the
   * {@code AD_Table_ID} of its backing Etendo table.
   *
   * <p>This map drives the dynamic document-type filter: at request time
   * {@link #refListDocumentTypes()} queries {@code c_acctschema_table} for all tables with
   * {@code isactive='Y'}, and only shows codes whose table appears in that result — meaning any
   * new document type whose module registers an accounting schema entry is picked up
   * automatically, with no code change required here.
   *
   * <p>Source: {@code SELECT tablename, ad_table_id FROM ad_table WHERE tablename IN (...)}.
   * Full matrix: {@code docs/generated-custom-windows/not-posted-documents.md}.
   */
  private static final String FIN_PAYMENT_TABLE_ID = "D1A97202E832470285C9B1EB026D54E2";

  private static final Map<String, String> DOCUMENT_TYPE_CODE_TO_TABLE_ID = new HashMap<>();

  static {
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("A",   "800060");                                // A_Amortization
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("BMP", "325");                                   // M_Production
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("BS",  "D4C23A17190649E7B78F55A05AF3438C");      // FIN_BankStatement
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("CA",  "D022B92163074E5E82449C8E0B5AFDF6");      // M_CostAdjustment
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("DD",  "30721072789F410E9606D2235CB2A226");       // FIN_Doubtful_Debt
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("GLJ", "224");                                   // GL_Journal
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("GR",  "319");                                   // M_InOut
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("GS",  "319");                                   // M_InOut
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("IC",  "800168");                                // M_Internal_Consumption
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("INV", "321");                                   // M_Inventory
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("LC",  "082F967CDF7245EB9A150941F326C45C");      // M_LandedCost
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("LCC", "55A984C314FD4C4FB5E7C32DE36BB07B");      // M_LC_Cost
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("MI",  "472");                                   // M_MatchInv
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("M",   "323");                                   // M_Movement
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("PIN", FIN_PAYMENT_TABLE_ID);                    // FIN_Payment
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("POT", FIN_PAYMENT_TABLE_ID);                    // FIN_Payment
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("PI",  "318");                                   // C_Invoice
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("R",   "B1B7075C46934F0A9FD4C4D0F1457B42");      // FIN_Reconciliation
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("RMR", "319");                                   // M_InOut
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("RVS", "319");                                   // M_InOut
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("SI",  "318");                                   // C_Invoice
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("T",   "4D8C3B3C31D1410DA046140C9F024D17");      // FIN_Finacc_Transaction
    DOCUMENT_TYPE_CODE_TO_TABLE_ID.put("WE",  "486");                                   // S_TimeExpense
  }

  /**
   * Document type codes that are <em>never</em> shown in the filter dropdown, regardless of
   * {@code c_acctschema_table} state. These tables exist in the accounting schema but the APRM
   * module intentionally disables direct bulk-posting on them ({@code POSTED = 'D'} on all
   * documents) — accounting flows through {@code FIN_Finacc_Transaction} ({@code T}) instead.
   *
   * <p>{@code BMP}, {@code DD}, {@code LC}, {@code LCC} and {@code CA} are globally excluded by
   * product decision (ETP-4452), for ALL tenants, even though some tenants (e.g. QA Testing,
   * F&amp;B International Group) have them actively configured for posting — the tradeoff of
   * hiding those legitimate documents was accepted by the product owner.
   *
   * <p>To re-enable any of these (e.g. if APRM posting is later reconfigured), remove its code
   * from this set.
   */
  private static final Set<String> APRM_DISABLED_TYPES = new HashSet<>(Arrays.asList(
      "BS",   // FIN_BankStatement       — in c_acctschema_table but all docs posted='D'
      "PIN",  // FIN_Payment             — in c_acctschema_table but 99.9% posted='D'
      "POT",  // FIN_Payment             — same as PIN
      "R",    // FIN_Reconciliation      — in c_acctschema_table but ~89% posted='D'
      "BMP",  // M_Production            — globally excluded, ETP-4452
      "DD",   // FIN_Doubtful_Debt       — globally excluded, ETP-4452
      "LC",   // M_LandedCost            — globally excluded, ETP-4452
      "LCC",  // M_LC_Cost               — globally excluded, ETP-4452
      "CA"    // M_CostAdjustment        — globally excluded, ETP-4452
  ));

  private static final String KEY_TABLE_ID = "tableId";
  private static final String KEY_ACCOUNTING_STATUS = "accountingStatus";
  private static final String KEY_RECORD_ID = "recordId";
  private static final String KEY_SUCCESS = "success";
  private static final String KEY_VALUE = "value";
  private static final String KEY_LABEL = "label";

  /**
   * Maps accounting status search keys to their {@code AD_Ref_List.ad_ref_list_id} (UUID).
   * {@link NoPostedDocumentDS#getGridData} calls {@code getValues(jsonArray, referenceId)} which
   * queries {@code AD_Ref_List} by primary key — so the JSON array must contain UUIDs, not
   * search keys.
   *
   * <p>Source: {@code SELECT ad_ref_list_id, value FROM ad_ref_list
   * WHERE ad_reference_id = 'D431058F6B7345598D1E0709DFF3B5DD' AND isactive = 'Y'}.
   */
  private static final Map<String, String> ACCOUNTING_STATUS_KEY_TO_ID = new HashMap<>();

  /**
   * Default statuses sent when the user applies no accounting-status filter (empty selection =
   * "show all unposted").  Covers the four options exposed in the UI filter.
   */
  private static final List<String> DEFAULT_ACCOUNTING_STATUS_KEYS =
      Arrays.asList("N", "E", "C", "i", "p");

  static {
    ACCOUNTING_STATUS_KEY_TO_ID.put("N",  "D16B6411F4CB4708AE05E7F6E109920E"); // Unposted
    ACCOUNTING_STATUS_KEY_TO_ID.put("E",  "420D49CD77304D32BE49582002C315BE");  // Error
    ACCOUNTING_STATUS_KEY_TO_ID.put("C",  "4AE29BF062D4484E976B1BEEF34A7913");  // Error, No cost
    ACCOUNTING_STATUS_KEY_TO_ID.put("i",  "A12420CC6D4144768EEC57143859EFD6");  // Invalid Account
    ACCOUNTING_STATUS_KEY_TO_ID.put("p",  "D1EAA8BCC3E649C398D4E544282E5292");  // Period Closed
    ACCOUNTING_STATUS_KEY_TO_ID.put("Y",  "B9D7C571ACE54412A454492A7BADB31E");  // Posted
    ACCOUNTING_STATUS_KEY_TO_ID.put("AD", "199C073FE49E4C57B5F9BFCF98187666");  // No Accounting Date
    ACCOUNTING_STATUS_KEY_TO_ID.put("b",  "7D94AAD5D6ED4AB4A0E19C036AB16617");  // Not Balanced
    ACCOUNTING_STATUS_KEY_TO_ID.put("c",  "ED89C605E8A448E5BF6ACEF88A7A4DFD");  // Not Convertible
    ACCOUNTING_STATUS_KEY_TO_ID.put("d",  "7EA1102ED3944934AEB250DB59A1990A");  // Disabled For Background
    ACCOUNTING_STATUS_KEY_TO_ID.put("D",  "249819A05B6E403EA3B238DE369FFADE");  // Document Disabled
    ACCOUNTING_STATUS_KEY_TO_ID.put("DT", "0DCCE34BC1D1470BA91D27FD40C3977E");  // No Document Type
    ACCOUNTING_STATUS_KEY_TO_ID.put("l",  "5D27C2A9DC37492888B36106AFD67206");  // Pending Refresh
    ACCOUNTING_STATUS_KEY_TO_ID.put("L",  "B6B9CD0EC571428BABE2E23AC62AE484");  // Document Locked
    ACCOUNTING_STATUS_KEY_TO_ID.put("NC", "EF3E057A84CD4BE88A9EF57BE9598DA3");  // Cost Not Calculated
    ACCOUNTING_STATUS_KEY_TO_ID.put("NO", "D53EBEA4992F44CD8DEBB19C716B4991");  // No Related PO
    ACCOUNTING_STATUS_KEY_TO_ID.put("T",  "F1D3C6E0594E4BEE9B60C559709A86E1");  // Table Disabled
    ACCOUNTING_STATUS_KEY_TO_ID.put("y",  "0381BEF8BB984A488CCA55B41B10BC1E");  // Post Prepared
  }

  /**
   * Maps the {@code documentType} string returned by {@link NoPostedDocumentDS} to the
   * corresponding {@code AD_Table_ID}. Used to enrich grid rows so the frontend can call
   * {@code POST /action/post} with {@code {tableId, recordId}} without extra lookups.
   *
   * <p>Values come from {@code NoPostedConstans} string constants (extracted from bytecode) and
   * the AD_Table query: {@code SELECT tablename, ad_table_id FROM ad_table WHERE tablename IN
   * ('C_Invoice','M_InOut','M_Movement','A_Amortization','GL_Journal','M_Inventory')}.</p>
   */
  /** Package-private so it can be unit-tested directly (see {@code NotPostedDocumentsHandlerTest}). */
  static final Map<String, String> DOCUMENT_TYPE_TO_TABLE_ID = new HashMap<>();

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
    DOCUMENT_TYPE_TO_TABLE_ID.put("Payment In",     FIN_PAYMENT_TABLE_ID);               // FIN_Payment
    DOCUMENT_TYPE_TO_TABLE_ID.put("Payment Out",    FIN_PAYMENT_TABLE_ID);               // FIN_Payment
    DOCUMENT_TYPE_TO_TABLE_ID.put("Bank Statement", "D4C23A17190649E7B78F55A05AF3438C"); // FIN_BankStatement
    DOCUMENT_TYPE_TO_TABLE_ID.put("Reconciliation", "B1B7075C46934F0A9FD4C4D0F1457B42"); // FIN_Reconciliation
    DOCUMENT_TYPE_TO_TABLE_ID.put("Bill of Materials Production", "325");                                 // M_Production
    DOCUMENT_TYPE_TO_TABLE_ID.put("Doubtful Debt", "30721072789F410E9606D2235CB2A226");                   // FIN_Doubtful_Debt
    DOCUMENT_TYPE_TO_TABLE_ID.put("Landed Cost", "082F967CDF7245EB9A150941F326C45C");                     // M_LandedCost
    DOCUMENT_TYPE_TO_TABLE_ID.put("Landed Cost Cost", "55A984C314FD4C4FB5E7C32DE36BB07B");                // M_LC_Cost
    DOCUMENT_TYPE_TO_TABLE_ID.put("Cost Adjustment", "D022B92163074E5E82449C8E0B5AFDF6");                 // M_CostAdjustment
  }

  /**
   * Table IDs for document types in {@link #APRM_DISABLED_TYPES}, derived at class-load time.
   * Any grid row whose resolved {@code tableId} is in this set is silently dropped — these
   * documents cannot be bulk-posted through this window regardless of their current status.
   */
  private static final Set<String> APRM_DISABLED_TABLE_IDS = new HashSet<>();

  static {
    for (String code : APRM_DISABLED_TYPES) {
      String tableId = DOCUMENT_TYPE_CODE_TO_TABLE_ID.get(code);
      if (tableId != null) {
        APRM_DISABLED_TABLE_IDS.add(tableId);
      }
    }
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
    body.put("documentTypes", refListDocumentTypes());
    body.put("accountingStatuses", buildAccountingStatusOptions());
    return NeoResponse.ok(body);
  }

  /**
   * Returns document types from {@link #DOCUMENT_TYPE_REF_ID} whose backing table is actively
   * configured for accounting ({@code c_acctschema_table.isactive = 'Y'}), excluding
   * {@link #APRM_DISABLED_TYPES}. The check is dynamic — new document types whose modules
   * register a {@code c_acctschema_table} entry are picked up automatically.
   */
  JSONArray refListDocumentTypes() throws Exception {
    Set<String> accountedTableIds = getTablesWithActiveAccounting();
    JSONArray options = new JSONArray();
    Reference ref = OBDal.getInstance().get(Reference.class, DOCUMENT_TYPE_REF_ID);
    if (ref == null) return options;
    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    for (org.openbravo.model.ad.domain.List item : ref.getADListList()) {
      String code = item.getSearchKey();
      String tableId = DOCUMENT_TYPE_CODE_TO_TABLE_ID.get(code);
      boolean enabled = item.isActive()
          && !APRM_DISABLED_TYPES.contains(code)
          && tableId != null
          && accountedTableIds.contains(tableId);
      if (enabled) {
        JSONObject opt = new JSONObject();
        opt.put(KEY_VALUE, code);
        String label = getTranslatedName(item, lang);
        opt.put(KEY_LABEL, label != null ? label : item.getName());
        options.put(opt);
      }
    }
    return options;
  }

  /** Returns the set of {@code AD_Table_ID} values that have at least one active accounting schema entry. */
  @SuppressWarnings("unchecked")
  private Set<String> getTablesWithActiveAccounting() {
    List<Object> rows = OBDal.getInstance().getSession()
        .createNativeQuery(
            "SELECT DISTINCT ad_table_id FROM c_acctschema_table WHERE isactive = 'Y'")
        .list();
    Set<String> ids = new HashSet<>();
    for (Object row : rows) {
      if (row instanceof String) ids.add((String) row);
    }
    return ids;
  }

  private JSONArray buildAccountingStatusOptions() throws Exception {
    Map<String, String> labels = refListLabels(ACCOUNTING_STATUS_REF_ID);
    JSONArray arr = new JSONArray();
    for (String[] opt : ACCOUNTING_STATUS_FILTER_OPTIONS) {
      String firstKey = opt[0].split(",")[0];
      JSONObject o = new JSONObject();
      o.put(KEY_VALUE, opt[0]);
      o.put(KEY_LABEL, labels.getOrDefault(firstKey, opt[1]));
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
      JSONObject j = buildRow(row);
      if (j != null) {
        array.put(j);
      }
    }
    JSONObject body = new JSONObject();
    body.put("rows", array);
    body.put("total", array.length());
    return NeoResponse.ok(body);
  }

  /**
   * Converts one raw {@link NoPostedDocumentDS} row into the JSON shape served to the frontend,
   * enriched with {@code tableId}. Returns {@code null} when the row's document type is
   * APRM-managed ({@link #APRM_DISABLED_TABLE_IDS}) — such rows must never reach the frontend,
   * since direct bulk-posting on them always fails by APRM design.
   *
   * <p>Package-private so it can be unit-tested without a live {@link NoPostedDocumentDS}.
   */
  JSONObject buildRow(Map<String, Object> row) throws Exception {
    Object docType = row.get("documentType");
    String tableId = docType instanceof String
        ? DOCUMENT_TYPE_TO_TABLE_ID.get(docType.toString()) : null;

    if (tableId != null && APRM_DISABLED_TABLE_IDS.contains(tableId)) {
      return null;
    }

    JSONObject j = new JSONObject();
    for (Map.Entry<String, Object> e : row.entrySet()) {
      j.put(e.getKey(), e.getValue() != null ? e.getValue() : JSONObject.NULL);
    }
    j.put(KEY_TABLE_ID, tableId != null ? tableId : JSONObject.NULL);
    return j;
  }

  /**
   * Translates frontend query params into the flat param map expected by
   * {@link NoPostedDocumentDS#getData}. Field names are read directly from the params
   * map by the datasource — no AdvancedCriteria wrapping.
   *
   * <p>Key insight: {@code NoPostedDocumentDS.getGridData} calls {@code getValues(jsonArray,
   * referenceId)} which queries {@code AD_Ref_List.id IN (...)}. The JSON array must therefore
   * contain {@code ad_ref_list_id} UUID values, NOT search keys like "N" or "E". This method
   * translates the frontend's search-key shorthand to the required UUIDs via
   * {@link #ACCOUNTING_STATUS_KEY_TO_ID}.
   *
   * <p>Key mapping:
   * <ul>
   *   <li>{@code _org}              ← current OBContext organisation</li>
   *   <li>{@code accounting_status} ← JSON array of {@code ad_ref_list_id} UUIDs</li>
   *   <li>{@code document}          ← {@code document}</li>
   *   <li>{@code DateFrom}          ← {@code dateFrom}</li>
   *   <li>{@code DateTo}            ← {@code dateTo}</li>
   * </ul>
   */
  Map<String, String> buildDsParams(Map<String, String> params) throws Exception {
    Map<String, String> dsParams = new HashMap<>();
    dsParams.put("_org", OBContext.getOBContext().getCurrentOrganization().getId());

    String document = params.get("document");
    if (document != null && !document.isEmpty()) {
      dsParams.put("document", document);
    }

    String accountingStatus = params.get(KEY_ACCOUNTING_STATUS);
    List<String> statusKeys = (accountingStatus != null && !accountingStatus.isEmpty())
        ? Arrays.asList(accountingStatus.split(","))
        : DEFAULT_ACCOUNTING_STATUS_KEYS;

    JSONArray arr = new JSONArray();
    for (String key : statusKeys) {
      String uuid = ACCOUNTING_STATUS_KEY_TO_ID.get(key.trim());
      if (uuid != null) {
        arr.put(uuid);
      }
    }
    if (arr.length() > 0) {
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
