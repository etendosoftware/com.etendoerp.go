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
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
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
  /**
   * Unified rectificative subtype (ETP-4737 "Factura Rectificativa") — collapses the former
   * {@code NC} (Credit Note) and {@code DEV} (Return Invoice) subtypes into one, since both sides
   * (AR and AP) now use a single document type for manual corrections AND auto-generation from a
   * Goods Return.
   */
  protected static final String SUBTYPE_RECTIFICATIVA = "RECTIFICATIVA";

  protected static final String FIELD_ORIGIN_INVOICE       = "originInvoice";
  // ETP-4919: plural counterpart of FIELD_ORIGIN_INVOICE — a Factura Rectificativa can be
  // linked back to MORE THAN ONE source invoice (importing from source invoice A, then later
  // from source invoice B, must keep BOTH links). FIELD_ORIGIN_INVOICE is kept as a
  // backward-compatible single-id alias (accepted on input, folded into the id set; emitted on
  // output as the first linked origin) — see captureOriginInvoice/persistOriginInvoice/
  // enrichOriginInvoice below.
  protected static final String FIELD_ORIGIN_INVOICES      = "originInvoices";
  protected static final String FIELD_TRANSACTION_DOCUMENT = "transactionDocument";
  private static final String FIELD_CURRENCY = "currency";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_PROCESSED = "processed";
  private static final String FIELD_TOTAL_DISCOUNT_PCT = "etgoTotalDiscount";
  protected static final String FIELD_GRAND_TOTAL_AMOUNT = "grandTotalAmount";
  protected static final String FIELD_OUTSTANDING_AMOUNT = "outstandingAmount";

  // ETP-4737: captured by captureOriginInvoice() (called from each subclass's handle(), the
  // pre-hook) and consumed by persistOriginInvoice() (called from afterHandle(), the post-hook)
  // — see the capture-site comment on captureOriginInvoice for why this can't just be re-read
  // from context.getRequestBody() in afterHandle(). Concrete subclasses are @Named-only CDI
  // beans, defaulting to @Dependent scope, and NeoServletSupport#handleWithHooks calls handle()
  // then afterHandle() on the SAME handler instance for a given request, so a plain instance
  // field on this shared base class safely carries state between them.
  private List<String> pendingOriginInvoiceIds;
  private boolean originInvoiceCaptured;

  // ---------------------------------------------------------------------------
  // Abstract contract
  // ---------------------------------------------------------------------------

  /**
   * Returns the invoice subtype ("FAC" or "RECTIFICATIVA") for the given document-type ID.
   * Handles null/blank IDs and DB lookup errors; delegates category-to-subtype mapping
   * to {@link #classifyDocType(DocumentType)}.
   *
   * @param docTypeId the ID of the selected document type, may be null/blank
   * @return one of {@code SUBTYPE_FAC}, {@code SUBTYPE_RECTIFICATIVA}
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
   * Maps the resolved {@link DocumentType} to a subtype constant. Driven primarily by
   * {@code EM_Etsg_Isrectificative} (ETP-4737 unified "Factura Rectificativa" type — see
   * {@link RectificativeSupport#isRectificative(DocumentType)}), with a legacy category-based
   * fallback (AR: ARC/ARI_RM; AP: APC/API+isReturn) so invoices already using the old Credit
   * Note / Return Invoice document types keep classifying correctly.
   *
   * @param dt the loaded document type (never null)
   * @return one of {@code SUBTYPE_FAC}, {@code SUBTYPE_RECTIFICATIVA}
   */
  protected abstract String classifyDocType(DocumentType dt);

  /**
   * Returns the virtual-field key used to store the subtype in the response JSON.
   * AP invoices return {@code "apInvoiceSubtype"}; AR invoices return {@code "arInvoiceSubtype"}.
   *
   * @return virtual-field name for the subtype
   */
  protected abstract String getInvoiceSubtypeKey();

  /**
   * Supplies the concrete handler's own {@code @Inject}-ed {@link TotalDiscountService} so
   * {@link #applyTotalDiscountToRecord} can call {@code hasDiscountLine} without this abstract
   * class holding a CDI-injected field itself — this class is a pure helper/base (see class
   * Javadoc) and never injects its own collaborators.
   *
   * @return the subclass's injected {@link TotalDiscountService}
   */
  protected abstract TotalDiscountService getTotalDiscountService();

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
   * to {@code RECTIFICATIVA} (ETP-4737 unified rectificative invoice — formerly the separate
   * {@code NC}/Credit Note and {@code DEV}/Return Invoice subtypes).
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
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Rectificative Invoice requires an origin invoice.");
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
   * Captures and strips {@code originInvoice}/{@code originInvoices} from the raw request body
   * BEFORE the generic field filter runs, so {@link #persistOriginInvoice} can still use them
   * later in {@code afterHandle()}. Must be called from each subclass's {@code handle()} (the
   * pre-hook), e.g. alongside the existing {@code NeoHandlerUtils.mirrorAccountingDate(...)}
   * call.
   *
   * <p>Neither field is a decisions.json/contract field, so
   * {@code NeoFieldFilter#filterCreateRequest}/{@code filterWriteRequest} would otherwise
   * silently drop them before {@code afterHandle()} got a chance to read them back from
   * {@code context.getRequestBody()} — the link was never actually persisted (confirmed via an
   * empty {@code C_Invoice_Reverse} table) despite {@code persistOriginInvoice} looking correct
   * in isolation. Same reasoning as the {@code parentId} carve-out at the top of
   * {@code NeoCrudHandler#executePostCreate}.
   *
   * <p>ETP-4919: {@code originInvoices} (a JSON array of invoice ids) is the current shape sent
   * by the "Import from Source Invoice" popup — a rectificativa can be linked to more than one
   * source invoice across separate import runs. {@code originInvoice} (a single id) is kept as a
   * backward-compatible alias: if present, its id is folded into the same captured set. Both
   * keys may be present at once; ids are de-duplicated.
   *
   * @param context
   *     the current request context
   */
  protected void captureOriginInvoice(NeoContext context) {
    if (!NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      return;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return;
    }
    List<String> ids = new ArrayList<>();
    boolean captured = collectOriginInvoiceIds(body, ids);

    if (captured) {
      pendingOriginInvoiceIds = ids;
      originInvoiceCaptured = true;
    }
  }

  /**
   * Reads {@code originInvoices} (array) and/or the legacy singular {@code originInvoice} from
   * {@code body} into {@code ids} (de-duplicated), removing both keys from {@code body} so the
   * generic field filter never sees them (see {@link #captureOriginInvoice} javadoc for why).
   *
   * @return {@code true} if either key was present in {@code body}
   */
  private boolean collectOriginInvoiceIds(JSONObject body, List<String> ids) {
    boolean captured = false;

    if (body.has(FIELD_ORIGIN_INVOICES)) {
      captured = true;
      addIdsFromOriginInvoicesArray(body, ids);
      body.remove(FIELD_ORIGIN_INVOICES);
    }

    if (body.has(FIELD_ORIGIN_INVOICE)) {
      captured = true;
      String legacyId = body.optString(FIELD_ORIGIN_INVOICE, null);
      if (StringUtils.isNotBlank(legacyId) && !ids.contains(legacyId)) {
        ids.add(legacyId);
      }
      body.remove(FIELD_ORIGIN_INVOICE);
    }

    return captured;
  }

  /** Parses the {@code originInvoices} JSON array in {@code body} into {@code ids} (de-duplicated). */
  private void addIdsFromOriginInvoicesArray(JSONObject body, List<String> ids) {
    try {
      JSONArray arr = body.getJSONArray(FIELD_ORIGIN_INVOICES);
      for (int i = 0; i < arr.length(); i++) {
        String id = arr.optString(i, null);
        if (StringUtils.isNotBlank(id) && !ids.contains(id)) {
          ids.add(id);
        }
      }
    } catch (Exception e) {
      log.warn("Could not parse {} array: {}", FIELD_ORIGIN_INVOICES, e.getMessage());
    }
  }

  /**
   * Persists the origin-invoice relationship(s) to {@code C_Invoice_Reverse} after a POST or
   * PUT/PATCH, using the ids {@link #captureOriginInvoice} captured from the raw request body
   * before the generic field filter stripped them.
   *
   * <p>ETP-4919 fix: this NEVER deletes existing links to OTHER origin invoices from prior
   * import runs — the old delete-then-single-create behavior is exactly why importing from a
   * second source invoice silently dropped the first link. It only creates a link per captured
   * id, and only if that exact (invoice, origin) pair doesn't already exist — so re-importing
   * from the same source invoice twice never produces duplicate rows. When the captured id set
   * is empty (field absent, or present but blank), this is a no-op: there is no supported way to
   * unlink an origin invoice through this endpoint.
   *
   * @param context
   *     the current request context
   */
  protected void persistOriginInvoice(NeoContext context) {
    try {
      if (!originInvoiceCaptured) {
        return;
      }
      List<String> originInvoiceIds = pendingOriginInvoiceIds;
      if (originInvoiceIds == null || originInvoiceIds.isEmpty()) {
        return;
      }

      String invoiceId = resolveInvoiceIdFromContext(context);
      if (StringUtils.isBlank(invoiceId)) {
        return;
      }

      OBContext.setAdminMode(true);
      try {
        for (String originInvoiceId : originInvoiceIds) {
          createReverseLinkIfMissing(invoiceId, originInvoiceId);
        }
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not persist origin invoice: {}", e.getMessage());
    }
  }

  private static String resolveInvoiceIdFromContext(NeoContext context) {
    if (context.getRecordId() != null) {
      return context.getRecordId();
    }
    // POST: extract newly created record ID from the CRUD response
    return NeoHandlerUtils.extractCreatedIdFromPreviousResult(context);
  }

  /**
   * Creates a {@code C_Invoice_Reverse} link from {@code invoiceId} to {@code originInvoiceId}
   * unless one already exists — the dedupe check that replaces the old unconditional
   * delete-then-create (ETP-4919), so importing from the same source invoice twice never
   * produces duplicate rows, and importing from a NEW source invoice never removes the link to a
   * previously-imported one.
   */
  private void createReverseLinkIfMissing(String invoiceId, String originInvoiceId) {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    Invoice origin = OBDal.getInstance().get(Invoice.class, originInvoiceId);
    if (invoice == null || origin == null) {
      log.warn("Cannot create reverse link: invoice={} origin={}", invoiceId, originInvoiceId);
      return;
    }
    List<ReversedInvoice> existing = OBDal.getInstance()
        .createCriteria(ReversedInvoice.class)
        .add(Restrictions.eq(ReversedInvoice.PROPERTY_INVOICE, invoice))
        .add(Restrictions.eq(ReversedInvoice.PROPERTY_REVERSEDINVOICE, origin))
        .list();
    if (!existing.isEmpty()) {
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
   * Injects {@code originInvoices} (a JSON array of {@code {id, documentNo}}, one entry per
   * linked origin invoice) plus the backward-compatible singular {@code originInvoice}/
   * {@code originInvoice$_identifier} pair (the FIRST linked origin, or {@code null} when none)
   * into the record, by querying the {@code C_Invoice_Reverse} table for ALL active links where
   * this invoice is the reversed one.
   *
   * <p>ETP-4919 fix: previously this read only the first matching row ({@code rs.next()} called
   * once) — a rectificativa linked to more than one source invoice (e.g. two separate "Import
   * from Source Invoice" runs) silently lost every origin but one on the frontend, even on the
   * rare occasion the backend link itself did survive. Callers that only need "is there at least
   * one" can keep reading {@code originInvoice}; {@code RelatedDocuments} now reads
   * {@code originInvoices} to render one chip per linked origin.
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
        + "WHERE r.c_invoice_id = ? AND r.isactive = 'Y' "
        + "ORDER BY inv.documentno";
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        JSONArray origins = new JSONArray();
        boolean first = true;
        while (rs.next()) {
          String originId = rs.getString(1);
          String originDocNo = rs.getString(2);
          origins.put(new JSONObject().put("id", originId).put("documentNo", originDocNo));
          if (first) {
            rec.put(FIELD_ORIGIN_INVOICE, originId);
            rec.put("originInvoice$_identifier", originDocNo);
            first = false;
          }
        }
        if (first) {
          // no rows at all
          rec.put(FIELD_ORIGIN_INVOICE, JSONObject.NULL);
          rec.put("originInvoice$_identifier", JSONObject.NULL);
        }
        rec.put(FIELD_ORIGIN_INVOICES, origins);
      }
    } catch (Exception e) {
      log.warn("Could not enrich origin invoice for {}: {}", invoiceId, e.getMessage());
    }
  }

  /**
   * Injects the invoice subtype virtual field ({@link #getInvoiceSubtypeKey()}) into the record
   * by resolving the current {@code transactionDocument} value via {@link #resolveSubtype(String)}.
   *
   * <p>This reflects the DOCUMENT TYPE only (FAC vs RECTIFICATIVA) and drives the doc-type badge
   * column and the list tab filters. It must NOT be used to decide payment state: whether an
   * invoice is a "saldo a favor" or a payable is decided by the SIGN of its total (ETP-4841),
   * because a Factura Rectificativa can legitimately be positive (an under-invoiced correction,
   * which is payable) and an ordinary Factura can be negative (a credit).
   *
   * @param rec
   *     the invoice record JSON object; modified in-place
   * @param key
   *     the virtual-field key (e.g. {@code "apInvoiceSubtype"} or {@code "arInvoiceSubtype"})
   * @throws Exception
   *     if a JSON operation fails
   */
  protected void enrichInvoiceSubtype(JSONObject rec, String key) throws Exception {
    rec.put(key, resolveSubtype(rec.optString(FIELD_TRANSACTION_DOCUMENT, null)));
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

  /**
   * Test hook: force or reset (null) the cached {@code em_etsg_isrectificative} column-presence
   * check shared via {@link RectificativeSupport} (the single source of truth for this flag,
   * also used by the ETP-4738 "saldo a favor" filter).
   */
  static void setRectificativeColumnPresentForTests(Boolean value) {
    RectificativeSupport.setColumnPresentForTests(value);
  }

  /**
   * Injects {@code isRectificative} (boolean) into the record.
   * True when the invoice's document type has {@code EM_Etsg_Isrectificative = 'Y'}.
   * Returns false when the invoice is a draft with no doctype selected, or when the
   * SIF General module (owner of the column) is not installed.
   */
  protected void enrichIsRectificative(JSONObject rec) throws Exception {
    String docTypeId = rec.optString(FIELD_TRANSACTION_DOCUMENT, null);
    if (StringUtils.isBlank(docTypeId) || !RectificativeSupport.isColumnPresent()) {
      rec.put("isRectificative", false);
      return;
    }
    boolean result = false;
    try {
      String sql = "SELECT em_etsg_isrectificative FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            result = "Y".equals(rs.getString(1));
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve isRectificative for doctype {}: {}", docTypeId, e.getMessage());
    }
    rec.put("isRectificative", result);
  }

  /**
   * Injects {@code hasRectifications} (boolean) into the record.
   * True when the invoice has one or more records in {@code C_Invoice_Reverse}.
   * Only meaningful in detail view (single record); do not call for list responses.
   */
  @SuppressWarnings("java:S2077")
  protected void enrichHasRectifications(JSONObject rec, String invoiceId) throws Exception {
    if (StringUtils.isBlank(invoiceId)) {
      rec.put("hasRectifications", false);
      return;
    }
    boolean result = false;
    try {
      String sql = "SELECT 1 FROM c_invoice_reverse WHERE c_invoice_id = ? AND isactive = 'Y' LIMIT 1";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        try (ResultSet rs = ps.executeQuery()) {
          result = rs.next();
        }
      }
    } catch (Exception e) {
      log.warn("Could not check hasRectifications for invoice {}: {}", invoiceId, e.getMessage());
    }
    rec.put("hasRectifications", result);
  }


  /**
   * Adjusts {@code grandTotalAmount} and {@code outstandingAmount} for a draft invoice carrying
   * a positive {@code etgoTotalDiscount} percentage that has not yet been materialized as a real
   * line — otherwise the list view and preview cards show the raw undiscounted total while the
   * document is still in draft, since the discount is only materialized into a real line at
   * Complete time via {@link TotalDiscountService}. Mirrors {@link AbstractOrderHeaderHandler}'s
   * order-side equivalent.
   *
   * <p>No-op when the invoice is processed (the DB total already reflects the discount), when no
   * discount percentage is set, or when the discount is already a real line
   * ({@code hasDiscountLine} — e.g. an invoice created from an order that already carried the
   * discount, via {@code InvoiceFromOrderSupport} — avoids double-counting). Mirrors the exact
   * guard order of the original {@code SalesInvoiceHeaderHandler}-only implementation this was
   * consolidated from: {@link #getTotalDiscountService()} is only dereferenced when {@code id}
   * is present, matching every existing caller/test.
   */
  protected void applyTotalDiscountToRecord(JSONObject invoice) throws Exception {
    if (invoice.optBoolean(FIELD_PROCESSED, false)) {
      return;
    }
    double discountPct = invoice.optDouble(FIELD_TOTAL_DISCOUNT_PCT, 0.0);
    if (discountPct <= 0.0) {
      return;
    }
    String invoiceId = invoice.optString("id", null);
    if (invoiceId != null && getTotalDiscountService().hasDiscountLine(invoiceId, true)) {
      return;
    }
    double factor = 1.0 - discountPct / 100.0;
    double grand = invoice.optDouble(FIELD_GRAND_TOTAL_AMOUNT, 0.0);
    invoice.put(FIELD_GRAND_TOTAL_AMOUNT, NeoHandlerUtils.roundHalfUp(grand * factor));
    double outstanding = invoice.optDouble(FIELD_OUTSTANDING_AMOUNT, 0.0);
    invoice.put(FIELD_OUTSTANDING_AMOUNT, NeoHandlerUtils.roundHalfUp(outstanding * factor));
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
   * <p>Call this AFTER {@link #validateLineQtyBeforeComplete(NeoContext)} AND AFTER
   * {@link AbstractOrderHeaderHandler#applyTotalDiscountBeforeComplete} in {@code handle()}, so
   * (1) pre-completion validation can still block the request before the real process runs, and
   * (2) the total-discount line already reflects the final set of product lines before it is
   * read/posted by {@link ProcessInvoiceUtil#process}. Calling this BEFORE the discount
   * recalculation would complete the document with a stale or missing discount line.
   *
   * <p><b>Session-lifecycle note (deliberate divergence):</b> unlike every other handler in this
   * module — which only {@code .flush()} the DAL session and leave the final commit to the
   * request-scoped {@code DalThreadCleaner} — {@link ProcessInvoiceUtil#process} internally calls
   * {@code OBDal.getInstance().commitAndClose()} (success) / {@code .rollbackAndClose()} (error),
   * fully closing the current Hibernate session mid-request. This mirrors classic UI behavior
   * (the method is shared with the classic completion path) and is required for its internal
   * {@code ProcessInstance}/{@code CallProcess} bookkeeping. It is safe here because the very next
   * statement performs a DAL read ({@code OBDal.getInstance().get(Process.class, ...)}), which
   * transparently reopens the session — the same characteristic already relied upon by
   * {@code GlJournalHeaderHandler#completeJournal} via {@code FIN_AddPaymentFromJournal}. Do not
   * remove or reorder that read without re-verifying this assumption.
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
      // Plain DalConnectionProvider, not wrapped in a RequestContext/pushRequestContextVars scope
      // (the pattern used elsewhere in NeoProcessService.java for classic-code invocations):
      // neither the Verifactu nor the TBAI ProcessInvoiceHook reads RequestContext today, so this
      // is a non-issue in practice. Revisit if a future hook needs request-scoped context.
      ConnectionProvider conn = new DalConnectionProvider(false);
      ProcessInvoiceUtil processInvoiceUtil =
          WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class);
      // Void-date/supplier-reference params are only consulted for the void action (docAction RC).
      // ProcessInvoiceUtil calls .isEmpty() on the date strings unconditionally, so they must be
      // non-null. Empty strings are the correct null-safe default for a normal "CO" completion.
      OBError result = processInvoiceUtil.process(invoiceId, "CO", "", "", "", vars, conn);
      Process process = OBDal.getInstance().get(Process.class, COMPLETE_PROCESS_ID_INVOICE);
      if (process == null) {
        log.error("[INVOICE-COMPLETE] Process record {} not found", COMPLETE_PROCESS_ID_INVOICE);
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Completion process configuration missing");
      }
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

  // ---------------------------------------------------------------------------
  // Currency / exchange-rate hooks (ETP-4029)
  // ---------------------------------------------------------------------------

  /**
   * Removes a callout-pushed {@code currency} key from the callout {@code updates} map.
   * Currency on invoices is only ever changed by the user directly, never by a callout
   * (e.g. business-partner or price-list callouts must not silently move the document's
   * currency). Mirrors {@code AbstractOrderHeaderHandler#blockCalloutCurrencyUpdate}.
   *
   * <p>Call from each subclass's {@code afterCallout()} override.
   *
   * @param updates      the callout response's {@code updates} object; may be {@code null}
   * @param triggerField the field that triggered the callout (from the request body)
   */
  protected static void blockCalloutCurrencyUpdate(JSONObject updates, String triggerField) {
    if (updates != null && updates.has(FIELD_CURRENCY) && !FIELD_CURRENCY.equals(triggerField)) {
      updates.remove(FIELD_CURRENCY);
      log.debug("[ETP-4029] Removed callout-driven currency update (trigger={})", triggerField);
    }
  }

  /**
   * Removes a callout-pushed {@code transactionDocument} key from the callout {@code updates} map
   * once the invoice has already been saved. Document type is only ever changed by the user
   * directly (or defaulted at first-save time) — a business-partner (or other) callout must not
   * silently revert NC/DEV subtypes back to the org's default document type after save (ETP-4535).
   *
   * <p>Unlike {@link #blockCalloutCurrencyUpdate}, which blocks ANY callout-driven overwrite, this
   * guard only applies once the invoice has a {@code documentNo} — choosing a document type via
   * BP-driven defaults before the first save is legitimate and must not be blocked. Mirrors the
   * "not yet saved, allow" rule already enforced client-side (PUT edits) by
   * {@link #validateDocTypeLock}.
   *
   * <p>Call from each subclass's {@code afterCallout()} override.
   *
   * @param updates      the callout response's {@code updates} object; may be {@code null}
   * @param triggerField the field that triggered the callout (from the request body)
   * @param recordId     the invoice's record id from the current context; may be {@code null} for
   *                     a new (unsaved) record
   */
  protected static void blockCalloutDocTypeUpdateIfLocked(JSONObject updates, String triggerField,
      String recordId) {
    if (updates == null || !updates.has(FIELD_TRANSACTION_DOCUMENT)
        || FIELD_TRANSACTION_DOCUMENT.equals(triggerField)) {
      return;
    }
    if (StringUtils.isBlank(recordId)) {
      return; // not yet saved — allow callout-driven doc type defaults
    }
    try {
      Invoice invoice = OBDal.getInstance().get(Invoice.class, recordId);
      if (invoice == null || StringUtils.isBlank(invoice.getDocumentNo())) {
        return; // not yet saved — allow
      }
      updates.remove(FIELD_TRANSACTION_DOCUMENT);
      log.debug("[ETP-4535] Removed callout-driven document type update on saved invoice {} (trigger={})",
          recordId, triggerField);
    } catch (Exception e) {
      log.warn("[ETP-4535] Could not evaluate document type lock for invoice {}: {}",
          recordId, e.getMessage());
    }
  }

  /**
   * Appends a {@code WARNING} message to the callout response body when the user directly
   * changes the invoice's {@code currency} field and no {@code C_Conversion_Rate} exists for
   * (docCurrency → orgCurrency, invoiceDate). Mirrors
   * {@code AbstractOrderHeaderHandler#checkExchangeRateWarning}.
   *
   * <p>ETP-4838: rate availability is resolved by {@link NeoExchangeRateService#hasRate} — the same
   * lookup {@code GET /sws/neo/validate-exchange-rate} serves the frontend — so the callout warning
   * and the frontend's own pre-check can never disagree.
   *
   * <p>Call from each subclass's {@code afterCallout()} override.
   *
   * @param body         the callout response body; messages are appended in-place
   * @param requestBody  the original callout request payload (carries {@code field}/{@code value})
   * @param formState    the callout {@code formState}, carries {@code invoiceDate} as fallback
   * @param triggerField the field that triggered the callout
   */
  protected static void checkExchangeRateWarning(JSONObject body, JSONObject requestBody,
      JSONObject formState, String triggerField) {
    if (formState == null) {
      return;
    }
    if (!FIELD_CURRENCY.equals(triggerField) && !"currencyid".equals(triggerField)) {
      return;
    }
    String docCurrencyId = requestBody != null ? requestBody.optString(FIELD_VALUE, "") : "";
    if (docCurrencyId.isEmpty()) {
      docCurrencyId = formState.optString("currencyid", "");
    }
    String invoiceDate = formState.optString("invoiceDate", "");
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    if (!docCurrencyId.isEmpty() && orgCurrencyId != null
        && !docCurrencyId.equals(orgCurrencyId) && !invoiceDate.isEmpty()
        && !NeoExchangeRateService.hasRate(orgCurrencyId, docCurrencyId, invoiceDate)) {
      appendMessage(body, "WARNING", "noExchangeRateAvailable");
      log.debug("[ETP-4029] No conversion rate warning added (currency={})", docCurrencyId);
    }
  }

  // ---------------------------------------------------------------------------
  // Unified date (ETP-4531)
  // ---------------------------------------------------------------------------
  //
  // The mirrorAccountingDate(NeoContext, String, String) logic itself lives in
  // NeoHandlerUtils — shared with AbstractOrderHeaderHandler — see
  // NeoHandlerUtils#mirrorAccountingDate. Call sites here invoke it with
  // ("invoiceDate", "accountingDate").

  /**
   * Shared {@code afterCallout} body: blocks callout-driven currency updates and appends an
   * exchange-rate warning when the user directly changes the invoice currency (ETP-4029); and
   * blocks callout-driven document type updates on an already-saved invoice (ETP-4535).
   *
   * <p>{@code accountingDate} cascades from {@code invoiceDate} via the classic Etendo callout
   * ({@code SE_Invoice_AccountingDate}) are intentionally left untouched — ETP-4531 now requires
   * the single visible date to be mirrored into {@code accountingDate} on save, so that cascade
   * is exactly the behavior wanted.
   *
   * <p>Identical for both {@link PurchaseInvoiceHeaderHandler} and {@link SalesInvoiceHeaderHandler}
   * — each subclass's {@code afterCallout()} override should just delegate here.
   */
  protected NeoResponse handleInvoiceAfterCallout(NeoContext context) {
    try {
      NeoHandlerUtils.CalloutFields fields = NeoHandlerUtils.extractCalloutFields(context);
      if (fields == null) {
        return null;
      }
      blockCalloutCurrencyUpdate(fields.updates(), fields.triggerField());
      checkExchangeRateWarning(fields.body(), fields.requestBody(), fields.formState(), fields.triggerField());
      String recordId = resolveCalloutRecordId(context, fields.formState());
      blockCalloutDocTypeUpdateIfLocked(fields.updates(), fields.triggerField(), recordId);
    } catch (Exception e) {
      log.warn("[ETP-4029/ETP-4535] afterCallout failed (non-fatal): {}", e.getMessage());
    }
    return null; // mutations applied in-place; dispatcher merges nothing extra
  }

  /**
   * Resolves the invoice's record id for a callout request.
   *
   * <p>Callout URLs carry no record-id path segment — {@code NeoServletSupport.parseSubEndpointPath}
   * matches the callout route as a literal {@code {specName}/{entityName}/callout} 3-segment path
   * with the record-id segment hardcoded {@code null}, and {@link NeoCalloutEndpoint#handleCallout}
   * never calls {@code .recordId(...)} on the {@link NeoContext} builder. So
   * {@link NeoContext#getRecordId()} is always {@code null} for a real callout, and the currently
   * loaded record's id must instead be read from the callout's echoed {@code formState.id} — the
   * same idiom already used by {@code InventoryLineHandler#afterCallout} (formState null-guard +
   * {@code optString("id", ...)}) and {@code CalloutRequestBuilder#injectParentId}
   * ({@code formState.has("id")} before reading).
   *
   * <p>{@code context.getRecordId()} is checked first as a defensive fallback for any future/other
   * dispatch path that DOES populate it (e.g. a non-callout invocation of this shared method), but
   * for the real production callout path it is always blank and {@code formState.id} is what fires.
   *
   * @param context   the current NeoContext
   * @param formState the callout's {@code formState} object; may be {@code null}
   * @return the resolved invoice record id, or {@code null} if neither source has one
   */
  private static String resolveCalloutRecordId(NeoContext context, JSONObject formState) {
    String recordId = context.getRecordId();
    if (StringUtils.isNotBlank(recordId)) {
      return recordId;
    }
    if (formState == null) {
      return null;
    }
    return StringUtils.trimToNull(formState.optString("id", null));
  }

  private static void appendMessage(JSONObject body, String type, String text) {
    try {
      org.codehaus.jettison.json.JSONArray messages = body.optJSONArray("messages");
      if (messages == null) {
        messages = new org.codehaus.jettison.json.JSONArray();
        body.put("messages", messages);
      }
      JSONObject msg = new JSONObject();
      msg.put("type", type);
      msg.put("text", text);
      messages.put(msg);
    } catch (Exception e) {
      log.warn("[ETP-4029] appendMessage failed: {}", e.getMessage());
    }
  }

  /**
   * Upserts the {@code C_Conversion_Rate_Document} record for an invoice whenever its currency
   * differs from the org currency. Runs as a continuous upsert on every successful header save
   * (PATCH/PUT/POST) — not only when {@code currency}/{@code eTGOCurrencyRate} appears in the
   * request body — so that {@code foreignAmount} stays in sync as {@code grandTotalAmount}
   * evolves while lines are added/edited after the currency was first set.
   *
   * <p>No-op when the invoice has no currency, the invoice currency equals the org currency,
   * or no {@code EM_ETGO_Currency_Rate} override has been set on the invoice yet.
   *
   * <p>Call unconditionally at the top of each subclass's {@code afterHandle()}, alongside
   * any other per-save hooks, before their own method-gated (e.g. GET-only) logic.
   *
   * <p>Resolves the invoice ID from the header {@code NeoContext} (via
   * {@link #resolveInvoiceIdFromContext(NeoContext)}) and delegates to
   * {@link #autoCreateOrUpdateConversionRateDocument(String)}. Handlers that are NOT scoped to
   * the invoice header entity (e.g. {@link InvoiceLineHandler}, whose {@code NeoContext} carries
   * the line's own record ID, not the invoice's) must resolve the parent invoice ID themselves
   * and call the {@code String}-based overload directly instead.
   *
   * @param context the current NeoContext; only {@code getHttpMethod()}/{@code getRecordId()}/
   *                {@code getPreviousResult()} are used
   */
  protected static void autoCreateOrUpdateConversionRateDocument(NeoContext context) {
    String method = context.getHttpMethod();
    if (!"PATCH".equals(method) && !"PUT".equals(method) && !"POST".equals(method)) {
      return;
    }
    String invoiceId = resolveInvoiceIdFromContext(context);
    autoCreateOrUpdateConversionRateDocument(invoiceId);
  }

  /**
   * Upserts the {@code C_Conversion_Rate_Document} record for the invoice identified by
   * {@code invoiceId}, whenever its currency differs from the org currency. Same business rule
   * and no-op conditions as {@link #autoCreateOrUpdateConversionRateDocument(NeoContext)} — this
   * is the shared core both overloads (and {@link InvoiceLineHandler}) funnel into, so there is
   * exactly one upsert implementation for {@code C_Conversion_Rate_Document}.
   *
   * @param invoiceId the invoice's primary key, already resolved by the caller; no-op if blank
   */
  @SuppressWarnings("java:S2077")
  protected static void autoCreateOrUpdateConversionRateDocument(String invoiceId) {
    if (StringUtils.isBlank(invoiceId)) {
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
        if (invoice == null || invoice.getCurrency() == null) {
          return;
        }
        // Force a fresh read of the DB row before trusting grandTotalAmount below. This method
        // is also invoked from InvoiceLineHandler.afterHandle() (line save), which has already
        // touched/loaded this same Invoice earlier in the request (via
        // resolveParentInvoiceIdAfterSave -> InvoiceLine.getInvoice()) — before the line-insert's
        // c_invoiceline_trg2 DB trigger recalculates C_Invoice.grandtotal. Hibernate's L1 cache
        // then returns that same stale managed instance here instead of re-querying, so
        // getGrandTotalAmount() below would reflect the PRE-line-insert total. refresh() re-reads
        // this entity's scalar columns from the DB in place; unlike the session.clear() pattern in
        // InvoiceFromOrderSupport#applyOrderDiscountToInvoice, it's safe here because this method
        // never touches invoice.invoiceLineList/invoiceTaxList collections and never calls
        // OBDal.save() on the invoice — it only reads a scalar total and writes to the unrelated
        // C_Conversion_Rate_Document table via raw JDBC, so there's no cascade-refresh/duplicate-
        // insert risk (the ETP-4015 symptom that motivated the heavier clear()+reload there).
        OBDal.getInstance().getSession().refresh(invoice);
        String orgId = invoice.getOrganization().getId();
        String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
        if (orgCurrencyId == null || orgCurrencyId.equals(invoice.getCurrency().getId())) {
          return; // nothing to track: same currency as org, or org currency unresolved
        }
        BigDecimal rate = invoice.getETGOCurrencyRate();
        if (rate == null) {
          return; // no override set yet
        }

        // EM_ETGO_Currency_Rate is the org→doc multiplyRate (mirrors C_Order semantics).
        // C_Conversion_Rate_Document.rate is the doc→org multiplier: amount_doc × docRate = amount_org.
        BigDecimal docRate = BigDecimal.ONE.divide(rate, 12, RoundingMode.HALF_UP);
        BigDecimal grandTotal = invoice.getGrandTotalAmount();
        BigDecimal foreignAmount = grandTotal != null
            ? grandTotal.multiply(docRate).setScale(2, RoundingMode.HALF_UP)
            : null;

        upsertConversionRateDocument(invoice, orgCurrencyId, docRate, foreignAmount);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-4029] autoCreateOrUpdateConversionRateDocument failed for invoice {}: {}",
          invoiceId, e.getMessage());
    }
  }

  private static void upsertConversionRateDocument(Invoice invoice, String orgCurrencyId,
      BigDecimal docRate, BigDecimal foreignAmount) throws java.sql.SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    String existingId = findConversionRateDocumentId(conn, invoice.getId(),
        invoice.getCurrency().getId(), orgCurrencyId);
    if (existingId != null) {
      updateConversionRateDocument(conn, existingId, docRate, foreignAmount);
    } else {
      insertConversionRateDocument(conn, invoice, orgCurrencyId, docRate, foreignAmount);
    }
  }

  private static String findConversionRateDocumentId(Connection conn, String invoiceId,
      String docCurrencyId, String orgCurrencyId) throws java.sql.SQLException {
    String sql =
        "SELECT c_conversion_rate_document_id FROM c_conversion_rate_document"
      + " WHERE c_invoice_id = ? AND c_currency_id = ? AND c_currency_id_to = ? LIMIT 1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      ps.setString(2, docCurrencyId);
      ps.setString(3, orgCurrencyId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static void updateConversionRateDocument(Connection conn, String recordId,
      BigDecimal docRate, BigDecimal foreignAmount) throws java.sql.SQLException {
    String userId = OBContext.getOBContext().getUser().getId();
    String sql =
        "UPDATE c_conversion_rate_document"
      + " SET rate = ?, foreign_amount = ?, updated = NOW(), updatedby = ?"
      + " WHERE c_conversion_rate_document_id = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setBigDecimal(1, docRate);
      if (foreignAmount != null) {
        ps.setBigDecimal(2, foreignAmount);
      } else {
        ps.setNull(2, java.sql.Types.NUMERIC);
      }
      ps.setString(3, userId);
      ps.setString(4, recordId);
      ps.executeUpdate();
      log.info("[ETP-4029] Updated C_Conversion_Rate_Document {} (docRate={})", recordId, docRate);
    }
  }

  private static void insertConversionRateDocument(Connection conn, Invoice invoice,
      String orgCurrencyId, BigDecimal docRate, BigDecimal foreignAmount) throws java.sql.SQLException {
    String newId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    String userId = OBContext.getOBContext().getUser().getId();
    String sql =
        "INSERT INTO c_conversion_rate_document ("
      + " c_conversion_rate_document_id, ad_client_id, ad_org_id, isactive,"
      + " created, createdby, updated, updatedby,"
      + " c_invoice_id, c_currency_id, c_currency_id_to, rate, foreign_amount"
      + ") VALUES (?, ?, ?, 'Y', NOW(), ?, NOW(), ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, newId);
      ps.setString(2, invoice.getClient().getId());
      ps.setString(3, invoice.getOrganization().getId());
      ps.setString(4, userId);
      ps.setString(5, userId);
      ps.setString(6, invoice.getId());
      ps.setString(7, invoice.getCurrency().getId());
      ps.setString(8, orgCurrencyId);
      ps.setBigDecimal(9, docRate);
      if (foreignAmount != null) {
        ps.setBigDecimal(10, foreignAmount);
      } else {
        ps.setNull(10, java.sql.Types.NUMERIC);
      }
      ps.executeUpdate();
      log.info("[ETP-4029] Created C_Conversion_Rate_Document {} for invoice {} (docRate={})",
          newId, invoice.getId(), docRate);
    }
  }
}
