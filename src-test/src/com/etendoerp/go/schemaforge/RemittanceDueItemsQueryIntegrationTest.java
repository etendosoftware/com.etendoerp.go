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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.test.base.OBBaseTest;

/**
 * Real-database check that the HQL of {@link RemittanceDueItems} resolves and executes: the
 * candidate list and count, with and without the direction filter and the search, and the
 * re-check of a selection. The rules themselves are specified in {@code RemittanceDueItemsTest}
 * (as the query states them); this class proves the query is valid against the real mapping and
 * that whatever the sample data yields obeys the two rules that need no fixtures: the account's
 * currency and a due date on or before the remittance's.
 *
 * <p>No invoice fixtures. The remittance is an <b>unsaved</b> {@link Remittance} on the F&B sample
 * client's {@code Bank - Account 1} with a provisioned type ({@link
 * RemittanceSepaProvisioning#ensureClient}): the queries only bind its id, client, organization,
 * account, type and due date. <b>Nothing is committed</b>: provisioning only flushes and every test
 * asks the session handler to roll back.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceDueItems
 */
public class RemittanceDueItemsQueryIntegrationTest extends OBBaseTest {

  /** {@code Bank - Account 1} of the F&B sample client. */
  private static final String BANK_ACCOUNT_ID = "2C059762760F4F3D8A91342ED988DEB8";
  private static final LocalDate DUE_DATE = LocalDate.of(2030, 12, 31);

  private Client client;
  private FIN_FinancialAccount account;

  @Before
  public void rollbackEverything() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    client = OBDal.getInstance().get(Client.class, TEST_CLIENT_ID);
    account = OBDal.getInstance().get(FIN_FinancialAccount.class, BANK_ACCOUNT_ID);
    assertNotNull("the sample bank account must exist", account);
  }

  @Test
  public void theListAndCountRunWithoutADirectionFilter() throws Exception {
    OBContext.setAdminMode(true);
    try {
      // Provisioned, but the account is not linked yet: no link means no direction filter.
      RemittanceSepaProvisioning.ensureClient(client);
      Remittance remittance = remittance(RemittanceSepaProvisioning.SCHEME_CORE);
      assertEquals(null, RemittanceDueItems.direction(account,
          remittance.getRemittanceType().getPaymentMethod()));

      assertListObeysTheFixtureFreeRules(RemittanceDueItems.available(remittance,
          new JSONObject()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void theListAndCountRunWithEachDirectionAndASearch() throws Exception {
    OBContext.setAdminMode(true);
    try {
      RemittanceSepaProvisioning.ensureClient(client);
      account.setETGOAllowRemittance(true);
      OBDal.getInstance().save(account);
      RemittanceSepaProvisioning.ensureAccountLinks(account, true);

      Remittance collection = remittance(RemittanceSepaProvisioning.SCHEME_CORE);
      assertEquals(Boolean.TRUE, RemittanceDueItems.direction(account,
          collection.getRemittanceType().getPaymentMethod()));
      assertListObeysTheFixtureFreeRules(RemittanceDueItems.available(collection,
          new JSONObject()));
      assertListObeysTheFixtureFreeRules(RemittanceDueItems.available(collection,
          new JSONObject().put("search", "a").put("page", 2).put("pageSize", 5)));

      Remittance payment = remittance(RemittanceSepaProvisioning.SCHEME_SCT);
      assertEquals(Boolean.FALSE, RemittanceDueItems.direction(account,
          payment.getRemittanceType().getPaymentMethod()));
      assertListObeysTheFixtureFreeRules(RemittanceDueItems.available(payment,
          new JSONObject().put("search", "zzz-no-such-invoice")));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void theSelectionRecheckRunsAndRefusesAnUnknownSchedule() throws Exception {
    OBContext.setAdminMode(true);
    try {
      RemittanceSepaProvisioning.ensureClient(client);
      Remittance remittance = remittance(RemittanceSepaProvisioning.SCHEME_CORE);

      NeoResponse response = RemittanceDueItems.select(remittance, new JSONObject()
          .put("ids", new JSONArray().put("NO-SUCH-SCHEDULE-0000000000000000")));

      assertEquals(422, response.getHttpStatus());
      assertEquals("NO-SUCH-SCHEDULE-0000000000000000", response.getBody().getJSONObject("error")
          .getJSONArray("invalidIds").getString(0));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------- helpers

  /** An unsaved remittance of the provisioned {@code scheme} type on the bank account. */
  private Remittance remittance(String scheme) {
    RemittanceType type = RemittanceSepaProvisioning.findType(client, scheme);
    assertNotNull("ensureClient provisions the " + scheme + " type", type);
    Remittance remittance = OBProvider.getInstance().get(Remittance.class);
    remittance.setId("ETP6148ITDUEITEMS" + scheme);
    remittance.setClient(client);
    remittance.setOrganization(account.getOrganization());
    remittance.setFinancialAccount(account);
    remittance.setRemittanceType(type);
    remittance.setDueDate(Date.from(DUE_DATE.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    remittance.setProcessed(false);
    return remittance;
  }

  private void assertListObeysTheFixtureFreeRules(NeoResponse response) throws Exception {
    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    JSONArray items = body.getJSONArray("items");
    long total = body.getLong("total");
    assertTrue("a page never exceeds the total", items.length() <= Math.max(total, 0));
    String currency = account.getCurrency().getISOCode();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      assertEquals("the account currency only", currency, item.getString("currency"));
      assertFalse("due on or before the remittance due date",
          LocalDate.parse(item.getString("dueDate")).isAfter(DUE_DATE));
      assertFalse("an unsaved remittance has no lines", item.getBoolean("alreadyInRemittance"));
    }
  }
}
