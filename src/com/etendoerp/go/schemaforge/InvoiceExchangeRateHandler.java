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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.invoice.Invoice;

/**
 * NeoHandler for the document-level exchange-rate sub-tab on Purchase Invoice
 * and Sales Invoice.
 *
 * <p>On POST, derives the {@code currency} (from) value from the parent invoice's
 * currency. Mirrors classic Etendo behavior where {@code C_Conversion_Rate_Document.C_Currency_ID}
 * is non-editable and auto-populated by callout from the invoice header.
 *
 * <p>On PATCH/PUT, recomputes the counterpart of the {@code rate} / {@code foreignAmount} pair
 * from the parent invoice's grand total, so a manually edited rate keeps the foreign amount in
 * sync (and vice versa) without relying on a client-side callout.
 *
 * <p>Registered via {@code javaQualifier = "invoiceExchangeRateHandler"} on the
 * {@code exchangeRates} entity of the sales-invoice and purchase-invoice specs.
 */
@Named("invoiceExchangeRateHandler")
public class InvoiceExchangeRateHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InvoiceExchangeRateHandler.class);
  private static final String PARAM_PARENT_ID = "parentId";
  private static final String PROPERTY_INVOICE = "invoice";
  private static final String PROPERTY_CURRENCY = "currency";
  private static final String PROPERTY_TO_CURRENCY = "toCurrency";
  private static final String PROPERTY_RATE = "rate";
  private static final String PROPERTY_FOREIGN_AMOUNT = "foreignAmount";
  private static final String FIELD_DEFAULTS = "defaults";
  private static final int RATE_SCALE = 12;

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    String method = context.getHttpMethod();
    if ("POST".equals(method)) {
      return handleCreate(body);
    }
    if ("PATCH".equals(method) || "PUT".equals(method)) {
      return handleUpdate(context, body);
    }
    return null;
  }

  /**
   * POST: resolve the parent invoice from the body, default the {@code currency} / {@code toCurrency}
   * pair, and derive the missing side of {@code rate} / {@code foreignAmount}.
   */
  private NeoResponse handleCreate(JSONObject body) {
    String invoiceId = resolveInvoiceIdFromBody(body);
    if (invoiceId == null || invoiceId.isEmpty()) {
      return null;
    }
    Invoice invoice = loadInvoice(invoiceId);
    if (invoice == null) {
      return null;
    }
    try {
      if ((!body.has(PROPERTY_CURRENCY) || body.isNull(PROPERTY_CURRENCY))
          && invoice.getCurrency() != null) {
        body.put(PROPERTY_CURRENCY, invoice.getCurrency().getId());
      }
      // Default the "to" currency to the org functional currency so the document rate is stored as
      // an invoice-currency -> org-currency pair (the pair the completion validator looks up).
      if (!body.has(PROPERTY_TO_CURRENCY) || body.isNull(PROPERTY_TO_CURRENCY)) {
        String orgCurrencyId = resolveOrgCurrencyId(invoice);
        if (orgCurrencyId != null) {
          body.put(PROPERTY_TO_CURRENCY, orgCurrencyId);
        }
      }
      computeRateAndForeignAmount(body, invoice);
    } catch (Exception e) {
      // Abort the save: persisting the row without the derived currency/rate would
      // create an inconsistent document-level exchange rate. OBException rolls back
      // the transaction and surfaces the failure to the caller.
      log.error("Failed to inject derived fields on POST for invoice {}", invoiceId, e);
      throw new OBException(e);
    }
    return null;
  }

  /**
   * PATCH/PUT: an inline edit sends only the changed field (e.g. {@code {"rate": 5}}). Recompute the
   * counterpart from the existing row's parent invoice grand total so the foreign amount stays in
   * sync with a manually edited rate (and vice versa). No-op unless exactly one side of the pair is
   * present in the body — if both are supplied the caller is authoritative, and if neither is the
   * edit does not touch the rate/foreignAmount pair.
   */
  private NeoResponse handleUpdate(NeoContext context, JSONObject body) {
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isEmpty()) {
      return null;
    }
    boolean ratePresent = body.has(PROPERTY_RATE) && !body.isNull(PROPERTY_RATE);
    boolean foreignPresent = body.has(PROPERTY_FOREIGN_AMOUNT) && !body.isNull(PROPERTY_FOREIGN_AMOUNT);
    if (ratePresent == foreignPresent) {
      return null;
    }
    ConversionRateDoc doc = loadConversionRateDoc(recordId);
    if (doc == null || doc.getInvoice() == null) {
      return null;
    }
    try {
      computeRateAndForeignAmount(body, doc.getInvoice());
    } catch (Exception e) {
      log.error("Failed to recompute derived fields on update for conversion rate doc {}", recordId, e);
      throw new OBException(e);
    }
    return null;
  }

  /**
   * Mirrors the classic {@code SE_CalculateExchangeRate} callout for invoices:
   * derives the missing side of {@code rate} / {@code foreignAmount} from the
   * invoice's {@code grandTotalAmount}.
   *
   * <ul>
   *   <li>rate provided, foreignAmount missing/zero → foreignAmount = grandTotal × rate</li>
   *   <li>foreignAmount provided, rate missing/zero → rate = foreignAmount ÷ grandTotal</li>
   *   <li>both provided or grandTotal=0 → leave caller values untouched</li>
   * </ul>
   */
  private static void computeRateAndForeignAmount(JSONObject body, Invoice invoice)
      throws Exception {
    BigDecimal grandTotal = invoice.getGrandTotalAmount();
    if (grandTotal == null || grandTotal.signum() == 0) {
      return;
    }
    BigDecimal rate = readDecimal(body, PROPERTY_RATE);
    BigDecimal foreignAmount = readDecimal(body, PROPERTY_FOREIGN_AMOUNT);
    boolean hasRate = rate != null && rate.signum() != 0;
    boolean hasForeign = foreignAmount != null && foreignAmount.signum() != 0;
    if (hasRate && !hasForeign) {
      body.put(PROPERTY_FOREIGN_AMOUNT, grandTotal.multiply(rate));
    } else if (hasForeign && !hasRate) {
      body.put(PROPERTY_RATE, foreignAmount.divide(grandTotal, RATE_SCALE, RoundingMode.HALF_UP));
    }
  }

  private static BigDecimal readDecimal(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) {
      return null;
    }
    try {
      String raw = body.optString(key, null);
      if (raw == null || raw.isEmpty()) {
        return null;
      }
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * On the new-row form, the {@code defaults} endpoint resolves AD defaults but does
   * not know how to derive {@code currency} from the parent invoice. Inject it here
   * so the UI shows the from-currency value while the user fills the row, mirroring
   * what the POST hook does at save time.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.DEFAULTS.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    String invoiceId = readParentIdFromRequest();
    if (invoiceId == null) {
      return null;
    }
    String currencyId = resolveInvoiceCurrencyId(invoiceId);
    if (currencyId == null) {
      return null;
    }
    try {
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject(FIELD_DEFAULTS);
      if (defaults == null) {
        defaults = new JSONObject();
        body.put(FIELD_DEFAULTS, defaults);
      }
      if (!defaults.has(PROPERTY_CURRENCY) || defaults.isNull(PROPERTY_CURRENCY)) {
        defaults.put(PROPERTY_CURRENCY, currencyId);
      }
      if (!defaults.has(PROPERTY_TO_CURRENCY) || defaults.isNull(PROPERTY_TO_CURRENCY)) {
        String orgCurrencyId = resolveOrgCurrencyId(loadInvoice(invoiceId));
        if (orgCurrencyId != null) {
          defaults.put(PROPERTY_TO_CURRENCY, orgCurrencyId);
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Failed to inject currency default for invoice {}", invoiceId, e);
      return null;
    }
  }

  private static String readParentIdFromRequest() {
    try {
      if (RequestContext.get() == null || RequestContext.get().getRequest() == null) {
        return null;
      }
      String parentId = RequestContext.get().getRequest().getParameter(PARAM_PARENT_ID);
      return (parentId != null && !parentId.isEmpty()) ? parentId : null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * The parent FK arrives in the request body under different keys depending on the
   * caller: legacy/listing flows use {@code parentId}, while the new inline add-row
   * sends the relationship property name {@code invoice}. Accept either.
   */
  private static String resolveInvoiceIdFromBody(JSONObject body) {
    String invoiceId = body.optString(PROPERTY_INVOICE, null);
    if (invoiceId != null && !invoiceId.isEmpty()) {
      return invoiceId;
    }
    return body.optString(PARAM_PARENT_ID, null);
  }

  private static String resolveInvoiceCurrencyId(String invoiceId) {
    Invoice invoice = loadInvoice(invoiceId);
    return (invoice != null && invoice.getCurrency() != null) ? invoice.getCurrency().getId() : null;
  }

  private static String resolveOrgCurrencyId(Invoice invoice) {
    if (invoice == null || invoice.getOrganization() == null) {
      return null;
    }
    return OBCurrencyUtils.getOrgCurrency(invoice.getOrganization().getId());
  }

  private static Invoice loadInvoice(String invoiceId) {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(Invoice.class, invoiceId);
    } catch (Exception e) {
      log.error("Failed to load parent invoice {}", invoiceId, e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static ConversionRateDoc loadConversionRateDoc(String recordId) {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(ConversionRateDoc.class, recordId);
    } catch (Exception e) {
      log.error("Failed to load conversion rate doc {}", recordId, e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
