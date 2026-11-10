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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Sequence;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoExtension;
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
 * <p>It also SCOPES THE SPEC. {@code AD_Sequence} holds ~145 rows per provisioned tenant,
 * almost all of them record-ID and internal counters that mean nothing to a user looking for
 * their invoice numbering — so the spec exposes only the caller's own client's rows among the
 * seven document series in {@link #VISIBLE_SEQUENCE_NAMES} (ETP-5285, ETP-5364, ETP-5837). The
 * list is narrowed through {@link #readPredicates} and every read or write by id is refused
 * outside that set — see {@link #refuseHiddenRecord}. Since ETP-5837 this is access control, not
 * just a shorter list: the hidden rows include shared fallback counters ({@code DocumentNo_*})
 * whose prefix would change every doctype without a sequence of its own.
 *
 * <p>Bound by {@link NeoExtension} (ETP-5837 migrated it off the legacy
 * {@code Java_Qualifier}/{@code @Named("document-sequence")} binding), so every channel —
 * REST single, batch and MCP — resolves it through {@code NeoExtensionDispatcher}, and
 * {@link #readPredicates} through {@code NeoReadPredicates}. Never give it a normal CDI scope:
 * it stays a {@code @Dependent} bean ({@code beans.xml} discovers all classes).
 */
@NeoExtension(spec = "document-sequence", entity = "sequence")
public class DocumentSequenceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(DocumentSequenceHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";

  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_DELETE = "DELETE";

  /** Etendo AD ids: 32 hex chars, or the numeric legacy ids. Guards the literal in the HQL. */
  private static final Pattern AD_ID = Pattern.compile("^(?:[0-9A-Fa-f]{32}|\\d+)$");

  /**
   * The only sequences this window shows, by {@code AD_Sequence.Name}.
   *
   * <p>A PRODUCT decision, not a technical one: a provisioned tenant has 242 sequences, and the
   * rest are record-ID counters and internal numbering that a user has no reason to open — a
   * 242-row list is not a shorter path to the invoice series than the Classic window was.
   * Adding a sequence to the product means adding its name here.
   *
   * <p>Matched on the name because that is what identifies these rows across tenants: every one
   * of these was verified to exist, by this exact name, exactly once per organization, in a
   * provisioned client. The name is consequently NOT editable from the window — {@code
   * decisions.json} classifies it {@code readOnly} — because renaming a row here would drop it
   * out of this very allowlist and make it unreachable, silently and permanently.
   *
   * <p><b>ETP-5285 narrowed this list from seven to five; ETP-5364 completed it at six;
   * ETP-5837 added the sales quotation as the seventh.</b> The product defines exactly seven
   * document series (purchase order {@code PC}, sales order {@code PV}, sales quotation
   * {@code PR}, sales invoice {@code FV}, corrective sales invoice {@code FVR}, purchase invoice
   * {@code FC}, corrective purchase invoice {@code FCR}), and all seven are below. {@code AP
   * Payment}, {@code AR Receipt}, {@code MM Shipment} and {@code Secuencia TICKETBAI} were
   * dropped by ETP-5285: they are not document series a tenant configures on this screen.
   * Dropping a name here only hides the row — nothing is deleted, and the numbering those
   * sequences drive is untouched.
   *
   * <p><b>{@code AP Invoice} ({@code FC}) was added by ETP-5364</b>, which is a change to how
   * purchase invoices are numbered, not merely a change to this list. Stock Openbravo ships
   * {@code AP Invoice} as {@code IsDocNoControlled='N'} with no sequence — "the number comes
   * from outside", i.e. the supplier numbers the purchase invoice. GO now ships its own series
   * instead: {@code GOClient/AD_SEQUENCE.xml} carries an {@code AP Invoice} sequence with prefix
   * {@code FC}, and {@code GOClient/C_DOCTYPE.xml} points the {@code AP Invoice} doctype at it
   * with {@code IsDocNoControlled='Y'}. Both halves are required — a sequence nothing points at
   * would show a configurable prefix that governs no numbering at all.
   *
   * <p><b>Preventive only.</b> That pair reaches NEW tenants through the dataset. Tenants
   * provisioned before ETP-5364 keep {@code IsDocNoControlled='N'} and own no {@code AP Invoice}
   * sequence, so this window shows them five rows rather than six — the name filter matches
   * nothing, which is the whole failure mode. Retrofitting them is a data-fix that was
   * deliberately not written here.
   *
   * <p><b>Every {@code DocumentNo_*} name is deliberately absent.</b> {@code
   * DocumentNo_C_Invoice} and friends are fallback counters that supply a *proposed* value for
   * any doctype lacking a sequence of its own, so a prefix there would configure a series that
   * is not authoritative for any document. One such row is SHARED by every doctype without a
   * sequence, so exposing it would have changed all of them at once.
   *
   * <p>They were also DUPLICATED in the data until ETP-5364 — provisioning created each twice
   * (9888 surplus rows across 103 of 125 clients), because Openbravo's {@code
   * InitialClientSetup} creates the 97 {@code DocumentNo_*} rows for a new client and the
   * onboarding dataset then imported 96 of the same names again. ETP-5364 drops those 96 at
   * IMPORT time ({@code OnboardingDatasetNormalizer.TableCounterSequenceFilter}) rather than
   * deleting them from {@code GOClient/AD_SEQUENCE.xml}, which stays complete because
   * {@code install.source} seeds the GOClient sample client from it wholesale and never runs the
   * client setup. A tenant provisioned from here on gets one copy of each. Already-provisioned
   * tenants still carry both: numbering survives that by accident ({@code ad_sequence_doc}
   * increments every row matching the name and reads one back with a non-{@code STRICT}
   * {@code SELECT INTO}, so the copies advance together), but editing ONE of a pair breaks exactly
   * that. Never a reason to expose these names.
   *
   * <p><b>{@code Quotation} ({@code PR}) was added by ETP-5837.</b> The quotation already had
   * its own series — the {@code Quotation} doctype ({@code DocSubTypeSO='OB'}) points at a
   * {@code Quotation} sequence with {@code IsDocNoControlled='Y'} — but it was not configurable
   * here and shipped without a prefix, so a quotation's number was indistinguishable from an
   * order's. {@code GOClient/AD_SEQUENCE.xml} now gives it the prefix {@code PR}, which reaches
   * NEW tenants. Existing tenants are retrofitted by the schema_forge data-fix
   * {@code R57-quotation-sequence-pr-prefix}: it sets {@code PR} on their {@code Quotation}
   * sequence and renumbers their existing quotations with it.
   *
   * <p>What their absence still costs: {@code AP CreditMemo}, {@code MM Receipt}, plus asset and
   * internal-movement numbering have no sequence of their own and stay unreachable from here.
   * See the window's guide.
   */
  static final List<String> VISIBLE_SEQUENCE_NAMES = List.of(
      "Purchase Order",
      "Standard Order",
      "Quotation",
      "AR Invoice",
      "Factura Rectificativa (Ventas)",
      "AP Invoice",
      "Factura Rectificativa (Compras)");

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
   * The answer to a read or write by id of a sequence this spec does not expose (ETP-5837).
   * Deliberately the same 404 a missing record gets: a hidden sequence is not a record of this
   * spec, and a 403 would confirm that the id exists.
   */
  static final String ERR_SEQUENCE_NOT_FOUND = "Document sequence not found.";

  /**
   * Pre-hook: refuses with a {@code 404} any read or write by id of a sequence outside the
   * spec's scope, then validates the submitted prefix on create/update and short-circuits with a
   * {@code 400} when a rule is violated.
   *
   * @param context the NEO request context
   * @return an error {@link NeoResponse} when the record is hidden or the prefix is rejected,
   *     {@code null} to continue with the default CRUD
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null || context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    NeoResponse hidden = refuseHiddenRecord(context);
    if (hidden != null) {
      return hidden;
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
   * The list scope, as predicates ANDed into the list query of every channel (ETP-5837): the
   * REST list {@code GET} and its count, export and {@code ?_distinct=} values, and MCP
   * {@code etendo_list} — see {@code NeoReadPredicates}. Only rows named in
   * {@link #VISIBLE_SEQUENCE_NAMES}, and only the caller's own client's.
   *
   * <p>It replaces the {@code criteria} injection this handler used to do from {@link #handle}.
   * That only narrowed the REST list, because MCP {@code etendo_list} builds its query from its
   * own {@code filters} and never reads a {@code criteria} parameter: an agent listing this spec
   * got every sequence of the tenant (149 rows on the QA instance). A predicate goes into the
   * query itself, so paging, sorting and the total count stay honest on every channel, and a
   * user's own filter is still ANDed on top rather than replaced.
   *
   * <p>The client clause is redundant today — the instance has no sequences at client
   * {@code "0"}, so DAL's readable-clients filter already yields tenant-only rows — but stated
   * so that a module which later ships System-level sequences cannot leak them into a tenant.
   *
   * <p>Spliced into the HQL text verbatim (there is no bind-parameter mechanism): the names are
   * server constants, quoted with {@code '} doubled; the client id comes from the session and is
   * matched against {@link #AD_ID} before use, so no request input reaches the query.
   *
   * @param context the read context
   * @return the HQL predicates over the alias {@code e}
   */
  @Override
  public List<String> readPredicates(NeoContext context) {
    List<String> predicates = new ArrayList<>();
    predicates.add("e.name in (" + quotedNames() + ")");
    String clientId = currentClientId(context);
    if (clientId == null) {
      // No session client to scope by: DAL's readable-clients filter still applies.
      return predicates;
    }
    if (AD_ID.matcher(clientId).matches()) {
      predicates.add("e.client.id = '" + clientId + "'");
    } else {
      // Fail closed: a session client id that is not an AD id cannot be spliced into the HQL,
      // and dropping the clause would widen the scope instead of narrowing it.
      log.warn("DocumentSequenceHandler: session client id '{}' is not an AD id; "
          + "serving an empty sequence list", clientId);
      predicates.add("1 = 0");
    }
    return predicates;
  }

  private static String quotedNames() {
    List<String> quoted = new ArrayList<>(VISIBLE_SEQUENCE_NAMES.size());
    for (String name : VISIBLE_SEQUENCE_NAMES) {
      quoted.add("'" + name.replace("'", "''") + "'");
    }
    return String.join(", ", quoted);
  }

  /**
   * Refuses a read or write by id ({@code GET}, {@code PUT}, {@code PATCH}, {@code DELETE}) of
   * one of the caller's own sequences that the spec does not expose — one whose name is not in
   * {@link #VISIBLE_SEQUENCE_NAMES} (ETP-5837).
   *
   * <p>A read by id is not covered by {@link #readPredicates} — core resolves it with its own
   * {@code id = :id} query and ignores the where clause — and neither is any write, so this is
   * where "the spec exposes only these rows" holds for them. Every channel reaches it: REST
   * single, batch and MCP all dispatch this pre-hook with the record id (MCP {@code etendo_get}
   * through its read provider, {@code etendo_update} as a {@code PUT}).
   *
   * <p>404, for three reasons: it is the NEO convention for a record outside a spec's scope
   * ({@code McpSpecScope}, ETP-5852 M-51), a 403 would confirm that the id exists, and nothing in
   * the UI links to a hidden sequence by id — the window is reached only through its own list.
   *
   * <p><b>What the answer reveals.</b> The lookup runs in admin mode so it can read the row's
   * client, but only an OWN-client row is ever answered from here. A row of another client, and
   * an id that does not exist at all, both return {@code null} and reach the default CRUD, whose
   * readable-client filter does not see the row — so the caller gets the same generic not-found
   * for both and cannot tell "exists in another tenant" from "does not exist". The one thing
   * this method's own 404 reveals is that an id names a sequence of the caller's own tenant
   * that this spec does not expose; within one's own tenant that is not a disclosure.
   *
   * @param context the CRUD request context
   * @return a {@code 404} when the target is an own-client sequence outside the allowlist,
   *     {@code null} otherwise (another client's row, a missing id, a list read and a create)
   */
  private NeoResponse refuseHiddenRecord(NeoContext context) {
    String method = context.getHttpMethod();
    boolean byIdMethod = METHOD_GET.equalsIgnoreCase(method) || METHOD_PUT.equalsIgnoreCase(method)
        || METHOD_PATCH.equalsIgnoreCase(method) || METHOD_DELETE.equalsIgnoreCase(method);
    String recordId = StringUtils.trimToNull(context.getReadId());
    if (!byIdMethod || recordId == null) {
      return null;
    }
    Sequence sequence;
    OBContext.setAdminMode(true);
    try {
      sequence = OBDal.getInstance().get(Sequence.class, recordId);
    } finally {
      OBContext.restorePreviousMode();
    }
    if (sequence == null) {
      return null;
    }
    String clientId = currentClientId(context);
    // Every channel (REST single, batch, MCP) builds its context with the session OBContext, so
    // a null client only happens outside a real request (unit tests): treat the row as the
    // caller's own and judge it by name alone. Neither branch can reveal another tenant's row:
    // both either refuse by name or hand over to the default CRUD.
    boolean ownClient = clientId == null
        || (sequence.getClient() != null && clientId.equals(sequence.getClient().getId()));
    if (!ownClient) {
      // Another tenant's row: indistinguishable from a missing id — let the default CRUD, whose
      // readable-client filter does not see it, answer its generic not-found.
      return null;
    }
    if (VISIBLE_SEQUENCE_NAMES.contains(sequence.getName())) {
      return null;
    }
    log.debug("DocumentSequenceHandler: refusing {} on hidden sequence {}", method, recordId);
    return NeoResponse.error(404, ERR_SEQUENCE_NOT_FOUND);
  }

  private String currentClientId(NeoContext context) {
    OBContext obContext = context == null ? null : context.getObContext();
    return obContext == null || obContext.getCurrentClient() == null
        ? null : obContext.getCurrentClient().getId();
  }
}
