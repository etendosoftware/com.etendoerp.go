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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.ReversedInvoice;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Abstract base class for AP and AR invoice header handlers.
 *
 * <p>Provides shared logic for document-type immutability enforcement, origin-invoice
 * relationship management, and GET-response enrichment. Subclasses implement
 * {@link #resolveSubtype(String)} and {@link #getInvoiceSubtypeKey()} to supply
 * document-category rules and the virtual-field name specific to each invoice direction.
 *
 * <p>This class does NOT implement {@link NeoHandler} — it is a pure helper/base.
 * Concrete subclasses are responsible for wiring the NeoHandler interface.
 */
public abstract class AbstractInvoiceHeaderHandler {

  private static final Logger log = LogManager.getLogger(AbstractInvoiceHeaderHandler.class);

  protected static final String SUBTYPE_FAC = "FAC";
  protected static final String SUBTYPE_NC  = "NC";
  protected static final String SUBTYPE_DEV = "DEV";

  protected static final String FIELD_ORIGIN_INVOICE       = "originInvoice";
  protected static final String FIELD_TRANSACTION_DOCUMENT = "transactionDocument";

  // ---------------------------------------------------------------------------
  // Abstract contract
  // ---------------------------------------------------------------------------

  /**
   * Returns the invoice subtype ("FAC", "NC", or "DEV") for the given document-type ID.
   * Handles null/blank IDs and DB lookup errors; delegates category-to-subtype mapping
   * to {@link #classifyDocType(DocumentType)}.
   *
   * @param docTypeId the ID of the selected document type, may be null/blank
   * @return one of {@code SUBTYPE_FAC}, {@code SUBTYPE_NC}, {@code SUBTYPE_DEV}
   */
  protected final String resolveSubtype(String docTypeId) {
    if (StringUtils.isBlank(docTypeId)) {
      return SUBTYPE_FAC;
    }
    try {
      DocumentType dt = OBDal.getInstance().get(DocumentType.class, docTypeId);
      if (dt == null) {
        return SUBTYPE_FAC;
      }
      return classifyDocType(dt);
    } catch (Exception e) {
      return SUBTYPE_FAC;
    }
  }

  /**
   * Maps the resolved {@link DocumentType} to a subtype constant.
   * AR invoices check ARC/ARI_RM; AP invoices check APC/API+isReturn.
   *
   * @param dt the loaded document type (never null)
   * @return one of {@code SUBTYPE_FAC}, {@code SUBTYPE_NC}, {@code SUBTYPE_DEV}
   */
  protected abstract String classifyDocType(DocumentType dt);

  /**
   * Returns the virtual-field key used to store the subtype in the response JSON.
   * AP invoices return {@code "apInvoiceSubtype"}; AR invoices return {@code "arInvoiceSubtype"}.
   *
   * @return virtual-field name for the subtype
   */
  protected abstract String getInvoiceSubtypeKey();

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  /**
   * Rejects PUT requests that attempt to change the document type after the invoice has been saved
   * (i.e., after a documentNo has been assigned).
   *
   * @param context
   *     the current request context
   * @return an error {@link NeoResponse} if the lock is violated, otherwise {@code null}
   */
  protected NeoResponse validateDocTypeLock(NeoContext context) {
    if (!"PUT".equals(context.getHttpMethod()) || context.getRecordId() == null) {
      return null;
    }
    try {
      JSONObject body = context.getRequestBody();
      if (body == null || !body.has(FIELD_TRANSACTION_DOCUMENT)) {
        return null;
      }
      String newDocTypeId = body.optString(FIELD_TRANSACTION_DOCUMENT, null);
      if (StringUtils.isBlank(newDocTypeId)) {
        return null;
      }
      Invoice invoice = OBDal.getInstance().get(Invoice.class, context.getRecordId());
      if (invoice == null || StringUtils.isBlank(invoice.getDocumentNo())) {
        return null; // record not yet saved, allow
      }
      DocumentType current = invoice.getTransactionDocument();
      if (current != null && !current.getId().equals(newDocTypeId)) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Document type cannot be changed after the invoice has been saved.");
      }
    } catch (Exception e) {
      log.warn("Could not validate document type lock for invoice {}: {}",
          context.getRecordId(), e.getMessage());
    }
    return null;
  }

  /**
   * Requires {@code originInvoice} in the request body when the selected document type resolves
   * to NC (Credit Note) or DEV (Return Invoice).
   *
   * @param context
   *     the current request context
   * @return an error {@link NeoResponse} if origin invoice is missing, otherwise {@code null}
   */
  protected NeoResponse validateOriginInvoiceRequired(NeoContext context) {
    if (!("POST".equals(context.getHttpMethod()) || "PUT".equals(context.getHttpMethod()))) {
      return null;
    }
    try {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return null;
      }
      String docTypeId = body.optString(FIELD_TRANSACTION_DOCUMENT, null);
      if (StringUtils.isBlank(docTypeId)) {
        return null;
      }
      String subtype = resolveSubtype(docTypeId);
      if (SUBTYPE_FAC.equals(subtype)) {
        return null; // Factura: origin invoice not required
      }
      String originId = body.optString(FIELD_ORIGIN_INVOICE, null);
      if (StringUtils.isBlank(originId)) {
        String label = SUBTYPE_NC.equals(subtype) ? "Credit Note" : "Return Invoice";
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            label + " requires an origin invoice.");
      }
    } catch (Exception e) {
      log.warn("Could not validate origin invoice for invoice {}: {}",
          context.getRecordId(), e.getMessage());
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Origin invoice persistence
  // ---------------------------------------------------------------------------

  /**
   * Persists the origin-invoice relationship to {@code C_Invoice_Reverse} after a POST or PUT.
   * Deletes any existing link for this invoice before creating the new one (or leaves it deleted
   * if {@code originInvoice} is absent/blank in the request body).
   *
   * @param context
   *     the current request context
   */
  protected void persistOriginInvoice(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return;
      }
      String originInvoiceId = body.optString(FIELD_ORIGIN_INVOICE, null);

      String invoiceId = resolveInvoiceIdFromContext(context);
      if (StringUtils.isBlank(invoiceId)) {
        return;
      }

      OBContext.setAdminMode(true);
      try {
        deleteExistingReverseLinks(invoiceId);
        if (StringUtils.isNotBlank(originInvoiceId)) {
          createReverseLink(invoiceId, originInvoiceId);
        }
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not persist origin invoice: {}", e.getMessage());
    }
  }

  private String resolveInvoiceIdFromContext(NeoContext context) {
    if (context.getRecordId() != null) {
      return context.getRecordId();
    }
    // POST: extract newly created record ID from the CRUD response
    try {
      NeoResponse prev = context.getPreviousResult();
      if (prev == null) {
        return null;
      }
      JSONObject responseBody = prev.getBody();
      if (responseBody == null) {
        return null;
      }
      JSONObject response = responseBody.optJSONObject("response");
      if (response == null) {
        return null;
      }
      JSONObject data = response.optJSONObject("data");
      return data != null ? data.optString("id", null) : null;
    } catch (Exception e) {
      log.debug("Could not extract invoice ID from POST response: {}", e.getMessage());
      return null;
    }
  }

  private void deleteExistingReverseLinks(String invoiceId) {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    if (invoice == null) {
      return;
    }
    List<ReversedInvoice> existing = OBDal.getInstance()
        .createCriteria(ReversedInvoice.class)
        .add(Restrictions.eq(ReversedInvoice.PROPERTY_INVOICE, invoice))
        .list();
    for (ReversedInvoice ri : existing) {
      OBDal.getInstance().remove(ri);
    }
  }

  private void createReverseLink(String invoiceId, String originInvoiceId) {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    Invoice origin = OBDal.getInstance().get(Invoice.class, originInvoiceId);
    if (invoice == null || origin == null) {
      log.warn("Cannot create reverse link: invoice={} origin={}", invoiceId, originInvoiceId);
      return;
    }
    ReversedInvoice link = OBProvider.getInstance().get(ReversedInvoice.class);
    link.setClient(invoice.getClient());
    link.setOrganization(invoice.getOrganization());
    link.setInvoice(invoice);
    link.setReversedInvoice(origin);
    OBDal.getInstance().save(link);
  }

  // ---------------------------------------------------------------------------
  // GET enrichment (virtual fields)
  // ---------------------------------------------------------------------------

  /**
   * Injects {@code originInvoice} and {@code originInvoice$_identifier} into the record by
   * querying the {@code C_Invoice_Reverse} table for a link where this invoice is the reversed one.
   *
   * @param rec
   *     the invoice record JSON object; modified in-place
   * @param invoiceId
   *     the primary key of the invoice being enriched
   * @throws Exception
   *     if a JSON or DB operation fails
   */
  protected void enrichOriginInvoice(JSONObject rec, String invoiceId) throws Exception {
    String sql =
        "SELECT inv.c_invoice_id, inv.documentno "
        + "FROM c_invoice_reverse r "
        + "JOIN c_invoice inv ON inv.c_invoice_id = r.reversed_c_invoice_id "
        + "WHERE r.c_invoice_id = ? AND r.isactive = 'Y'";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          rec.put(FIELD_ORIGIN_INVOICE, rs.getString(1));
          rec.put("originInvoice$_identifier", rs.getString(2));
        } else {
          rec.put(FIELD_ORIGIN_INVOICE, JSONObject.NULL);
          rec.put("originInvoice$_identifier", JSONObject.NULL);
        }
      }
    } catch (Exception e) {
      log.warn("Could not enrich origin invoice for {}: {}", invoiceId, e.getMessage());
    }
  }

  /**
   * Injects the invoice subtype virtual field ({@link #getInvoiceSubtypeKey()}) into the record
   * by resolving the current {@code transactionDocument} value via {@link #resolveSubtype(String)}.
   *
   * @param rec
   *     the invoice record JSON object; modified in-place
   * @param key
   *     the virtual-field key (e.g. {@code "apInvoiceSubtype"} or {@code "arInvoiceSubtype"})
   * @throws Exception
   *     if a JSON operation fails
   */
  protected void enrichInvoiceSubtype(JSONObject rec, String key) throws Exception {
    String docTypeId = rec.optString(FIELD_TRANSACTION_DOCUMENT, null);
    rec.put(key, resolveSubtype(docTypeId));
  }

  /**
   * Sets {@code docTypeLocked} to {@code true} in the record, indicating that the document type
   * is immutable once the invoice has been saved.
   *
   * @param rec
   *     the invoice record JSON object; modified in-place
   * @throws Exception
   *     if a JSON operation fails
   */
  protected void enrichDocTypeLocked(JSONObject rec) throws Exception {
    rec.put("docTypeLocked", true);
  }

  // ---------------------------------------------------------------------------
  // Completion (documentAction=CO) — routes through the ProcessInvoiceHook chain
  // ---------------------------------------------------------------------------

  /** AD_Process_ID for {@code C_Invoice.DocAction}, used internally by {@link ProcessInvoiceUtil}. */
  private static final String COMPLETE_PROCESS_ID_INVOICE = "111";

  /**
   * Runs the real core invoice-completion process ({@link ProcessInvoiceUtil#process}) when the
   * request is a completion action (documentAction=CO), short-circuiting NEO's default dispatch.
   *
   * <p>For {@code C_Invoice.DocAction} (AD_Process 111, a raw DB procedure with no
   * {@code JavaClassName}), NEO's generic dispatch runs {@code C_Invoice_Post0} directly via
   * {@code CallProcess} and never touches {@link ProcessInvoiceUtil} or the
   * {@code ProcessInvoiceHook} CDI extension point — so hooks such as the Verifactu (and TBAI)
   * billing-registration hooks never fire when an invoice is completed through NEO, even though
   * they fire correctly from the classic UI. This method restores that behavior for NEO.
   *
   * <p><b>Must be obtained through Weld.</b> {@link ProcessInvoiceUtil} is a plain class with an
   * {@code @Inject @Any Instance<ProcessInvoiceHook> hooks} field; that field is only populated
   * when the instance itself is CDI-managed. Calling {@code new ProcessInvoiceUtil()} would leave
   * {@code hooks} empty and silently skip every hook — reproducing the exact bug this method
   * fixes, just moved one layer down. {@link WeldUtils#getInstanceFromStaticBeanManager} returns
   * a fully Weld-managed reference, so {@code hooks} is populated correctly.
   *
   * <p>Call this AFTER {@link #validateLineQtyBeforeComplete(NeoContext)} in {@code handle()} so
   * pre-completion validation can still block the request before the real process runs.
   *
   * @param context the current NeoContext
   * @return a {@link NeoResponse} translating the completion result, or {@code null} if this is
   *     not a completion request (caller should continue to the default dispatch)
   */
  protected static NeoResponse completeInvoiceIfNeeded(NeoContext context) {
    if (!isInvoiceCompleteAction(context)) {
      return null;
    }
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing invoice record id for completion");
    }
    try {
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(context.getObContext());
      ConnectionProvider conn = new DalConnectionProvider(false);
      ProcessInvoiceUtil processInvoiceUtil =
          WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class);
      // Void-date/supplier-reference params are only consulted for the void ("RC") action;
      // ProcessInvoiceUtil calls .isEmpty() on the date strings unconditionally, so they must be
      // non-null. Empty strings are the correct null-safe default for a normal "CO" completion.
      OBError result = processInvoiceUtil.process(invoiceId, "CO", "", "", "", vars, conn);
      Process process = OBDal.getInstance().get(Process.class, COMPLETE_PROCESS_ID_INVOICE);
      return NeoProcessService.translateClassicResult(result, process);
    } catch (Exception e) {
      log.error("[INVOICE-COMPLETE] Completion failed for invoice {}: {}",
          invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Invoice completion failed: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Pre-completion invoice line quantity validation
  // ---------------------------------------------------------------------------

  private static final String FIELD_DOCUMENT_ACTION_INV = "documentAction";

  /**
   * Blocks invoice completion when any invoice line would over-invoice a shipment or receipt line.
   * For each invoice line with {@code m_inoutline_id}, computes the pending (uninvoiced) quantity
   * on the referenced shipment/receipt line (excluding other drafts) and rejects if the draft
   * quantity exceeds what is still available.
   *
   * <p>Call at the top of {@code handle()} in both AR and AP invoice header subclasses, after the
   * exchange-rate check.
   *
   * @param context the current NeoContext
   * @return a NeoResponse error to block completion, or {@code null} to proceed
   */
  @SuppressWarnings("java:S2077")
  static NeoResponse validateLineQtyBeforeComplete(NeoContext context) {
    if (!isInvoiceCompleteAction(context)) {
      return null;
    }
    String invoiceId = context.getRecordId();
    if (invoiceId == null || invoiceId.isEmpty()) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      Map<String, String> docNoByInout = new LinkedHashMap<>();
      Map<String, Map<String, BigDecimal>> linesByInout = new LinkedHashMap<>();

      String sql =
          "SELECT il.m_inoutline_id, ABS(il.qtyinvoiced), io.m_inout_id, io.documentno "
          + "FROM c_invoiceline il "
          + "JOIN m_inoutline iol ON iol.m_inoutline_id = il.m_inoutline_id "
          + "JOIN m_inout io ON io.m_inout_id = iol.m_inout_id "
          + "WHERE il.c_invoice_id = ? AND il.isactive = 'Y' AND il.m_inoutline_id IS NOT NULL";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String lineId = rs.getString(1);
            BigDecimal qty = rs.getBigDecimal(2);
            String inoutId = rs.getString(3);
            String docNo = rs.getString(4);
            docNoByInout.put(inoutId, docNo);
            linesByInout.computeIfAbsent(inoutId, k -> new LinkedHashMap<>()).put(lineId, qty);
          }
        }
      }
      if (linesByInout.isEmpty()) {
        return null;
      }
      for (Map.Entry<String, Map<String, BigDecimal>> inoutEntry : linesByInout.entrySet()) {
        NeoResponse error = checkInoutEntryForOverInvoicing(
            inoutEntry.getKey(), inoutEntry.getValue(), docNoByInout, invoiceId);
        if (error != null) {
          return error;
        }
      }
      return null;
    } catch (Exception e) {
      log.error("Error validating invoice lines before complete for invoice {}", invoiceId, e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static NeoResponse checkInoutEntryForOverInvoicing(String inoutId,
      Map<String, BigDecimal> draftLines, Map<String, String> docNoByInout,
      String invoiceId) throws Exception {
    Map<String, BigDecimal> pendingMap = NeoInvoiceSupport.computePendingQtyPerLine(inoutId, false);
    for (Map.Entry<String, BigDecimal> lineEntry : draftLines.entrySet()) {
      String lineId = lineEntry.getKey();
      BigDecimal draftQty = lineEntry.getValue();
      if (draftQty == null || draftQty.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal pendingQty = pendingMap.getOrDefault(lineId, BigDecimal.ZERO);
      if (pendingQty.compareTo(draftQty) < 0) {
        String docNo = docNoByInout.get(inoutId);
        String template = OBMessageUtils.messageBD("ETGO_InvoiceLineAlreadyInvoiced");
        String msg = template
            .replace("@docNo@", docNo)
            .replace("@invoiced@", draftQty.toPlainString())
            .replace("@pending@", pendingQty.toPlainString());
        log.warn("Blocking invoice completion id={}: {}", invoiceId, msg);
        JSONObject body = new JSONObject();
        body.put("status", "error");
        body.put("message", msg);
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, body);
      }
    }
    return null;
  }

  private static boolean isInvoiceCompleteAction(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      String method = context.getHttpMethod();
      if (!"PATCH".equals(method) && !"PUT".equals(method)) {
        return false;
      }
      JSONObject body = context.getRequestBody();
      return body != null && "CO".equals(body.optString(FIELD_DOCUMENT_ACTION_INV, ""));
    }
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && FIELD_DOCUMENT_ACTION_INV.equals(context.getFieldName())) {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return false;
      }
      JSONObject fieldValues = body.optJSONObject("fieldValues");
      String docAction = fieldValues != null
          ? fieldValues.optString(FIELD_DOCUMENT_ACTION_INV, "")
          : body.optString("docAction", body.optString(FIELD_DOCUMENT_ACTION_INV, ""));
      return "CO".equals(docAction);
    }
    return false;
  }
}
