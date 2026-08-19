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
import org.openbravo.module.aeat349.es.AEAT3492010ReportDao;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

class Fiscal349BoxesHandler extends AbstractFiscalHandler {

  private static final String OPERATORS = "operators";
  private static final String GENERATE  = "generate";

  Fiscal349BoxesHandler(NeoServlet servlet) {
    super(servlet);
  }

  @Override
  protected boolean isKnownEntity(String entityName) {
    return OPERATORS.equals(entityName) || GENERATE.equals(entityName)
        || MODIFIED.equals(entityName);
  }

  @Override
  protected boolean allowsPost(String entityName) {
    return GENERATE.equals(entityName);
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

    Map<String, BigDecimal> summaryByKey = new LinkedHashMap<>();
    for (String k : Arrays.asList("E", "S", "A", "I")) summaryByKey.put(k, BigDecimal.ZERO);

    List<Map<String, Object>> all = new ArrayList<>(purchaseBP);
    all.addAll(salesBP);

    Map<String, BusinessPartner> bpMap        = loadBpMap(all);
    JSONArray                    operatorsArr = buildOperatorsArray(all, bpMap, summaryByKey);

    JSONObject summary = new JSONObject();
    for (Map.Entry<String, BigDecimal> e : summaryByKey.entrySet()) {
      summary.put("total" + e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP).toString());
    }

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
      return "pending";
    }
    String status = bp.getOBTIKVIESStatus();
    if ("V".equals(status)) {
      return "valid";
    }
    if ("I".equals(status)) {
      return "invalid";
    }
    return "pending";
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

  JSONArray buildOperatorsArray(List<Map<String, Object>> rows,
      Map<String, BusinessPartner> bpMap, Map<String, BigDecimal> summaryByKey) throws Exception {
    JSONArray arr = new JSONArray();
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
      arr.put(op);
      if (key != null && summaryByKey.containsKey(key)) {
        summaryByKey.put(key, summaryByKey.get(key).add(base));
      }
    }
    return arr;
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
