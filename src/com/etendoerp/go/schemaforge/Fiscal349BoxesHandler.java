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
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.apache.commons.lang3.StringUtils;
import org.openbravo.module.aeat349.es.AEAT3492010ReportDao;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;
import org.openbravo.module.bptaxidkey.ViesService;

class Fiscal349BoxesHandler extends AbstractFiscalHandler {

  // Package-private (not private): also read by Fiscal349SnapshotSupport.
  static final String OPERATORS = "operators";
  /** Payload key of the per-invoice origin rows (also the snapshot's excluded list). */
  static final String INVOICES_KEY = "invoices";
  /** Payload key of the corrective (Tipo Registro 2) detail rows. */
  static final String RECTIFICATIONS_KEY = "rectifications";
  /** Row key of an invoice/rectification's partner NIF-IVA. */
  static final String NIF_IVA_KEY = "nifIva";
  private static final String GENERATE  = "generate";

  /** Row key for the operator's tax base amount, as produced by AEAT3492010ReportDao. */
  private static final String BP_TAX_BASE_AMOUNT = "BPTaxBaseAmount";

  /** POST-only verb that re-runs VIES validation for the declaration's pending operators. */
  private static final String VALIDATE_VIES = "validate-vies";

  /**
   * The {@code vies} value {@link #mapViesStatus} emits for "not validated yet". Package-private
   * (not {@code private}) because {@link Fiscal349ViesSupport#pendingBpIds} also needs it.
   */
  static final String VIES_PENDING = "pending";

  /** AEAT349 tributary keys, in the order the summary object exposes them. */
  private static final List<String> SUMMARY_KEYS = Arrays.asList("E", "S", "A", "I");

  /**
   * Marks an operator row that comes from a corrective (rectificative) invoice — AEAT
   * "registro tipo 2". See {@link #computeOperators} for why these rows are kept out of the
   * regular {@code summary}.
   */
  static final String RECTIFICATIVE = "rectificative";

  /**
   * The whole VIES-validation cluster (gate, network phase, persistence), extracted for the
   * {@code java:S1448} method-count fix — see {@link Fiscal349ViesSupport}'s class javadoc.
   * Package-private so the same-package test suite ({@code Fiscal349ViesValidationTest}) can
   * reach it directly.
   */
  final Fiscal349ViesSupport viesSupport = new Fiscal349ViesSupport();

  /**
   * The file-name/contact/org-data resolution cluster used by {@link #handleGenerate} and
   * {@link #computeOperators}'s {@code contactFallback}/{@code phoneFallback}/{@code orgNif} —
   * extracted for the same {@code java:S1448} method-count fix, see
   * {@link Fiscal349GenerateSupport}'s class javadoc.
   */
  private final Fiscal349GenerateSupport generateSupport = new Fiscal349GenerateSupport();

  Fiscal349BoxesHandler(NeoServlet servlet) {
    super(servlet);
    this.snapshotSupport = new Fiscal349SnapshotSupport();
  }

  @Override
  protected boolean isKnownEntity(String entityName) {
    return OPERATORS.equals(entityName) || GENERATE.equals(entityName)
        || MODIFIED.equals(entityName) || VALIDATE_VIES.equals(entityName);
  }

  @Override
  protected boolean allowsPost(String entityName) {
    return GENERATE.equals(entityName) || VALIDATE_VIES.equals(entityName);
  }

  /** {@code validate-vies} mutates {@code C_BPartner}, so it is POST-only. */
  @Override
  protected boolean allowsGet(String entityName) {
    return !VALIDATE_VIES.equals(entityName);
  }

  @Override
  protected void dispatch(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException {
    runDispatch(response, () -> {
      if (OPERATORS.equals(entityName)) {
        // Deliberately NOT guarded (ETP-5438): operators is a pure read. A submitted declaration
        // is served from its persisted submission snapshot (never recomputed); a legacy submitted
        // one without a snapshot, and every draft/ready one, is computed live. Only the
        // side-effecting generate (file generation) is blocked once submitted.
        JSONObject result = snapshotOrCompute(orgId, year, period);
        response.setContentType(JSON_CT);
        response.getWriter().write(result.toString());
      } else if (GENERATE.equals(entityName)) {
        guardNotAlreadySubmitted(orgId, year, period);
        handleGenerate(orgId, year, period, request, response);
      } else if (VALIDATE_VIES.equals(entityName)) {
        JSONObject result = viesSupport.handleValidateVies(this, orgId, year, period);
        response.setContentType(JSON_CT);
        response.getWriter().write(result.toString());
      } else {
        long sinceMs = Long.parseLong(request.getParameter(SINCE_KEY));
        handleModified(orgId, year, period, new Date(sinceMs), response);
      }
    });
  }

  /**
   * ETP-5438 — thin, model-fixed wrapper around the shared {@link
   * AbstractFiscalHandler#guardNotAlreadySubmitted(String, int, String, String)}, applied to
   * {@code generate} only (the {@code operators} read stays open for a submitted declaration so
   * the frontend can render and freeze it on a cold session cache), so callers here
   * (and {@code Fiscal349BoxesHandlerTest}) don't have to repeat the {@code "349"} literal. The
   * guard logic itself (and {@code AlreadySubmittedException}) moved to the shared base class —
   * see its javadoc — once {@code Fiscal303BoxesHandler} needed the identical check.
   */
  void guardNotAlreadySubmitted(String orgId, int year, String period) {
    guardNotAlreadySubmitted(orgId, year, period, "349");
  }

  @Override
  protected String getModelKey() {
    return "fiscal349";
  }

  JSONObject computeOperators(String orgId, int year, String period) throws Exception {
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    if (org == null) {
      throw new OBException("Organization not found: " + orgId);
    }
    TaxReport  taxReport  = resolveTaxReport349(orgId, period);
    AcctSchema acctSchema = resolveAcctSchema();
    List<Period> periods  = resolvePeriods(orgId, year, period);

    if (periods.isEmpty()) {
      throw new OBException(
          "No periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    AEAT3492010ReportDao dao349 = new AEAT3492010ReportDao();

    List<TaxRate> taxesPurchase = dao349.get349Taxes(taxReport.getId(), "Purchase");
    List<TaxRate> taxesSales    = dao349.get349Taxes(taxReport.getId(), "Sales");

    Set<Invoice> allPurch  = dao349.get349Invoices(org, taxesPurchase, periods, acctSchema, true,  null);
    Set<Invoice> corrPurch = dao349.getCorrectiveInvoices(allPurch);
    Set<Invoice> purch     = dao349.removeCorrectiveInvoices(allPurch, corrPurch);

    Set<Invoice> allSales  = dao349.get349Invoices(org, taxesSales, periods, acctSchema, false, null);
    Set<Invoice> corrSales = dao349.getCorrectiveInvoices(allSales);
    Set<Invoice> sales     = dao349.removeCorrectiveInvoices(allSales, corrSales);

    Set<Map<String, Object>> purchaseBP =
        dao349.getTaxBaseAmountPerBusinessPartner(purch, taxesPurchase, true,  taxReport);
    Set<Map<String, Object>> salesBP =
        dao349.getTaxBaseAmountPerBusinessPartner(sales, taxesSales,    false, taxReport);

    // ETP-5027: correctives are excluded from purch/sales above, so a second per-BP aggregation
    // pass is what makes them visible in Operadores at all. They are deliberately aggregated
    // separately — never merged into purchaseBP/salesBP — so the regular summary stays exactly
    // what it was and the rows can be badged in the UI. Reported as a SIGNED DELTA against the
    // originally declared base: see correctiveDeltaRows.
    Map<String, BigDecimal> summaryByKey       = emptyKeyTotals();
    Map<String, BigDecimal> rectificativeByKey = emptyKeyTotals();

    List<Map<String, Object>> all = new ArrayList<>(purchaseBP);
    all.addAll(salesBP);

    List<Map<String, Object>> allRectificative =
        correctiveDeltaRows(dao349, corrPurch, taxesPurchase, true,  taxReport);
    allRectificative.addAll(
        correctiveDeltaRows(dao349, corrSales, taxesSales,    false, taxReport));

    List<Map<String, Object>> everyBpRow = new ArrayList<>(all);
    everyBpRow.addAll(allRectificative);

    Map<String, BusinessPartner> bpMap        = loadBpMap(everyBpRow);
    JSONArray                    operatorsArr = buildOperatorsArray(all, bpMap, summaryByKey);
    appendOperators(operatorsArr, allRectificative, bpMap, rectificativeByKey, true);

    JSONObject summary = buildKeyTotals(summaryByKey);

    // Per-invoice AEAT349 keys (E/S/A/I) with the base each key carries, resolved separately for
    // purchase/sales invoices against their respective tax rate sets, then merged — purch/sales
    // invoice ids never overlap, so a plain putAll is safe. Lets the frontend split "origin"
    // counts per operator key instead of aggregating solely by nifIva (ETP-4755). ETP-5597: an
    // invoice mixing goods and services lines carries BOTH keys — the same per-tax split
    // getTaxBaseAmountPerBusinessPartner applies to the operator rows — so it backs both rows.
    Map<String, Map<String, BigDecimal>> invoiceKeyBases = new HashMap<>(
        resolveInvoiceKeyBases(purch, taxesPurchase, taxReport.getId(), true));
    invoiceKeyBases.putAll(resolveInvoiceKeyBases(sales, taxesSales, taxReport.getId(), false));

    String    orgNif      = generateSupport.resolveOrgNif(orgId);
    JSONArray invoicesArr = collectInvoices(purch, sales, invoiceKeyBases);
    JSONArray rectifArr   = collectRectifications(corrPurch, corrSales);

    JSONObject root = new JSONObject();
    root.put(OPERATORS, operatorsArr);
    root.put("summary",  summary);
    root.put("rectificativeSummary", buildKeyTotals(rectificativeByKey));
    root.put(INVOICES_KEY, invoicesArr);
    root.put(RECTIFICATIONS_KEY, rectifArr);
    root.put("orgNif",   orgNif != null ? orgNif : "");
    root.put("orgName",  org.getName());
    // ETP-5456 — read-only fallback values for FileGenModal's "Persona de contacto"/"Teléfono de
    // contacto" fields, so the frontend can tell whether leaving them blank would actually resolve
    // to something at generation time (and block the modal when it wouldn't). Reuses the EXACT
    // same resolution {@link Fiscal349GenerateSupport#applyContactParams} already falls back to
    // server-side — never duplicated, just exposed — so this can never drift from what a blank
    // field actually does.
    String contactFallback = Fiscal349GenerateSupport.resolveCurrentUserContactName();
    String phoneFallback   = generateSupport.resolveOrgPhone(orgId);
    root.put("contactFallback", contactFallback != null ? contactFallback : "");
    root.put("phoneFallback",   phoneFallback   != null ? phoneFallback   : "");
    return root;
  }

  /**
   * Corrective (Tipo Registro 2) detail rows for the Rectificaciones tab: one row
   * per C_Invoice_Reverse entry with AEAT349 corrective data, on the corrective
   * invoices of the declared period. Scalar HQL — the EM_AEAT349_* module
   * properties have no typed getters on the core ReversedInvoice class.
   */
  JSONArray collectRectifications(Set<Invoice> corrPurch, Set<Invoice> corrSales)
      throws Exception {
    JSONArray arr = new JSONArray();
    collectRectificationRows(arr, corrPurch, "Compra");
    collectRectificationRows(arr, corrSales, "Venta");
    return arr;
  }

  private void collectRectificationRows(JSONArray arr, Set<Invoice> invoices, String type)
      throws Exception {
    if (invoices == null || invoices.isEmpty()) {
      return;
    }
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery("select i.documentNo, i.invoiceDate, bp.name, bp.taxID,"
            + " rev.documentNo, y.fiscalYear, ri.aEAT349Period,"
            + " ri.aEAT349BPBaseAmount, ri.aEAT349BPBaseAmountS"
            + " from ReversedInvoices ri"
            + " join ri.invoice i"
            + " join i.businessPartner bp"
            + " join ri.reversedInvoice rev"
            + " left join ri.aEAT349CYear y"
            + " where ri.invoice in :invs and ri.aEAT349IsCorrective = true", Object[].class)
        .setParameterList("invs", invoices)
        .list();
    for (Object[] r : rows) {
      JSONObject row = new JSONObject();
      row.put("ref",           str(r[0]));
      row.put("date",          dateStr(r[1], sdf));
      row.put("type",          type);
      row.put("party",         str(r[2]));
      row.put(NIF_IVA_KEY,     str(r[3]));
      row.put("originalRef",   str(r[4]));
      row.put("declaredYear",  str(r[5]));
      row.put("declaredPeriod", str(r[6]));
      row.put("baseProducts",  scaled((BigDecimal) r[7]));
      row.put("baseServices",  scaled((BigDecimal) r[8]));
      arr.put(row);
    }
  }

  // Null-safe string coercion for HQL projection columns (null -> "").
  private static String str(Object o) {
    return o != null ? o.toString() : "";
  }

  // Null-safe date formatting for HQL projection columns (null -> "").
  private static String dateStr(Object o, SimpleDateFormat sdf) {
    return o != null ? sdf.format((Date) o) : "";
  }

  private static String scaled(BigDecimal v) {
    return (v != null ? v : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toString();
  }

  // Maps C_BPartner.EM_OBTIK_VIESStatus ('V'/'I'/'P'/null) to the 3 status strings the
  // frontend's ViesBadge component understands (valid/pending/invalid). Matches the same
  // 'V'/'I' vs.-everything-else convention already used in BusinessPartnerHandler.java and
  // ContactsLocationAddressHandler.java for this same column.
  private static String mapViesStatus(BusinessPartner bp) {
    if (bp == null) {
      return VIES_PENDING;
    }
    String status = bp.getOBTIKVIESStatus();
    if (ViesService.STATUS_VALID.equals(status)) {
      return "valid";
    }
    if (ViesService.STATUS_INVALID.equals(status)) {
      return "invalid";
    }
    return VIES_PENDING;
  }

  // ── validate-vies ─────────────────────────────────────────────────
  //
  // The gate/network/persistence cluster for this verb (handleValidateVies, pendingBpIds,
  // validatePendingVies, loadViesCandidates, checkVatInParallel, persistViesStatuses, and their
  // ViesCandidate/ViesGateResult value types) was extracted into Fiscal349ViesSupport (ETP-5027,
  // java:S1448 method-count fix). checkVat and releaseDalConnection stay here — see
  // Fiscal349ViesSupport's class javadoc for why.

  /**
   * Commits and closes the DAL session so the VIES network phase holds NO pooled DB connection.
   *
   * <p><b>Why this is required.</b> {@code DalRequestFilter} opens a session on the request
   * thread and keeps it — together with its pooled JDBC connection — bound until the request
   * ends. {@code Fiscal349ViesSupport#handleValidateVies} runs {@link #computeOperators} first,
   * which is a dozen HQL queries, so by the time the VIES phase starts there is a live
   * transaction and a pinned connection. {@code Fiscal349ViesSupport#checkVatInParallel} then
   * blocks for up to two minutes (25 partners over 4 threads is typically ~60 s). A handful of
   * users clicking the banner at once would therefore hold a handful of connections for a
   * minute each — an instance-wide availability problem, not a 349 problem.
   *
   * <p>Closing here is safe because everything the request still needs is already materialized:
   * {@link #computeOperators} returned a {@code JSONObject} and the gate phase read its two
   * columns with plain JDBC into value objects. No detached entity is touched afterwards. The
   * persist phase calls {@code OBDal.getInstance().getConnection()}, which transparently opens
   * a fresh session, and {@code DalRequestFilter}'s own commit/close at the end of the request
   * tolerates an already-closed session.
   *
   * <p>Failures are logged and swallowed on purpose: not being able to release the connection
   * degrades this to the previous (pinned) behaviour, which is worse but still correct, and
   * must not fail a validation run that has not even started yet.
   *
   * <p>Package-private and non-static so tests can verify it runs BETWEEN the gate and the
   * network phase.
   */
  void releaseDalConnection() {
    try {
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } catch (Exception e) {
      log.warn("validate-vies: could not release the DAL connection before the VIES phase: "
          + e.getMessage());
    }
  }

  /**
   * The single VIES call, isolated as an overridable seam.
   *
   * <p>It exists so unit tests can stub the network out on a SPY of this handler: the calls run
   * on a worker pool and Mockito's {@code mockStatic} is thread-local, so a static mock of
   * {@link ViesService} would silently NOT apply inside those threads and the suite would issue
   * real requests to {@code ec.europa.eu}. Stubbing this method instead is thread-safe.
   *
   * <p>Deliberately kept on this class (not moved into {@link Fiscal349ViesSupport} with the
   * rest of the VIES cluster): the test suite stubs it directly via
   * {@code doReturn(...).when(handler).checkVat(...)} on a Mockito spy, which requires it to
   * remain a real instance method here — see {@link Fiscal349ViesSupport}'s class javadoc.
   */
  String checkVat(String taxId) {
    return ViesService.checkVat(taxId).status;
  }

  // Package-private for unit testing of the pure data→JSON transformation logic.
  Map<String, BusinessPartner> loadBpMap(List<Map<String, Object>> rows) {
    List<String> bpIds = rows.stream()
        .filter(row -> {
          BigDecimal b = (BigDecimal) row.get(BP_TAX_BASE_AMOUNT);
          return b != null && b.compareTo(BigDecimal.ZERO) != 0;
        })
        .map(row -> (String) row.get("BPId"))
        .filter(Objects::nonNull)
        .distinct()
        .collect(Collectors.toList());
    if (bpIds.isEmpty()) return new HashMap<>();
    return OBDal.getInstance().getSession()
        .createQuery("from BusinessPartner where id in :ids", BusinessPartner.class)
        .setParameterList("ids", bpIds)
        .list()
        .stream()
        .collect(Collectors.toMap(BusinessPartner::getId, bp -> bp));
  }

  /**
   * Builds the regular (non-corrective) operator rows and accumulates their bases into
   * {@code summaryByKey}. Kept as the original 3-arg signature so the ordinary path reads
   * unchanged; corrective rows are appended afterwards via {@link #appendOperators}.
   */
  JSONArray buildOperatorsArray(List<Map<String, Object>> rows,
      Map<String, BusinessPartner> bpMap, Map<String, BigDecimal> summaryByKey) throws Exception {
    return appendOperators(new JSONArray(), rows, bpMap, summaryByKey, false);
  }

  /**
   * Appends one operator row per non-zero per-business-partner aggregation in {@code rows} to
   * {@code arr}, accumulating each base into {@code totalsByKey} (only for the four known
   * AEAT349 keys — anything else is emitted as a row but contributes to no total).
   *
   * <p>Every row carries a {@code rectificative} flag. Corrective rows land in the SAME array
   * as the regular ones — so the frontend's existing key filter and search pick them up for
   * free — but are passed a DIFFERENT {@code totalsByKey} map by the caller, which is what
   * keeps their amounts out of the regular {@code summary}. The AEAT treats correctives as a
   * separate record type (registro tipo 2), so mixing the two subtotals would misstate the
   * declaration.
   *
   * <p>A corrective base is a SIGNED delta (see {@link #toSignedDeltaRows}) and is legitimately
   * negative, so nothing here may clamp or {@code abs()} it. The zero-base skip below is a
   * {@code compareTo(ZERO) == 0} test precisely so it drops only no-op corrections and never a
   * negative one; the accumulation into {@code totalsByKey} is a plain signed {@code add}.
   *
   * <p>ETP-5027 (QA F4): rows also carry {@code declaredYear}/{@code declaredPeriod} whenever the
   * source row has them. The DAO groups corrective rows by
   * {@code (BPId, TaxKey, Year, Period)} — correcting the same partner's 2025/T1 and 2025/T2
   * sales of goods in one declaration legitimately produces TWO rows with the same
   * {@code (bpId, key, rectificative)} triple — so without the year/period the frontend cannot
   * tell them apart, which collided its React keys and its row selection. Regular rows come from
   * {@code getTaxBaseAmountPerBusinessPartner}, which groups by {@code (BPId, TaxKey)} only and
   * carries no {@code Year}/{@code Period}; the two keys are therefore emitted only when present,
   * and regular rows keep exactly the shape they had. The names match the ones
   * {@link #collectRectifications} already uses for the same concept.
   *
   * @param rectificative
   *          value of the {@code rectificative} flag written on every row produced here
   */
  JSONArray appendOperators(JSONArray arr, List<Map<String, Object>> rows,
      Map<String, BusinessPartner> bpMap, Map<String, BigDecimal> totalsByKey,
      boolean rectificative) throws Exception {
    for (Map<String, Object> row : rows) {
      String     bpId = (String)     row.get("BPId");
      BigDecimal base = (BigDecimal) row.get(BP_TAX_BASE_AMOUNT);
      String     key  = (String)     row.get("TaxKey");
      if (base == null || base.compareTo(BigDecimal.ZERO) == 0) continue;
      BusinessPartner bp   = bpMap.get(bpId);
      String          name = bp != null ? bp.getName() : bpId;
      String          nif  = bp != null && bp.getTaxID() != null ? bp.getTaxID() : "";
      JSONObject op = new JSONObject();
      op.put("bpId", bpId);
      op.put("nif",  nif);
      op.put("name", name);
      op.put("key",  key != null ? key : "");
      op.put("base", base.setScale(2, RoundingMode.HALF_UP).toString());
      op.put("vies", mapViesStatus(bp));
      op.put(RECTIFICATIVE, rectificative);
      putIfNotBlank(op, "declaredYear",   row.get("Year"));
      putIfNotBlank(op, "declaredPeriod", row.get("Period"));
      arr.put(op);
      if (key != null && totalsByKey.containsKey(key)) {
        totalsByKey.put(key, totalsByKey.get(key).add(base));
      }
    }
    return arr;
  }

  /**
   * Writes {@code key} only when the value is present and non-blank, so a row that has no such
   * column keeps exactly the JSON shape it had before the column existed.
   */
  private static void putIfNotBlank(JSONObject json, String key, Object value) throws Exception {
    String text = str(value);
    if (StringUtils.isNotBlank(text)) {
      json.put(key, text);
    }
  }

  /** A zeroed E/S/A/I accumulator, one per subtotal being computed. */
  private static Map<String, BigDecimal> emptyKeyTotals() {
    Map<String, BigDecimal> totals = new LinkedHashMap<>();
    for (String k : SUMMARY_KEYS) {
      totals.put(k, BigDecimal.ZERO);
    }
    return totals;
  }

  /**
   * Renders an E/S/A/I accumulator as the {@code totalE}/{@code totalS}/{@code totalA}/
   * {@code totalI} JSON object — the shape used by BOTH {@code summary} and
   * {@code rectificativeSummary}.
   *
   * <p>ETP-5027: deliberately emits NO grand total. E and S are <i>entregas</i> (sales) while
   * A and I are <i>adquisiciones</i> (purchases); the AEAT never nets one against the other,
   * so {@code E + S + A + I} is not a quantity that means anything. An earlier revision emitted
   * such a {@code total} on the rectificative subtotal and it produced figures like
   * {@code -32.00 + -5.00 = -37.00} — and would render {@code 0,00} for a -30 sales correction
   * offset by a +30 purchase correction. The four per-key rows already carry the information.
   */
  static JSONObject buildKeyTotals(Map<String, BigDecimal> totalsByKey) throws Exception {
    JSONObject json = new JSONObject();
    for (Map.Entry<String, BigDecimal> e : totalsByKey.entrySet()) {
      json.put("total" + e.getKey(), scaled(e.getValue()));
    }
    return json;
  }

  /**
   * Per-business-partner rows for the corrective (rectificative) invoices of the period, with
   * {@code BPTaxBaseAmount} rewritten as the SIGNED DELTA against the originally declared base —
   * a rectification that removes 3 units of a 10.00 product reports {@code -30.00}.
   *
   * <p>Returns an empty list when there is nothing to query: the DAO's HQL filters with
   * {@code it.invoice in :correctiveInvoices}, which is not valid SQL for an empty collection,
   * and a period with no corrective invoices is the common case.
   */
  private static List<Map<String, Object>> correctiveDeltaRows(AEAT3492010ReportDao dao349,
      Set<Invoice> invoices, List<TaxRate> taxRates, boolean isPurchase, TaxReport taxReport) {
    if (invoices == null || invoices.isEmpty() || taxRates == null || taxRates.isEmpty()) {
      return new ArrayList<>();
    }
    return toSignedDeltaRows(dao349.getCorrectiveTaxBaseAmountPerBusinessPartner(
        invoices, taxRates, isPurchase, taxReport));
  }

  /**
   * Converts {@code AEAT3492010ReportDao#getCorrectiveTaxBaseAmountPerBusinessPartner} rows from
   * the AEAT's unsigned correction magnitude to the signed delta the Operadores view shows.
   *
   * <p><b>Why a negation.</b> That DAO computes {@code BPTaxBaseAmount} as
   * {@code sum(it.taxableAmount * -1)} over the corrective invoice's own tax lines, so a credit
   * note carrying {@code -30} becomes {@code +30}: an unsigned magnitude of the correction, not a
   * delta. {@code AEAT3492010Report.generateLine2_Corrections} confirms the semantics — the
   * registro tipo 2 record writes {@code BPFormerAmount - BPTaxBaseAmount} as the corrected base
   * and {@code BPFormerAmount} as the previously declared one. Hence
   * {@code corrected - former == -BPTaxBaseAmount}, which is the delta, and it holds in both
   * directions: an upward correction yields a negative {@code BPTaxBaseAmount} and therefore a
   * positive delta.
   *
   * <p>Rows are copied rather than mutated so the DAO's own maps are left untouched, and a null
   * amount is passed through as null for {@link #appendOperators} to skip.
   */
  static List<Map<String, Object>> toSignedDeltaRows(List<Map<String, Object>> correctiveRows) {
    List<Map<String, Object>> signed = new ArrayList<>();
    if (correctiveRows == null) {
      return signed;
    }
    for (Map<String, Object> row : correctiveRows) {
      Map<String, Object> copy = new HashMap<>(row);
      BigDecimal magnitude = (BigDecimal) row.get(BP_TAX_BASE_AMOUNT);
      copy.put(BP_TAX_BASE_AMOUNT, magnitude == null ? null : magnitude.negate());
      signed.add(copy);
    }
    return signed;
  }

  /**
   * The "Facturas origen" rows: one row per (invoice, AEAT349 key).
   *
   * <p>ETP-5597: an invoice mixing goods and services lines (e.g. E + S on a sale, A + I on a
   * purchase) is split by {@code getTaxBaseAmountPerBusinessPartner} into BOTH operator rows, so
   * it must back both origins too. It therefore produces one row per key, each carrying only the
   * base of that key's tax lines (the per-key base from the same HQL the DAO uses: halved for a
   * purchase line with a non-zero tax amount, in the invoice currency).
   * A single-key invoice keeps exactly the row it always had (one row, the invoice's
   * {@code summedLineAmount}).
   *
   * <p>The rows are origin evidence, not a reconciliation: their bases are NOT guaranteed to add
   * up to the operator's base (a single-key row shows {@code summedLineAmount}, which is neither
   * halved nor converted, and no row is currency-converted).
   *
   * <p>Every row of a mixed invoice keeps {@code id} = invoice id; {@code (id, key)} is the
   * unique pair, and the frontend keys rows on it.
   */
  JSONArray collectInvoices(Set<Invoice> purch, Set<Invoice> sales,
      Map<String, Map<String, BigDecimal>> invoiceKeyBases) throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    JSONArray arr = new JSONArray();
    for (Invoice inv : purch) {
      appendInvoiceRows(arr, inv, "Compra", sdf, invoiceKeyBases);
    }
    for (Invoice inv : sales) {
      appendInvoiceRows(arr, inv, "Venta", sdf, invoiceKeyBases);
    }
    return arr;
  }

  /**
   * Appends the "Facturas origen" row(s) of one invoice: a single row with the invoice-level
   * {@code summedLineAmount} when it resolves to at most one key, or one row per key with that
   * key's per-key HQL base when it is mixed — see {@link #collectInvoices} for why those bases are
   * not a reconciliation of the operator's base.
   */
  private void appendInvoiceRows(JSONArray arr, Invoice inv, String type, SimpleDateFormat sdf,
      Map<String, Map<String, BigDecimal>> invoiceKeyBases) throws Exception {
    Map<String, BigDecimal> bases = invoiceKeyBases != null ? invoiceKeyBases.get(inv.getId()) : null;
    if (bases == null || bases.isEmpty()) {
      arr.put(buildInvoiceRow(inv, type, sdf, null, null));
      return;
    }
    // Only a mixed invoice needs the per-key base; a single-key one keeps its invoice-level base.
    boolean mixed = bases.size() > 1;
    for (Map.Entry<String, BigDecimal> e : bases.entrySet()) {
      arr.put(buildInvoiceRow(inv, type, sdf, e.getKey(), mixed ? e.getValue() : null));
    }
  }

  /**
   * One "Facturas origen" row.
   *
   * @param key
   *          AEAT349 key of the row ({@code ""} when unresolved)
   * @param keyBase
   *          base of the row's key alone (mixed invoice), or {@code null} to use the invoice's
   *          summed line amount (single-key invoice)
   */
  JSONObject buildInvoiceRow(Invoice inv, String type, SimpleDateFormat sdf, String key,
      BigDecimal keyBase) throws Exception {
    BusinessPartner bp   = inv.getBusinessPartner();
    BigDecimal      raw  = keyBase != null ? keyBase : inv.getSummedLineAmount();
    BigDecimal      base = raw != null
        ? raw.abs().setScale(2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;
    JSONObject row = new JSONObject();
    // ETP-5597: the invoice id gives the frontend a collision-free row key (documentNo is not
    // unique across AR/AP — same rationale as ETP-5393 in Fiscal303SourcesSupport). A mixed
    // invoice emits one row per key with the same id; the frontend keys on (id, key).
    row.put("id",     inv.getId());
    row.put("ref",    inv.getDocumentNo());
    row.put("date",   inv.getInvoiceDate() != null ? sdf.format(inv.getInvoiceDate()) : "");
    // ETP-5597: feeds the "Fecha contable" column; same key/format/null handling as the 303
    // sources row (JSONObject.put with null omits the key, so a missing date stays absent).
    row.put("accountingDate",
        inv.getAccountingDate() != null ? sdf.format(inv.getAccountingDate()) : null);
    row.put("type",   type);
    row.put("party",  bp != null ? bp.getName() : "");
    row.put(NIF_IVA_KEY, bp != null && bp.getTaxID() != null ? bp.getTaxID() : "");
    row.put("base",   base.toString());
    row.put("key",    key != null ? key : "");
    return row;
  }

  /**
   * Resolves, for each invoice in {@code invoices}, every AEAT349 classification key (E/S/A/I)
   * its tax lines map to, with the taxable base of each key — the same join and the same amount
   * expression {@link org.openbravo.module.aeat349.es.AEAT3492010ReportDao
   * #getTaxBaseAmountPerBusinessPartner} uses to build the operator rows, grouped per invoice
   * instead of per partner (purchases halve the base of a line with a non-zero tax amount, the
   * DAO's own rule for self-assessed intra-community VAT).
   *
   * <p>ETP-5597: this used to keep a single key per invoice (the one with most tax lines), so an
   * invoice mixing goods and services backed only one of its two operator rows and the other
   * showed "—" in Origen. Keys come back in ascending order ({@code order by}), so the per-invoice
   * map is deterministic. Amounts are in the invoice currency (no EUR conversion — the same as
   * the summed line amount a single-key row shows).
   *
   * @return invoice id -&gt; (key -&gt; base), keys in ascending order; never null.
   */
  Map<String, Map<String, BigDecimal>> resolveInvoiceKeyBases(Set<Invoice> invoices,
      Collection<TaxRate> taxRates, String taxReportId, boolean isPurchase) {
    if (invoices == null || invoices.isEmpty() || taxRates == null || taxRates.isEmpty()) {
      return new HashMap<>();
    }
    String amountExpr = isPurchase
        ? "sum(case it.taxAmount when 0 then coalesce(it.taxableAmount, 0)"
            + " else (coalesce(it.taxableAmount, 0) / 2) end)"
        : "sum(coalesce(it.taxableAmount, 0))";
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery("select i.id, trp.tributaryKey.name, " + amountExpr + " "
            + "from InvoiceTax as it, Invoice i, FinancialMgmtTaxRate tr, "
            + "OBTL_Tax_Parameter tp, OBTL_Tax_Report_Parameter trp "
            + "where i.id = it.invoice "
            + "  and tr.id = it.tax "
            + "  and tp.tax = tr.id "
            + "  and trp.id = tp.taxReportParameter "
            + "  and trp.taxReportGroup.taxReport.id = :taxReportId "
            + "  and it.invoice in :invoices "
            + "  and it.tax in :taxRates "
            + "group by i.id, trp.tributaryKey.name "
            + "order by i.id, trp.tributaryKey.name", Object[].class)
        .setParameter("taxReportId", taxReportId)
        .setParameterList("invoices", invoices)
        .setParameterList("taxRates", taxRates)
        .list();

    Map<String, Map<String, BigDecimal>> basesByInvoice = new HashMap<>();
    for (Object[] row : rows) {
      String     invId = (String) row[0];
      String     key   = (String) row[1];
      if (key == null) {
        continue;
      }
      basesByInvoice.computeIfAbsent(invId, k -> new LinkedHashMap<>())
          .merge(key, toBigDecimal(row[2]), BigDecimal::add);
    }
    return basesByInvoice;
  }

  // HQL sum() over a BigDecimal column comes back as BigDecimal; the /2 branch may widen it.
  private static BigDecimal toBigDecimal(Object value) {
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return value != null ? new BigDecimal(value.toString()) : BigDecimal.ZERO;
  }

  // ── generate ──────────────────────────────────────────────────────

  private void handleGenerate(String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    TaxReport    taxReport  = resolveTaxReport349(orgId, period);
    AcctSchema   acctSchema = resolveAcctSchema();
    List<Period> periods    = resolvePeriods(orgId, year, period);

    if (periods.isEmpty()) {
      throw new OBException(
          "No fiscal periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    String yearId    = periods.get(0).getYear().getId();
    String periodIds = periods.stream().map(Period::getId).collect(Collectors.joining(","));
    String filename  = generateSupport.resolveFileName(request, period, year);

    Map<String, String> inputParams =
        generateSupport.buildGenerateInputParams(request, orgId, filename);

    OBTL_TaxReport_I report = (OBTL_TaxReport_I)
        Class.forName(taxReport.getJavaClassName()).getDeclaredConstructor().newInstance();

    HashMap<String, Object> result = report.generateElectronicFile(
        orgId, taxReport.getId(), acctSchema.getId(), yearId, periodIds, inputParams);
    writeGeneratedFile(result, filename + ".349", response);
  }

  TaxReport resolveTaxReport349(String orgId, String periodCode) {
    String    type   = periodCode.startsWith("T") ? "Q" : "M";
    TaxReport report = findTaxReport(orgId, "AEAT3492010_" + type);
    if (report == null) report = findTaxReport(orgId, "AEAT349_" + type);
    if (report == null) {
      throw new OBException(
          "No TaxReport 349 found for org=" + orgId + " periodType=" + type);
    }
    return report;
  }

  // findTaxReport(orgId, searchKey) moved to AbstractFiscalHandler (SonarQube java:S1192 dedupe
  // — was byte-identical to Fiscal303BoxesHandler's own copy).

}
