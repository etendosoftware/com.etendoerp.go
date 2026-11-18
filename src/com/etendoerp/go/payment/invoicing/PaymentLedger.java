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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.payment.invoicing.PaymentLedgerStore.PaymentRow;

/**
 * The payments the ERP turns into legal invoices (ETP-6322): one row per Stripe charge, a new
 * purchase or a renewal, recorded by the payment webhook and handed to the ERP through
 * {@link PaymentsApiServlet}.
 *
 * <p><b>Recording.</b> {@link #record} runs in the webhook's transaction and does not commit: the
 * payment exists exactly when the event that reports it was applied. If the row cannot be written,
 * the exception reaches the webhook, the event is marked failed and Stripe delivers it again; a
 * payment is never acknowledged without being kept. A redelivery finds the invoice already there
 * and adds nothing.
 *
 * <p><b>The company's fiscal data</b> (name, tax id, address) are a snapshot, one per payment, so a
 * renewal carries the data of its own date. They are taken when the payment is recorded, which for
 * a first payment is often before the environment exists, and then refreshed every time the ERP
 * reads the payment, until the ERP reports its invoice number: from then on the snapshot is the one
 * that invoice was issued with and never changes. Whether an invoice may go without them (a
 * simplified invoice) is the ERP's decision; the API only says what there is.
 */
public class PaymentLedger {

  private static final Logger log = LogManager.getLogger(PaymentLedger.class);

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  static final String CODE_PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
  static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";
  static final String CODE_INVOICE_NUMBER_CONFLICT = "INVOICE_NUMBER_CONFLICT";
  static final String CODE_PAYMENT_COMPLETED = "PAYMENT_COMPLETED";
  static final String CODE_INVOICE_NUMBER_MISSING = "INVOICE_NUMBER_MISSING";
  static final String CODE_INVALID_TRANSITION = "INVALID_TRANSITION";

  static final String FIELD_INVOICE_NUMBER = "invoiceNumber";
  static final String FIELD_EVENT = "event";
  static final String FIELD_STEP = "step";
  static final String FIELD_REASON = "reason";

  /** SQL against the open connection; JSON errors are programming errors. */
  @FunctionalInterface
  interface SqlWork<T> {
    /**
     * Runs the work.
     *
     * @param connection the open connection of the current transaction
     * @return the work's result
     * @throws SQLException  when a statement fails
     * @throws JSONException when a payment's JSON cannot be read or built
     */
    T run(Connection connection) throws SQLException, JSONException;
  }

  /** Where the SQL runs and how the API's transaction ends; replaced in unit tests. */
  interface Database {
    /**
     * Runs SQL work on the current transaction's connection.
     *
     * @param <T>  the work's result type
     * @param work the work
     * @return the work's result
     */
    <T> T call(SqlWork<T> work);

    /** Commits the current transaction. */
    void commit();

    /** Rolls the current transaction back. */
    void rollback();
  }

  /** One page of the list. */
  static final class Page {
    private final List<JSONObject> payments;
    private final String nextCursor;

    Page(List<JSONObject> payments, String nextCursor) {
      this.payments = payments;
      this.nextCursor = nextCursor;
    }

    List<JSONObject> payments() {
      return payments;
    }

    /** @return the cursor of the next page, or null on the last one */
    String nextCursor() {
      return nextCursor;
    }
  }

  private final PaymentLedgerStore store;
  private final PaymentMethodReader methodReader;
  private final Database database;

  /** The ledger over the DAL session, asking Stripe for payment methods. */
  public PaymentLedger() {
    this(new PaymentLedgerStore(), new PaymentMethodReader(), new DalDatabase());
  }

  /**
   * The ledger over the DAL session with another source of payment methods, e.g. one that asks
   * Stripe nothing in an integration test.
   *
   * @param methodReader where payment methods are read
   */
  public PaymentLedger(PaymentMethodReader methodReader) {
    this(new PaymentLedgerStore(), methodReader, new DalDatabase());
  }

  PaymentLedger(PaymentLedgerStore store, PaymentMethodReader methodReader, Database database) {
    this.store = store;
    this.methodReader = methodReader;
    this.database = database;
  }

  /**
   * Records the payment a Stripe event reports, in the caller's transaction. Events other than an
   * {@code invoice.paid} that collected money record nothing.
   *
   * @param type  the Stripe event type
   * @param event the full event
   * @throws RuntimeException when the payment cannot be written; the caller fails the event so
   *     Stripe delivers it again
   */
  public void recordPayment(String type, JSONObject event) {
    Optional<StripeInvoicePayment> found = StripeInvoicePayment.from(type, event);
    if (found.isEmpty()) {
      return;
    }
    StripeInvoicePayment payment = found.get();
    if (Boolean.TRUE.equals(database.call(c -> store.isRecorded(c, payment.invoiceId())))) {
      return;
    }
    // Outside any SQL: a slow Stripe must not hold the connection. Never throws.
    JSONObject method = methodReader.read(payment.invoiceId());
    String id = database.call(c -> {
      String accountId = store.accountIdFor(c, payment.requestId(), payment.subscriptionId());
      String email = StringUtils.defaultIfBlank(store.accountEmail(c, accountId),
          payment.customerEmail());
      String environment = store.environmentClientFor(c, payment.subscriptionId(),
          payment.requestId());
      String recorded = store.insert(c, payment, accountId, email, environment,
          store.fiscalData(c, environment), method);
      if (recorded != null) {
        store.addEvent(c, recorded, PaymentStatus.EVENT_RECORDED, null, null, null);
      }
      return recorded;
    });
    if (id != null) {
      log.info("Payment {} recorded for the Stripe invoice {} ({} {})", id, payment.invoiceId(),
          payment.amountPaid(), payment.currency());
    }
  }

  /**
   * {@code GET /payments}: a page of payments, oldest first, each with a fresh fiscal snapshot
   * while its invoice number is not reported.
   *
   * @param status the status to list, in API form, or null for all
   * @param after  the cursor a previous page returned, or null for the first page
   * @param limit  the page size, or null for {@value #DEFAULT_LIMIT}
   * @return the page
   * @throws PaymentsApiException 400 when a parameter is not valid
   */
  Page list(String status, String after, String limit) throws PaymentsApiException {
    String column = null;
    if (StringUtils.isNotBlank(status)) {
      column = PaymentStatus.parse(status);
      if (column == null) {
        throw invalid("status", "Unknown status: use pending_invoice, invoiced, mail_sent,"
            + " completed or error");
      }
    }
    int size = pageSize(limit);
    String cursor = StringUtils.trimToNull(after);
    String filter = column;
    return inTransaction(() -> database.call(c -> {
      List<PaymentRow> rows = store.list(c, filter, cursor, size + 1);
      boolean more = rows.size() > size;
      List<JSONObject> payments = new ArrayList<>();
      for (PaymentRow row : more ? rows.subList(0, size) : rows) {
        payments.add(refreshed(c, row).json());
      }
      String next = more ? rows.get(size - 1).id() : null;
      return new Page(payments, next);
    }));
  }

  /**
   * {@code GET /payments/{id}}: the payment with a fresh snapshot, and its history.
   *
   * @throws PaymentsApiException 404 when there is no such payment
   */
  JSONObject get(String id) throws PaymentsApiException {
    return inTransaction(() -> database.call(c -> {
      PaymentRow row = store.find(c, id);
      return row == null ? null : withEvents(c, refreshed(c, row));
    }), id);
  }

  /**
   * {@code PATCH /payments/{id}}: the ERP's invoice number. The first number moves the payment to
   * {@code invoiced} and freezes its fiscal snapshot; the same number again changes nothing.
   *
   * @throws PaymentsApiException 400 without a number, 404 without the payment, 409 when the
   *     payment already has another number
   */
  JSONObject setInvoiceNumber(String id, String invoiceNumber, String channel)
      throws PaymentsApiException {
    String number = StringUtils.trimToNull(invoiceNumber);
    if (number == null) {
      throw invalid(FIELD_INVOICE_NUMBER, "invoiceNumber is required");
    }
    if (number.length() > PaymentLedgerStore.INVOICE_NUMBER_MAX_LENGTH) {
      throw invalid(FIELD_INVOICE_NUMBER, "invoiceNumber is longer than "
          + PaymentLedgerStore.INVOICE_NUMBER_MAX_LENGTH + " characters");
    }
    return inTransaction(() -> database.call(c -> {
      PaymentRow row = store.find(c, id, true);
      if (row == null) {
        return null;
      }
      if (row.invoiceNumber() != null) {
        if (!row.invoiceNumber().equals(number)) {
          throw new Refused(new PaymentsApiException(HttpServletResponse.SC_CONFLICT,
              CODE_INVOICE_NUMBER_CONFLICT, FIELD_INVOICE_NUMBER,
              "The payment was already invoiced with another number"));
        }
        return withEvents(c, row);
      }
      // From here the fiscal snapshot is frozen: it is the one the ERP last read and invoiced.
      store.setInvoiceNumber(c, id, number);
      if (!PaymentStatus.COMPLETED.equals(row.status())) {
        store.setStatus(c, id, PaymentStatus.INVOICED);
      }
      store.addEvent(c, id, PaymentStatus.INVOICED, null, null, channel);
      return withEvents(c, store.find(c, id));
    }), id);
  }

  /**
   * {@code POST /payments/{id}/events}: a step the ERP reached, or the one that failed.
   *
   * @param body {@code {event, step, reason}}; {@code step} and {@code reason} are required for
   *             {@code error}
   * @throws PaymentsApiException 400 when the body is not valid, 404 without the payment, 409 when
   *     the payment's state does not allow the event (see {@link PaymentStatus})
   */
  JSONObject report(String id, JSONObject body, String channel) throws PaymentsApiException {
    String event = PaymentStatus.parse(body.optString(FIELD_EVENT, ""));
    if (event == null || !PaymentStatus.REPORTABLE.contains(event)) {
      throw invalid(FIELD_EVENT, "event must be invoiced, mail_sent, completed or error");
    }
    String step = StringUtils.trimToNull(body.optString(FIELD_STEP, ""));
    String reason = StringUtils.trimToNull(body.optString(FIELD_REASON, ""));
    if (PaymentStatus.ERROR.equals(event) && (step == null || reason == null)) {
      throw invalid(step == null ? FIELD_STEP : FIELD_REASON,
          "An error needs the step that failed and the reason");
    }
    return inTransaction(() -> database.call(c -> {
      PaymentRow row = store.find(c, id, true);
      if (row == null) {
        return null;
      }
      PaymentStatus.Refusal refusal = PaymentStatus.refusalFor(row.status(),
          row.invoiceNumber() != null, event);
      if (refusal != null) {
        throw new Refused(refused(refusal, event));
      }
      store.setStatus(c, id, event);
      store.addEvent(c, id, event, step, reason, channel);
      return withEvents(c, store.find(c, id));
    }), id);
  }

  /**
   * The row with a fresh fiscal snapshot when its invoice number is not reported yet. The
   * environment is looked up again while unknown: the first payment usually comes before it.
   * A snapshot only replaces the stored one when it differs, and a company that disappeared keeps
   * the last snapshot rather than none.
   */
  private PaymentRow refreshed(Connection c, PaymentRow row) throws SQLException, JSONException {
    if (row.invoiceNumber() != null) {
      return row;
    }
    String environment = row.environmentClientId() != null ? row.environmentClientId()
        : store.environmentClientFor(c, row.subscriptionId(), null);
    JSONObject fiscal = store.fiscalData(c, environment);
    boolean environmentChanged = !Objects.equals(environment, row.environmentClientId());
    boolean fiscalChanged = fiscal != null && !fiscal.toString().equals(row.fiscalData());
    if (!environmentChanged && !fiscalChanged) {
      return row;
    }
    store.updateFiscalData(c, row.id(), environment, fiscalChanged ? fiscal
        : jsonOrNull(row.fiscalData()));
    PaymentRow updated = store.find(c, row.id());
    return updated == null ? row : updated;
  }

  private JSONObject withEvents(Connection c, PaymentRow row) throws SQLException, JSONException {
    return new JSONObject(row.json().toString()).put("events", store.events(c, row.id()));
  }

  private static JSONObject jsonOrNull(String text) throws JSONException {
    return StringUtils.isBlank(text) ? null : new JSONObject(text);
  }

  private static int pageSize(String limit) throws PaymentsApiException {
    if (StringUtils.isBlank(limit)) {
      return DEFAULT_LIMIT;
    }
    try {
      int size = Integer.parseInt(limit.trim());
      if (size >= 1 && size <= MAX_LIMIT) {
        return size;
      }
    } catch (NumberFormatException e) {
      // Reported below, like an out-of-range number.
    }
    throw invalid("limit", "limit must be a number from 1 to " + MAX_LIMIT);
  }

  private static PaymentsApiException refused(PaymentStatus.Refusal refusal, String event) {
    switch (refusal) {
      case COMPLETED:
        return new PaymentsApiException(HttpServletResponse.SC_CONFLICT, CODE_PAYMENT_COMPLETED,
            FIELD_EVENT, "The payment is completed; nothing more can be reported");
      case INVOICE_NUMBER_MISSING:
        return new PaymentsApiException(HttpServletResponse.SC_CONFLICT,
            CODE_INVOICE_NUMBER_MISSING, FIELD_EVENT,
            "Report the invoice number (PATCH) before " + PaymentStatus.toApi(event));
      default:
        return new PaymentsApiException(HttpServletResponse.SC_CONFLICT, CODE_INVALID_TRANSITION,
            FIELD_EVENT, "The payment is past " + PaymentStatus.toApi(event));
    }
  }

  static PaymentsApiException invalid(String field, String message) {
    return new PaymentsApiException(HttpServletResponse.SC_BAD_REQUEST, CODE_INVALID_REQUEST,
        field, message);
  }

  private static PaymentsApiException notFound(String id) {
    return new PaymentsApiException(HttpServletResponse.SC_NOT_FOUND, CODE_PAYMENT_NOT_FOUND,
        null, "No payment has the id " + id);
  }

  /** A refusal decided inside the SQL work, carried out of it unchecked and rolled back. */
  static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient PaymentsApiException refusal;

    Refused(PaymentsApiException refusal) {
      super(refusal.getMessage(), null, false, false);
      this.refusal = refusal;
    }
  }

  /** The work in its own transaction: committed when it answers, rolled back when it throws. */
  private <T> T inTransaction(Supplier<T> work) throws PaymentsApiException {
    try {
      T result = work.get();
      database.commit();
      return result;
    } catch (Refused e) {
      database.rollback();
      throw e.refusal;
    } catch (RuntimeException e) {
      database.rollback();
      throw e;
    }
  }

  /** As {@link #inTransaction(Supplier)}, a null answer meaning "no payment". */
  private <T> T inTransaction(Supplier<T> work, String id)
      throws PaymentsApiException {
    T result = inTransaction(work);
    if (result == null) {
      throw notFound(id);
    }
    return result;
  }

  /** The DAL session's connection, in the current transaction. */
  static final class DalDatabase implements Database {
    @Override
    public <T> T call(SqlWork<T> work) {
      Session session = OBDal.getInstance().getSession();
      // Pending DAL writes first, so the SQL sees them.
      session.flush();
      return session.doReturningWork(connection -> {
        try {
          return work.run(connection);
        } catch (JSONException e) {
          throw new IllegalStateException("Could not read or build a payment's JSON", e);
        }
      });
    }

    @Override
    public void commit() {
      OBDal.getInstance().commitAndClose();
    }

    @Override
    public void rollback() {
      OBDal.getInstance().rollbackAndClose();
    }
  }
}
