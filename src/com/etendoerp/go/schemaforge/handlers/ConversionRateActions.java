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
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.handlers.ConversionRateRules.Close;
import com.etendoerp.go.schemaforge.handlers.ConversionRateStore.CurrencyInfo;
import com.etendoerp.go.schemaforge.handlers.ConversionRateStore.CurrentRate;
import com.etendoerp.go.schemaforge.handlers.ConversionRateWrites.CreatePlan;
import com.etendoerp.go.schemaforge.handlers.ConversionRateWrites.CreateRequest;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * ETP-6365: the actions of {@code conversion-rates/conversionRate}, served on every channel
 * ({@code POST /sws/neo/conversion-rates/conversionRate/<id|new>/action/<name>}, MCP
 * {@code etendo_action}). The read ones below are not tied to a record (the id is ignored) and
 * write nothing; the write ones are in {@link ConversionRateWriteActions}.
 *
 * <ul>
 *   <li>{@value #MANUAL_CURRENCY_OPTIONS}: the currencies a manual rate can be created for;
 *       with {@value #P_WITHOUT_RATES} only those with no rate yet.</li>
 *   <li>{@value #PREVIEW_MANUAL_RATE}: what a create with the same parameters would do — the two
 *       rows it inserts and the rows it closes — or the refusal it would get, without writing.</li>
 *   <li>{@value #CURRENT_RATES}: one entry per currency with a range in force now.</li>
 *   <li>{@value #SERVER_CLOCK}: the server's current minute, the default start of a new rate.</li>
 * </ul>
 */
final class ConversionRateActions {

  static final String MANUAL_CURRENCY_OPTIONS = "manualCurrencyOptions";
  static final String PREVIEW_MANUAL_RATE = "previewManualRate";
  static final String CURRENT_RATES = "currentRates";
  static final String SERVER_CLOCK = "serverClock";

  /** Optional boolean of {@value #MANUAL_CURRENCY_OPTIONS}. */
  static final String P_WITHOUT_RATES = "withoutRates";

  static final String ORIGIN_AUTO = "AUTO";
  static final String ORIGIN_MANUAL = "MANUAL";

  private static final String ID_NOT_USED = "Not used: the action is not tied to a record."
      + " Pass \"_\".";
  private static final String K_ID = "id";
  private static final String K_ISO = "isoCode";
  private static final String K_FROM_ISO = "fromIso";
  private static final String K_TO_ISO = "toIso";
  private static final String K_RATE = "rate";
  private static final String K_VALID_FROM = "validFrom";

  /** Optional option of {@value #PREVIEW_MANUAL_RATE}: which create to preview. */
  static final String P_MODE = "mode";

  private static final String CREATE_HINT = " To create it, call createManualCurrency (mode"
      + " newCurrency) or createManualRate (mode newRate) with the same parameters. An existing"
      + " rate only changes its rate (updateManualRate); to change its period, delete it with"
      + " etendo_delete and create a new one.";

  /** One request of the customization, run with the per-request writer. */
  @FunctionalInterface
  interface Work {
    /**
     * Runs the request.
     *
     * @param writes the per-request writer
     * @return the answer
     * @throws JSONException if the answer cannot be built
     */
    NeoResponse run(ConversionRateWrites writes) throws JSONException;
  }

  static final Map<String, NeoActionContract> CONTRACTS = buildContracts();

  private ConversionRateActions() {
  }

  private static Map<String, NeoActionContract> buildContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(MANUAL_CURRENCY_OPTIONS, NeoActionContract.read(MANUAL_CURRENCY_OPTIONS,
        "Lists the currencies a MANUAL conversion rate can be created for:"
            + " [{id, isoCode, name, symbol}]. Excludes the organization currency and the"
            + " currencies whose rate is downloaded automatically every day (they have a"
            + " download rule). With withoutRates:true it also excludes the currencies that"
            + " already have any conversion rate with the organization currency (the \"new"
            + " currency\" list of createManualCurrency); to add a rate to one of those, use"
            + " createManualRate."
            + " Use an id or isoCode as toCurrency of createManualCurrency / createManualRate.",
        Param.optional(P_WITHOUT_RATES, NeoActionContract.TYPE_BOOLEAN,
            "true to list only currencies with no conversion rate yet against the organization"
                + " currency, in either direction. Default false."))
        .withIdDescription(ID_NOT_USED));
    contracts.put(PREVIEW_MANUAL_RATE, NeoActionContract.read(PREVIEW_MANUAL_RATE,
        "Dry run of a manual rate create; writes nothing. validFromDate cannot be later than"
            + " the server's current minute (see serverClock). Returns {creates:[{fromIso, toIso,"
            + " rate, validFrom}], closes:[{id, fromIso, toIso, validFrom, newValidTo}]} — the"
            + " two rows (rate and its inverse) the create inserts and the rows of the range in"
            + " force it closes one minute before validFromDate — or {creates:[], closes:[],"
            + " error, errorKey, errorField} when the create would be refused (e.g. an"
            + " overlapping range: error names the colliding range and the start to choose"
            + " instead; errorField is the parameter at fault)."
            + CREATE_HINT,
        Param.required(ConversionRateWrites.F_TO_CURRENCY, NeoActionContract.TYPE_STRING,
            "Destination currency: C_Currency id or ISO code (e.g. CHF)."),
        Param.required(ConversionRateWrites.F_RATE, NeoActionContract.TYPE_NUMBER,
            "Units of the destination currency per 1 unit of the organization currency;"
                + " greater than zero."),
        Param.optional(ConversionRateWrites.F_VALID_FROM, NeoActionContract.TYPE_STRING,
            "Start of the range, server-local wall clock yyyy-MM-ddTHH:mm:ss (minutes are"
                + " kept, seconds dropped; a bare yyyy-MM-dd means 00:00); default: now."),
        Param.options(P_MODE, "newCurrency: preview createManualCurrency (refused when the"
            + " currency already has rates); newRate: preview createManualRate (refused when it"
            + " has none). Absent: no check on the existing rates.",
            ConversionRateWriteActions.PREVIEW_MODES))
        .withIdDescription(ID_NOT_USED));
    contracts.put(CURRENT_RATES, NeoActionContract.read(CURRENT_RATES,
        "Lists the rates in force now, one per currency: [{currencyId, isoCode, name, symbol,"
            + " origin: AUTO|MANUAL, rate (1 organization currency = rate units), validFrom,"
            + " lastDownloadAt}]. lastDownloadAt (AUTO only) is the latest fully successful run"
            + " of the rate downloader, or the time the row was last written when there is no"
            + " such run.")
        .withIdDescription(ID_NOT_USED));
    contracts.put(SERVER_CLOCK, NeoActionContract.read(SERVER_CLOCK,
        "Returns {now: yyyy-MM-ddTHH:mm:00}, the server's current minute (server-local wall clock,"
            + " the time zone validFromDate is read in). A rate cannot start later than this"
            + " minute: use it as the default validFromDate of a new rate.")
        .withIdDescription(ID_NOT_USED));
    ConversionRateWriteActions.addContracts(contracts);
    return Collections.unmodifiableMap(contracts);
  }

  /** Whether {@code name} is one of these actions. */
  static boolean serves(String name) {
    return name != null && CONTRACTS.containsKey(name);
  }

  /**
   * Runs the action the context names, after judging its parameters against the contract.
   *
   * @param context the ACTION context
   * @param store   the reads
   * @param writes  the per-request writer
   * @return the 200 response, or the contract's 422
   * @throws JSONException if the body cannot be built
   */
  static NeoResponse run(NeoContext context, ConversionRateStore store,
      ConversionRateWrites writes) throws JSONException {
    String name = context.getFieldName();
    JSONObject params = parameters(context);
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, name, params);
    if (invalid != null) {
      return invalid;
    }
    if (ConversionRateWriteActions.serves(name)) {
      return ConversionRateWriteActions.run(name, context.getRecordId(), params, store, writes);
    }
    JSONArray data;
    if (MANUAL_CURRENCY_OPTIONS.equals(name)) {
      data = manualCurrencyOptions(store, params.optBoolean(P_WITHOUT_RATES, false));
    } else if (PREVIEW_MANUAL_RATE.equals(name)) {
      return wrap(preview(params, store));
    } else if (SERVER_CLOCK.equals(name)) {
      return wrap(serverClock(store));
    } else {
      data = currentRates(store);
    }
    return wrap(data);
  }

  /**
   * The action's parameters: the body (REST {@code POST}, MCP), else the query string, keeping
   * only the declared keys so a transport field never reads as an unknown parameter.
   */
  static JSONObject parameters(NeoContext context) throws JSONException {
    NeoActionContract contract = CONTRACTS.get(context.getFieldName());
    JSONObject params = new JSONObject();
    if (contract == null) {
      return params;
    }
    JSONObject body = context.getRequestBody();
    Map<String, String> query = context.getQueryParams();
    for (Param param : contract.getParams()) {
      String key = param.getName();
      if (body != null && body.has(key)) {
        params.put(key, body.get(key));
      } else if (query != null && query.get(key) != null) {
        params.put(key, fromQuery(param, query.get(key)));
      }
    }
    return params;
  }

  /** A query-string value, typed: {@code "true"}/{@code "false"} become booleans. */
  private static Object fromQuery(Param param, String raw) {
    boolean isBoolean = NeoActionContract.TYPE_BOOLEAN.equals(param.getType());
    if (isBoolean && ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw))) {
      return Boolean.valueOf(raw);
    }
    return raw;
  }

  /**
   * The currencies a manual rate can be created for.
   *
   * @param store        the reads
   * @param withoutRates also leave out the currencies that already have a rate with the
   *                     organization currency
   * @return {@code [{id, isoCode, name, symbol}]}
   * @throws JSONException if the body cannot be built
   */
  static JSONArray manualCurrencyOptions(ConversionRateStore store, boolean withoutRates)
      throws JSONException {
    String clientId = store.currentClientId();
    String orgCurrencyId = store.orgCurrencyId();
    JSONArray options = new JSONArray();
    if (orgCurrencyId == null) {
      return options;
    }
    Set<String> excluded = new HashSet<>(store.autoCurrencyIds(clientId, orgCurrencyId));
    if (withoutRates) {
      excluded.addAll(store.currenciesWithRates(clientId, orgCurrencyId));
    }
    for (CurrencyInfo currency : store.activeCurrencies(clientId, orgCurrencyId)) {
      if (!excluded.contains(currency.id())) {
        options.put(currencyJson(currency, K_ID));
      }
    }
    return options;
  }

  static JSONObject preview(JSONObject params, ConversionRateStore store) throws JSONException {
    JSONObject result = new JSONObject();
    JSONArray creates = new JSONArray();
    JSONArray closes = new JSONArray();
    result.put("creates", creates);
    result.put("closes", closes);
    ConversionRateWrites writes = new ConversionRateWrites(store);
    try {
      CreatePlan plan = writes.planCreate(writes.parseCreate(params,
          ConversionRateWriteActions.previewMode(params.optString(P_MODE, null))));
      CreateRequest request = plan.request();
      String orgIso = request.orgCurrency().getISOCode();
      String toIso = request.toCurrency().getISOCode();
      Date from = ConversionRateStore.toDate(request.validFrom());
      creates.put(createJson(orgIso, toIso, request.rate(), from));
      creates.put(createJson(toIso, orgIso, ConversionRateRules.inverse(request.rate()), from));
      for (Close close : plan.plan().closes()) {
        putCloses(closes, close, orgIso, toIso);
      }
    } catch (ConversionRateRefusal refusal) {
      result.put("error", refusal.text());
      result.put("errorKey", refusal.getKey());
      if (refusal.getField() != null) {
        result.put("errorField", refusal.getField());
      }
    }
    return result;
  }

  private static void putCloses(JSONArray closes, Close close, String orgIso, String toIso)
      throws JSONException {
    Date from = ConversionRateStore.toDate(close.range().validFrom());
    Date newTo = ConversionRateStore.toDate(close.newValidTo());
    if (close.range().forwardId() != null) {
      closes.put(closeJson(close.range().forwardId(), orgIso, toIso, from, newTo));
    }
    if (close.range().inverseId() != null) {
      closes.put(closeJson(close.range().inverseId(), toIso, orgIso, from, newTo));
    }
  }

  static JSONArray currentRates(ConversionRateStore store) throws JSONException {
    String clientId = store.currentClientId();
    String orgCurrencyId = store.orgCurrencyId();
    JSONArray rates = new JSONArray();
    if (orgCurrencyId == null) {
      return rates;
    }
    Set<String> auto = store.autoCurrencyIds(clientId, orgCurrencyId);
    Date lastRun = auto.isEmpty() ? null : store.latestSuccessfulDownload(clientId);
    for (CurrentRate current : store.currentRates(clientId, orgCurrencyId)) {
      boolean isAuto = auto.contains(current.currency().id());
      JSONObject entry = currencyJson(current.currency(), "currencyId");
      entry.put("origin", isAuto ? ORIGIN_AUTO : ORIGIN_MANUAL);
      entry.put(K_RATE, current.rate());
      entry.put(K_VALID_FROM, NeoDateFormat.toWireDateTime(current.validFrom()));
      Date downloaded = isAuto ? lastDownload(lastRun, current.rowUpdated()) : null;
      entry.put("lastDownloadAt", downloaded == null ? JSONObject.NULL
          : NeoDateFormat.toWireDateTime(downloaded));
      rates.put(entry);
    }
    return rates;
  }

  /** {@code {now}}: the server's current minute, seconds zeroed, server-local wall clock. */
  static JSONObject serverClock(ConversionRateStore store) throws JSONException {
    LocalDateTime minute = store.now().truncatedTo(ChronoUnit.MINUTES);
    return new JSONObject().put("now", NeoDateFormat.toWireDateTime(
        ConversionRateStore.toDate(minute)));
  }

  /** The downloader's last successful run, unless the row itself is newer (or there is no run). */
  static Date lastDownload(Date lastRun, Date rowUpdated) {
    if (lastRun == null) {
      return rowUpdated;
    }
    return rowUpdated != null && rowUpdated.after(lastRun) ? rowUpdated : lastRun;
  }

  private static JSONObject currencyJson(CurrencyInfo currency, String idKey)
      throws JSONException {
    JSONObject json = new JSONObject();
    json.put(idKey, currency.id());
    json.put(K_ISO, currency.isoCode());
    json.put("name", currency.name());
    json.put("symbol", currency.symbol() == null ? JSONObject.NULL : currency.symbol());
    return json;
  }

  private static JSONObject createJson(String fromIso, String toIso, BigDecimal rate, Date from)
      throws JSONException {
    JSONObject json = new JSONObject();
    json.put(K_FROM_ISO, fromIso);
    json.put(K_TO_ISO, toIso);
    json.put(K_RATE, rate);
    json.put(K_VALID_FROM, NeoDateFormat.toWireDateTime(from));
    return json;
  }

  private static JSONObject closeJson(String id, String fromIso, String toIso, Date from,
      Date newTo) throws JSONException {
    JSONObject json = new JSONObject();
    json.put(K_ID, id);
    json.put(K_FROM_ISO, fromIso);
    json.put(K_TO_ISO, toIso);
    json.put(K_VALID_FROM, NeoDateFormat.toWireDateTime(from));
    json.put("newValidTo", NeoDateFormat.toWireDateTime(newTo));
    return json;
  }

  static NeoResponse wrap(Object data) throws JSONException {
    return NeoResponse.ok(new JSONObject().put("response", new JSONObject().put("data", data)));
  }
}
