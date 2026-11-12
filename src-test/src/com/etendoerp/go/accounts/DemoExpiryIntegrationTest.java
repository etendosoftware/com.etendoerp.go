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

package com.etendoerp.go.accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.payment.AccountDemoExpiry;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * The edited demo expiry on a real database: the environment's owner user leads to the account
 * whose {@code DEMO_EXPIRES_AT} moves the trial, with or without the {@code +<slug>} suffix of a
 * second environment's username, and the account leads back to the environments it owns.
 *
 * <p>The fixture is an account and an owner user added to an existing tenant of the sample data;
 * both are committed (the reads run outside the test's session) and deleted afterwards.
 *
 * @covers com.etendoerp.go.payment.AccountDemoExpiry
 * @covers com.etendoerp.go.accounts.DemoExpiryService
 */
public class DemoExpiryIntegrationTest extends OBBaseTest {

  private static final LocalDate DECEMBER_FIRST = LocalDate.of(2026, 12, 1);

  private final AccountDemoExpiry demoExpiry = new AccountDemoExpiry();
  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final String email = "demo-itest-" + suffix + "@example.com";
  private String clientId;
  private String accountId;
  private String ownerUserId;

  @Before
  public void createOwnerAndAccount() {
    setSystemAdministratorContext();
    clientId = (String) OBDal.getInstance().getSession()
        .createNativeQuery("SELECT ad_client_id FROM ad_client WHERE ad_client_id <> '0'"
            + " AND isactive = 'Y' ORDER BY ad_client_id LIMIT 1")
        .getSingleResult();
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().getProxy(Client.class, "0"));
    account.setOrganization(OBDal.getInstance().getProxy(Organization.class, "0"));
    account.setEmail(email);
    account.setName("Demo Test");
    account.setStatus("active");
    account.setSignupSendEmails(false);
    account.setSupportAccount(false);
    account.setMarketingConsent(false);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    accountId = account.getId();
    ownerUserId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    OBDal.getInstance().getSession()
        .createNativeQuery("INSERT INTO ad_user (ad_user_id, ad_client_id, ad_org_id, isactive,"
            + " created, createdby, updated, updatedby, name, username, em_etgo_is_owner)"
            + " VALUES (:id, :client, '0', 'Y', now(), '100', now(), '100', 'Demo Test',"
            + " :username, 'Y')")
        .setParameter("id", ownerUserId)
        .setParameter("client", clientId)
        // A second environment's username: the account email plus the client slug.
        .setParameter("username", email.toUpperCase() + "+itest")
        .executeUpdate();
    OBDal.getInstance().commitAndClose();
  }

  @After
  public void deleteFixtures() {
    setSystemAdministratorContext();
    OBDal.getInstance().getSession()
        .createNativeQuery("DELETE FROM ad_user WHERE ad_user_id = :id")
        .setParameter("id", ownerUserId)
        .executeUpdate();
    OBDal.getInstance().getSession()
        .createNativeQuery("DELETE FROM etgo_account WHERE etgo_account_id = :id")
        .setParameter("id", accountId)
        .executeUpdate();
    OBDal.getInstance().commitAndClose();
  }

  @Test
  public void anEnvironmentReadsTheEditedExpiryOfItsOwnersAccount() {
    storeExpiry(DECEMBER_FIRST);

    assertEquals(Instant.parse("2026-12-01T00:00:00Z"), demoExpiry.findTrialEnd(clientId));
  }

  @Test
  public void anAccountWithoutAnEditedExpiryLeavesTheStandardTrial() {
    storeExpiry(null);

    assertNull(demoExpiry.findTrialEnd(clientId));
  }

  @Test
  public void anAccountFindsTheEnvironmentsItOwns() {
    assertTrue(demoExpiry.findOwnedClientIds(email).contains(clientId));
    assertTrue(demoExpiry.findOwnedClientIds("nobody-" + suffix + "@example.com").isEmpty());
  }

  @Test
  public void theStoredDayIsReadBackAsTheSameDay() {
    storeExpiry(DECEMBER_FIRST);

    Account account = OBDal.getInstance().get(Account.class, accountId);
    assertEquals(DECEMBER_FIRST, AccountDemoExpiry.dateOf(account.getDemoExpiresAt()));
  }

  @Test
  public void anUnknownAccountIsNotFound() {
    assertEquals(DemoExpiryService.Outcome.ACCOUNT_NOT_FOUND, new DemoExpiryService()
        .update("0123456789ABCDEF0123456789ABCDEF", DECEMBER_FIRST).outcome());
  }

  @Test
  public void anAccountThatOwnsNoDemoIsRefusedAndKeepsItsDate() {
    String lonelyId = createAccountOwningNothing();
    try {
      DemoExpiryService.Result result = new DemoExpiryService().update(lonelyId, DECEMBER_FIRST);

      assertEquals(DemoExpiryService.Outcome.NO_DEMO, result.outcome());
      assertNull(OBDal.getInstance().get(Account.class, lonelyId).getDemoExpiresAt());
    } finally {
      OBDal.getInstance().getSession()
          .createNativeQuery("DELETE FROM etgo_account WHERE etgo_account_id = :id")
          .setParameter("id", lonelyId)
          .executeUpdate();
      OBDal.getInstance().commitAndClose();
    }
  }

  private void storeExpiry(LocalDate expiresOn) {
    setSystemAdministratorContext();
    Account account = OBDal.getInstance().get(Account.class, accountId);
    account.setDemoExpiresAt(expiresOn == null ? null : AccountDemoExpiry.toStored(expiresOn));
    OBDal.getInstance().save(account);
    OBDal.getInstance().commitAndClose();
  }

  private String createAccountOwningNothing() {
    setSystemAdministratorContext();
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().getProxy(Client.class, "0"));
    account.setOrganization(OBDal.getInstance().getProxy(Organization.class, "0"));
    account.setEmail("lonely-itest-" + suffix + "@example.com");
    account.setName("Lonely Test");
    account.setStatus("active");
    account.setSignupSendEmails(false);
    account.setSupportAccount(false);
    account.setMarketingConsent(false);
    OBDal.getInstance().save(account);
    OBDal.getInstance().commitAndClose();
    return account.getId();
  }
}
