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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Which "import lines into a draft invoice" actions an invoice may take, by what KIND of invoice it
 * is (ETP-5876), the way the screen offers them: a rectificative gets "from return" and "from
 * origin invoice", a normal invoice gets "from order" and "from goods shipment / receipt", never
 * the other way round.
 *
 * <p>Two faces of the one rule, both fed by the {@link RectificativeImportPolicy} the invoice header
 * customization stores on the request:
 * <ul>
 *   <li>{@link #refuse} — the enforcement, called by the header's {@code handle} before it
 *       dispatches to the delegates: a 422 and nothing created, on every channel (REST single and
 *       batch, MCP);</li>
 *   <li>{@link #unavailable} — the advice, {@code NeoHandler#unavailableActions}: the actions that do
 *       not apply to the invoice an agent asked about, each with the reason and what to use
 *       instead.</li>
 * </ul>
 * It names no entity: the action names come from the delegates' own constants and the
 * classification from the policy.
 */
final class RectificativeImportGate {

  private static final Logger log = LogManager.getLogger(RectificativeImportGate.class);

  /** The order / goods shipment / goods receipt imports (ETP-5713): for a normal invoice. */
  static final Set<String> STANDARD_ACTIONS = union(ImportOrderLinesIntoInvoiceHandler.ACTIONS,
      ImportInOutLinesIntoInvoiceHandler.STANDARD_ACTIONS);

  /** The return / origin invoice imports (ETP-5876): for a rectificative invoice. */
  static final Set<String> RECTIFICATIVE_ACTIONS = union(
      ImportInOutLinesIntoInvoiceHandler.RETURN_ACTIONS, ImportOriginInvoiceLinesHandler.ACTIONS);

  private RectificativeImportGate() {
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    Set<String> all = new LinkedHashSet<>(first);
    all.addAll(second);
    return Collections.unmodifiableSet(all);
  }

  /**
   * The 422 for an import action asked of the wrong kind of invoice.
   *
   * @param context the request; only an ACTION POST of one of the import actions is judged
   * @param policy  the header's rule for "rectificative"
   * @return the refusal, or {@code null} when the action may go on (or is not an import action, or
   *         the invoice cannot be read: the delegate then answers 404/400 itself)
   */
  static NeoResponse refuse(NeoContext context, RectificativeImportPolicy policy) {
    String action = context.getFieldName();
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod()) || action == null) {
      return null;
    }
    boolean standard = STANDARD_ACTIONS.contains(action);
    if (!standard && !RECTIFICATIVE_ACTIONS.contains(action)) {
      return null;
    }
    Invoice invoice = ownInvoice(context.getRecordId());
    if (invoice == null) {
      return null;
    }
    boolean rectificative = judgeAsAdmin(invoice, policy);
    if (standard && rectificative) {
      return DocumentLineImport.refusal(DocumentLineImport.ERROR_RECTIFICATIVE_TARGET,
          standardReason(invoice)).response();
    }
    if (!standard && !rectificative) {
      return DocumentLineImport.refusal(DocumentLineImport.ERROR_NOT_RECTIFICATIVE,
          rectificativeReason(invoice)).response();
    }
    return null;
  }

  /**
   * The import actions that do not apply to {@code subject}, with the reason: the order and goods
   * imports on a rectificative, the return and origin imports on anything else. Advisory only; a
   * lookup that fails rules nothing out.
   *
   * @param subject the record the agent asked about
   * @param policy  the header's rule for "rectificative"
   * @return {@code action → reason}; empty for anything that is not an invoice
   */
  static Map<String, String> unavailable(BaseOBObject subject, RectificativeImportPolicy policy) {
    if (!(subject instanceof Invoice)) {
      return Collections.emptyMap();
    }
    Invoice invoice = (Invoice) subject;
    try {
      boolean rectificative = policy.isRectificative(invoice);
      String reason = rectificative ? standardReason(invoice) : rectificativeReason(invoice);
      Map<String, String> unavailable = new LinkedHashMap<>();
      for (String action : rectificative ? STANDARD_ACTIONS : RECTIFICATIVE_ACTIONS) {
        unavailable.put(action, reason);
      }
      return unavailable;
    } catch (RuntimeException e) {
      log.warn("Could not judge the import actions of invoice {}: {}", invoice.getId(),
          e.getMessage());
      return Collections.emptyMap();
    }
  }

  /** Admin mode: the document type of an invoice the role reads may sit in an org it does not. */
  private static boolean judgeAsAdmin(Invoice invoice, RectificativeImportPolicy policy) {
    OBContext.setAdminMode(true);
    try {
      return policy.isRectificative(invoice);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String standardReason(Invoice invoice) {
    return "Invoice " + invoice.getDocumentNo() + " is a rectificative invoice: it takes no lines "
        + "from orders, goods shipments or goods receipts (they would come in positive). Import "
        + "from a return (availableReturns / importReturnLines) or from the invoice it rectifies "
        + "(availableOriginInvoices / importOriginInvoiceLines).";
  }

  private static String rectificativeReason(Invoice invoice) {
    return "Invoice " + invoice.getDocumentNo() + " is not a rectificative invoice: importing from "
        + "a return or from an origin invoice is only for rectificatives. Import from an order "
        + "(availableOrders / importOrderLines) or from a goods shipment / receipt instead.";
  }

  /** The invoice of the session's client, or {@code null}: a foreign one reads as a missing one. */
  private static Invoice ownInvoice(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    boolean admin = false;
    try {
      OBContext.setAdminMode(true);
      admin = true;
      Invoice invoice = OBDal.getInstance().get(Invoice.class, id);
      boolean own = invoice != null && invoice.getClient() != null && invoice.getClient().getId()
          .equals(OBContext.getOBContext().getCurrentClient().getId());
      return own ? invoice : null;
    } catch (RuntimeException e) {
      log.warn("Could not load invoice {} to judge an import action: {}", id, e.getMessage());
      return null;
    } finally {
      if (admin) {
        OBContext.restorePreviousMode();
      }
    }
  }
}
