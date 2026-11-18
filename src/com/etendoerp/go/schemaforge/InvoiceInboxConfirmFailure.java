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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Records that the confirmation of an inbox document failed (ETP-6051): in a NEW transaction, after
 * the failed unit was rolled back, the row becomes {@code ERROR} with the code
 * {@code CONFIRM_FAILED} and the translated reason in {@code ERROR_DETAIL}. A conditional
 * {@code UPDATE} like the discard's: it only moves a row that can still be confirmed, so a row that
 * another request confirmed or discarded meanwhile is never overwritten. It never throws: the answer
 * to the user does not depend on it.
 */
final class InvoiceInboxConfirmFailure {

  /** Longest reason stored: the column holds 2000 characters. */
  static final int MAX_DETAIL = 2000;

  private static final Logger log = LogManager.getLogger(InvoiceInboxConfirmFailure.class);
  private static final String UPDATE = "UPDATE etgo_incoming_document SET status = 'ERROR',"
      + " error_code = '" + InvoiceInboxConfirm.CONFIRM_FAILED + "', error_detail = :detail,"
      + " updated = now(), updatedby = :user WHERE etgo_incoming_document_id = :id"
      + " AND status IN ('PENDING_REVIEW', 'READY', 'ERROR') AND ad_client_id IN (:clients)";

  private InvoiceInboxConfirmFailure() {
  }

  /**
   * Records the failure.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @param reason   the translated reason, shown to the user
   */
  static void recordFailure(String recordId, String reason) {
    try {
      Session session = OBDal.getInstance().getSession();
      session.createNativeQuery(UPDATE)
          .setParameter("detail", StringUtils.abbreviate(StringUtils.defaultString(reason),
              MAX_DETAIL))
          .setParameter("user", OBContext.getOBContext().getUser().getId())
          .setParameter("id", recordId)
          .setParameterList("clients", OBContext.getOBContext().getReadableClients())
          .executeUpdate();
      OBDal.getInstance().commitAndClose();
    } catch (RuntimeException e) {
      log.warn("Could not record the failed confirmation of {}: {}", recordId,
          e.getClass().getSimpleName());
      OBDal.getInstance().rollbackAndClose();
    }
  }
}
