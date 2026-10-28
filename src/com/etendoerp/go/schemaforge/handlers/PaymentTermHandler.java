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

package com.etendoerp.go.schemaforge.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;

import com.etendoerp.go.schemaforge.AbstractNeoHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoMandatoryDefaultsService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoServerOwnedFields;

/**
 * ETP-5826: customization of the {@code payment-term} header ({@code C_PaymentTerm}).
 *
 * <p>Payment terms can be created on the fly from every payment-term selector ("+ Crear condición
 * de pago") by typing only a number of days, as well as from the Payment Term settings window and
 * through MCP / batch. This pre-hook runs before the generic CRUD on every channel and, on a
 * create or an update:</p>
 * <ul>
 *   <li>requires {@code overduePaymentDaysRule} ({@code NetDays}) to be a whole number between
 *       {@value #MIN_DAYS} and {@value #MAX_DAYS} — HTTP 400, keys {@value #MSG_DAYS_INVALID} /
 *       {@value #MSG_DAYS_OUT_OF_RANGE};</li>
 *   <li>refuses a second <i>simple</i> term (no {@code C_PaymentTermLine}, {@code IsDueFixed=N},
 *       {@code FixMonthOffset} 0 or null) with the same number of days in the same client — any
 *       organization, active or not — HTTP 409, key {@value #MSG_DAYS_DUPLICATE}, naming the
 *       existing term so the caller can reuse it;</li>
 *   <li>on create, fills what the days-only form does not send: {@code name} ({@code "N Días"},
 *       {@code "1 Día"}), {@code searchKey} ({@code "ND"}), {@code offsetMonthDue} = 0 and
 *       {@code nextBusinessDay} = true — the shape of the seeded 30/60/90 terms. Zero days follows
 *       the seeded {@code Inmediato} term instead ({@code "Inmediato"}, {@code "I"}, no next
 *       business day).</li>
 * </ul>
 *
 * <p>On MCP {@code etendo_create} the mandatory pre-check runs before this pre-hook, so the
 * create enrichment also runs earlier, through {@link #resolveServerCreateFields}; and the
 * mandatory-defaults pass runs before both, so on create the pre-hook first discards the
 * days-derived values the server filled (a placeholder 0 days, a default) and decides them as on
 * REST — see {@link PaymentTermCreateDefaults#dropServerFilledCreateFields} (ETP-5978).</p>
 *
 * <p>On an update, the range and duplicate checks only run when the days or the shape actually
 * change, so a legacy term that already breaks a rule can still be renamed. The message keys are
 * plain strings, deliberately not {@code AD_MESSAGE} records (ETP-5692 decision): the text is
 * English and a client that wants another language maps the key.</p>
 *
 * <p>This class is the only binding and keeps the {@code NeoHandler} surface; plain helpers carry
 * the rest: {@link PaymentTermCreateDefaults} (create-time derivations),
 * {@link PaymentTermRenameSync} (rename → translations) and {@link PaymentTermRefusals} (the keyed
 * error responses).</p>
 */
@NeoExtension(spec = "payment-term", entity = "header")
public class PaymentTermHandler extends AbstractNeoHandler {

  private static final Logger log = LogManager.getLogger(PaymentTermHandler.class);

  static final String SPEC = "payment-term";

  static final String F_DAYS = PaymentTerm.PROPERTY_OVERDUEPAYMENTDAYSRULE;
  static final String F_NAME = PaymentTerm.PROPERTY_NAME;
  static final String F_SEARCH_KEY = PaymentTerm.PROPERTY_SEARCHKEY;
  static final String F_OFFSET_MONTH = PaymentTerm.PROPERTY_OFFSETMONTHDUE;
  static final String F_NEXT_BUSINESS_DAY = PaymentTerm.PROPERTY_NEXTBUSINESSDAY;
  static final String F_FIXED_DUE_DATE = PaymentTerm.PROPERTY_FIXEDDUEDATE;
  static final String F_ORGANIZATION = PaymentTerm.PROPERTY_ORGANIZATION;

  static final int MIN_DAYS = 0;
  static final int MAX_DAYS = 365;

  /** Stable identities sent in {@code error.messageKeys} — plain strings, not AD_MESSAGE. */
  static final String MSG_DAYS_INVALID = "ETGO_PaymentTermDaysInvalid";
  static final String MSG_DAYS_OUT_OF_RANGE = "ETGO_PaymentTermDaysOutOfRange";
  static final String MSG_DAYS_DUPLICATE = "ETGO_PaymentTermDaysDuplicate";
  static final String MSG_NAME_DUPLICATE = "ETGO_PaymentTermNameDuplicate";

  static final String CODE_DAYS_INVALID = "payment_term_days_invalid";
  static final String CODE_DAYS_OUT_OF_RANGE = "payment_term_days_out_of_range";
  static final String CODE_DAYS_DUPLICATE = "payment_term_days_duplicate";
  static final String CODE_NAME_DUPLICATE = "payment_term_name_duplicate";

  /** A signed integer short enough to never overflow; longer digit runs are not a day count. */
  private static final Pattern WHOLE_NUMBER = Pattern.compile("[+-]?\\d{1,9}");

  private static final String DATA_ENVELOPE = "data";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != null && context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    return NeoResponse.ensureTopLevelMessage(runWriteHook(context, SPEC, log,
        body -> validateAndEnrich(unwrap(body), context.getRecordId(), context)));
  }

  /**
   * ETP-5978: fills the days-derived create fields on MCP {@code etendo_create} before its
   * mandatory pre-check, which runs ahead of {@link #handle} on that channel (ETP-5710).
   *
   * <p>{@code searchKey} ({@code Value}) and {@code offsetMonthDue} ({@code FixMonthOffset}) are
   * mandatory in AD and hidden from the Payment Term window, so an agent creating a term from a
   * name and a number of days would otherwise get a {@code missingFields} 422 for fields it was
   * never offered. This runs the same {@link PaymentTermCreateDefaults#enrichCreate} the pre-hook
   * runs, so every channel
   * derives the same values.</p>
   *
   * <p>Abstains (body untouched) on an update, or when the days are absent, null or not a whole
   * number: the pre-check then reports what is missing, and the days themselves are validated and
   * normalized only by {@link #handle}. On MCP, though, an agent that omits the days rarely reaches
   * that branch: the mandatory-defaults pass runs first and fills {@code NetDays} with a
   * safe-type placeholder 0, and the context this hook receives does not say so (no fallback
   * defaults, no {@link NeoContext#ATTR_CLIENT_PROVIDED_FIELDS}). The zero-day shape is then
   * derived here, harmlessly: the pre-hook does see both signals, treats the placeholder as absent
   * ({@link PaymentTermCreateDefaults#dropServerFilledCreateFields}) and answers the same 400
   * {@value #MSG_DAYS_INVALID} as
   * REST, before anything is persisted. Days out of range are still enriched, so the pre-hook
   * answers its keyed 400 instead of a 422 for a key derived from them. Idempotent with the later
   * pre-hook and never overwrites a value the caller sent
   * ({@link PaymentTermCreateDefaults#enrichCreate}); never
   * throws.</p>
   *
   * @param context the MCP create context; its request body is mutated in place
   */
  @Override
  public void resolveServerCreateFields(NeoContext context) {
    Object rawDays = null;
    try {
      JSONObject body = context.getRequestBody();
      if (body == null || StringUtils.isNotBlank(context.getRecordId())) {
        return;
      }
      JSONObject fields = unwrap(body);
      if (!PaymentTermCreateDefaults.isCallerValue(context, fields, F_DAYS)
          || fields.isNull(F_DAYS)) {
        return;
      }
      rawDays = fields.get(F_DAYS);
      Long days = parseWholeNumber(rawDays);
      if (days != null) {
        PaymentTermCreateDefaults.enrichCreate(fields, days);
      }
    } catch (JSONException | RuntimeException e) {
      log.debug("[ETP-5978] Payment term create defaults skipped (days={}): {}", rawDays,
          e.getMessage());
    }
  }

  /** The record fields, unwrapped from the optional {@code {"data": {...}}} envelope. */
  static JSONObject unwrap(JSONObject body) {
    JSONObject data = body.optJSONObject(DATA_ENVELOPE);
    return data != null ? data : body;
  }

  /**
   * Validates the days and the duplicate rule, and enriches a create with the derived fields.
   *
   * @param body     the record fields (already unwrapped); mutated to normalize the days and to
   *                 inject the derived fields on create
   * @param recordId the record being updated, or blank on create
   * @return {@code null} to let the generic CRUD proceed, or the refusal
   * @throws JSONException if reading or mutating the body fails
   */
  NeoResponse validateAndEnrich(JSONObject body, String recordId) throws JSONException {
    return validateAndEnrich(body, recordId, null);
  }

  /**
   * {@link #validateAndEnrich(JSONObject, String)} with the request context, which a create needs
   * to resolve the organization the record will get when the caller does not send one (name
   * uniqueness is per organization), and to tell the caller's values from the ones an MCP create
   * filled before the pre-hook ({@link PaymentTermCreateDefaults#dropServerFilledCreateFields}).
   * Without a context both are
   * skipped.
   */
  NeoResponse validateAndEnrich(JSONObject body, String recordId, NeoContext context)
      throws JSONException {
    boolean create = StringUtils.isBlank(recordId);
    PaymentTerm stored = create ? null : loadStored(recordId);
    if (!create && stored == null) {
      // Unknown or unreadable id: the generic CRUD produces the canonical 404.
      return null;
    }
    if (create) {
      PaymentTermCreateDefaults.dropServerFilledCreateFields(body, context);
    }
    NeoResponse refusal = validateDaysAndEnrich(body, create, stored);
    if (refusal == null) {
      refusal = checkNameUnique(body, stored, context);
    }
    if (refusal == null && !create) {
      PaymentTermRenameSync.rememberRename(context, stored, submittedString(body, F_NAME));
    }
    return refusal;
  }

  /**
   * QA BUG-1 (ETP-5978): after a successful rename, copies the new name into every
   * {@code C_PaymentTerm_Trl} row of the term and into the {@code _identifier} the write echoes.
   *
   * <p>{@code C_PaymentTerm.Name} is translatable and is the entity's only identifier column, so
   * the identifier core serialises ({@code DataToJsonConverter} → {@code
   * BaseOBObject.getIdentifier()}) is read from the translation row of the request language, not
   * from the base column. {@code C_PAYMENTTERM_TRG} copies the name into the translations on
   * insert, but on update only sets {@code IsTranslated = 'N'} and leaves their text alone. A
   * rename therefore kept the old name everywhere the identifier is shown — the PATCH response,
   * the title and breadcrumb, every payment-term selector — while {@code name} itself and the
   * database base row were right.</p>
   *
   * <p><b>Every language is overwritten, by design.</b> The rename replaces the {@code name} of
   * every {@code C_PaymentTerm_Trl} row of the term, whatever its language: the trigger has just
   * flagged all of them {@code IsTranslated = 'N'} (out of date), and Etendo GO has no
   * translation tab for payment terms, so nothing else could ever correct them.</p>
   *
   * <p><b>Not atomic with the rename.</b> This runs only after the write succeeded (the
   * dispatcher skips {@code afterHandle} on an error result, and this checks again), and on a
   * single REST or MCP write that write is already committed by the write service; the
   * translation update runs in a second transaction, committed at the end of the request. A
   * failure here is logged at error and leaves the rename saved, the response as it was and the
   * identifier stale until the name is changed again. Inside a batch both share the batch's
   * transaction.</p>
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    Object renamedTo = context.getAttribute(PaymentTermRenameSync.ATTR_RENAMED_TO);
    NeoResponse saved = context.getPreviousResult();
    if (!(renamedTo instanceof String) || saved == null || saved.getHttpStatus() >= 400
        || StringUtils.isBlank(context.getRecordId())) {
      return null;
    }
    String name = (String) renamedTo;
    try {
      syncTranslatedName(context.getRecordId(), name);
      PaymentTermRenameSync.applyIdentifier(saved.getBody(), context.getRecordId(), name);
    } catch (Exception e) {
      log.error("[ETP-5978] Could not sync the translated name of payment term {}: {}",
          context.getRecordId(), e.getMessage(), e);
    }
    return null;
  }

  private NeoResponse validateDaysAndEnrich(JSONObject body, boolean create, PaymentTerm stored)
      throws JSONException {
    Long storedDays = stored == null ? null : stored.getOverduePaymentDaysRule();
    NeoResponse daysRefusal = checkDays(body, create, storedDays);
    if (daysRefusal != null) {
      return daysRefusal;
    }
    Long days = body.has(F_DAYS) ? Long.valueOf(body.getLong(F_DAYS)) : storedDays;
    if (days == null) {
      return null;
    }

    if (create) {
      PaymentTermCreateDefaults.enrichCreate(body, days);
    }
    return checkDuplicate(body, stored, days);
  }

  /**
   * Refuses a simple term whose days another simple term of the same client already uses. Only
   * runs when the term being written is itself simple and, on update, when its days or shape
   * change.
   *
   * @param stored the record being updated, or {@code null} on create
   * @return the 409, or {@code null}
   */
  NeoResponse checkDuplicate(JSONObject body, PaymentTerm stored, long days)
      throws JSONException {
    Long offset = effectiveOffset(body, stored);
    boolean fixedDueDate = effectiveFixedDueDate(body, stored);
    if (!isSimpleShape(offset, fixedDueDate)) {
      return null;
    }
    if (stored != null
        && (!changesDaysOrShape(stored, days, offset, fixedDueDate) || hasLines(stored))) {
      return null;
    }
    String clientId = stored == null ? currentClientId() : stored.getClient().getId();
    PaymentTerm existing = findSimpleTermWithDays(clientId, days,
        stored == null ? null : stored.getId());
    return existing == null ? null : PaymentTermRefusals.duplicate(days, existing);
  }

  /**
   * Refuses a name another term of the same client already uses in the organization the record
   * will be saved in — the {@code C_PAYMENTTERM_NAME} unique constraint ({@code AD_Client_ID,
   * AD_Org_ID, Name}), compared exactly as the database does (case-sensitive, untrimmed), so the
   * caller gets a keyed 409 instead of the raw constraint violation. Catches what the days rule
   * cannot: a term that is not simple (a month offset, a fixed due date, lines) but is named
   * like the {@code "N Días"} a days-only create derives.
   *
   * <p>On update it only runs when the name changes (the organization cannot: it is
   * server-owned). It is skipped when the name is blank (the generic CRUD's mandatory check
   * answers) or the organization cannot be resolved.</p>
   *
   * @param stored  the record being updated, or {@code null} on create
   * @param context the request, used to resolve the organization of a create; may be {@code null}
   * @return the 409, or {@code null}
   */
  NeoResponse checkNameUnique(JSONObject body, PaymentTerm stored, NeoContext context)
      throws JSONException {
    String name = submittedString(body, F_NAME);
    if (name == null && stored != null) {
      name = stored.getName();
    }
    if (StringUtils.isBlank(name)) {
      return null;
    }
    if (stored != null && name.equals(stored.getName())) {
      return null;
    }
    String orgId = resolveTargetOrgId(stored, body, context);
    if (orgId == null) {
      return null;
    }
    String clientId = stored == null ? currentClientId() : stored.getClient().getId();
    PaymentTerm existing = findTermWithName(clientId, orgId, name,
        stored == null ? null : stored.getId());
    return existing == null ? null : PaymentTermRefusals.nameDuplicate(existing);
  }

  /** The string the body sets for {@code key}, untrimmed, or {@code null} when absent or null. */
  private static String submittedString(JSONObject body, String key) throws JSONException {
    if (!body.has(key) || body.isNull(key)) {
      return null;
    }
    return String.valueOf(body.get(key));
  }

  /**
   * The organization the record will be saved in. A caller-sent {@code organization} is never
   * it: {@link NeoServerOwnedFields} strips it from every write after this pre-hook. So, on
   * update, the stored one; on create, the one the generic create defaults — resolved by running
   * the same {@link NeoMandatoryDefaultsService} pass every channel uses (REST single, batch, MCP)
   * on a scratch copy of the body, server-owned fields stripped, without the callout cascade.
   * {@code null} when it cannot be resolved.
   */
  String resolveTargetOrgId(PaymentTerm stored, JSONObject body, NeoContext context)
      throws JSONException {
    if (stored != null) {
      return stored.getOrganization() == null ? null : stored.getOrganization().getId();
    }
    if (context == null || context.getAdTab() == null) {
      return null;
    }
    JSONObject scratch = new JSONObject(body.toString());
    NeoServerOwnedFields.stripServerOwned(scratch, null);
    NeoMandatoryDefaultsService.injectMandatoryDefaults(scratch, context.getAdTab(), context, null,
        false);
    return idOf(scratch, F_ORGANIZATION);
  }

  private static String idOf(JSONObject body, String key) throws JSONException {
    if (!body.has(key) || body.isNull(key)) {
      return null;
    }
    Object value = body.get(key);
    String id = value instanceof JSONObject ? ((JSONObject) value).optString("id", null)
        : String.valueOf(value);
    return StringUtils.trimToNull(id);
  }

  /**
   * Validates the submitted days and normalizes them to a number in the body. On update, a value
   * equal to the stored one skips the range check (a legacy term stays editable).
   *
   * @return the refusal, or {@code null} when the days are acceptable or not being written
   */
  static NeoResponse checkDays(JSONObject body, boolean create, Long storedDays)
      throws JSONException {
    if (!body.has(F_DAYS) || body.isNull(F_DAYS)) {
      // Required on create; an explicit null on update would clear a mandatory column.
      return create || body.has(F_DAYS) ? PaymentTermRefusals.invalidDays(null) : null;
    }
    Object raw = body.get(F_DAYS);
    Long days = parseWholeNumber(raw);
    if (days == null) {
      return PaymentTermRefusals.invalidDays(raw);
    }
    body.put(F_DAYS, days.longValue());
    if ((create || !days.equals(storedDays)) && !isInRange(days)) {
      return PaymentTermRefusals.daysOutOfRange(days);
    }
    return null;
  }

  /** True when an update writes other days or another due-date shape than the stored ones. */
  static boolean changesDaysOrShape(PaymentTerm stored, long days, Long offset,
      boolean fixedDueDate) {
    return !Objects.equals(stored.getOverduePaymentDaysRule(), days)
        || normalizeOffset(offset) != normalizeOffset(stored.getOffsetMonthDue())
        || fixedDueDate != Boolean.TRUE.equals(stored.isFixedDueDate());
  }

  static boolean isInRange(long days) {
    return days >= MIN_DAYS && days <= MAX_DAYS;
  }

  /**
   * The value as a whole number, or {@code null} when it is not one: a JSON integer, an integral
   * decimal ({@code 12.0}) or a string of digits with an optional sign ({@code "12"}). Rejects
   * fractions ({@code 7.5}, {@code "7,5"}), text and expressions ({@code "50% 30 / 50% 60"}).
   */
  static Long parseWholeNumber(Object value) {
    if (value instanceof Integer || value instanceof Long) {
      return ((Number) value).longValue();
    }
    if (value instanceof Number) {
      return integralValue(value.toString());
    }
    if (value instanceof String) {
      String text = ((String) value).trim();
      return WHOLE_NUMBER.matcher(text).matches() ? Long.valueOf(text) : null;
    }
    return null;
  }

  private static Long integralValue(String number) {
    try {
      return new BigDecimal(number).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      // a fraction, NaN/Infinity, or beyond a long
      return null;
    }
  }

  /** A term the days alone describe: no fixed due date and no month offset. Lines are checked apart. */
  static boolean isSimpleShape(Long offsetMonthDue, boolean fixedDueDate) {
    return !fixedDueDate && normalizeOffset(offsetMonthDue) == 0L;
  }

  private static long normalizeOffset(Long offset) {
    return offset == null ? 0L : offset;
  }

  private static Long effectiveOffset(JSONObject body, PaymentTerm stored) throws JSONException {
    if (body.has(F_OFFSET_MONTH)) {
      if (body.isNull(F_OFFSET_MONTH)) {
        return null;
      }
      Long parsed = parseWholeNumber(body.get(F_OFFSET_MONTH));
      // Not a number: the generic CRUD refuses it; treat it as non-simple meanwhile.
      return parsed == null ? Long.valueOf(-1L) : parsed;
    }
    return stored == null ? null : stored.getOffsetMonthDue();
  }

  private static boolean effectiveFixedDueDate(JSONObject body, PaymentTerm stored)
      throws JSONException {
    if (body.has(F_FIXED_DUE_DATE) && !body.isNull(F_FIXED_DUE_DATE)) {
      Object value = body.get(F_FIXED_DUE_DATE);
      return Boolean.TRUE.equals(value) || "Y".equalsIgnoreCase(String.valueOf(value))
          || "true".equalsIgnoreCase(String.valueOf(value));
    }
    return stored != null && Boolean.TRUE.equals(stored.isFixedDueDate());
  }

  // --- data access, package-private so unit tests can stub them on a spy ---------------------

  PaymentTerm loadStored(String recordId) {
    return OBDal.getInstance().get(PaymentTerm.class, recordId);
  }

  /** Seam over {@link PaymentTermRenameSync#syncTranslatedName}, stubbed by unit tests. */
  int syncTranslatedName(String termId, String name) {
    return PaymentTermRenameSync.syncTranslatedName(termId, name);
  }

  boolean hasLines(PaymentTerm term) {
    OBQuery<PaymentTerm> query = OBDal.getInstance().createQuery(PaymentTerm.class,
        "as pt where pt.id = :id and exists (select 1 from FinancialMgmtPaymentTermLine l"
            + " where l.paymentTerms.id = pt.id)");
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setNamedParameter("id", term.getId());
    query.setMaxResult(1);
    return !query.list().isEmpty();
  }

  String currentClientId() {
    return OBContext.getOBContext().getCurrentClient().getId();
  }

  /**
   * Another simple term of {@code clientId} with {@code days}, in any organization and active or
   * not; an active one is preferred so the caller is pointed at a term it can use straight away.
   *
   * @param excludeId the record being updated, or {@code null} on create
   */
  PaymentTerm findSimpleTermWithDays(String clientId, long days, String excludeId) {
    StringBuilder where = new StringBuilder("as pt where pt.client.id = :clientId")
        .append(" and pt.overduePaymentDaysRule = :days")
        .append(" and pt.fixedDueDate = false")
        .append(" and coalesce(pt.offsetMonthDue, 0) = 0")
        .append(" and not exists (select 1 from FinancialMgmtPaymentTermLine l")
        .append(" where l.paymentTerms.id = pt.id)");
    if (excludeId != null) {
      where.append(" and pt.id <> :excludeId");
    }
    where.append(" order by pt.active desc, pt.creationDate asc");
    OBQuery<PaymentTerm> query = OBDal.getInstance().createQuery(PaymentTerm.class, where.toString());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("days", days);
    if (excludeId != null) {
      query.setNamedParameter("excludeId", excludeId);
    }
    query.setMaxResult(1);
    List<PaymentTerm> found = query.list();
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * Another term of {@code clientId} named exactly {@code name} in organization {@code orgId},
   * active or not (the unique constraint ignores both readability and the active flag).
   *
   * @param excludeId the record being updated, or {@code null} on create
   */
  PaymentTerm findTermWithName(String clientId, String orgId, String name, String excludeId) {
    StringBuilder where = new StringBuilder("as pt where pt.client.id = :clientId")
        .append(" and pt.organization.id = :orgId")
        .append(" and pt.name = :name");
    if (excludeId != null) {
      where.append(" and pt.id <> :excludeId");
    }
    OBQuery<PaymentTerm> query = OBDal.getInstance().createQuery(PaymentTerm.class, where.toString());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("orgId", orgId);
    query.setNamedParameter("name", name);
    if (excludeId != null) {
      query.setNamedParameter("excludeId", excludeId);
    }
    query.setMaxResult(1);
    List<PaymentTerm> found = query.list();
    return found.isEmpty() ? null : found.get(0);
  }
}
