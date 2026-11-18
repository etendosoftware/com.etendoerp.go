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

package com.etendoerp.go.payment.invoicing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.payment.StripeCurrencyScale;

/**
 * The SQL of the payments the ERP invoices: {@code ETGO_PAYMENT} and its history
 * {@code ETGO_PAYMENT_EVENT} (ETP-6322).
 *
 * <p>Plain JDBC on the caller's connection, like the HubSpot outbox: the tables are written from
 * the payment webhook's transaction and read by a server-to-server API, and neither needs a DAL
 * entity. Nothing here commits; the caller owns the transaction.
 *
 * <p>All rows belong to client and organization {@code 0}: a payment is about an Etendo account,
 * not about a tenant, and its environment may not exist yet when it is recorded.
 */
class PaymentLedgerStore {

  static final int STEP_MAX_LENGTH = 60;
  static final int REASON_MAX_LENGTH = 2000;
  static final int INVOICE_NUMBER_MAX_LENGTH = 255;
  static final int CHANNEL_MAX_LENGTH = 60;

  private static final String COLUMNS = "p.etgo_payment_id, p.status, p.payment_type,"
      + " p.billing_reason, p.billing_email, p.etgo_account_id, p.environment_client_id,"
      + " p.stripe_invoice_id, p.stripe_invoice_number, p.stripe_subscription_id,"
      + " p.stripe_customer_id, p.currency, p.amount_paid, p.subtotal, p.tax_amount,"
      + " p.total_amount, p.paid_at, p.period_start, p.period_end, p.lines, p.fiscal_data,"
      + " p.fiscal_data_at, p.payment_method, p.invoice_number, p.invoiced_at, p.created,"
      + " p.updated";

  private static final String INSERT_SQL = "insert into etgo_payment"
      + " (etgo_payment_id, ad_client_id, ad_org_id, isactive, created, createdby, updated,"
      + " updatedby, etgo_account_id, environment_client_id, status, payment_type,"
      + " billing_reason, billing_email, stripe_invoice_id, stripe_invoice_number,"
      + " stripe_event_id, stripe_subscription_id, stripe_customer_id, currency, amount_paid,"
      + " subtotal, tax_amount, total_amount, paid_at, period_start, period_end, lines,"
      + " fiscal_data, fiscal_data_at, payment_method)"
      + " values (get_uuid(), '0', '0', 'Y', now(), '0', now(), '0', ?, ?, ?, ?, ?, ?, ?, ?, ?,"
      + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
      + " on conflict (stripe_invoice_id) do nothing"
      + " returning etgo_payment_id";

  private static final String SELECT_BY_ID_SQL = "select " + COLUMNS
      + " from etgo_payment p where p.etgo_payment_id = ?";

  // A change waits for a concurrent one on the same payment, so two calls cannot both store a
  // different invoice number or both pass the state check.
  private static final String SELECT_FOR_UPDATE_SQL = SELECT_BY_ID_SQL + " for update";

  private static final String EXISTS_SQL = "select etgo_payment_id from etgo_payment"
      + " where stripe_invoice_id = ?";

  private static final String LIST_SQL ="select " + COLUMNS + " from etgo_payment p"
      + " where (cast(? as varchar) is null or p.status = ?)"
      + " and (cast(? as varchar) is null or (p.created, p.etgo_payment_id) >"
      + " (select a.created, a.etgo_payment_id from etgo_payment a where a.etgo_payment_id = ?))"
      + " order by p.created, p.etgo_payment_id"
      + " limit ?";

  // The purchase names the account: by the request id Etendo put in the subscription's metadata,
  // else by the subscription the purchase or the subscription row records.
  private static final String ACCOUNT_SQL = "select coalesce("
      + " (select r.etgo_account_id from etgo_checkout_request r"
      + " where r.request_id = ? or r.stripe_subscription_id = ? order by r.created desc limit 1),"
      + " (select s.etgo_account_id from etgo_subscription s"
      + " where s.stripe_subscription_id = ? and s.etgo_account_id is not null limit 1))";

  private static final String ACCOUNT_EMAIL_SQL = "select email from etgo_account"
      + " where etgo_account_id = ?";

  /**
   * The environment the payment is for. The subscription knows it once the environment exists,
   * and before that the purchase may already have created it.
   */
  private static final String ENVIRONMENT_SQL = "select coalesce("
      + " (select s.environment_client_id from etgo_subscription s"
      + " where s.stripe_subscription_id = ? and s.environment_client_id is not null limit 1),"
      + " (select r.created_client_id from etgo_checkout_request r"
      + " where (r.request_id = ? or r.stripe_subscription_id = ?)"
      + " and r.created_client_id is not null order by r.created desc limit 1))";

  // The tenant's own legal entity, else its first real organization; the one with a tax id first.
  private static final String FISCAL_SQL = "select o.name, o.social_name, oi.taxid, l.address1,"
      + " l.address2, l.postal, l.city, coalesce(r.name, l.regionname), c.countrycode, c.name"
      + " from ad_org o"
      + " join ad_orgtype ot on ot.ad_orgtype_id = o.ad_orgtype_id"
      + " left join ad_orginfo oi on oi.ad_org_id = o.ad_org_id"
      + " left join c_location l on l.c_location_id = oi.c_location_id"
      + " left join c_region r on r.c_region_id = l.c_region_id"
      + " left join c_country c on c.c_country_id = l.c_country_id"
      + " where o.ad_client_id = ? and o.ad_org_id <> '0' and o.isactive = 'Y'"
      + " order by ot.islegalentity desc, (oi.taxid is null), o.created"
      + " limit 1";

  private static final String UPDATE_PAYMENT = "update etgo_payment";

  private static final String UPDATE_FISCAL_SQL = UPDATE_PAYMENT
      + " set environment_client_id = ?, fiscal_data = ?, fiscal_data_at = now(), updated = now()"
      + " where etgo_payment_id = ? and invoice_number is null";

  private static final String SET_INVOICE_NUMBER_SQL = UPDATE_PAYMENT
      + " set invoice_number = ?, invoiced_at = now(), updated = now()"
      + " where etgo_payment_id = ? and invoice_number is null";

  private static final String SET_STATUS_SQL = UPDATE_PAYMENT
      + " set status = ?, updated = now() where etgo_payment_id = ?";

  private static final String INSERT_EVENT_SQL = "insert into etgo_payment_event"
      + " (etgo_payment_event_id, ad_client_id, ad_org_id, isactive, created, createdby,"
      + " updated, updatedby, etgo_payment_id, event_type, step, reason, api_channel)"
      + " values (get_uuid(), '0', '0', 'Y', clock_timestamp(), '0', clock_timestamp(), '0',"
      + " ?, ?, ?, ?, ?)";

  private static final String EVENTS_SQL = "select event_type, step, reason, api_channel, created"
      + " from etgo_payment_event where etgo_payment_id = ?"
      + " order by created, etgo_payment_event_id";

  /** One payment as read back: what the API answers, plus what the logic decides on. */
  static final class PaymentRow {
    private final String id;
    private final String status;
    private final String invoiceNumber;
    private final String subscriptionId;
    private final String environmentClientId;
    private final String fiscalData;
    private final JSONObject json;

    PaymentRow(String id, String status, String invoiceNumber, String subscriptionId,
        String environmentClientId, String fiscalData, JSONObject json) {
      this.id = id;
      this.status = status;
      this.invoiceNumber = invoiceNumber;
      this.subscriptionId = subscriptionId;
      this.environmentClientId = environmentClientId;
      this.fiscalData = fiscalData;
      this.json = json;
    }

    String id() {
      return id;
    }

    String status() {
      return status;
    }

    String invoiceNumber() {
      return invoiceNumber;
    }

    String subscriptionId() {
      return subscriptionId;
    }

    String environmentClientId() {
      return environmentClientId;
    }

    /** The stored snapshot as text, compared to tell whether a refresh changed it. */
    String fiscalData() {
      return fiscalData;
    }

    JSONObject json() {
      return json;
    }
  }

  /**
   * Records a payment once; a redelivered event finds the invoice already there.
   *
   * @return the new row's id, or {@code null} when the invoice was already recorded
   */
  String insert(Connection connection, StripeInvoicePayment payment, String accountId,
      String billingEmail, String environmentClientId, JSONObject fiscalData,
      JSONObject paymentMethod) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      int i = 1;
      statement.setString(i++, accountId);
      statement.setString(i++, environmentClientId);
      statement.setString(i++, PaymentStatus.PENDING_INVOICE);
      statement.setString(i++, payment.paymentType());
      statement.setString(i++, payment.billingReason());
      statement.setString(i++, billingEmail);
      statement.setString(i++, payment.invoiceId());
      statement.setString(i++, payment.invoiceNumber());
      statement.setString(i++, payment.eventId());
      statement.setString(i++, payment.subscriptionId());
      statement.setString(i++, payment.customerId());
      statement.setString(i++, payment.currency());
      statement.setBigDecimal(i++, payment.amountPaid());
      statement.setBigDecimal(i++, payment.subtotal());
      statement.setBigDecimal(i++, payment.taxAmount());
      statement.setBigDecimal(i++, payment.totalAmount());
      statement.setTimestamp(i++, timestamp(payment.paidAt()));
      statement.setTimestamp(i++, timestamp(payment.periodStart()));
      statement.setTimestamp(i++, timestamp(payment.periodEnd()));
      statement.setString(i++, payment.lines().toString());
      statement.setString(i++, fiscalData == null ? null : fiscalData.toString());
      statement.setTimestamp(i++, fiscalData == null ? null : Timestamp.from(Instant.now()));
      statement.setString(i, paymentMethod == null ? null : paymentMethod.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  String accountIdFor(Connection connection, String requestId, String subscriptionId)
      throws SQLException {
    String subscription = StringUtils.defaultString(subscriptionId);
    return selectString(connection, ACCOUNT_SQL, StringUtils.defaultString(requestId),
        subscription, subscription);
  }

  String accountEmail(Connection connection, String accountId) throws SQLException {
    return accountId == null ? null : selectString(connection, ACCOUNT_EMAIL_SQL, accountId);
  }

  String environmentClientFor(Connection connection, String subscriptionId, String requestId)
      throws SQLException {
    String subscription = StringUtils.defaultString(subscriptionId);
    return selectString(connection, ENVIRONMENT_SQL, subscription,
        StringUtils.defaultString(requestId), subscription);
  }

  /**
   * The fiscal data of a tenant's company, as the invoice prints it.
   *
   * @return {@code {name, legalName, taxId, address1, address2, postalCode, city, region,
   *     countryCode, country}}, blank fields as {@code null}; or {@code null} when the tenant has
   *     no organization of its own
   */
  JSONObject fiscalData(Connection connection, String clientId)
      throws SQLException, JSONException {
    if (StringUtils.isBlank(clientId)) {
      return null;
    }
    try (PreparedStatement statement = connection.prepareStatement(FISCAL_SQL)) {
      statement.setString(1, clientId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        String[] keys = { "name", "legalName", "taxId", "address1", "address2", "postalCode",
            "city", "region", "countryCode", "country" };
        JSONObject fiscal = new JSONObject();
        for (int i = 0; i < keys.length; i++) {
          fiscal.put(keys[i], textOrNull(result.getString(i + 1)));
        }
        return fiscal;
      }
    }
  }

  /** The page of payments after {@code afterId}, oldest first; {@code status} null for all. */
  List<PaymentRow> list(Connection connection, String status, String afterId, int limit)
      throws SQLException, JSONException {
    List<PaymentRow> rows = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST_SQL)) {
      statement.setString(1, status);
      statement.setString(2, status);
      statement.setString(3, afterId);
      statement.setString(4, afterId);
      statement.setInt(5, limit);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.add(row(result));
        }
      }
    }
    return rows;
  }

  /** @return whether a Stripe invoice is already recorded */
  boolean isRecorded(Connection connection, String invoiceId) throws SQLException {
    return selectString(connection, EXISTS_SQL, invoiceId) != null;
  }

  PaymentRow find(Connection connection, String id) throws SQLException, JSONException {
    return find(connection, id, false);
  }

  /** The payment, locked until the transaction ends when {@code forUpdate}. */
  PaymentRow find(Connection connection, String id, boolean forUpdate)
      throws SQLException, JSONException {
    try (PreparedStatement statement = connection.prepareStatement(
        forUpdate ? SELECT_FOR_UPDATE_SQL : SELECT_BY_ID_SQL)) {
      statement.setString(1, id);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? row(result) : null;
      }
    }
  }

  /** Stores a fresh snapshot; a payment whose invoice number was reported keeps its own. */
  boolean updateFiscalData(Connection connection, String id, String environmentClientId,
      JSONObject fiscalData) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_FISCAL_SQL)) {
      statement.setString(1, environmentClientId);
      statement.setString(2, fiscalData == null ? null : fiscalData.toString());
      statement.setString(3, id);
      return statement.executeUpdate() > 0;
    }
  }

  /** @return whether the number was stored; false when the payment already had one */
  boolean setInvoiceNumber(Connection connection, String id, String invoiceNumber)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SET_INVOICE_NUMBER_SQL)) {
      statement.setString(1, invoiceNumber);
      statement.setString(2, id);
      return statement.executeUpdate() > 0;
    }
  }

  void setStatus(Connection connection, String id, String status) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SET_STATUS_SQL)) {
      statement.setString(1, status);
      statement.setString(2, id);
      statement.executeUpdate();
    }
  }

  void addEvent(Connection connection, String paymentId, String eventType, String step,
      String reason, String channel) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT_SQL)) {
      statement.setString(1, paymentId);
      statement.setString(2, eventType);
      statement.setString(3, StringUtils.left(StringUtils.trimToNull(step), STEP_MAX_LENGTH));
      statement.setString(4, StringUtils.left(StringUtils.trimToNull(reason),
          REASON_MAX_LENGTH));
      statement.setString(5, StringUtils.left(channel, CHANNEL_MAX_LENGTH));
      statement.executeUpdate();
    }
  }

  /** The payment's history, oldest first: {@code [{event, step, reason, channel, at}]}. */
  JSONArray events(Connection connection, String paymentId) throws SQLException, JSONException {
    JSONArray events = new JSONArray();
    try (PreparedStatement statement = connection.prepareStatement(EVENTS_SQL)) {
      statement.setString(1, paymentId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          events.put(new JSONObject()
              .put("event", PaymentStatus.toApi(result.getString(1)))
              .put("step", textOrNull(result.getString(2)))
              .put("reason", textOrNull(result.getString(3)))
              .put("channel", textOrNull(result.getString(4)))
              .put("at", iso(result.getTimestamp(5))));
        }
      }
    }
    return events;
  }

  private static PaymentRow row(ResultSet result) throws SQLException, JSONException {
    String id = result.getString("etgo_payment_id");
    String status = result.getString("status");
    String invoiceNumber = result.getString("invoice_number");
    String subscriptionId = result.getString("stripe_subscription_id");
    String environmentClientId = result.getString("environment_client_id");
    String fiscalData = result.getString("fiscal_data");
    String currency = result.getString("currency");
    int scale = StripeCurrencyScale.exponent(currency);
    JSONObject json = new JSONObject()
        .put("id", id)
        .put("status", PaymentStatus.toApi(status))
        .put("paymentType", PaymentStatus.toApi(result.getString("payment_type")))
        .put("billingReason", textOrNull(result.getString("billing_reason")))
        .put("accountId", textOrNull(result.getString("etgo_account_id")))
        .put("billingEmail", textOrNull(result.getString("billing_email")))
        .put("environmentClientId", textOrNull(environmentClientId))
        .put("currency", currency)
        .put("amountPaid", amount(result.getBigDecimal("amount_paid"), scale))
        .put("subtotal", amount(result.getBigDecimal("subtotal"), scale))
        .put("taxAmount", amount(result.getBigDecimal("tax_amount"), scale))
        .put("totalAmount", amount(result.getBigDecimal("total_amount"), scale))
        .put("paidAt", iso(result.getTimestamp("paid_at")))
        .put("periodStart", iso(result.getTimestamp("period_start")))
        .put("periodEnd", iso(result.getTimestamp("period_end")))
        .put("lines", jsonArray(result.getString("lines")))
        .put("fiscalData", jsonObject(fiscalData))
        .put("fiscalDataAt", iso(result.getTimestamp("fiscal_data_at")))
        .put("paymentMethod", jsonObject(result.getString("payment_method")))
        .put("invoiceNumber", textOrNull(invoiceNumber))
        .put("invoicedAt", iso(result.getTimestamp("invoiced_at")))
        .put("stripe", new JSONObject()
            .put("invoiceId", result.getString("stripe_invoice_id"))
            .put("invoiceNumber", textOrNull(result.getString("stripe_invoice_number")))
            .put("subscriptionId", textOrNull(subscriptionId))
            .put("customerId", textOrNull(result.getString("stripe_customer_id"))))
        .put("created", iso(result.getTimestamp("created")))
        .put("updated", iso(result.getTimestamp("updated")));
    return new PaymentRow(id, status, invoiceNumber, subscriptionId, environmentClientId,
        fiscalData, json);
  }

  private static String selectString(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        if (parameters[i] == null) {
          statement.setNull(i + 1, Types.VARCHAR);
        } else {
          statement.setString(i + 1, parameters[i]);
        }
      }
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Object iso(Timestamp timestamp) {
    return timestamp == null ? JSONObject.NULL : timestamp.toInstant().toString();
  }

  /** A decimal string with the currency's decimals ({@code "25.00"}), never a float. */
  private static Object amount(BigDecimal value, int scale) {
    return value == null ? JSONObject.NULL
        : value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }

  private static Object textOrNull(String value) {
    return StringUtils.isBlank(value) ? JSONObject.NULL : value.trim();
  }

  private static Object jsonObject(String text) throws JSONException {
    return StringUtils.isBlank(text) ? JSONObject.NULL : new JSONObject(text);
  }

  private static JSONArray jsonArray(String text) throws JSONException {
    return StringUtils.isBlank(text) ? new JSONArray() : new JSONArray(text);
  }
}
