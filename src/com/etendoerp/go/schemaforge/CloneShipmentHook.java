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
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import javax.enterprise.context.ApplicationScoped;
import java.util.Calendar;
import java.util.Date;

/**
 * CloneRecordHook for Goods Shipments (M_InOut with issotrx='Y').
 *
 * Mirrors the pattern of {@link com.smf.jobs.defaults.CloneInvoiceHook}:
 * resets header state to Draft, copies lines, and leaves documentNo null so
 * that {@link NeoCloneRecordHandler} can assign the next sequence number.
 */
@ApplicationScoped
@Qualifier(ShipmentInOut.ENTITY_NAME)
public class CloneShipmentHook extends CloneRecordHook {

  @Override
  public boolean shouldCopyChildren(boolean uiCopyChildren) {
    return false;
  }

  @Override
  public BaseOBObject preCopy(BaseOBObject originalRecord) {
    return originalRecord;
  }

  @Override
  public BaseOBObject postCopy(BaseOBObject originalRecord, BaseOBObject newRecord) {
    ShipmentInOut original = (ShipmentInOut) originalRecord;
    ShipmentInOut clone = (ShipmentInOut) newRecord;
    User currentUser = OBContext.getOBContext().getUser();

    Date today = DateUtils.truncate(new Date(), Calendar.DATE);

    clone.setDocumentStatus("DR");
    clone.setDocumentAction("CO");
    clone.setPosted("N");
    clone.setProcessed(false);
    clone.setDocumentNo(null);
    clone.setMovementDate(today);
    clone.setCreationDate(new Date());
    clone.setUpdated(new Date());
    clone.setCreatedBy(currentUser);
    clone.setUpdatedBy(currentUser);

    for (ShipmentInOutLine line : original.getMaterialMgmtShipmentInOutLineList()) {
      BigDecimal movQty = line.getMovementQuantity();
      ShipmentInOutLine clonedLine = (ShipmentInOutLine) DalUtil.copy(line, false);
      clonedLine.setCanceledInoutLine(null);
      clonedLine.setSalesOrderLine(null);
      // Restore movementQuantity: listeners may reset it when canceledInoutLine is cleared
      if (movQty != null) {
        clonedLine.setMovementQuantity(movQty);
      }
      clonedLine.setCreationDate(new Date());
      clonedLine.setUpdated(new Date());
      clonedLine.setCreatedBy(currentUser);
      clonedLine.setUpdatedBy(currentUser);
      clonedLine.setShipmentReceipt(clone);
      clone.getMaterialMgmtShipmentInOutLineList().add(clonedLine);
    }

    OBDal.getInstance().save(clone);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(clone);
    return clone;
  }
}
