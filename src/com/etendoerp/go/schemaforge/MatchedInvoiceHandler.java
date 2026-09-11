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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * Post-hook for {@code M_MatchInv} rows (the {@code matchedInvoice} entity of the
 * {@code matched-purchase-invoices} window — "Relación albarán-factura", ETP-5075).
 *
 * <p>Injects {@code invoiceHeaderId} and {@code receiptHeaderId} into every row so the UI
 * can turn the read-only {@code invoiceLine} / {@code goodsShipmentLine} fields into
 * click-throughs to the purchase invoice and the goods receipt.
 *
 * <p>Both FKs of this table point at a LINE ({@code C_InvoiceLine}, {@code M_InOutLine}),
 * and a line has no window of its own — cross-window navigation in the app-shell only ever
 * reaches a document header. An FK's response shape carries just the id plus a
 * {@code $_identifier} label, so the parent document id is not obtainable from the payload,
 * and Schema Forge has no declarative server-side derivation to compute one (see
 * {@code docs/possible-limitations.md}). Injecting it here is what makes the link possible,
 * and it keeps the resolution to a single request: the alternative was a two-hop frontend
 * fetch that also forced the parent-link column to be exposed on the {@code purchase-invoice}
 * and {@code goods-receipt} windows, coupling three windows together for one link.
 *
 * <p>Uses a single batched native query over all IDs in the current page — same shape as
 * {@link PaymentScheduleDetailHandler}, which injects {@code invoiceDocumentNo} into payment
 * lines. Runs for both the list and the detail GET, so grid rows carry the ids too.
 *
 * <p>Consumed by {@code tools/app-shell/src/components/contract-ui/fkNavigation.js} in the
 * functional repo, whose registry maps {@code C_InvoiceLine_ID}/{@code M_InOutLine_ID} to
 * these two field names.
 */
@Named("matchedInvoiceHandler")
public class MatchedInvoiceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(MatchedInvoiceHandler.class);

  private static final String INVOICE_HEADER_ID = "invoiceHeaderId";
  private static final String RECEIPT_HEADER_ID = "receiptHeaderId";

  private static final String PARENT_IDS_SQL =
      "SELECT mi.m_matchinv_id AS match_id, "
      + "  il.c_invoice_id AS invoice_id, "
      + "  iol.m_inout_id AS inout_id "
      + "FROM m_matchinv mi "
      + "LEFT JOIN c_invoiceline il ON il.c_invoiceline_id = mi.c_invoiceline_id "
      + "LEFT JOIN m_inoutline iol ON iol.m_inoutline_id = mi.m_inoutline_id "
      + "WHERE mi.m_matchinv_id IN (:ids)";

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * Pre-hook: routes the accounting post/unpost action to the shared {@link
   * DocumentPostingService} (ETP-5075 — the window is read-only for data, but the posting
   * action is explicitly allowed from it).
   *
   * <p>{@code handleAction} only intercepts an ACTION request whose action name is literally
   * {@code "post"}/{@code "unpost"}, resolving the accounting engine generically from the
   * tab's {@code AD_Table_ID} — for this window that is {@code 472} ({@code M_MatchInv}),
   * which core's {@code AcctServer} dispatches to {@code DocMatchInv} (docbasetype
   * {@code MXI}). Everything else returns {@code null} and falls through to the default CRUD
   * path, which stays restricted to {@code GET}/{@code GETBYID} by {@code ETGO_SF_ENTITY} —
   * the action endpoint is a sub-endpoint dispatched before that CRUD gate, so allowing the
   * post does not open any write path to the data itself.
   *
   * <p>Both the kebab's "post" and "unpost" actions are declared in {@code decisions.json}
   * (the latter added once the requirement clarified reversing a match must also be
   * possible from this window), and the bulk selection toolbar's "Confirmar" modal
   * (window custom component {@code MatchedInvoiceBulkActions.jsx}) offers both over a
   * multi-row selection too — this single {@code handle()} generically serves all of it,
   * since it dispatches on the literal action name, not on which surface called it.
   *
   * @param context the current NeoContext
   * @return the posting service's response when it handled the request, else {@code null}
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    return postingService != null ? postingService.handleAction(context) : null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    // The extract/collect/guard/loop skeleton lives in NeoHandlerUtils#enrichGetRowsById,
    // shared with the other by-id enriching handlers; only the query and the per-row writes
    // below are specific to this window.
    return NeoHandlerUtils.enrichGetRowsById(context, this::fetchParentIds, (rec, parents) -> {
      // A null side is left absent rather than written as null: resolveFkNavigation()
      // treats a missing/blank id as "not navigable" and keeps the plain read-only field.
      if (parents[0] != null) {
        rec.put(INVOICE_HEADER_ID, parents[0]);
      }
      if (parents[1] != null) {
        rec.put(RECEIPT_HEADER_ID, parents[1]);
      }
    }, "Error enriching matched purchase invoices with parent document ids", log);
  }

  /**
   * Resolves, for each {@code M_MatchInv} id, the parent invoice and goods-receipt ids.
   *
   * @param ids the match ids present in the current response page
   * @return map of match id → {@code [invoiceId, inOutId]}, either entry possibly {@code null}
   */
  private Map<String, String[]> fetchParentIds(List<String> ids) {
    Map<String, String[]> result = new HashMap<>();
    OBContext.setAdminMode(true);
    try {
      @SuppressWarnings("unchecked")
      NativeQuery<Object[]> query = OBDal.getInstance().getSession()
          .createNativeQuery(PARENT_IDS_SQL);
      query.setParameterList("ids", ids);
      for (Object[] row : query.list()) {
        result.put((String) row[0], new String[] { (String) row[1], (String) row[2] });
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }
}
