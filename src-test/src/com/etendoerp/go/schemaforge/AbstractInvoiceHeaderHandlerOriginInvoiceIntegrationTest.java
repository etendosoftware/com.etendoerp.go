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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.enterprise.inject.Vetoed;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.test.base.OBBaseTest;

/**
 * DB-backed regression coverage for the ETP-4919 multi-origin-invoice bug: the
 * "Import from Source Invoice" flow on a Factura Rectificativa only ever persisted ONE linked
 * origin invoice, via two converging bugs in {@link AbstractInvoiceHeaderHandler}:
 * <ul>
 *   <li>{@code persistOriginInvoice} unconditionally deleted ALL existing
 *   {@code C_Invoice_Reverse} links for the invoice before creating the single new one —
 *   importing from a second source invoice silently destroyed the link to the first.</li>
 *   <li>{@code enrichOriginInvoice} only ever read the FIRST matching row back
 *   ({@code ResultSet#next()} called once) — even on the rare occasion more than one link did
 *   survive in the DB, the GET response (and therefore the frontend's {@code RelatedDocuments})
 *   never saw more than one.</li>
 * </ul>
 *
 * <p>This test simulates the real-world sequence a user triggers by importing lines from two
 * different source invoices in two separate popup runs — two {@code PATCH} requests, each
 * capturing+persisting one additional origin id, exactly as
 * {@code AbstractInvoiceHeaderHandler#captureOriginInvoice}/{@code #persistOriginInvoice} are
 * invoked from {@code handle()}/{@code afterHandle()} in the real request lifecycle — against a
 * live Hibernate session and a live {@code C_Invoice_Reverse} table (no mocks), then asserts the
 * GET-response enrichment ({@code enrichOriginInvoice}) surfaces BOTH origins.
 */
public class AbstractInvoiceHeaderHandlerOriginInvoiceIntegrationTest extends OBBaseTest {

  /**
   * Minimal concrete subclass exposing the protected methods under test — same pattern as
   * {@code AbstractInvoiceHeaderHandlerTest.TestHandler}, duplicated here (rather than shared)
   * because it is package-private and declared {@code private static} inside that file.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class TestHandler extends AbstractInvoiceHeaderHandler {
    @Override
    protected String classifyDocType(DocumentType dt) {
      return SUBTYPE_FAC;
    }

    @Override
    protected String getInvoiceSubtypeKey() {
      return "arInvoiceSubtype";
    }

    @Override
    protected TotalDiscountService getTotalDiscountService() {
      // Not exercised by this test — applyTotalDiscountToRecord() null-guards on this.
      return null;
    }

    public void callCapture(NeoContext ctx) {
      captureOriginInvoice(ctx);
    }

    public void callPersist(NeoContext ctx) {
      persistOriginInvoice(ctx);
    }

    public void callEnrich(JSONObject rec, String id) throws Exception {
      enrichOriginInvoice(rec, id);
    }
  }

  private final TestHandler handler = new TestHandler();

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testImportingFromTwoSourceInvoicesPersistsAndEnrichesBothOrigins() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      // The target must NOT be processed/posted: C_Invoice_Reverse's own DB trigger
      // (c_invoice_reverse_trg, message @20501@ "Document posted/processed") rejects any
      // insert/update against a processed invoice's reverse links — exactly mirroring real
      // usage, since "Import from Source Invoice" only ever runs on a draft rectificativa.
      Invoice target = (Invoice) OBDal.getInstance().createCriteria(Invoice.class)
          .add(Restrictions.eq(Invoice.PROPERTY_PROCESSED, false))
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull("Test fixture must contain at least one non-processed invoice to use as "
          + "the rectificativa under test", target);

      // origin1/origin2 must share target's business partner: the same trigger also rejects
      // linking invoices across different business partners (message @NotEqualBPartner@).
      @SuppressWarnings("unchecked")
      List<Invoice> originCandidates = OBDal.getInstance().createCriteria(Invoice.class)
          .add(Restrictions.eq(Invoice.PROPERTY_BUSINESSPARTNER, target.getBusinessPartner()))
          .add(Restrictions.ne(Invoice.PROPERTY_ID, target.getId()))
          .setMaxResults(2)
          .list();
      assertTrue("Test fixture must contain at least 2 other invoices from the target's "
          + "business partner to use as origins", originCandidates.size() >= 2);
      Invoice origin1 = originCandidates.get(0);
      Invoice origin2 = originCandidates.get(1);

      // First "Import from Source Invoice" run — links origin1.
      JSONObject body1 = new JSONObject().put("originInvoice", origin1.getId());
      NeoContext ctx1 = NeoContext.builder()
          .httpMethod("PATCH").recordId(target.getId()).requestBody(body1).build();
      handler.callCapture(ctx1);
      handler.callPersist(ctx1);

      // Second, LATER "Import from Source Invoice" run — links origin2. Must NOT remove the
      // link to origin1 created above (that removal is exactly the ETP-4919 bug).
      JSONObject body2 = new JSONObject().put("originInvoice", origin2.getId());
      NeoContext ctx2 = NeoContext.builder()
          .httpMethod("PATCH").recordId(target.getId()).requestBody(body2).build();
      handler.callCapture(ctx2);
      handler.callPersist(ctx2);

      OBDal.getInstance().flush();

      JSONObject rec = new JSONObject().put("id", target.getId());
      handler.callEnrich(rec, target.getId());

      JSONArray origins = rec.getJSONArray("originInvoices");
      Set<String> enrichedOriginIds = new HashSet<>();
      for (int i = 0; i < origins.length(); i++) {
        enrichedOriginIds.add(origins.getJSONObject(i).getString("id"));
      }

      assertEquals("Both origin invoices imported in two separate popup runs must survive — "
          + "the second persistOriginInvoice call must not delete the first link, and "
          + "enrichOriginInvoice must return ALL linked origins, not just one",
          new HashSet<>(Arrays.asList(origin1.getId(), origin2.getId())),
          enrichedOriginIds);
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
