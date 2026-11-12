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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.accounts.AccountGroupService.MembershipOutcome;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountGroup;

/**
 * Groups of accounts and their members on a real database: the module ships the {@code beta}
 * group, a key is unique, and adding or removing a member is idempotent.
 *
 * <p>The service commits, so every row a test creates (its groups, its account and their
 * memberships, removed with them by the cascading foreign keys) is deleted afterwards.
 *
 * @covers com.etendoerp.go.accounts.AccountGroupService
 */
public class AccountGroupServiceIntegrationTest extends OBBaseTest {

  private final AccountGroupService service = new AccountGroupService();
  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final String groupKey = "itest-" + suffix;
  private String accountId;

  @Before
  public void createAccount() {
    setSystemAdministratorContext();
    Account account = OBProvider.getInstance().get(Account.class);
    account.setClient(OBDal.getInstance().getProxy(Client.class, "0"));
    account.setOrganization(OBDal.getInstance().getProxy(Organization.class, "0"));
    account.setEmail("group-itest-" + suffix + "@example.com");
    account.setName("Group Test");
    account.setStatus("active");
    account.setSignupSendEmails(false);
    account.setSupportAccount(false);
    account.setMarketingConsent(false);
    OBDal.getInstance().save(account);
    OBDal.getInstance().commitAndClose();
    accountId = account.getId();
  }

  @After
  public void deleteFixtures() {
    setSystemAdministratorContext();
    OBDal.getInstance().getSession()
        .createNativeQuery("DELETE FROM etgo_group WHERE value LIKE :prefix")
        .setParameter("prefix", "itest-" + suffix + "%")
        .executeUpdate();
    OBDal.getInstance().getSession()
        .createNativeQuery("DELETE FROM etgo_account WHERE etgo_account_id = :id")
        .setParameter("id", accountId)
        .executeUpdate();
    OBDal.getInstance().commitAndClose();
  }

  @Test
  public void theModuleShipsTheBetaGroup() {
    assertTrue(service.list().stream().anyMatch(group -> "beta".equals(group.key())
        && "Beta".equals(group.name()) && group.active()));
  }

  @Test
  public void aGroupIsCreatedOnceAndListed() {
    assertTrue(service.create(groupKey, "Integration", "Created by a test").isPresent());

    assertFalse("a key is unique", service.create(groupKey, "Again", null).isPresent());
    assertTrue(service.list().stream().anyMatch(group -> groupKey.equals(group.key())
        && "Created by a test".equals(group.description())));
  }

  @Test
  public void addingAndRemovingAMemberIsIdempotent() {
    service.create(groupKey, "Integration", null);

    assertEquals(MembershipOutcome.CHANGED, service.add(groupKey, accountId));
    assertEquals(MembershipOutcome.UNCHANGED, service.add(groupKey, accountId));
    List<AccountGroupService.Member> members = service.members(groupKey).orElseThrow();
    assertEquals(1, members.size());
    assertEquals(accountId, members.get(0).accountId());
    assertEquals(List.of(groupKey), service.groupKeysOf(accountId));

    assertEquals(MembershipOutcome.CHANGED, service.remove(groupKey, accountId));
    assertEquals(MembershipOutcome.UNCHANGED, service.remove(groupKey, accountId));
    assertTrue(service.members(groupKey).orElseThrow().isEmpty());
    assertTrue(service.groupKeysOf(accountId).isEmpty());
  }

  @Test
  public void aMembershipDeactivatedInClassicIsNoMembershipUntilAddedAgain() {
    service.create(groupKey, "Integration", null);
    service.add(groupKey, accountId);
    deactivateMemberships();

    assertTrue(service.members(groupKey).orElseThrow().isEmpty());
    assertTrue(service.groupKeysOf(accountId).isEmpty());
    assertEquals(MembershipOutcome.CHANGED, service.add(groupKey, accountId));
    assertEquals(List.of(groupKey), service.groupKeysOf(accountId));
  }

  @Test
  public void anUnknownGroupOrAccountIsReported() {
    service.create(groupKey, "Integration", null);

    assertFalse(service.members(groupKey + "-missing").isPresent());
    assertEquals(MembershipOutcome.GROUP_NOT_FOUND, service.add(groupKey + "-missing", accountId));
    assertEquals(MembershipOutcome.ACCOUNT_NOT_FOUND,
        service.add(groupKey, "0123456789ABCDEF0123456789ABCDEF"));
    assertEquals(MembershipOutcome.ACCOUNT_NOT_FOUND,
        service.remove(groupKey, "0123456789ABCDEF0123456789ABCDEF"));
  }

  private void deactivateMemberships() {
    setSystemAdministratorContext();
    OBDal.getInstance().getSession()
        .createNativeQuery("UPDATE " + AccountGroup.TABLE_NAME
            + " SET isactive = 'N' WHERE etgo_account_id = :id")
        .setParameter("id", accountId)
        .executeUpdate();
    OBDal.getInstance().commitAndClose();
  }
}
