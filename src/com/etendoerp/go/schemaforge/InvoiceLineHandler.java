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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;

/**
 * NeoHandler for invoice line entities (Sales Invoice, Purchase Invoice).
 *
 * <p>Implements {@link #afterCallout(NeoContext)} to publish the tax rate to the frontend
 * when the user changes the line tax. The shared logic lives in {@link LineCalloutTaxRateHelper},
 * mirroring what {@link OrderLineHandler} does for order/quotation lines.
 *
 * <p>On GET: filters discount lines (dummy product {@code ETGO_DTO}) from the response so the
 * UI never displays the internal discount line as a regular product line.
 * Filtering logic is shared with {@link OrderLineHandler} via {@link DiscountLineFilter}.
 *
 * <p>On POST/PATCH/PUT for return invoices (ARI_RM / isReturn=Y): auto-negates
 * {@code invoicedQuantity} and {@code lineNetAmount} when positive, so the
 * {@code C_Invoice_Post} validation ({@code @ReturnInvoiceNegativeQty@}) never fires
 * regardless of how the line was created (manual form or import modal).
 *
 * <p>Registered via {@code javaQualifier = "invoiceLineHandler"} on the lines
 * entity of sales-invoice and purchase-invoice specs.
 */
@Named("invoiceLineHandler")
public class InvoiceLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InvoiceLineHandler.class);
  private static final String FIELD_INVOICED_QTY = "invoicedQuantity";
  private static final String FIELD_LINE_NET_AMOUNT = "lineNetAmount";
  // ETP-4737: signal field for the "Import from Source Invoice" popup — carries the source
  // C_InvoiceLine id being imported, persisted into EM_ETGO_Source_Invoiceline_ID (a
  // self-referencing FK on C_InvoiceLine, same pattern as BOM_Parent_ID) so the popup can
  // detect and block re-importing the same source line twice. Not a decisions.json/contract
  // field — read directly from the raw request body (POST) and injected directly into GET
  // records, same bypass-the-generic-filter approach as AbstractInvoiceHeaderHandler's
  // originInvoice/persistOriginInvoice.
  private static final String FIELD_SOURCE_INVOICE_LINE_ID = "sourceInvoiceLineId";

  // ETP-4737: captured in handle() (pre-hook) and consumed in afterHandle() (post-hook) by
  // persistSourceInvoiceLine — see the capture-before-filtering note there for why this can't
  // just be re-read from context.getRequestBody() in afterHandle(). @Named-only handlers
  // default to @Dependent CDI scope (see class Javadoc precedent in NeoHandler docs), and
  // NeoServletSupport#handleWithHooks calls handle() then afterHandle() on the SAME handler
  // instance for a given request, so a plain instance field safely carries state between them.
  private String pendingSourceInvoiceLineId;

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!"POST".equals(method) && !"PATCH".equals(method) && !"PUT".equals(method)) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    // ETP-4737: capture + strip sourceInvoiceLineId BEFORE the generic field filter runs.
    // It is not a decisions.json/contract field, so NeoFieldFilter#filterCreateRequest would
    // otherwise silently drop it (same reasoning as the parentId carve-out at the top of
    // NeoCrudHandler#executePostCreate) — by the time afterHandle() ran and tried to read it
    // back from context.getRequestBody(), it was already gone, so the link was never persisted.
    if ("POST".equals(method) && body != null && body.has(FIELD_SOURCE_INVOICE_LINE_ID)) {
      pendingSourceInvoiceLineId = body.optString(FIELD_SOURCE_INVOICE_LINE_ID, null);
      body.remove(FIELD_SOURCE_INVOICE_LINE_ID);
    }
    if (body == null || !body.has(FIELD_INVOICED_QTY)) {
      return null;
    }
    try {
      String parentId = resolveParentInvoiceId(context, body);
      if (StringUtils.isBlank(parentId) || !isReturnInvoice(parentId)) {
        return null;
      }
      double qty = body.optDouble(FIELD_INVOICED_QTY, 0);
      if (qty > 0) {
        body.put(FIELD_INVOICED_QTY, -qty);
      }
      if (body.has(FIELD_LINE_NET_AMOUNT)) {
        double amount = body.optDouble(FIELD_LINE_NET_AMOUNT, 0);
        if (amount > 0) {
          body.put(FIELD_LINE_NET_AMOUNT, -amount);
        }
      }
    } catch (Exception e) {
      log.warn("Could not auto-negate return invoice line quantities: {}", e.getMessage());
    }
    return null;
  }

  private String resolveParentInvoiceId(NeoContext context, JSONObject body) {
    String fromBody = body.optString("parentId", null);
    if (StringUtils.isNotBlank(fromBody)) {
      return fromBody;
    }
    // PATCH/PUT: parentId may not be in body — resolve via the existing line record
    String lineId = context.getRecordId();
    if (StringUtils.isBlank(lineId)) {
      return null;
    }
    InvoiceLine line = OBDal.getInstance().get(InvoiceLine.class, lineId);
    return (line != null && line.getInvoice() != null) ? line.getInvoice().getId() : null;
  }

  private boolean isReturnInvoice(String invoiceId) {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    if (invoice == null) {
      return false;
    }
    var docType = invoice.getTransactionDocument();
    return docType != null && Boolean.TRUE.equals(docType.isReturn());
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    String method = context.getHttpMethod();
    if ("GET".equals(method)) {
      enrichSourceInvoiceLineId(context);
      return DiscountLineFilter.filterFromResponse(context);
    }
    if ("POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method)) {
      syncConversionRateDocumentAfterLineSave(context);
      if ("POST".equals(method)) {
        persistSourceInvoiceLine(context);
      }
      return autoFillExemptionCauseAfterLineSave(context);
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // SII exemption-cause auto-fill (ETP-4751 Block B) — mirrors the SII module's
  // ExemptTaxes action handler (org.openbravo.module.sii.actionhandlers.ExemptTaxes).
  // ---------------------------------------------------------------------------

  /**
   * Signal key on the line-save response body that tells the frontend the invoice header's
   * exemption cause was just auto-populated with the default cause, so it can surface the Classic
   * "Causa de exención modificada" info toast. Absent/false when nothing was changed.
   */
  static final String FIELD_EXEMPTION_CAUSE_AUTOFILLED = "exemptionCauseAutoFilled";

  /**
   * Signal key on the line-save response body that tells the frontend the invoice now carries an
   * exempt tax but has NO exemption cause selected and NO default cause exists to auto-fill, so it
   * must surface the Classic "should indicate an exemption cause" warning toast. Mutually exclusive
   * with {@link #FIELD_EXEMPTION_CAUSE_AUTOFILLED}: a save either auto-fills (default cause exists),
   * warns (no default cause), or does neither. Absent/false when no warning is due.
   */
  static final String FIELD_EXEMPTION_CAUSE_WARNING = "exemptionCauseWarning";

  /**
   * After a sales-invoice line is saved, replicates the SII module's {@code ExemptTaxes}
   * default-cause auto-set: when the parent is a <b>sales</b> invoice whose header has <b>no</b>
   * exemption cause yet, the saved line (or any line/tax of the invoice) carries an
   * <b>exempt</b> tax, and a <b>default</b> {@code AEATSII_CAUSE_EXEMPTION} exists, the default
   * cause is written to {@code C_Invoice.EM_Aeatsii_Cause_Exemption_ID} and the response is
   * augmented with {@link #FIELD_EXEMPTION_CAUSE_AUTOFILLED}{@code = true} so the SIF tab can toast.
   *
   * <p><b>Auto-fill is dormant by design</b> in Etendo GO: no default cause is seeded, so
   * {@link #findDefaultCauseExemptionId} returns {@code null} and no cause is written. In that case
   * — sales invoice + exempt tax present + no header cause + no default cause — the response is
   * instead augmented with {@link #FIELD_EXEMPTION_CAUSE_WARNING}{@code = true}, so the SIF tab
   * surfaces the Classic "should indicate an exemption cause" warning toast. The two signals are
   * mutually exclusive. Implemented via raw SQL (no compile dependency on the SII module,
   * mirroring {@link SiiSendHandler}'s reflection-based decoupling): if the SII columns/table are
   * absent (module not installed) the queries fail and are swallowed — a safe no-op.
   *
   * @param context the current NeoContext (lines entity)
   * @return a {@link NeoResponse} replacing the previous result with the auto-fill signal added,
   *     or {@code null} to keep the original response unchanged (the common/dormant case)
   */
  private NeoResponse autoFillExemptionCauseAfterLineSave(NeoContext context) {
    try {
      OBContext.setAdminMode(true);
      try {
        String invoiceId = resolveParentInvoiceIdAfterSave(context);
        if (StringUtils.isBlank(invoiceId) || !shouldAutoFillExemptionCause(invoiceId)) {
          return null;
        }
        String defaultCauseId = findDefaultCauseExemptionId();
        if (StringUtils.isBlank(defaultCauseId)) {
          // No default cause configured: nothing to auto-fill, but the invoice needs a cause and
          // has none — surface the Classic "should indicate an exemption cause" warning instead.
          return augmentResponseWithSignal(context, FIELD_EXEMPTION_CAUSE_WARNING);
        }
        setInvoiceExemptionCause(invoiceId, defaultCauseId);
        return augmentResponseWithSignal(context, FIELD_EXEMPTION_CAUSE_AUTOFILLED);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-4751] exemption-cause auto-fill skipped for line save: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Whether the invoice qualifies for exemption-cause auto-fill: it is a sales invoice, its header
   * has no exemption cause selected yet, and it carries at least one exempt tax on an active LINE.
   * Mirrors the {@code !salesInvoice || invoiceHasCauseExemptionSelected} early-return and the
   * {@code invoiceHasExemptTaxes} check in {@code ExemptTaxes#execute}.
   *
   * <p>Exempt detection uses ONLY active invoice lines (c_invoiceline -> c_tax). Do NOT add a
   * c_invoicetax branch back in: in Etendo GO draft invoices NEO CRUD does not recompute or remove
   * c_invoicetax rows when lines change or are deleted, so those rows LINGER and would report
   * exempt=true after the exempt line was removed (ETP-4751). Active lines are the current source
   * of truth. This still returns true right after an exempt line is ADDED, since the c_invoiceline
   * row already exists at afterHandle time — which is what makes the warning fire on add.
   */
  @SuppressWarnings("java:S2077")
  private boolean shouldAutoFillExemptionCause(String invoiceId) throws java.sql.SQLException {
    String sql =
        "SELECT i.issotrx, i.em_aeatsii_cause_exemption_id,"
      + " EXISTS (SELECT 1 FROM c_invoiceline il JOIN c_tax t ON t.c_tax_id = il.c_tax_id"
      + "   WHERE il.c_invoice_id = i.c_invoice_id AND il.isactive = 'Y' AND t.istaxexempt = 'Y')"
      + " AS has_exempt"
      + " FROM c_invoice i WHERE i.c_invoice_id = ?";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return false;
        }
        boolean salesInvoice = "Y".equals(rs.getString("issotrx"));
        boolean hasCause = StringUtils.isNotBlank(rs.getString("em_aeatsii_cause_exemption_id"));
        boolean hasExempt = rs.getBoolean("has_exempt");
        return salesInvoice && !hasCause && hasExempt;
      }
    }
  }

  /**
   * Returns the ID of the default {@code AEATSII_CAUSE_EXEMPTION} ({@code ISDEFAULT = 'Y'}) visible
   * to the current client/org, or {@code null} if none exists (the dormant case in Etendo GO).
   * Mirrors {@code ExemptTaxes#getCauseExemptionByDefault}.
   */
  @SuppressWarnings("java:S2077")
  private String findDefaultCauseExemptionId() throws java.sql.SQLException {
    String sql =
        "SELECT aeatsii_cause_exemption_id FROM aeatsii_cause_exemption"
      + " WHERE isdefault = 'Y' AND isactive = 'Y' LIMIT 1";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  @SuppressWarnings("java:S2077")
  private void setInvoiceExemptionCause(String invoiceId, String causeId) throws java.sql.SQLException {
    String userId = OBContext.getOBContext().getUser().getId();
    String sql =
        "UPDATE c_invoice SET em_aeatsii_cause_exemption_id = ?, updated = NOW(), updatedby = ?"
      + " WHERE c_invoice_id = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, causeId);
      ps.setString(2, userId);
      ps.setString(3, invoiceId);
      ps.executeUpdate();
    }
    log.info("[ETP-4751] Auto-set default exemption cause {} on invoice {}", causeId, invoiceId);
  }

  private NeoResponse augmentResponseWithSignal(NeoContext context, String signalKey) {
    try {
      NeoResponse prev = context.getPreviousResult();
      if (prev == null || prev.getBody() == null) {
        return null;
      }
      JSONObject body = prev.getBody();
      body.put(signalKey, true);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("[ETP-4751] Could not add '{}' signal to line-save response: {}", signalKey,
          e.getMessage());
      return null;
    }
  }

  /**
   * Injects {@code sourceInvoiceLineId} (the id of the source line this line was imported
   * from, or {@code null}) into every GET record — mirrors
   * {@link AbstractInvoiceHeaderHandler#enrichOriginInvoice}'s injection pattern at the line
   * level. Read by the "Import from Source Invoice" popup to mark already-imported source
   * lines so the user cannot pick the same one twice.
   */
  private void enrichSourceInvoiceLineId(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return;
      }
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String lineId = rec.optString("id", null);
        if (StringUtils.isBlank(lineId)) {
          continue;
        }
        InvoiceLine line = OBDal.getInstance().get(InvoiceLine.class, lineId);
        InvoiceLine sourceLine = (line != null) ? line.getETGOSourceInvoiceLine() : null;
        rec.put(FIELD_SOURCE_INVOICE_LINE_ID, sourceLine != null ? sourceLine.getId() : JSONObject.NULL);
      }
    } catch (Exception e) {
      log.warn("Could not enrich sourceInvoiceLineId: {}", e.getMessage());
    }
  }

  /**
   * Persists the {@code sourceInvoiceLineId} link after a new line is created via POST, using
   * the value {@link #handle} captured from the raw request body before the generic field
   * filter stripped it (see the capture-site comment there). Resolves the newly-created line's
   * own id the same way {@link #resolveParentInvoiceIdAfterSave} does, then sets the
   * self-referencing FK directly via OBDal — bypasses the generic field filter entirely since
   * this is not a decisions.json/contract field.
   */
  private void persistSourceInvoiceLine(NeoContext context) {
    try {
      String sourceLineId = pendingSourceInvoiceLineId;
      if (StringUtils.isBlank(sourceLineId)) {
        return;
      }
      String newLineId = extractCreatedLineIdFromPreviousResult(context);
      if (StringUtils.isBlank(newLineId)) {
        return;
      }
      InvoiceLine newLine = OBDal.getInstance().get(InvoiceLine.class, newLineId);
      InvoiceLine sourceLine = OBDal.getInstance().get(InvoiceLine.class, sourceLineId);
      if (newLine == null || sourceLine == null) {
        return;
      }
      newLine.setETGOSourceInvoiceLine(sourceLine);
      OBDal.getInstance().save(newLine);
      OBDal.getInstance().flush();
    } catch (Exception e) {
      log.warn("Could not persist sourceInvoiceLineId: {}", e.getMessage());
    }
  }

  /**
   * Re-syncs the parent invoice's {@code C_Conversion_Rate_Document} (rate/foreignAmount)
   * after a line is added or edited (POST/PATCH/PUT). {@code AbstractInvoiceHeaderHandler
   * #autoCreateOrUpdateConversionRateDocument} only runs on HEADER saves — since it is bound
   * per-entity via {@code ETGO_SF_ENTITY.Java_Qualifier} — so nothing re-syncs the document's
   * {@code foreignAmount} as {@code grandTotalAmount} changes while lines are added after the
   * header currency/rate was first set (ETP-4029 follow-up gap, confirmed via manual QA on
   * invoice {@code 200611A7D11E4EE3B3B9DEEA607015D9}).
   *
   * <p>Resolves the parent invoice ID the same way {@link #resolveParentInvoiceId(NeoContext,
   * JSONObject)} does for {@code handle()}, adapted for {@code afterHandle()} where the original
   * request body is not guaranteed to still carry {@code parentId} (e.g. POST responses only
   * expose the created line via {@code getPreviousResult()}). Delegates the actual upsert to the
   * shared {@link AbstractInvoiceHeaderHandler#autoCreateOrUpdateConversionRateDocument(String)}
   * core — no SQL is duplicated here.
   *
   * @param context the current NeoContext (lines entity; {@code getRecordId()} is the LINE id)
   */
  private void syncConversionRateDocumentAfterLineSave(NeoContext context) {
    try {
      String invoiceId = resolveParentInvoiceIdAfterSave(context);
      AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(invoiceId);
    } catch (Exception e) {
      log.warn("[ETP-4029] Could not sync conversion rate document after line save: {}",
          e.getMessage());
    }
  }

  /**
   * Resolves the parent invoice ID after a line POST/PATCH/PUT has already completed.
   * PATCH/PUT: the line record already existed, so {@code context.getRecordId()} is the line ID
   * and can be looked up directly. POST: the line did not exist yet when the request started, so
   * the new line ID must be read from {@code context.getPreviousResult()}'s CRUD response body
   * (mirrors {@code AbstractInvoiceHeaderHandler#resolveInvoiceIdFromContext}'s POST-vs-PATCH
   * resolution pattern, adapted here because the "record" in this entity is the line, not the
   * invoice).
   *
   * @param context the current NeoContext
   * @return the parent invoice ID, or {@code null} if it could not be resolved
   */
  private String resolveParentInvoiceIdAfterSave(NeoContext context) {
    String lineId = context.getRecordId();
    if (StringUtils.isBlank(lineId)) {
      lineId = extractCreatedLineIdFromPreviousResult(context);
    }
    if (StringUtils.isBlank(lineId)) {
      return null;
    }
    InvoiceLine line = OBDal.getInstance().get(InvoiceLine.class, lineId);
    return (line != null && line.getInvoice() != null) ? line.getInvoice().getId() : null;
  }

  private String extractCreatedLineIdFromPreviousResult(NeoContext context) {
    return NeoHandlerUtils.extractCreatedIdFromPreviousResult(context);
  }

  /**
   * Callout post-hook: enrich the response with a synthetic {@code taxRate} update
   * when the user changed the line tax. Logic shared with order lines lives in
   * {@link LineCalloutTaxRateHelper}.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    return LineCalloutTaxRateHelper.augmentTaxRate(context);
  }
}
