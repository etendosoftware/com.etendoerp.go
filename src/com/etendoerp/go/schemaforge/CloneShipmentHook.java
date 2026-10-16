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

import com.smf.jobs.hooks.CloneRecordHook;

import org.apache.commons.lang3.time.DateUtils;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.ComponentProvider.Qualifier;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import javax.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;

/**
 * CloneRecordHook for Goods Shipments (M_InOut).
 *
 * DalUtil.copy handles line copying (shouldCopyChildren = true).
 * postCopy only resets the header state to Draft so the clone
 * can be processed independently.
 * documentNo is left null so NeoCloneRecordHandler assigns the next sequence.
 */
@ApplicationScoped
@Qualifier(ShipmentInOut.ENTITY_NAME)
public class CloneShipmentHook extends CloneRecordHook {

  @Override
  public boolean shouldCopyChildren(boolean uiCopyChildren) {
    return true;
  }

  @Override
  public BaseOBObject preCopy(BaseOBObject originalRecord) {
    return originalRecord;
  }

  @Override
  public BaseOBObject postCopy(BaseOBObject originalRecord, BaseOBObject newRecord) {
    ShipmentInOut clone = (ShipmentInOut) newRecord;
    User currentUser = OBContext.getOBContext().getUser();
    Date today = DateUtils.truncate(new Date(), Calendar.DATE);

    clone.setDocumentStatus("DR");
    clone.setDocumentAction("CO");
    clone.setPosted("N");
    clone.setProcessed(false);
    clone.setDocumentNo(null);
    clone.setSalesOrder(null);
    clone.setMovementDate(today);
    // DateAcct was copied from the source: align it with the new movement date.
    clone.setAccountingDate(today);
    clone.setCompletelyInvoiced(false);
    clone.setInvoice(null);
    clone.setCreationDate(new Date());
    clone.setUpdated(new Date());
    clone.setCreatedBy(currentUser);
    clone.setUpdatedBy(currentUser);

    for (ShipmentInOutLine clonedLine : clone.getMaterialMgmtShipmentInOutLineList()) {
      clonedLine.setCanceledInoutLine(null);
      // m_inoutline_trg enforces MovementQtyCheck: total delivered for an order line cannot
      // exceed QtyOrdered. Preserving C_OrderLine_ID on a new line would double-count the
      // delivered quantity and fail the trigger. The header's C_Order_ID is cleared above too, so
      // the copy has no linked order at all: a purchase invoice from the cloned receipt takes the
      // no-purchase-order path (CreatePurchaseInvoiceHandler.createFromReceiptNoPo), not the
      // order-line matching of buildSelectedLinesFromReceipt.
      clonedLine.setSalesOrderLine(null);
      // The source's invoicing counters describe invoices the copy does not have (no invoice
      // line, no M_MatchInv row): a copy must not look invoiced.
      clonedLine.setInvoiceQuantity(BigDecimal.ZERO);
      clonedLine.setReinvoice(false);
      clonedLine.setCreationDate(new Date());
      clonedLine.setUpdated(new Date());
      clonedLine.setCreatedBy(currentUser);
      clonedLine.setUpdatedBy(currentUser);
    }

    return clone;
  }
}
