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
import java.util.Collections;
import java.util.Date;
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
 * ETP-6365: the three read actions of {@code conversion-rates/conversionRate}, served on every
 * channel ({@code POST /sws/neo/conversion-rates/conversionRate/new/action/<name>}, MCP
 * {@code etendo_action}). None of them is tied to a record — the id is ignored — and none writes.
 *
 * <ul>
 *   <li>{@value #MANUAL_CURRENCY_OPTIONS}: the currencies a manual rate can be created for.</li>
 *   <li>{@value #PREVIEW_MANUAL_RATE}: what a create with the same parameters would do — the two
 *       rows it inserts and the rows it closes — or the refusal it would get, without writing.</li>
 *   <li>{@value #CURRENT_RATES}: one entry per currency with a range in force now.</li>
 * </ul>
 */
final class ConversionRateActions {

  static final String MANUAL_CURRENCY_OPTIONS = "manualCurrencyOptions";
  static final String PREVIEW_MANUAL_RATE = "previewManualRate";
  static final String CURRENT_RATES = "currentRates";

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

  private static final String CREATE_HINT = " To create the rate, call etendo_create on"
      + " conversion-rates/conversionRate with the same three fields: the inverse row and the"
      + " closing of the previous range are done by the server in the same transaction.";

  static final Map<String, NeoActionContract> CONTRACTS = buildContracts();

  private ConversionRateActions() {
  }

  private static Map<String, NeoActionContract> buildContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(MANUAL_CURRENCY_OPTIONS, NeoActionContract.read(MANUAL_CURRENCY_OPTIONS,
        "Lists the currencies a MANUAL conversion rate can be created for:"
            + " [{id, isoCode, name, symbol}]. Excludes the organization currency and the"
            + " currencies whose rate is downloaded automatically every day (they have a"
            + " download rule). Use an id or isoCode as toCurrency of etendo_create.")
        .withIdDescription(ID_NOT_USED));
    contracts.put(PREVIEW_MANUAL_RATE, NeoActionContract.read(PREVIEW_MANUAL_RATE,
        "Dry run of a manual rate create; writes nothing. Returns {creates:[{fromIso, toIso,"
            + " rate, validFrom}], closes:[{id, fromIso, toIso, validFrom, newValidTo}]} — the"
            + " two rows (rate and its inverse) the create inserts and the rows of the range in"
            + " force it closes one minute before validFromDate — or {creates:[], closes:[],"
            + " error, errorKey} when the create would be refused (e.g. an overlapping range)."
            + CREATE_HINT,
        Param.required(ConversionRateWrites.F_TO_CURRENCY, NeoActionContract.TYPE_STRING,
            "Destination currency: C_Currency id or ISO code (e.g. CHF)."),
        Param.required(ConversionRateWrites.F_RATE, NeoActionContract.TYPE_NUMBER,
            "Units of the destination currency per 1 unit of the organization currency;"
                + " greater than zero."),
        Param.required(ConversionRateWrites.F_VALID_FROM, NeoActionContract.TYPE_STRING,
            "Start of the range, server-local wall clock yyyy-MM-ddTHH:mm:ss (minutes are"
                + " kept, seconds dropped; a bare yyyy-MM-dd means 00:00)."))
        .withIdDescription(ID_NOT_USED));
    contracts.put(CURRENT_RATES, NeoActionContract.read(CURRENT_RATES,
        "Lists the rates in force now, one per currency: [{currencyId, isoCode, name, symbol,"
            + " origin: AUTO|MANUAL, rate (1 organization currency = rate units), validFrom,"
            + " lastDownloadAt}]. lastDownloadAt (AUTO only) is the latest fully successful run"
            + " of the rate downloader, or the time the row was last written when there is no"
            + " such run.")
        .withIdDescription(ID_NOT_USED));
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
   * @return the 200 response, or the contract's 422
   * @throws JSONException if the body cannot be built
   */
  static NeoResponse run(NeoContext context, ConversionRateStore store) throws JSONException {
    String name = context.getFieldName();
    JSONObject params = parameters(context);
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, name, params);
    if (invalid != null) {
      return invalid;
    }
    JSONArray data;
    if (MANUAL_CURRENCY_OPTIONS.equals(name)) {
      data = manualCurrencyOptions(store);
    } else if (PREVIEW_MANUAL_RATE.equals(name)) {
      return wrap(preview(params, store));
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
        params.put(key, query.get(key));
      }
    }
    return params;
  }

  static JSONArray manualCurrencyOptions(ConversionRateStore store) throws JSONException {
    String clientId = store.currentClientId();
    String orgCurrencyId = store.orgCurrencyId();
    JSONArray options = new JSONArray();
    if (orgCurrencyId == null) {
      return options;
    }
    Set<String> auto = store.autoCurrencyIds(clientId, orgCurrencyId);
    for (CurrencyInfo currency : store.activeCurrencies(clientId, orgCurrencyId)) {
      if (!auto.contains(currency.id())) {
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
      CreatePlan plan = writes.planCreate(writes.parseCreate(params));
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

  private static NeoResponse wrap(Object data) throws JSONException {
    return NeoResponse.ok(new JSONObject().put("response", new JSONObject().put("data", data)));
  }
}
