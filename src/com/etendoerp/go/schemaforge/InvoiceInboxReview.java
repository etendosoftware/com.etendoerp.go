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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CANDIDATES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CHECK;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RECEIVER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VALIDATION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VERSION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NONE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.TOTAL_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.VERSION;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.InboxTaxModes;
import com.etendoerp.go.schemaforge.ocr.OcrConfig;
import com.etendoerp.go.schemaforge.ocr.OcrDocType;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * ETP-6050 — the {@code review} action of the invoice inbox: {@code GET
 * /sws/neo/invoice-inbox/header/{id}/action/review} answers everything the review panel needs to
 * open one document in a single call: the state of the row, the stored {@code CURRENT_DATA} with its
 * verdict recomputed, the candidates enriched for display, the main attachment and the currency.
 *
 * <p>Nothing here writes. The verdict ({@code validation}) is recomputed with
 * {@link InvoiceInboxValidator#validate} on every read, so a row stored before a rule changed never
 * shows a stale one, and the enrichment (city, tax id, search key, category, unit) is added to the
 * answer only, never to the stored document: it would go stale, and the worker's contract keeps
 * ids and names only. A row that carries no {@code CURRENT_DATA} ({@code UNREADABLE},
 * {@code SERVICE_FAILURE}: the worker only fails them) is answered with an empty version-1
 * skeleton, not persisted. Texts of the document are untrusted data and are only copied.</p>
 *
 * <p>The tenant is judged twice: {@code NeoActionRecordGuard} refuses another tenant's id (404)
 * before the handler runs, and the row is read through {@link TenantOwnership}. Stateless; called
 * by {@link InvoiceInboxHeaderHandler}.</p>
 */
final class InvoiceInboxReview {

  static final String ACTION = "review";
  static final String CODE_NOT_FOUND = "not_found";
  static final String CODE_PROCESSING = "processing";

  /** The states a document can be edited in ({@code apply} and {@code mark-reviewed} judge it too). */
  private static final Set<String> EDITABLE = Set.of("PENDING_REVIEW", "ERROR", "READY");
  private static final String STATUS_PROCESSING = "PROCESSING";
  private static final String TABLE = "ETGO_Incoming_Document";
  private static final String DEFAULT_MIME = "application/octet-stream";
  private static final String K_DETAILS = "details";
  private static final String K_CITY = "city";
  private static final String K_TAX_ID = "taxId";
  private static final String K_NAME = "name";

  private static final Logger log = LogManager.getLogger(InvoiceInboxReview.class);

  private InvoiceInboxReview() {
  }

  /**
   * Builds the review answer.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @return 200 with the review document; 404 {@code not_found}; 409 {@code processing}
   */
  static NeoResponse review(String recordId) {
    IncomingDocument row = TenantOwnership.loadOwned(IncomingDocument.class, recordId);
    if (row == null) {
      return refuse(404, CODE_NOT_FOUND, InvoiceInboxMessages.NOT_FOUND);
    }
    if (STATUS_PROCESSING.equals(row.getStatus())) {
      return refuse(409, CODE_PROCESSING, InvoiceInboxMessages.PROCESSING);
    }
    try {
      return NeoResponse.ok(answer(row));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the review answer", e);
    }
  }

  /**
   * Whether a document in this state can be edited: waiting for the user and not addressed to
   * another company. The one rule of the {@code editable} flag of the answer and of the refusals of
   * {@code apply} and {@code mark-reviewed}.
   *
   * @param status    the row status
   * @param errorCode the row error code, may be {@code null}
   * @return {@code true} when the document is editable
   */
  static boolean isEditable(String status, String errorCode) {
    return status != null && EDITABLE.contains(status) && !InvoiceInboxValidator.FOREIGN_RECEIVER.equals(errorCode);
  }

  /**
   * The review answer of a row already loaded (what {@code apply} and {@code mark-reviewed} answer
   * after saving).
   *
   * @param row the inbox row
   * @return 200 with the review document
   */
  static NeoResponse respond(IncomingDocument row) {
    try {
      return NeoResponse.ok(answer(row));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the review answer", e);
    }
  }

  /**
   * The grand total of the linked invoice read now (so rows confirmed before the list column was
   * kept equal to it also answer it), {@code null} when the engine has not set one.
   */
  private static Object grandTotal(Invoice invoice) {
    return invoice.getGrandTotalAmount() == null ? JSONObject.NULL : invoice.getGrandTotalAmount();
  }

  private static JSONObject answer(IncomingDocument row) throws JSONException {
    String status = row.getStatus();
    String errorCode = row.getErrorCode();
    Invoice invoice = row.getInvoice();
    JSONObject out = new JSONObject();
    out.put("id", row.getId());
    out.put("status", status);
    out.put("errorCode", errorCode == null ? JSONObject.NULL : errorCode);
    if (!StringUtils.isBlank(row.getErrorDetail())) {
      out.put("errorDetail", row.getErrorDetail());
      Optional<InvoiceInboxFailureDetail.Detail> info =
          InvoiceInboxFailureDetail.parse(row.getErrorDetail());
      if (info.isPresent()) {
        out.put("errorInfo", new JSONObject().put("code", info.get().code())
            .put("params", info.get().paramsJson()));
      }
    }
    out.put("reviewed", Boolean.TRUE.equals(row.isReviewed()));
    out.put("adjusted", Boolean.TRUE.equals(row.isAdjusted()));
    out.put("updated", orNull(NeoDateFormat.toAuditToken(row.getUpdated())));
    out.put("editable", isEditable(status, errorCode));
    out.put("fileName", StringUtils.defaultString(row.getFileName()));
    out.put("sender", StringUtils.defaultString(row.getSender()));
    out.put("source", StringUtils.defaultString(row.getSource()));
    out.put("created", orNull(NeoDateFormat.toAuditToken(row.getCreationDate())));
    out.put("invoice", invoice == null ? JSONObject.NULL
        : new JSONObject().put("id", invoice.getId())
            .put("documentNo", StringUtils.defaultString(invoice.getDocumentNo()))
            .put("identifier", invoice.getIdentifier())
            .put("grandTotal", grandTotal(invoice)));
    out.put("attachment", attachment(row.getId()));
    JSONObject document = document(row);
    out.put("currency", currency(row, document));
    out.put("totalMismatch", InvoiceInboxSummary.totalMismatch(document,
        InvoiceInboxLiveChecks.documentCurrency(document, row)));
    out.put("document", document);
    return out;
  }

  private static Object orNull(String value) {
    return value == null ? JSONObject.NULL : value;
  }

  /** The main attachment of the row, {@code null} when none or not visible to the tenant. */
  private static Object attachment(String recordId) throws JSONException {
    try {
      Attachment main = NeoAttachmentsHelper.findMainAttachment(
          NeoAttachmentsHelper.resolveTableId(TABLE), recordId);
      if (main == null || !TenantOwnership.isVisibleToCurrentTenant(main)) {
        return JSONObject.NULL;
      }
      return new JSONObject().put("id", main.getId())
          .put("name", StringUtils.defaultString(main.getName()))
          .put("mimeType", mimeType(main));
    } catch (RuntimeException e) {
      log.warn("Invoice inbox review: main attachment lookup failed: {}",
          e.getClass().getSimpleName());
      return JSONObject.NULL;
    }
  }

  /** The stored data type when it is a MIME type, else the one the file extension implies. */
  private static String mimeType(Attachment attachment) {
    String dataType = StringUtils.trimToEmpty(attachment.getDataType());
    if (dataType.contains("/")) {
      return dataType;
    }
    String name = StringUtils.defaultString(attachment.getName()).toLowerCase(Locale.ROOT);
    if (name.endsWith(".pdf")) {
      return "application/pdf";
    }
    if (name.endsWith(".png")) {
      return "image/png";
    }
    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    return DEFAULT_MIME;
  }

  /**
   * The currency of the document with the decimals of its amounts ({@code scale}): the one stored in
   * the header, else derived now from the vendor (its purchase price list currency) or the
   * organization, without writing; the organization's, then the client's, when the lookups are not
   * available. Nulls when none can be established; {@code scale} is then 2.
   */
  private static JSONObject currency(IncomingDocument row, JSONObject document)
      throws JSONException {
    Optional<OcrLookups.DocCurrency> found = lookedUpCurrency(row, document);
    if (found.isEmpty()) {
      return organizationCurrency(row.getOrganization());
    }
    Object symbol = found.get().symbol() == null ? JSONObject.NULL : found.get().symbol();
    return new JSONObject().put("id", found.get().id()).put("isoCode", found.get().isoCode())
        .put("symbol", symbol).put("scale", found.get().scale());
  }

  /** The stored currency, else the one derived now; empty when unknown or the lookup failed. */
  private static Optional<OcrLookups.DocCurrency> lookedUpCurrency(IncomingDocument row,
      JSONObject document) {
    Optional<OcrLookups> lookups = lookupsFor(row.getTargetType());
    if (lookups.isEmpty()) {
      return Optional.empty();
    }
    try {
      JSONObject header = document.optJSONObject(K_HEADER);
      String stored = header == null ? "" : header.optString(K_CURRENCY_ID, "");
      if (!stored.isEmpty()) {
        return lookups.get().currencyById(stored);
      }
      String organizationId = row.getOrganization() == null ? null
          : row.getOrganization().getId();
      return lookups.get().documentCurrency(linkedVendorId(header), organizationId);
    } catch (RuntimeException e) {
      log.warn("Invoice inbox review: currency lookup failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static String linkedVendorId(JSONObject header) {
    JSONObject vendor = header == null ? null : header.optJSONObject(K_VENDOR);
    return vendor != null && S_LINKED.equals(vendor.optString(K_STATE))
        ? vendor.optString(K_ID) : null;
  }

  /** The organization's currency, the client's when the organization has none; nulls when neither. */
  private static JSONObject organizationCurrency(Organization organization) throws JSONException {
    Currency currency = organization == null ? null : organization.getCurrency();
    if (currency == null) {
      Client client = OBContext.getOBContext().getCurrentClient();
      currency = client == null ? null : client.getCurrency();
    }
    return new JSONObject()
        .put("id", currency == null ? JSONObject.NULL : currency.getId())
        .put("isoCode", currency == null ? JSONObject.NULL : currency.getISOCode())
        .put("symbol", currency == null ? JSONObject.NULL : currency.getSymbol())
        .put("scale", 2);
  }

  /** The stored document (or the skeleton) with the verdict recomputed and the read-time enrichment. */
  private static JSONObject document(IncomingDocument row) throws JSONException {
    JSONObject document = parse(row.getCurrentData());
    if (document == null) {
      document = skeleton();
    }
    document.put(K_VALIDATION, InvoiceInboxLiveChecks.verdict(document, row).toJson());
    enrich(document, lookupsFor(row.getTargetType()));
    return document;
  }

  private static JSONObject parse(String stored) {
    if (StringUtils.isBlank(stored)) {
      return null;
    }
    try {
      return new JSONObject(stored);
    } catch (JSONException e) {
      log.warn("Invoice inbox review: CURRENT_DATA is not valid JSON, answering the skeleton");
      return null;
    }
  }

  /** The empty version-1 document of a row the worker could not read. */
  static JSONObject skeleton() throws JSONException {
    JSONObject vendor = new JSONObject().put(K_STATE, S_NONE).put(K_CANDIDATES, new JSONArray())
        .put(K_EXTRACTED, new JSONObject());
    JSONObject duplicate = new JSONObject().put(K_STATE, D_UNKNOWN)
        .put(K_INVOICES, new JSONArray()).put(K_INBOX, new JSONArray());
    JSONObject header = new JSONObject().put(K_VENDOR, vendor).put(K_DOCUMENT_NO, "")
        .put(K_INVOICE_DATE, JSONObject.NULL).put(K_TOTAL_AMOUNT, 0)
        .put(K_TOTAL_SOURCE, TOTAL_LINES).put(K_DUPLICATE, duplicate)
        .put(K_RECEIVER, new JSONObject().put(K_CHECK, "absent"));
    return new JSONObject().put(K_VERSION, VERSION).put(K_HEADER, header)
        .put(K_LINES, new JSONArray()).put(K_NOTICES, new JSONArray());
  }

  static Optional<OcrLookups> lookupsFor(String targetType) {
    String spec = targetType == null ? null : InvoiceInboxProcessor.SPEC_BY_TARGET.get(targetType);
    Optional<OcrDocType> type = spec == null ? Optional.empty() : OcrConfig.get().forSpec(spec);
    return type.flatMap(t -> OcrLookupsRegistry.forResolver(t.getResolver()));
  }

  // ── Read-time enrichment ─────────────────────────────────────────────────────────────────────

  private static void enrich(JSONObject document, Optional<OcrLookups> lookups)
      throws JSONException {
    if (lookups.isEmpty()) {
      return;
    }
    try {
      enrichVendor(document.optJSONObject(K_HEADER), lookups.get());
      enrichProducts(document.optJSONArray(K_LINES), lookups.get());
      enrichTaxes(document.optJSONArray(K_LINES), lookups.get());
      InboxTaxModes.stamp(document.optJSONArray(K_LINES), lookups.get());
    } catch (RuntimeException e) {
      // The lookups are total; this is a belt for the document walk. The panel still opens.
      log.warn("Invoice inbox review: enrichment failed: {}", e.getClass().getSimpleName());
    }
  }

  private static void enrichVendor(JSONObject header, OcrLookups lookups) throws JSONException {
    JSONObject vendor = header == null ? null : header.optJSONObject(K_VENDOR);
    if (vendor == null) {
      return;
    }
    List<String> ids = new ArrayList<>();
    if (S_LINKED.equals(vendor.optString(K_STATE))) {
      ids.add(vendor.optString(K_ID));
    }
    JSONArray candidates = vendor.optJSONArray(K_CANDIDATES);
    collectIds(candidates, ids);
    Map<String, OcrLookups.VendorDetails> details = lookups.vendorDetails(ids);
    if (S_LINKED.equals(vendor.optString(K_STATE))) {
      OcrLookups.VendorDetails own = details.get(vendor.optString(K_ID));
      if (own != null) {
        vendor.put(K_DETAILS, new JSONObject().put(K_NAME, own.name()).put(K_TAX_ID, own.taxId())
            .put(K_CITY, own.city()));
      }
    }
    for (int i = 0; candidates != null && i < candidates.length(); i++) {
      JSONObject candidate = candidates.optJSONObject(i);
      OcrLookups.VendorDetails found = candidate == null ? null
          : details.get(candidate.optString(K_ID));
      if (found != null) {
        candidate.put(K_CITY, found.city());
      }
    }
  }

  private static void enrichProducts(JSONArray lines, OcrLookups lookups) throws JSONException {
    List<String> ids = new ArrayList<>();
    for (int i = 0; lines != null && i < lines.length(); i++) {
      JSONObject product = productOf(lines.optJSONObject(i));
      if (product == null) {
        continue;
      }
      if (S_LINKED.equals(product.optString(K_STATE))) {
        ids.add(product.optString(K_ID));
      }
      collectIds(product.optJSONArray(K_CANDIDATES), ids);
    }
    Map<String, OcrLookups.ProductDetails> details = lookups.productDetails(ids);
    for (int i = 0; lines != null && i < lines.length(); i++) {
      JSONObject product = productOf(lines.optJSONObject(i));
      if (product != null) {
        applyProductDetails(product, details);
        applyNewProductNames(product, lookups);
      }
    }
  }

  /**
   * The names of the category and the unit of a product to create, from the ids the document
   * stores (ETP-6050): resolved here, in the language of the request, never stored, so they always
   * follow the UI language. A name stored by an earlier version is replaced; an id that no longer
   * resolves for the session (inactive, another tenant) leaves no name.
   */
  private static void applyNewProductNames(JSONObject product, OcrLookups lookups)
      throws JSONException {
    JSONObject draft = product.optJSONObject(K_NEW);
    if (draft == null) {
      return;
    }
    putName(draft, K_CATEGORY, lookups.productCategoryById(draft.optString(K_CATEGORY_ID, "")));
    putName(draft, K_UOM, lookups.uomById(draft.optString(K_UOM_ID, "")));
  }

  private static void putName(JSONObject draft, String key, Optional<OcrLookups.Party> found)
      throws JSONException {
    draft.remove(key);
    if (found.isPresent() && !StringUtils.isBlank(found.get().name())) {
      draft.put(key, found.get().name());
    }
  }

  /**
   * The name of the tax of each line, in the language of the request (ETP-6050): resolved here from
   * the id, never stored. A tax whose id does not resolve keeps what the document holds.
   */
  private static void enrichTaxes(JSONArray lines, OcrLookups lookups) throws JSONException {
    List<String> ids = new ArrayList<>();
    for (int i = 0; lines != null && i < lines.length(); i++) {
      String id = taxIdOf(lines.optJSONObject(i));
      if (id != null) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Map<String, String> names = lookups.taxNames(ids);
    for (int i = 0; i < lines.length(); i++) {
      String name = names.get(taxIdOf(lines.optJSONObject(i)));
      if (name != null) {
        lines.optJSONObject(i).getJSONObject(K_TAX).put(K_NAME, name);
      }
    }
  }

  /** The id of the line's tax, {@code null} when the line has no tax block or the block no id. */
  private static String taxIdOf(JSONObject line) {
    JSONObject tax = line == null ? null : line.optJSONObject(K_TAX);
    String id = tax == null ? "" : tax.optString(K_ID);
    return StringUtils.isBlank(id) ? null : id;
  }

  private static JSONObject productOf(JSONObject line) {
    return line == null ? null : line.optJSONObject(K_PRODUCT);
  }

  private static void applyProductDetails(JSONObject product,
      Map<String, OcrLookups.ProductDetails> details) throws JSONException {
    if (S_LINKED.equals(product.optString(K_STATE))) {
      OcrLookups.ProductDetails own = details.get(product.optString(K_ID));
      if (own != null) {
        product.put(K_DETAILS, productJson(own, false));
      }
    }
    JSONArray candidates = product.optJSONArray(K_CANDIDATES);
    for (int i = 0; candidates != null && i < candidates.length(); i++) {
      JSONObject candidate = candidates.optJSONObject(i);
      OcrLookups.ProductDetails found = candidate == null ? null
          : details.get(candidate.optString(K_ID));
      if (found != null) {
        JSONObject extra = productJson(found, true);
        for (String key : new String[] { "searchKey", "category", "uom" }) {
          candidate.put(key, extra.get(key));
        }
        if (!StringUtils.isBlank(found.name())) {
          candidate.put(K_NAME, found.name());
        }
      }
    }
  }

  private static JSONObject productJson(OcrLookups.ProductDetails details, boolean withoutName)
      throws JSONException {
    JSONObject json = new JSONObject();
    if (!withoutName) {
      json.put(K_NAME, details.name());
    }
    return json.put("searchKey", details.searchKey()).put("category", details.category())
        .put("uom", details.uom());
  }

  private static void collectIds(JSONArray candidates, List<String> ids) {
    for (int i = 0; candidates != null && i < candidates.length(); i++) {
      JSONObject candidate = candidates.optJSONObject(i);
      if (candidate != null && !StringUtils.isBlank(candidate.optString(K_ID))) {
        ids.add(candidate.optString(K_ID));
      }
    }
  }

  private static NeoResponse refuse(int status, String code, String messageKey) {
    return NeoErrorResponses.toResponse(status, code, InvoiceInboxMessages.text(messageKey),
        List.of(messageKey));
  }
}
