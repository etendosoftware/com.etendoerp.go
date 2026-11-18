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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.handlers.ContactTypeRules;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;

/**
 * Creates, for ONE inbox document, everything its confirmation needs and completes the invoice
 * (ETP-6051), as one unit of work with one commit: the completion.
 *
 * <p>Everything is written flush-only BEFORE the completion, in this order: the vendor and its
 * address when the vendor is new, the products when a line's product is new, the invoice header and
 * its lines (all through the NEO create pipeline, {@link BatchService#executeInCallerTransaction},
 * so defaults, callouts, cascades and customizations are the ones of the SPA's own batch), the
 * original file attached to the invoice as its main document, and the inbox row linked
 * ({@code C_INVOICE_ID}, {@code STATUS = CONFIRMED}, error cleared). Then
 * {@link InvoiceCompletionService#completeInvoiceOrThrow}, which commits all of it on success and
 * rolls all of it back on a refusal: no half-created invoice, vendor or product. After the
 * completion nothing may be written in the unit; the answer is read by id.</p>
 *
 * <p>Any failure is a {@link Stop}: the caller rolls back and records the failure in a new
 * transaction. Collaborators: {@link InvoiceInboxConfirmSeeds} (vendor), {@link
 * InvoiceInboxConfirmLines} (lines, products), {@link InvoiceInboxConfirmAttachment} (the file).</p>
 */
final class InvoiceInboxConfirmWork {

  private static final Logger log = LogManager.getLogger(InvoiceInboxConfirmWork.class);
  private static final String SPEC_CONTACTS = "contacts";
  private static final String SPEC_PRODUCT = "product";
  private static final String SPEC_INVOICE = "purchase-invoice";
  private static final String OP_VENDOR = "bp";
  private static final String OP_ADDRESS = "loc";
  private static final String OP_INVOICE = "inv";
  private static final String OP_PRODUCT = "p";
  private static final String OP_LINE = "ln";
  private static final String K_BUSINESS_PARTNER = "businessPartner";
  private static final String K_ERROR = "error";
  private static final String K_MESSAGE = "message";

  /** A failure of the unit: a reason to record, or a refusal to answer as it is. */
  static final class Stop extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient NeoResponse refusal;

    Stop(String reason) {
      super(reason);
      this.refusal = null;
    }

    Stop(NeoResponse refusal) {
      super("refused");
      this.refusal = refusal;
    }

    /** Returns the refusal to answer as it is, {@code null} when the failure is to be recorded. */
    NeoResponse refusal() {
      return refusal;
    }
  }

  /** What a confirmed document produced. */
  static final class Done {
    final String invoiceId;
    final boolean adjusted;
    final boolean createdVendor;
    final int createdProducts;
    final boolean totalMismatch;

    Done(String invoiceId, boolean adjusted, boolean createdVendor, int createdProducts,
        boolean totalMismatch) {
      this.totalMismatch = totalMismatch;
      this.invoiceId = invoiceId;
      this.adjusted = adjusted;
      this.createdVendor = createdVendor;
      this.createdProducts = createdProducts;
    }
  }

  private final IncomingDocument row;
  private final String rowId;
  private final JSONObject header;
  private final JSONObject vendor;
  private final JSONArray lines;
  private final OcrLookups lookups;
  private final Map<Integer, String> productIds = new HashMap<>();
  private String vendorId;
  private String locationId;
  private boolean createdVendor;

  private InvoiceInboxConfirmWork(IncomingDocument row, JSONObject document, OcrLookups lookups) {
    this.row = row;
    this.rowId = row.getId();
    this.header = document.optJSONObject(K_HEADER);
    this.vendor = header.optJSONObject(K_VENDOR);
    this.lines = document.optJSONArray(K_LINES);
    this.lookups = lookups;
  }

  /**
   * Runs the unit. Always in admin mode (as the creation precedents), with the batch scope of the
   * request opened here and closed after the commit; the checks of the user (organization, window,
   * the role's access to each spec) were made before and by the pipeline.
   *
   * @param row      the locked row
   * @param document the stored document
   * @param lookups  the lookups of the document type
   * @return what was produced
   * @throws Stop when the unit failed
   */
  static Done run(IncomingDocument row, JSONObject document, Optional<OcrLookups> lookups) {
    if (lookups.isEmpty()) {
      throw new Stop(InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_FAILED, "reason",
          "no lookups for the document type"));
    }
    InvoiceInboxConfirmWork work = new InvoiceInboxConfirmWork(row, document, lookups.get());
    OBContext.setAdminMode(true);
    NeoBatchScope previous = NeoBatchScope.open(false, false);
    try {
      work.createMasterData();
      String invoiceId = work.createInvoice();
      boolean adjusted = Boolean.TRUE.equals(row.isAdjusted());
      boolean mismatch = work.totalMismatch(document);
      work.completeInvoice(invoiceId);
      // From here the unit is committed: nothing below may fail the confirmation.
      afterCommit();
      return new Done(invoiceId, adjusted, work.createdVendor, work.productIds.size(), mismatch);
    } catch (JSONException | IOException e) {
      log.warn("Inbox confirm: the unit failed: {}", e.getMessage());
      throw new Stop(InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_COMPLETION_ERROR));
    } finally {
      NeoBatchScope.close(previous);
      OBContext.restorePreviousMode();
    }
  }

  /** The warning of the document, read from the stored document before anything is written. */
  private boolean totalMismatch(JSONObject document) {
    return InvoiceInboxSummary.totalMismatch(document,
        InvoiceInboxLiveChecks.documentCurrency(document, row));
  }

  /** The actions queued for after the commit; a failure of one is logged, never raised. */
  private static void afterCommit() {
    try {
      NeoBatchScope.runAfterCommitActions();
    } catch (RuntimeException e) {
      log.warn("Inbox confirm: an after-commit action failed: {}", e.getMessage());
    }
  }

  // ── Vendor and products ─────────────────────────────────────────────────────────────────────

  private void createMasterData() throws JSONException {
    JSONArray ops = new JSONArray();
    planVendor(ops);
    planProducts(ops);
    Map<String, String> created = ops.length() == 0 ? Map.of() : runOps(ops);
    if (createdVendor) {
      vendorId = created.get(OP_VENDOR);
      locationId = created.get(OP_ADDRESS);
    }
    for (int i = 0; i < lines.length(); i++) {
      String id = created.get(OP_PRODUCT + i);
      if (id != null) {
        productIds.put(i, id);
      }
    }
  }

  private void planVendor(JSONArray ops) throws JSONException {
    if (S_LINKED.equals(vendor.optString(K_STATE, ""))) {
      vendorId = vendor.optString(K_ID);
      // Admin mode sees every client: the vendor must be this tenant's (or System's) own.
      if (TenantOwnership.loadOwnedByClient(BusinessPartner.class, vendorId) == null) {
        throw new Stop(InvoiceInboxConfirmChecks.vendorAddressMissing());
      }
      locationId = lookups.vendorAddress(vendorId).locationId();
      if (StringUtils.isBlank(locationId)) {
        throw new Stop(InvoiceInboxConfirmChecks.vendorAddressMissing());
      }
      return;
    }
    JSONObject extracted = vendor.optJSONObject(K_EXTRACTED);
    String countryId = InvoiceInboxConfirmSeeds.countryId(
        extracted == null ? "" : extracted.optString(InvoiceInboxConfirmSeeds.K_COUNTRY, ""),
        InvoiceInboxLiveChecks.countries());
    if (!S_NEW.equals(vendor.optString(K_STATE, ""))
        || !InvoiceInboxConfirmSeeds.hasUsableAddress(extracted) || countryId == null) {
      throw new Stop(InvoiceInboxConfirmChecks.vendorAddressMissing());
    }
    boolean categoryType = ContactTypeRules.isEnabled();
    String category = InvoiceInboxConfirmLookups.purchaseContactCategory(
        row.getClient().getId());
    if (category == null && categoryType) {
      // The popup stops here too: never a customer-only contact by accident.
      throw new Stop(InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_NO_CATEGORY));
    }
    ops.put(op(OP_VENDOR, SPEC_CONTACTS, K_BUSINESS_PARTNER, null, withOrganization(
        InvoiceInboxConfirmSeeds.vendorBody(extracted, categoryType, category))));
    ops.put(op(OP_ADDRESS, SPEC_CONTACTS, "locationAddress", OP_VENDOR,
        InvoiceInboxConfirmSeeds.addressBody(extracted, countryId)
            .put(K_BUSINESS_PARTNER, BatchService.REF_PREFIX + OP_VENDOR)));
    createdVendor = true;
  }

  private void planProducts(JSONArray ops) throws JSONException {
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.getJSONObject(i);
      JSONObject product = line.optJSONObject(K_PRODUCT);
      if (product != null && S_NEW.equals(product.optString(K_STATE, ""))) {
        String taxCategory = InvoiceInboxConfirmLookups.taxCategoryOf(taxIdOf(line));
        ops.put(op(OP_PRODUCT + i, SPEC_PRODUCT, SPEC_PRODUCT, null,
            withOrganization(InvoiceInboxConfirmLines.productBody(line, taxCategory,
                nextProductKey()))));
      }
    }
  }

  /**
   * Pins the organization of the row on a body: in admin mode the pipeline would take the CURRENT
   * organization of the user's context, which may differ from the one the document belongs to (and
   * which the currency and rate checks use).
   */
  private JSONObject withOrganization(JSONObject body) throws JSONException {
    return body.put("organization", row.getOrganization().getId());
  }

  /**
   * The next number of the document sequence of the products, consumed in this transaction. */
  private String nextProductKey() {
    String key = Utility.getDocumentNoConnection(OBDal.getInstance().getConnection(false),
        new DalConnectionProvider(false), row.getClient().getId(), "M_Product", true);
    if (StringUtils.isBlank(key)) {
      throw new Stop("no document sequence for the search key of a new product");
    }
    return key;
  }

  // ── The invoice ─────────────────────────────────────────────────────────────────────────────

  private String createInvoice() throws JSONException {
    JSONArray ops = new JSONArray();
    ops.put(op(OP_INVOICE, SPEC_INVOICE, "Header", null, withOrganization(headerBody())));
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.getJSONObject(i);
      JSONObject body = InvoiceInboxConfirmLines.lineBody(line, productIdOf(i, line),
          resolvedTax(line));
      ops.put(op(OP_LINE + i, SPEC_INVOICE, "Lines", OP_INVOICE, body));
    }
    return runOps(ops).get(OP_INVOICE);
  }

  private JSONObject headerBody() throws JSONException {
    JSONObject body = new JSONObject().put(K_BUSINESS_PARTNER, vendorId)
        .put("partnerAddress", locationId);
    String documentNo = header.optString(K_DOCUMENT_NO, "").trim();
    if (!documentNo.isEmpty()) {
      body.put("orderReference", documentNo);
    }
    body.put("invoiceDate", header.optString(K_INVOICE_DATE, ""));
    String currencyId = InvoiceInboxLiveChecks.documentCurrencyId(header, row,
        row.getOrganization().getId());
    if (currencyId != null) {
      body.put("currency", currencyId);
    }
    return body;
  }

  private String productIdOf(int position, JSONObject line) throws JSONException {
    String created = productIds.get(position);
    return created != null ? created : line.getJSONObject(K_PRODUCT).optString(K_ID);
  }

  /** The tax id of the line: the stored one, else the tax the vendor's address determines now. */
  private String resolvedTax(JSONObject line) throws JSONException {
    String stored = taxIdOf(line);
    if (stored != null || line.isNull(K_TAX_RATE) || !line.has(K_TAX_RATE)) {
      return stored;
    }
    OcrLookups.TaxAnswer answer = lookups.defaultTax(locationId,
        header.optString(K_INVOICE_DATE, null), new BigDecimal(line.get(K_TAX_RATE).toString()));
    return answer.chosen() == null ? null : answer.chosen().id();
  }

  private static String taxIdOf(JSONObject line) {
    JSONObject tax = line.optJSONObject(K_TAX);
    String id = tax == null ? "" : tax.optString(K_ID, "");
    return id.isEmpty() ? null : id;
  }

  // ── Before and through the completion ───────────────────────────────────────────────────────

  private void completeInvoice(String invoiceId) throws JSONException, IOException {
    OBDal.getInstance().flush();
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    OBDal.getInstance().getSession().refresh(invoice);
    InvoiceInboxConfirmVerify.verify(invoice, header, lines);
    InvoiceInboxConfirmAttachment.attach(rowId, invoiceId);
    link(invoice);
    NeoQuotaCreateCheck.requireAllowedDocument(invoice);
    DocumentCurrencyRate.requireRateForCompletion(invoice, Invoice.PROPERTY_ETGOCURRENCYRATE);
    InvoiceSifPreset.applyBeforeCompletion(invoice, null);
    complete(invoiceId);
  }

  /** The completion: its business refusals are translated and safe to show; infrastructure ones are not. */
  private static void complete(String invoiceId) {
    try {
      InvoiceCompletionService.completeInvoiceOrThrow(invoiceId, OBContext.getOBContext());
    } catch (OBException e) {
      throw new Stop(e.getMessage());
    } catch (IllegalStateException e) {
      log.warn("Inbox confirm: the completion failed: {}", e.getMessage());
      throw new Stop(InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_COMPLETION_ERROR));
    }
  }

  /** The row now points at its invoice and is CONFIRMED; rolled back with everything on a failure. */
  private void link(Invoice invoice) {
    row.setInvoice(invoice);
    row.setStatus(InvoiceInboxConfirmClaim.STATUS_CONFIRMED);
    row.setErrorCode(null);
    row.setErrorDetail(null);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
  }

  // ── The pipeline ────────────────────────────────────────────────────────────────────────────

  private static JSONObject op(String id, String spec, String entity, String parentRef,
      JSONObject body) throws JSONException {
    JSONObject op = new JSONObject().put("id", id).put("spec", spec).put("entity", entity)
        .put("body", body);
    if (parentRef != null) {
      op.put("parentRef", parentRef);
    }
    return op;
  }

  /** Runs the operations in the caller's transaction; the ids they created by operation id. */
  private static Map<String, String> runOps(JSONArray ops) throws JSONException {
    JSONObject result = BatchService.forBatchOnly().executeInCallerTransaction(ops);
    if (result.has("failedAt")) {
      String step = stepLabel(result, ops);
      log.warn("Inbox confirm: the creation failed at {}: {}", step, result);
      JSONArray persisted = result.optJSONArray("persisted");
      if (persisted != null && persisted.length() > 0 || !result.optBoolean("atomic", true)) {
        log.warn("Inbox confirm: records survived the rollback at {}: {}", step, persisted);
      }
      throw new Stop(step + ": " + friendly(reasonOf(result)));
    }
    Map<String, String> ids = new HashMap<>();
    JSONArray done = result.optJSONArray("operations");
    for (int i = 0; done != null && i < done.length(); i++) {
      JSONObject item = done.getJSONObject(i);
      ids.put(item.optString("id"), item.optString("recordId"));
    }
    return ids;
  }

  /** Names the step that failed: the new vendor, its address, a new product, the header, a line. */
  static String stepLabel(JSONObject failure, JSONArray ops) {
    JSONObject failedAt = failure.optJSONObject("failedAt");
    String id = failedAt == null ? "" : failedAt.optString("id", "");
    if (OP_VENDOR.equals(id)) {
      return "new vendor";
    }
    if (OP_ADDRESS.equals(id)) {
      return "new vendor address";
    }
    if (OP_INVOICE.equals(id)) {
      return "invoice header";
    }
    if (id.startsWith(OP_LINE) && id.length() > OP_LINE.length()) {
      return "line " + (Integer.parseInt(id.substring(OP_LINE.length())) + 1);
    }
    if (id.startsWith(OP_PRODUCT) && id.length() > OP_PRODUCT.length()) {
      return "new product '" + productName(ops, id) + "'";
    }
    return "creation";
  }

  /** Names the failure of a duplicated search key; any other reason is returned as it is. */
  private static String friendly(String reason) {
    return reason.contains("Search Key") && reason.contains("unique")
        ? InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_SEARCH_KEY_EXISTS) : reason;
  }

  private static String productName(JSONArray ops, String id) {
    for (int i = 0; i < ops.length(); i++) {
      JSONObject op = ops.optJSONObject(i);
      if (op != null && id.equals(op.optString("id"))) {
        return op.optJSONObject("body").optString("name", id);
      }
    }
    return id;
  }

  /** The names of the missing fields the failed operation reported, as ": fields a, b" or empty. */
  private static String missingFields(JSONObject detail) {
    JSONObject nested = detail == null ? null : detail.optJSONObject(K_ERROR);
    JSONArray fields = nested == null ? null : nested.optJSONArray("fields");
    if (fields == null || fields.length() == 0) {
      return "";
    }
    List<String> names = new ArrayList<>();
    for (int i = 0; i < fields.length(); i++) {
      names.add(fields.optString(i));
    }
    return " (" + String.join(", ", names) + ")";
  }

  /** The reason a batch failure carries: the detail of the operation, else the batch's own. */
  static String reasonOf(JSONObject failure) {
    JSONObject error = failure.optJSONObject(K_ERROR);
    JSONObject detail = error == null ? null : error.optJSONObject("detail");
    JSONObject nested = detail == null ? null : detail.optJSONObject(K_ERROR);
    String reason = nested != null ? nested.optString(K_MESSAGE, "") : "";
    if (reason.isEmpty() && detail != null) {
      reason = detail.optString(K_MESSAGE, "");
    }
    if (reason.isEmpty() && error != null) {
      reason = error.optString(K_MESSAGE, "");
    }
    return (reason.isEmpty() ? "The creation was refused" : reason) + missingFields(detail);
  }
}
