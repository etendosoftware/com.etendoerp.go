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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.ReversedInvoice;

/**
 * Carries the {@code C_Invoice_Reverse} origin/rectifying-invoice relationship — capture, persist
 * and GET-enrichment — out of {@link AbstractInvoiceHeaderHandler} (java:S1448, ETP-5745).
 *
 * <p>One instance per invoice header handler (see {@code AbstractInvoiceHeaderHandler#rectificationLinks},
 * mirroring the existing {@code followUp} field pattern): {@link #captureOriginInvoice} stashes
 * the ids read from the raw request body in {@code handle()} (the pre-hook) into instance state,
 * and {@link #persistOriginInvoice} reads that same state back in {@code afterHandle()} (the
 * post-hook) on the SAME handler instance for a given request — see the capture-site comment on
 * {@link #captureOriginInvoice} for why this can't just be re-read from
 * {@code context.getRequestBody()} in {@code afterHandle()}.
 *
 * <p>This class is a pure helper, not a {@link NeoHandler} and not a CDI bean — concrete header
 * handlers own one as a plain instance field, exactly as they do {@code followUp}.
 */
class InvoiceRectificationLinkSupport {

  private static final Logger log = LogManager.getLogger(InvoiceRectificationLinkSupport.class);

  // ETP-4737: captured by captureOriginInvoice() (called from each subclass's handle(), the
  // pre-hook) and consumed by persistOriginInvoice() (called from afterHandle(), the post-hook).
  private List<String> pendingOriginInvoiceIds;
  private boolean originInvoiceCaptured;

  // ---------------------------------------------------------------------------
  // Origin invoice persistence
  // ---------------------------------------------------------------------------

  /**
   * Captures and strips {@code originInvoice}/{@code originInvoices} from the raw request body
   * BEFORE the generic field filter runs, so {@link #persistOriginInvoice} can still use them
   * later in {@code afterHandle()}. Must be called from each subclass's {@code handle()} (the
   * pre-hook), e.g. alongside the existing {@code NeoHandlerUtils.mirrorAccountingDateOnCreate(...)}
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
  void captureOriginInvoice(NeoContext context) {
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

    if (body.has(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICES)) {
      captured = true;
      try {
        JSONArray arr = body.getJSONArray(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICES);
        for (int i = 0; i < arr.length(); i++) {
          String id = arr.optString(i, null);
          if (StringUtils.isNotBlank(id) && !ids.contains(id)) {
            ids.add(id);
          }
        }
      } catch (Exception e) {
        log.warn("Could not parse {} array: {}", AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICES,
            e.getMessage());
      }
      body.remove(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICES);
    }

    if (body.has(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICE)) {
      captured = true;
      String legacyId = body.optString(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICE, null);
      if (StringUtils.isNotBlank(legacyId) && !ids.contains(legacyId)) {
        ids.add(legacyId);
      }
      body.remove(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICE);
    }

    return captured;
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
  void persistOriginInvoice(NeoContext context) {
    try {
      if (!originInvoiceCaptured) {
        return;
      }
      List<String> originInvoiceIds = pendingOriginInvoiceIds;
      if (originInvoiceIds == null || originInvoiceIds.isEmpty()) {
        return;
      }

      String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
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
  void enrichOriginInvoice(JSONObject rec, String invoiceId) throws Exception {
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
            rec.put(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICE, originId);
            rec.put("originInvoice$_identifier", originDocNo);
            first = false;
          }
        }
        if (first) {
          // no rows at all
          rec.put(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICE, JSONObject.NULL);
          rec.put("originInvoice$_identifier", JSONObject.NULL);
        }
        rec.put(AbstractInvoiceHeaderHandler.FIELD_ORIGIN_INVOICES, origins);
      }
    } catch (Exception e) {
      log.warn("Could not enrich origin invoice for {}: {}", invoiceId, e.getMessage());
    }
  }

  /**
   * Injects {@code rectifyingInvoices} (a JSON array of {@code {id, documentNo}}, one entry per
   * rectificativa that rectifies THIS invoice) into the record, by querying
   * {@code C_Invoice_Reverse} for ALL active links where this invoice is the REVERSED one
   * (ETP-5745 CP-12) — the mirror image of {@link #enrichOriginInvoice}, which reads the same
   * table in the opposite direction.
   *
   * @param rec
   *     the invoice record JSON object; modified in-place
   * @param invoiceId
   *     the primary key of the invoice being enriched
   * @throws Exception
   *     if a JSON or DB operation fails
   */
  @SuppressWarnings("java:S2077")
  void enrichRectifyingInvoices(JSONObject rec, String invoiceId) throws Exception {
    String sql =
        "SELECT inv.c_invoice_id, inv.documentno "
        + "FROM c_invoice_reverse r "
        + "JOIN c_invoice inv ON inv.c_invoice_id = r.c_invoice_id "
        + "WHERE r.reversed_c_invoice_id = ? AND r.isactive = 'Y' "
        + "ORDER BY inv.documentno";
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        JSONArray rectifying = new JSONArray();
        while (rs.next()) {
          rectifying.put(new JSONObject().put("id", rs.getString(1)).put("documentNo", rs.getString(2)));
        }
        rec.put(AbstractInvoiceHeaderHandler.FIELD_RECTIFYING_INVOICES, rectifying);
      }
    } catch (Exception e) {
      log.warn("Could not enrich rectifying invoices for {}: {}", invoiceId, e.getMessage());
    }
  }

  /**
   * Injects {@code hasRectifications} (boolean) into the record.
   * True when the invoice has one or more records in {@code C_Invoice_Reverse}.
   * Only meaningful in detail view (single record); do not call for list responses.
   */
  @SuppressWarnings("java:S2077")
  void enrichHasRectifications(JSONObject rec, String invoiceId) throws Exception {
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
}
