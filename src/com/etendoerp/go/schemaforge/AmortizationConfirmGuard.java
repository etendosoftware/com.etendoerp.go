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
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.assetmgmt.Amortization;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;

/**
 * Closes the gap ETP-5414 found in {@code a_amortization_process} (PL/pgSQL): confirming an
 * amortization with no lines, a line missing its percentage, or a line with a zero/negative
 * amount is only ever blocked CLIENT-side (the bulk bar's {@code validateConfirmEligibility},
 * the row kebab, and {@code AmortizationConfirmModal.jsx}). Any OTHER consumer of the NEO API —
 * MCP, a script, a raw curl — could confirm straight through, at which point
 * {@code TotalAmortization} resolves to {@code NULL} (a {@code sum()} over zero rows) and the
 * document is left {@code Processed='Y'} in an unrecoverable state: {@code a_amortizationline_trg}
 * then refuses further edits. This guard closes that hole for EVERY consumer, not just the UI
 * surfaces this ticket already built.
 *
 * <p><b>Confirm vs reactivate, decided the same way the PL/pgSQL itself decides it</b> (read in
 * full for ETP-5414's confirm/reactivate wire-action investigation): {@code a_amortization_process}
 * branches on the row's CURRENT {@code Processed} column, not on caller intent — {@code Processed
 * = 'N'} means the call is about to CONFIRM, {@code Processed = 'Y'} means it is about to
 * REACTIVATE. This guard reads the same column, the same way, before letting the action proceed:
 * only a currently-unconfirmed ({@code Processed = 'N'}) row is validated. A confirmed row heading
 * into reactivate is waved through unvalidated — reactivate has no lines precondition at all in
 * the PL/pgSQL (its only gate is {@code Processed='Y' AND Posted<>'Y'}), so validating lines on
 * that path would reject a perfectly legal reactivate.
 *
 * <p><b>Why the PATCH/PUT path is blocked outright, not merely validated (ETP-5414 follow-up).</b>
 * {@code header.Processed} is writable directly through the generic CRUD endpoint
 * (a plain {@code PATCH /header/{id}} with {@code {"processed":"Y"}} in the body never reaches
 * {@code a_amortization_process} at all — that PL/pgSQL only runs behind the button/action route,
 * {@code POST .../action/Processed}). Validating the lines on that path would not be enough to
 * make it safe: even with perfectly valid lines, a bare PATCH write skips the ENTIRE business
 * process — no {@code TotalAmortization} recompute, no org/period checks, and (verified by reading
 * the live function body) no recompute of the linked assets' {@code DepreciatedValue}/
 * {@code IsFullyDepreciated} (the unconditional {@code UPDATE a_asset} block that runs after the
 * confirm/reactivate branch, gated only by the never-set {@code FINISH_PROCESS} flag). A
 * lines-valid PATCH would leave the document marked {@code Processed='Y'} while every asset it
 * amortizes stays un-amortized — a WORSE inconsistency than the lines gap this guard was built to
 * close, and one no amount of lines validation would catch. So a direct write to {@code processed}
 * is rejected unconditionally; the caller is expected to use the action endpoint, which always
 * runs the real process.
 *
 * <p>Mirrors {@link GoodsMovementProcessGuard}'s shape exactly: a static, non-instantiable guard
 * in its own file, called first thing from the entity handler's {@code handle()}, returning
 * {@code null} to let the request continue or a {@link NeoResponse} error to short-circuit it
 * before {@code NeoHookDispatcher} ever reaches the default action/CRUD path.
 */
final class AmortizationConfirmGuard {

  private static final Logger log = LogManager.getLogger(AmortizationConfirmGuard.class);

  /** Button columnName the frontend calls for both confirm and reactivate (see class javadoc). */
  private static final String ACTION_FIELD_PROCESSED = "Processed";
  /** DAL/contract property name for the same column, as it appears in a CRUD request body. */
  private static final String BODY_PROPERTY_PROCESSED = "processed";
  private static final String VALUE_NOT_PROCESSED = "N";

  private static final String MSG_NO_LINES = "ETGO_AmortizationNoLines";
  private static final String MSG_MISSING_PERCENTAGE = "ETGO_AmortizationLineMissingPercentage";
  private static final String MSG_INVALID_AMOUNT = "ETGO_AmortizationLineInvalidAmount";
  private static final String MSG_PROCESSED_DIRECT_WRITE_BLOCKED =
      "ETGO_AmortizationProcessedDirectUpdateBlocked";

  private AmortizationConfirmGuard() {
    // Static utility, not instantiable.
  }

  /**
   * Entry point called from {@code AmortizationHeaderHandler#handle}. Returns {@code null} to let
   * the request proceed, or a {@link NeoResponse} error to short-circuit it.
   */
  static NeoResponse validateBeforeAction(NeoContext context) {
    NeoResponse patchRejection = rejectDirectProcessedWrite(context);
    if (patchRejection != null) {
      return patchRejection;
    }
    return validateLinesBeforeConfirm(context);
  }

  /**
   * Rejects any CRUD PATCH/PUT that touches {@code processed} directly — see class javadoc for
   * why this is an outright block rather than a validated pass-through.
   */
  private static NeoResponse rejectDirectProcessedWrite(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!"PATCH".equals(method) && !"PUT".equals(method)) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || !body.has(BODY_PROPERTY_PROCESSED)) {
      return null;
    }
    return errorResponse(MSG_PROCESSED_DIRECT_WRITE_BLOCKED);
  }

  /**
   * Validates lines before letting a confirm (not reactivate) action proceed. See class javadoc
   * for the confirm-vs-reactivate detection and the three checks themselves.
   */
  private static NeoResponse validateLinesBeforeConfirm(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !ACTION_FIELD_PROCESSED.equals(context.getFieldName())) {
      return null;
    }
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      Amortization amortization = OBDal.getInstance().get(Amortization.class, recordId);
      if (amortization == null) {
        return null;
      }
      if (!VALUE_NOT_PROCESSED.equals(amortization.getProcessed())) {
        // Already processed → this call is a REACTIVATE, not a confirm. No lines precondition
        // applies to reactivate in a_amortization_process — let it through unvalidated.
        return null;
      }
      List<AmortizationLine> lines = amortization.getFinancialMgmtAmortizationLineList();
      if (lines == null || lines.isEmpty()) {
        return errorResponse(MSG_NO_LINES);
      }
      for (AmortizationLine line : lines) {
        if (line.getAmortizationPercentage() == null) {
          return errorResponse(MSG_MISSING_PERCENTAGE);
        }
      }
      for (AmortizationLine line : lines) {
        BigDecimal amount = line.getAmortizationAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
          return errorResponse(MSG_INVALID_AMOUNT);
        }
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static NeoResponse errorResponse(String messageKey) {
    try {
      String message = OBMessageUtils.messageBD(messageKey);
      JSONObject body = new JSONObject();
      body.put("status", "error");
      body.put("code", messageKey);
      body.put("message", message);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, body);
    } catch (JSONException e) {
      log.warn("[AMORTIZATION] Could not build error response for {}: {}", messageKey,
          e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, messageKey);
    }
  }
}
