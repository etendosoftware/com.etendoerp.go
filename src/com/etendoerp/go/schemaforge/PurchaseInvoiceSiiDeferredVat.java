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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6284 — whether a purchase invoice is an import of deferred VAT, the case where the SPA offers
 * the compensatory import-tax line ("Añadir línea compensatoria").
 *
 * <pre>GET /sws/neo/purchase-invoice/header/{id}/action/siiDeferredVat</pre>
 *
 * <p>Body: {@code {deferredVat}}. {@code true} when the invoice is sent to SII
 * ({@link #applicableConfig}), its SII invoice type is F5 or LC, the organization declares IVA, and
 * {@link SIIUtils#isDeferredVATInvoice} classifies it as deferred VAT — the same rule the Classic
 * send uses to pick the deferred-VAT registry builder. Read-only; works on a draft.
 *
 * <p>Called explicitly by {@link PurchaseInvoiceHeaderHandler}, never from shared code.
 */
final class PurchaseInvoiceSiiDeferredVat {

  static final String ACTION_NAME = "siiDeferredVat";

  static final String KEY_DEFERRED_VAT = "deferredVat";

  private static final String KEY_F5 = "F5";
  private static final String KEY_LC = "LC";

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceSiiDeferredVat.class);

  /** Published for agents next to the header's other actions; served over GET only. */
  static final NeoActionContract CONTRACT = NeoActionContract.read(ACTION_NAME,
      "Whether this purchase invoice is an import (F5/LC) of deferred VAT, sent to SII by an IVA "
          + "organization: {deferredVat}. When true, each import tax needs a compensatory manual "
          + "tax row (the matching '=0%' import tax, base = minus that tax's base, tax amount 0). "
          + "Read-only; works on a draft.")
      .withIdDescription("the id of the purchase invoice")
      .withHttpMethod("GET");

  private PurchaseInvoiceSiiDeferredVat() {
  }

  /**
   * Serves the action when the request is it, else {@code null}.
   *
   * @param context the header request
   * @return the response, or {@code null} when the request is not this action
   */
  static NeoResponse handleIfDeferredVat(NeoContext context) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !ACTION_NAME.equals(context.getFieldName())) {
      return null;
    }
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          ACTION_NAME + " only supports GET");
    }
    String recordId = context.getRecordId();
    // Read with the caller's own access, so an invoice the role cannot see is a 404.
    Invoice invoice = StringUtils.isBlank(recordId) ? null
        : OBDal.getInstance().get(Invoice.class, recordId);
    if (invoice == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Purchase invoice not found: " + recordId);
    }
    try {
      return NeoResponse.ok(new JSONObject().put(KEY_DEFERRED_VAT, isDeferredVatImport(invoice)));
    } catch (JSONException e) {
      log.error("Could not serialize the deferred VAT answer of invoice {}", recordId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not answer " + ACTION_NAME);
    }
  }

  /** F5/LC, sent to SII by an IVA organization, and deferred VAT for the SII module. */
  static boolean isDeferredVatImport(Invoice invoice) {
    OBContext.setAdminMode(true);
    try {
      AEATSIIConfig config = applicableConfig(invoice);
      return config != null && isImportKey(invoice.getAeatsiiClaveTipoFc()) && isIvaOrg(config)
          && isDeferredVat(invoice);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The SII configuration the send would use, or {@code null} when the invoice is not sent to SII:
   * a sales invoice, no SII invoice type, an organization that is not in SII (or has no adoption
   * date), or an invoice older than the adoption year ({@link SIIUtils#notSendingByDateCause}).
   */
  static AEATSIIConfig applicableConfig(Invoice invoice) {
    if (invoice == null || Boolean.TRUE.equals(invoice.isSalesTransaction())
        || StringUtils.isBlank(invoice.getAeatsiiClaveTipoFc())) {
      return null;
    }
    AEATSIIConfig config = SIIUtils.getSiiConfigFromOrg(invoice.getOrganization());
    if (config == null || !Boolean.TRUE.equals(config.isAcogidaAlSII())
        || config.getFechaAcogidaSII() == null) {
      return null;
    }
    try {
      if (invoice.getAccountingDate() != null && SIIUtils.notSendingByDateCause(config, invoice)) {
        return null;
      }
    } catch (RuntimeException e) {
      log.debug("Could not apply the SII adoption-date rule to invoice {}: {}", invoice.getId(),
          e.getMessage());
    }
    return config;
  }

  static boolean isImportKey(String key) {
    return KEY_F5.equals(key) || KEY_LC.equals(key);
  }

  static boolean isIvaOrg(AEATSIIConfig config) {
    return config != null && SIIUtils.getIvaTaxType().equals(config.getTaxtype());
  }

  private static boolean isDeferredVat(Invoice invoice) {
    // The SII module reads the partner address country; without it the invoice is not deferred.
    if (invoice.getPartnerAddress() == null
        || invoice.getPartnerAddress().getLocationAddress() == null) {
      return false;
    }
    try {
      return SIIUtils.isDeferredVATInvoice(invoice);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
