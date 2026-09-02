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

package com.etendoerp.go.rest;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountIdentity;

/**
 * Single point of access to an account's linked SSO identities (ETP-5115).
 *
 * <p><strong>Why this class exists.</strong> An identity used to live in four inline columns on the
 * account row ({@code AUTH_PROVIDER}, {@code EXTERNAL_SUBJECT}, {@code EXTERNAL_EMAIL},
 * {@code LAST_SSO_LOGIN}) under a unique over the first two. That is structurally <em>one</em>
 * identity per account, which is what blocked linking a second provider. The identities now live in
 * {@code ETGO_Account_Identity}, one row per provider, and every read and write goes through here.
 *
 * <p><strong>There is no backfill and no migration script.</strong> The deployment model rules out a
 * {@code ModuleScript} data migration, and none is needed: every read of an identity is <em>per
 * account</em> — resolving an SSO login is "find by (provider, subject)", listing an account's
 * methods is "the rows for this account" — so the fallback in {@link #materialiseLegacyIdentity}
 * covers every case. An account migrates on its own, the first time anything touches it.
 *
 * <p><strong>The legacy columns are never written again, and never cleared.</strong> Clearing them
 * would need exactly the script this design avoids, and it would destroy the fallback: an account
 * with no child row <em>and</em> emptied columns has lost its identity, which locks the user out.
 * They retire on their own once no account is left unmigrated.
 *
 * <p><strong>Nothing here commits.</strong> The lazy migration saves through the caller's existing
 * transaction rather than opening a commit point of its own, so no call sequence gains a commit it
 * did not have before. If that transaction rolls back the row is simply not written and the next
 * read materialises it again — the operation is idempotent, so losing it costs nothing.
 */
final class AccountIdentityDalHelper {

  private static final Logger log = LogManager.getLogger();

  private static final String ZERO_ID = "0";
  private static final String PARAM_AUTH_PROVIDER = "authProvider";
  private static final String PARAM_EXTERNAL_SUBJECT = "externalSubject";
  private static final String PARAM_ACCOUNT = "account";
  private static final String IDENTITY_QUERY = "as identity where identity.";
  private static final String AND_IDENTITY = " and identity.";
  private static final String ACTIVE_IDENTITY_FILTER = " and identity.active = true";

  private AccountIdentityDalHelper() {
  }

  /**
   * Resolves the account owning an identity, by the provider's own stable subject claim.
   *
   * <p>Reads the child table first and falls back to the legacy inline columns, materialising the
   * child row when it finds one there. Replaces the direct query on the account's own columns.
   *
   * @param provider provider id, e.g. {@code google}
   * @param subject the subject claim asserted by that provider
   * @return the owning account, or null when the identity is not linked to any active account
   */
  static Account findAccountByIdentity(String provider, String subject) {
    if (StringUtils.isAnyBlank(provider, subject)) {
      return null;
    }
    AccountIdentity identity = findIdentity(provider, subject);
    if (identity != null) {
      return identity.getAccount();
    }
    Account legacy = findAccountByLegacyIdentity(provider, subject);
    if (legacy != null) {
      materialiseLegacyIdentity(legacy);
    }
    return legacy;
  }

  /**
   * Lists an account's linked identities, migrating it off the legacy columns on the way if it has
   * not been migrated yet.
   *
   * @param account account to list, may be null
   * @return its identities, newest link first; empty when the account has none
   */
  static List<AccountIdentity> identitiesFor(Account account) {
    if (account == null) {
      return Collections.emptyList();
    }
    List<AccountIdentity> identities = queryIdentitiesFor(account);
    if (!identities.isEmpty()) {
      return identities;
    }
    AccountIdentity materialised = materialiseLegacyIdentity(account);
    return materialised == null ? Collections.emptyList() : Collections.singletonList(materialised);
  }

  /**
   * Returns the account's identity for one provider, or null when that provider is not linked.
   *
   * @param account account to look in, may be null
   * @param provider provider id to look for
   * @return the matching identity, or null
   */
  static AccountIdentity identityForProvider(Account account, String provider) {
    if (account == null || StringUtils.isBlank(provider)) {
      return null;
    }
    for (AccountIdentity identity : identitiesFor(account)) {
      if (StringUtils.equals(identity.getAuthProvider(), provider)) {
        return identity;
      }
    }
    return null;
  }

  /**
   * Links an identity to an account.
   *
   * <p>Does not commit — see the class note. The caller is expected to be inside a transaction that
   * will.
   *
   * @param account account to link to
   * @param provider provider id
   * @param subject the provider's subject claim
   * @param externalEmail the address the provider asserted, which may differ from the account's
   * @param linkedAt when the link was made, also recorded as the first login through it
   * @return the persisted identity
   */
  static AccountIdentity link(Account account, String provider, String subject, String externalEmail,
      Date linkedAt) {
    AccountIdentity identity = OBProvider.getInstance().get(AccountIdentity.class);
    identity.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    identity.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    identity.setAccount(account);
    identity.setAuthProvider(provider);
    identity.setExternalSubject(subject);
    identity.setExternalEmail(externalEmail);
    identity.setLinked(linkedAt);
    identity.setLastSSOLogin(linkedAt);
    OBDal.getInstance().save(identity);
    return identity;
  }

  /**
   * Records a sign-in through an existing identity, refreshing the address the provider asserted.
   *
   * @param identity identity that was used, may be null
   * @param externalEmail the address asserted on this sign-in
   * @param loginAt when it happened
   */
  static void recordLogin(AccountIdentity identity, String externalEmail, Date loginAt) {
    if (identity == null) {
      return;
    }
    identity.setExternalEmail(externalEmail);
    identity.setLastSSOLogin(loginAt);
    OBDal.getInstance().save(identity);
  }

  /**
   * Links the identity when the account can accept it, reproducing <em>exactly</em> the rule the
   * inline columns enforced: an account carrying no identity accepts one, an account already
   * carrying this same identity accepts it again as a no-op, and anything else is a conflict.
   *
   * <p><strong>An account that already has one identity still refuses a second, different one.</strong>
   * The child table makes several identities per account <em>possible</em>, but making them
   * <em>allowed</em> here is a product decision that belongs to the explicit, authenticated linking
   * flow, not to a side effect of signing in. Relaxing it in this phase would mean any provider
   * asserting a matching address could attach itself to an existing account silently — a different
   * and much weaker rule than the one this replaces. The table is the prerequisite; the relaxation
   * is a later, deliberate step.
   *
   * @param account account to link to
   * @param provider provider id
   * @param subject the provider's subject claim
   * @param externalEmail the address the provider asserted
   * @return true when the account now carries this identity, false when it conflicts
   */
  static boolean linkIfCompatible(Account account, String provider, String subject,
      String externalEmail) {
    List<AccountIdentity> existing = identitiesFor(account);
    if (existing.isEmpty()) {
      link(account, provider, subject, externalEmail, new Date());
      return true;
    }
    AccountIdentity current = existing.get(0);
    return StringUtils.equals(current.getAuthProvider(), provider)
        && StringUtils.equals(current.getExternalSubject(), subject);
  }

  /**
   * Returns the account's only identity, or null when it has none.
   *
   * <p>Valid only while an account is limited to one identity, which
   * {@link #linkIfCompatible} still enforces. Once explicit linking allows a second, every caller
   * of this method has to name the identity it means instead — hence the name, which is meant to
   * stop compiling honestly rather than quietly return the wrong row.
   *
   * @param account account to read, may be null
   * @return its single identity, or null
   */
  static AccountIdentity soleIdentityOf(Account account) {
    List<AccountIdentity> identities = identitiesFor(account);
    return identities.isEmpty() ? null : identities.get(0);
  }

  private static AccountIdentity findIdentity(String provider, String subject) {
    OBQuery<AccountIdentity> query = OBDal.getInstance().createQuery(AccountIdentity.class,
        IDENTITY_QUERY + AccountIdentity.PROPERTY_AUTHPROVIDER + " = :" + PARAM_AUTH_PROVIDER
            + AND_IDENTITY + AccountIdentity.PROPERTY_EXTERNALSUBJECT + " = :"
            + PARAM_EXTERNAL_SUBJECT + ACTIVE_IDENTITY_FILTER + " and identity.account.active = true");
    query.setNamedParameter(PARAM_AUTH_PROVIDER, provider);
    query.setNamedParameter(PARAM_EXTERNAL_SUBJECT, subject);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  private static List<AccountIdentity> queryIdentitiesFor(Account account) {
    OBQuery<AccountIdentity> query = OBDal.getInstance().createQuery(AccountIdentity.class,
        IDENTITY_QUERY + AccountIdentity.PROPERTY_ACCOUNT + " = :" + PARAM_ACCOUNT
            + ACTIVE_IDENTITY_FILTER + " order by identity."
            + AccountIdentity.PROPERTY_CREATIONDATE + " desc");
    query.setNamedParameter(PARAM_ACCOUNT, account);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  private static Account findAccountByLegacyIdentity(String provider, String subject) {
    OBQuery<Account> query = OBDal.getInstance().createQuery(Account.class,
        "as account where account." + Account.PROPERTY_AUTHPROVIDER + " = :" + PARAM_AUTH_PROVIDER
            + " and account." + Account.PROPERTY_EXTERNALSUBJECT + " = :" + PARAM_EXTERNAL_SUBJECT
            + " and account.active = true");
    query.setNamedParameter(PARAM_AUTH_PROVIDER, provider);
    query.setNamedParameter(PARAM_EXTERNAL_SUBJECT, subject);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.uniqueResult();
  }

  /**
   * Copies an account's legacy inline identity into a child row, once.
   *
   * <p>{@code LINKED} is left null on purpose: the account row does not record when the user linked
   * the provider, and the row's creation date would answer "when the migration ran", which is a
   * different question. Null says we do not know, which is the truth.
   *
   * <p>Concurrency is handled by the unique over (account, provider) rather than by a pre-check: two
   * simultaneous first reads would both pass a check and both insert, whereas the constraint lets
   * exactly one win. The loser re-reads.
   *
   * @param account account whose legacy columns should be migrated
   * @return the identity now backing this account, or null when it has no legacy identity
   */
  private static AccountIdentity materialiseLegacyIdentity(Account account) {
    String provider = StringUtils.trimToNull((String) account.get(Account.PROPERTY_AUTHPROVIDER));
    String subject = StringUtils.trimToNull((String) account.get(Account.PROPERTY_EXTERNALSUBJECT));
    if (provider == null || subject == null) {
      return null;
    }
    AccountIdentity identity = OBProvider.getInstance().get(AccountIdentity.class);
    identity.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    identity.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    identity.setAccount(account);
    identity.setAuthProvider(provider);
    identity.setExternalSubject(subject);
    identity.setExternalEmail((String) account.get(Account.PROPERTY_EXTERNALEMAIL));
    identity.setLastSSOLogin((Date) account.get(Account.PROPERTY_LASTSSOLOGIN));
    identity.setLinked(null);
    try {
      OBDal.getInstance().save(identity);
      OBDal.getInstance().flush();
      log.debug("Migrated the inline SSO identity of one account onto its own row");
      return identity;
    } catch (RuntimeException e) {
      log.debug("Concurrent migration of an inline SSO identity, re-reading", e);
      AccountIdentity winner = findIdentity(provider, subject);
      return winner != null ? winner : null;
    }
  }
}
