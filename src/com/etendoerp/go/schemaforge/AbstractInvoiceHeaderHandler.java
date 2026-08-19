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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
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
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.ReversedInvoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;
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
  private String pendingOriginInvoiceId;
  private boolean originInvoiceCaptured;

  // ETP-4783: SII authorization number — same capture/persist pattern as originInvoice.
  // captureAndValidateSiiAuthorization() (called from handle()) validates the SII config and
  // stores the resolved authorization number (or ""), then persistSiiAuthorizationno() (called
  // from afterHandle()) writes it directly via DAL after the CRUD write completes.
  // This is necessary because aeatsiiAuthorizationno has visibility=readOnly in the contract,
  // so filterWriteRequest() strips it from the PATCH body before the DB write — it cannot be
  // injected into the request body in handle() and survive to the DAL layer.
  private String pendingSiiAuthorizationno;
  private boolean siiAuthorizationCaptured;

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
   * Captures and strips {@code originInvoice} from the raw request body BEFORE the generic
   * field filter runs, so {@link #persistOriginInvoice} can still use it later in
   * {@code afterHandle()}. Must be called from each subclass's {@code handle()} (the pre-hook),
   * e.g. alongside the existing {@code NeoHandlerUtils.mirrorAccountingDate(...)} call.
   *
   * <p>{@code originInvoice} is not a decisions.json/contract field, so
   * {@code NeoFieldFilter#filterCreateRequest}/{@code filterWriteRequest} would otherwise
   * silently drop it before {@code afterHandle()} got a chance to read it back from
   * {@code context.getRequestBody()} — the link was never actually persisted (confirmed via an
   * empty {@code C_Invoice_Reverse} table) despite {@code persistOriginInvoice} looking correct
   * in isolation. Same reasoning as the {@code parentId} carve-out at the top of
   * {@code NeoCrudHandler#executePostCreate}.
   *
   * @param context
   *     the current request context
   */
  protected void captureOriginInvoice(NeoContext context) {
    if (!NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      return;
    }
    JSONObject body = context.getRequestBody();
    if (body != null && body.has(FIELD_ORIGIN_INVOICE)) {
      pendingOriginInvoiceId = body.optString(FIELD_ORIGIN_INVOICE, null);
      originInvoiceCaptured = true;
      body.remove(FIELD_ORIGIN_INVOICE);
    }
  }

  /**
   * Persists the origin-invoice relationship to {@code C_Invoice_Reverse} after a POST or PUT,
   * using the value {@link #captureOriginInvoice} captured from the raw request body before the
   * generic field filter stripped it. Deletes any existing link for this invoice before creating
   * the new one (or leaves it deleted if {@code originInvoice} was absent/blank).
   *
   * @param context
   *     the current request context
   */
  protected void persistOriginInvoice(NeoContext context) {
    try {
      if (!originInvoiceCaptured) {
        return;
      }
      String originInvoiceId = pendingOriginInvoiceId;

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

  private static String resolveInvoiceIdFromContext(NeoContext context) {
    if (context.getRecordId() != null) {
      return context.getRecordId();
    }
    // POST: extract newly created record ID from the CRUD response
    return NeoHandlerUtils.extractCreatedIdFromPreviousResult(context);
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
  // SII authorization (ETP-4783)
  // ---------------------------------------------------------------------------

  /**
   * Validates and captures the SII authorization number when {@code aeatsiiIsauthorization}
   * is being set to {@code true} in a write request (ETP-4783).
   *
   * <p>Must be called from each subclass's {@code handle()} (the pre-hook). The classic
   * {@code SiiAuthorizationCallout} does not fire in Go/NEO Headless because the SifTab custom
   * component fires a direct {@code /header/callout} fetch instead (bypassing the
   * {@code fireCallout} guard that filters 'Y'/'N' values). This method enforces the same
   * save-time invariant for every PATCH/PUT/POST.
   *
   * <p>{@code aeatsiiAuthorizationno} has {@code visibility=readOnly} in the contract
   * ({@code isreadonly='Y'} in the DB), so {@code filterWriteRequest} strips it from the PATCH
   * body before the DAL write. The value resolved here (from {@code AEATSIIConfig}) is stored in
   * {@link #pendingSiiAuthorizationno} and written by {@link #persistSiiAuthorizationno(NeoContext)}
   * (called from {@code afterHandle()}) via a direct DAL update on the already-saved record.
   *
   * <p>When {@code aeatsiiIsauthorization} is being set to {@code false}, this method returns
   * {@code null} immediately (no capture, no persist) — preserving the existing DB number for
   * future re-enabling. Clearing the number on uncheck caused a "2nd cycle" bug where the number
   * would vanish after uncheck→save→re-check→save.
   *
   * @param context the current request context
   * @return an error {@link NeoResponse} to reject the save (400), or {@code null} to proceed
   */
  protected NeoResponse captureAndValidateSiiAuthorization(NeoContext context) {
    if (!NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || !body.has("aeatsiiIsauthorization")) {
      return null;
    }
    try {
      Object rawValue = body.opt("aeatsiiIsauthorization");
      // The field is a Boolean in the Invoice entity; the JSON body can carry true/false or "Y"/"N"
      // depending on the frontend serialization.
      boolean isAuthorization = Boolean.TRUE.equals(rawValue)
          || "Y".equalsIgnoreCase(String.valueOf(rawValue))
          || "true".equalsIgnoreCase(String.valueOf(rawValue));

      if (isAuthorization) {
        Organization org = OBContext.getOBContext().getCurrentOrganization();
        if (org == null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "La organización no tiene configuración de SII o la configuración no tiene número de autorización.");
        }
        AEATSIIConfig config = SIIUtils.getSiiConfigFromOrg(org);
        if (config == null || StringUtils.isBlank(config.getAuthorizationno())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
              "La organización no tiene configuración de SII o la configuración no tiene número de autorización.");
        }
        pendingSiiAuthorizationno = config.getAuthorizationno();
      } else {
        // ETP-4783: Authorization disabled — do NOT clear the stored number. Wiping it here
        // (via persistSiiAuthorizationno) would erase the DB value when the user unchecks,
        // causing the number to vanish on re-check + save. The CRUD write handles
        // aeatsiiIsauthorization = false normally; the number is preserved for future use.
        return null;
      }
      siiAuthorizationCaptured = true;
    } catch (Exception e) {
      log.warn("[ETP-4783] captureAndValidateSiiAuthorization failed (non-fatal): {}", e.getMessage());
    }
    return null;
  }

  /**
   * Writes {@link #pendingSiiAuthorizationno} (captured in the pre-hook) to the invoice via a
   * direct DAL update after the main CRUD write completes (ETP-4783).
   *
   * <p>Must be called from each subclass's {@code afterHandle()} alongside
   * {@link #persistOriginInvoice(NeoContext)}.
   *
   * @param context the current request context
   */
  protected void persistSiiAuthorizationno(NeoContext context) {
    if (!siiAuthorizationCaptured) {
      return;
    }
    try {
      String invoiceId = resolveInvoiceIdFromContext(context);
      if (StringUtils.isBlank(invoiceId)) {
        return;
      }
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
        if (invoice == null) {
          return;
        }
        invoice.setAeatsiiAuthorizationno(pendingSiiAuthorizationno);
        OBDal.getInstance().save(invoice);
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-4783] persistSiiAuthorizationno failed: {}", e.getMessage());
    }
  }

  /**
   * Injects {@link #pendingSiiAuthorizationno} into the PATCH/POST response body so the
   * frontend sees the value that {@link #persistSiiAuthorizationno(NeoContext)} wrote to DB
   * after the main CRUD write captured its response (ETP-4783).
   *
   * <p>The main CRUD handler captures its JSON result in {@code context.getPreviousResult()}
   * before {@code afterHandle()} runs. {@code persistSiiAuthorizationno()} writes to DB in
   * {@code afterHandle()}, so the captured PATCH response body does not include the new value.
   * Without this injection the frontend would display a stale (empty) auth number.
   *
   * <p>No-op (returns {@code null}) when {@link #siiAuthorizationCaptured} is {@code false} or
   * the previous result cannot be parsed — callers fall back to the normal {@code null} return.
   *
   * @param context the current request context
   * @return a {@link NeoResponse} with the patched body, or {@code null} to fall through
   */
  protected NeoResponse injectAuthorizationnoIntoSaveResponse(NeoContext context) {
    if (!siiAuthorizationCaptured) {
      return null;
    }
    try {
      NeoResponse prev = context.getPreviousResult();
      if (prev == null || prev.getBody() == null) {
        return null;
      }
      // Defensive copy so we do not mutate the original captured result.
      JSONObject body = new JSONObject(prev.getBody().toString());
      // Try the standard JsonDataService wrapper: { "response": { "data": [{ ... }] } }
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper != null) {
        JSONArray data = responseWrapper.optJSONArray("data");
        if (data != null && data.length() > 0) {
          data.getJSONObject(0).put("aeatsiiAuthorizationno", pendingSiiAuthorizationno);
          return NeoResponse.ok(body);
        }
      }
      // Flat format fallback: the body itself is the record.
      body.put("aeatsiiAuthorizationno", pendingSiiAuthorizationno);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("[ETP-4783] injectAuthorizationnoIntoSaveResponse failed (non-fatal): {}", e.getMessage());
      return null;
    }
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
  /**
   * ETP-4783: Injects the synthetic {@code tbaiConfigActive} field into the GET response
   * so the header form's {@code displayLogic} for {@code tbaiReverseinvoicecode} can
   * evaluate it client-side.
   *
   * <p>Queries {@code tbai_config} for an active record in the same org as the invoice.
   * Injects {@code 'Y'} when found, {@code 'N'} otherwise. The backend ignores this
   * synthetic field in PATCH bodies ({@code NeoFieldFilter} drops unknown fields).
   */
  protected void enrichTbaiConfigActive(JSONObject rec) {
    String value = "N"; // default: TBAI not active
    try {
      String orgId = rec.isNull("organization") ? null : rec.optString("organization", null);
      if (orgId != null && !orgId.isEmpty()) {
        String sql = "SELECT 1 FROM tbai_config WHERE ad_org_id = ? AND isactive = 'Y' LIMIT 1";
        Connection conn = OBDal.getReadOnlyInstance().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.setString(1, orgId);
          try (ResultSet rs = ps.executeQuery()) {
            value = rs.next() ? "Y" : "N";
          }
        }
      }
    } catch (Exception e) {
      log.debug("[ETP-4783] enrichTbaiConfigActive: {}", e.getMessage());
    }
    try {
      rec.put("tbaiConfigActive", value);
    } catch (Exception ignored) {
      // best-effort: if rec.put fails the field remains absent;
      // displayLogicJs treats absent as shown (safe default for TBAI orgs)
    }
  }

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
      // ETP-4783: In Go, the Classic ETVFAC_C_INVOICE_SET_VERIFACTU callout is never triggered.
      // Copy DocType Verifactu fields to the invoice before completing so GenerateRFAfterProcessingHook
      // finds em_etvfac_inv_type / em_etvfac_verifac_desc populated (only for AR invoices; AP skipped
      // by the hook anyway). Also ensures em_etsg_date_operation is set when null.
      populateVerifactuFieldsFromDocType(invoiceId);
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
  // Pre-completion Verifactu field population
  // ---------------------------------------------------------------------------

  /**
   * ETP-4783: Copies Verifactu fields from the invoice's DocType to the invoice record itself
   * before the completion hook chain runs, replicating the behaviour of the Classic callout
   * {@code ETVFAC_C_INVOICE_SET_VERIFACTU} which Go never fires.
   *
   * <p>Only touches AR sales invoices where {@code em_etvfac_inv_type} is currently null.
   * Uses native SQL so the Go module compiles even when the Verifactu module is absent.
   * After the UPDATE the invoice is evicted from the Hibernate first-level cache so that
   * {@link ProcessInvoiceUtil} (called immediately after) reads the fresh DB values.
   *
   * <p>Fields populated (strictly from DocType — no fallback derivation):
   * <ul>
   *   <li>{@code em_etvfac_inv_type} — invoice type (e.g. F1, R1) — must be set on the DocType</li>
   *   <li>{@code em_etvfac_verifac_desc} — operation description — must be set on the DocType</li>
   *   <li>{@code em_etvfac_reverseinvtype} — rectification method I/S (DocType, for R-types)</li>
   *   <li>{@code em_etsg_date_operation} — defaults to {@code dateinvoiced} when null</li>
   * </ul>
   *
   * <p><b>Developer responsibility:</b> any DocType added to Go for AR invoices MUST have
   * {@code em_etvfac_inv_type} and {@code em_etvfac_verifac_desc} configured in its sampledata
   * (and {@code em_etvfac_reverseinvtype} for R-types). No automatic derivation is performed —
   * if those fields are absent the Verifactu hook will reject the invoice at completion.
   *
   * @param invoiceId the ID of the invoice being completed
   */
  @SuppressWarnings("java:S2077")
  private static void populateVerifactuFieldsFromDocType(String invoiceId) {
    String sql =
        "UPDATE c_invoice i"
        + "   SET em_etvfac_inv_type       = COALESCE(i.em_etvfac_inv_type,       dt.em_etvfac_inv_type),"
        + "       em_etvfac_verifac_desc   = COALESCE(i.em_etvfac_verifac_desc,   dt.em_etvfac_verifac_desc),"
        + "       em_etvfac_reverseinvtype = COALESCE(i.em_etvfac_reverseinvtype, dt.em_etvfac_reverseinvtype),"
        + "       em_etsg_date_operation   = COALESCE(i.em_etsg_date_operation,   i.dateinvoiced)"
        + "  FROM c_doctype dt"
        + " WHERE i.c_invoice_id   = ?"
        + "   AND dt.c_doctype_id  = i.c_doctypetarget_id"
        + "   AND i.issotrx        = 'Y'"
        + "   AND (i.em_etvfac_inv_type IS NULL OR i.em_etsg_date_operation IS NULL)";
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, invoiceId);
        int rows = ps.executeUpdate();
        if (rows > 0) {
          // Evict from Hibernate first-level cache so ProcessInvoiceUtil sees the updated values
          Invoice inv = OBDal.getInstance().getSession().get(Invoice.class, invoiceId);
          if (inv != null) {
            OBDal.getInstance().getSession().evict(inv);
          }
          log.debug("[INVOICE-COMPLETE] Populated Verifactu DocType fields for invoice {}", invoiceId);
        }
      }
    } catch (Exception e) {
      // Non-fatal: log and continue — if Verifactu is not installed the columns don't exist,
      // and the hook itself will skip processing (shouldSkipSendingToVerifactu returns true).
      log.debug("[INVOICE-COMPLETE] Could not populate Verifactu fields for invoice {}: {}",
          invoiceId, e.getMessage());
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
      applySiiAuthorizationCallout(fields.triggerField(), fields.requestBody(), fields.updates(), fields.body());
      applyVerifactuInvTypeFromDocType(fields.triggerField(), fields.requestBody(), fields.updates());
      realignVerifactuDescWithFormStateDocType(fields.triggerField(), fields.formState(), fields.updates());
    } catch (Exception e) {
      log.warn("[ETP-4029/ETP-4535] afterCallout failed (non-fatal): {}", e.getMessage());
    }
    return null; // mutations applied in-place; dispatcher merges nothing extra
  }

  /**
   * Replicates {@code SiiAuthorizationCallout} behavior for Go/NEO Headless (ETP-4783).
   *
   * <p>Classic Etendo fires the {@code SiiAuthorizationCallout} immediately when the user
   * toggles the {@code aeatsiiIsauthorization} checkbox on the invoice. In Go/NEO, that
   * Classic callout does not run; this hook restores the same field-update semantics:
   *
   * <ul>
   *   <li>If set to {@code Y}: looks up {@link AEATSIIConfig} for the current legal-entity org
   *       via {@link SIIUtils#getSiiConfigFromOrg(Organization)}. If no config or no
   *       authorization number is set, an ERROR message is appended to the callout response.
   *       Otherwise, {@code aeatsiiAuthorizationno} is injected into the {@code updates} map
   *       so the form field is populated immediately without a round-trip save.
   *   <li>If set to {@code N}: clears {@code aeatsiiAuthorizationno} by injecting an empty
   *       string into the {@code updates} map.
   * </ul>
   *
   * <p>No-op when the trigger field is not {@code aeatsiiIsauthorization}. All failures are
   * caught and logged as warnings (non-fatal) to match the Classic callout's error-handling.
   *
   * @param triggerField the name of the field that fired the callout
   * @param requestBody  the callout request body ({@code field}, {@code value}, {@code formState})
   * @param updates      the callout response's {@code updates} map; may be {@code null}
   * @param body         the full callout response body, used to append error messages
   */
  private static void applySiiAuthorizationCallout(String triggerField, JSONObject requestBody,
      JSONObject updates, JSONObject body) {
    if (!"aeatsiiIsauthorization".equals(triggerField)) {
      return;
    }
    try {
      Object rawValue = requestBody != null ? requestBody.opt("value") : null;
      String value = rawValue != null ? rawValue.toString() : "";
      boolean isAuthorization = "Y".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);

      if (updates == null) {
        // Cannot inject field updates without the updates section — skip silently.
        log.debug("[ETP-4783] applySiiAuthorizationCallout: no updates map, skipping injection");
        return;
      }

      if (isAuthorization) {
        Organization org = OBContext.getOBContext().getCurrentOrganization();
        if (org == null) {
          log.warn("[ETP-4783] applySiiAuthorizationCallout: no current organization in OBContext");
          return;
        }
        AEATSIIConfig config = SIIUtils.getSiiConfigFromOrg(org);
        if (config == null || StringUtils.isBlank(config.getAuthorizationno())) {
          // The classic SiiAuthorizationCallout (executed earlier via NeoCalloutEndpoint.executeCallout)
          // already appended this ERROR message to the response. Appending it here too would cause the
          // error to appear twice in the UI. Skip — no field update to inject either. (ETP-4783)
          return;
        }
        // Updates entries must be { "value": "..." } objects — see applyVerifactuInvTypeFromDocType.
        JSONObject authUpdate = new JSONObject();
        authUpdate.put("value", config.getAuthorizationno());
        updates.put("aeatsiiAuthorizationno", authUpdate);
      } else {
        JSONObject authClear = new JSONObject();
        authClear.put("value", "");
        updates.put("aeatsiiAuthorizationno", authClear);
      }
    } catch (Exception e) {
      log.warn("[ETP-4783] applySiiAuthorizationCallout failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * ETP-4783: After a non-DocType callout (e.g. {@code businessPartner}) the cascade may
   * fire the {@code transactionDocument} Classic callout (because the BP callout suggests a
   * different DocType) and inject {@code etvfacVerifacDesc} based on that suggested DocType.
   * The cascade runs BEFORE the after-callout hook, so by the time this method runs the
   * "wrong" description is already in {@code updates}.
   *
   * <p>This method corrects it by re-reading {@code em_etvfac_verifac_desc} (and
   * {@code em_etvfac_inv_type}) for the DocType the user actually selected, taken from
   * {@code formState.transactionDocument} (the live editing snapshot the frontend sends).
   *
   * <p>No-op when trigger IS {@code transactionDocument} (handled by
   * {@link #applyVerifactuInvTypeFromDocType}), or when no Verifactu field is present in
   * updates (the cascade did not fire for {@code transactionDocument} → nothing to fix).
   * All failures are caught and logged (non-fatal).
   *
   * @param triggerField the field that fired the callout
   * @param formState    the form state snapshot from the callout request
   * @param updates      the callout response's {@code updates} map; may be {@code null}
   */
  private static void realignVerifactuDescWithFormStateDocType(String triggerField,
      JSONObject formState, JSONObject updates) {
    if (FIELD_TRANSACTION_DOCUMENT.equals(triggerField)) {
      return; // already handled by applyVerifactuInvTypeFromDocType
    }
    if (updates == null || !updates.has("etvfacVerifacDesc")) {
      return; // cascade did not touch Verifactu description — nothing to realign
    }
    if (formState == null) {
      return;
    }
    String docTypeId = formState.optString(FIELD_TRANSACTION_DOCUMENT, "").trim();
    if (docTypeId.isEmpty()) {
      return;
    }
    try {
      String sql = "SELECT em_etvfac_verifac_desc, em_etvfac_inv_type"
          + " FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String desc    = rs.getString(1);
            String invType = rs.getString(2);
            if (desc != null && !desc.trim().isEmpty()) {
              JSONObject descUpdate = new JSONObject();
              descUpdate.put("value", desc.trim());
              updates.put("etvfacVerifacDesc", descUpdate);
            }
            // Also realign etvfacInvType if the cascade put a wrong value there
            if (invType != null && !invType.trim().isEmpty() && updates.has("etvfacInvType")) {
              JSONObject invUpdate = new JSONObject();
              invUpdate.put("value", invType.trim());
              updates.put("etvfacInvType", invUpdate);
            }
          }
        }
      }
      log.debug("[ETP-4783] realignVerifactuDescWithFormStateDocType: corrected Verifactu desc/inv for docType={} (trigger={})",
          docTypeId, triggerField);
    } catch (Exception e) {
      log.warn("[ETP-4783] realignVerifactuDescWithFormStateDocType failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * When the user changes the document type ({@code transactionDocument} trigger), injects the
   * new DocType's {@code em_etvfac_inv_type} value into the callout update map.
   *
   * <p>The Classic callout {@code ETVFAC_C_INVOICE_SET_VERIFACTU} propagates this field on
   * DocType change, but it does not run in GO/NEO Headless. This method replicates that behaviour
   * so {@code etvfacInvType} ("Tipo de Factura") updates when the user switches to a rectificative
   * document type (ETP-4783).
   *
   * <p>No-op when the trigger is not {@code transactionDocument} or the DocType has no Verifactu
   * invoice type configured. All failures are caught and logged as warnings (non-fatal).
   *
   * @param triggerField the field that fired the callout
   * @param requestBody  the callout request body ({@code field}, {@code value}, {@code formState})
   * @param updates      the callout response's {@code updates} map; may be {@code null}
   */
  private static void applyVerifactuInvTypeFromDocType(String triggerField, JSONObject requestBody,
      JSONObject updates) {
    if (!FIELD_TRANSACTION_DOCUMENT.equals(triggerField)) {
      return;
    }
    if (updates == null) {
      return;
    }
    try {
      Object rawValue = requestBody != null ? requestBody.opt("value") : null;
      String docTypeId = rawValue != null ? rawValue.toString().trim() : "";
      if (docTypeId.isEmpty()) {
        return;
      }
      String sql = "SELECT em_etvfac_inv_type FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String invType = rs.getString(1);
            if (invType != null && !invType.trim().isEmpty()) {
              // Updates entries must be { "value": "..." } objects, not raw strings —
              // the frontend's applyCalloutFieldUpdates reads entry.value; a raw string
              // would give entry.value === undefined and silently skip the update.
              JSONObject fieldUpdate = new JSONObject();
              fieldUpdate.put("value", invType.trim());
              updates.put("etvfacInvType", fieldUpdate);
              log.debug("[ETP-4783] applyVerifactuInvTypeFromDocType: injected etvfacInvType={} for docType={}",
                  invType.trim(), docTypeId);
            }
          }
        }
      }
    } catch (Exception e) {
      log.warn("[ETP-4783] applyVerifactuInvTypeFromDocType failed (non-fatal): {}", e.getMessage());
    }
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
