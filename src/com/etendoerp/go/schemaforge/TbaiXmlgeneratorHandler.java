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

import java.util.regex.Pattern;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.invoice.Invoice;

import com.smf.ticketbai.data.TbaiSyncinvoice;
import com.smf.ticketbai.data.TbaiValcode;

/**
 * NeoHandler delegate for the legacy TicketBAI XML generation button on Sales Invoice.
 *
 * <p>Classic Etendo wires {@code Em_Tbai_Xmlgenerator} to
 * {@code com.smf.ticketbai.process.XMLConvertionFromInvoice}, an OBUIAPP handler
 * that expects a {@code recordIds} array in its request content. This adapter
 * builds that payload for a single invoice and delegates to the standard NEO
 * OBUIAPP execution path.
 *
 * <p><b>Failed attempts are recorded (ETP-5700).</b> {@code XMLConvertionFromInvoice} only writes
 * {@code tbai_syncinvoice} once the tax authority has answered. When the send fails earlier — a
 * local validation while building the XML (e.g. a missing postal code), a signing or connection
 * error — it just returns the error in {@code responseActions} and writes nothing, so
 * {@code ETGO_GET_TBAI_STATUS} kept reporting "Pendiente" after a failed send. On such a failure
 * this handler writes the same trace ticketbai's own auto-send writes in
 * {@code ProcessInvoiceTbaiHook#generateTbaiStatusLog}: a {@code TbaiSyncinvoice} with
 * {@code estado = "Error"} / {@code descripcion = "01"} plus a {@code TbaiValcode} holding the
 * message. "The authority did not answer" is detected by the {@code tbai_syncinvoice} row count
 * of the invoice being unchanged across the call — independent of the (translated) message text,
 * so a rejection ticketbai already recorded is never duplicated.
 *
 * <p>The rows are flushed into the request transaction, which commits at the end of the request:
 * an error {@link NeoResponse} is a returned value, not a thrown exception, so neither
 * {@code NeoServletSupport} nor the DAL request filter rolls it back.
 */
@Named("tbai-xmlgenerator")
public class TbaiXmlgeneratorHandler extends AbstractLegacyInvoiceActionHandler {

  static final String ACTION_NAME = "EM_Tbai_Xmlgenerator";
  static final String ACTION_NAME_LEGACY = "Em_Tbai_Xmlgenerator";
  static final String ACTION_NAME_QUALIFIER = "tbaiXmlgenerator";
  private static final String PROCESS_ID = "BE2486102F2C41779B760609FD69A225";
  private static final String PROCESS_CLASS = "com.smf.ticketbai.process.XMLConvertionFromInvoice";

  private static final Logger log = LogManager.getLogger(TbaiXmlgeneratorHandler.class);

  /** Same values ticketbai's own auto-send writes for a failed attempt. */
  static final String FAILED_ATTEMPT_STATUS = "Error";
  static final String FAILED_ATTEMPT_CODE = "01";
  /** {@code TBAI_VALCODE.DESCRIPCION} is {@code VARCHAR(1000)}. */
  static final int VALCODE_DESCRIPTION_MAX_LENGTH = 1000;
  /**
   * AD_MESSAGE key of the ValCode text stored only when the failure carried no text at all.
   */
  static final String FALLBACK_FAILURE_MESSAGE_KEY = "ETGO_TbaiSendFailed";
  private static final String MESSAGE = "message";

  /**
   * A fully qualified Java class name followed by {@code ": "} — what {@code Throwable#toString}
   * prepends when an exception is wrapped as {@code new XException(cause)}. Matched only at the
   * start of the text or right after another {@code ": "}, and only with at least TWO lowercase
   * package segments ({@code java.lang.X}, {@code org.openbravo.base.exception.OBException}), so
   * ordinary prose ("sincronización Gipuzkoa: ...") and dotted field paths
   * ("Error: nif.Emisor: vacío") are never touched.
   */
  private static final Pattern QUALIFIED_EXCEPTION_PREFIX = Pattern.compile(
      "(?:^|(?<=: ))(?:[a-z_$][\\w$]*+\\.){2,}+[A-Z][\\w$]*+: ");

  @Override
  protected NeoResponse executeAction(String recordId) throws Exception {
    int attemptsBefore = countSyncAttempts(recordId);
    NeoResponse response;
    try {
      response = runTbaiProcess(recordId);
    } catch (Exception e) {
      // Defensive: NeoProcessService.executeObuiappClass already converts exceptions into 500
      // responses, so in practice failures arrive through the response branch below. Kept so a
      // throw before or around that bridge still leaves the trace.
      recordFailedAttemptIfUnanswered(recordId, attemptsBefore, cleanMessage(e.getMessage()));
      throw e;
    }
    if (response == null || response.getHttpStatus() < 400) {
      return response;
    }
    String message = cleanMessage(extractMessage(response.getBody()));
    replaceMessage(response.getBody(), message);
    recordFailedAttemptIfUnanswered(recordId, attemptsBefore, message);
    return response;
  }

  /** Runs ticketbai's {@code XMLConvertionFromInvoice} for one invoice (test seam). */
  NeoResponse runTbaiProcess(String recordId) throws Exception {
    return executeSingleRecordObuiappClass(PROCESS_CLASS, PROCESS_ID, recordId);
  }

  /** Number of {@code tbai_syncinvoice} rows (send attempts with a trace) of the invoice. */
  int countSyncAttempts(String invoiceId) {
    return OBDal.getInstance()
        .createCriteria(TbaiSyncinvoice.class)
        .add(Restrictions.eq(
            TbaiSyncinvoice.PROPERTY_INVOICE + ".id", invoiceId))
        .count();
  }

  /**
   * Records the failed attempt unless ticketbai already wrote a row during this call (the tax
   * authority answered, e.g. "Rechazado"). Never lets its own failure mask the original error.
   *
   * <p><b>Concurrent sends.</b> The before/after counts run under READ COMMITTED: if another
   * request commits a ticketbai row for the same invoice between the two counts, the count changes
   * and this failure is not recorded. The worst case is therefore a missing Error row, never a
   * duplicate one.
   */
  private void recordFailedAttemptIfUnanswered(String invoiceId, int attemptsBefore,
      String message) {
    try {
      if (countSyncAttempts(invoiceId) != attemptsBefore) {
        return;
      }
      String description = StringUtils.isBlank(message)
          ? OBMessageUtils.messageBD(FALLBACK_FAILURE_MESSAGE_KEY)
          : message;
      recordFailedAttempt(invoiceId, description);
    } catch (Exception e) {
      log.error("Could not record the failed TicketBAI attempt of invoice {}", invoiceId, e);
    }
  }

  /**
   * Persists the failed-attempt trace, mirroring
   * {@code com.smf.ticketbai.hooks.ProcessInvoiceTbaiHook#generateTbaiStatusLog}.
   */
  void recordFailedAttempt(String invoiceId, String message) {
    OBDal dal = OBDal.getInstance();
    Invoice invoice = dal.get(Invoice.class, invoiceId);
    if (invoice == null) {
      return;
    }
    TbaiSyncinvoice syncInvoice = OBProvider.getInstance().get(TbaiSyncinvoice.class);
    syncInvoice.setNewOBObject(true);
    syncInvoice.setOrganization(invoice.getOrganization());
    syncInvoice.setClient(invoice.getClient());
    syncInvoice.setInvoice(invoice);
    syncInvoice.setEstado(FAILED_ATTEMPT_STATUS);
    syncInvoice.setDescripcion(FAILED_ATTEMPT_CODE);
    dal.save(syncInvoice);

    TbaiValcode valCode = OBProvider.getInstance().get(TbaiValcode.class);
    valCode.setNewOBObject(true);
    valCode.setOrganization(invoice.getOrganization());
    valCode.setClient(invoice.getClient());
    valCode.setTbaiSyncinvoiceID(syncInvoice);
    valCode.setDescripcion(StringUtils.left(message, VALCODE_DESCRIPTION_MAX_LENGTH));
    dal.save(valCode);
    dal.flush();
  }

  /** Strips {@code <fully.qualified.ExceptionClass>: } prefixes; {@code null} becomes "". */
  static String cleanMessage(String message) {
    if (message == null) {
      return "";
    }
    return QUALIFIED_EXCEPTION_PREFIX.matcher(message).replaceAll("").trim();
  }

  /** Reads the flat {@code message}, falling back to the nested {@code error.message}. */
  private static String extractMessage(JSONObject body) {
    if (body == null) {
      return null;
    }
    String message = body.optString(MESSAGE, null);
    if (StringUtils.isBlank(message)) {
      JSONObject error = body.optJSONObject("error");
      message = error == null ? null : error.optString(MESSAGE, null);
    }
    return message;
  }

  private static void replaceMessage(JSONObject body, String message) {
    if (body == null || StringUtils.isBlank(message)) {
      return;
    }
    try {
      if (body.has(MESSAGE)) {
        body.put(MESSAGE, message);
      }
      JSONObject error = body.optJSONObject("error");
      if (error != null && error.has(MESSAGE)) {
        error.put(MESSAGE, message);
      }
    } catch (JSONException e) {
      log.debug("Could not clean the TicketBAI error message", e);
    }
  }

  @Override
  protected boolean matchesActionName(String fieldName) {
    return ACTION_NAME.equals(fieldName)
        || ACTION_NAME_LEGACY.equals(fieldName)
        || ACTION_NAME_QUALIFIER.equals(fieldName);
  }

  @Override
  protected String buildExecutionErrorMessage(Exception e) {
    return "TicketBAI XML generation failed: " + cleanMessage(e.getMessage());
  }
}
