/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the read-only {@code accounting} entity of the {@code calendar} spec's
 * Accounting subtab — a year-scoped, aggregated trial-balance-style view of {@code
 * FinancialMgmtAccountingFact} (Fact_Acct) rows.
 *
 * <h2>Why this is a hand-written HQL query, not the stored {@code
 * FinancialMgmtAccountingFactEndYearHQL} AD_Table view text</h2>
 *
 * <p>The {@code accounting} entity's underlying AD_Table ({@code
 * FinancialMgmtAccountingFactEndYearHQL}, id {@code A45FDE07216C40FBA472E7B91F7273E4}) is an
 * HQL-backed table read via {@code AD_Table.hqlquery} (confirmed by direct DB query). Its stored
 * HQL does <b>not</b> expose a {@code year} column/property on the result at all — the {@code
 * GROUP BY} includes {@code p.year.id} but it is never {@code SELECT}ed, and the one row it does
 * expose that looks year-related ({@code C_Year_Close_V_ID}) is actually {@code pc.id} (a {@code
 * FinancialMgmtPeriodControl} id), not the Year's own id. The stored HQL also relies on Etendo's
 * {@code @additional_filters@} template-substitution mechanism (normally resolved by the classic
 * AD_Tab/window pipeline, not something directly invocable from a standalone HQL query call) and
 * an organization-security join (`pc.organization.id = org.periodControlAllowedOrganization.id`)
 * that is redundant here since this handler always runs under {@link OBContext#setAdminMode()}.
 *
 * <p>Given that, this handler queries {@code FinancialMgmtAccountingFact} directly, scoping by
 * year through the real association chain confirmed against the DAL model
 * ({@code AccountingFact.period.year.id} — {@code fa.period} and {@code Period.year} both exist
 * as real, DAL-modeled FKs), and reproduces the stored view's grouping/netting logic (per
 * account + entry type, non-zero net only) without the org-security join. This mirrors the
 * existing {@link com.etendoerp.go.schemaforge.FactAcctHandler}'s direct-HQL-over-AccountingFact
 * approach in this same package (a real, already-shipping precedent for exposing Fact_Acct rows
 * to the frontend via a custom NeoHandler instead of the generic entity CRUD path).
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'year-accounting'} on the {@code accounting}
 * ETGO_SF_ENTITY record for the {@code calendar} spec.
 */
@Named("year-accounting")
public class YearAccountingHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(YearAccountingHandler.class);

  // Only the year-end closing/regularization entry types created BY the Close Year process
  // (CreateRegFactAcct) belong on this tab — matching Classic's End Year Close window, whose
  // stored FinancialMgmtAccountingFactEndYearHQL view filters on this exact same set. Without
  // this filter, every regular "Actual" (type='A') transactional posting for the year's periods
  // leaks in too, which is wrong for a not-yet-closed year (confirmed live: Classic correctly
  // shows "No data in grid" for a not-yet-closed year; this handler was wrongly returning 302
  // type='A' rows for the same year before this fix).
  private static final List<String> YEAR_END_CLOSING_TYPES = List.of("O", "C", "D", "R");

  private static final String HQL =
      "select max(fa.id), fa.account.searchKey, fa.type, sum(fa.debit), sum(fa.credit), max(fa.description) "
          + "from FinancialMgmtAccountingFact fa "
          + "where fa.period.year.id = :yearId "
          + "and fa.type in (:closingTypes) "
          + "group by fa.account.searchKey, fa.type "
          + "having sum(fa.credit - fa.debit) <> 0 "
          + "order by fa.account.searchKey";

  @Override
  public NeoResponse handle(NeoContext context) {
    // Only intercept CRUD GET list requests (no recordId = list, matching FactAcctHandler's
    // own guard shape) — anything else (single-record GET, POST/PUT/DELETE) falls through to
    // default CRUD handling, since this entity has no writable fields anyway.
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !"GET".equals(context.getHttpMethod())
        || context.getRecordId() != null) {
      return null;
    }

    String yearId = context.getQueryParams() != null
        ? context.getQueryParams().get("year")
        : null;
    if (StringUtils.isBlank(yearId)) {
      return NeoResponse.error(400, "Missing required query param: year");
    }

    try {
      OBContext.setAdminMode();
      try {
        return NeoResponse.ok(buildResponseBody(fetchRows(yearId)));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching accounting entries for year {}", yearId, e);
      return NeoResponse.error(500, "Error fetching accounting entries: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object[]> fetchRows(String yearId) {
    return OBDal.getInstance().getSession()
        .createQuery(HQL)
        .setParameter("yearId", yearId)
        .setParameterList("closingTypes", YEAR_END_CLOSING_TYPES)
        .list();
  }

  private JSONObject buildResponseBody(List<Object[]> rows) throws JSONException {
    JSONArray data = new JSONArray();
    for (Object[] row : rows) {
      JSONObject entry = new JSONObject();
      entry.put("id", row[0]);
      entry.put("account", nullToEmpty((String) row[1]));
      entry.put("factaccttype", nullToEmpty((String) row[2]));
      entry.put("debit", nullToZero((BigDecimal) row[3]));
      entry.put("credit", nullToZero((BigDecimal) row[4]));
      entry.put("description", nullToEmpty((String) row[5]));
      data.put(entry);
    }
    JSONObject body = new JSONObject();
    body.put("data", data);
    return body;
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }

  private static BigDecimal nullToZero(BigDecimal amount) {
    return amount != null ? amount : BigDecimal.ZERO;
  }
}
