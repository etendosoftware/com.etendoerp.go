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

import java.math.BigDecimal;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.RemittanceLine;

/**
 * ETP-6148 — customization of the {@code remittance} spec's {@code lines}
 * ({@code REM_RemittanceLine}). Lines are added only by the remittance module's own process
 * («Seleccionar desde factura», {@code selectDueItems} / {@code syncDueItems} on the header).
 *
 * <ul>
 *   <li>With {@code sepa-remittances} off every request answers 403 {@code feature_disabled}.</li>
 *   <li>{@code POST} answers 405 {@code ETGO_RemittanceLinesReadOnly}.</li>
 *   <li>{@code PUT} / {@code PATCH} change «A remesar» ({@code amount}), the only writable field
 *       (every other key is read-only and dropped or refused by the generic write, as for any
 *       read-only field). Refused with 422 {@code ETGO_RemittanceProcessedNotEditable} on a
 *       processed remittance, {@code ETGO_RemittanceLineAmountNotEditable} for a line with no due,
 *       and {@code ETGO_RemittanceLineAmountInvalid} unless {@link RemittanceLineAmount#isValid}
 *       holds against the due's outstanding. REST and MCP run this same pre-hook.</li>
 *   <li>{@code DELETE} removes a line while its remittance is not processed (the generic delete,
 *       which is all REM's own removal does); a line of a processed remittance answers 422
 *       {@code ETGO_RemittanceProcessedNotEditable}. The entity must allow DELETE in its NEO
 *       configuration ({@code ETGO_SF_ENTITY.ISDELETE}).</li>
 * </ul>
 */
@NeoExtension(spec = "remittance", entity = "lines")
public class RemittanceLinesHandler implements NeoHandler {

  static final String MSG_READ_ONLY = "ETGO_RemittanceLinesReadOnly";
  static final String MSG_PROCESSED = RemittanceHeaderHandler.MSG_PROCESSED;
  private static final int SC_UNPROCESSABLE = 422;

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!isFlagEnabled()) {
      return SepaRemittancesFlag.featureDisabled();
    }
    String method = context.getHttpMethod();
    if (!NeoEndpointTypes.isCrud(context)) {
      return null;
    }
    if ("PUT".equals(method) || "PATCH".equals(method)) {
      return checkAmountUpdate(context.getRecordId(), context.getRequestBody());
    }
    if (NeoHandlerUtils.isWriteMethod(method)) {
      return NeoMessageText.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, MSG_READ_ONLY);
    }
    if ("DELETE".equals(method)) {
      return checkDelete(context.getRecordId());
    }
    return null;
  }

  /**
   * A line may be deleted only while its remittance is not processed; then the generic delete
   * runs. That is all REM's own line removal does ({@code DeleteRemittanceLineActionHandler}:
   * {@code OBDal.remove} plus flush), and {@code REM_REMITTANCELINE_TRG} refuses deleting a line of
   * a processed remittance anyway; an unprocessed remittance stays {@code PE}, so no status
   * refresh is due. An unknown line is left to the generic delete, which answers it.
   */
  NeoResponse checkDelete(String lineId) {
    OBContext.setAdminMode(true);
    try {
      RemittanceLine line = loadLine(lineId);
      if (line != null && line.getRemittance() != null
          && Boolean.TRUE.equals(line.getRemittance().isProcessed())) {
        return NeoMessageText.error(SC_UNPROCESSABLE, MSG_PROCESSED);
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * A change of «A remesar»: refused on a processed remittance, on a line with no due, and for an
   * amount {@link RemittanceLineAmount#isValid} rejects against the due's outstanding (the amount of
   * the payment schedule detail the line points to). A body without {@code amount} changes nothing
   * this hook guards and is left to the generic write; so is an unknown line.
   *
   * @param lineId the line id
   * @param body   the request body, flat or wrapped in {@code data}
   * @return the refusal, or {@code null} to let the generic write run
   */
  NeoResponse checkAmountUpdate(String lineId, JSONObject body) {
    OBContext.setAdminMode(true);
    try {
      RemittanceLine line = loadLine(lineId);
      if (line == null) {
        return null;
      }
      if (line.getRemittance() != null
          && Boolean.TRUE.equals(line.getRemittance().isProcessed())) {
        return NeoMessageText.error(SC_UNPROCESSABLE, MSG_PROCESSED);
      }
      JSONObject values = body != null && body.optJSONObject("data") != null
          ? body.optJSONObject("data") : body;
      if (values == null || !values.has(RemittanceLine.PROPERTY_AMOUNT)) {
        return null;
      }
      FIN_PaymentScheduleDetail due = line.getPaymentScheduleDetail();
      if (due == null) {
        return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceLineAmount.MSG_NOT_EDITABLE);
      }
      BigDecimal amount = RemittanceLineAmount.parse(values.opt(RemittanceLine.PROPERTY_AMOUNT));
      if (!RemittanceLineAmount.isValid(amount, due.getAmount())) {
        return RemittanceLineAmount.invalid(due.getAmount());
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  RemittanceLine loadLine(String lineId) {
    return StringUtils.isBlank(lineId) ? null
        : TenantOwnership.loadOwned(RemittanceLine.class, lineId);
  }

  boolean isFlagEnabled() {
    return SepaRemittancesFlag.isEnabled();
  }
}
