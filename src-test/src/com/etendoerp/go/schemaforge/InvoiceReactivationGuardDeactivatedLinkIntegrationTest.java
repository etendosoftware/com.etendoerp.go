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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.ReversedInvoice;
import org.openbravo.test.base.OBBaseTest;

/**
 * DB-backed regression coverage for {@link InvoiceReactivationGuard#completedRectificativaDocNos}
 * (ETP-5745 QA follow-up): the guard's SQL filters {@code r.isactive = 'Y'} on
 * {@code C_Invoice_Reverse}, but every case in {@link InvoiceReactivationGuardTest} mocks the
 * {@code ResultSet} directly, so none of them exercises that predicate against a real row. This
 * class creates a genuine {@code C_Invoice_Reverse} link between a fiscally-accepted original
 * invoice and a completed rectificativa, then deactivates it, proving a voided/deactivated link
 * is excluded from the query and therefore never blocks reactivation.
 *
 * <p>No existing {@code OBBaseTest} class covered {@link InvoiceReactivationGuard} — {@code make
 * find-tests FILE=InvoiceReactivationGuard} returns only {@code InvoiceReactivationGuardTest}
 * (Mockito) and {@code SalesInvoiceHeaderHandlerPostingTest} (same-package reference, Mockito) —
 * so this is a new class, justified because the behavior under test (the real SQL predicate
 * against a real row) cannot be observed through a mocked {@code ResultSet}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceReactivationGuard
 */
public class InvoiceReactivationGuardDeactivatedLinkIntegrationTest extends OBBaseTest {

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void aDeactivatedRectificativaLinkDoesNotBlockReactivation() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      // The rectificativa link row's own trigger (c_invoice_reverse_trg) rejects an insert whose
      // C_Invoice_Reverse.C_Invoice_ID (the rectificativa side) is already processed, so the
      // rectificativa fixture is driven to DocStatus=CO directly (bypassing the real completion
      // workflow) while staying Processed=false — exactly enough for the guard's own
      // docstatus = 'CO' SQL filter to match it, without upsetting the DB trigger.
      Invoice original = (Invoice) OBDal.getInstance().createCriteria(Invoice.class)
          .add(Restrictions.eq(Invoice.PROPERTY_PROCESSED, true))
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull("Test fixture must contain at least one processed invoice to use as the "
          + "fiscally-accepted original", original);

      @SuppressWarnings("unchecked")
      List<Invoice> rectificativaCandidates = OBDal.getInstance().createCriteria(Invoice.class)
          .add(Restrictions.eq(Invoice.PROPERTY_BUSINESSPARTNER, original.getBusinessPartner()))
          .add(Restrictions.eq(Invoice.PROPERTY_PROCESSED, false))
          .add(Restrictions.ne(Invoice.PROPERTY_ID, original.getId()))
          .setMaxResults(1)
          .list();
      assertTrue("Test fixture must contain a draft invoice from the original's business "
          + "partner to use as the rectificativa", !rectificativaCandidates.isEmpty());
      Invoice rectificativa = rectificativaCandidates.get(0);

      original.setAeatsiiEstado("CO");
      OBDal.getInstance().save(original);
      rectificativa.setDocumentStatus("CO");
      OBDal.getInstance().save(rectificativa);
      OBDal.getInstance().flush();

      ReversedInvoice link = OBProvider.getInstance().get(ReversedInvoice.class);
      link.setNewOBObject(true);
      link.setClient(original.getClient());
      link.setOrganization(original.getOrganization());
      link.setActive(true);
      link.setInvoice(rectificativa);
      link.setReversedInvoice(original);
      OBDal.getInstance().save(link);
      OBDal.getInstance().flush();

      // Sanity: with the link active, the guard must see the block — otherwise the test below
      // would pass for the wrong reason (no block regardless of isactive).
      InvoiceReactivationGuard.Block activeBlock =
          InvoiceReactivationGuard.computeBlock(original.getId());
      assertNotNull("Sanity: an active completed-rectificativa link must block reactivation",
          activeBlock);
      assertEquals(List.of(rectificativa.getDocumentNo()), activeBlock.rectificativaDocNos);

      // REVERSED_C_INVOICE_ID is left untouched, so the trigger's processed/docstatus guard on
      // UPDATE (which only fires when that column changes) does not apply to this deactivation.
      link.setActive(false);
      OBDal.getInstance().save(link);
      OBDal.getInstance().flush();

      assertNull("A deactivated (voided) rectificativa link must never block reactivation — "
          + "only an active link may", InvoiceReactivationGuard.computeBlock(original.getId()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
