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

import java.io.File;
import java.io.IOException;

import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Attaches the ORIGINAL file of an inbox document to the invoice created from it, as the invoice's
 * main document (ETP-6051), through the very store the SPA's upload uses, so the policy, the storage
 * and the main-document marking are the same.
 *
 * <p>The file is copied, not moved: the inbox row keeps its own attachment as the audit trail of the
 * extraction. The copy is an owner-only temporary file read through the configured attach handler
 * ({@link NeoAttachmentsHelper#copyMainAttachmentToTemp}); the store removes it and so does the
 * {@code finally} here. The store catches its own errors, rolls the session back and answers
 * {@code >= 400}: that is a failure of the confirmation, and the caller must stop and not complete.
 * The bytes go to disk, which is not transactional: a rollback after this call leaves an orphan
 * file, as the intake already accepts and logs.</p>
 */
final class InvoiceInboxConfirmAttachment {

  private static final String TABLE_NAME = "C_Invoice";
  private static final String SPEC_INVOICE = "purchase-invoice";
  private static final String ENTITY_HEADER = "Header";

  private InvoiceInboxConfirmAttachment() {
  }

  /**
   * Attaches the original file.
   *
   * @param incomingDocumentId the id of the inbox row, owner of the original
   * @param invoiceId          the id of the flushed invoice
   * @throws IOException                          when the copy cannot be made
   * @throws InvoiceInboxConfirmWork.Stop when the original is gone or the store refuses
   */
  static void attach(String incomingDocumentId, String invoiceId) throws IOException {
    File copy = NeoAttachmentsHelper.copyMainAttachmentToTemp(IncomingDocument.TABLE_NAME,
        incomingDocumentId);
    if (copy == null) {
      throw new InvoiceInboxConfirmWork.Stop(
          InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_NO_FILE));
    }
    try {
      NeoResponse stored = NeoAttachmentStore.store(new NeoAttachmentUpload(TABLE_NAME, invoiceId,
          invoiceHeaderTabId(), copy.getName(), copy.length(), name -> copy, true));
      if (stored.getHttpStatus() >= 400) {
        OBDal.getInstance().rollbackAndClose();
        throw new InvoiceInboxConfirmWork.Stop(
            InvoiceInboxMessages.text(InvoiceInboxMessages.CONFIRM_ATTACH_FAILED));
      }
    } finally {
      NeoAttachmentsHelper.cleanupTempFile(copy);
    }
  }

  /** The tab of the purchase invoice header, {@code null} to let the store take the first one. */
  private static String invoiceHeaderTabId() {
    SFSpec spec = NeoServletSupport.findSpec(SPEC_INVOICE);
    if (spec == null) {
      return null;
    }
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, ENTITY_HEADER, MatchMode.EXACT));
    criteria.setMaxResults(1);
    SFEntity entity = (SFEntity) criteria.uniqueResult();
    return entity == null || entity.getADTab() == null ? null : entity.getADTab().getId();
  }
}
