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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * NeoHandler for the Sales Invoice header entity.
 * <p>
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler} (uses {@code CloneInvoiceHook})</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentHandler}</li>
 *   <li>{@code Em_Aeatsii_Send} → {@link SiiSendHandler}</li>
 *   <li>{@code Em_Tbai_Xmlgenerator} → {@link TbaiXmlgeneratorHandler}</li>
 * </ul>
 *
 * <p>Before the Complete action (documentAction=CO), creates the total discount line so it is
 * included in the completed invoice. Delegates to {@link TotalDiscountService} via the shared
 * helper in {@link AbstractOrderHeaderHandler}.
 */
@Named("salesInvoiceHeaderHandler")
public class SalesInvoiceHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(SalesInvoiceHeaderHandler.class);

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private RegisterPaymentHandler registerPaymentHandler;

  @Inject
  private SiiSendHandler siiSendHandler;

  @Inject
  private TbaiXmlgeneratorHandler tbaiXmlgeneratorHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

  /**
   * Rounds a monetary value to 2 decimal places using half-up rounding.
   *
   * @param value
   *     the raw computed amount
   * @return the value rounded to 2 decimal places
   */
  private static double roundHalfUp(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  /**
   * Pre-hook: creates the total-discount line before the Complete action and routes all other
   * ACTION requests to the appropriate downstream handler.
   *
   * @param context
   *     the current request context
   * @return the response from the matched downstream handler, or null if none matched
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    return NeoHeaderActionRouter.dispatch(context, cloneRecordHandler, registerPaymentHandler, siiSendHandler,
        tbaiXmlgeneratorHandler);
  }

  /**
   * Adjusts grandTotalAmount and outstandingAmount in GET responses for draft invoices that have
   * an etgoTotalDiscount set. Confirmed invoices already have the discount reflected in the DB via
   * negative lines created by TotalDiscountService at completion time.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod()) || !NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = prev.getBody();
      JSONObject wrapper = body.optJSONObject("response");
      if (wrapper == null) {
        return null;
      }
      JSONArray data = wrapper.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return null;
      }
      for (int i = 0; i < data.length(); i++) {
        applyTotalDiscountToRecord(data.getJSONObject(i));
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error adjusting grandTotalAmount for total discount", e);
      return null;
    }
  }

  /**
   * Applies the total-discount factor to {@code grandTotalAmount} and {@code outstandingAmount}
   * in the given record. Skips confirmed invoices ({@code processed=true}) and records with no
   * positive discount.
   *
   * @param invoice
   *     a single invoice record from the response data array; modified in-place
   * @throws Exception
   *     if a JSON read or write operation fails
   */
  private void applyTotalDiscountToRecord(JSONObject invoice) throws Exception {
    if (invoice.optBoolean("processed", false)) {
      return;
    }
    double discountPct = invoice.optDouble("etgoTotalDiscount", 0.0);
    if (discountPct <= 0.0) {
      return;
    }
    double factor = 1.0 - discountPct / 100.0;

    double grandTotal = invoice.optDouble("grandTotalAmount", 0.0);
    invoice.put("grandTotalAmount", roundHalfUp(grandTotal * factor));

    double outstanding = invoice.optDouble("outstandingAmount", 0.0);
    invoice.put("outstandingAmount", roundHalfUp(outstanding * factor));
  }
}
