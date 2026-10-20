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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.invoice.Invoice;

/**
 * The exchange-rate default of an invoice header write that leaves {@code eTGOCurrencyRate} out
 * (ETP-5703), extracted from {@link AbstractInvoiceHeaderHandler} (java:S1448) with its behaviour
 * unchanged.
 *
 * <p><b>Per-request state.</b> One instance per handler instance — the handler holds it in a final
 * field — so the reason recorded by {@link #capture} in the pre-hook is read by {@link #apply} in the
 * post-hook of the SAME request: every channel runs {@code handle()} and {@code afterHandle()} on
 * the one handler instance its pre-dispatch resolved (the handlers are {@code @Dependent}).
 */
final class InvoiceCurrencyRateDefaults {

  private static final Logger log = LogManager.getLogger(InvoiceCurrencyRateDefaults.class);
  private static final String FIELD_CURRENCY = "currency";
  /** {@code EM_ETGO_Currency_Rate}: the org→document multiply rate (ETP-4029). */
  private static final String FIELD_CURRENCY_RATE = "eTGOCurrencyRate";

  // Set by capture() in handle(), consumed by apply() in afterHandle(). Remembers WHY the rate is
  // left to the server: a create only fills an absent rate, a currency change replaces the stored
  // one (it belongs to the previous currency).
  private RateDefault rateDefault = RateDefault.NONE;

  /** Why a write leaves the exchange rate to the server (ETP-5703). */
  enum RateDefault {
    /** The request carries a rate, or is not a CRUD write: nothing to do. */
    NONE,
    /** A create without a rate: fill it when the saved invoice has none. */
    ON_CREATE,
    /** An update that changes the currency without a rate: the stored rate is the old currency's. */
    ON_CURRENCY_CHANGE
  }

  /**
   * What to do with the invoice's rate: when {@code write}, set it to {@code rate} ({@code null} =
   * clear it). A {@code null} {@code rate} also means no rate is configured for
   * {@code orgCurrencyId → docCurrencyId} on {@code date}, which the write response reports as a
   * hint (ETP-5703 W5).
   */
  record RateChange(BigDecimal rate, String orgCurrencyId, String docCurrencyId, LocalDate date,
      boolean write) {

    /** No rate is configured for the pair on the date. */
    boolean rateMissing() {
      return rate == null;
    }
  }

  /**
   * Scale of {@code EM_ETGO_Currency_Rate} ({@code NUMERIC(20,12)}). The SPA posts the
   * {@code currencyOptions} double and PostgreSQL rounds it to this scale, HALF_UP; rounding the
   * looked-up rate the same way gives MCP and UI invoices the same stored rate.
   */
  static final int CURRENCY_RATE_SCALE = 12;

  /**
   * Notes, in the pre-hook, that this write leaves the exchange rate to the server, and why: a
   * create, or an update that changes the currency, whose body carries no {@code eTGOCurrencyRate}
   * (ETP-5703). {@link #apply} resolves it in {@code afterHandle()}, once the
   * saved invoice says which currency and date it ended up with — on REST and batch the currency
   * may only be derived after the pre-hook, by the create cascade.
   *
   * <p>The SPA stages the rate together with the currency in its currency picker
   * ({@code currencyOptions} only lists currencies that have a rate), so for it this stays
   * {@link RateDefault#NONE}.
   *
   * @param context the current request context
   */
  void capture(NeoContext context) {
    rateDefault = RateDefault.NONE;
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      return;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || hasUsableValue(body, FIELD_CURRENCY_RATE)) {
      return;
    }
    if ("POST".equals(context.getHttpMethod()) && context.getRecordId() == null) {
      rateDefault = RateDefault.ON_CREATE;
    } else if (currencyChangedBy(body, context.getRecordId())) {
      rateDefault = RateDefault.ON_CURRENCY_CHANGE;
    }
  }

  /** {@code true} when {@code body} sets a currency different from the stored invoice's. */
  private static boolean currencyChangedBy(JSONObject body, String invoiceId) {
    String currencyId = usableValue(body, FIELD_CURRENCY);
    if (currencyId == null || StringUtils.isBlank(invoiceId)) {
      return false;
    }
    try {
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      return invoice != null && invoice.getCurrency() != null
          && !currencyId.equals(invoice.getCurrency().getId());
    } catch (Exception e) {
      log.debug("[ETP-5703] Could not read invoice {} currency: {}", invoiceId, e.getMessage());
      return false;
    }
  }

  /**
   * Applies the server-side exchange rate {@link #capture} asked for (ETP-5703).
   *
   * <p><b>Why.</b> The {@code C_Conversion_Rate_Document} sync
   * ({@link AbstractInvoiceHeaderHandler#autoCreateOrUpdateConversionRateDocument(String)}) does nothing while the rate is
   * null, so a USD invoice created through MCP showed an empty exchange-rates tab; and an update
   * USD→GBP without a rate kept the USD rate and synced the rate document with it. The SPA fills
   * the rate from its currency picker ({@code currencyOptions}); this resolves the same org→document
   * rate for the invoice date through {@link NeoExchangeRateService#rateAsDecimal} (direct, then
   * inverse). See {@link #resolveCurrencyRateChange} for the exact rule.
   *
   * <p><b>How it is written.</b> One JDBC {@code UPDATE} of the rate column under a savepoint
   * ({@link JdbcSavepoints}, as {@link ConversionRateDocumentSync} does), then the managed invoice
   * is refreshed. A failure rolls back to the savepoint and is logged: it never aborts the request
   * transaction, and it never leaves a dirty entity behind to fail the next flush. It does not touch
   * {@code Updated}, so the client's optimistic-lock token from this very write stays valid.
   *
   * <p>Call from {@code afterHandle()} BEFORE {@link AbstractInvoiceHeaderHandler#autoCreateOrUpdateConversionRateDocument(NeoContext)}
   * so the sync sees the new rate. The saved record in the write response is updated too, when it
   * carries the field.
   *
   * <p>Total: never throws.
   *
   * @param context the current request context
   */
  void apply(NeoContext context) {
    RateDefault reason = rateDefault;
    rateDefault = RateDefault.NONE;
    if (reason == RateDefault.NONE) {
      return;
    }
    String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
    if (StringUtils.isBlank(invoiceId)) {
      return;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
        RateChange change = resolveCurrencyRateChange(invoice, reason);
        if (change == null) {
          return;
        }
        if (change.write()) {
          writeCurrencyRate(invoiceId, change.rate());
          if (change.rate() == null) {
            // No rate for the new currency: the row synced for the previous one is stale too.
            // ORDER IS LOAD-BEARING (ETP-5703 QA): delete BEFORE refreshing the invoice. Refresh
            // cascades into the invoice's conversion-rate-document collection and loads that row
            // into the session; deleting it afterwards by JDBC left a managed entity with no row,
            // so the next refresh (autoCreateOrUpdateConversionRateDocument) failed with "No row
            // with the given identifier exists" and Hibernate marked the whole transaction
            // rollback-only: the cleared rate and the delete were both rolled back (the caller's
            // update had already been committed by the CRUD write), leaving GBP with the USD rate.
            ConversionRateDocumentSync.deleteAllForInvoice(invoiceId, change.orgCurrencyId());
          }
          // Safe: the CRUD write already flushed (and committed) the invoice before afterHandle()
          // runs, so the refresh only discards the stale in-memory rate, never a pending change. It
          // must stay after that flush and after the JDBC writes above.
          OBDal.getInstance().getSession().refresh(invoice);
          reflectInWriteResponse(context, FIELD_CURRENCY_RATE,
              change.rate() != null ? change.rate() : JSONObject.NULL);
          log.debug("[ETP-5703] Set eTGOCurrencyRate {} on invoice {} ({})", change.rate(),
              invoiceId, reason);
        }
        if (change.rateMissing()) {
          addMissingRateHint(context, change);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-5703] Could not default the exchange rate of invoice {}: {}", invoiceId,
          e.getMessage());
    }
  }

  /**
   * The rate change {@code reason} calls for on {@code invoice}, or {@code null} to leave it alone.
   *
   * <ul>
   *   <li>Never on a missing or processed invoice, one without currency or date, or one in the
   *       organization's own currency.</li>
   *   <li>{@link RateDefault#ON_CREATE}: only when the saved invoice has no rate; the looked-up
   *       org→document rate. When none is configured nothing is written and no error is raised:
   *       the result is a non-writing change whose {@link RateChange#rateMissing()} drives the
   *       response hint (W5).</li>
   *   <li>{@link RateDefault#ON_CURRENCY_CHANGE}: the stored rate belongs to the previous currency,
   *       so it is replaced by the new currency's rate — or <b>cleared</b> when that currency has
   *       none. Keeping a rate of another currency would silently convert with the wrong figure and
   *       re-sync the rate document with it; a null rate is the state the invoice would be in had it
   *       been created in that currency, and the exchange-rates tab says so (empty) instead of
   *       showing a wrong row.</li>
   * </ul>
   * The rate is rounded HALF_UP to {@link #CURRENCY_RATE_SCALE}, the column's scale.
   */
  static RateChange resolveCurrencyRateChange(Invoice invoice, RateDefault reason)
      throws java.sql.SQLException {
    if (reason == null || reason == RateDefault.NONE || invoice == null
        || invoice.getCurrency() == null || invoice.getInvoiceDate() == null
        || Boolean.TRUE.equals(invoice.isProcessed())) {
      return null;
    }
    BigDecimal stored = invoice.getETGOCurrencyRate();
    if (reason == RateDefault.ON_CREATE && stored != null) {
      return null;
    }
    String orgId = invoice.getOrganization().getId();
    String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    String docCurrencyId = invoice.getCurrency().getId();
    if (orgCurrencyId == null || orgCurrencyId.equals(docCurrencyId)) {
      return null;
    }
    // getTime(), not toInstant(): a java.sql.Date from Hibernate throws on toInstant().
    LocalDate date = Instant.ofEpochMilli(invoice.getInvoiceDate().getTime())
        .atZone(ZoneId.systemDefault()).toLocalDate();
    BigDecimal looked = NeoExchangeRateService.rateAsDecimal(orgCurrencyId, docCurrencyId, date,
        orgId);
    BigDecimal rate = looked != null && looked.signum() > 0
        ? looked.setScale(CURRENCY_RATE_SCALE, RoundingMode.HALF_UP) : null;
    if (rate == null && stored == null) {
      // Nothing to write, but the caller left the rate to the server and there is none: say so.
      return new RateChange(null, orgCurrencyId, docCurrencyId, date, false);
    }
    if (rate != null && stored != null && rate.compareTo(stored) == 0) {
      return null;
    }
    return new RateChange(rate, orgCurrencyId, docCurrencyId, date, true);
  }

  /**
   * Adds the non-blocking "no exchange rate" hint to the write response (ETP-5703 W5): the write
   * succeeded and its status is unchanged, but the caller left the rate to the server and none is
   * configured, so the invoice has no rate (the exchange-rates tab stays empty). Translated to the
   * request language ({@link NeoHints}), naming both currencies and the invoice date.
   */
  private static void addMissingRateHint(NeoContext context, RateChange change) {
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return;
    }
    String language = com.etendoerp.go.schemaforge.util.NeoLanguage.currentCode();
    NeoHints.addMessage(prev.getBody(), context.isMcpOrigin(), NeoHints.TYPE_WARNING,
        missingRateText(language, isoCode(change.orgCurrencyId()), isoCode(change.docCurrencyId()),
            change.date()));
  }

  /** The hint text: currencies by ISO code, the date as the language writes it. */
  static String missingRateText(String language, String orgIso, String docIso, LocalDate date) {
    String pattern = NeoHints.isSpanish(language) ? "dd/MM/yyyy" : "yyyy-MM-dd";
    return NeoHints.text("exchangeRateMissing", language, orgIso, docIso,
        date.format(java.time.format.DateTimeFormatter.ofPattern(pattern)));
  }

  private static String isoCode(String currencyId) {
    org.openbravo.model.common.currency.Currency currency =
        OBDal.getInstance().get(org.openbravo.model.common.currency.Currency.class, currencyId);
    return currency != null && currency.getISOCode() != null ? currency.getISOCode() : currencyId;
  }

  /** Writes the rate column of one invoice under a savepoint; {@code null} clears it. */
  private static void writeCurrencyRate(String invoiceId, BigDecimal rate)
      throws java.sql.SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    JdbcSavepoints.run(conn, () -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE c_invoice SET em_etgo_currency_rate = ? WHERE c_invoice_id = ?")) {
        if (rate != null) {
          ps.setBigDecimal(1, rate);
        } else {
          ps.setNull(1, java.sql.Types.NUMERIC);
        }
        ps.setString(2, invoiceId);
        ps.executeUpdate();
      }
    });
  }

  /**
   * Writes {@code value} under {@code key} into the saved record of the write response, when the
   * record already carries that key — the response was captured before {@code afterHandle()} wrote
   * the value. Mutates the captured body in place, so it holds whichever post-hook returns it.
   */
  private static void reflectInWriteResponse(NeoContext context, String key, Object value) {
    try {
      NeoResponse prev = context.getPreviousResult();
      if (prev == null || prev.getBody() == null) {
        return;
      }
      JSONObject body = prev.getBody();
      JSONObject wrapper = body.optJSONObject("response");
      JSONArray data = wrapper != null ? wrapper.optJSONArray("data") : null;
      JSONObject rec = data != null && data.length() > 0 ? data.optJSONObject(0) : body;
      if (rec != null && rec.has(key)) {
        rec.put(key, value);
      }
    } catch (Exception e) {
      log.debug("[ETP-5703] Could not reflect {} in the write response: {}", key, e.getMessage());
    }
  }

  private static boolean hasUsableValue(JSONObject body, String key) {
    return PartnerLocationDefaults.hasUsableValue(body, key);
  }

  private static String usableValue(JSONObject body, String key) {
    return PartnerLocationDefaults.usableValue(body, key);
  }
}
