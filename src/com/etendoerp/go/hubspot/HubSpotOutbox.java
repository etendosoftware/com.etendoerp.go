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

package com.etendoerp.go.hubspot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * Where business code reports the account events HubSpot learns about. The other events (first
 * entry into the demo, payment, expired trial) are detected by {@link HubSpotSyncService} itself.
 *
 * <p>Each call adds a row to the outbox in the caller's transaction and does not commit: the event
 * exists exactly when the change it reports does. Only accounts the app's own registration
 * created are reported; one created through the signup API carries a channel and is left out.
 *
 * <p>Reporting never fails the caller. The row is written in plain JDBC inside a savepoint: a
 * failed write is rolled back alone and logged, and Hibernate never sees the exception. Through a
 * Hibernate query it would mark the whole transaction rollback-only, and the registration or
 * onboarding would then be rolled back silently at its commit.
 */
public final class HubSpotOutbox {

  private static final Logger log = LogManager.getLogger(HubSpotOutbox.class);

  static final String FIELD_COMPANY_NAME = "companyName";
  static final String FIELD_COUNTRY = "country";

  // clock_timestamp(), not now(): now() is the transaction's start, so two events reported in one
  // transaction (a first entry and a payment detected in the same pass) would tie, and the sync
  // sends an account's events in CREATED order.
  static final String ENQUEUE_SQL = "insert into etgo_hubspot_outbox"
      + " (etgo_hubspot_outbox_id, ad_client_id, ad_org_id, isactive, created, createdby, updated,"
      + " updatedby, etgo_account_id, event_type, environment_id, payload, status, attempt_count)"
      + " values (get_uuid(), '0', '0', 'Y', clock_timestamp(), '0', clock_timestamp(), '0',"
      + " ?, ?, ?, ?, 'PENDING', 0)"
      + " on conflict (etgo_account_id, event_type) do nothing";

  private static final String SELF_SERVE_ACCOUNT_SQL = "select etgo_account_id from etgo_account"
      + " where lower(email) = lower(?) and isactive = 'Y' and signup_channel is null";

  private static final String COUNTRY_NAME_SQL = "select name from c_country"
      + " where countrycode = ?";

  /** One write against the open connection. */
  @FunctionalInterface
  interface JdbcWrite {
    /**
     * Writes.
     *
     * @param connection the caller's connection, inside the savepoint
     * @throws SQLException when the write fails; the savepoint is then rolled back
     */
    void run(Connection connection) throws SQLException;
  }

  private HubSpotOutbox() {
  }

  /**
   * Reports a new account.
   *
   * @param account the account just created
   */
  public static void accountCreated(Account account) {
    if (account == null || StringUtils.isNotBlank(account.getSignupChannel())) {
      return;
    }
    String accountId = account.getId();
    write("account " + accountId + " created",
        connection -> enqueue(connection, accountId, HubSpotOutboxStore.ACCOUNT_CREATED, null,
            null));
  }

  /**
   * Reports a demo environment ready for its owner: the moment the trial starts and the deal is
   * created.
   *
   * @param accountEmail the address of the account that created it
   * @param clientId     the demo's {@code AD_Client_ID}
   * @param companyName  the company name the owner entered
   * @param countryCode  the ISO code of the company's country
   */
  public static void demoReady(String accountEmail, String clientId, String companyName,
      String countryCode) {
    if (StringUtils.isBlank(accountEmail) || StringUtils.isBlank(clientId)) {
      return;
    }
    write("demo " + clientId + " ready", connection -> {
      String accountId = selectString(connection, SELF_SERVE_ACCOUNT_SQL, accountEmail.trim());
      if (accountId == null) {
        return;
      }
      enqueue(connection, accountId, HubSpotOutboxStore.DEMO_READY, clientId,
          demoPayload(companyName, countryName(connection, countryCode)));
    });
  }

  static boolean enqueue(Connection connection, String accountId, String eventType,
      String environmentId, String payload) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ENQUEUE_SQL)) {
      statement.setString(1, accountId);
      statement.setString(2, eventType);
      statement.setString(3, environmentId);
      statement.setString(4, payload);
      return statement.executeUpdate() > 0;
    }
  }

  static String demoPayload(String companyName, String country) {
    try {
      JSONObject payload = new JSONObject();
      payload.put(FIELD_COMPANY_NAME, StringUtils.trimToEmpty(companyName));
      payload.put(FIELD_COUNTRY, StringUtils.trimToEmpty(country));
      return payload.toString();
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the demo event payload", e);
    }
  }

  /** The country's name, which is what HubSpot's free-text country holds; the code if unknown. */
  private static String countryName(Connection connection, String countryCode)
      throws SQLException {
    if (StringUtils.isBlank(countryCode)) {
      return null;
    }
    String code = countryCode.trim().toUpperCase();
    return StringUtils.defaultString(selectString(connection, COUNTRY_NAME_SQL, code), code);
  }

  private static String selectString(Connection connection, String sql, String parameter)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  static void write(String what, JdbcWrite write) {
    try {
      Session session = OBDal.getInstance().getSession();
      // Pending business writes go to the database first, outside the savepoint, so the
      // savepoint covers the outbox row alone. Should they fail here, they fail again, and are
      // reported to the caller, at its own commit.
      session.flush();
      session.doWork(connection -> {
        Savepoint savepoint = connection.setSavepoint();
        try {
          write.run(connection);
          connection.releaseSavepoint(savepoint);
        } catch (SQLException | RuntimeException e) {
          // Handled here, never rethrown: Hibernate must not see it (see the class comment).
          connection.rollback(savepoint);
          logLost(what, e);
        }
      });
    } catch (RuntimeException e) { // NOSONAR - reporting to HubSpot must never fail the caller
      logLost(what, e);
    }
  }

  private static void logLost(String what, Exception e) {
    log.error("HubSpot event for {} could not be recorded; it will not be sent", what, e);
  }
}
