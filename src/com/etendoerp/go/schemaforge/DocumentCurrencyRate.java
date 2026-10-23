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
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;

/**
 * The exchange rate of a commercial document header (ETP-5840): the org→document multiply rate
 * kept in a document's rate column, which must be the {@code C_Conversion_Rate} valid on the
 * document's <b>reference date</b>.
 *
 * <p><b>Shared code, no identity.</b> This class never decides which documents it applies to, nor
 * which date is their reference date: each entity's customization constructs it with its own
 * {@link RateType} (entity, rate property, reference-date property, the name of that date) and calls
 * it explicitly from its own hooks. The only properties read without being told are the standard AD
 * columns every document header carries ({@code currency}, {@code organization}, {@code client},
 * {@code processed}).
 *
 * <p><b>Rules.</b>
 * <ol>
 *   <li>Recalculated whenever the reference date or the currency changes, replacing any manual
 *       rate; no rate configured → {@code null} ({@link #applyToCallout}, {@link #beforeWrite},
 *       {@link #recompute}).</li>
 *   <li>A write that changes the date or the currency AND sends the rate keeps the sent rate; one
 *       that changes them without a rate gets the recalculated one. A manual rate survives every
 *       write that changes neither.</li>
 *   <li>A save leaving a foreign-currency document without a rate is refused, nothing saved
 *       ({@link #beforeWrite}; the post-hook {@link #afterWrite} covers the creates whose currency
 *       or date the pre-hook cannot know yet).</li>
 *   <li>Completing such a document is refused ({@link #refuseCompletionWithoutRate}).</li>
 *   <li>A document in its organization's currency needs no rate and is never refused.</li>
 *   <li>Processed documents are never touched.</li>
 * </ol>
 *
 * <p><b>Lookups</b> use the DOCUMENT's organization and client, never the session's, through
 * {@link NeoExchangeRateService#rateAsDecimal(String, String, LocalDate, String, String)} (direct
 * rate, then the inverse of the reverse one), rounded HALF_UP to {@link #RATE_SCALE}.
 *
 * <p><b>Per-request state.</b> One instance per handler instance (the handlers are
 * {@code @Dependent}, and every channel runs {@code handle()} and {@code afterHandle()} on the one
 * instance its pre-dispatch resolved), so the deferral noted by {@link #beforeWrite} is read by
 * {@link #afterWrite} of the same request.
 */
final class DocumentCurrencyRate {

  private static final Logger log = LogManager.getLogger(DocumentCurrencyRate.class);

  /** Scale of the {@code EM_ETGO_Currency_Rate} columns ({@code NUMERIC(20,12)}). */
  static final int RATE_SCALE = 12;
  /**
   * {@link NeoContext} attribute under which a header customization publishes its {@link RateType}
   * for the shared {@link CurrencyOptionsHandler} it routes {@code currencyOptions} to.
   */
  static final String ATTR_RATE_TYPE = "etgo.documentRateType";

  private static final String PROP_CURRENCY = "currency";
  private static final String PROP_ORGANIZATION = "organization";
  private static final String PROP_CLIENT = "client";
  private static final String PROP_PROCESSED = "processed";
  private static final String KEY_VALUE = "value";
  private static final String KEY_UPDATES = "updates";

  /**
   * What a customization tells this class about its document.
   *
   * @param entityName            the DAL entity of the header (e.g. {@code Order})
   * @param rateProperty          the property holding the org→document rate
   * @param referenceDateProperty the property holding the date the rate must be valid on
   * @param referenceDateLabelKey the {@code AD_MESSAGE} naming that date as the UI labels it
   */
  record RateType(String entityName, String rateProperty, String referenceDateProperty,
      String referenceDateLabelKey) {
  }

  /** The values a rate decision is made from. Any of them may be {@code null} when unknown. */
  record RateInputs(String orgId, String clientId, String currencyId, LocalDate referenceDate) {

    boolean complete() {
      return orgId != null && clientId != null && currencyId != null && referenceDate != null;
    }
  }

  private final RateType type;
  // Set by beforeWrite() on a create whose currency or reference date is only derived by the
  // create cascade, consumed by afterWrite() of the same request.
  private boolean deferredCreate;

  DocumentCurrencyRate(RateType type) {
    this.type = type;
  }

  RateType type() {
    return type;
  }

  // ---------------------------------------------------------------------------------------------
  // Lookup
  // ---------------------------------------------------------------------------------------------

  /**
   * The org→document rate valid on {@code date}, for the document's organization and client, or
   * {@code null} when none is configured (a non-positive rate counts as none).
   */
  static BigDecimal lookupRate(String orgCurrencyId, String docCurrencyId, LocalDate date,
      String orgId, String clientId) {
    try {
      BigDecimal rate = NeoExchangeRateService.rateAsDecimal(orgCurrencyId, docCurrencyId, date,
          orgId, clientId);
      return rate != null && rate.signum() > 0 ? rate.setScale(RATE_SCALE, RoundingMode.HALF_UP)
          : null;
    } catch (Exception e) {
      log.warn("[ETP-5840] Exchange rate lookup failed ({} -> {} on {}): {}", orgCurrencyId,
          docCurrencyId, date, e.getMessage());
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Callout: the form gets the recalculated rate without saving
  // ---------------------------------------------------------------------------------------------

  /**
   * Puts the recalculated rate into the callout response {@code updates} — as
   * {@code {"value": rate}}, or {@code {"value": null}} when no rate is configured — when the
   * callout was triggered by the currency or the reference date, or when the callout cascade itself
   * changed the reference date (a date the document copies into it, e.g. an invoice date copied into
   * the accounting date by the classic callout). Org-currency documents are left alone.
   *
   * <p>Call from the customization's {@code afterCallout()}. Total: never throws.
   *
   * @param fields   the unpacked callout context
   * @param recordId the saved document, or {@code null} for a new one; supplies the organization
   *                 and client when the form state lacks them
   */
  void applyToCallout(NeoHandlerUtils.CalloutFields fields, String recordId) {
    try {
      if (fields == null || fields.body() == null) {
        return;
      }
      String trigger = fields.triggerField();
      JSONObject updates = fields.updates();
      boolean currencyTrigger = PROP_CURRENCY.equals(trigger) || "currencyid".equals(trigger);
      boolean dateTrigger = type.referenceDateProperty().equals(trigger);
      boolean dateCascaded = updates != null && updates.has(type.referenceDateProperty());
      if (!currencyTrigger && !dateTrigger && !dateCascaded) {
        return;
      }
      JSONObject form = fields.formState() != null ? fields.formState() : new JSONObject();
      Object triggered = fields.requestBody() != null ? fields.requestBody().opt(KEY_VALUE) : null;

      String currencyId = currencyTrigger ? idOf(triggered) : null;
      if (currencyId == null) {
        currencyId = firstId(form.opt(PROP_CURRENCY), form.opt("currencyid"));
      }
      LocalDate date;
      if (dateTrigger) {
        date = parseDate(triggered);
      } else if (dateCascaded) {
        date = parseDate(updates.opt(type.referenceDateProperty()));
      } else {
        date = parseDate(form.opt(type.referenceDateProperty()));
      }
      BaseOBObject stored = load(recordId);
      String orgId = firstId(form.opt(PROP_ORGANIZATION), idOf(stored, PROP_ORGANIZATION),
          sessionOrgId());
      String clientId = firstId(idOf(stored, PROP_CLIENT), sessionClientId());
      RateInputs inputs = new RateInputs(orgId, clientId, currencyId, date);
      if (!inputs.complete()) {
        return;
      }
      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
      if (orgCurrencyId == null || orgCurrencyId.equals(currencyId)) {
        return;
      }
      BigDecimal rate = lookupRate(orgCurrencyId, currencyId, date, orgId, clientId);
      if (updates == null) {
        updates = new JSONObject();
        fields.body().put(KEY_UPDATES, updates);
      }
      updates.put(type.rateProperty(),
          new JSONObject().put(KEY_VALUE, rate != null ? rate : JSONObject.NULL));
    } catch (Exception e) {
      log.warn("[ETP-5840] Could not recalculate the exchange rate in the callout: {}",
          e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Save: recalculation + blocking validation, before anything is written
  // ---------------------------------------------------------------------------------------------

  /**
   * Pre-hook of a header CRUD write (create or update). Resolves the currency, organization and
   * reference date the document will have — the body's value, else the stored one — and:
   * <ul>
   *   <li>when the write changes the currency or the reference date (a create always does) and
   *       does not send a usable rate, writes the recalculated rate into the body (or {@code null});
   *   </li>
   *   <li>refuses the write with a 422 when the document's currency is not its organization's and
   *       the rate it would be saved with is empty — the completion text when {@code completing}.
   *   </li>
   * </ul>
   * A create whose currency, organization or reference date is not in the body yet (derived later
   * by the create cascade) is deferred to {@link #afterWrite}.
   *
   * <p>Runs on every channel: REST single and batch through {@code handleWithHooks}, MCP
   * {@code etendo_create}/{@code etendo_update} through their pre-dispatch. Total: a failure to
   * decide lets the write through (logged), it never answers 500.
   *
   * @param context    the request
   * @param completing whether this same write also completes the document
   * @return the refusal, or {@code null} to let the write proceed
   */
  NeoResponse beforeWrite(NeoContext context, boolean completing) {
    deferredCreate = false;
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !NeoHandlerUtils.isWriteMethod(context.getHttpMethod())
        || context.getRequestBody() == null) {
      return null;
    }
    try {
      JSONObject body = context.getRequestBody();
      boolean create = StringUtils.isBlank(context.getRecordId());
      BaseOBObject stored = create ? null : load(context.getRecordId());
      if (!create && (stored == null || isProcessed(stored))) {
        return null;
      }
      String bodyCurrency = idOf(body.opt(PROP_CURRENCY));
      LocalDate bodyDate = parseDate(body.opt(type.referenceDateProperty()));
      String currencyId = firstId(bodyCurrency, idOf(stored, PROP_CURRENCY));
      LocalDate date = bodyDate != null ? bodyDate : dateOf(stored);
      String orgId = firstId(idOf(body.opt(PROP_ORGANIZATION)), idOf(stored, PROP_ORGANIZATION),
          create ? sessionOrgId() : null);
      String clientId = firstId(idOf(stored, PROP_CLIENT), sessionClientId());
      RateInputs inputs = new RateInputs(orgId, clientId, currencyId, date);
      if (!inputs.complete()) {
        deferredCreate = create;
        return null;
      }
      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
      if (orgCurrencyId == null || orgCurrencyId.equals(currencyId)) {
        return null;
      }
      boolean sendsRate = body.has(type.rateProperty());
      BigDecimal sentRate = sendsRate ? parseRate(body.opt(type.rateProperty())) : null;
      boolean changed = create || !currencyId.equals(idOf(stored, PROP_CURRENCY))
          || !date.equals(dateOf(stored));
      BigDecimal finalRate;
      if (sentRate != null) {
        finalRate = sentRate;
      } else if (changed) {
        finalRate = lookupRate(orgCurrencyId, currencyId, date, orgId, clientId);
        body.put(type.rateProperty(), finalRate != null ? finalRate : JSONObject.NULL);
      } else if (sendsRate) {
        finalRate = null; // the caller cleared the rate
      } else {
        finalRate = (BigDecimal) stored.get(type.rateProperty());
      }
      if (finalRate != null && finalRate.signum() > 0) {
        return null;
      }
      return completing
          ? DocumentRateMessages.missingOnComplete(isoCode(orgCurrencyId), isoCode(currencyId))
          : DocumentRateMessages.missingOnSave(isoCode(orgCurrencyId), isoCode(currencyId), date,
              type.referenceDateLabelKey(), type.referenceDateProperty());
    } catch (Exception e) {
      log.warn("[ETP-5840] Could not evaluate the exchange rate of {} {}: {}", type.entityName(),
          context.getRecordId(), e.getMessage());
      return null;
    }
  }

  /**
   * Post-hook counterpart of {@link #beforeWrite} for the creates it deferred: the saved document now
   * says which currency and reference date the create cascade gave it. A missing rate is filled with
   * the recalculated one. When none is configured:
   * <ul>
   *   <li>inside a batch ({@code /batch}, {@code etendo_batch}) the operation is refused with the
   *       save 422, and the batch rolls back — nothing saved;</li>
   *   <li>on a REST single create the write has already been committed by the CRUD service, so it
   *       cannot be undone here: the document stays without a rate and the response carries the save
   *       text as a warning. Declared divergence (neo-headless.md §4.12.9); the next save of the
   *       document is refused until it has a rate.</li>
   * </ul>
   * Total: never throws.
   *
   * @param context the request
   * @return the refusal (batch only), or {@code null}
   */
  NeoResponse afterWrite(NeoContext context) {
    boolean deferred = deferredCreate;
    deferredCreate = false;
    if (!deferred || context == null) {
      return null;
    }
    String id = NeoHandlerUtils.extractCreatedIdFromPreviousResult(context);
    if (StringUtils.isBlank(id)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        BaseOBObject doc = load(id);
        if (doc == null || isProcessed(doc)) {
          return null;
        }
        RateInputs inputs = new RateInputs(idOf(doc, PROP_ORGANIZATION), idOf(doc, PROP_CLIENT),
            idOf(doc, PROP_CURRENCY), dateOf(doc));
        if (!inputs.complete()) {
          return null;
        }
        String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(inputs.orgId());
        if (orgCurrencyId == null || orgCurrencyId.equals(inputs.currencyId())) {
          return null;
        }
        BigDecimal stored = (BigDecimal) doc.get(type.rateProperty());
        if (stored != null && stored.signum() > 0) {
          return null;
        }
        BigDecimal rate = lookupRate(orgCurrencyId, inputs.currencyId(), inputs.referenceDate(),
            inputs.orgId(), inputs.clientId());
        if (rate != null) {
          writeRate(id, rate);
          OBDal.getInstance().getSession().refresh(doc);
          reflectInWriteResponse(context, type.rateProperty(), rate);
          return null;
        }
        if (NeoBatchScope.current() != null) {
          return DocumentRateMessages.missingOnSave(isoCode(orgCurrencyId),
              isoCode(inputs.currencyId()), inputs.referenceDate(), type.referenceDateLabelKey(),
              type.referenceDateProperty());
        }
        NeoResponse prev = context.getPreviousResult();
        if (prev != null) {
          NeoHints.addMessage(prev.getBody(), context.isMcpOrigin(), NeoHints.TYPE_WARNING,
              DocumentRateMessages.missingOnSaveText(isoCode(orgCurrencyId),
                  isoCode(inputs.currencyId()), inputs.referenceDate(),
                  type.referenceDateLabelKey()));
        }
        return null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-5840] Could not resolve the exchange rate of new {} {}: {}",
          type.entityName(), id, e.getMessage());
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Completion
  // ---------------------------------------------------------------------------------------------

  /**
   * Refuses to complete a stored foreign-currency document that has no rate. Call from the
   * customization's pre-hook when the request completes the document, before anything else runs.
   * Total: a failure to decide lets the completion through (logged).
   *
   * @param recordId the document
   * @return the 422 refusal, or {@code null}
   */
  NeoResponse refuseCompletionWithoutRate(String recordId) {
    try {
      BaseOBObject doc = load(recordId);
      if (doc == null) {
        return null;
      }
      String message = missingRateOnCompletion(doc, type.rateProperty());
      if (message == null) {
        return null;
      }
      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(idOf(doc, PROP_ORGANIZATION));
      return DocumentRateMessages.missingOnComplete(isoCode(orgCurrencyId),
          isoCode(idOf(doc, PROP_CURRENCY)));
    } catch (Exception e) {
      log.warn("[ETP-5840] Could not check the exchange rate of {} {} before completing: {}",
          type.entityName(), recordId, e.getMessage());
      return null;
    }
  }

  /**
   * Refusal of a generation flow that creates a document and completes it in one step (ETP-5840):
   * thrown BEFORE the completion runs, so the caller rolls the whole generation back and answers
   * {@link #toResponse()} — the same translated 422, {@code messageKeys} and {@code messageParams}
   * as {@link #refuseCompletionWithoutRate}.
   */
  static final class MissingRateException extends org.openbravo.base.exception.OBException {
    private static final long serialVersionUID = 1L;
    private final String orgIso;
    private final String docIso;

    MissingRateException(String message, String orgIso, String docIso) {
      super(message);
      this.orgIso = orgIso;
      this.docIso = docIso;
    }

    /** The 422 completion refusal to answer with. */
    NeoResponse toResponse() {
      return DocumentRateMessages.missingOnComplete(orgIso, docIso);
    }
  }

  /**
   * Throws {@link MissingRateException} when {@code doc} is in a currency other than its
   * organization's and {@code rateProperty} is empty. For the create-and-complete generation flows,
   * to call after the generated document got its rate ({@link #recompute}) and before completing it.
   *
   * @param doc          the generated document
   * @param rateProperty the property holding its rate
   */
  static void requireRateForCompletion(BaseOBObject doc, String rateProperty) {
    String message = missingRateOnCompletion(doc, rateProperty);
    if (message == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(idOf(doc, PROP_ORGANIZATION));
      throw new MissingRateException(message, isoCode(orgCurrencyId),
          isoCode(idOf(doc, PROP_CURRENCY)));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The completion-refusal text for {@code doc} when its currency is not its organization's and
   * {@code rateProperty} is empty, else {@code null}. Used by the GO completion checks only
   * ({@link #refuseCompletionWithoutRate}, {@link #requireRateForCompletion}); the classic
   * {@code InvoiceCompletionRateHook} keeps its own, unchanged rule.
   */
  static String missingRateOnCompletion(BaseOBObject doc, String rateProperty) {
    if (doc == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      String orgId = idOf(doc, PROP_ORGANIZATION);
      String currencyId = idOf(doc, PROP_CURRENCY);
      if (orgId == null || currencyId == null) {
        return null;
      }
      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
      if (orgCurrencyId == null || orgCurrencyId.equals(currencyId)) {
        return null;
      }
      BigDecimal rate = (BigDecimal) doc.get(rateProperty);
      if (rate != null && rate.signum() > 0) {
        return null;
      }
      return DocumentRateMessages.missingOnCompleteText(isoCode(orgCurrencyId),
          isoCode(currencyId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Documents generated from another one
  // ---------------------------------------------------------------------------------------------

  /**
   * Recomputes the rate of a freshly generated draft document from ITS OWN reference date — never
   * copied from the source document — and saves it through DAL (flushed). No configured rate →
   * {@code null}: the document is created anyway and its next save is refused until it has one.
   * Org-currency and processed documents are left alone. Total: never throws.
   *
   * @param doc the generated document, managed by the current session
   * @return the rate written, or {@code null}
   */
  BigDecimal recompute(BaseOBObject doc) {
    if (doc == null) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        if (isProcessed(doc)) {
          return null;
        }
        RateInputs inputs = new RateInputs(idOf(doc, PROP_ORGANIZATION), idOf(doc, PROP_CLIENT),
            idOf(doc, PROP_CURRENCY), dateOf(doc));
        if (!inputs.complete()) {
          return null;
        }
        String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(inputs.orgId());
        if (orgCurrencyId == null || orgCurrencyId.equals(inputs.currencyId())) {
          return null;
        }
        BigDecimal rate = lookupRate(orgCurrencyId, inputs.currencyId(), inputs.referenceDate(),
            inputs.orgId(), inputs.clientId());
        doc.set(type.rateProperty(), rate);
        OBDal.getInstance().save(doc);
        OBDal.getInstance().flush();
        log.debug("[ETP-5840] Recomputed rate {} on generated {} {}", rate, type.entityName(),
            doc.getId());
        return rate;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-5840] Could not recompute the exchange rate of {} {}: {}", type.entityName(),
          doc.getId(), e.getMessage());
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** The stored reference date of the document, or {@code null}. */
  LocalDate dateOf(BaseOBObject doc) {
    if (doc == null) {
      return null;
    }
    Object value = doc.get(type.referenceDateProperty());
    // getTime(), not toInstant(): a java.sql.Date from Hibernate throws on toInstant().
    return value instanceof Date d
        ? Instant.ofEpochMilli(d.getTime()).atZone(ZoneId.systemDefault()).toLocalDate()
        : null;
  }

  private BaseOBObject load(String id) {
    if (StringUtils.isBlank(id) || "new".equals(id)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      return (BaseOBObject) OBDal.getInstance().get(type.entityName(), id);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static boolean isProcessed(BaseOBObject doc) {
    return doc.getEntity().hasProperty(PROP_PROCESSED)
        && Boolean.TRUE.equals(doc.get(PROP_PROCESSED));
  }

  private static String idOf(BaseOBObject doc, String property) {
    if (doc == null) {
      return null;
    }
    Object value = doc.get(property);
    return value instanceof BaseOBObject ref ? (String) ref.getId() : null;
  }

  /**
   * The id a body/form/callout value carries: a plain id string, or an object with {@code value}
   * or {@code id}. {@code null} when blank, a batch {@code $ref:}, or not an id.
   */
  static String idOf(Object value) {
    Object raw = value;
    if (raw instanceof JSONObject obj) {
      raw = obj.has(KEY_VALUE) ? obj.opt(KEY_VALUE) : obj.opt("id");
    }
    if (raw == null || raw == JSONObject.NULL) {
      return null;
    }
    String id = StringUtils.trimToNull(String.valueOf(raw));
    return id == null || "null".equals(id) || id.startsWith(BatchService.REF_PREFIX) ? null : id;
  }

  private static String firstId(Object... candidates) {
    for (Object candidate : candidates) {
      String id = candidate instanceof String s ? StringUtils.trimToNull(s) : idOf(candidate);
      if (id != null) {
        return id;
      }
    }
    return null;
  }

  /** A date from a body/form/callout value: its leading {@code yyyy-MM-dd}, else {@code null}. */
  static LocalDate parseDate(Object value) {
    Object raw = value instanceof JSONObject obj ? obj.opt(KEY_VALUE) : value;
    if (raw == null || raw == JSONObject.NULL) {
      return null;
    }
    if (raw instanceof Date d) {
      return Instant.ofEpochMilli(d.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }
    String text = StringUtils.trimToEmpty(String.valueOf(raw));
    if (text.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(text.substring(0, 10));
    } catch (Exception e) {
      return null;
    }
  }

  /** A positive rate from a body value, or {@code null} (blank, zero, negative, unparseable). */
  static BigDecimal parseRate(Object value) {
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    try {
      BigDecimal rate = value instanceof BigDecimal b ? b
          : new BigDecimal(StringUtils.trimToEmpty(String.valueOf(value)));
      return rate.signum() > 0 ? rate : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String sessionOrgId() {
    OBContext ctx = OBContext.getOBContext();
    return ctx != null && ctx.getCurrentOrganization() != null
        ? ctx.getCurrentOrganization().getId() : null;
  }

  private static String sessionClientId() {
    OBContext ctx = OBContext.getOBContext();
    return ctx != null && ctx.getCurrentClient() != null ? ctx.getCurrentClient().getId() : null;
  }

  static String isoCode(String currencyId) {
    if (currencyId == null) {
      return "";
    }
    OBContext.setAdminMode(true);
    try {
      Currency currency = OBDal.getInstance().get(Currency.class, currencyId);
      return currency != null && currency.getISOCode() != null ? currency.getISOCode()
          : currencyId;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** One JDBC {@code UPDATE} of the rate column under a savepoint; leaves {@code Updated} alone. */
  private void writeRate(String id, BigDecimal rate) throws java.sql.SQLException {
    Entity entity = ModelProvider.getInstance().getEntity(type.entityName());
    String table = entity.getTableName();
    String idColumn = entity.getIdProperties().get(0).getColumnName();
    String rateColumn = entity.getProperty(type.rateProperty()).getColumnName();
    Connection conn = OBDal.getInstance().getConnection();
    JdbcSavepoints.run(conn, () -> {
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE " + table + " SET " + rateColumn + " = ? WHERE " + idColumn + " = ?")) {
        ps.setBigDecimal(1, rate);
        ps.setString(2, id);
        ps.executeUpdate();
      }
    });
  }

  /** Writes {@code value} into the saved record of the write response when it carries the key. */
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
      log.debug("[ETP-5840] Could not reflect {} in the write response: {}", key, e.getMessage());
    }
  }
}
