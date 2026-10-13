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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * Refuses Reactivate ({@code DocAction} = {@code RE}) on an order that already has a linked
 * shipment/receipt or invoice (ETP-5704).
 *
 * <p><b>Why.</b> Core's {@code C_Order_Post} reactivates such an order without complaint — its RE
 * branch only checks payments ({@code APRM_GEN_PAYMENTSCHEDULE_ORD} raises
 * {@code APRM_OrderWithPayments}) and debt payments — and leaves the shipment or invoice pointing
 * at a draft order. The SPA has always hidden Reactivate in that case (the {@code hasLinkedDocuments}
 * flag), but nothing enforced it, so MCP and any other integration could do what the UI cannot.</p>
 *
 * <p><b>Same rule as the UI, by construction.</b> "Linked" is
 * {@link AbstractOrderHeaderHandler#hasLinkedDocuments}, the very query behind the flag: an active
 * {@code C_Invoice} or {@code M_InOut} whose header {@code C_Order_ID} is the order, in any status.
 * </p>
 *
 * <p><b>Decides on the action that will actually run.</b> The value checked is
 * {@link DocActionRequestSupport#effectiveDocAction}: the root {@code docAction} when the process
 * can actually write it on the record (the DAL accepts only an exact member of the list), otherwise
 * the record's stored {@code DocAction}, which the procedure then runs. Each narrower reading had a
 * bypass: taking {@code fieldValues.documentAction} first let {@code {"fieldValues":
 * {"documentAction":"CO"},"docAction":"RE"}} reactivate; ignoring the stored value let an empty
 * body run a stored {@code RE}; and taking {@code docAction} verbatim let {@code "re"},
 * {@code " RE"} or {@code "XX"} — which the DAL refuses to write — run a stored {@code RE}.</p>
 *
 * <p><b>Fails closed.</b> If the order cannot be loaded — or its tab or its DocAction cannot be
 * resolved, so the action that will run is unknown — or the linked documents cannot be read, the
 * request is refused with a 500 (and logged): the order stays as it is, which a retry or a
 * configuration fix can repair, whereas letting it through could orphan a document. An order id
 * that does not exist is not such a failure: it answers 404 {@code Record not found}, reusing
 * {@link NeoActionRecordGuard}'s not-found status and message (that guard itself only refuses
 * another tenant's record; an unknown id passes it).</p>
 *
 * <p><b>Called explicitly</b> by each order customization that opts in, from its pre-hook — today
 * only {@link SalesOrderHeaderHandler}. It names no entity, so it adds nothing entity-specific to
 * shared code; an entity gets the rule only by calling it.</p>
 */
final class OrderReactivationGuard {

  private static final Logger log = LogManager.getLogger(OrderReactivationGuard.class);

  static final String DOC_ACTION_REACTIVATE = "RE";
  static final String MSG_LINKED_DOCUMENTS = "ETGO_OrderReactivateLinkedDocs";
  static final String MSG_CHECK_FAILED = "ETGO_OrderLinkedDocsCheckFailed";

  private OrderReactivationGuard() {
  }

  /**
   * The refusal for a Reactivate on an order with linked documents, or {@code null} when the
   * request may continue — it does not run {@code RE}, it names no record, or the order has none.
   *
   * <p>A refusal is a {@code 400}, the status every other document-action precondition of this
   * module answers with ({@code GoodsMovementProcessGuard}, {@code RejectQuotationHandler},
   * {@code CreateShipmentHandler}). A lookup that cannot run is a {@code 500}: the server, not
   * the caller, is at fault.</p>
   *
   * @param context the request reaching the order customization's pre-hook
   * @return the error response, or {@code null}
   */
  static NeoResponse refuseIfLinkedDocuments(NeoContext context) {
    if (!DocActionRequestSupport.isDocActionButtonPost(context)) {
      return null;
    }
    String orderId = context.getRecordId();
    if (StringUtils.isBlank(orderId)) {
      return null;
    }
    try {
      if (!DOC_ACTION_REACTIVATE.equals(DocActionRequestSupport.effectiveDocAction(context))
          || !AbstractOrderHeaderHandler.hasLinkedDocuments(orderId)) {
        return null;
      }
    } catch (NeoProcessService.DocActionRecordNotFoundException e) {
      // No such order: nothing can run, let alone be reactivated. Reuses NeoActionRecordGuard's
      // not-found status and message — not the fail-closed "could not be checked".
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          NeoActionRecordGuard.MSG_RECORD_NOT_FOUND);
    } catch (Exception e) {
      log.error("DocAction on order {} refused: its action or linked documents could not be read",
          orderId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          OBMessageUtils.messageBD(MSG_CHECK_FAILED));
    }
    log.info("Reactivation of order {} refused: it has linked shipments or invoices", orderId);
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        OBMessageUtils.messageBD(MSG_LINKED_DOCUMENTS));
  }
}
