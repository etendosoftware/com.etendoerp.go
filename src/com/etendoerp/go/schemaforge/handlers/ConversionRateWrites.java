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
import java.time.temporal.ChronoUnit;
import java.util.List;

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
 * change its rate, delete it — always on both directions of the pair. A range never changes its
 * period: to change it, the range is deleted and created again.
 *
 * <p><b>Validate everything, then write.</b> Every refusal ({@link ConversionRateRefusal}) is
 * thrown before the first row is touched. The writes themselves run on the request's session and
 * are only flushed, never committed: the channel owns the transaction (REST single and MCP commit
 * at the end of the request, the batch at the end of the batch), and the caller rolls back on any
 * failure, so a range never ends up without its inverse.</p>
 *
 * <p><b>Triggers off, their checks kept.</b> Every write runs through
 * {@link ConversionRateTriggerBypass}, so core's {@code C_CONVERSION_RATE_TRG} and
 * {@code C_CONVERSION_RATE_TRG3} do not run for them: any manual range can be changed or deleted,
 * postings or not (only downloaded rows stay locked). What those triggers enforce and is still
 * wanted is checked here instead ({@link #checkRow}): an end is never empty (an open range is
 * written as 2999-12-31, the downloader's value), the end is never before the start (the
 * trigger's {@code @20505@}) and the two currencies differ (TRG3's {@code @SameCurrency@}). Left
 * out on purpose: the trigger's {@code @20506@} "rate used in accounting" refusal, which is what
 * the bypass is for. Core's Java {@code ConversionRateEventHandler} (overlap, 20504) is not a
 * trigger and still runs at flush.</p>
 *
 * <p><b>Order of the writes.</b> Core's {@code ConversionRateEventHandler} refuses (20504) a row
 * whose dates overlap another row of the pair at flush time, and Hibernate flushes inserts before
 * updates. A create therefore closes the open range and flushes before it inserts the new one.</p>
 *
 * <p>One instance per request: it remembers the pair and the create it works on, so a failure
 * raised by the database can be worded with them ({@link #overlapAfterFailure()}).</p>
 */
class ConversionRateWrites {

  static final String F_TO_CURRENCY = "toCurrency";
  static final String F_RATE = "multipleRateBy";
  static final String F_VALID_FROM = "validFromDate";
  static final String F_VALID_TO = "validToDate";
  static final String SPOT_RATE_TYPE = "S";
  private static final String KEY_ID = "id";

  private final ConversionRateStore store;
  private final ConversionRateTriggerBypass bypass;
  private String fromIso;
  private String toIso;
  private CreateRequest lastCreate;

  /** A validated create: everything the two rows need. */
  record CreateRequest(String clientId, Currency orgCurrency, Currency toCurrency, BigDecimal rate,
      LocalDateTime validFrom) {
  }

  /** A validated create and what it does to the other ranges of the pair. */
  record CreatePlan(CreateRequest request, Plan plan) {
  }

  ConversionRateWrites(ConversionRateStore store) {
    this(store, new ConversionRateTriggerBypass());
  }

  ConversionRateWrites(ConversionRateStore store, ConversionRateTriggerBypass bypass) {
    this.store = store;
    this.bypass = bypass;
  }

  // --- create --------------------------------------------------------------------------------

  /**
   * Which create a caller asked for: the window's "Nueva divisa" (a currency with no rate yet),
   * its "Nueva tasa" (a manual currency that already has rates), or either.
   */
  enum CreateMode {
    /** No check on the existing rates of the currency. */
    ANY,
    /** "Nueva divisa": the currency must have no rate yet. */
    NEW_CURRENCY,
    /** "Nueva tasa": the currency must already have rates. */
    NEW_RATE
  }

  /**
   * Validates a create ({@code toCurrency}, {@code multipleRateBy}, optional
   * {@code validFromDate}, which defaults to the server's current minute) against the request's
   * organization currency and the {@code mode} asked for.
   *
   * @throws ConversionRateRefusal for a missing or wrong value
   */
  CreateRequest parseCreate(JSONObject body, CreateMode mode) {
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
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.ORG_CURRENCY, toIso)
          .on(F_TO_CURRENCY);
    }
    if (store.autoCurrencyIds(clientId, orgCurrencyId).contains(to.getId())) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.AUTO_CURRENCY, toIso)
          .on(F_TO_CURRENCY);
    }
    checkMode(mode, store.currenciesWithRates(clientId, orgCurrencyId).contains(to.getId()));
    BigDecimal rate = requireRate(body.opt(F_RATE));
    LocalDateTime validFrom = requirePastValidFrom(body.opt(F_VALID_FROM));
    return new CreateRequest(clientId, orgCurrency, to, rate, validFrom);
  }

  private void checkMode(CreateMode mode, boolean hasRates) {
    if (mode == CreateMode.NEW_CURRENCY && hasRates) {
      throw ConversionRateRefusal.conflict(ConversionRateRefusal.CURRENCY_HAS_RATES, toIso)
          .on(F_TO_CURRENCY);
    }
    if (mode == CreateMode.NEW_RATE && !hasRates) {
      throw ConversionRateRefusal.conflict(ConversionRateRefusal.CURRENCY_NO_RATES, toIso)
          .on(F_TO_CURRENCY);
    }
  }

  /**
   * Plans a validated create against the stored ranges of the pair.
   *
   * @throws ConversionRateRefusal {@code OVERLAP} when the new range collides with another
   */
  CreatePlan planCreate(CreateRequest request) {
    lastCreate = request;
    List<Range> ranges = rangesOf(request);
    Plan plan = ConversionRateRules.planCreate(ranges, request.validFrom());
    if (plan.overlap()) {
      throw overlapRefusal(ranges, plan.conflict(), request.validFrom());
    }
    return new CreatePlan(request, plan);
  }

  /**
   * The overlap refusal for a create the database refused (core's 20504) after this instance
   * planned it: the ranges are read again — the caller has rolled the session back — so the
   * message names the range that now collides. Without a planned create, or when no range
   * collides any more (a concurrent change), the message has no dates.
   */
  ConversionRateRefusal overlapAfterFailure() {
    if (lastCreate == null) {
      return ConversionRateRefusal.overlapOf(fromIso, toIso, null, null);
    }
    List<Range> ranges = rangesOf(lastCreate);
    Plan plan = ConversionRateRules.planCreate(ranges, lastCreate.validFrom());
    return overlapRefusal(ranges, plan.conflict(), lastCreate.validFrom());
  }

  private ConversionRateRefusal overlapRefusal(List<Range> ranges, Range conflict,
      LocalDateTime from) {
    return ConversionRateRefusal.overlapOf(fromIso, toIso, conflict,
        ConversionRateRules.suggestedStart(ranges, from));
  }

  private List<Range> rangesOf(CreateRequest request) {
    return store.rangesOfPair(request.clientId(), request.orgCurrency().getId(),
        request.toCurrency().getId());
  }

  /**
   * Closes the open ranges the plan names, then inserts the forward and the inverse row.
   *
   * @return the forward row (organization currency to the other one)
   */
  ConversionRate applyCreate(CreatePlan createPlan) {
    return bypass.run(() -> writeCreate(createPlan));
  }

  private ConversionRate writeCreate(CreatePlan createPlan) {
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

  // --- rate change ---------------------------------------------------------------------------

  /**
   * A validated change of the rate of one range, seen from the organization currency.
   *
   * @param range      the range, oriented organization currency → other currency
   * @param from       the organization currency (or the row's own origin for a pair without it)
   * @param to         the other currency
   * @param oldForward the stored rate from → to
   * @param oldInverse the stored rate to → from
   * @param newForward the new rate from → to
   * @param newInverse its inverse
   */
  record RateChange(Range range, Currency from, Currency to, BigDecimal oldForward,
      BigDecimal oldInverse, BigDecimal newForward, BigDecimal newInverse) {
  }

  /**
   * Validates a change of the rate of the range {@code row} belongs to — either of its two rows.
   * {@code rawRate} is always the forward rate: units of the other currency per 1 unit of the
   * organization currency. Nothing else of a range can change: its period is changed by deleting
   * it and creating a new one, as in the window.
   *
   * @throws ConversionRateRefusal when the range is downloaded or the rate is not a positive
   *     number
   */
  RateChange planRateChange(ConversionRate row, Object rawRate) {
    checkEditable(row);
    BigDecimal rate = requireRate(rawRate);
    boolean rowIsForward = isForward(row);
    Currency from = rowIsForward ? row.getCurrency() : row.getToCurrency();
    Currency to = rowIsForward ? row.getToCurrency() : row.getCurrency();
    fromIso = from.getISOCode();
    toIso = to.getISOCode();
    Range range = targetOf(store.rangesOfPair(row.getClient().getId(), from.getId(), to.getId()),
        row, rowIsForward);
    ConversionRate forward = store.rate(range.forwardId());
    ConversionRate inverse = store.rate(range.inverseId());
    BigDecimal oldForward = forward != null ? forward.getMultipleRateBy()
        : ConversionRateRules.inverse(inverse.getMultipleRateBy());
    BigDecimal oldInverse = inverse != null ? inverse.getMultipleRateBy()
        : ConversionRateRules.inverse(forward.getMultipleRateBy());
    return new RateChange(range, from, to, oldForward, oldInverse, rate,
        ConversionRateRules.inverse(rate));
  }

  /**
   * Writes a planned rate change on both rows; a legacy range with one direction only gets its
   * missing row.
   *
   * @return the forward row
   */
  ConversionRate applyRateChange(RateChange change) {
    return bypass.run(() -> {
      Range range = change.range();
      ConversionRate forward = writeRate(range.forwardId(), change.newForward(),
          change.newInverse());
      ConversionRate inverse = writeRate(range.inverseId(), change.newInverse(),
          change.newForward());
      String clientId = (forward != null ? forward : inverse).getClient().getId();
      if (forward == null) {
        forward = newRow(clientId, change.from(), change.to(), change.newForward(),
            change.newInverse(), range.validFrom(), range.validTo());
      }
      if (inverse == null) {
        newRow(clientId, change.to(), change.from(), change.newInverse(), change.newForward(),
            range.validFrom(), range.validTo());
      }
      return forward;
    });
  }

  private ConversionRate writeRate(String rowId, BigDecimal multiply, BigDecimal divide) {
    ConversionRate row = store.rate(rowId);
    if (row != null) {
      row.setMultipleRateBy(multiply);
      row.setDivideRateBy(divide);
    }
    return row;
  }

  /**
   * Whether {@code row} goes from the organization currency; a row of a pair that does not
   * involve it is taken in its own orientation.
   */
  private boolean isForward(ConversionRate row) {
    String orgCurrencyId = store.orgCurrencyId();
    return orgCurrencyId == null || !orgCurrencyId.equals(row.getToCurrency().getId());
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
    Range target = targetOf(rangesOf(row), row, true);
    bypass.run(() -> {
      for (String id : target.rowIds()) {
        OBDal.getInstance().remove(store.rate(id));
      }
      return null;
    });
  }

  // --- shared --------------------------------------------------------------------------------

  /**
   * The row {@code id} names when the current client may change it. A row the client can read is
   * judged first, so a downloaded one — the client's or a System ({@code 0}) one, which is where
   * the downloader writes — gets the synced-lock refusal, not a 404. A System row is never
   * writable by a tenant, downloaded or not: it answers {@code null}, as an unknown id does.
   *
   * @param id the id of the request
   * @return the row, or {@code null} when it is unknown, of another tenant or of System
   * @throws ConversionRateRefusal {@code SYNCED_LOCKED} when the row is downloaded
   */
  ConversionRate writableRate(String id) {
    ConversionRate row = store.readableRate(id);
    if (row == null) {
      return null;
    }
    checkEditable(row);
    return store.isOwn(row) ? row : null;
  }

  /**
   * Every manual range can be changed or deleted, used in accounting or not: a posted document
   * keeps the rate it was posted with. Only a downloaded row is locked.
   */
  void checkEditable(ConversionRate row) {
    if (store.isSynced(row)) {
      throw ConversionRateRefusal.conflict(ConversionRateRefusal.SYNCED_LOCKED);
    }
  }

  /**
   * What core's triggers enforced on a row and still holds with them bypassed: an end is set (an
   * open range is 2999-12-31), the end is not before the start ({@code @20505@}) and the two
   * currencies differ ({@code @SameCurrency@}). A violation is a planning bug, never a user
   * input — the input is refused before anything is written — so it fails the write (rolled back).
   *
   * @throws IllegalStateException when the row breaks one of them
   */
  static void checkRow(ConversionRate row) {
    String broken = null;
    if (row.getValidToDate() == null || row.getValidFromDate() == null) {
      broken = "has no start or no end";
    } else if (row.getValidToDate().before(row.getValidFromDate())) {
      broken = "ends before it starts";
    } else if (row.getCurrency().getId().equals(row.getToCurrency().getId())) {
      broken = "converts a currency to itself";
    }
    if (broken != null) {
      throw new IllegalStateException("Conversion rate " + row.getId() + " " + broken);
    }
  }

  private List<Range> rangesOf(ConversionRate row) {
    return store.rangesOfPair(row.getClient().getId(), row.getCurrency().getId(),
        row.getToCurrency().getId());
  }

  private static Range targetOf(List<Range> ranges, ConversionRate row, boolean rowIsForward) {
    for (Range range : ranges) {
      if (range.contains(row.getId())) {
        return range;
      }
    }
    LocalDateTime from = ConversionRateStore.toLocal(row.getValidFromDate());
    LocalDateTime to = ConversionRateStore.toLocal(row.getValidToDate());
    return rowIsForward ? new Range(row.getId(), null, from, to, false)
        : new Range(null, row.getId(), from, to, false);
  }

  private void rememberPair(ConversionRate row) {
    fromIso = row.getCurrency().getISOCode();
    toIso = row.getToCurrency().getISOCode();
  }

  private void writeEnd(Close close) {
    for (String id : close.range().rowIds()) {
      ConversionRate closed = store.rate(id);
      closed.setValidToDate(ConversionRateStore.toDate(close.newValidTo()));
      checkRow(closed);
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
    // Open range: written as 2999-12-31, the end the rate downloader writes, so every open range
    // stores the same value (a read treats any end in 2999 or later as open). A real date also
    // lets core's overlap check (ConversionRateEventHandler) compare it at flush.
    rate.setValidToDate(ConversionRateStore.toDate(validTo != null ? validTo
        : ConversionRateRules.OPEN_END));
    checkRow(rate);
    OBDal.getInstance().save(rate);
    return rate;
  }

  private Currency resolveTargetCurrency(Object raw, String clientId) {
    String ref = idOf(raw);
    if (ref == null) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.CURRENCY_REQUIRED)
          .on(F_TO_CURRENCY);
    }
    Currency currency = store.currency(ref);
    if (currency == null && ref.length() == 3) {
      currency = store.currencyByIso(ref);
    }
    if (currency == null || !Boolean.TRUE.equals(currency.isActive())
        || !isVisibleTo(currency, clientId)) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.CURRENCY_INVALID, ref)
          .on(F_TO_CURRENCY);
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
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.INVALID_RATE).on(F_RATE);
    }
    return rate;
  }

  /**
   * A valid start that is not later than the server's current minute: a range never starts in
   * the future, so a rate is in force the moment it is saved.
   */
  private LocalDateTime requirePastValidFrom(Object raw) {
    if (raw == null || JSONObject.NULL.equals(raw) || StringUtils.isBlank(String.valueOf(raw))) {
      // Not sent: the rate starts now, the default of the window's popups.
      return store.now().truncatedTo(ChronoUnit.MINUTES);
    }
    LocalDateTime validFrom = ConversionRateRules.parseValidFrom(raw);
    if (validFrom == null) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.INVALID_VALID_FROM)
          .on(F_VALID_FROM);
    }
    if (ConversionRateRules.isFuture(validFrom, store.now())) {
      throw ConversionRateRefusal.badRequest(ConversionRateRefusal.FUTURE_START)
          .on(F_VALID_FROM);
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
