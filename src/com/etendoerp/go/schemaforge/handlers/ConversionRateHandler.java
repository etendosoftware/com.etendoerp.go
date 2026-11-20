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

import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
 * "Conversión de divisas" / "Currency Conversion"). The window keeps MANUAL rates for the
 * currencies the downloader does not fetch.
 *
 * <p><b>Writes are actions, one implementation for the window and the MCP.</b> A rate is created
 * with {@code createManualCurrency} ("Nueva divisa") or {@code createManualRate} ("Nueva tasa") and
 * its rate changed with {@code updateManualRate} ("Modificar la tasa", confirmation required); see
 * {@link ConversionRateActions}. The window's popups call the same actions, so the rules of
 * {@link ConversionRateWrites} hold whoever writes. The entity's {@code ISPOST}/{@code ISPUT}/
 * {@code ISPATCH} are {@code N}; should a configuration push open them again, {@link #handle}
 * still refuses a generic create or update (405, {@code ETGO_ConvRateUseActions}) instead of
 * letting it bypass the rules.</p>
 *
 * <p><b>Delete</b> stays a generic {@code DELETE}, served here: both rows of the range are removed
 * and the previous range is not reopened. Any manual range can be changed or deleted, used in
 * accounting or not; only a downloaded range is refused. The writes bypass the database triggers
 * ({@link ConversionRateTriggerBypass}); what they enforced is re-checked in
 * {@link ConversionRateWrites}.</p>
 *
 * <p>Reads are the generic ones; {@code etgoRateStatus} (CURRENT / HISTORIC, a list reference) is
 * a virtual AD column.</p>
 */
@NeoExtension(spec = "conversion-rates", entity = "conversionRate")
public class ConversionRateHandler extends AbstractNeoHandler {

  private static final Logger log = LogManager.getLogger(ConversionRateHandler.class);

  private static final String METHOD_DELETE = "DELETE";

  /**
   * Serves the actions and the delete of the entity, and refuses a generic create or update;
   * reads fall through to the generic CRUD.
   *
   * @param context the request context
   * @return the response, or {@code null} for a read or a request this customization does not own
   */
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
    if (isWriteMethod(method)) {
      return new ConversionRateRefusal(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          ConversionRateRefusal.USE_ACTIONS).toResponse();
    }
    String recordId = StringUtils.trimToNull(context.getRecordId());
    if (METHOD_DELETE.equals(method) && recordId != null) {
      return run(context, writes -> delete(writes, recordId));
    }
    return null;
  }

  private NeoResponse delete(ConversionRateWrites writes, String recordId) {
    ConversionRate row = writes.writableRate(recordId);
    if (row == null) {
      return ConversionRateResponses.notFound(recordId);
    }
    writes.applyDelete(row);
    return NeoResponse.noContent();
  }

  /**
   * The delete of a row the tenant can read but not delete — a System ({@code 0}) rate, where the
   * downloader writes — reaches here instead of the customization: the CRUD guard refused it
   * first. A downloaded one is answered with the synced-lock refusal (409
   * {@code ETGO_ConvRateSyncedLocked}) on every channel; any other keeps the guard's 404. The row
   * is never written either way.
   *
   * @param context the delete context
   * @return the 409, or {@code null} to keep the 404
   */
  @Override
  public NeoResponse refusalForReadOnlyTarget(NeoContext context) {
    String recordId = StringUtils.trimToNull(context == null ? null : context.getRecordId());
    if (recordId == null) {
      return null;
    }
    try {
      enterAdminMode();
      new ConversionRateWrites(store()).writableRate(recordId);
      return null;
    } catch (ConversionRateRefusal refusal) {
      return refusal.toResponse();
    } finally {
      exitAdminMode();
    }
  }

  private NeoResponse runAction(NeoContext context) {
    return run(context, writes -> ConversionRateActions.run(context, store(), writes));
  }

  /**
   * Runs one request under admin mode: a refusal (always thrown before the first write) is
   * answered as is; any other failure rolls the session back and is translated.
   */
  private NeoResponse run(NeoContext context, ConversionRateActions.Work work) {
    ConversionRateWrites writes = new ConversionRateWrites(store());
    try {
      enterAdminMode();
      return work.run(writes);
    } catch (ConversionRateRefusal refusal) {
      return refusal.toResponse();
    } catch (Exception e) {
      log.debug("[ETP-6365] conversion-rates request failed: {}", e.getMessage());
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.ensureTopLevelMessage(ConversionRateResponses.failure(e,
          context.getHttpMethod(), writes));
    } finally {
      exitAdminMode();
    }
  }

  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return ConversionRateActions.CONTRACTS;
  }

  /** The reads; a seam for unit tests. */
  ConversionRateStore store() {
    return new ConversionRateStore();
  }
}
