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

package com.etendoerp.go.schemaforge;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * ETP-5726: refuses, on the MCP channel only, a currency change of a draft order whose price list
 * is in another currency.
 *
 * <p>The screen lets the user change the currency and keeps the price list as it was (the order's
 * prices then silently belong to a different currency); REST single, REST batch and the SPA are
 * deliberately left as they are. An agent cannot see that mismatch, so {@code etendo_update} answers
 * a 422 that says what to fix. Declared in {@code neo-headless.md} §4.12.9.
 *
 * <p>Called explicitly from the {@code handle()} of the order headers that adopt it, like
 * {@link DocumentRateGuards}, so the abstract header keeps its method budget (java:S1448). The rule
 * is structural: it compares the currency of the document with the currency of its price list.
 */
final class OrderCurrencyPriceListGuard {

  private static final Logger log = LogManager.getLogger(OrderCurrencyPriceListGuard.class);
  private static final String STATUS_DRAFT = "DR";
  private static final int SC_UNPROCESSABLE = 422;

  private OrderCurrencyPriceListGuard() {
  }

  /**
   * Refuses (422) an MCP update that moves a draft order to a currency its price list is not in.
   *
   * @param context the request; only an MCP {@code PUT} of an existing record that carries a
   *                {@code currency} is evaluated
   * @return the 422 refusal, or {@code null} to carry on (also when it cannot decide: total)
   */
  static NeoResponse refuseCurrencyOutsidePriceList(NeoContext context) {
    if (context == null || !context.isMcpOrigin()
        || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !"PUT".equals(context.getHttpMethod())
        || StringUtils.isBlank(context.getRecordId())
        || context.getRequestBody() == null
        || !context.getRequestBody().has(Order.PROPERTY_CURRENCY)) {
      return null;
    }
    try {
      return evaluate(context);
    } catch (Exception e) {
      log.warn("[ETP-5726] Could not evaluate the currency of order {}: {}",
          context.getRecordId(), e.getMessage());
      return null;
    }
  }

  private static NeoResponse evaluate(NeoContext context) {
    Order stored = OBDal.getInstance().get(Order.class, context.getRecordId());
    JSONObject body = context.getRequestBody();
    String newCurrencyId = idOf(body.opt(Order.PROPERTY_CURRENCY));
    if (stored == null || !STATUS_DRAFT.equals(stored.getDocumentStatus()) || newCurrencyId == null
        || stored.getCurrency() == null || newCurrencyId.equals(stored.getCurrency().getId())) {
      return null;
    }
    // The SPA sends sparse bodies: the stored price list stands when the body does not carry one.
    String priceListId = idOf(body.opt(Order.PROPERTY_PRICELIST));
    PriceList priceList = priceListId != null ? readablePriceList(priceListId)
        : stored.getPriceList();
    if (priceList == null || priceList.getCurrency() == null
        || newCurrencyId.equals(priceList.getCurrency().getId())) {
      return null;
    }
    Currency requested = OBDal.getInstance().get(Currency.class, newCurrencyId);
    return refusal(requested != null ? requested.getISOCode() : newCurrencyId, priceList);
  }

  /**
   * The price list the caller named, read through a criteria so the readable client/organization
   * filters stay on ({@code OBDal.get} only checks entity access). A list the session cannot read
   * is {@code null}: the guard then abstains and nothing about it is echoed.
   */
  private static PriceList readablePriceList(String id) {
    OBCriteria<PriceList> criteria = OBDal.getInstance().createCriteria(PriceList.class);
    criteria.add(Restrictions.eq(PriceList.PROPERTY_ID, id));
    criteria.setMaxResults(1);
    return (PriceList) criteria.uniqueResult();
  }

  private static NeoResponse refusal(String requestedIso, PriceList priceList) {
    NeoResponse response = NeoResponse.error(SC_UNPROCESSABLE,
        "The order's price list '" + priceList.getName() + "' is in "
            + priceList.getCurrency().getISOCode() + ", not in the requested currency "
            + requestedIso + ".");
    try {
      response.getBody().put("hint", "Set a price list in " + requestedIso
          + " on the same update (priceList), or keep the order's current currency.");
    } catch (Exception e) {
      log.debug("[ETP-5726] Hint not attached: {}", e.getMessage());
    }
    return response;
  }

  /** The id a body value carries: the string itself, or the {@code id} of a reference object. */
  private static String idOf(Object value) {
    if (value instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) value).optString("id", null));
    }
    return value == null || JSONObject.NULL.equals(value) ? null
        : StringUtils.trimToNull(value.toString());
  }
}
