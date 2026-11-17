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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountGroup;
import com.etendoerp.go.schemaforge.data.Group;

/**
 * ETP-6223 — groups of platform accounts ({@code ETGO_GROUP}) and their members
 * ({@code ETGO_ACCOUNT_GROUP}), as the signup API manages them. Classic edits the same rows in the
 * <i>Account Groups</i> window.
 *
 * <p><b>A group is only a label.</b> Nothing in the product reads membership to enable a feature
 * or change a limit; it exists to segment accounts (the module ships the {@code beta} group).
 *
 * <p>A membership deactivated in Classic is not a membership: it is neither listed nor reported,
 * and adding the account again reactivates it.
 *
 * <p>Groups and memberships belong to System ({@code AD_Client_ID = 0}), like the accounts, and
 * every call runs as System. Any active account can be a member, whichever way it was created.
 */
public class AccountGroupService {

  private static final String OPERATION = "account groups";
  private static final String SYSTEM_ID = "0";
  private static final String PARAM_KEY = "key";
  private static final String PARAM_GROUP = "group";
  private static final String PARAM_ACCOUNT = "account";
  private static final String ID_EQUALS = ".id = :";

  /**
   * A group key: lowercase letters, digits, {@code -} and {@code _}, starting with a letter or a
   * digit, at most 60 characters. The signup API and Classic ({@code AccountGroupKeyHandler})
   * enforce the same rule, so every key is reachable from the API.
   */
  private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,59}");

  /** A group as the API reports it. */
  public record GroupInfo(String key, String name, String description, boolean active) {
  }

  /** A member of a group. */
  public record Member(String accountId, String email, String name, Date addedAt) {
  }

  /** What adding or removing a membership did. */
  public enum MembershipOutcome {
    GROUP_NOT_FOUND,
    ACCOUNT_NOT_FOUND,
    /** The membership was created, or deleted. */
    CHANGED,
    /** Nothing to do: the account already was (or already was not) a member. */
    UNCHANGED
  }

  /**
   * Tells whether a text is a valid group key.
   *
   * @param key the candidate key
   * @return true when it matches the group key rule
   */
  public static boolean isValidKey(String key) {
    return key != null && KEY.matcher(key).matches();
  }

  /**
   * Creates a group.
   *
   * @param key the group's unique key, already validated
   * @param name its display name
   * @param description its description, may be null
   * @return the new group, or empty when a group with that key exists
   */
  public Optional<GroupInfo> create(String key, String name, String description) {
    return SystemContext.call(OPERATION, () -> {
      if (findGroup(key) != null) {
        return Optional.<GroupInfo>empty();
      }
      Group group = OBProvider.getInstance().get(Group.class);
      group.setClient(OBDal.getInstance().getProxy(Client.class, SYSTEM_ID));
      group.setOrganization(OBDal.getInstance().getProxy(Organization.class, SYSTEM_ID));
      group.setSearchKey(key);
      group.setName(name);
      group.setDescription(description);
      OBDal.getInstance().save(group);
      OBDal.getInstance().commitAndClose();
      return Optional.of(new GroupInfo(key, name, description, true));
    });
  }

  /**
   * @return every group, active or not, by key
   */
  public List<GroupInfo> list() {
    return SystemContext.call(OPERATION, () -> {
      OBQuery<Group> query = OBDal.getInstance().createQuery(Group.class,
          "as g order by g." + Group.PROPERTY_SEARCHKEY);
      query.setFilterOnActive(false);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      List<GroupInfo> groups = new ArrayList<>();
      for (Group group : query.list()) {
        groups.add(info(group));
      }
      return groups;
    });
  }

  /**
   * The members of a group, by email.
   *
   * @param key the group's key
   * @return its members, or empty when there is no such group
   */
  public Optional<List<Member>> members(String key) {
    return SystemContext.call(OPERATION, () -> {
      Group group = findGroup(key);
      if (group == null) {
        return Optional.<List<Member>>empty();
      }
      OBQuery<AccountGroup> query = OBDal.getInstance().createQuery(AccountGroup.class,
          "as m where m." + AccountGroup.PROPERTY_ACCOUNTGROUP + ID_EQUALS + PARAM_GROUP
              + " order by m." + AccountGroup.PROPERTY_ETENDOGOACCOUNT + "."
              + Account.PROPERTY_EMAIL);
      query.setNamedParameter(PARAM_GROUP, group.getId());
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      List<Member> members = new ArrayList<>();
      for (AccountGroup membership : query.list()) {
        Account account = membership.getEtendoGoAccount();
        members.add(new Member(account.getId(), account.getEmail(), account.getName(),
            membership.getCreationDate()));
      }
      return Optional.of(members);
    });
  }

  /**
   * Makes an account a member of a group; a no-op when it already is.
   *
   * @param key the group's key
   * @param accountId the account's id
   * @return what happened
   */
  public MembershipOutcome add(String key, String accountId) {
    return SystemContext.call(OPERATION, () -> {
      Group group = findGroup(key);
      if (group == null) {
        return MembershipOutcome.GROUP_NOT_FOUND;
      }
      Account account = findActiveAccount(accountId);
      if (account == null) {
        return MembershipOutcome.ACCOUNT_NOT_FOUND;
      }
      AccountGroup existing = findMembership(group, account);
      if (existing != null && Boolean.TRUE.equals(existing.isActive())) {
        return MembershipOutcome.UNCHANGED;
      }
      if (existing != null) {
        // Deactivated in Classic: a member again.
        existing.setActive(true);
        OBDal.getInstance().save(existing);
        OBDal.getInstance().commitAndClose();
        return MembershipOutcome.CHANGED;
      }
      AccountGroup membership = OBProvider.getInstance().get(AccountGroup.class);
      membership.setClient(OBDal.getInstance().getProxy(Client.class, SYSTEM_ID));
      membership.setOrganization(OBDal.getInstance().getProxy(Organization.class, SYSTEM_ID));
      membership.setAccountGroup(group);
      membership.setEtendoGoAccount(account);
      OBDal.getInstance().save(membership);
      OBDal.getInstance().commitAndClose();
      return MembershipOutcome.CHANGED;
    });
  }

  /**
   * Takes an account out of a group; a no-op when it is not a member.
   *
   * @param key the group's key
   * @param accountId the account's id
   * @return what happened
   */
  public MembershipOutcome remove(String key, String accountId) {
    return SystemContext.call(OPERATION, () -> {
      Group group = findGroup(key);
      if (group == null) {
        return MembershipOutcome.GROUP_NOT_FOUND;
      }
      Account account = findActiveAccount(accountId);
      if (account == null) {
        return MembershipOutcome.ACCOUNT_NOT_FOUND;
      }
      AccountGroup membership = findMembership(group, account);
      if (membership == null) {
        return MembershipOutcome.UNCHANGED;
      }
      boolean wasMember = Boolean.TRUE.equals(membership.isActive());
      OBDal.getInstance().remove(membership);
      OBDal.getInstance().commitAndClose();
      return wasMember ? MembershipOutcome.CHANGED : MembershipOutcome.UNCHANGED;
    });
  }

  /**
   * The keys of the groups an account belongs to, sorted.
   *
   * @param accountId the account's id
   * @return the group keys; empty when it belongs to none
   */
  public List<String> groupKeysOf(String accountId) {
    return SystemContext.call(OPERATION, () -> OBDal.getInstance().getSession()
        .createQuery("select m." + AccountGroup.PROPERTY_ACCOUNTGROUP + "."
            + Group.PROPERTY_SEARCHKEY + " from " + AccountGroup.ENTITY_NAME + " m where m."
            + AccountGroup.PROPERTY_ETENDOGOACCOUNT + ID_EQUALS + PARAM_ACCOUNT
            + " and m." + AccountGroup.PROPERTY_ACTIVE + " = true order by 1", String.class)
        .setParameter(PARAM_ACCOUNT, accountId)
        .getResultList());
  }

  private static GroupInfo info(Group group) {
    return new GroupInfo(group.getSearchKey(), group.getName(), group.getDescription(),
        Boolean.TRUE.equals(group.isActive()));
  }

  private static Group findGroup(String key) {
    OBQuery<Group> query = OBDal.getInstance().createQuery(Group.class,
        "as g where g." + Group.PROPERTY_SEARCHKEY + " = :" + PARAM_KEY);
    query.setNamedParameter(PARAM_KEY, key);
    query.setFilterOnActive(false);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  private static Account findActiveAccount(String accountId) {
    Account account = OBDal.getInstance().get(Account.class, accountId);
    return account != null && Boolean.TRUE.equals(account.isActive()) ? account : null;
  }

  private static AccountGroup findMembership(Group group, Account account) {
    OBQuery<AccountGroup> query = OBDal.getInstance().createQuery(AccountGroup.class,
        "as m where m." + AccountGroup.PROPERTY_ACCOUNTGROUP + ID_EQUALS + PARAM_GROUP
            + " and m." + AccountGroup.PROPERTY_ETENDOGOACCOUNT + ID_EQUALS + PARAM_ACCOUNT);
    query.setNamedParameter(PARAM_GROUP, group.getId());
    query.setNamedParameter(PARAM_ACCOUNT, account.getId());
    query.setFilterOnActive(false);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }
}
