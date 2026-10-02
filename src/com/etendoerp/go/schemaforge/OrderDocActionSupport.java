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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.order.Order;
import org.openbravo.service.db.CallProcess;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Runs a document action on an order through {@code C_Order_Post}, server-side (ETP-5528).
 *
 * <p>This is the procedure behind the order's {@code DocAction} button (AD_Process {@code 104}) —
 * the one the SPA reaches with {@code POST /sales-order/header/{id}/action/DocAction
 * {docAction:…}}, which {@code NeoProcessService.executeDbProcedure} serves by writing the action
 * onto the record and calling the procedure. This helper does the same two steps from Java, for a
 * backend flow that must leave an order in a given state without a second client request.
 *
 * <p>The process-access check is kept: the SPA request is refused for a role without access to
 * {@code C_Order_Post}, and a backend shortcut must not grant more than that request would.
 */
public final class OrderDocActionSupport {

  private static final Logger log = LogManager.getLogger(OrderDocActionSupport.class);

  /** {@code C_Order_Post}, the process of the order's DocAction button. */
  static final String C_ORDER_POST_PROCESS_ID = "104";
  /** Reactivate: a completed order back to draft. */
  public static final String DOC_ACTION_REACTIVATE = "RE";

  private OrderDocActionSupport() {
  }

  /**
   * Runs {@code docAction} on {@code order} through {@code C_Order_Post}.
   *
   * <p>A failure the procedure reports (an {@code AD_PInstance} result other than 1) is returned
   * as {@code false}, not thrown, so the caller decides whether it is fatal. The order is refreshed
   * either way, so its {@code documentStatus} is the one the procedure left.
   *
   * @param order     the order to act on; must be persisted
   * @param docAction the action code, e.g. {@link #DOC_ACTION_REACTIVATE}
   * @return {@code true} when the procedure ran and reported success
   * @throws Exception when the procedure call itself fails (DB error)
   */
  public static boolean runDocAction(Order order, String docAction) throws Exception {
    if (order == null || docAction == null) {
      return false;
    }
    if (!NeoAccessHelper.hasProcessAccess(C_ORDER_POST_PROCESS_ID)) {
      log.warn("[ORDER-DOCACTION] Role has no access to C_Order_Post; {} not run on order {}",
          docAction, order.getId());
      return false;
    }
    Process process = OBDal.getInstance().get(Process.class, C_ORDER_POST_PROCESS_ID);
    if (process == null) {
      log.warn("[ORDER-DOCACTION] AD_Process {} (C_Order_Post) not found; {} not run on order {}",
          C_ORDER_POST_PROCESS_ID, docAction, order.getId());
      return false;
    }
    // C_Order_Post reads the action from the record, exactly as the DocAction button path sets it.
    order.setDocumentAction(docAction);
    OBDal.getInstance().save(order);
    OBDal.getInstance().flush();

    ProcessInstance pInstance = CallProcess.getInstance().call(process, order.getId(), null);
    OBDal.getInstance().getSession().refresh(pInstance);
    OBDal.getInstance().refresh(order);
    long resultCode = pInstance.getResult() != null ? pInstance.getResult() : 0L;
    if (resultCode != 1L) {
      log.warn("[ORDER-DOCACTION] C_Order_Post {} on order {} reported result {}: {}",
          docAction, order.getId(), resultCode, pInstance.getErrorMsg());
      return false;
    }
    return true;
  }
}
