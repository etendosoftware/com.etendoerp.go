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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.test.base.OBBaseTest;

import com.smf.ticketbai.data.TbaiSyncinvoice;

/**
 * DB-backed regression coverage for {@link TbaiSyncStatusInjector}, exercising the real
 * {@code tbai_syncinvoice} native query against a live Hibernate session.
 *
 * <p>{@link TbaiSyncStatusInjectorTest} intentionally never reaches {@code OBDal} (its comment
 * says the happy path "requires a live tbai_syncinvoice table and is covered by integration
 * tests") — but no such integration test existed. That gap is exactly why a real bug in
 * {@code fetchLatestByInvoice()} shipped silently: it called
 * {@code session.createNativeQuery(sql, Object[].class)}, a JPA-style overload that maps the
 * {@code Class} argument onto an <em>entity</em> ({@code addEntity(alias, resultClass.getName(),
 * ...)}  under the hood in Hibernate 5.6). {@code Object[]} is not a mapped entity, so every call
 * threw a {@code MappingException} — silently swallowed by {@code inject()}'s generic
 * {@code catch (Exception e)} — meaning {@code tbaiSyncEstado} was never added to ANY GET
 * response (list or detail), for ANY invoice, regardless of how much real data existed in
 * {@code tbai_syncinvoice}. Confirmed live against a running dev environment: the
 * {@code GET /sws/neo/sales-invoice/header} list response for an invoice with
 * {@code EM_Tbai_Issent=true} and a matching {@code tbai_syncinvoice} row of
 * {@code estado='Recibido'} carried no {@code tbaiSyncEstado} key at all.
 */
public class TbaiSyncStatusInjectorIntegrationTest extends OBBaseTest {

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testInjectReadsLatestEstadoFromRealTbaiSyncInvoiceTable() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = (Invoice) OBDal.getInstance().createCriteria(Invoice.class)
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull("Test fixture must contain at least one invoice to attach a sync row to",
          invoice);

      TbaiSyncinvoice older = OBProvider.getInstance().get(TbaiSyncinvoice.class);
      older.setClient(invoice.getClient());
      older.setOrganization(invoice.getOrganization());
      older.setInvoice(invoice);
      older.setEstado("Rechazado");
      older.setDescripcion("01");
      OBDal.getInstance().save(older);

      TbaiSyncinvoice latest = OBProvider.getInstance().get(TbaiSyncinvoice.class);
      latest.setClient(invoice.getClient());
      latest.setOrganization(invoice.getOrganization());
      latest.setInvoice(invoice);
      latest.setEstado("Recibido");
      latest.setDescripcion("00");
      OBDal.getInstance().save(latest);
      OBDal.getInstance().flush();

      JSONArray data = new JSONArray().put(new JSONObject().put("id", invoice.getId()));

      TbaiSyncStatusInjector.inject(data);

      assertEquals("inject() must read the MOST RECENT tbai_syncinvoice row (by 'created') and "
          + "write its estado into tbaiSyncEstado — this is the exact regression a "
          + "createNativeQuery(sql, Object[].class) MappingException silently masked",
          "Recibido", data.getJSONObject(0).getString("tbaiSyncEstado"));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testInjectLeavesRecordUnchangedWhenNoSyncRowExistsForInvoice() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = (Invoice) OBDal.getInstance().createCriteria(Invoice.class)
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull("Test fixture must contain at least one invoice", invoice);

      JSONArray data = new JSONArray()
          .put(new JSONObject().put("id", "NON-EXISTENT-INVOICE-ID-FOR-TBAI-TEST"));

      TbaiSyncStatusInjector.inject(data);

      assertEquals(false, data.getJSONObject(0).has("tbaiSyncEstado"));
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
