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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Replicates, for the completion step of the NEO/MCP write path, the SII "rectificative invoice"
 * field rule that today only ever runs on the classic UI (ETP-5745 CP-13).
 *
 * <p><b>Why.</b> The rule "a rectificative invoice (SII {@code Clave Tipo Factura = R}) must carry
 * both {@code aeatsiiMotivoRectif} ('Motivo de rectificación') and {@code aeatsiiTipoRectif}
 * ('Tipo de rectificación')" is enforced three different ways today, and NONE of them is reachable
 * from a plain CRUD write:
 * <ul>
 *   <li>{@code tools/app-shell/src/windows/custom/shared/useSifFieldPatcher.js} auto-sets
 *       {@code aeatsiiClaveTipo='R'} and {@code aeatsiiTipoRectif='I'} via React effects that only
 *       run inside the browser SPA;</li>
 *   <li>{@code FacturasEventHandler#checkReversedInvoice} (org.openbravo.module.sii) only fires when
 *       a classic {@code windowId} request parameter equals {@code "167"}/{@code "183"} — NEO
 *       Headless requests never carry it;</li>
 *   <li>the DB CHECK constraint {@code EM_AEATSII_C_INV_RECT_CHK} on {@code C_INVOICE} is the only
 *       enforcement every channel shares, but it throws a raw, unfriendly constraint violation
 *       instead of a validation message, and does nothing to SET the right value in the first
 *       place.</li>
 * </ul>
 *
 * <p>So an MCP flow that creates a header with a rectificative document type and never explicitly
 * sends {@code aeatsiiClaveTipo}/{@code aeatsiiTipoRectif} slips through: the generic AD_Column
 * default ({@code "F1"}) wins at create time, nothing downstream corrects it, and the invoice
 * completes as an ordinary "Factura" instead of a "Factura rectificativa" — silently wrong, not
 * merely unvalidated.
 *
 * <p><b>What this guard does</b>, right before completion (mirroring where
 * {@link RectificationTotalGuard} sits): for a SALES invoice (purchase-side corrective support is
 * not implemented yet — see the same ETP-5745 CP-10 precedent in {@code useSifFieldPatcher.js})
 * whose document type is structurally rectificative ({@link RectificativeSupport#isRectificative}
 * — a document-type flag, never a spec/table name), it (1) corrects {@code aeatsiiClaveTipo} to
 * {@code "R"} when it still holds the generic default, (2) defaults {@code aeatsiiTipoRectif} to
 * {@code "I"} ("Por diferencias") when blank — exactly what the UI effect already does — and (3)
 * refuses the completion with a clear, field-naming 422 when {@code aeatsiiMotivoRectif} is still
 * blank, the one value that is a genuine accounting decision no default may guess (never fabricate
 * fiscal/compliance data on the caller's behalf).
 *
 * <p><b>Fails OPEN on a read/write error</b>, mirroring {@link RectificationTotalGuard}: this
 * guard's own failure to resolve or persist the SII fields must never block a legitimate
 * completion it cannot evaluate.
 *
 * <p><b>Placement.</b> Invoice-specific, not shared NEO code: wired from
 * {@link AbstractInvoiceHeaderHandler#completeInvoiceIfNeeded}, alongside
 * {@link RectificationTotalGuard}. Applies identically to any invoice spec built on
 * {@code C_Invoice} — nothing here branches on a spec or table name, only on the document type's
 * own rectificative flag and the invoice's own {@code IsSOTrx}.
 */
final class RectificationSiiFieldsGuard {

  private static final Logger log = LogManager.getLogger(RectificationSiiFieldsGuard.class);

  private static final String CLAVE_TIPO_RECTIFICATIVA = "R";
  private static final String TIPO_RECTIF_DEFAULT = "I";

  /** Stable identity sent in messageKeys — a plain string, not an AD_MESSAGE (ETP-5692 precedent). */
  static final String MSG_MOTIVO_RECTIF_REQUIRED = "ETGO_InvoiceMotivoRectifRequired";

  private RectificationSiiFieldsGuard() {
  }

  /**
   * Applies the rectificative SII field rule when the request is a completion action; otherwise a
   * no-op. See the class javadoc for the full rule.
   *
   * @param context the current request
   * @return a 422 naming the missing field, or {@code null} to let the completion proceed
   */
  static NeoResponse checkAndApply(NeoContext context) {
    if (!InvoiceCalloutHelper.isInvoiceCompleteAction(context)) {
      return null;
    }
    String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    try {
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      if (invoice == null || !Boolean.TRUE.equals(invoice.isSalesTransaction())) {
        return null;
      }
      DocumentType docType = invoice.getTransactionDocument();
      if (!RectificativeSupport.isRectificative(docType)) {
        return null;
      }
      // Checked BEFORE mutating anything: a request that will be refused must leave the entity
      // exactly as it was, so no partial correction can leak into the DB through some other,
      // unrelated flush later in the same session/request.
      if (StringUtils.isBlank(invoice.getAeatsiiMotivoRectif())) {
        return reject();
      }
      boolean changed = correctClaveTipo(invoice) | defaultTipoRectif(invoice);
      if (changed) {
        OBDal.getInstance().save(invoice);
        OBDal.getInstance().flush();
      }
      return null;
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not evaluate the rectification SII fields guard for invoice {}: {}",
          invoiceId, e.getMessage());
      return null;
    }
  }

  /** @return {@code true} when the value was changed */
  private static boolean correctClaveTipo(Invoice invoice) {
    if (CLAVE_TIPO_RECTIFICATIVA.equals(invoice.getAeatsiiClaveTipo())) {
      return false;
    }
    invoice.setAeatsiiClaveTipo(CLAVE_TIPO_RECTIFICATIVA);
    return true;
  }

  /** @return {@code true} when the value was changed */
  private static boolean defaultTipoRectif(Invoice invoice) {
    if (StringUtils.isNotBlank(invoice.getAeatsiiTipoRectif())) {
      return false;
    }
    invoice.setAeatsiiTipoRectif(TIPO_RECTIF_DEFAULT);
    return true;
  }

  /**
   * The 422 refusal, in the same flat {@code success:false}/{@code message}/{@code messageKeys}/
   * shape {@link RectificationTotalGuard} answers with — explicitly naming the missing field,
   * which the raw {@code EM_AEATSII_C_INV_RECT_CHK} constraint violation never did (ETP-5745
   * CP-11).
   */
  private static NeoResponse reject() throws JSONException {
    String message = "This rectificative invoice cannot be completed: 'Motivo de rectificación' "
        + "is required when the SII invoice type is a corrective ('R').";

    JSONObject body = new JSONObject();
    body.put("success", false);
    body.put("message", message);
    body.put("messageKeys", new JSONArray(List.of(MSG_MOTIVO_RECTIF_REQUIRED)));
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, body);
  }
}
