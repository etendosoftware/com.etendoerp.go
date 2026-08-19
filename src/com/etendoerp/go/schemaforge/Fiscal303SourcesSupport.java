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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.ScrollableResults;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.CashVATOperationType;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;

/**
 * Builds the per-invoice {@code sources} rows returned alongside the AEAT 303 boxes by
 * {@code GET /neo/fiscal303/boxes} — on behalf of {@link Fiscal303BoxesHandler}, which
 * constructs one instance of this class and delegates to it from {@code computeBoxes(...)}.
 *
 * <p>Extracted verbatim from {@link Fiscal303BoxesHandler} (ETP-4755) purely to keep that class's
 * method count under the SonarQube {@code java:S1448} threshold, mirroring how the
 * "submission" concern was already split out into {@link Fiscal303SubmissionSupport}. This class
 * is exactly the "invoice sources" concern: iterating {@code C_INVOICETAX} for the tracked tax
 * rates and grouping the results into one row per invoice. No behavior change — every method here
 * is a direct move, calling back into {@code owner} only for the shared {@code BOXES} constant
 * and the {@code round} rounding helper, which stay declared exactly where they already were.
 * {@code owner}'s package-private members are reachable here because this class lives in the same
 * package, not because of any inheritance relationship.</p>
 */
class Fiscal303SourcesSupport {

  private final Fiscal303BoxesHandler owner;

  Fiscal303SourcesSupport(Fiscal303BoxesHandler owner) {
    this.owner = owner;
  }

  /**
   * Iterates C_INVOICETAX for all tracked tax rates and builds a per-invoice source row.
   * Groups multiple tax lines of the same invoice into one row.
   */
  List<Map<String, Object>> collectSources(
      Organization org, List<Period> periods,
      AEAT303Report2014Dao dao303,
      Map<String, List<Integer>> rateToBoxes) {

    if (rateToBoxes.isEmpty()) return Collections.emptyList();

    List<TaxRate> allRates = buildRatesList(rateToBoxes);
    Map<String, Map<String, Object>> byInvoice = new LinkedHashMap<>();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    ScrollableResults sr = dao303.getInvoiceTax(
        org, allRates, periods, CashVATOperationType.ONLY_NONCASHVAT);
    try {
      while (sr.next()) {
        InvoiceTax it = (InvoiceTax) sr.get(0);
        Invoice inv   = it.getInvoice();
        Map<String, Object> row = byInvoice.computeIfAbsent(inv.getId(), k -> buildNewInvoiceRow(inv, sdf));
        accumulateInvoiceTax(row, it, rateToBoxes);
        OBDal.getInstance().getSession().evict(it);
        OBDal.getInstance().getSession().evict(inv);
      }
    } finally {
      sr.close();
    }

    List<Map<String, Object>> result = new ArrayList<>(byInvoice.values());
    result.forEach(this::finalizeInvoiceRow);
    return result;
  }

  private List<TaxRate> buildRatesList(Map<String, List<Integer>> rateToBoxes) {
    List<TaxRate> allRates = new ArrayList<>();
    for (String id : rateToBoxes.keySet()) {
      TaxRate tr = OBDal.getInstance().get(TaxRate.class, id);
      if (tr != null) allRates.add(tr);
    }
    return allRates;
  }

  private Map<String, Object> buildNewInvoiceRow(Invoice inv, SimpleDateFormat sdf) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("ref",   inv.getDocumentNo());
    r.put("date",  sdf.format(inv.getInvoiceDate()));
    String cat = inv.getDocumentType().getDocumentCategory();
    r.put("type",  "ARI".equals(cat) || "ARI_RM".equals(cat) ? "Venta" : "Compra");
    r.put("party", inv.getBusinessPartner() != null ? inv.getBusinessPartner().getName() : "");
    r.put("base",  BigDecimal.ZERO);
    r.put("vat",   BigDecimal.ZERO);
    r.put(Fiscal303BoxesHandler.BOXES, new java.util.LinkedHashSet<Integer>());
    return r;
  }

  private void accumulateInvoiceTax(Map<String, Object> row, InvoiceTax it,
      Map<String, List<Integer>> rateToBoxes) {
    BigDecimal base = it.getTaxableAmount() != null ? it.getTaxableAmount().abs() : BigDecimal.ZERO;
    BigDecimal tax  = it.getTaxAmount()     != null ? it.getTaxAmount().abs()     : BigDecimal.ZERO;
    row.put("base", owner.round(((BigDecimal) row.get("base")).add(base)));
    row.put("vat",  owner.round(((BigDecimal) row.get("vat")).add(tax)));
    List<Integer> boxes = rateToBoxes.get(it.getTax().getId());
    if (boxes != null) {
      @SuppressWarnings("unchecked")
      java.util.LinkedHashSet<Integer> bSet =
          (java.util.LinkedHashSet<Integer>) row.get(Fiscal303BoxesHandler.BOXES);
      bSet.addAll(boxes);
    }
  }

  void finalizeInvoiceRow(Map<String, Object> row) {
    @SuppressWarnings("unchecked")
    java.util.LinkedHashSet<Integer> bSet =
        (java.util.LinkedHashSet<Integer>) row.get(Fiscal303BoxesHandler.BOXES);
    List<Integer> sorted = new ArrayList<>(bSet);
    Collections.sort(sorted);
    StringBuilder sb = new StringBuilder();
    for (Integer bx : sorted) {
      if (sb.length() > 0) {
        sb.append(",");
      }
      sb.append(bx);
    }
    row.put(Fiscal303BoxesHandler.BOXES, sb.toString());
    BigDecimal base = (BigDecimal) row.get("base");
    BigDecimal vat  = (BigDecimal) row.get("vat");
    row.put("total", owner.round(base.add(vat)));
  }
}
