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
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

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
  // output as the first linked origin) — see InvoiceRectificationLinkSupport's
  // captureOriginInvoice/persistOriginInvoice/enrichOriginInvoice (java:S1448, ETP-5745).
  protected static final String FIELD_ORIGIN_INVOICES      = "originInvoices";
  /**
   * The reverse of {@link #FIELD_ORIGIN_INVOICES}: the rectificativa(s) that rectify THIS
   * invoice (ETP-5745 CP-12). {@code C_Invoice_Reverse} is read in the opposite direction here
   * ({@code reversed_c_invoice_id = invoiceId}, same join {@link InvoiceReactivationGuard} uses
   * for its own guard query) — without it, the "Rectificaciones" tab and the "Documentos
   * relacionados" card had no way to show, from the ORIGINAL invoice, which document(s) rectify
   * it: {@code InvoiceRectificationLinkSupport#enrichOriginInvoice} only ever answers "what does
   * THIS invoice rectify".
   */
  protected static final String FIELD_RECTIFYING_INVOICES  = "rectifyingInvoices";
  protected static final String FIELD_TRANSACTION_DOCUMENT = "transactionDocument";
  private static final String FIELD_CURRENCY = "currency";
  /** {@code C_BPartner_Location_ID}: the invoicing address. */
  static final String FIELD_PARTNER_ADDRESS = "partnerAddress";
  // Package-private: also used by InvoiceCalloutHelper (S1448 extraction)
  static final String FIELD_VALUE = "value";
  private static final String FIELD_PROCESSED = "processed";
  /** {@code C_Invoice.Posted} value of a posted (booked) invoice. */
  private static final String POSTED_YES = "Y";
  private static final String FIELD_TOTAL_DISCOUNT_PCT = "etgoTotalDiscount";
  static final String FIELD_AEATSII_IS_AUTHORIZATION = "aeatsiiIsauthorization";
  static final String FIELD_AEATSII_AUTHORIZATION_NO = "aeatsiiAuthorizationno";
  static final String FIELD_ETVFAC_INV_TYPE = "etvfacInvType";
  protected static final String FIELD_GRAND_TOTAL_AMOUNT = "grandTotalAmount";
  protected static final String FIELD_OUTSTANDING_AMOUNT = "outstandingAmount";

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
  // Follow-up document: goods shipment / goods receipt for the pending quantities (ETP-5576)
  // ---------------------------------------------------------------------------

  /**
   * Follow-up documents of this invoice entity (ETP-5576): the generic, identity-free
   * {@link FollowUpSupport}, fed by {@link #followUpFlows()}. Subclasses plug
   * {@code followUp.actionHandler()} into their dispatch chain and call
   * {@code followUp.annotate(dataArr)} on GET.
   */
  protected final FollowUpSupport followUp = new FollowUpSupport(this::followUpFlows);

  /**
   * The {@code C_Invoice_Reverse} origin/rectifying-invoice relationship (capture, persist, GET
   * enrichment) — extracted out of this class into its own collaborator (java:S1448, ETP-5745),
   * exactly like {@link #followUp}. One instance per handler; see
   * {@link InvoiceRectificationLinkSupport} for the capture/persist state-carrying rationale.
   */
  protected final InvoiceRectificationLinkSupport rectificationLinks = new InvoiceRectificationLinkSupport();

  /**
   * The keys this header adds to every GET record that are not spec fields: the follow-up
   * annotation ({@link FollowUpSupport#responseFields()}) and the invoice subtype
   * ({@link #getInvoiceSubtypeKey()}). The concrete handlers implement {@link NeoHandler}, so this
   * public method is their {@link NeoHandler#responseEnrichedFields()} (ETP-5576, MCP obs. 11).
   *
   * @return the injected response keys
   */
  public Set<String> responseEnrichedFields() {
    Set<String> keys = new HashSet<>(followUp.responseFields());
    keys.add(getInvoiceSubtypeKey());
    return keys;
  }

  /**
   * The follow-ups this invoice entity offers, in the order the UI should offer them — the ONE
   * binding point. Default: none. The sales handler registers the goods shipment, the purchase
   * handler the goods receipt — each an {@link InvoicePendingResolver} with
   * {@link #isStandardInvoiceDocType} as its eligibility classifier, composed with the shared
   * {@link InOutFollowUpCreator} — one {@link FollowUpFlow} line each.
   */
  protected List<FollowUpFlow> followUpFlows() {
    return Collections.emptyList();
  }

  /**
   * {@code true} when {@code docTypeId} is a standard invoice (FAC) for THIS handler's own
   * {@link #classifyDocType} — the rule behind {@code arInvoiceSubtype}/{@code apInvoiceSubtype},
   * so credit notes, returns and rectificatives never qualify.
   *
   * <p><b>Fails CLOSED</b>, unlike {@link #resolveSubtype}, which fails open to {@code FAC} for
   * the display annotation and is left as it is: this predicate gates a WRITE (creating a
   * follow-up document), so a blank id, an unknown id or a lookup error answers {@code false}
   * (ETP-5576 review W5). An error is logged, never swallowed silently.
   */
  protected boolean isStandardInvoiceDocType(String docTypeId) {
    if (StringUtils.isBlank(docTypeId)) {
      return false;
    }
    try {
      DocumentType dt = OBDal.getInstance().get(DocumentType.class, docTypeId);
      if (dt == null) {
        log.warn("Document type {} not found; not eligible for a follow-up document", docTypeId);
        return false;
      }
      return SUBTYPE_FAC.equals(classifyDocType(dt));
    } catch (Exception e) {
      log.error("Could not classify document type {}; not eligible for a follow-up document",
          docTypeId, e);
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  /**
   * The header fields this window can still change once the invoice is completed (ETP-5692) — the
   * one extension point of the invoice write guards ({@link InvoicePostingGate#checkHeaderRequest}).
   * The sales default; {@link PurchaseInvoiceHeaderHandler} adds {@code orderReference}. Both sets
   * are declared once, in {@link CompletedInvoiceWriteFence}.
   *
   * @return the allowlist the completed-invoice write fence applies to this header
   */
  protected Set<String> completedEditableHeaderFields() {
    return CompletedInvoiceWriteFence.headerEditableWhenCompleted();
  }

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
  // Origin invoice persistence — moved to InvoiceRectificationLinkSupport (java:S1448,
  // ETP-5745): captureOriginInvoice, persistOriginInvoice and their private helpers now live
  // on the rectificationLinks collaborator field above. Call them as
  // rectificationLinks.captureOriginInvoice(context) / rectificationLinks.persistOriginInvoice(context).
  // ---------------------------------------------------------------------------

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
    if (body == null || !body.has(FIELD_AEATSII_IS_AUTHORIZATION)) {
      return null;
    }
    try {
      Object rawValue = body.opt(FIELD_AEATSII_IS_AUTHORIZATION);
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
   * {@code rectificationLinks.persistOriginInvoice(context)}.
   *
   * @param context the current request context
   */
  protected void persistSiiAuthorizationno(NeoContext context) {
    if (!siiAuthorizationCaptured) {
      return;
    }
    try {
      String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
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
      // Try the standard JsonDataService wrapper (response → data array) first.
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper != null) {
        JSONArray data = responseWrapper.optJSONArray("data");
        if (data != null && data.length() > 0) {
          data.getJSONObject(0).put(FIELD_AEATSII_AUTHORIZATION_NO, pendingSiiAuthorizationno);
          return NeoResponse.ok(body);
        }
      }
      // Flat format fallback: the body itself is the record.
      body.put(FIELD_AEATSII_AUTHORIZATION_NO, pendingSiiAuthorizationno);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("[ETP-4783] injectAuthorizationnoIntoSaveResponse failed (non-fatal): {}", e.getMessage());
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // GET enrichment (virtual fields)
  // ---------------------------------------------------------------------------
  //
  // enrichOriginInvoice, enrichRectifyingInvoices and enrichHasRectifications moved to
  // InvoiceRectificationLinkSupport (java:S1448, ETP-5745) alongside the capture/persist methods
  // above — call them as rectificationLinks.enrichOriginInvoice(rec, invoiceId), etc.

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
      // best-effort: field stays absent on failure, which displayLogicJs treats as shown (safe default for TBAI orgs)
    }
  }

  /**
   * Response key of {@link InvoiceDetailEnrichment#enrichLinkedOrders}: the orders reached through
   * the invoice lines.
   */
  protected static final String FIELD_LINKED_ORDERS = "linkedOrders";

  /**
   * Distinct orders reached through the invoice's own lines ({@code C_InvoiceLine.C_OrderLine_ID}),
   * excluding the {@code ETGO_DTO} total-discount line. Bound twice: invoice id, discount product.
   */
  static final String LINKED_ORDERS_SQL =
      "SELECT o.c_order_id, o.documentno, o.docstatus, o.grandtotal, cur.iso_code, o.dateordered, "
          + "  dt.docsubtypeso "
          + "FROM c_order o "
          + "LEFT JOIN c_currency cur ON cur.c_currency_id = o.c_currency_id "
          + "LEFT JOIN c_doctype dt ON dt.c_doctype_id = o.c_doctypetarget_id "
          + "WHERE o.c_order_id IN ("
          + "  SELECT ol.c_order_id FROM c_invoiceline il "
          + "  JOIN c_orderline ol ON ol.c_orderline_id = il.c_orderline_id "
          + "  WHERE il.c_invoice_id = ? AND il.isactive = 'Y' "
          + "    AND (il.m_product_id IS NULL OR il.m_product_id <> ?)) "
          + "ORDER BY o.dateordered, o.documentno";

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

  /**
   * Runs the real core invoice-completion process when the request is a completion action
   * (documentAction=CO), short-circuiting NEO's default dispatch.
   *
   * <p>This is the header-handler entry point; the completion itself lives in
   * {@link InvoiceCompletionService}, which is shared with the handlers that auto-generate
   * invoices from orders, shipments, receipts, returns and quotations (ETP-5381). See that class
   * for the full rationale — why {@code ProcessInvoiceUtil} must be resolved through Weld, and the
   * session-lifecycle consequences of its internal commit.
   *
   * <p>Call this AFTER {@link #validateLineQtyBeforeComplete(NeoContext)} AND AFTER
   * {@link AbstractOrderHeaderHandler#applyTotalDiscountBeforeComplete} in {@code handle()}, so
   * (1) pre-completion validation can still block the request before the real process runs, and
   * (2) the total-discount line already reflects the final set of product lines before it is
   * read/posted by the completion process. Calling this BEFORE the discount recalculation would
   * complete the document with a stale or missing discount line (ETP-4388).
   *
   * @param context the current NeoContext
   * @return a {@link NeoResponse} translating the completion result, or {@code null} if this is
   *     not a completion request (caller should continue to the default dispatch)
   */
  protected static NeoResponse completeInvoiceIfNeeded(NeoContext context) {
    if (!InvoiceCalloutHelper.isInvoiceCompleteAction(context)) {
      return null;
    }
    // ETP-5745 CP-9: refuse completing a rectificativa that would push the total rectified
    // against its origin past the origin's own total. Checked here, before the real process
    // runs, mirroring where validateLineQtyBeforeComplete used to sit.
    NeoResponse rectificationBlock = RectificationTotalGuard.checkCompletion(context);
    if (rectificationBlock != null) {
      return rectificationBlock;
    }
    return InvoiceCompletionService.completeInvoice(context.getRecordId(), context.getObContext());
  }

  // ---------------------------------------------------------------------------
  // Pre-completion invoice line quantity validation — REMOVED (ETP-5381)
  // ---------------------------------------------------------------------------
  //
  // `validateLineQtyBeforeComplete` and `checkInoutEntryForOverInvoicing` used to cap each invoice
  // line at the pending quantity of the shipment/receipt line it pointed at, rejecting completion
  // with ETGO_InvoiceLineAlreadyInvoiced. It is gone, deliberately. Do not reinstate it.
  //
  // WHY: the shipment must not constrain what the invoice may say. The commitment is the ORDER.
  // The guard measured the invoice against the wrong document, and did so even when that document
  // had delivered nothing: `NeoInvoiceSupport.queryPendingQtyPerLine` takes its ceiling from
  // `ABS(sil.movementqty)` under `WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'` — no docstatus
  // filter on the shipment at all (every docstatus clause in that query filters INVOICES). So a
  // DRAFT shipment capped the invoice.
  //
  // The reported case: order for 15, shipment created from it and edited down to 10 but left in
  // draft, invoice created from the ORDER, reactivated and set to 12. Completion was refused with
  // "quantity to invoice (12) exceeds pending quantity (10). It may already be invoiced in another
  // document." Both halves of that sentence were false — 15 were pending on the order, and no other
  // invoice existed. `InvoiceLineLinker` had attached the invoice line to the draft shipment line
  // (its query matches on C_OrderLine_ID and does not look at the shipment's status either), which
  // is what handed the guard the wrong ceiling.
  //
  // WHAT STILL PROTECTS THIS: the core, in C_INVOICE_POST — for every invoice line carrying a
  // C_OrderLine_ID it computes `ABS(ol.qtyordered) - ABS(ol.qtyinvoiced + qty)` and raises
  // @QtyInvoicedHigherOrdered@ when it goes negative. That is the ceiling that belongs here, it is
  // measured against the order, and it is Classic's own behaviour.
  //
  // KNOWN GAP, accepted: an invoice line with no C_OrderLine_ID (invoiced straight from a shipment
  // that has no order behind it) now has no quantity ceiling at all, because the core check is
  // conditional on that column. Flagged rather than papered over.
  //
  // `NeoInvoiceSupport.computePendingQtyPerLine` itself stays — the billing-status badge and the
  // "no lines left to invoice" check at creation time still use it.

  // Package-private: also used by InvoiceCalloutHelper (S1448 extraction)
  static final String FIELD_DOCUMENT_ACTION_INV = "documentAction";

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

  // ---------------------------------------------------------------------------
  // Server-side defaults for a write that leaves a field out (ETP-5703)
  // ---------------------------------------------------------------------------

  /**
   * Fills {@code partnerAddress} (the invoicing address, {@code C_Invoice.C_BPartner_Location_ID},
   * mandatory) on a create that names a business partner but no address, when the partner has
   * exactly one active bill-to location (ETP-5703).
   *
   * <p><b>Why.</b> The SPA always sends the address it picked from the partner's locations, but an
   * agent creating an invoice with only {@code businessPartner} was refused with a 422 naming
   * {@code partnerAddress}, even for a partner with a single address: the business-partner callout
   * cannot select one through NEO (the {@code _LOC} selector aux value it reads does not exist —
   * see {@code McpBillToInjector}). Sales and purchase invoices alike.
   *
   * <p><b>Only when unambiguous.</b> With several bill-to locations the choice is the caller's —
   * a plausible wrong invoicing address is worse than a precise 422 — so this abstains, as it does
   * when the body already carries an address, when the partner is absent or still a batch
   * {@code $ref:}, or when the partner has none. Runs in the caller's own DAL scope: a location this
   * role cannot read never becomes the address of a document it writes.
   *
   * <p><b>Channels.</b> Called from each subclass's {@code handle()} pre-hook, which runs before
   * the create on REST single and REST batch / {@code etendo_batch} ({@code handleWithHooks}).
   * On MCP {@code etendo_create} the mandatory pre-check runs before this pre-hook (ETP-5535), so
   * the same rule is also run through this hook right before that pre-check (ETP-5710): the
   * default is identical on every channel, and with several (or no) bill-to locations it abstains
   * and the precise {@code missingFields} 422 stands.
   *
   * <p><b>ETP-5710 hook.</b> Deliberately NOT declared in {@link #serverResolvedCreateFields()}:
   * that would demote the field to optional in {@code etendo_schema}, yet with several bill-to
   * locations the default abstains and the field must still be reported missing. Overrides
   * {@code NeoHandler#resolveServerCreateFields} in the concrete invoice handlers.
   *
   * <p>Total: never throws.
   *
   * @param context the create request context; its request body is mutated in place
   */
  public void resolveServerCreateFields(NeoContext context) {
    // The invoicing address column's AD rule ("C_BPartner Location - Bill To") requires IsBillTo.
    PartnerLocationDefaults.fillIfMissing(context, FIELD_PARTNER_ADDRESS,
        Location.PROPERTY_INVOICETOADDRESS);
  }

  // ---------------------------------------------------------------------------
  // Unified date (ETP-4531) / re-revert to independent accounting date (ETP-5273)
  // ---------------------------------------------------------------------------
  //
  // The mirrorAccountingDateOnCreate(NeoContext, String, String) logic itself lives in
  // NeoHandlerUtils — shared with AbstractOrderHeaderHandler, GoodsReceiptHeaderHandler and
  // GoodsShipmentHeaderHandler — see NeoHandlerUtils#mirrorAccountingDateOnCreate. Call sites
  // here invoke it with ("invoiceDate", "accountingDate").

  static final String FIELD_INVOICE_DATE = "invoiceDate";
  static final String FIELD_ACCOUNTING_DATE = "accountingDate";
  /**
   * The operation date of {@code com.etendoerp.sif.general} ({@code EM_Etsg_Date_Operation}); only
   * mirrored when the {@code Invoice} entity declares it (the module is installed).
   */
  static final String FIELD_OPERATION_DATE = "etsgDateOperation";

  /**
   * ETP-5840 (QA CP-12b): the update counterpart of
   * {@link NeoHandlerUtils#mirrorAccountingDateOnCreate}, so an API update (REST single, batch, MCP
   * {@code etendo_update}) that changes the invoice date gets what the UI gets.
   *
   * <p>In the UI, changing the invoice date fires the classic callout registered on
   * {@code C_Invoice.DateInvoiced} — {@code SifInvoiceOperationDateCallout}, which extends
   * {@code SE_Invoice_AccountingDate} — and it overwrites the accounting date ({@code inpdateacct})
   * and the operation date ({@code inpemEtsgDateOperation}) with the new invoice date; the user may
   * then retype either before saving. An API update runs no interactive callout, so this replays
   * that rule: on a CRUD {@code PUT}/{@code PATCH} whose {@code invoiceDate} differs from the stored
   * one, it copies that date into {@code accountingDate} and {@code etsgDateOperation} — each only
   * when the body does not send its own non-blank value (the "user retyped it" case). An update
   * that does not change the invoice date mirrors nothing, so an independently edited accounting
   * date is never clobbered (ETP-5273).
   *
   * <p>Must run before the exchange-rate pre-hook ({@link DocumentRateGuards#guardInvoiceHeader}),
   * whose recalculation reads the accounting date from the body. Total: never throws.
   *
   * @param context the current request
   */
  protected void mirrorInvoiceDateOnUpdate(NeoContext context) {
    try {
      if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
          || !("PUT".equals(context.getHttpMethod()) || "PATCH".equals(context.getHttpMethod()))
          || StringUtils.isBlank(context.getRecordId()) || context.getRequestBody() == null) {
        return;
      }
      JSONObject body = context.getRequestBody();
      LocalDate newDate = DocumentCurrencyRate.parseDate(body.opt(FIELD_INVOICE_DATE));
      if (newDate == null) {
        return;
      }
      Invoice stored = OBDal.getInstance().get(Invoice.class, context.getRecordId());
      if (stored == null
          || newDate.equals(DocumentCurrencyRate.parseDate(stored.getInvoiceDate()))) {
        return;
      }
      mirrorDateIfNotSent(body, FIELD_ACCOUNTING_DATE);
      if (invoiceDeclares(FIELD_OPERATION_DATE)) {
        mirrorDateIfNotSent(body, FIELD_OPERATION_DATE);
      }
    } catch (Exception e) {
      log.warn("[ETP-5840] Could not mirror the invoice date of {}: {}",
          context != null ? context.getRecordId() : null, e.getMessage());
    }
  }

  /** Copies {@code invoiceDate} into {@code target} unless the body sends a non-blank one. */
  private static void mirrorDateIfNotSent(JSONObject body, String target) {
    Object sent = body.opt(target);
    boolean explicit = sent != null && !JSONObject.NULL.equals(sent)
        && StringUtils.isNotBlank(String.valueOf(sent));
    if (!explicit) {
      NeoHandlerUtils.mirrorFieldValue(body, FIELD_INVOICE_DATE, target);
    }
  }

  /** Whether the {@code Invoice} entity declares {@code property} (an optional module's column). */
  static boolean invoiceDeclares(String property) {
    try {
      return ModelProvider.getInstance().getEntity(Invoice.ENTITY_NAME)
          .hasProperty(property);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Shared {@code afterCallout} body: blocks callout-driven currency updates (ETP-4029); returns
   * the recalculated exchange rate when the currency or the accounting date changes, including an
   * accounting date the classic callout copied from a new invoice date (ETP-5840, see
   * {@link DocumentCurrencyRate#applyToCallout}); and
   * blocks callout-driven document type updates on an already-saved invoice (ETP-4535).
   *
   * <p>Note that {@code accountingDate} is deliberately NOT guarded here — see the comment at the
   * call site for why matching Classic's one-way {@code DateInvoiced -> DateAcct} cascade is the
   * intended behaviour (ETP-5273).
   *
   * <p>Identical for both {@link PurchaseInvoiceHeaderHandler} and {@link SalesInvoiceHeaderHandler}
   * — each subclass's {@code afterCallout()} override should just delegate here.
   */
  protected NeoResponse handleInvoiceAfterCallout(NeoContext context,
      DocumentCurrencyRate currencyRate) {
    try {
      NeoHandlerUtils.CalloutFields fields = NeoHandlerUtils.extractCalloutFields(context);
      if (fields == null) {
        return null;
      }
      blockCalloutCurrencyUpdate(fields.updates(), fields.triggerField());
      // ETP-5273: accountingDate is deliberately NOT guarded here. Classic propagates
      // DateInvoiced -> DateAcct one way (SE_Invoice_AccountingDate, reached through
      // SifInvoiceOperationDateCallout on C_Invoice.DateInvoiced), and this window must
      // match that. The reverse cascade needs no guard either: the callout registered on
      // DateAcct is SE_Invoice_TaxDate, which writes Taxdate only and never touches
      // DateInvoiced. Independent editing is preserved by restricting the server-side
      // mirror to creation (NeoHandlerUtils#mirrorAccountingDateOnCreate), not by
      // stripping the cascade.
      String recordId = InvoiceCalloutHelper.resolveCalloutRecordId(context, fields.formState());
      // ETP-5840: the recalculated rate for the accounting date — also when the classic callout
      // copied a new invoice date into it — returned in updates.eTGOCurrencyRate.
      currencyRate.applyToCallout(fields, recordId);
      blockCalloutDocTypeUpdateIfLocked(fields.updates(), fields.triggerField(), recordId);
      InvoiceCalloutHelper.applySiiAuthorizationCallout(fields.triggerField(), fields.requestBody(), fields.updates());
      InvoiceCalloutHelper.applyVerifactuInvTypeFromDocType(fields.triggerField(), fields.requestBody(), fields.updates());
      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(fields.triggerField(), fields.requestBody(), fields.updates());
      InvoiceCalloutHelper.realignVerifactuDescWithFormStateDocType(fields.triggerField(), fields.formState(), fields.updates());
    } catch (Exception e) {
      log.warn("[ETP-4029/ETP-5273/ETP-4535] afterCallout failed (non-fatal): {}", e.getMessage());
    }
    return null; // mutations applied in-place; dispatcher merges nothing extra
  }


  /**
   * Upserts the {@code C_Conversion_Rate_Document} record for an invoice whenever its currency
   * differs from the org currency. Runs as a continuous upsert on every successful header save
   * (PATCH/PUT/POST) — not only when {@code currency}/{@code eTGOCurrencyRate} appears in the
   * request body — so that {@code foreignAmount} stays in sync as {@code grandTotalAmount}
   * evolves while lines are added/edited after the currency was first set.
   *
   * <p>No-op when the invoice has no currency. When the invoice has no {@code EM_ETGO_Currency_Rate},
   * its rows are deleted only when this header write left the rate empty
   * ({@link DocumentRateGuards#invoiceHeaderWriteClearedRate}, ETP-5840); otherwise nothing is done. When the invoice
   * currency equals the org currency, any
   * conversion-rate row left over from an earlier foreign-currency state is deleted instead
   * (ETP-4836) — switching back to the org currency must clear the tab, not leave it stale.
   *
   * <p>ETP-5547: only runs for the requests {@link #shouldSyncConversionRateDocument(NeoContext)}
   * accepts. Action POSTs on a processed invoice ({@code registerPayment}, {@code cloneRecord},
   * SII/TBAI, post/unpost…) change neither the invoice's rate nor its total, and used to rewrite
   * the SOURCE invoice's rate row anyway — on a posted invoice that UPDATE was rejected by Core
   * and aborted the whole request transaction.
   *
   * <p>Call unconditionally at the top of each subclass's {@code afterHandle()}, alongside
   * any other per-save hooks, before their own method-gated (e.g. GET-only) logic.
   *
   * <p>Resolves the invoice ID from the header {@code NeoContext} (via
   * {@link InvoiceCalloutHelper#resolveInvoiceIdFromContext(NeoContext)}) and delegates to
   * {@link #autoCreateOrUpdateConversionRateDocument(String)}. Handlers that are NOT scoped to
   * the invoice header entity (e.g. {@link InvoiceLineHandler}, whose {@code NeoContext} carries
   * the line's own record ID, not the invoice's) must resolve the parent invoice ID themselves
   * and call the {@code String}-based overload directly instead.
   *
   * @param context the current NeoContext; only {@code getHttpMethod()}/{@code getEndpointType()}/
   *                {@code getFieldName()}/{@code getRequestBody()}/{@code getRecordId()}/
   *                {@code getPreviousResult()} are used
   */
  protected static void autoCreateOrUpdateConversionRateDocument(NeoContext context) {
    if (!shouldSyncConversionRateDocument(context)) {
      return;
    }
    String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
    autoCreateOrUpdateConversionRateDocument(invoiceId,
        DocumentRateGuards.invoiceHeaderWriteClearedRate(context));
  }

  /**
   * Decides whether a header request may have changed the invoice's rate or grand total, and so
   * whether the {@code C_Conversion_Rate_Document} row must be re-synced (ETP-5547).
   *
   * <ul>
   *   <li>Non-write methods (GET, DELETE) → never.</li>
   *   <li>{@link NeoEndpointType#CRUD} POST/PUT/PATCH → always: this is where the rate, the
   *       currency and the header fields are edited.</li>
   *   <li>{@link NeoEndpointType#ACTION} → only the "Complete" document action (it recalculates
   *       the total-discount line right before completing, see
   *       {@link AbstractOrderHeaderHandler#applyTotalDiscountBeforeComplete}) and actions run on
   *       a still-draft invoice (the generic AD processes {@code createLinesFrom*},
   *       {@code copyFrom}, {@code calculatePromotions}, {@code explode}… add or reprice lines,
   *       which moves the grand total). Every custom action on a processed invoice
   *       ({@code registerPayment}, {@code cloneRecord}, {@code aeatsiiSend},
   *       {@code tbaiXmlgenerator}, {@code createShipment}, {@code post}/{@code unpost},
   *       {@code currencyOptions}) leaves both untouched and is skipped.</li>
   *   <li>Any other endpoint type (SELECTOR, CALLOUT, DEFAULTS…) → never.</li>
   * </ul>
   *
   * @param context the current NeoContext
   * @return {@code true} when the rate row must be re-synced for this request
   */
  static boolean shouldSyncConversionRateDocument(NeoContext context) {
    if (context == null || !NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      return false;
    }
    NeoEndpointType endpointType = context.getEndpointType();
    if (NeoEndpointType.CRUD.equals(endpointType)) {
      return true;
    }
    if (!NeoEndpointType.ACTION.equals(endpointType)) {
      return false;
    }
    return InvoiceCalloutHelper.isInvoiceCompleteAction(context)
        || isDraftInvoice(context.getRecordId());
  }

  /**
   * {@code true} when the invoice exists and is not processed yet. Never throws: it runs from
   * {@code afterHandle()} outside the sync's own try/catch, so a failed read (e.g. on a transaction
   * the action already aborted) answers "not a draft" — skip the sync — instead of failing the
   * post-hook.
   */
  private static boolean isDraftInvoice(String invoiceId) {
    if (StringUtils.isBlank(invoiceId)) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      return invoice != null && !Boolean.TRUE.equals(invoice.isProcessed());
    } catch (Exception e) {
      log.debug("[ETP-5547] Could not read invoice {} to decide the rate-doc sync: {}",
          invoiceId, e.getMessage());
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Upserts the {@code C_Conversion_Rate_Document} record for the invoice identified by
   * {@code invoiceId}, whenever its currency differs from the org currency. Same business rule
   * and no-op conditions as {@link #autoCreateOrUpdateConversionRateDocument(NeoContext)} — this
   * is the shared core both overloads (and {@link InvoiceLineHandler}) funnel into, so there is
   * exactly one upsert implementation for {@code C_Conversion_Rate_Document}.
   *
   * <p>ETP-5547: no-op when the invoice is posted ({@code Posted = 'Y'}). Core's
   * {@code c_conversion_rate_document_trg} rejects any INSERT/UPDATE/DELETE of the rate row of a
   * posted invoice with {@code @20501@} — the rate a document was booked with is immutable — so
   * there is nothing this method may legitimately do there. The write itself also runs under a
   * savepoint ({@link ConversionRateDocumentSync}), so any other failure is contained instead of
   * aborting the caller's transaction.
   *
   * @param invoiceId the invoice's primary key, already resolved by the caller; no-op if blank
   */
  protected static void autoCreateOrUpdateConversionRateDocument(String invoiceId) {
    autoCreateOrUpdateConversionRateDocument(invoiceId, false);
  }

  /**
   * {@link #autoCreateOrUpdateConversionRateDocument(String)}, deciding what a null header rate
   * means (ETP-5840): with {@code rateCleared} — the current header write, or the generation flow
   * that just recomputed the rate, left it null — the invoice's rate rows are deleted so the tab
   * mirrors the header; otherwise (a line save, a write that did not touch the rate) a null rate
   * leaves the rows alone, as before.
   *
   * @param invoiceId   the invoice's primary key; no-op if blank
   * @param rateCleared whether the caller itself left the header rate null
   */
  @SuppressWarnings("java:S2077")
  protected static void autoCreateOrUpdateConversionRateDocument(String invoiceId,
      boolean rateCleared) {
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
        if (POSTED_YES.equals(invoice.getPosted())) {
          // ETP-5547: a posted invoice's rate row is immutable (Core trigger, @20501@).
          log.debug("[ETP-5547] Skipping C_Conversion_Rate_Document sync for posted invoice {}",
              invoiceId);
          return;
        }
        String orgId = invoice.getOrganization().getId();
        String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
        if (orgCurrencyId == null) {
          return; // org currency unresolved, nothing to do
        }
        if (orgCurrencyId.equals(invoice.getCurrency().getId())) {
          // Doc currency now matches org — any conversion-rate row left over from an earlier
          // foreign-currency state is stale and must be cleared, not silently ignored (ETP-4836).
          ConversionRateDocumentSync.deleteAllForInvoice(invoice.getId(), orgCurrencyId);
          return;
        }
        BigDecimal rate = invoice.getETGOCurrencyRate();
        if (rate == null || rate.signum() <= 0) {
          // ETP-5840: when THIS write (or generation flow) left the header rate empty, the tab
          // follows it — a row from an earlier rate is stale and must not survive. Never from a
          // line save or a write that did not touch the rate: there a null rate means "no override
          // set yet", as before.
          if (rateCleared) {
            ConversionRateDocumentSync.deleteAllForInvoice(invoice.getId(), orgCurrencyId);
          }
          return;
        }

        // EM_ETGO_Currency_Rate is the org→doc multiplyRate (mirrors C_Order semantics).
        // C_Conversion_Rate_Document.rate is the doc→org multiplier: amount_doc × docRate = amount_org.
        BigDecimal docRate = BigDecimal.ONE.divide(rate, 12, RoundingMode.HALF_UP);
        BigDecimal grandTotal = invoice.getGrandTotalAmount();
        BigDecimal foreignAmount = grandTotal != null
            ? grandTotal.multiply(docRate).setScale(2, RoundingMode.HALF_UP)
            : null;

        ConversionRateDocumentSync.upsert(invoice, orgCurrencyId, docRate, foreignAmount);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-4029] autoCreateOrUpdateConversionRateDocument failed for invoice {}: {}",
          invoiceId, e.getMessage());
    }
  }
}
