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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.handlers.ConversionRateRules.Range;
import com.etendoerp.go.schemaforge.handlers.ConversionRateWrites.CreateMode;
import com.etendoerp.go.schemaforge.handlers.ConversionRateWrites.RateChange;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * ETP-6365: the three write actions of {@code conversion-rates/conversionRate} — the only way a
 * rate is created or changed, for the window's popups and for an agent alike (story F.22-25):
 *
 * <ul>
 *   <li>{@value #CREATE_CURRENCY} ("Nueva divisa"): a currency with no rate yet;</li>
 *   <li>{@value #CREATE_RATE} ("Nueva tasa"): a manual currency that already has rates;</li>
 *   <li>{@value #UPDATE_RATE} ("Modificar la tasa"): the rate of an existing manual range, both
 *       directions, only with {@code confirm: true} — without it nothing is saved and the answer
 *       is the proposed change and its warning.</li>
 * </ul>
 *
 * <p>All the rules live in {@link ConversionRateWrites}; {@code previewManualRate} runs the same
 * validation with the same mode, so a popup previews exactly what the action does.</p>
 */
final class ConversionRateWriteActions {

  static final String CREATE_CURRENCY = "createManualCurrency";
  static final String CREATE_RATE = "createManualRate";
  static final String UPDATE_RATE = "updateManualRate";

  /** Boolean of {@value #UPDATE_RATE}: {@code true} saves, absent or {@code false} proposes. */
  static final String P_CONFIRM = "confirm";

  private static final String K_OLD = "old";
  private static final String K_NEW = "new";
  private static final String K_FROM_ISO = "fromIso";
  private static final String K_TO_ISO = "toIso";
  private static final String NOT_A_RECORD = "Not used: the action is not tied to a record."
      + " Pass \"_\".";

  private static final String CREATE_PARAMS_NOTE = " Parameters: toCurrency (C_Currency id or ISO"
      + " code), multipleRateBy (units of toCurrency per 1 unit of the organization currency, > 0),"
      + " optional validFromDate (yyyy-MM-ddTHH:mm[:ss] server-local; default: now; never later"
      + " than now). The server writes the rate AND its inverse and closes the range in force one"
      + " minute before validFromDate, in one transaction. Answers the new rate"
      + " (organization currency -> toCurrency). Run previewManualRate with the same parameters"
      + " to see the effect first.";

  private ConversionRateWriteActions() {
  }

  /** Adds the three contracts, in the order they are presented. */
  static void addContracts(Map<String, NeoActionContract> contracts) {
    contracts.put(CREATE_CURRENCY, NeoActionContract.write(CREATE_CURRENCY,
        "Adds a currency to the manual rates with its first rate (the window's \"Nueva divisa\")."
            + " Only for an active currency that has NO conversion rate yet, is not the"
            + " organization currency and is not downloaded automatically —"
            + " manualCurrencyOptions(withoutRates:true) lists them. For a currency that already"
            + " has rates use createManualRate." + CREATE_PARAMS_NOTE,
        createParams())
        .withIdDescription(NOT_A_RECORD));
    contracts.put(CREATE_RATE, NeoActionContract.write(CREATE_RATE,
        "Enters a new rate for a MANUAL currency that already has rates (the window's \"Nueva"
            + " tasa\"); the rate in force is closed one minute before the new one starts. For a"
            + " currency without rates use createManualCurrency; downloaded currencies are"
            + " refused." + CREATE_PARAMS_NOTE,
        createParams())
        .withIdDescription(NOT_A_RECORD));
    contracts.put(UPDATE_RATE, NeoActionContract.write(UPDATE_RATE,
        "Changes ONLY the rate of an existing manual rate, in both directions (its period never"
            + " changes: to change it, etendo_delete the rate and create a new one). Two steps:"
            + " call it without confirm to get {saved:false, proposal:{forward:{fromIso, toIso,"
            + " old, new}, inverse:{…}, validFrom, validTo}, warning} and nothing is saved; show"
            + " the warning to the user, then call it again with confirm:true to save — it answers"
            + " the updated rate (organization currency -> other currency). Downloaded rates are"
            + " refused.",
        Param.required(ConversionRateWrites.F_RATE, NeoActionContract.TYPE_NUMBER,
            "The new rate: units of the other currency per 1 unit of the organization"
                + " currency, > 0 (whichever direction the id is)."),
        Param.optional(P_CONFIRM, NeoActionContract.TYPE_BOOLEAN,
            "true to save. Absent or false: nothing is saved and the proposal is returned."))
        .withIdDescription("The id of the conversion rate to change (either of its two"
            + " directions)."));
  }

  private static Param[] createParams() {
    return new Param[] {
        Param.required(ConversionRateWrites.F_TO_CURRENCY, NeoActionContract.TYPE_STRING,
            "Destination currency: C_Currency id or ISO code (e.g. CHF)."),
        Param.required(ConversionRateWrites.F_RATE, NeoActionContract.TYPE_NUMBER,
            "Units of the destination currency per 1 unit of the organization currency;"
                + " greater than zero."),
        Param.optional(ConversionRateWrites.F_VALID_FROM, NeoActionContract.TYPE_STRING,
            "Start of the rate, server-local yyyy-MM-ddTHH:mm[:ss]; default: the current"
                + " minute (serverClock). Never later than now.") };
  }

  /** Whether {@code name} is one of these actions. */
  static boolean serves(String name) {
    return CREATE_CURRENCY.equals(name) || CREATE_RATE.equals(name) || UPDATE_RATE.equals(name);
  }

  /**
   * Runs the write action {@code name}; the parameters were already judged against its contract.
   *
   * @param name     the action
   * @param recordId the record id of the call (used by {@value #UPDATE_RATE})
   * @param params   the declared parameters
   * @param store    the reads
   * @param writes   the per-request writer
   * @return the answer
   * @throws JSONException if the answer cannot be built
   */
  static NeoResponse run(String name, String recordId, JSONObject params,
      ConversionRateStore store, ConversionRateWrites writes) throws JSONException {
    if (UPDATE_RATE.equals(name)) {
      return updateRate(recordId, params, store, writes);
    }
    CreateMode mode = CREATE_CURRENCY.equals(name) ? CreateMode.NEW_CURRENCY
        : CreateMode.NEW_RATE;
    ConversionRate forward = writes.applyCreate(writes.planCreate(writes.parseCreate(params,
        mode)));
    return ConversionRateActions.wrap(ConversionRateResponses.recordJson(forward, store));
  }

  private static NeoResponse updateRate(String recordId, JSONObject params,
      ConversionRateStore store, ConversionRateWrites writes) throws JSONException {
    ConversionRate row = writes.writableRate(recordId);
    if (row == null) {
      return ConversionRateResponses.notFound(recordId);
    }
    RateChange change = writes.planRateChange(row, params.opt(ConversionRateWrites.F_RATE));
    if (!params.optBoolean(P_CONFIRM, false)) {
      return ConversionRateActions.wrap(proposal(change));
    }
    ConversionRate forward = writes.applyRateChange(change);
    return ConversionRateActions.wrap(ConversionRateResponses.recordJson(forward, store));
  }

  /**
   * {@code {saved:false, proposal:{forward:{fromIso, toIso, old, new}, inverse:{…}, validFrom,
   * validTo}, warning}}; {@code validTo} is {@code null} for an open range.
   */
  static JSONObject proposal(RateChange change) throws JSONException {
    Currency from = change.from();
    Currency to = change.to();
    Range range = change.range();
    JSONObject proposal = new JSONObject();
    proposal.put("forward", direction(from, to, change.oldForward(), change.newForward()));
    proposal.put("inverse", direction(to, from, change.oldInverse(), change.newInverse()));
    proposal.put("validFrom", NeoDateFormat.toWireDateTime(
        ConversionRateStore.toDate(range.validFrom())));
    proposal.put("validTo", ConversionRateResponses.wireEnd(
        ConversionRateStore.toDate(range.validTo())));
    JSONObject answer = new JSONObject();
    answer.put("saved", false);
    answer.put("proposal", proposal);
    answer.put("warning", NeoMessageText.text(ConversionRateRefusal.UPDATE_WARNING));
    return answer;
  }

  private static JSONObject direction(Currency from, Currency to, BigDecimal oldRate,
      BigDecimal newRate) throws JSONException {
    JSONObject json = new JSONObject();
    json.put(K_FROM_ISO, from.getISOCode());
    json.put(K_TO_ISO, to.getISOCode());
    json.put(K_OLD, oldRate);
    json.put(K_NEW, newRate);
    return json;
  }

  /** The preview modes, as {@code previewManualRate} takes them. */
  static final List<String> PREVIEW_MODES = List.of("newCurrency", "newRate");

  /** The create mode a preview asks for: absent → no check on the existing rates. */
  static CreateMode previewMode(String mode) {
    if (PREVIEW_MODES.get(0).equals(mode)) {
      return CreateMode.NEW_CURRENCY;
    }
    return PREVIEW_MODES.get(1).equals(mode) ? CreateMode.NEW_RATE : CreateMode.ANY;
  }
}
