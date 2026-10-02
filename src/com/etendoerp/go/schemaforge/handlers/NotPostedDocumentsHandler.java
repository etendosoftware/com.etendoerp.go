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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.ListTrl;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

import com.etendoerp.bulk.posting.datasource.NoPostedDocumentDS;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.AccountingDocumentTypeSupport;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Serves the Not Posted Documents window.
 *
 * <p>CRUD endpoint:
 * <ul>
 *   <li>Query param {@code _mode=filter-options} → returns dropdown option lists for Document type
 *       and Accounting status from AD_Ref_List.</li>
 *   <li>Otherwise → returns the unposted document grid by delegating to
 *       {@link NoPostedDocumentDS}. Each row is enriched with {@code documentTypeCode} and
 *       {@code tableId} (resolved from {@code documentType} via
 *       {@link #DS_LABEL_TO_DOCUMENT_TYPE_CODE}), so the frontend can call the Post action without
 *       knowing the table, plus {@code accountingStatus} (and {@code financialAccountId} on
 *       transactions) read from the document itself.</li>
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

  /**
   * OBUIAPP process id for the classic "Not Posted Documents" process. This is the process this
   * spec must gate access on — confirmed via the real {@code AD_Menu.em_obuiapp_process_id}
   * foreign key on {@code AD_Menu} row {@code 3EB0F5F33ECC4FEBABD8F513E9C49521} ("Not Posted
   * Documents"), not by name-matching. Do not repoint this constant at a different-looking
   * process without re-confirming that same FK chain (ETP-4510, follow-up to BUG-3).
   */
  static final String NOT_POSTED_DOCUMENTS_PROCESS_ID = "D6AB95CE52D34E1599590526115E26C6";

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

  private static final String KEY_TABLE_ID = "tableId";
  private static final String KEY_ACCOUNTING_STATUS = "accountingStatus";
  private static final String KEY_RECORD_ID = "recordId";
  private static final String KEY_SUCCESS = "success";
  private static final String KEY_VALUE = "value";
  private static final String KEY_LABEL = "label";
  private static final String KEY_DOCUMENT_ID = "documentId";
  private static final String KEY_DOCUMENT_TYPE_CODE = "documentTypeCode";
  private static final String KEY_FINANCIAL_ACCOUNT_ID = "financialAccountId";

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
   * Maps the {@code documentType} label emitted by {@link NoPostedDocumentDS} to its document-type
   * code (the {@code AD_Ref_List} search key of {@link #DOCUMENT_TYPE_REF_ID}). Both the row's
   * {@code tableId} (via {@link #DOCUMENT_TYPE_CODE_TO_TABLE_ID}) and its {@code documentTypeCode}
   * — which the frontend translates and uses to pick the "Open document" target window — derive
   * from this single map.
   *
   * <p>ETP-5591: this replaced a parallel label → {@code AD_Table_ID} map. Keeping two maps keyed
   * on different vocabularies is how rows kept reaching the grid with {@code tableId: null}
   * (ETP-5075 Matched Invoice, ETP-5445 Internal Consumption, then ETP-5591 Transaction): a type
   * was added to one map and not the other. Now a label only needs an entry here; its table comes
   * from the code map the filter dropdown already uses.
   *
   * <p>Source: the labels {@code DocumentSearchService.search*} actually emits, read from the
   * bulk.posting bytecode ({@code NoPostedConstans}); each label belongs to exactly one code.
   * {@code NoPostedConstans} also declares {@code Invoice}, {@code ShipmentInOut}, {@code Payment}
   * and {@code Production}, but no search method emits them, so they are deliberately absent.
   */
  static final Map<String, String> DS_LABEL_TO_DOCUMENT_TYPE_CODE = new HashMap<>();

  static {
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Sales Invoice", "SI");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Purchase Invoice", "PI");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Payment In", "PIN");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Payment Out", "POT");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("GL Journal", "GLJ");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Bill of Materials Production", "BMP");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Work Effort", "WE");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Bank Statement", "BS");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Goods Shipment", "GS");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Goods Receipt", "GR");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Return to Vendor Shipment", "RVS");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Return Material Receipt", "RMR");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Amortization", "A");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Inventory", "INV");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Cost Adjustment", "CA");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Matched Invoice", "MI");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Internal Consumption", "IC");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Doubtful Debt", "DD");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Reconciliation", "R");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Transaction", "T");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Landed Cost", "LC");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Landed Cost Cost", "LCC");
    DS_LABEL_TO_DOCUMENT_TYPE_CODE.put("Movement", "M");
  }

  /**
   * Resolves a {@link NoPostedDocumentDS} label to its {@code AD_Table_ID} through the document-type
   * code, or {@code null} when the label is unknown. Package-private for tests.
   */
  static String tableIdForLabel(String label) {
    String code = label != null ? DS_LABEL_TO_DOCUMENT_TYPE_CODE.get(label) : null;
    return code != null ? DOCUMENT_TYPE_CODE_TO_TABLE_ID.get(code) : null;
  }

  /**
   * The bulk.posting extension column every posting table carries. It is the column
   * {@link NoPostedDocumentDS} filters on, so it is also what the row's status badge must show:
   * {@code Posted} can differ from it (e.g. {@code posted = 'p'} while this is {@code 'l'}).
   */
  static final String ACCOUNTING_STATUS_PROPERTY = "etblkpAccountingstatus";

  /** Max ids per {@code IN} clause when reading row accounting state. */
  private static final int STATE_QUERY_CHUNK = 1000;

  /** A row's accounting status key and, for financial-account transactions, its account id. */
  record AccountingState(String status, String financialAccountId) { }

  @Inject
  private DocumentPostingService postingService;

  /**
   * This spec is tab-less, so ETP-4254's catalog rule would hide it as "handler-only" were it
   * not for the {@code post} / {@code bulk-post} ACTION routes below — which are exactly the
   * transactional business actions the agentic catalog must keep. Declaring the action surface
   * is what keeps {@code not-posted-documents} in {@code neo_discover} and reachable through
   * {@code neo_action}.
   *
   * @return always {@code true}
   */
  @Override
  public boolean servesActions() {
    return true;
  }

  /**
   * The same OBUIAPP process grant {@link #handle} already enforces, declared so the shared gate
   * can ask for it.
   *
   * <p>This spec has no {@code AD_Window}, no linked {@code AD_Process} and no {@code AD_TAB_ID}
   * on its entity, so {@code NeoAccessHelper} has nothing to evaluate and falls through to its
   * permissive default. The refusal below was therefore invisible to the catalogue: the spec was
   * listed for roles the execution then answered {@code 403}. Declaring it changes what is
   * advertised, not who is allowed - the rule is the same one, asked one step earlier.</p>
   *
   * @return whether the current role holds the Not Posted Documents process grant
   */
  @Override
  public boolean isAccessibleForCurrentRole() {
    return NeoAccessHelper.hasObuiappProcessAccess(NOT_POSTED_DOCUMENTS_PROCESS_ID);
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!isAccessibleForCurrentRole()) {
      return NeoResponse.error(403, "Access denied");
    }
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
   * configured for accounting ({@code c_acctschema_table.isactive = 'Y'}) and not APRM-disabled.
   * The check is dynamic — new document types whose modules register a
   * {@code c_acctschema_table} entry are picked up automatically. The actual predicate now lives
   * in {@link AccountingDocumentTypeSupport} (ETP-4948), shared with the Calendar window's
   * {@code documents} entity so the two can never diverge on what counts as accounting-relevant.
   */
  JSONArray refListDocumentTypes() throws Exception {
    Set<String> accountedTableIds = AccountingDocumentTypeSupport.loadTablesWithActiveAccounting();
    JSONArray options = new JSONArray();
    Reference ref = OBDal.getInstance().get(Reference.class, DOCUMENT_TYPE_REF_ID);
    if (ref == null) return options;
    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    for (org.openbravo.model.ad.domain.List item : ref.getADListList()) {
      String code = item.getSearchKey();
      String tableId = DOCUMENT_TYPE_CODE_TO_TABLE_ID.get(code);
      boolean enabled = item.isActive()
          && AccountingDocumentTypeSupport.isTableAccountingRelevant(tableId, accountedTableIds);
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

    List<JSONObject> built = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      JSONObject j = buildRow(row);
      if (j != null) {
        built.add(j);
      }
    }
    enrichWithAccountingState(built);

    JSONArray array = new JSONArray();
    for (JSONObject j : built) {
      array.put(j);
    }
    JSONObject body = new JSONObject();
    body.put("rows", array);
    body.put("total", array.length());
    return NeoResponse.ok(body);
  }

  /**
   * Converts one raw {@link NoPostedDocumentDS} row into the JSON shape served to the frontend,
   * enriched with {@code tableId}. Returns {@code null} when the row's document type is
   * APRM-managed ({@link AccountingDocumentTypeSupport#isAprmDisabledTable}) — such rows must
   * never reach the frontend, since direct bulk-posting on them always fails by APRM design.
   *
   * <p>Package-private so it can be unit-tested without a live {@link NoPostedDocumentDS}.
   */
  JSONObject buildRow(Map<String, Object> row) throws Exception {
    Object docType = row.get("documentType");
    String code = docType instanceof String ? DS_LABEL_TO_DOCUMENT_TYPE_CODE.get(docType) : null;
    String tableId = docType instanceof String ? tableIdForLabel((String) docType) : null;

    if (AccountingDocumentTypeSupport.isAprmDisabledTable(tableId)) {
      return null;
    }

    JSONObject j = new JSONObject();
    for (Map.Entry<String, Object> e : row.entrySet()) {
      j.put(e.getKey(), e.getValue() != null ? e.getValue() : JSONObject.NULL);
    }
    j.put(KEY_TABLE_ID, tableId != null ? tableId : JSONObject.NULL);
    j.put(KEY_DOCUMENT_TYPE_CODE, code != null ? code : JSONObject.NULL);
    j.put(KEY_ACCOUNTING_STATUS, JSONObject.NULL);
    return j;
  }

  /**
   * Adds {@code accountingStatus} (and {@code financialAccountId} on financial-account
   * transactions) to every row, reading them from the documents themselves: the datasource rows
   * carry neither. One query per distinct table, chunked by id.
   *
   * <p>Never fails the grid: a table whose state cannot be read is logged and its rows keep
   * {@code accountingStatus: null} (the UI then renders no badge).
   *
   * <p>Package-private so the merge logic can be tested with a stubbed
   * {@link #loadAccountingStates}.
   */
  void enrichWithAccountingState(List<JSONObject> rows) {
    Map<String, List<JSONObject>> rowsByTable = new LinkedHashMap<>();
    for (JSONObject row : rows) {
      String tableId = stringOrNull(row, KEY_TABLE_ID);
      String documentId = stringOrNull(row, KEY_DOCUMENT_ID);
      if (tableId != null && documentId != null) {
        rowsByTable.computeIfAbsent(tableId, k -> new ArrayList<>()).add(row);
      }
    }
    for (Map.Entry<String, List<JSONObject>> entry : rowsByTable.entrySet()) {
      applyAccountingStates(entry.getKey(), entry.getValue());
    }
  }

  private static String stringOrNull(JSONObject row, String key) {
    Object value = row.opt(key);
    return value instanceof String ? (String) value : null;
  }

  private void applyAccountingStates(String tableId, List<JSONObject> tableRows) {
    try {
      Set<String> ids = new LinkedHashSet<>();
      for (JSONObject row : tableRows) {
        ids.add(row.getString(KEY_DOCUMENT_ID));
      }
      Map<String, AccountingState> states = loadAccountingStates(tableId, ids);
      for (JSONObject row : tableRows) {
        AccountingState state = states.get(row.getString(KEY_DOCUMENT_ID));
        if (state == null) {
          continue;
        }
        row.put(KEY_ACCOUNTING_STATUS, state.status() != null ? state.status() : JSONObject.NULL);
        if (state.financialAccountId() != null) {
          row.put(KEY_FINANCIAL_ACCOUNT_ID, state.financialAccountId());
        }
      }
    } catch (Exception e) {
      log.warn("Could not read the accounting status of table {}; its rows show no status",
          tableId, e);
    }
  }

  /**
   * Reads the accounting status of the given documents of one table, keyed by document id. For
   * {@code FIN_Finacc_Transaction} it also reads the financial account, which is where the
   * frontend opens a transaction (it has no window of its own).
   *
   * <p>The entity name in the HQL comes from the application dictionary
   * ({@link ModelProvider#getEntityByTableId}), never from the request; the ids are bound.
   * Returns an empty map for a table without the status column.
   */
  Map<String, AccountingState> loadAccountingStates(String tableId, Set<String> ids) {
    Map<String, AccountingState> result = new HashMap<>();
    Entity entity = ModelProvider.getInstance().getEntityByTableId(tableId);
    if (entity == null || !entity.hasProperty(ACCOUNTING_STATUS_PROPERTY)) {
      return result;
    }
    boolean withAccount = FIN_FinaccTransaction.ENTITY_NAME.equals(entity.getName());
    String hql = "select e.id, e." + ACCOUNTING_STATUS_PROPERTY
        + (withAccount ? ", e." + FIN_FinaccTransaction.PROPERTY_ACCOUNT + ".id" : "")
        + " from " + entity.getName() + " e where e.id in (:ids)";

    List<String> all = new ArrayList<>(ids);
    for (int from = 0; from < all.size(); from += STATE_QUERY_CHUNK) {
      List<String> chunk = all.subList(from, Math.min(from + STATE_QUERY_CHUNK, all.size()));
      List<Object[]> found = OBDal.getInstance().getSession()
          .createQuery(hql, Object[].class)
          .setParameterList("ids", chunk)
          .list();
      for (Object[] r : found) {
        result.put((String) r[0], new AccountingState((String) r[1],
            withAccount ? (String) r[2] : null));
      }
    }
    return result;
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
    // ETP-5175: same identity DocumentPostingService#handleAction sends, so this entry point
    // renders the Invalid-Account detail in the SPA's locale too.
    DocumentPostingService.putMessageIdentity(resp, result);
    // Pass the JSONObject itself (not resp.toString()) — the String overload of
    // NeoResponse.error wraps it as a nested error.message string instead of sending this flat
    // body, silently discarding the real message from any client reading a top-level `message`
    // field (same bug class as DocumentPostingService#handleAction, ETP-4706).
    return result.ok() ? NeoResponse.ok(resp) : NeoResponse.error(422, resp);
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
      DocumentPostingService.putMessageIdentity(rowResult, result);
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
