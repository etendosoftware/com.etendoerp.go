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

package com.etendoerp.go.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-6223 — the edited expiry date of a demo, {@code ETGO_ACCOUNT.DEMO_EXPIRES_AT}.
 *
 * <p>The date lives on the platform account, where Classic (window <i>Etendo GO Accounts</i>) and
 * the signup API ({@code PATCH /accounts/{id}}) edit it, and applies to every demo the account
 * owns. The trial is read per environment, so {@link #findTrialEnd} goes from the environment to
 * the account behind its owner ({@code EM_ETGO_Is_Owner}), the same link
 * {@code GoAccountResolver#findAccountByUsername} follows: the owner's username is the account
 * email, plus a {@code +<slug>} suffix ({@code [a-z0-9]} only) from its second environment on.
 *
 * <p><b>The date is a calendar day, in UTC.</b> A demo that expires on {@code 2026-12-01} ends at
 * {@code 2026-12-01T00:00:00Z}: that is the instant the access check refuses from and the date the
 * notices and the API report. The column stores the day at local midnight, as Classic's date
 * fields do, and is read back as the same day whatever the server's time zone.
 */
public class AccountDemoExpiry {

  private static final Logger log = LogManager.getLogger(AccountDemoExpiry.class);

  /** The account emails an owner username may stand for: itself, and itself without the suffix. */
  private static final String OWNER_EMAILS = "(lower(u.username),"
      + " lower(regexp_replace(u.username, '\\+[a-z0-9]+$', '')))";

  /**
   * The owner's account: its email is the owner's username, with or without the client suffix.
   * Both sides are lowercased because the account lookup is case-insensitive.
   */
  private static final String TRIAL_END_SQL = "SELECT a.demo_expires_at FROM ad_user u"
      + " JOIN etgo_account a ON lower(a.email) IN " + OWNER_EMAILS
      + " WHERE u.ad_client_id = :clientId AND u.em_etgo_is_owner = 'Y' AND u.isactive = 'Y'"
      + " AND a.isactive = 'Y' AND a.demo_expires_at IS NOT NULL"
      + " ORDER BY a.demo_expires_at LIMIT 1";

  /** The other way round: the active environments an account owns, oldest first. */
  private static final String OWNED_CLIENTS_SQL = "SELECT u.ad_client_id FROM ad_user u"
      + " JOIN ad_client c ON c.ad_client_id = u.ad_client_id"
      + " WHERE u.em_etgo_is_owner = 'Y' AND u.isactive = 'Y' AND c.isactive = 'Y'"
      + " AND u.ad_client_id <> '0' AND lower(:email) IN " + OWNER_EMAILS
      + " ORDER BY c.created";

  /**
   * The edited trial end of a demo environment.
   *
   * <p>A read that fails is logged and reads as "not edited": the demo then keeps its standard
   * trial, which is what it had before the date existed, instead of failing the access check.
   *
   * @param clientId the environment's {@code AD_Client_ID}
   * @return the instant the trial ends, or null when its owner's account has no edited date
   */
  public Instant findTrialEnd(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return null;
    }
    try {
      NativeQuery<Object> query = OBDal.getInstance().getSession()
          .createNativeQuery(TRIAL_END_SQL);
      query.setParameter("clientId", clientId);
      List<Object> rows = query.getResultList();
      if (rows.isEmpty() || !(rows.get(0) instanceof Date)) {
        return null;
      }
      return trialEndOf(dateOf((Date) rows.get(0)));
    } catch (RuntimeException e) {
      log.warn("Could not read the edited demo expiry of client {}; the standard trial applies",
          clientId, e);
      return null;
    }
  }

  /**
   * The environments an account owns: those whose owner user is the account.
   *
   * @param accountEmail the account's email
   * @return their {@code AD_Client_ID}s, oldest first; empty for a blank email
   */
  public List<String> findOwnedClientIds(String accountEmail) {
    if (StringUtils.isBlank(accountEmail)) {
      return List.of();
    }
    NativeQuery<Object> query = OBDal.getInstance().getSession()
        .createNativeQuery(OWNED_CLIENTS_SQL);
    query.setParameter("email", accountEmail.trim());
    List<String> clientIds = new ArrayList<>();
    for (Object row : query.getResultList()) {
      clientIds.add(String.valueOf(row));
    }
    return clientIds;
  }

  /**
   * Converts an expiry day to the instant its trial ends.
   *
   * @param expiresOn the day the demo expires
   * @return the instant its trial ends: the start of that day, in UTC
   */
  public static Instant trialEndOf(LocalDate expiresOn) {
    return expiresOn.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /**
   * Converts a trial end instant back to its expiry day.
   *
   * @param trialEndsAt the instant a trial ends
   * @return the day it expires, in UTC
   */
  public static LocalDate expiryDateOf(Instant trialEndsAt) {
    return LocalDate.ofInstant(trialEndsAt, ZoneOffset.UTC);
  }

  /**
   * Builds the value persisted for an expiry day.
   *
   * @param expiresOn the day to store in {@code DEMO_EXPIRES_AT}
   * @return the column value: that day at local midnight
   */
  public static Date toStored(LocalDate expiresOn) {
    return Timestamp.valueOf(expiresOn.atStartOfDay());
  }

  /**
   * Reads the expiry day back from a stored value.
   *
   * @param stored a {@code DEMO_EXPIRES_AT} value
   * @return the day it holds
   */
  public static LocalDate dateOf(Date stored) {
    return new Timestamp(stored.getTime()).toLocalDateTime().toLocalDate();
  }
}
