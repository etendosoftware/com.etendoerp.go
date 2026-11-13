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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.RemittanceTypeParameter;

/**
 * ETP-6148 — the two selectors of the remittance header, served by {@link RemittanceHeaderHandler}
 * rather than through a {@code SelectorContextPolicy}, which would leak into every
 * financial-account selector of the instance. Split out of the handler; the request, the client
 * and the IBAN check are still read through the handler's seams.
 */
final class RemittanceHeaderSelectors {

  private static final int SELECTOR_DEFAULT_LIMIT = 20;
  private static final int SELECTOR_MAX_LIMIT = 100;
  private static final String Q_CLIENT = "client";
  private static final String Q_SEARCH = "search";

  private RemittanceHeaderSelectors() {
  }

  /**
   * The selector of {@code field}, or {@code null} for a field this customization does not serve
   * (the generic selector then runs).
   *
   * @param handler the remittance header customization (its seams)
   * @param field   the selector field, by API key or AD column name
   * @return the selector answer, or {@code null}
   * @throws JSONException if the answer cannot be built
   */
  static NeoResponse handle(RemittanceHeaderHandler handler, String field) throws JSONException {
    if (matches(field, RemittanceHeaderHandler.FIELD_ACCOUNT,
        RemittanceHeaderHandler.COLUMN_ACCOUNT)) {
      return accountSelector(handler);
    }
    if (matches(field, RemittanceHeaderHandler.FIELD_TYPE, RemittanceHeaderHandler.COLUMN_TYPE)) {
      return typeSelector(handler);
    }
    return null;
  }

  /**
   * Whether {@code field} names a field by its API key or its column, ignoring case.
   *
   * @param field  the field asked for
   * @param key    the API key
   * @param column the AD column name
   * @return whether they match
   */
  static boolean matches(String field, String key, String column) {
    return key.equalsIgnoreCase(field) || column.equalsIgnoreCase(field);
  }

  /**
   * The accounts a remittance can be made on ({@link RemittanceHeaderHandler#eligibilityRefusal})
   * that the user can read: active Bank accounts with an active link to a remittance payment
   * method, WITH OR WITHOUT a valid IBAN — the remittance header lets the user give the account
   * one. The link condition is {@link RemittanceSepaProvisioning#activeRemittanceLinkExists}, the
   * one {@link RemittanceSepaProvisioning#hasRemittanceMethodLink} runs, so the selector and the
   * write check cannot disagree. Every item carries {@code ibanValid}
   * ({@link RemittanceHeaderHandler#hasValidIban}) and the stored {@code iban} (or {@code null}), so
   * the SPA can mark the accounts that still need one. A client has a handful of bank accounts:
   * every candidate is read, and the page is cut from that one list, so {@code totalCount} and
   * {@code hasMore} describe the same set as the items.
   */
  private static NeoResponse accountSelector(RemittanceHeaderHandler handler)
      throws JSONException {
    SelectorPage page = SelectorPage.fromRequest(handler.currentRequest());
    String hql = "select a from " + FIN_FinancialAccount.ENTITY_NAME + " as a"
        + " where a.client.id = :client and a.active = true and a.type = :bank"
        + " and a.organization.id in (:orgs)"
        + " and " + RemittanceSepaProvisioning.activeRemittanceLinkExists("a")
        + (page.search != null ? " and lower(a.name) like :search" : "")
        + " order by a.name, a.id";
    org.hibernate.query.Query<FIN_FinancialAccount> rows = OBDal.getInstance().getSession()
        .createQuery(hql, FIN_FinancialAccount.class);
    rows.setParameter(Q_CLIENT, handler.currentClientId());
    rows.setParameterList("orgs", List.of(OBContext.getOBContext().getReadableOrganizations()));
    if (page.search != null) {
      rows.setParameter(Q_SEARCH, page.likePattern());
    }
    rows.setParameter("bank", RemittanceHeaderHandler.TYPE_BANK);
    rows.setParameter(RemittanceSepaProvisioning.PARAM_MARKER,
        RemittanceSepaProvisioning.PARAM_SCHEME);
    List<FIN_FinancialAccount> accounts = rows.list();
    JSONArray items = new JSONArray();
    int end = Math.min(accounts.size(), page.offset + page.limit);
    for (int i = Math.min(page.offset, end); i < end; i++) {
      FIN_FinancialAccount account = accounts.get(i);
      JSONObject item = item(account.getId(), account.getIdentifier());
      String iban = StringUtils.trimToNull(account.getIBAN());
      item.put("iban", iban != null ? iban : JSONObject.NULL);
      item.put("ibanValid", RemittanceHeaderHandler.hasValidIban(account));
      items.put(item);
    }
    return page.response(items, (long) accounts.size());
  }

  /**
   * The provisioned remittance types linked to the account the form already holds (request
   * parameter {@value RemittanceHeaderHandler#PARAM_ACCOUNT}). No account, no types.
   */
  private static NeoResponse typeSelector(RemittanceHeaderHandler handler) throws JSONException {
    HttpServletRequest request = handler.currentRequest();
    SelectorPage page = SelectorPage.fromRequest(request);
    String accountId = findParamCaseInsensitive(request, RemittanceHeaderHandler.PARAM_ACCOUNT);
    if (accountId == null) {
      return page.response(new JSONArray(), 0L);
    }
    String where = " from " + RemittanceType.ENTITY_NAME + " as t"
        + " where t.client.id = :client and t.active = true"
        + " and exists (select 1 from " + RemittanceTypeParameter.ENTITY_NAME + " as p"
        + "   where p.remittanceType.id = t.id and p.name = :marker)"
        + " and exists (select 1 from " + FinAccPaymentMethod.ENTITY_NAME + " as l"
        + "   where l.account.id = :account and l.paymentMethod.id = t.paymentMethod.id"
        + "   and l.active = true)"
        + (page.search != null ? " and lower(t.name) like :search" : "");
    org.hibernate.query.Query<Long> count = OBDal.getInstance().getSession()
        .createQuery("select count(t.id)" + where, Long.class);
    org.hibernate.query.Query<RemittanceType> rows = OBDal.getInstance().getSession()
        .createQuery("select t" + where + " order by t.name, t.id", RemittanceType.class);
    for (org.hibernate.query.Query<?> query : List.of(count, rows)) {
      query.setParameter(Q_CLIENT, handler.currentClientId());
      query.setParameter(RemittanceSepaProvisioning.PARAM_MARKER,
          RemittanceSepaProvisioning.PARAM_SCHEME);
      query.setParameter("account", accountId);
      if (page.search != null) {
        query.setParameter(Q_SEARCH, page.likePattern());
      }
    }
    List<RemittanceType> types = rows.setFirstResult(page.offset).setMaxResults(page.limit).list();
    JSONArray items = new JSONArray();
    for (RemittanceType type : types) {
      items.put(item(type.getId(), type.getIdentifier()));
    }
    return page.response(items, count.uniqueResult());
  }

  private static JSONObject item(String id, String label) throws JSONException {
    JSONObject item = new JSONObject();
    item.put("id", id);
    item.put("label", label);
    return item;
  }

  private static String findParamCaseInsensitive(HttpServletRequest request, String name) {
    if (request == null || request.getParameterMap() == null) {
      return null;
    }
    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
      String[] values = entry.getValue();
      if (name.equalsIgnoreCase(entry.getKey()) && values != null && values.length > 0) {
        String value = StringUtils.trimToNull(values[0]);
        if (value != null) {
          return value;
        }
      }
    }
    return null;
  }

  /** Paging and search of a selector request ({@code q}, {@code limit}, {@code offset}). */
  private static final class SelectorPage {
    private final String search;
    private final int limit;
    private final int offset;

    private SelectorPage(String search, int limit, int offset) {
      this.search = search;
      this.limit = limit;
      this.offset = offset;
    }

    static SelectorPage fromRequest(HttpServletRequest request) {
      String search = request != null ? StringUtils.trimToNull(request.getParameter("q")) : null;
      return new SelectorPage(search,
          intParam(request, "limit", SELECTOR_DEFAULT_LIMIT, 1, SELECTOR_MAX_LIMIT),
          intParam(request, "offset", 0, 0, Integer.MAX_VALUE));
    }

    String likePattern() {
      return "%" + search.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    NeoResponse response(JSONArray items, Long total) throws JSONException {
      long totalCount = total != null ? total : 0L;
      JSONObject result = new JSONObject();
      result.put("items", items);
      result.put("columns", new JSONArray());
      result.put("totalCount", totalCount);
      result.put("hasMore", offset + (long) limit < totalCount);
      return NeoResponse.ok(result);
    }

    private static int intParam(HttpServletRequest request, String name, int fallback, int min,
        int max) {
      String raw = request != null ? StringUtils.trimToNull(request.getParameter(name)) : null;
      if (raw == null) {
        return fallback;
      }
      try {
        return Math.min(Math.max(Integer.parseInt(raw), min), max);
      } catch (NumberFormatException e) {
        return fallback;
      }
    }
  }
}
