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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;

/**
 * ETP-6284 — guards on the tax lines ({@code C_InvoiceTax}) of a purchase invoice, the rows GO uses
 * as manual import-tax lines of an import with DUA. Bound by annotation, so the same rules hold on
 * every channel: REST single, {@code /batch} and MCP (all dispatch through
 * {@code NeoExtensionDispatcher}).
 *
 * <ul>
 *   <li><b>Create</b> always makes a manual row: {@code recalculate} is forced to {@code false}
 *       whatever the caller sent.</li>
 *   <li><b>Update / delete</b> of an automatic row ({@code Recalculate = 'Y'}, derived by the core
 *       from the invoice lines) is refused: the core would overwrite or recreate it anyway.</li>
 *   <li><b>Any write</b> is refused when the invoice is not a draft, is processed, or was already
 *       sent to SII ({@code aeatsiiIssent}) — the backend form of the SPA's
 *       {@code siiFieldReadOnly} plus its draft check ({@code useSifFieldPatcher.js}).</li>
 *   <li><b>Múltiple DUA</b> = Y with invoice type F5 or LC: the row must carry DUA and Fecha DUA.
 *       Classic only fails at send time (its {@code InvoiceTaxEventHandler} check never throws);
 *       GO refuses the save instead.</li>
 * </ul>
 *
 * <p>Nothing here names another entity; it is the customization of
 * {@code purchase-invoice/tax} only. See {@code neo-headless.md} §5.3.a for the binding.
 */
@NeoExtension(spec = "purchase-invoice", entity = "tax")
public class PurchaseInvoiceTaxExtension implements NeoHandler {

  static final String FIELD_RECALCULATE = InvoiceTax.PROPERTY_RECALCULATE;
  static final String FIELD_DUA = InvoiceTax.PROPERTY_AEATSIIDUA;
  static final String FIELD_DUA_DATE = InvoiceTax.PROPERTY_AEATSIIFECHADUA;
  static final String FIELD_INVOICE = InvoiceTax.PROPERTY_INVOICE;
  static final String FIELD_PARENT_ID = "parentId";

  static final String MSG_LOCKED = "ETGO_PurchaseInvoiceTaxLocked";
  static final String MSG_AUTOMATIC = "ETGO_PurchaseInvoiceTaxAutomatic";
  static final String MSG_DUA_REQUIRED = "ETGO_PurchaseInvoiceTaxDuaRequired";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_DELETE = "DELETE";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    String method = context.getHttpMethod();
    boolean create = METHOD_POST.equals(method);
    boolean delete = METHOD_DELETE.equals(method);
    if (!create && !delete && !isUpdate(method)) {
      return null;
    }
    // ETP-6284: writes are part of the imports-with-DUA feature (reads are not gated). Evaluated
    // at request entry, while the request runs as the account owner.
    if (!SiiImportDuaFlag.isEnabled()) {
      return SiiImportDuaFlag.featureDisabled();
    }
    JSONObject body = dataNode(context.getRequestBody());
    // Read with the caller's own access (before admin mode), so a row or invoice the role cannot
    // see is left to the default CRUD path (404) instead of answering 409 and revealing it.
    InvoiceTax existing = create ? null : readTax(context.getRecordId());
    Invoice invoice = targetInvoice(create, body, existing);
    if (invoice == null) {
      // Unknown or invisible parent or row: the default CRUD path answers it (404 / validation).
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      return checkWrite(invoice, existing, body, create, delete);
    } catch (JSONException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Invalid tax line body: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static boolean isUpdate(String method) {
    return "PUT".equals(method) || "PATCH".equals(method);
  }

  /** The invoice the write touches: the create's parent, else the existing row's invoice. */
  private static Invoice targetInvoice(boolean create, JSONObject body, InvoiceTax existing) {
    if (create) {
      return readInvoice(parentInvoiceId(body));
    }
    return existing == null ? null : existing.getInvoice();
  }

  /**
   * The guards of a write on a visible invoice: locked invoice, automatic row, then (create and
   * update only) the forced manual flag and the per-line DUA rule. {@code null} lets CRUD run.
   */
  private static NeoResponse checkWrite(Invoice invoice, InvoiceTax existing, JSONObject body,
      boolean create, boolean delete) throws JSONException {
    if (isLocked(invoice)) {
      return NeoMessageText.error(HttpServletResponse.SC_CONFLICT, MSG_LOCKED);
    }
    if (existing != null && Boolean.TRUE.equals(existing.isRecalculate())) {
      return NeoMessageText.error(HttpServletResponse.SC_CONFLICT, MSG_AUTOMATIC);
    }
    if (delete) {
      return null;
    }
    if (create && body != null) {
      body.put(FIELD_RECALCULATE, false);
    }
    if (requiresLineDua(invoice) && !hasDuaAndDate(body, existing)) {
      return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_DUA_REQUIRED);
    }
    return null;
  }

  /** A write body may come wrapped in {@code data} (as {@code NeoFieldFilter} also accepts). */
  static JSONObject dataNode(JSONObject body) {
    if (body == null) {
      return null;
    }
    JSONObject data = body.optJSONObject("data");
    return data != null ? data : body;
  }

  /**
   * The parent invoice of a create: {@code parentId} (what the SPA sends) or the {@code invoice}
   * link itself (what an agent may send), as an id or as {@code {id}}.
   */
  static String parentInvoiceId(JSONObject body) {
    if (body == null) {
      return null;
    }
    String parentId = StringUtils.trimToNull(body.optString(FIELD_PARENT_ID, null));
    if (parentId != null) {
      return parentId;
    }
    JSONObject asObject = body.optJSONObject(FIELD_INVOICE);
    if (asObject != null) {
      return StringUtils.trimToNull(asObject.optString("id", null));
    }
    return StringUtils.trimToNull(body.optString(FIELD_INVOICE, null));
  }

  /** Not a draft, processed, or already sent to SII: its tax lines are frozen. */
  static boolean isLocked(Invoice invoice) {
    return !"DR".equals(invoice.getDocumentStatus())
        || Boolean.TRUE.equals(invoice.isProcessed())
        || Boolean.TRUE.equals(invoice.isAeatsiiIssent());
  }

  /** Múltiple DUA with an import key (F5 / LC): each tax line carries its own DUA. */
  static boolean requiresLineDua(Invoice invoice) {
    return Boolean.TRUE.equals(invoice.isAeatsiiMultiDua())
        && PurchaseInvoiceSiiDeferredVat.isImportKey(invoice.getAeatsiiClaveTipoFc());
  }

  /**
   * Whether the row will hold both values after the write: the body's value when it names the
   * field (a {@code null} or blank clears it), else the stored one.
   */
  static boolean hasDuaAndDate(JSONObject body, InvoiceTax existing) {
    boolean dua = body != null && body.has(FIELD_DUA)
        ? isPresent(body.opt(FIELD_DUA))
        : existing != null && StringUtils.isNotBlank(existing.getAeatsiiDua());
    boolean date = body != null && body.has(FIELD_DUA_DATE)
        ? isPresent(body.opt(FIELD_DUA_DATE))
        : existing != null && existing.getAeatsiiFechaDua() != null;
    return dua && date;
  }

  private static boolean isPresent(Object value) {
    return value != null && !JSONObject.NULL.equals(value)
        && StringUtils.isNotBlank(String.valueOf(value));
  }

  private static InvoiceTax readTax(String id) {
    return StringUtils.isBlank(id) ? null : OBDal.getInstance().get(InvoiceTax.class, id);
  }

  private static Invoice readInvoice(String id) {
    return id == null ? null : OBDal.getInstance().get(Invoice.class, id);
  }
}
