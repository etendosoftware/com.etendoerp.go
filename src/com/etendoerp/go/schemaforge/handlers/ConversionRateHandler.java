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
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.currency.ConversionRate;

import com.etendoerp.go.schemaforge.AbstractNeoHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoErrorResponses;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6365: customization of the {@code conversion-rates} window ({@code C_Conversion_Rate},
 * "Conversión de divisas" / "Currency Conversion"). The window lets a user keep MANUAL rates for
 * the currencies the downloader does not fetch, and this class serves every write of it itself, on
 * every channel (REST single, REST batch, MCP {@code etendo_create}/{@code etendo_update}/
 * {@code etendo_delete}), so the rules below hold whoever writes.
 *
 * <p><b>Create</b> ({@code POST}, body {@code {toCurrency, multipleRateBy, validFromDate}}): the
 * server takes the organization currency of the request as the origin, writes on organization
 * {@code *} of the tenant, rate type {@code S}, not synced, open end; it inserts the rate AND its
 * inverse, and closes the range in force one minute before the new start (both directions). A
 * currency equal to the organization one, a downloaded currency (it has an {@code SMFCR_RULE}), a
 * rate that is not greater than zero, a missing start and a start inside another range are refused
 * before anything is written ({@link ConversionRateRefusal}).</p>
 *
 * <p><b>Update</b> ({@code PATCH}/{@code PUT}): only {@code multipleRateBy} (the inverse is
 * recomputed) and {@code validFromDate} (same overlap rules; the range closed by this one follows
 * it). <b>Delete</b> removes both rows and does not reopen the previous range. Both are refused on a
 * downloaded range and on one used in accounting (states, see {@link ConversionRateResponses}).</p>
 *
 * <p><b>Actions</b>: {@link ConversionRateActions}. Reads are the generic ones; the state columns
 * {@code etgoRateStatus} / {@code etgoUsedInAccounting} are virtual AD columns.</p>
 */
@NeoExtension(spec = "conversion-rates", entity = "conversionRate")
public class ConversionRateHandler extends AbstractNeoHandler {

  private static final Logger log = LogManager.getLogger(ConversionRateHandler.class);

  private static final String METHOD_DELETE = "DELETE";
  private static final String DATA_ENVELOPE = "data";

  /** One write of the handler, run under admin mode with the refusals translated. */
  @FunctionalInterface
  interface Write {
    NeoResponse run(ConversionRateWrites writes) throws JSONException;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoEndpointType type = context.getEndpointType();
    if (type == NeoEndpointType.ACTION) {
      return ConversionRateActions.serves(context.getFieldName()) ? runAction(context) : null;
    }
    if (type != null && type != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    String recordId = StringUtils.trimToNull(context.getRecordId());
    if (METHOD_POST.equals(method) && recordId == null) {
      return runWrite(context, writes -> create(writes, body(context)));
    }
    if (recordId == null) {
      return null;
    }
    if (METHOD_PUT.equals(method) || METHOD_PATCH.equals(method)) {
      return runWrite(context, writes -> update(writes, recordId, body(context)));
    }
    if (METHOD_DELETE.equals(method)) {
      return runWrite(context, writes -> delete(writes, recordId));
    }
    return null;
  }

  private NeoResponse create(ConversionRateWrites writes, JSONObject body) throws JSONException {
    ConversionRate forward = writes.applyCreate(writes.planCreate(writes.parseCreate(body)));
    return ConversionRateResponses.record(forward, store());
  }

  private NeoResponse update(ConversionRateWrites writes, String recordId, JSONObject body)
      throws JSONException {
    ConversionRate row = store().rate(recordId);
    NeoResponse missing = refuseUnknown(row, recordId);
    if (missing != null) {
      return missing;
    }
    return ConversionRateResponses.record(writes.applyUpdate(row, body), store());
  }

  private NeoResponse delete(ConversionRateWrites writes, String recordId) {
    ConversionRate row = store().rate(recordId);
    NeoResponse missing = refuseUnknown(row, recordId);
    if (missing != null) {
      return missing;
    }
    writes.applyDelete(row);
    return NeoResponse.noContent();
  }

  /** A 404 for an unknown row or a row of another tenant, {@code null} for one the caller owns. */
  private NeoResponse refuseUnknown(ConversionRate row, String recordId) {
    if (row != null && row.getClient().getId().equals(store().currentClientId())) {
      return null;
    }
    return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
        "Conversion rate not found: " + recordId);
  }

  private NeoResponse runWrite(NeoContext context, Write write) {
    ConversionRateWrites writes = new ConversionRateWrites(store());
    try {
      enterAdminMode();
      return write.run(writes);
    } catch (ConversionRateRefusal refusal) {
      // Thrown before the first write: nothing to roll back.
      return refusal.toResponse();
    } catch (Exception e) {
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.ensureTopLevelMessage(ConversionRateResponses.failure(e,
          context.getHttpMethod(), writes.pairIsoCodes()));
    } finally {
      exitAdminMode();
    }
  }

  private NeoResponse runAction(NeoContext context) {
    try {
      enterAdminMode();
      return ConversionRateActions.run(context, store());
    } catch (Exception e) {
      log.error("[ETP-6365] conversion-rates action {} failed", context.getFieldName(), e);
      return NeoErrorResponses.fromException(e, context.getHttpMethod(),
          "conversion-rates action", null);
    } finally {
      exitAdminMode();
    }
  }

  /**
   * Fills, on MCP {@code etendo_create}, the AD-mandatory fields the server decides before the
   * mandatory pre-check runs (it precedes {@link #handle} on that channel): {@code currency} (the
   * organization currency), {@code conversionRateType} ({@code S}) and {@code divideRateBy}
   * (1 / {@code multipleRateBy}). Never overwrites a value the caller sent, never throws; the
   * values are informative only, since {@link #handle} decides them again.
   */
  @Override
  public void resolveServerCreateFields(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null || StringUtils.isNotBlank(context.getRecordId())) {
        return;
      }
      JSONObject fields = unwrap(body);
      enterAdminMode();
      try {
        putIfAbsent(fields, ConversionRateWrites.F_CURRENCY, store().orgCurrencyId());
      } finally {
        exitAdminMode();
      }
      putIfAbsent(fields, ConversionRateWrites.F_RATE_TYPE, ConversionRateWrites.SPOT_RATE_TYPE);
      BigDecimal rate = ConversionRateRules.parseRate(fields.opt(ConversionRateWrites.F_RATE));
      if (rate != null) {
        putIfAbsent(fields, ConversionRateWrites.F_DIVIDE_RATE, ConversionRateRules.inverse(rate));
      }
    } catch (JSONException | RuntimeException e) {
      log.debug("[ETP-6365] Conversion rate create defaults skipped: {}", e.getMessage());
    }
  }

  private static void putIfAbsent(JSONObject fields, String key, Object value)
      throws JSONException {
    if (value != null && (!fields.has(key) || fields.isNull(key))) {
      fields.put(key, value);
    }
  }

  @Override
  public Set<String> serverResolvedCreateFields() {
    return Set.of(ConversionRateWrites.F_CURRENCY, ConversionRateWrites.F_RATE_TYPE,
        ConversionRateWrites.F_DIVIDE_RATE);
  }

  @Override
  public Set<String> callerRequiredCreateFields() {
    return Set.of(ConversionRateWrites.F_TO_CURRENCY, ConversionRateWrites.F_RATE,
        ConversionRateWrites.F_VALID_FROM);
  }

  @Override
  public Set<String> governedCreateFields(NeoContext context) {
    return Set.of(ConversionRateWrites.F_CURRENCY, ConversionRateWrites.F_RATE_TYPE,
        ConversionRateWrites.F_DIVIDE_RATE, ConversionRateWrites.F_VALID_TO);
  }

  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return ConversionRateActions.CONTRACTS;
  }

  /** The reads; a seam for unit tests. */
  ConversionRateStore store() {
    return new ConversionRateStore();
  }

  private static JSONObject body(NeoContext context) {
    JSONObject body = context.getRequestBody();
    return body == null ? new JSONObject() : unwrap(body);
  }

  /** The record fields, unwrapped from the optional {@code {"data": {...}}} envelope. */
  static JSONObject unwrap(JSONObject body) {
    JSONObject data = body.optJSONObject(DATA_ENVELOPE);
    return data != null ? data : body;
  }
}
