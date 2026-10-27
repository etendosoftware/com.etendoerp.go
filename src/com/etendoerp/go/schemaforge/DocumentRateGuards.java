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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.invoice.Invoice;

/**
 * The exchange-rate pre-hook rules of the commercial document headers (ETP-5840), one entry point
 * per family, called explicitly from each header customization's {@code handle()} with its own
 * {@link DocumentCurrencyRate}. Kept out of {@link AbstractOrderHeaderHandler} and
 * {@link AbstractInvoiceHeaderHandler} so those classes stay within the method budget
 * (java:S1448); the behaviour is the one they documented.
 */
final class DocumentRateGuards {

  private static final Logger log = LogManager.getLogger(DocumentRateGuards.class);
  private static final String DOC_ACTION_COMPLETE = "CO";

  private DocumentRateGuards() {
  }

  /**
   * Exchange-rate rules of an order-type header (orders, quotations), to call from the
   * customization's {@code handle()} BEFORE anything with side effects runs (total discount,
   * actions):
   * <ul>
   *   <li>publishes the document's rate type for {@code currencyOptions};</li>
   *   <li>CRUD write → {@link DocumentCurrencyRate#beforeWrite}: recalculates the rate when the
   *       order date or the currency changes and the request sends none, and refuses a
   *       foreign-currency save without a rate (the completion text when the write completes);</li>
   *   <li>completion through an action ({@code documentAction} = {@code CO}, or the
   *       {@code DocAction} button running {@code CO}) → refused when the stored rate is empty.</li>
   * </ul>
   * REST single, REST batch and MCP all reach this pre-hook.
   *
   * @param context the request
   * @param rate    the customization's own {@link DocumentCurrencyRate}
   * @return the refusal, or {@code null} to carry on
   */
  static NeoResponse guardOrderHeader(NeoContext context, DocumentCurrencyRate rate) {
    context.setAttribute(DocumentCurrencyRate.ATTR_RATE_TYPE, rate.type());
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return rate.beforeWrite(context, AbstractOrderHeaderHandler.isCompleteAction(context));
    }
    if (orderCompletesThroughAction(context)) {
      return rate.refuseCompletionWithoutRate(context.getRecordId());
    }
    return null;
  }

  /**
   * Whether an ACTION request completes the order: {@code documentAction} with {@code CO}, or the
   * {@code DocAction} button whose effective value is {@code CO} (what the quotation's "send to
   * evaluation" posts with an empty body, and what an agent's {@code etendo_action} runs).
   */
  static boolean orderCompletesThroughAction(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || StringUtils.isBlank(context.getRecordId())) {
      return false;
    }
    if (AbstractOrderHeaderHandler.isActionDocumentActionComplete(context)) {
      return true;
    }
    if (!DocActionRequestSupport.isDocActionButtonPost(context)) {
      return false;
    }
    try {
      return DOC_ACTION_COMPLETE.equals(DocActionRequestSupport.effectiveDocAction(context));
    } catch (Exception e) {
      log.debug("[ETP-5840] Could not resolve the DocAction of {}: {}", context.getRecordId(),
          e.getMessage());
      return false;
    }
  }

  /**
   * Exchange-rate rules of an invoice header, to call from the customization's {@code handle()}
   * after the accounting date is mirrored on create and BEFORE anything with side effects runs
   * (total discount, completion):
   * <ul>
   *   <li>publishes the document's rate type for {@code currencyOptions};</li>
   *   <li>CRUD write → {@link DocumentCurrencyRate#beforeWrite}: recalculates the rate when the
   *       accounting date or the currency changes and the request sends none, and refuses a
   *       foreign-currency save without a rate (the completion text when the write completes);</li>
   *   <li>completion through the {@code documentAction} action → refused when the stored rate is
   *       empty. This strict rule is the GO channels' only: {@link InvoiceCompletionRateHook}, which
   *       {@code ProcessInvoiceUtil} also runs (classic backoffice included), keeps its pre-ETP-5840
   *       behaviour (any rate on the invoice date, {@code SMFCR_NoRateOnComplete}).</li>
   * </ul>
   *
   * @param context the request
   * @param rate    the customization's own {@link DocumentCurrencyRate}
   * @return the refusal, or {@code null} to carry on
   */
  static NeoResponse guardInvoiceHeader(NeoContext context, DocumentCurrencyRate rate) {
    context.setAttribute(DocumentCurrencyRate.ATTR_RATE_TYPE, rate.type());
    boolean completing = InvoiceCalloutHelper.isInvoiceCompleteAction(context);
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return rate.beforeWrite(context, completing);
    }
    return completing ? rate.refuseCompletionWithoutRate(context.getRecordId()) : null;
  }

  /**
   * Whether this invoice header CRUD write left the rate empty on purpose: its body carries
   * {@code eTGOCurrencyRate} without a positive value — sent so by the caller, or written so by the
   * pre-hook's recalculation ({@link DocumentCurrencyRate#beforeWrite}). Only then may the
   * rate-document sync delete the invoice's rows; a write that does not touch the rate, and a line
   * save, never do.
   */
  static boolean invoiceHeaderWriteClearedRate(NeoContext context) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || context.getRequestBody() == null) {
      return false;
    }
    JSONObject body = context.getRequestBody();
    return body.has(Invoice.PROPERTY_ETGOCURRENCYRATE)
        && DocumentCurrencyRate.parseRate(body.opt(Invoice.PROPERTY_ETGOCURRENCYRATE)) == null;
  }
}
