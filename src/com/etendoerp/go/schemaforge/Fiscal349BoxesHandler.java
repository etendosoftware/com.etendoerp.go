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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
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

  private static final String OPERATORS = "operators";
  private static final String GENERATE  = "generate";

  /** POST-only verb that re-runs VIES validation for the declaration's pending operators. */
  private static final String VALIDATE_VIES = "validate-vies";

  /** The {@code vies} value {@link #mapViesStatus} emits for "not validated yet". */
  private static final String VIES_PENDING = "pending";

  /**
   * {@code EM_OBTIK_Tax_ID_Key} value for "NOI" (EU intra-community operator). Only partners
   * carrying this key are eligible for a VIES check — the exact gate
   * {@code ViesStatusObserver} applies in the bptaxidkey module.
   */
  private static final String NOI_TAX_ID_KEY = "2";

  /**
   * Maximum partners checked against VIES in a single request. VIES rate-limits per member
   * state ({@code MS_MAX_CONCURRENT_REQ} is a routinely-hit error), and each check can take up
   * to 10 s (5 s connect + 5 s read), so an unbounded batch would both hammer the service and
   * blow the HTTP timeout. Anything beyond the cap simply stays pending and is picked up by the
   * next click.
   */
  static final int VIES_BATCH_CAP = 25;

  /** Bounded parallelism for the VIES calls — high enough to be fast, low enough not to be throttled. */
  private static final int VIES_MAX_THREADS = 4;

  /** Upper bound on the whole VIES phase; anything unfinished by then is reported as pending. */
  private static final long VIES_PHASE_TIMEOUT_SECONDS = 120L;

  /** AEAT349 tributary keys, in the order the summary object exposes them. */
  private static final List<String> SUMMARY_KEYS = Arrays.asList("E", "S", "A", "I");

  /**
   * Marks an operator row that comes from a corrective (rectificative) invoice — AEAT
   * "registro tipo 2". See {@link #computeOperators} for why these rows are kept out of the
   * regular {@code summary}.
   */
  private static final String RECTIFICATIVE = "rectificative";

  Fiscal349BoxesHandler(NeoServlet servlet) {
    super(servlet);
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
    try {
      if (OPERATORS.equals(entityName)) {
        JSONObject result = computeOperators(orgId, year, period);
        response.setContentType(JSON_CT);
        response.getWriter().write(result.toString());
      } else if (GENERATE.equals(entityName)) {
        handleGenerate(orgId, year, period, request, response);
      } else if (VALIDATE_VIES.equals(entityName)) {
        JSONObject result = handleValidateVies(orgId, year, period);
        response.setContentType(JSON_CT);
        response.getWriter().write(result.toString());
      } else {
        long sinceMs = Long.parseLong(request.getParameter(SINCE_KEY));
        handleModified(orgId, year, period, new Date(sinceMs), response);
      }
    } catch (FiscalHandlerException e) {
      throw e;
    } catch (Exception e) {
      throw new FiscalHandlerException(e);
    }
  }

  @Override
  protected String getModelKey() {
    return "fiscal349";
  }

  // ── operators ─────────────────────────────────────────────────────

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

    // Per-invoice AEAT349 key (E/S/A/I), resolved separately for purchase/sales invoices
    // against their respective tax rate sets, then merged — purch/sales invoice ids never
    // overlap, so a plain putAll is safe. Lets the frontend split "origin" counts per
    // operator key instead of aggregating solely by nifIva (ETP-4755).
    Map<String, String> invoiceKeys = new HashMap<>(
        resolveInvoiceKeys(purch, taxesPurchase, taxReport.getId()));
    invoiceKeys.putAll(resolveInvoiceKeys(sales, taxesSales, taxReport.getId()));

    String    orgNif      = resolveOrgNif(orgId);
    JSONArray invoicesArr = collectInvoices(purch, sales, invoiceKeys);
    JSONArray rectifArr   = collectRectifications(corrPurch, corrSales);

    JSONObject root = new JSONObject();
    root.put(OPERATORS, operatorsArr);
    root.put("summary",  summary);
    root.put("rectificativeSummary", buildKeyTotals(rectificativeByKey));
    root.put("invoices", invoicesArr);
    root.put("rectifications", rectifArr);
    root.put("orgNif",   orgNif != null ? orgNif : "");
    root.put("orgName",  org.getName());
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
      row.put("nifIva",        str(r[3]));
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

  /**
   * A partner that passed the eligibility gate and will actually be sent to VIES.
   */
  static final class ViesCandidate {
    final String bpId;
    final String taxId;

    ViesCandidate(String bpId, String taxId) {
      this.bpId  = bpId;
      this.taxId = taxId;
    }
  }

  /**
   * Outcome of the eligibility gate: the partners to send to VIES, plus how many were rejected
   * PERMANENTLY.
   *
   * <p>ETP-5027 (QA F5): {@code notEligible} must stay separate from "still pending". A partner
   * whose tax-id key is not {@code NOI}, or whose tax id is blank, fails the gate on EVERY
   * future click — folding it into a count the UI describes as re-runnable invites an
   * unbreakable loop. Partners the gate could not even read (a row-level DB error) are in
   * NEITHER bucket: they are transient and fall through to {@code stillPending}.
   */
  static final class ViesGateResult {
    final List<ViesCandidate> eligible = new ArrayList<>();
    int notEligible;
  }

  /**
   * Re-runs VIES validation for the operators of a declaration whose NIF-IVA is still pending,
   * and returns the outcome counts backing the "N NIF-IVA con validación VIES pendiente" banner.
   *
   * <p>The operator set is resolved SERVER-SIDE from {@code orgId/year/period} by reusing
   * {@link #computeOperators}: the caller never supplies a list of business-partner ids, so no
   * extra authorization check is needed — a user who may read the declaration may re-validate
   * exactly the partners that declaration already exposes to them.
   *
   * <p>Response shape (fixed contract):
   * <pre>{ "validated": N, "valid": N, "invalid": N,
   *   "notEligible": N, "failed": N, "stillPending": N }</pre>
   * {@code validated} is the number of pending operators this call accounted for, and
   * {@code valid + invalid + notEligible + failed + stillPending} always equals it: every
   * pending operator lands in exactly ONE bucket.
   *
   * <ul>
   *   <li>{@code valid} / {@code invalid} — VIES answered conclusively AND the answer was
   *       successfully written back to {@code C_BPartner}. A result that was not persisted is
   *       NOT counted here: the user reloads against the database, not against this response
   *       (ETP-5027, QA F2).</li>
   *   <li>{@code notEligible} — the partner failed the eligibility gate (tax-id key is not
   *       {@code NOI}, or the tax id is blank) or no longer exists. This is a PERMANENT
   *       condition: the same partner fails the same gate on every future click, so the UI must
   *       not invite a re-run for it (ETP-5027, QA F5). Split out of {@code stillPending}
   *       precisely so the copy can say what needs fixing instead of looping.</li>
   *   <li>{@code failed} — VIES answered conclusively but the write-back failed. Transient and
   *       worth retrying, but it must NOT be reported as success.</li>
   *   <li>{@code stillPending} — genuinely inconclusive right now: VIES could not answer
   *       (timeout, or the very common {@code MS_MAX_CONCURRENT_REQ}), or the partner was
   *       deferred past {@link #VIES_BATCH_CAP}. A non-zero value is a NORMAL outcome, never an
   *       error, and a re-run is the right follow-up.</li>
   * </ul>
   */
  JSONObject handleValidateVies(String orgId, int year, String period) throws Exception {
    JSONObject   operators  = computeOperators(orgId, year, period);
    List<String> pendingIds = pendingBpIds(operators.optJSONArray(OPERATORS));
    return validatePendingVies(pendingIds);
  }

  /**
   * The distinct business-partner ids of the operator rows whose VIES status is still pending.
   * The same partner can appear on several rows (one per AEAT key, plus rectificative rows), so
   * the result is de-duplicated — a partner must be checked once, not once per row.
   */
  static List<String> pendingBpIds(JSONArray operators) {
    Set<String> ids = new LinkedHashSet<>();
    if (operators == null) {
      return new ArrayList<>(ids);
    }
    for (int i = 0; i < operators.length(); i++) {
      JSONObject op = operators.optJSONObject(i);
      if (op == null || !VIES_PENDING.equals(op.optString("vies"))) {
        continue;
      }
      String bpId = op.optString("bpId");
      if (StringUtils.isNotBlank(bpId)) {
        ids.add(bpId);
      }
    }
    return new ArrayList<>(ids);
  }

  /**
   * Drives the four phases of a validation run, in this order and for this reason:
   * <ol>
   *   <li><b>Read + gate</b> (short DB work) — resolve each partner's taxId/key and drop the
   *       ineligible ones.</li>
   *   <li><b>Release the DAL session</b> — see {@link #releaseDalConnection()}. This step is
   *       what actually makes phase 3 connection-free; without it the whole network phase would
   *       run with a transaction open and a pooled connection pinned to the request thread.</li>
   *   <li><b>Call VIES</b> (slow, network) — performed with NO DB connection held.</li>
   *   <li><b>Persist</b> (short DB work, on a fresh session) — write back only the conclusive
   *       results.</li>
   * </ol>
   */
  JSONObject validatePendingVies(List<String> pendingIds) throws Exception {
    List<String> all = pendingIds != null ? pendingIds : new ArrayList<>();
    int total = all.size();

    List<String> batch = total > VIES_BATCH_CAP ? all.subList(0, VIES_BATCH_CAP) : all;

    ViesGateResult      gate     = loadViesCandidates(batch);
    releaseDalConnection();
    Map<String, String> statuses = checkVatInParallel(gate.eligible);
    Set<String>         persisted = persistViesStatuses(statuses);

    // ETP-5027 (QA F2): the conclusive counts are derived from what was actually WRITTEN, not
    // from the in-memory answers. Reporting "20 valid" after a failed UPDATE told the user the
    // job was done and then showed them 20 still-pending badges on the next reload.
    int valid   = 0;
    int invalid = 0;
    int failed  = 0;
    for (Map.Entry<String, String> e : statuses.entrySet()) {
      boolean isValid   = ViesService.STATUS_VALID.equals(e.getValue());
      boolean isInvalid = ViesService.STATUS_INVALID.equals(e.getValue());
      if (!isValid && !isInvalid) {
        continue; // inconclusive — nothing to persist, falls through to stillPending
      }
      if (!persisted.contains(e.getKey())) {
        failed++;
      } else if (isValid) {
        valid++;
      } else {
        invalid++;
      }
    }

    JSONObject result = new JSONObject();
    result.put("validated",    total);
    result.put("valid",        valid);
    result.put("invalid",      invalid);
    result.put("notEligible",  gate.notEligible);
    result.put("failed",       failed);
    // Never negative: notEligible + eligible <= batch <= total, and the conclusive buckets
    // together cannot exceed the eligible set.
    result.put("stillPending", total - valid - invalid - gate.notEligible - failed);
    return result;
  }

  /**
   * Loads {@code (taxId, taxIdKey)} for each candidate and keeps only the partners that pass the
   * SAME eligibility gate {@code ViesStatusObserver} applies in the bptaxidkey module: tax id key
   * {@code '2'} (NOI) AND a non-blank tax id. Go must behave identically to Classic here, so no
   * looser rule (such as "any EU-prefixed tax id") may be substituted. A partner that fails the
   * gate is simply absent from the result and therefore counted as still-pending without ever
   * being sent to VIES.
   *
   * <p>Read with plain JDBC rather than OBDal so this stays a projection of two columns and does
   * not pull entities into the session that the persist phase would then have to fight with.
   *
   * <p>ETP-5027 (QA F2): the per-row work is wrapped in its OWN try/catch. The catch used to sit
   * outside the loop, so a single unreadable row silently discarded every candidate that had not
   * been processed yet — a partial batch reported as a complete run. Only a failure to open the
   * statement at all aborts the phase now, and it leaves the whole batch in {@code stillPending}
   * rather than pretending it was checked.
   */
  ViesGateResult loadViesCandidates(List<String> bpIds) {
    ViesGateResult gate = new ViesGateResult();
    if (bpIds == null || bpIds.isEmpty()) {
      return gate;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT taxid, em_obtik_tax_id_key FROM c_bpartner WHERE c_bpartner_id = ?")) {
      for (String bpId : bpIds) {
        readCandidate(ps, bpId, gate);
      }
    } catch (Exception e) {
      log.warn("validate-vies: could not load VIES candidates: " + e.getMessage());
    }
    return gate;
  }

  /**
   * One row of the eligibility gate. A partner that is missing or fails the gate is counted as
   * PERMANENTLY not eligible; a row that could not be READ is counted as neither, so it stays
   * transiently pending and the next click retries it.
   */
  private void readCandidate(PreparedStatement ps, String bpId, ViesGateResult gate) {
    try {
      ps.setString(1, bpId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          gate.notEligible++;
          return;
        }
        String taxId    = rs.getString("taxid");
        String taxIdKey = rs.getString("em_obtik_tax_id_key");
        if (NOI_TAX_ID_KEY.equals(taxIdKey) && StringUtils.isNotBlank(taxId)) {
          gate.eligible.add(new ViesCandidate(bpId, taxId));
        } else {
          gate.notEligible++;
        }
      }
    } catch (Exception e) {
      log.warn("validate-vies: could not read VIES candidate " + bpId + ": " + e.getMessage());
    }
  }

  /**
   * Commits and closes the DAL session so the VIES network phase holds NO pooled DB connection.
   *
   * <p><b>Why this is required.</b> {@code DalRequestFilter} opens a session on the request
   * thread and keeps it — together with its pooled JDBC connection — bound until the request
   * ends. {@link #handleValidateVies} runs {@link #computeOperators} first, which is a dozen
   * HQL queries, so by the time the VIES phase starts there is a live transaction and a pinned
   * connection. {@link #checkVatInParallel} then blocks for up to
   * {@link #VIES_PHASE_TIMEOUT_SECONDS} (25 partners over 4 threads is typically ~60 s). A
   * handful of users clicking the banner at once would therefore hold a handful of connections
   * for a minute each — an instance-wide availability problem, not a 349 problem.
   *
   * <p>Closing here is safe because everything the request still needs is already materialized:
   * {@link #computeOperators} returned a {@code JSONObject} and the gate phase read its two
   * columns with plain JDBC into {@link ViesCandidate} value objects. No detached entity is
   * touched afterwards. The persist phase calls {@code OBDal.getInstance().getConnection()},
   * which transparently opens a fresh session, and {@code DalRequestFilter}'s own
   * commit/close at the end of the request tolerates an already-closed session.
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
   * Calls {@link ViesService#checkVat} for every candidate with BOUNDED parallelism.
   *
   * <p>Neither a sequential loop nor a naive parallel stream is acceptable here: sequential
   * would need up to 10 s per partner and blow the request timeout, while a parallel stream
   * would fan out on the common pool and trip the VIES per-member-state concurrency limit
   * ({@code MS_MAX_CONCURRENT_REQ}) — which makes the run slower AND less conclusive, since
   * throttled answers come back as pending. A fixed pool of {@link #VIES_MAX_THREADS} is the
   * compromise. {@code checkVat} never throws, so a failure surfaces as {@code P}.
   *
   * @return bpId → VIES status ({@code V}/{@code I}/{@code P}); one entry per candidate
   */
  Map<String, String> checkVatInParallel(List<ViesCandidate> candidates) {
    Map<String, String> statuses = new LinkedHashMap<>();
    if (candidates == null || candidates.isEmpty()) {
      return statuses;
    }
    ExecutorService pool =
        Executors.newFixedThreadPool(Math.min(VIES_MAX_THREADS, candidates.size()));
    try {
      List<Callable<String>> tasks = candidates.stream()
          .map(c -> (Callable<String>) () -> checkVat(c.taxId))
          .collect(Collectors.toList());
      List<Future<String>> futures =
          pool.invokeAll(tasks, VIES_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      for (int i = 0; i < candidates.size(); i++) {
        statuses.put(candidates.get(i).bpId, statusOf(futures.get(i)));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("validate-vies: VIES phase interrupted");
      for (ViesCandidate c : candidates) {
        statuses.putIfAbsent(c.bpId, ViesService.STATUS_PENDING);
      }
    } finally {
      pool.shutdownNow();
    }
    return statuses;
  }

  /**
   * The single VIES call, isolated as an overridable seam.
   *
   * <p>It exists so unit tests can stub the network out on a SPY of this handler: the calls run
   * on a worker pool and Mockito's {@code mockStatic} is thread-local, so a static mock of
   * {@link ViesService} would silently NOT apply inside those threads and the suite would issue
   * real requests to {@code ec.europa.eu}. Stubbing this method instead is thread-safe.
   */
  String checkVat(String taxId) {
    return ViesService.checkVat(taxId).status;
  }

  /** A task that was cancelled by the phase timeout, or failed, yields "pending". */
  private static String statusOf(Future<String> future) {
    try {
      String status = future.get();
      return status != null ? status : ViesService.STATUS_PENDING;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ViesService.STATUS_PENDING;
    } catch (Exception e) {
      return ViesService.STATUS_PENDING;
    }
  }

  /**
   * Writes the conclusive VIES statuses back to {@code C_BPartner} with raw JDBC.
   *
   * <p>Two deliberate constraints, both matching {@code ContactsLocationAddressHandler}:
   * <ul>
   *   <li><b>JDBC, not OBDal.</b> {@code bp.setOBTIKVIESStatus(...)} would fire
   *       {@code ViesStatusObserver.onUpdate}, which calls {@code checkVat} a SECOND time for
   *       that partner — doubling traffic against a service that already rate-limits us.</li>
   *   <li><b>Only {@code V} and {@code I}.</b> A {@code P} result means nothing was learned;
   *       persisting it is a no-op at best and clobbers a previously known answer at worst.</li>
   * </ul>
   *
   * <p>ETP-5027 (QA F2): the caller needs to know what was actually WRITTEN, so this returns the
   * ids whose UPDATE reported an affected row. A failure no longer disappears into a
   * {@code log.warn} while the response reports success — the caller turns every unpersisted
   * conclusive answer into the {@code failed} count. Per-row errors are isolated so one bad id
   * cannot discard the rest of the batch.
   *
   * @return the bpIds whose status was successfully persisted; never null
   */
  Set<String> persistViesStatuses(Map<String, String> statuses) {
    Set<String> persisted = new LinkedHashSet<>();
    if (statuses == null || statuses.isEmpty()) {
      return persisted;
    }
    Map<String, String> conclusive = statuses.entrySet().stream()
        .filter(e -> ViesService.STATUS_VALID.equals(e.getValue())
            || ViesService.STATUS_INVALID.equals(e.getValue()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
            (a, b) -> a, LinkedHashMap::new));
    if (conclusive.isEmpty()) {
      return persisted;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE c_bpartner SET em_obtik_viesstatus = ? WHERE c_bpartner_id = ?")) {
      for (Map.Entry<String, String> e : conclusive.entrySet()) {
        persistOne(ps, e.getKey(), e.getValue(), persisted);
      }
    } catch (Exception e) {
      log.warn("validate-vies: could not persist VIES statuses: " + e.getMessage());
    }
    return persisted;
  }

  /**
   * One write-back. An UPDATE affecting zero rows (the partner disappeared between the gate and
   * the persist phase) counts as NOT persisted, exactly like a thrown error would — in both
   * cases the user's next reload will still show the partner as pending.
   */
  private void persistOne(PreparedStatement ps, String bpId, String status,
      Set<String> persisted) {
    try {
      ps.setString(1, status);
      ps.setString(2, bpId);
      if (ps.executeUpdate() > 0) {
        persisted.add(bpId);
      } else {
        log.warn("validate-vies: VIES status for " + bpId + " matched no C_BPartner row");
      }
    } catch (Exception e) {
      log.warn("validate-vies: could not persist VIES status for " + bpId + ": " + e.getMessage());
    }
  }

  // Package-private for unit testing of the pure data→JSON transformation logic.
  Map<String, BusinessPartner> loadBpMap(List<Map<String, Object>> rows) {
    List<String> bpIds = rows.stream()
        .filter(row -> {
          BigDecimal b = (BigDecimal) row.get("BPTaxBaseAmount");
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
      BigDecimal base = (BigDecimal) row.get("BPTaxBaseAmount");
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
      BigDecimal magnitude = (BigDecimal) row.get("BPTaxBaseAmount");
      copy.put("BPTaxBaseAmount", magnitude == null ? null : magnitude.negate());
      signed.add(copy);
    }
    return signed;
  }

  JSONArray collectInvoices(Set<Invoice> purch, Set<Invoice> sales,
      Map<String, String> invoiceKeys) throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    JSONArray arr = new JSONArray();
    for (Invoice inv : purch) {
      arr.put(buildInvoiceRow(inv, "Compra", sdf, invoiceKeys));
    }
    for (Invoice inv : sales) {
      arr.put(buildInvoiceRow(inv, "Venta", sdf, invoiceKeys));
    }
    return arr;
  }

  JSONObject buildInvoiceRow(Invoice inv, String type, SimpleDateFormat sdf,
      Map<String, String> invoiceKeys) throws Exception {
    BusinessPartner bp   = inv.getBusinessPartner();
    BigDecimal      base = inv.getSummedLineAmount() != null
        ? inv.getSummedLineAmount().abs().setScale(2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;
    String resolvedKey = invoiceKeys != null ? invoiceKeys.get(inv.getId()) : null;
    JSONObject row = new JSONObject();
    row.put("ref",    inv.getDocumentNo());
    row.put("date",   inv.getInvoiceDate() != null ? sdf.format(inv.getInvoiceDate()) : "");
    row.put("type",   type);
    row.put("party",  bp != null ? bp.getName() : "");
    row.put("nifIva", bp != null && bp.getTaxID() != null ? bp.getTaxID() : "");
    row.put("base",   base.toString());
    row.put("key",    resolvedKey != null ? resolvedKey : "");
    return row;
  }

  /**
   * Resolves the AEAT349 classification key (E/S/A/I) for each invoice in {@code invoices},
   * mirroring the join {@link org.openbravo.module.aeat349.es.AEAT3492010ReportDao
   * #getTaxBaseAmountPerBusinessPartner} uses to derive the key per-BusinessPartner, but
   * grouped per-invoice instead — this method does not need that DAO's amount-summing or
   * multi-currency conversion, only the classification key.
   *
   * <p>Edge case: an invoice could in principle have lines mapping to more than one key
   * (the HQL groups by (invoice, key), so a single invoice can produce multiple result rows).
   * This is a simplification for a rare case: whichever key has the most matching
   * {@code InvoiceTax} lines for that invoice wins; on an exact tie, the alphabetically first
   * key wins — the HQL's {@code order by ... trp.tributaryKey.name} guarantees rows for the
   * same invoice arrive in ascending key order, so "first encountered" is deterministic rather
   * than depending on undefined DB row-return order. A single invoice almost always maps to
   * exactly one key in practice for this report.
   *
   * @return a map of invoice id -&gt; AEAT349 key ("E"/"S"/"A"/"I"); never null.
   */
  Map<String, String> resolveInvoiceKeys(Set<Invoice> invoices, Collection<TaxRate> taxRates,
      String taxReportId) {
    if (invoices == null || invoices.isEmpty() || taxRates == null || taxRates.isEmpty()) {
      return new HashMap<>();
    }
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery("select i.id, trp.tributaryKey.name, count(it.id) "
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

    Map<String, String> keyByInvoice   = new HashMap<>();
    Map<String, Long>   lineCountByInv = new HashMap<>();
    for (Object[] row : rows) {
      String invId = (String) row[0];
      String key   = (String) row[1];
      Long   count = (Long)   row[2];
      Long prevCount = lineCountByInv.get(invId);
      if (prevCount == null || count > prevCount) {
        lineCountByInv.put(invId, count);
        keyByInvoice.put(invId, key);
      }
    }
    return keyByInvoice;
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
    String filename  = resolveFileName(request, period, year);

    Map<String, String> inputParams = buildGenerateInputParams(request, orgId, filename);

    OBTL_TaxReport_I report = (OBTL_TaxReport_I)
        Class.forName(taxReport.getJavaClassName()).getDeclaredConstructor().newInstance();

    HashMap<String, Object> result = report.generateElectronicFile(
        orgId, taxReport.getId(), acctSchema.getId(), yearId, periodIds, inputParams);
    writeGeneratedFile(result, filename + ".349", response);
  }

  // FileName: request param wins when provided, else fall back to the locally-computed default.
  private String resolveFileName(HttpServletRequest request, String period, int year) {
    String requestedFileName = request.getParameter("fileName");
    if (requestedFileName != null && !requestedFileName.isEmpty()) {
      return requestedFileName;
    }
    return "349_" + period + "_" + year;
  }

  // Extracted from handleGenerate to keep its cognitive complexity within budget.
  private Map<String, String> buildGenerateInputParams(HttpServletRequest request, String orgId,
      String filename) {
    Map<String, String> inputParams = new HashMap<>();
    inputParams.put("FileName", filename);

    // Substitutive/Navarra/Guipuzcoa are checkbox parameters: AEAT3492010Report's generateLine1
    // calls inputParams.get("Substitutive").equals("Y") — NPE if the key is absent — so all three
    // must ALWAYS be present in the map, "Y" or "N", mirroring classic's CHECK-type convention
    // (OBTL_TaxReportLauncher#generateFile always writes CHECK params regardless of value).
    inputParams.put("Substitutive", "Y".equals(request.getParameter("substitutive")) ? "Y" : "N");
    inputParams.put("Navarra",      "Y".equals(request.getParameter("navarra"))      ? "Y" : "N");
    inputParams.put("Guipuzcoa",    "Y".equals(request.getParameter("guipuzcoa"))    ? "Y" : "N");

    applyContactParams(request, orgId, inputParams);
    applyOptionalTextParams(request, inputParams);
    return inputParams;
  }

  // Phone and Contact: AEAT3492010Report checks constantParameters first (TaxReport config),
  // then falls back to inputParams. Query params override; fall back to AD_OrgInformation /
  // current user so generation works even without TaxReport pre-configuration.
  private void applyContactParams(HttpServletRequest request, String orgId,
      Map<String, String> inputParams) {
    String phone   = request.getParameter("phone");
    String contact = request.getParameter("contact");
    if (phone == null || phone.isEmpty()) {
      phone = resolveOrgPhone(orgId);
    }
    if (contact == null || contact.isEmpty()) {
      contact = OBContext.getOBContext().getUser().getName();
    }
    if (phone   != null && !phone.isEmpty())   inputParams.put("Phone",   phone);
    if (contact != null && !contact.isEmpty()) inputParams.put("Contact", contact);
  }

  // FormerStatement/RepresentativeTaxId are TEXT parameters — classic omits empty TEXT
  // parameters from inputParams entirely (OBTL_TaxReportLauncher#generateFile), so mirror
  // that here rather than sending an empty string.
  private void applyOptionalTextParams(HttpServletRequest request, Map<String, String> inputParams) {
    String formerStatement     = request.getParameter("formerStatement");
    String representativeTaxId = request.getParameter("representativeTaxId");
    if (formerStatement != null && !formerStatement.isEmpty()) {
      inputParams.put("FormerStatement", formerStatement);
    }
    if (representativeTaxId != null && !representativeTaxId.isEmpty()) {
      inputParams.put("RepresentativeTaxId", representativeTaxId);
    }
  }

  // ── resolution helpers ────────────────────────────────────────────

  private String resolveOrgNif(String orgId) {
    OBCriteria<OrganizationInformation> crit =
        OBDal.getInstance().createCriteria(OrganizationInformation.class);
    crit.add(Restrictions.in(OrganizationInformation.PROPERTY_ORGANIZATION + ".id",
        Arrays.asList(orgId, "0")));
    crit.addOrder(Order.desc(OrganizationInformation.PROPERTY_ORGANIZATION + ".id"));
    crit.setMaxResults(1);
    List<OrganizationInformation> list = crit.list();
    if (list.isEmpty()) return "";
    String taxId = list.get(0).getTaxID();
    return taxId != null ? taxId : "";
  }

  private String resolveOrgPhone(String orgId) {
    OBCriteria<OrganizationInformation> crit =
        OBDal.getInstance().createCriteria(OrganizationInformation.class);
    crit.add(Restrictions.eq(OrganizationInformation.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.setMaxResults(1);
    List<OrganizationInformation> list = crit.list();
    if (list.isEmpty()) return null;
    // OrganizationInformation has no phone directly; try the org's user contact phone
    org.openbravo.model.ad.access.User contact = list.get(0).getUserContact();
    if (contact == null) return null;
    String phone = contact.getPhone();
    return phone != null && !phone.isEmpty() ? phone : contact.getAlternativePhone();
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

  private TaxReport findTaxReport(String orgId, String searchKey) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.in(TaxReport.PROPERTY_ORGANIZATION + ".id", Arrays.asList(orgId, "0")));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_SEARCHKEY, searchKey));
    crit.addOrder(Order.desc(TaxReport.PROPERTY_ORGANIZATION + ".id"));
    crit.setMaxResults(1);
    List<TaxReport> list = crit.list();
    return list.isEmpty() ? null : list.get(0);
  }

}
