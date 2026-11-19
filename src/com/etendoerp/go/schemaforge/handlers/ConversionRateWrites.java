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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.handlers.ConversionRateRules.Close;
import com.etendoerp.go.schemaforge.handlers.ConversionRateRules.Plan;
import com.etendoerp.go.schemaforge.handlers.ConversionRateRules.Range;

/**
 * ETP-6365: the writes of the {@code conversion-rates} customization — create a manual range,
 * change its rate or start, delete it — always on both directions of the pair.
 *
 * <p><b>Validate everything, then write.</b> Every refusal ({@link ConversionRateRefusal}) is
 * thrown before the first row is touched. The writes themselves run on the request's session and
 * are only flushed, never committed: the channel owns the transaction (REST single and MCP commit
 * at the end of the request, the batch at the end of the batch), and the caller rolls back on any
 * failure, so a range never ends up without its inverse.</p>
 *
 * <p><b>Order of the writes.</b> Core's {@code ConversionRateEventHandler} refuses (20504) a row
 * whose dates overlap another row of the pair at flush time, and Hibernate flushes inserts before
 * updates. A range that is being shortened is therefore written and flushed first, then the new or
 * moved range, then a range that grows.</p>
 *
 * <p>One instance per request: it remembers the ISO codes of the pair it works on, so a failure
 * raised by the database can be worded with them ({@link #pairIsoCodes()}).</p>
 */
class ConversionRateWrites {

  static final String F_TO_CURRENCY = "toCurrency";
  static final String F_CURRENCY = "currency";
  static final String F_RATE = "multipleRateBy";
  static final String F_DIVIDE_RATE = "divideRateBy";
  static final String F_VALID_FROM = "validFromDate";
  static final String F_VALID_TO = "validToDate";
  static final String F_RATE_TYPE = "conversionRateType";
  static final String SPOT_RATE_TYPE = "S";
  private static final String KEY_ID = "id";

  /** Fields a stored range never changes: sending another value is refused. */
  private static final Set<String> FIXED_REFERENCES = Set.of(F_CURRENCY, F_TO_CURRENCY);

  private final ConversionRateStore store;
  private String fromIso;
  private String toIso;

  /** A validated create: everything the two rows need. */
  record CreateRequest(String clientId, Currency orgCurrency, Currency toCurrency, BigDecimal rate,
      LocalDateTime validFrom) {
  }

  /** A validated create and what it does to the other ranges of the pair. */
  record CreatePlan(CreateRequest request, Plan plan) {
  }

  ConversionRateWrites(ConversionRateStore store) {
    this.store = store;
  }

  /** The {@code [from, to]} ISO codes of the pair being written, for a database refusal. */
  String[] pairIsoCodes() {
    return new String[] { fromIso, toIso };
  }

  // --- create --------------------------------------------------------------------------------

  /**
   * Validates a create body ({@code toCurrency}, {@code multipleRateBy}, {@code validFromDate})
   * against the request's organization currency.
   *
   * @throws ConversionRateRefusal for a missing or wrong value
   */
  CreateRequest parseCreate(JSONObject body) {
    String clientId = store.currentClientId();
    String orgCurrencyId = store.orgCurrencyId();
    if (orgCurrencyId == null) {
      throw new ConversionRateRefusal(ConversionRateRefusal.SC_UNPROCESSABLE,
          ConversionRateRefusal.NO_ORG_CURRENCY);
    }
    Currency orgCurrency = store.currency(orgCurrencyId);
    Currency to = resolveTargetCurrency(body.opt(F_TO_CURRENCY), clientId);
    fromIso = orgCurrency.getISOCode();
    toIso = to.getISOCode();
    if (orgCurrencyId.equals(to.getId())) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.ORG_CURRENCY, toIso);
    }
    if (store.autoCurrencyIds(clientId, orgCurrencyId).contains(to.getId())) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.AUTO_CURRENCY, toIso);
    }
    BigDecimal rate = requireRate(body.opt(F_RATE));
    LocalDateTime validFrom = requireValidFrom(body.opt(F_VALID_FROM));
    return new CreateRequest(clientId, orgCurrency, to, rate, validFrom);
  }

  /**
   * Plans a validated create against the stored ranges of the pair.
   *
   * @throws ConversionRateRefusal {@code OVERLAP} when the new range collides with another
   */
  CreatePlan planCreate(CreateRequest request) {
    List<Range> ranges = store.rangesOfPair(request.clientId(), request.orgCurrency().getId(),
        request.toCurrency().getId());
    Plan plan = ConversionRateRules.planCreate(ranges, request.validFrom());
    if (plan.overlap()) {
      throw ConversionRateRefusal.overlap(fromIso, toIso);
    }
    return new CreatePlan(request, plan);
  }

  /**
   * Closes the open ranges the plan names, then inserts the forward and the inverse row.
   *
   * @return the forward row (organization currency to the other one)
   */
  ConversionRate applyCreate(CreatePlan createPlan) {
    for (Close close : createPlan.plan().closes()) {
      writeEnd(close);
    }
    flush();
    CreateRequest request = createPlan.request();
    BigDecimal inverse = ConversionRateRules.inverse(request.rate());
    ConversionRate forward = newRow(request.clientId(), request.orgCurrency(),
        request.toCurrency(), request.rate(), inverse, request.validFrom(), null);
    newRow(request.clientId(), request.toCurrency(), request.orgCurrency(), inverse,
        request.rate(), request.validFrom(), null);
    flush();
    return forward;
  }

  // --- update --------------------------------------------------------------------------------

  /**
   * Changes the rate and/or the start of the range {@code row} belongs to, on both directions.
   * {@code multipleRateBy} is the rate of {@code row} itself; its twin gets the inverse. Moving the
   * start re-closes the range this one closed when it was created.
   *
   * @throws ConversionRateRefusal when the range is locked, a fixed field changes or the new start
   *     collides with another range
   */
  ConversionRate applyUpdate(ConversionRate row, JSONObject body) {
    rememberPair(row);
    checkEditable(row);
    rejectFixedFields(row, body);
    BigDecimal rate = body.has(F_RATE) ? requireRate(body.opt(F_RATE)) : null;
    LocalDateTime newFrom = body.has(F_VALID_FROM) ? requireValidFrom(body.opt(F_VALID_FROM))
        : null;

    List<Range> ranges = rangesOf(row);
    Range target = targetOf(ranges, row);
    if (newFrom != null && !newFrom.equals(target.validFrom())) {
      moveStart(ranges, target, newFrom);
    }
    if (rate != null) {
      writeRate(row, target, rate);
    }
    flush();
    return row;
  }

  private void moveStart(List<Range> ranges, Range target, LocalDateTime newFrom) {
    List<Range> others = new ArrayList<>(ranges);
    others.remove(target);
    Plan plan = ConversionRateRules.planMove(others, target, newFrom);
    if (plan.overlap()) {
      throw ConversionRateRefusal.overlap(fromIso, toIso);
    }
    Close adjacent = plan.closes().isEmpty() ? null : plan.closes().get(0);
    boolean shrinksAdjacent = adjacent != null
        && adjacent.newValidTo().isBefore(adjacent.range().validTo());
    if (shrinksAdjacent) {
      writeEnd(adjacent);
      flush();
    }
    for (String id : target.rowIds()) {
      store.rate(id).setValidFromDate(ConversionRateStore.toDate(newFrom));
    }
    flush();
    if (adjacent != null && !shrinksAdjacent) {
      writeEnd(adjacent);
    }
  }

  private void writeRate(ConversionRate row, Range target, BigDecimal rate) {
    BigDecimal inverse = ConversionRateRules.inverse(rate);
    row.setMultipleRateBy(rate);
    row.setDivideRateBy(inverse);
    String twinId = row.getId().equals(target.forwardId()) ? target.inverseId()
        : target.forwardId();
    ConversionRate twin = store.rate(twinId);
    if (twin != null) {
      twin.setMultipleRateBy(inverse);
      twin.setDivideRateBy(rate);
      return;
    }
    // A legacy range with one direction only: the edit gives it its missing inverse.
    newRow(row.getClient().getId(), row.getToCurrency(), row.getCurrency(), inverse, rate,
        ConversionRateStore.toLocal(row.getValidFromDate()),
        ConversionRateStore.toLocal(row.getValidToDate()));
  }

  // --- delete --------------------------------------------------------------------------------

  /**
   * Deletes both rows of the range {@code row} belongs to. The range it closed when it was
   * created stays closed (story point B.9).
   *
   * @throws ConversionRateRefusal when the range is locked
   */
  void applyDelete(ConversionRate row) {
    rememberPair(row);
    checkEditable(row);
    Range target = targetOf(rangesOf(row), row);
    for (String id : target.rowIds()) {
      OBDal.getInstance().remove(store.rate(id));
    }
    flush();
  }

  // --- shared --------------------------------------------------------------------------------

  /**
   * A range can be changed or deleted only while it is manual and not used in accounting — that
   * is, while its state is {@code UNUSED}, or {@code CURRENT} without postings.
   */
  void checkEditable(ConversionRate row) {
    if (store.isSynced(row)) {
      throw ConversionRateRefusal.conflict(ConversionRateRefusal.SYNCED_LOCKED);
    }
    if (store.isUsedInAccounting(row.getId())) {
      throw ConversionRateRefusal.conflict(ConversionRateRefusal.USED_LOCKED);
    }
  }

  private void rejectFixedFields(ConversionRate row, JSONObject body) {
    for (String field : FIXED_REFERENCES) {
      if (body.has(field) && !body.isNull(field)) {
        String sent = idOf(body.opt(field));
        StoredCurrency stored = new StoredCurrency(F_CURRENCY.equals(field) ? row.getCurrency()
            : row.getToCurrency());
        if (!stored.matches(sent)) {
          throw ConversionRateRefusal.badRequest(ConversionRateRefusal.FIELD_NOT_EDITABLE);
        }
      }
    }
    if (body.has(F_VALID_TO) && !body.isNull(F_VALID_TO)) {
      LocalDateTime sent = ConversionRateRules.parseValidFrom(body.opt(F_VALID_TO));
      if (!Objects.equals(sent, truncated(row.getValidToDate()))) {
        throw ConversionRateRefusal.badRequest(ConversionRateRefusal.FIELD_NOT_EDITABLE);
      }
    }
  }

  private static LocalDateTime truncated(java.util.Date date) {
    LocalDateTime local = ConversionRateStore.toLocal(date);
    return local == null ? null : local.withSecond(0).withNano(0);
  }

  /** A stored currency compared with what a caller sent: its id or its ISO code. */
  private record StoredCurrency(Currency currency) {
    boolean matches(String sent) {
      return currency != null && (currency.getId().equals(sent)
          || StringUtils.equalsIgnoreCase(currency.getISOCode(), sent));
    }
  }

  private List<Range> rangesOf(ConversionRate row) {
    return store.rangesOfPair(row.getClient().getId(), row.getCurrency().getId(),
        row.getToCurrency().getId());
  }

  private static Range targetOf(List<Range> ranges, ConversionRate row) {
    for (Range range : ranges) {
      if (range.contains(row.getId())) {
        return range;
      }
    }
    LocalDateTime from = ConversionRateStore.toLocal(row.getValidFromDate());
    LocalDateTime to = ConversionRateStore.toLocal(row.getValidToDate());
    return new Range(row.getId(), null, from, to, false);
  }

  private void rememberPair(ConversionRate row) {
    fromIso = row.getCurrency().getISOCode();
    toIso = row.getToCurrency().getISOCode();
  }

  private void writeEnd(Close close) {
    for (String id : close.range().rowIds()) {
      store.rate(id).setValidToDate(ConversionRateStore.toDate(close.newValidTo()));
    }
  }

  private ConversionRate newRow(String clientId, Currency from, Currency to, BigDecimal multiply,
      BigDecimal divide, LocalDateTime validFrom, LocalDateTime validTo) {
    ConversionRate rate = OBProvider.getInstance().get(ConversionRate.class);
    rate.setClient(OBDal.getInstance().getProxy(Client.class, clientId));
    rate.setOrganization(OBDal.getInstance().getProxy(Organization.class,
        ConversionRateStore.SYSTEM_ID));
    rate.setActive(true);
    rate.setCurrency(from);
    rate.setToCurrency(to);
    rate.setConversionRateType(SPOT_RATE_TYPE);
    rate.setMultipleRateBy(multiply);
    rate.setDivideRateBy(divide);
    rate.setValidFromDate(ConversionRateStore.toDate(validFrom));
    // Open range: written as the 9999-12-31 that C_CONVERSION_RATE_TRG would store for an empty
    // end, so core's overlap check (ConversionRateEventHandler) compares a real date at flush.
    rate.setValidToDate(ConversionRateStore.toDate(validTo != null ? validTo
        : ConversionRateRules.OPEN_END));
    OBDal.getInstance().save(rate);
    return rate;
  }

  private Currency resolveTargetCurrency(Object raw, String clientId) {
    String ref = idOf(raw);
    if (ref == null) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.CURRENCY_REQUIRED);
    }
    Currency currency = store.currency(ref);
    if (currency == null && ref.length() == 3) {
      currency = store.currencyByIso(ref);
    }
    if (currency == null || !Boolean.TRUE.equals(currency.isActive())
        || !isVisibleTo(currency, clientId)) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.CURRENCY_INVALID, ref);
    }
    return currency;
  }

  private static boolean isVisibleTo(Currency currency, String clientId) {
    String owner = currency.getClient().getId();
    return ConversionRateStore.SYSTEM_ID.equals(owner) || owner.equals(clientId);
  }

  private static BigDecimal requireRate(Object raw) {
    BigDecimal rate = ConversionRateRules.parseRate(raw);
    if (rate == null) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.INVALID_RATE);
    }
    return rate;
  }

  private static LocalDateTime requireValidFrom(Object raw) {
    LocalDateTime validFrom = ConversionRateRules.parseValidFrom(raw);
    if (validFrom == null) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.INVALID_VALID_FROM);
    }
    return validFrom;
  }

  /** The id of a reference value: a plain string, or the {@code id} of a {@code {id, …}} object. */
  static String idOf(Object raw) {
    if (raw instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) raw).optString(KEY_ID, null));
    }
    if (raw == null || JSONObject.NULL.equals(raw)) {
      return null;
    }
    return StringUtils.trimToNull(String.valueOf(raw));
  }

  private static void flush() {
    OBDal.getInstance().flush();
  }
}
