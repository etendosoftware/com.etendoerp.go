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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code document-sequence} spec ({@code sequence} entity, table
 * {@code AD_Sequence}) that rejects an invoice-series prefix the Spanish fiscal localizations
 * would refuse — before it is stored, rather than when the first affected invoice is completed
 * (ETP-5190).
 *
 * <p><b>Why this is not left to classic.</b> The same rules exist in
 * {@code com.smf.ticketbai}'s {@code ProcessInvoiceTbaiHook#preProcess}, but that hook only runs
 * when a <i>rectificative</i> invoice is completed in a TicketBAI-configured organization
 * ({@code tbaiConfig != null && "CO".equals(strDocAction) && invoice.isTbaiIsreverseinvoice()}).
 * A prefix chosen during onboarding therefore stayed unchecked until the tenant happened to
 * issue that first corrective invoice — at which point the numbering is already in use and the
 * prefix can no longer be changed without breaking the series. Validating on write moves the
 * refusal to the only moment it is still cheap to act on.
 *
 * <p>The rules are copied from that hook and are deliberately NOT re-expressed in terms of it:
 * {@code ProcessInvoiceTbaiHook} keeps them inline in an {@code if/else} chain over an
 * {@code Invoice}, with nothing extractable to call.
 *
 * <p><b>Why the country gate.</b> These are localization rules, not Etendo ones — {@code W} is
 * an ordinary prefix letter in most of the world. They are applied only when the organization's
 * country is Spain, resolved through {@code AD_OrgInfo} → {@code C_Location} →
 * {@code C_Country.ISO_Country_Code}. Widening the gate (or moving these rules into the
 * localization modules that own them) is a Localization-team decision, not a GO one.
 *
 * <p>Country resolution lives in {@link OrganizationCountrySupport}. When no country can be
 * resolved at all the prefix is accepted: refusing a save because the address is not filled in
 * yet would break the very onboarding step this validation exists to serve.
 *
 * <p>It also SCOPES THE LIST. {@code AD_Sequence} holds 242 rows per provisioned tenant
 * (measured on the instance), almost all of them record-ID and internal counters that mean
 * nothing to a user looking for their invoice numbering — so the list is narrowed to the
 * caller's own client and to {@link #VISIBLE_SEQUENCE_NAMES}. See {@code applyListScope}.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2: {@code lookupHandler()} reads {@code @Named}
 * off {@code handler.getClass()}, and a normal-scoped bean resolves to a Weld client proxy whose
 * subclass does not carry the (non-{@code @Inherited}) annotation, so the qualifier silently
 * stops being discovered.
 */
@Named("document-sequence")
public class DocumentSequenceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(DocumentSequenceHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";

  private static final String CRITERIA_PARAM = "criteria";
  private static final String FIELD_NAME = "fieldName";
  private static final String OPERATOR = "operator";
  private static final String VALUE = "value";

  /** Contract field names of {@code AD_Sequence.Name} and {@code AD_Client_ID}. */
  private static final String FIELD_SEQUENCE_NAME = "name";
  private static final String FIELD_CLIENT = "client";

  /**
   * The only sequences this window shows, by {@code AD_Sequence.Name}.
   *
   * <p>A PRODUCT decision, not a technical one: a provisioned tenant has 242 sequences, and the
   * rest are record-ID counters and internal numbering that a user has no reason to open — a
   * 242-row list is not a shorter path to the invoice series than the Classic window was.
   * Adding a sequence to the product means adding its name here.
   *
   * <p>Matched on the name because that is what identifies these rows across tenants: every one
   * of the seven was verified to exist, by this exact name, exactly once per organization, in a
   * provisioned client.
   *
   * <p><b>Every {@code DocumentNo_*} name is deliberately absent</b>, for two independent
   * reasons.
   *
   * <p>They are DUPLICATED in the data — provisioning creates each twice (6912 surplus rows
   * across 72 of 94 clients), because the client setup writes them and
   * {@code generateOnboardingSequences} then runs Etendo's Create Sequences over the same
   * client. Numbering survives that by accident: {@code ad_sequence_doc} increments every row
   * matching the name and reads one back with a non-{@code STRICT} {@code SELECT INTO}, so both
   * copies advance in lockstep and either answer is the same. Editing ONE of a pair breaks
   * exactly that — the rows diverge, an arbitrary one still answers, and PostgreSQL relocates an
   * updated row, so a prefix would apply intermittently.
   *
   * <p>And for the one series a tenant might actually want to prefix there is nothing to
   * configure: {@code DocumentNo_C_Invoice} numbers purchase invoices, but {@code AP Invoice}
   * carries {@code IsDocNoControlled='N'} and no sequence in 76 of 76 doctypes across all 75
   * clients, while every other invoice doctype has both. That is stock Openbravo semantics for
   * "the number comes from outside" — the supplier numbers a purchase invoice, and the fallback
   * counter only supplies a proposed value. So de-duplicating the data would NOT make these
   * names worth exposing; do not treat that data-fix as a prerequisite for re-adding them.
   *
   * <p>What their absence costs: the doctypes without a sequence of their own are unreachable
   * from here — {@code AP Invoice} and {@code AP CreditMemo}, {@code MM Receipt}, plus asset and
   * internal-movement numbering. One fallback row is SHARED by every doctype lacking a
   * sequence, so that entry point changed all of them at once. See the window's guide.
   */
  static final List<String> VISIBLE_SEQUENCE_NAMES = List.of(
      "AR Invoice",
      "AP Payment",
      "AR Receipt",
      "MM Shipment",
      "Standard Order",
      "Purchase Order",
      "Secuencia TICKETBAI");

  /** Contract field name of {@code AD_Sequence.Prefix} (see the window's decisions.json). */
  static final String FIELD_PREFIX = "prefix";

  /** {@code ProcessInvoiceTbaiHook}: {@code docPrefix.length() > 20}. */
  static final int MAX_PREFIX_LENGTH = 20;

  /** {@code ProcessInvoiceTbaiHook}: {@code Pattern.compile("[a-záéíóúüñ]")}. */
  private static final Pattern LOWERCASE_OR_ACCENT = Pattern.compile("[a-záéíóúüñ]");

  /** {@code ProcessInvoiceTbaiHook}: {@code Pattern.compile("[IOYWÑ]")}. */
  private static final Pattern FORBIDDEN_LETTERS = Pattern.compile("[IOYWÑ]");

  /** {@code ProcessInvoiceTbaiHook}: {@code Pattern.compile("[^A-Z0-9-]")}. */
  private static final Pattern INVALID_CHARS = Pattern.compile("[^A-Z0-9-]");

  /*
   * English, and registered in the frontend's backendErrors.js map so both locales render it —
   * the pattern ERR_DUPLICATE_CODE in ChartOfAccountsSaveValidationSupport documents as the
   * right one.
   */
  static final String ERR_PREFIX_TOO_LONG =
      "The prefix cannot be longer than 20 characters.";
  static final String ERR_PREFIX_LOWER_OR_ACCENT =
      "The prefix cannot contain lowercase or accented letters.";
  static final String ERR_PREFIX_FORBIDDEN_LETTERS =
      "The prefix cannot contain the letters I, O, Y, W or Ñ.";
  static final String ERR_PREFIX_INVALID_CHARS =
      "The prefix can only contain uppercase letters, digits and hyphens.";

  /**
   * Pre-hook: validates the submitted prefix on create/update and short-circuits with a
   * {@code 400} when a rule is violated.
   *
   * @param context the NEO request context
   * @return an error {@link NeoResponse} when the prefix is rejected, {@code null} to continue
   *     with the default CRUD
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null || context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (METHOD_GET.equalsIgnoreCase(method)) {
      applyListScope(context);
      return null;
    }
    if (!METHOD_POST.equalsIgnoreCase(method) && !METHOD_PUT.equalsIgnoreCase(method)) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || !body.has(FIELD_PREFIX)) {
      return null;
    }
    String prefix = StringUtils.trimToNull(body.optString(FIELD_PREFIX, null));
    if (prefix == null) {
      // Clearing the prefix is always allowed — the localizations constrain its CONTENT.
      return null;
    }
    if (!requiresSpanishPrefixRules(context)) {
      return null;
    }
    String error = validateSpanishPrefix(prefix);
    return error == null ? null : NeoResponse.error(400, error);
  }

  /**
   * Applies the four {@code ProcessInvoiceTbaiHook} rules in that hook's own order.
   *
   * @param prefix the submitted prefix, already trimmed and non-null
   * @return the message to answer with, or {@code null} when the prefix is acceptable
   */
  static String validateSpanishPrefix(String prefix) {
    if (prefix.length() > MAX_PREFIX_LENGTH) {
      return ERR_PREFIX_TOO_LONG;
    }
    if (LOWERCASE_OR_ACCENT.matcher(prefix).find()) {
      return ERR_PREFIX_LOWER_OR_ACCENT;
    }
    if (FORBIDDEN_LETTERS.matcher(prefix).find()) {
      return ERR_PREFIX_FORBIDDEN_LETTERS;
    }
    if (INVALID_CHARS.matcher(prefix).find()) {
      return ERR_PREFIX_INVALID_CHARS;
    }
    return null;
  }

  /**
   * True when the request's organization resolves to Spain — see
   * {@link OrganizationCountrySupport}, which the tax-identifier handler shares.
   */
  private boolean requiresSpanishPrefixRules(NeoContext context) {
    OBContext obContext = context.getObContext();
    return OrganizationCountrySupport.isSpain(
        obContext == null ? null : obContext.getCurrentOrganization());
  }

  /**
   * Narrows a list GET to the caller's own client and to {@link #VISIBLE_SEQUENCE_NAMES}, by
   * appending clauses to the request's own {@code criteria} parameter.
   *
   * <p>Done by INJECTING criteria rather than by filtering the result: the pre-hook shares its
   * {@link NeoContext} with the default CRUD that runs after it, so the narrowed query is the
   * one that executes — which keeps paging, sorting and the total count honest. Filtering the
   * response instead would have returned "page 1 of 242" and then thrown most of it away, so
   * the first page could legitimately have come back empty.
   *
   * <p>Top-level criteria clauses are ANDed by {@code AdvancedQueryBuilder}, so appending is
   * enough — a user's own filter still applies, narrowed further rather than replaced.
   *
   * <p>Applies to the LIST only. A GET by id is left alone: these are the tenant's own records
   * and the point here is a shorter list, not access control, so a deep link into a sequence
   * that the list does not show still resolves.
   *
   * <p>Never throws. A criteria parameter this method cannot parse is left untouched — a
   * cluttered list is a far better failure than a 500 on a window that has just loaded.
   */
  private void applyListScope(NeoContext context) {
    if (context.getRecordId() != null) {
      return;
    }
    Map<String, String> params = context.getQueryParams();
    if (params == null) {
      return;
    }
    try {
      JSONArray criteria = readCriteria(params.get(CRITERIA_PARAM));
      criteria.put(new JSONObject()
          .put(FIELD_NAME, FIELD_SEQUENCE_NAME)
          .put(OPERATOR, "inSet")
          .put(VALUE, new JSONArray(VISIBLE_SEQUENCE_NAMES)));
      String clientId = currentClientId(context);
      if (clientId != null) {
        // The tenant's own sequences only. Redundant today — the instance has no sequences at
        // client "0", so DAL's readable-clients filter already yields tenant-only rows — but
        // stated explicitly so a module that later ships System-level sequences cannot leak
        // them into a tenant's window.
        criteria.put(new JSONObject()
            .put(FIELD_NAME, FIELD_CLIENT)
            .put(OPERATOR, "equals")
            .put(VALUE, clientId));
      }
      params.put(CRITERIA_PARAM, criteria.toString());
    } catch (JSONException e) {
      log.warn("DocumentSequenceHandler: could not narrow the sequence list ({}); "
          + "serving it unfiltered", e.getMessage());
    }
  }

  /**
   * The request's existing criteria, or a fresh array. A value that is not a JSON array is
   * discarded rather than merged: {@code NeoCrudHandler} treats an unparseable criteria the same
   * way, so this stays consistent with it instead of inventing a second interpretation.
   */
  private JSONArray readCriteria(String raw) {
    if (StringUtils.isBlank(raw)) {
      return new JSONArray();
    }
    try {
      return new JSONArray(raw);
    } catch (JSONException e) {
      log.debug("DocumentSequenceHandler: existing criteria is not a JSON array ({}); "
          + "replacing it with the window's own scope", e.getMessage());
      return new JSONArray();
    }
  }

  private String currentClientId(NeoContext context) {
    OBContext obContext = context.getObContext();
    return obContext == null || obContext.getCurrentClient() == null
        ? null : obContext.getCurrentClient().getId();
  }
}
