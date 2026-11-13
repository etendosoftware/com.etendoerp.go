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

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.RejectReason;

/**
 * The sales quotation's own decision writes, shared by the app's reject action
 * ({@link RejectQuotationHandler}, every channel) and the customer portal's confirmed decisions
 * (ETP-6209 Part C). Part of the sales-quotation customization: it names the quotation's statuses,
 * which is why it is not shared NEO code.
 *
 * <p>Writes by DAL, never through {@code C_Order_Post}, as the app's reject always did: the status,
 * the reason and {@code Processed = Y} (the form's {@code readOnlyLogic = @Processed@='Y'} locks the
 * record). The caller owns the transaction and the admin mode.</p>
 */
public final class QuotationDecisions {

  /** Under evaluation: the only status a quotation can be decided from (app and portal). */
  public static final String STATUS_UNDER_EVALUATION = "UE";
  /** Closed - Rejected. */
  public static final String STATUS_REJECTED = "CJ";
  /**
   * <b>PO question Q1 (ETP-6209, Jira comment 149513) — the accept status.</b> The planned module
   * value "Aceptado" (like {@code ETGO_CI}). Its {@code AD_REF_LIST} row is <b>not</b> added yet, and
   * nothing reaches {@link #accept} until Q1 is answered (the portal refuses accepts:
   * {@code PortalDecisionService.ACCEPT_AVAILABLE}). Answering Q1 is this constant, the list value
   * with its translations, and the app's convert gate for it.
   */
  public static final String STATUS_ACCEPTED = "ETGO_AC";

  private QuotationDecisions() {
  }

  /**
   * <b>PO question Q3 — which quotations can be decided.</b> Only Under evaluation, the app's reject
   * rule. One predicate for the app's reject and the portal.
   */
  public static boolean isDecidable(Order quotation) {
    return quotation != null && STATUS_UNDER_EVALUATION.equals(quotation.getDocumentStatus());
  }

  /**
   * Closes the quotation as rejected.
   *
   * @param quotation the quotation, already checked with {@link #isDecidable}
   * @param reason the rejection reason, or {@code null} when the caller has none (the portal until
   *     PO question Q2 is answered: its reason is kept in the notes instead)
   */
  public static void reject(Order quotation, RejectReason reason) {
    if (reason != null) {
      quotation.setRejectReason(reason);
    }
    quotation.setDocumentStatus(STATUS_REJECTED);
    quotation.setProcessed(true);
    OBDal.getInstance().save(quotation);
    OBDal.getInstance().flush();
  }

  /**
   * Marks the quotation as accepted by the customer, without generating an order (PO spec).
   * Unreachable until PO question Q1 is answered (see {@link #STATUS_ACCEPTED}).
   *
   * @param quotation the quotation, already checked with {@link #isDecidable}
   */
  public static void accept(Order quotation) {
    quotation.setDocumentStatus(STATUS_ACCEPTED);
    quotation.setProcessed(true);
    OBDal.getInstance().save(quotation);
    OBDal.getInstance().flush();
  }
}
