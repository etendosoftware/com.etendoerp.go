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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.rest.CompanyInvitationService;
import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.AbstractSmartDeactivationHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoCrudHelper;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.etendoerp.go.schemaforge.util.UserRoleSyncSupport;

/**
 * NeoHandler for the {@code user} spec. Three independent concerns share this one class because
 * only one {@code JAVA_QUALIFIER} can be registered per {@code ETGO_SF_ENTITY} row (see
 * {@code docs/neo-headless-extensibility.md} §2.2), and this entity's qualifier was already
 * claimed by the role-sync concern below (ETP-4512):
 *
 * <ol>
 *   <li><b>Role sync (ETP-4512):</b> keeps {@code AD_User_Roles} in sync with
 *   {@code AD_User.Default_Ad_Role_ID}. The Go SPA's "assign role to user" UX (Configuración
 *   &gt; Usuarios) only ever offers a single-role dropdown backed by {@code Default_Ad_Role_ID}
 *   — see {@code AssignRoleControl.jsx} in {@code etendo_schema_forge}, which deliberately
 *   sources its options from the unrestricted {@code userRoles.role} selector rather than this
 *   field's own {@code Default_Ad_Role_ID} selector (which is filtered to roles the user
 *   already has, making it useless for assigning a *new* role). Real login/window-access checks
 *   read {@code AD_User_Roles}, not {@code Default_Ad_Role_ID}, so this handler is the only
 *   place that writes {@code AD_User_Roles} for a {@code user} save, enforcing at most one
 *   active row per user: every save deletes any existing row(s) for that user and inserts
 *   exactly one new row for the role currently set in {@code Default_Ad_Role_ID} (or leaves the
 *   user role-less if that field is cleared). Scoped to {@code PUT}/{@code PATCH} only —
 *   editing an existing user. Best-effort, secondary side effect: the {@link User} has already
 *   been saved by the time {@link #afterHandle(NeoContext)} runs, so a failure here must never
 *   fail the parent request — any exception is logged and swallowed, and {@code null} is
 *   returned so the original CRUD response is kept untouched.</li>
 *
 *   <li><b>Bootstrap-user hiding (2026-07-27):</b> the "Admin" ({@code AD_User_ID='100'}) and
 *   "System" ({@code AD_User_ID='0'}) accounts belong to the System client ({@code
 *   AD_Client_ID='0'}), which Openbravo's readable-client security model always treats as
 *   visible to every tenant — so they leaked into every tenant's "Usuarios" grid even though
 *   they are internal bootstrap accounts, never real assignable users. Filtered out of every
 *   GET-list response for this entity, unconditionally (no tenant needs to see or manage them
 *   through the Go SPA — the native classic backend remains the place for that kind of
 *   maintenance).</li>
 *
 *   <li><b>Admin-initiated user creation (ETP-4829/ETP-4830):</b> the admin-facing "create user"
 *   form never shows a username field (see {@code artifacts/user/decisions.json}'s create-only
 *   {@code username} override in {@code etendo_schema_forge}) — it is the email address for the
 *   first client and a client-suffixed username for later clients, matching the convention of
 *   {@code EtendoGoJwtDalHelper}/
 *   {@code EtendoGoJwtSupport} already rely on to link an {@code AD_User} row to its {@code
 *   etgo_account} row by matching value. The frontend never sends a {@code username}, but this
 *   is enforced server-side too (defense in depth): {@link #handle(NeoContext)} rewrites the
 *   {@code POST} request body's {@code username} to a normalized email-derived username
 *   (with the shared client suffix when needed)
 *   before the default CRUD create runs — reachable because {@link NeoContext#getRequestBody()}
 *   is the same mutable {@code JSONObject} the default service reads afterward (see {@code
 *   NeoServletSupport#handleWithHooks}). A blank/missing email is rejected with 400 before it
 *   ever reaches the DB's NOT NULL constraint, for a clearer error message. Once the {@code
 *   AD_User} is created, {@link #afterHandle(NeoContext)} reads the created record's {@code
 *   email} back out of the response body (POST's {@code recordId} is never populated on {@link
 *   NeoContext} — this is the one path that doesn't need it), FIRST ensures the new user already
 *   has their own personal role created and assigned (see {@link
 *   #ensurePersonalRoleForNewlyCreatedUser}, ETP-4830 human-directed requirement: "create user
 *   -&gt; assign personal role -&gt; invite", so no other role can ever land on the user first),
 *   THEN sends a company invitation via {@link
 *   CompanyInvitationService#createInvitationForNewlyCreatedUser}, the same
 *   invitation/token/email mechanism ETP-4894 built for company administrators inviting an
 *   existing user (dedup of an already-open invitation and throttling included). Template-role
 *   composition on TOP of that empty personal role happens independently, any time after
 *   creation, via {@code AssignTemplateRolesControl}'s own save (see {@link
 *   com.etendoerp.go.roles.UserRoleCompositionService#assignTemplateRoles(String, List)}) — this
 *   is why the invitation intentionally skips the "invited user already has an active role" check
 *   {@link CompanyInvitationService#createInvitation} otherwise enforces (an empty personal role
 *   with no templates is not a meaningful "active role" from that check's perspective). There is
 *   deliberately no eager {@code etgo_account} row created on this
 *   path any more (ETP-4830 superseded ETP-4829's pending/active bookkeeping): the invitation's
 *   {@code register-and-accept} flow is now the sole place an {@code etgo_account} gets created
 *   for an admin-created user, lazily, once the invitee actually accepts. Same best-effort
 *   contract as role sync: never fails the parent {@code AD_User} creation. The resulting {@code
 *   invitationStatus} (see {@link #attachInvitationStatus}) is surfaced back on every {@code
 *   user} GET so the frontend can render a "pending invite" badge — AND (ETP-4830 pending-invite-
 *   pill fix) attached directly onto the {@code POST} create response itself, right after the
 *   invitation is created (see {@link #inviteNewlyCreatedUser}), so the pill renders on the
 *   detail header's FIRST paint instead of only after a subsequent GET (leaving and re-entering
 *   the record). <b>Duplicate-email guard (ETP-5264):</b> because the create form never shows
 *   {@code username}, a client submitting an email that already belongs to one of its users used
 *   to fail on the DB's {@code username} unique-constraint violation instead — the derived
 *   username collides too, but the resulting error names the technical {@code username} column,
 *   which the user never typed and the frontend ({@code backendErrors.js}) has no mapping for, so
 *   it surfaced raw and misleadingly. {@link #rejectDuplicateEmail} runs BEFORE the {@code
 *   username} derivation above and returns a clear 400 instead, so the confusing DB message is
 *   never reached.</li>
 *
 *   <li><b>Write-path guards on {@code PUT}/{@code PATCH} (ETP-4830 QA rejection cycle 1):</b>
 *   {@link #handle(NeoContext)} rejects two dangerous updates with a 400 BEFORE the default CRUD
 *   update ever runs:
 *   <ul>
 *     <li><i>Email immutability.</i> {@code decisions.json}'s {@code readOnlyLogicJs:
 *     "!!record.id"} on {@code email} is client-side only — {@code push-to-neo.js}'s {@code
 *     mapVisibility()} has no notion of a dynamic display-logic expression, so {@code
 *     NeoFieldFilter}'s {@code writableFields} still lets a direct PATCH change {@code email}
 *     after creation, desyncing it from {@code username}/the linked {@code etgo_account} (both
 *     keyed off the original email — see concern (3) above and {@code EtendoGoJwtDalHelper}/
 *     {@code EtendoGoJwtSupport}). Rejected unless the incoming value is byte-for-byte identical
 *     to the persisted one, so a client re-submitting its own unchanged form value is a no-op,
 *     not an error. Scoped narrowly to {@code email} on this window's write path — NOT a generic
 *     {@code readOnlyLogicJs} enforcement mechanism (the same gap exists elsewhere, e.g. {@code
 *     transactionDocument}, and is tracked separately).</li>
 *     <li><i>Self/last-admin lockout.</i> {@code NeoFieldFilter#forEntity} always adds {@code
 *     active} to {@code writable} "so toggles persist", with no guard of its own. Only evaluated
 *     when the request explicitly sets {@code active=false} ({@link
 *     AbstractSmartDeactivationHandler#isExplicitlyDeactivating}, the same helper other {@code
 *     active=false} guards in this module use — widened to {@code public static} for this reuse
 *     since this handler cannot itself extend that single-purpose base class). Rejects when the
 *     target record is the currently-authenticated user's own record (resolved from {@code
 *     context.getObContext().getUser()}, the same "who is making this request" pattern used
 *     elsewhere in this package), and separately rejects when the target user is the last
 *     remaining active {@code AD_User} holding an active client-admin {@code AD_Role} ({@code
 *     Role.isClientAdmin() == true}) for that client — see {@link #isLastActiveClientAdmin}.
 *     </li>
 *   </ul>
 *   Both guards fail CLOSED (surface a 500) on an unexpected error rather than silently falling
 *   through to the default CRUD update — same reasoning {@code AbstractSmartDeactivationHandler}'s
 *   own javadoc gives for its guard.</li>
 *
 *   <li><b>Owner protection (ETP-4830, "owner" concept):</b> {@code AD_User.EM_ETGO_Is_Owner}
 *   (see {@link OwnerSupport}) flags the ONE user who completed self-service tenant
 *   registration for a client. {@link #rejectNonOwnerEditingOwner} runs FIRST in {@link
 *   #validateUpdate(NeoContext)}, before the email-immutability and self/last-admin-lockout
 *   guards above: when the TARGET record is flagged as owner and the requester (resolved the
 *   same way the lockout guard resolves "who is making this request",
 *   {@code context.getObContext().getUser()}) is NOT that same user, the ENTIRE PUT/PATCH is
 *   rejected with a 400 — blanket, regardless of which fields the request touches, matching the
 *   human-confirmed "owner-lock scope: everything" decision. When the requester IS the owner
 *   editing their own record, this guard is a no-op and the request falls through to the other
 *   guards exactly as any other self-edit would (the self-lockout guard above already covers
 *   "the owner can't deactivate themselves" generically — no separate case needed here). A
 *   target that is not flagged as owner (every pre-existing user until a separate,
 *   human-reviewed backfill data-fix runs) never triggers this guard at all.</li>
 *
 *   <li><b>Delete-path guards on {@code DELETE} (ETP-5195 Bug 3):</b> {@link
 *   #handle(NeoContext)} previously had no {@code DELETE} case at all, so a delete request fell
 *   straight through to the default CRUD delete with none of this window's other guards applied
 *   — an administrator could delete their own {@code AD_User} record outright. {@link
 *   #rejectDangerousDelete} now rejects three cases before the default CRUD delete ever runs:
 *   a self-delete (same {@code actingUserId.equals(userId)} pattern as the deactivation guard
 *   above), a delete of the record flagged as the client's owner via {@link OwnerSupport#isOwner}
 *   (unconditional — unlike the update guard's owner protection, the owner is NOT exempt from
 *   deleting their own record here), and a delete of the last remaining active client-admin
 *   (reusing {@link #isLastActiveClientAdmin} as-is). Same fail-CLOSED contract as the other
 *   guards in this class.</li>
 * </ol>
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2.
 */
@Named("user")
public class UserRoleAssignmentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(UserRoleAssignmentHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_DELETE = "DELETE";

  /** {@code AD_User_ID} of the System-client "Admin" and "System" bootstrap accounts. */
  private static final Set<String> HIDDEN_BOOTSTRAP_USER_IDS = Set.of("0", "100");

  /**
   * ETP-5188 — query params for the "Rol" advanced filter on the {@code user} list. See {@link
   * #applyRoleFilter(NeoContext)}'s javadoc for the full contract.
   */
  private static final String QUERY_PARAM_ROLE_IDS = "RoleIds";
  private static final String QUERY_PARAM_NO_ROLE = "NoRole";
  /**
   * ETP-5188 — negates the ENTIRE {@code RoleIds}/{@code NoRole} predicate {@link
   * #applyRoleFilter(NeoContext)} builds, wrapping it in an HQL {@code not (...)}. Backs the
   * "No es" ({@code RoleIds} + negate) and "No está vacío" ({@code NoRole} + negate) advanced-
   * filter operators — see {@link #applyRoleFilter(NeoContext)}'s javadoc for the full contract.
   */
  private static final String QUERY_PARAM_ROLE_FILTER_NEGATE = "RoleFilterNegate";
  private static final String TRUE_STRING = "true";

  /**
   * ETP-5188 — every {@code AD_Role_ID} this handler inlines into an HQL {@code _neoWhere}
   * predicate (see {@link #applyRoleFilter(NeoContext)}) MUST match this shape before being
   * concatenated into the predicate string: {@link NeoCrudHelper#NEO_WHERE_PARAM} has no
   * bind-parameter mechanism (confirmed by reading {@code NeoCrudHelper#buildWhereClause} — the
   * predicate is spliced into the HQL text as-is), so a literal id is the only way to express
   * this filter, and this regex is the substitute for parameterization. Etendo AD ids are 32
   * hex chars (see {@code CLAUDE.md}'s "Generating new Etendo UUIDs" section); case-insensitive
   * since the frontend echoes back whatever case the DB already stores the id in.
   */
  private static final Pattern ROLE_ID_PATTERN = Pattern.compile("^[A-Fa-f0-9]{32}$");

  private static final String FIELD_TOTAL_ROWS = "totalRows";
  private static final String FIELD_ID = "id";
  private static final String FIELD_USERNAME = "username";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_INVITATION_STATUS = "invitationStatus";
  private static final String FIELD_IS_OWNER = "isOwner";
  /** ETP-5277: fields patched onto the create response by {@link #patchUserDefaultsOntoRow}. */
  private static final String FIELD_DEFAULT_ROLE = "defaultRole";
  private static final String FIELD_DEFAULT_CLIENT = "defaultClient";
  private static final String FIELD_DEFAULT_ORGANIZATION = "defaultOrganization";
  private static final String FIELD_DEFAULT_WAREHOUSE = "defaultWarehouse";
  /** Keys of the {@code JSONObject} returned by {@code CompanyInvitationService} (ETP-4830). */
  private static final String FIELD_ERROR = "error";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_INVITATION = "invitation";
  private static final String FIELD_STATUS = "status";

  /**
   * Pre-hook dispatch: on a {@code user} {@code POST} (create), derives a unique {@code
   * username}; on a {@code user} {@code PUT}/{@code PATCH} (update), guards against the
   * email-immutability and self/last-admin-lockout writes described in the class javadoc's
   * ETP-4830 write-path-guards concern; on a {@code user} {@code DELETE}, guards against the
   * self/last-admin/owner deletes described in the class javadoc's ETP-5195 delete-guards
   * concern; on a {@code user} list {@code GET}, excludes contact-only rows (see {@link
   * #excludeContactOnlyUsers}, ETP-5019). No-op for every other method/endpoint.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (METHOD_POST.equalsIgnoreCase(method)) {
      return handleCreate(context);
    }
    if (METHOD_PUT.equalsIgnoreCase(method) || METHOD_PATCH.equalsIgnoreCase(method)) {
      return validateUpdate(context);
    }
    if (METHOD_DELETE.equalsIgnoreCase(method)) {
      return rejectDangerousDelete(context);
    }
    if (METHOD_GET.equalsIgnoreCase(method) && context.getRecordId() == null) {
      excludeContactOnlyUsers(context);
      applyRoleFilter(context);
    }
    return null;
  }

  /**
   * ETP-5019 — a {@code Business Partner Contact} created from the BP window's "Contacts" tab
   * auto-creates its own {@code AD_User} row purely to carry contact info (e.g. "Default
   * Customer Contact"), never meant to log in or hold roles. These rows kept leaking into the
   * Users window's list ("Default Customer Contact" rows for every client, plus assorted named
   * BP-contact rows) even though they are not real application users.
   *
   * <p>Structural signal, confirmed by direct DB query rather than a name match on "Contact":
   * EVERY genuinely login-capable {@code AD_User} — admin-created via this window's own {@link
   * #handleCreate}, or provisioned by {@code InitialSetupUtility#insertUser} at client onboarding
   * — always gets a non-blank {@code username} (this handler itself derives one from {@code
   * email} on every create, see the class javadoc's concern (3)). A BP-contact-only row never
   * has one. This holds even for a real user with zero roles assigned yet (confirmed: 42 of 185
   * real users in this sandbox have zero {@code AD_User_Roles} rows but all 185 have a
   * non-blank {@code username}) — so filtering on role count, unlike {@code username}, would
   * wrongly hide legitimate not-yet-assigned users.
   *
   * <p>Injects the exclusion as a {@code _neoWhere} HQL predicate (see {@link
   * NeoCrudHelper#NEO_WHERE_PARAM}) BEFORE the default CRUD list fetch runs, rather than
   * post-filtering the response rows the way {@link #hideBootstrapUsers} does — the bootstrap
   * list is 2 fixed IDs system-wide, but a client can have dozens of BP-contact rows, and
   * post-filtering after the DB-level {@code LIMIT}/{@code OFFSET} already applied would corrupt
   * pagination (a page could come back with fewer rows than requested, or empty, even though
   * more real users exist beyond it). Filtering in the HQL keeps {@code totalRows} and paging
   * correct for free.
   */
  private void excludeContactOnlyUsers(NeoContext context) {
    Map<String, String> queryParams = context.getQueryParams();
    if (queryParams == null) {
      return;
    }
    String predicate = "e.username is not null and e.username <> ''";
    String existing = queryParams.get(NeoCrudHelper.NEO_WHERE_PARAM);
    queryParams.put(NeoCrudHelper.NEO_WHERE_PARAM,
        StringUtils.isBlank(existing) ? predicate : "(" + existing + ") and (" + predicate + ")");
  }

  /**
   * ETP-5188 — server-side half of the Users window's "Rol" advanced filter. The frontend's
   * generic {@code criteria=} query-param mechanism cannot express this (confirmed empirically,
   * 500 error): it builds a naive dotted HQL property path through the {@code
   * aDUserRolesList} collection, which is invalid HQL for a one-to-many association. So the
   * frontend instead sends two DEDICATED query params on this same {@code user} list {@code GET}:
   *
   * <ul>
   *   <li>{@code RoleIds=<id1>,<id2>,...} — comma-separated {@code AD_Role_ID}s of the fixed
   *   system role templates ({@link com.etendoerp.go.roles.SystemRoleTemplates#byName()}) and/or
   *   the caller's own client's admin ("Administrador") role id.</li>
   *   <li>{@code NoRole=true} — users with no composed role at all (and not the admin).</li>
   * </ul>
   *
   * <p>Since ETP-4906, a user's actual access is never a direct {@code Default_Ad_Role_ID} match
   * against a template — it is expressed via that user's PERSONAL role (see {@link
   * com.etendoerp.go.roles.UserRoleCompositionService}'s own class javadoc), which COMPOSES 1+
   * templates through an active {@code AD_Role_Inheritance} row. The one exception is the
   * client-admin "Admin" role, which {@code UserRoleCompositionService} never lets a personal
   * role compose — it is always a DIRECT {@code Default_Ad_Role_ID} assignment (see that class's
   * "Never touches the Admin role" javadoc section). {@link #buildComposedOrDirectPredicate}
   * covers both shapes with a single OR: a direct {@code Default_Ad_Role_ID} match (the only way
   * the admin id can ever match, but harmless to check for a template id too — no personal role's
   * id is ever equal to a template's own id) OR an active inheritance from one of the requested
   * template ids.
   *
   * <p>Injected as an HQL {@code _neoWhere} predicate (see {@link
   * NeoCrudHelper#NEO_WHERE_PARAM}), the exact same mechanism {@link
   * #excludeContactOnlyUsers(NeoContext)} already uses on this same list {@code GET} — combined
   * with that method's own predicate (and any other existing one) via {@code and}, while
   * {@code RoleIds} and {@code NoRole} are combined with {@code or} between themselves: they are
   * two chips of the SAME multi-select filter ("match any of the selected options"), not two
   * independent filters.
   *
   * <p><b>No bind-parameter mechanism exists for {@code _neoWhere}</b> (confirmed by reading
   * {@link NeoCrudHelper#buildWhereClause} in full — the predicate string is spliced verbatim
   * into the HQL text). Every {@code AD_Role_ID} inlined into the predicate is therefore first
   * validated against {@link #ROLE_ID_PATTERN} in {@link #sanitizeRoleIds(String)} — anything
   * that doesn't match a 32-char hex id is dropped (logged, not rejected with an error, so one
   * malformed entry doesn't 500 the whole list) rather than ever reaching the HQL string
   * unescaped. This is the same literal-string-only precedent {@link #excludeContactOnlyUsers}
   * already sets for this file (its predicate has no dynamic values at all, so it never needed
   * this sanitization step), used here in the substitute-for-parameterization sense CLAUDE.md's
   * NeoHandler guidance calls for.
   *
   * <p><b>ETP-5188 negation ({@code RoleFilterNegate=true}).</b> The frontend's "Rol" filter
   * offers four operators — "Es", "No es", "Está vacío", "No está vacío" — but only needs the two
   * primitives above ({@code RoleIds}, {@code NoRole}) plus this one boolean flag to express all
   * four; no new query semantics are needed:
   * <ul>
   *   <li>"Es" — {@code RoleIds} alone, no negate (unchanged, pre-ETP-5188 behavior).</li>
   *   <li>"No es" — {@code RoleIds} + {@code RoleFilterNegate=true}: NOT the same predicate "Es"
   *   builds — "this user has none of the selected roles".</li>
   *   <li>"Está vacío" — {@code NoRole=true} alone, no negate (unchanged "Sin rol" behavior).</li>
   *   <li>"No está vacío" — {@code NoRole=true} + {@code RoleFilterNegate=true}: NOT "Sin rol" —
   *   "this user has some role, whichever it is".</li>
   * </ul>
   * When present, {@code RoleFilterNegate} wraps the ENTIRE {@code or}-joined combination of
   * whichever branches ({@code RoleIds}/{@code NoRole}) are present in one outer HQL
   * {@code not (...)}, applied AFTER that combination is built and BEFORE it is merged into any
   * existing {@code _neoWhere} predicate — so it composes with an already-present filter exactly
   * like the un-negated predicate always did. Parsed with the same strict {@code
   * TRUE_STRING.equalsIgnoreCase(StringUtils.trimToNull(...))} convention {@code NoRole} already
   * uses (see {@link #isRoleFilterNegated(Map)}): anything other than a case-insensitive
   * {@code "true"} is treated as absent/false, so this is a pure additive change — behavior is
   * byte-for-byte unchanged whenever {@code RoleFilterNegate} is absent.
   *
   * <p>A no-op when neither {@code RoleIds} nor {@code NoRole} is present, regardless of {@code
   * RoleFilterNegate} — negating an empty/no-op filter would otherwise wrongly match every user.
   */
  private void applyRoleFilter(NeoContext context) {
    Map<String, String> queryParams = context.getQueryParams();
    if (queryParams == null) {
      return;
    }
    Set<String> roleIds = sanitizeRoleIds(queryParams.get(QUERY_PARAM_ROLE_IDS));
    boolean noRole = TRUE_STRING.equalsIgnoreCase(
        StringUtils.trimToNull(queryParams.get(QUERY_PARAM_NO_ROLE)));
    if (roleIds.isEmpty() && !noRole) {
      return;
    }

    List<String> predicates = new ArrayList<>();
    if (!roleIds.isEmpty()) {
      predicates.add(buildComposedOrDirectPredicate(roleIds));
    }
    if (noRole) {
      predicates.add(buildNoRolePredicate(resolveClientAdminRoleId(context.getObContext())));
    }

    String predicate = "(" + String.join(") or (", predicates) + ")";
    if (isRoleFilterNegated(queryParams)) {
      predicate = "not (" + predicate + ")";
    }
    String existing = queryParams.get(NeoCrudHelper.NEO_WHERE_PARAM);
    queryParams.put(NeoCrudHelper.NEO_WHERE_PARAM,
        StringUtils.isBlank(existing) ? predicate : "(" + existing + ") and (" + predicate + ")");
  }

  /**
   * ETP-5188 — whether the current request asked to negate the whole {@code RoleIds}/{@code
   * NoRole} predicate via {@link #QUERY_PARAM_ROLE_FILTER_NEGATE}. Same strict, case-insensitive
   * {@code "true"}-only parsing convention {@code NoRole} already uses one line above in {@link
   * #applyRoleFilter(NeoContext)} — anything else (missing, blank, {@code "1"}, {@code "yes"},
   * mixed case aside from {@code true}/{@code TRUE}/{@code True}-style variants, ...) is treated
   * as absent/false. Carries no id/value of its own, so it needs no {@link #ROLE_ID_PATTERN}-style
   * sanitization before being inlined — it never reaches the HQL string itself, only decides
   * whether to prepend the literal {@code "not "} wrapper.
   */
  private boolean isRoleFilterNegated(Map<String, String> queryParams) {
    return TRUE_STRING.equalsIgnoreCase(
        StringUtils.trimToNull(queryParams.get(QUERY_PARAM_ROLE_FILTER_NEGATE)));
  }

  /**
   * Splits {@code rawRoleIds} on {@code ,}, trims each entry, and keeps only the ones matching
   * {@link #ROLE_ID_PATTERN} — see {@link #applyRoleFilter(NeoContext)}'s javadoc for why this
   * validation stands in for a bind-parameter mechanism {@code _neoWhere} does not have. A
   * malformed entry is logged at WARN and silently dropped rather than failing the whole request.
   *
   * @param rawRoleIds the raw {@code RoleIds} query param value, possibly {@code null}/blank
   * @return the validated, deduplicated (insertion-order) set of role ids; empty if {@code
   *     rawRoleIds} is blank or every entry was malformed
   */
  private Set<String> sanitizeRoleIds(String rawRoleIds) {
    Set<String> result = new LinkedHashSet<>();
    if (StringUtils.isBlank(rawRoleIds)) {
      return result;
    }
    for (String candidate : rawRoleIds.split(",")) {
      String trimmed = StringUtils.trimToNull(candidate);
      if (trimmed == null) {
        continue;
      }
      if (ROLE_ID_PATTERN.matcher(trimmed).matches()) {
        result.add(trimmed);
      } else {
        log.warn("UserRoleAssignmentHandler.applyRoleFilter: rejected malformed RoleIds entry "
            + "'{}' — not a 32-char hex AD_Role_ID", trimmed);
      }
    }
    return result;
  }

  /**
   * Builds the OR of the two ways a user can match one of {@code roleIds} — see {@link
   * #applyRoleFilter(NeoContext)}'s javadoc for why both branches are needed. {@code roleIds} is
   * already sanitized by {@link #sanitizeRoleIds(String)} (32-char hex only), so inlining it
   * directly into the HQL literal list is safe.
   *
   * @param roleIds a non-empty, pre-sanitized set of {@code AD_Role_ID}s
   * @return an HQL boolean expression, not yet wrapped in an outer paren by the caller
   */
  private String buildComposedOrDirectPredicate(Set<String> roleIds) {
    String literalIdList = toHqlLiteralList(roleIds);
    return "(e.defaultRole.id in (" + literalIdList + ")) or "
        + "(exists (select 1 from ADRoleInheritance ri where ri.role = e.defaultRole and "
        + "ri.active = true and ri.inheritFrom.id in (" + literalIdList + ")))";
  }

  /**
   * Builds the "Sin rol" predicate: no active composed template inheritance from the user's
   * personal role, AND the user's {@code Default_Ad_Role_ID} is not the client's admin role
   * (admin is a real, direct role assignment — never "no role" — see {@link
   * #applyRoleFilter(NeoContext)}'s javadoc). The {@code exists} subquery correlates on {@code
   * ri.role = e.defaultRole} — an entity/FK comparison, not a dotted property-value read — so a
   * {@code null} {@code e.defaultRole} simply makes the correlation (and therefore the {@code
   * exists}) false, with no join required; the same safe pattern {@link
   * #buildComposedOrDirectPredicate} already relies on.
   *
   * @param adminRoleId the caller's client's admin role id, or {@code null} if it could not be
   *     resolved (e.g. no {@code OBContext}/client on this request) — the admin-exclusion clause
   *     is simply omitted in that case, never inlined as a literal {@code null}
   * @return an HQL boolean expression, not yet wrapped in an outer paren by the caller
   */
  private String buildNoRolePredicate(String adminRoleId) {
    StringBuilder predicate = new StringBuilder(
        "not exists (select 1 from ADRoleInheritance ri where ri.role = e.defaultRole and "
            + "ri.active = true)");
    if (adminRoleId != null) {
      predicate.append(" and (e.defaultRole is null or e.defaultRole.id <> '")
          .append(adminRoleId).append("')");
    }
    return predicate.toString();
  }

  /**
   * Resolves the caller's own client's active client-admin ({@code IsClientAdmin = 'Y'}) role
   * id, following the exact same {@code OBCriteria} shape {@code SFRolesOverview#resolveTenantRoles}
   * already uses to find a tenant's admin role — see that method for the precedent. Wrapped in
   * {@code OBContext.setAdminMode(true)} like every other DB read in this class that isn't
   * scoped to the acting user's own default client/org (e.g. {@link #attachOwnerFlag}), since
   * this runs during a plain list {@code GET} under the acting user's own (non-admin) context.
   *
   * @param obContext the request's resolved {@code OBContext}, or {@code null}
   * @return the client-admin role id, or {@code null} if {@code obContext}/its current client is
   *     unavailable, or the client genuinely has no active admin role
   */
  private String resolveClientAdminRoleId(OBContext obContext) {
    String clientId = obContext != null && obContext.getCurrentClient() != null
        ? obContext.getCurrentClient().getId() : null;
    if (clientId == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
      criteria.setFilterOnReadableClients(false);
      criteria.setFilterOnReadableOrganization(false);
      criteria.add(Restrictions.eq(Role.PROPERTY_CLIENT + ".id", clientId));
      criteria.add(Restrictions.eq(Role.PROPERTY_ACTIVE, true));
      criteria.add(Restrictions.eq(Role.PROPERTY_CLIENTADMIN, true));
      criteria.setMaxResults(1);
      List<Role> results = criteria.list();
      return results.isEmpty() ? null : results.get(0).getId();
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.resolveClientAdminRoleId error for client {}: {}",
          clientId, e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Joins {@code ids} into a comma-separated, single-quoted HQL literal list — e.g. {@code
   * 'ID1','ID2'} — for splicing into an {@code in (...)} clause. Callers are responsible for
   * ensuring every id is already safe to inline (see {@link #sanitizeRoleIds(String)}).
   */
  private static String toHqlLiteralList(Set<String> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }

  /**
   * Derives a unique {@code username} from {@code email} and the current client, and rejects a
   * blank/missing email with 400. Before that derivation runs, also rejects a duplicate {@code
   * email} within the same client with a clear 400 (see {@link #rejectDuplicateEmail}, ETP-5264)
   * — otherwise the derived {@code username} would collide too, and the resulting DB
   * unique-constraint error would confusingly name a field this create form never shows.
   *
   * <p>No longer validates or reads an admin-typed {@code password} (ETP-4830 removed that
   * temporary bypass — see the class javadoc's concern (3)): invite-email is now the only way
   * to activate an admin-created user's {@code etgo_account}, so a {@code password} field on
   * this create form, if the frontend still sends one, is simply ignored here (it still reaches
   * {@code AD_User.Password}, Openbravo's own classic-backend login, unrelated to {@code
   * etgo_account}).
   */
  private NeoResponse handleCreate(NeoContext context) {
    JSONObject requestBody = context.getRequestBody();
    if (requestBody == null) {
      return null;
    }
    String email = StringUtils.trimToNull(requestBody.optString(FIELD_EMAIL, null));
    if (email == null) {
      return NeoResponse.error(400, "Field 'email' is required to create a user");
    }
    String normalizedEmail = email.toLowerCase();
    OBContext obContext = OBContext.getOBContext();
    Client client = obContext != null ? obContext.getCurrentClient() : null;
    NeoResponse duplicateEmailGuard = rejectDuplicateEmail(normalizedEmail, client);
    if (duplicateEmailGuard != null) {
      return duplicateEmailGuard;
    }
    try {
      String clientName = client != null ? client.getName() : null;
      requestBody.put(FIELD_USERNAME,
          clientName == null
              ? normalizedEmail
              : EtendoGoJwtSupport.buildClientUsername(normalizedEmail, clientName));
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.handle: failed to derive username from email: {}",
          e.getMessage(), e);
    }
    return null;
  }

  /**
   * Rejects a {@code user} create whose {@code email} (already lowercased by the caller) is
   * already used by another {@code AD_User} of the same {@code client} — see the class javadoc's
   * ETP-5264 duplicate-email-guard concern for why this proactive check exists (it stands in for
   * the DB's own {@code username} unique-constraint, whose violation would otherwise surface a
   * raw message naming a field this form never shows). Deliberately does NOT filter by {@code
   * active} — the DB constraint this check stands in for doesn't care about the {@code active}
   * flag either, so filtering here would create a gap where this pre-check passes but the DB
   * insert still fails with the confusing raw message. A no-op ({@code null}) when {@code client}
   * is {@code null} — {@link #handleCreate} still falls through to its own best-effort username
   * derivation in that case, unchanged from before ETP-5264.
   */
  private NeoResponse rejectDuplicateEmail(String normalizedEmail, Client client) {
    if (client == null) {
      return null;
    }
    OBCriteria<User> criteria = OBDal.getInstance().createCriteria(User.class);
    criteria.add(Restrictions.eq(User.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.ilike(User.PROPERTY_EMAIL, normalizedEmail, MatchMode.EXACT));
    criteria.setMaxResults(1);
    if (!criteria.list().isEmpty()) {
      return NeoResponse.error(400, "A user with this email address already exists");
    }
    return null;
  }

  /**
   * Pre-hook guard for a {@code user} {@code PUT}/{@code PATCH}: rejects an {@code email} change
   * on an existing record, then rejects a self/last-admin-lockout {@code active=false} write.
   * See the class javadoc's ETP-4830 write-path-guards concern for the full rationale. Runs
   * BEFORE the default CRUD update (this is a {@code handle()} pre-hook, not an {@code
   * afterHandle()} side effect), so a rejection here never lets the write reach the DB.
   *
   * @return a 400/500 error response to short-circuit the request, or {@code null} to let the
   *     default CRUD update proceed
   */
  private NeoResponse validateUpdate(NeoContext context) {
    JSONObject requestBody = context.getRequestBody();
    String userId = context.getRecordId();
    if (requestBody == null || userId == null) {
      return null;
    }
    // Owner protection runs FIRST and short-circuits everything else — a rejection here means
    // the request never reaches the email-immutability/self-lockout guards below at all, per the
    // "owner-lock scope: everything" decision (see class javadoc). A self-edit by the owner (or
    // any request against a non-owner record) simply falls through to those guards unchanged.
    NeoResponse ownerGuard = rejectNonOwnerEditingOwner(userId, context.getObContext());
    if (ownerGuard != null) {
      return ownerGuard;
    }
    NeoResponse emailGuard = rejectEmailChange(requestBody, userId);
    if (emailGuard != null) {
      return emailGuard;
    }
    return rejectDangerousDeactivation(requestBody, userId, context.getObContext());
  }

  /**
   * Rejects a {@code PUT}/{@code PATCH} against a record flagged as its client's owner ({@link
   * OwnerSupport#isOwner}) when the requester is anyone OTHER than that same owner. See the class
   * javadoc's ETP-4830 owner-protection concern for the full rationale. A no-op — returns {@code
   * null}, letting the request fall through to the other guards — when {@code userId} is not
   * flagged as owner at all, or when the requester IS the owner editing their own record.
   */
  private NeoResponse rejectNonOwnerEditingOwner(String userId, OBContext obContext) {
    boolean targetIsOwner;
    try {
      OBContext.setAdminMode(true);
      try {
        targetIsOwner = OwnerSupport.isOwner(userId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserRoleAssignmentHandler.rejectNonOwnerEditingOwner error for user {}: {}",
          userId, e.getMessage(), e);
      // Fail CLOSED, same reasoning as the other write-path guards in this class.
      return NeoResponse.error(500, "Error validating owner protection: " + e.getMessage());
    }
    if (!targetIsOwner) {
      return null;
    }
    String actingUserId = obContext != null && obContext.getUser() != null
        ? obContext.getUser().getId() : null;
    if (actingUserId != null && actingUserId.equals(userId)) {
      return null;
    }
    return NeoResponse.error(400,
        "This user is the tenant owner — only the owner can modify this account");
  }

  /**
   * Rejects a {@code PUT}/{@code PATCH} that changes {@code email} on an existing {@code user}
   * record. A no-op when the request doesn't touch {@code email} at all, or when the incoming
   * value is byte-for-byte identical (after trimming) to the currently-persisted one — a naive
   * client re-submitting its own unchanged form value must not 400.
   */
  private NeoResponse rejectEmailChange(JSONObject requestBody, String userId) {
    if (!requestBody.has(FIELD_EMAIL)) {
      return null;
    }
    String incomingEmail = StringUtils.trimToNull(requestBody.optString(FIELD_EMAIL, null));
    try {
      OBContext.setAdminMode(true);
      try {
        User user = OBDal.getInstance().get(User.class, userId);
        if (user == null) {
          // Record doesn't exist (yet) — let the default CRUD update produce its own error.
          return null;
        }
        String currentEmail = StringUtils.trimToNull(user.getEmail());
        if (!Objects.equals(incomingEmail, currentEmail)) {
          return NeoResponse.error(400,
              "Field 'email' cannot be changed after the user has been created");
        }
        return null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserRoleAssignmentHandler.rejectEmailChange error for user {}: {}", userId,
          e.getMessage(), e);
      // Fail CLOSED: an error here must not silently let an email change through unverified.
      return NeoResponse.error(500, "Error validating email immutability: " + e.getMessage());
    }
  }

  /**
   * Rejects a {@code PUT}/{@code PATCH} that explicitly sets {@code active=false} on the
   * currently-authenticated user's own record, or on the last remaining active user holding an
   * active client-admin role for that client. A no-op for any request that doesn't explicitly
   * deactivate (see {@link AbstractSmartDeactivationHandler#isExplicitlyDeactivating}).
   */
  private NeoResponse rejectDangerousDeactivation(JSONObject requestBody, String userId,
      OBContext obContext) {
    if (!AbstractSmartDeactivationHandler.isExplicitlyDeactivating(requestBody)) {
      return null;
    }
    String actingUserId = obContext != null && obContext.getUser() != null
        ? obContext.getUser().getId() : null;
    if (actingUserId != null && actingUserId.equals(userId)) {
      return NeoResponse.error(400, "You cannot deactivate your own user account");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        User targetUser = OBDal.getInstance().get(User.class, userId);
        if (targetUser == null) {
          return null;
        }
        if (isLastActiveClientAdmin(targetUser)) {
          return NeoResponse.error(400,
              "Cannot deactivate the last active administrator for this client");
        }
        return null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserRoleAssignmentHandler.rejectDangerousDeactivation error for user {}: {}",
          userId, e.getMessage(), e);
      // Fail CLOSED: an error here must not silently let a lockout-risking deactivation through.
      return NeoResponse.error(500, "Error validating deactivation: " + e.getMessage());
    }
  }

  /**
   * Pre-hook guard for a {@code user} {@code DELETE}: rejects a self-delete, a delete of the
   * client's owner record (by anyone, including the owner themself), and a delete of the last
   * remaining active client-admin. Runs BEFORE the default CRUD delete (this is a {@code
   * handle()} pre-hook), so a rejection here never lets the record reach the DB delete at all.
   *
   * <p><b>ETP-5195 Bug 3.</b> {@link #handle(NeoContext)} previously had no {@code DELETE} case
   * whatsoever, so a delete request fell straight through to the default CRUD delete with NONE
   * of this window's existing write-path guards applied — an administrator could delete their
   * own {@code AD_User} record outright. This mirrors the {@code
   * actingUserId.equals(userId)} self-check from {@link #rejectDangerousDeactivation}, the
   * {@link OwnerSupport#isOwner} check from {@link #rejectNonOwnerEditingOwner} (widened here:
   * unlike the update guard, which lets the owner edit their OWN record, a delete of the owner's
   * record is blocked unconditionally — the owner is not exempt from deleting themself), and
   * reuses {@link #isLastActiveClientAdmin} as-is. Same fail-CLOSED reasoning as the other guards
   * in this class: an unexpected error surfaces a 500 rather than silently letting the delete
   * proceed.
   *
   * @return a 400/500 error response to short-circuit the request, or {@code null} to let the
   *     default CRUD delete proceed
   */
  private NeoResponse rejectDangerousDelete(NeoContext context) {
    // ETP-5195, R3: resolve the EFFECTIVE target id the same way NeoCrudHandler#buildDalParams
    // now does — the path id wins when present, otherwise fall back to the query "id" — so this
    // guard always evaluates the exact same record the CRUD delete will actually touch. Before
    // this fix, a request like DELETE /sws/neo/user/user/<ordinary-id>?id=<protected-id> let a
    // query "id" silently override the path id at the CRUD layer while this guard kept looking
    // only at the path id, so guard and delete disagreed on the target; a path-less DELETE
    // .../user?id=<protected-id> bypassed the guard outright, since it bailed out immediately
    // below with no query fallback at all.
    String userId = context.getRecordId();
    if (userId == null && context.getQueryParams() != null) {
      userId = context.getQueryParams().get(FIELD_ID);
    }
    if (userId == null) {
      return null;
    }
    OBContext obContext = context.getObContext();
    String actingUserId = obContext != null && obContext.getUser() != null
        ? obContext.getUser().getId() : null;
    if (actingUserId != null && actingUserId.equals(userId)) {
      return NeoResponse.error(400, "You cannot delete your own user account");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        if (OwnerSupport.isOwner(userId)) {
          return NeoResponse.error(400, "This user is the tenant owner and cannot be deleted");
        }
        User targetUser = OBDal.getInstance().get(User.class, userId);
        if (targetUser == null) {
          return null;
        }
        if (isLastActiveClientAdmin(targetUser)) {
          return NeoResponse.error(400,
              "Cannot delete the last active administrator for this client");
        }
        return null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserRoleAssignmentHandler.rejectDangerousDelete error for user {}: {}", userId,
          e.getMessage(), e);
      // Fail CLOSED: an error here must not silently let a dangerous delete through unverified.
      return NeoResponse.error(500, "Error validating delete: " + e.getMessage());
    }
  }

  /**
   * Whether {@code targetUser} is the sole remaining active {@code AD_User} holding an active
   * client-admin {@code AD_Role} ({@code Role.isClientAdmin() == true}) for {@code targetUser}'s
   * client — i.e. deactivating it would leave that client with zero active admins. Counts
   * distinct users via {@code AD_User_Roles} rather than {@code AD_User.Default_Ad_Role_ID}
   * (same reasoning as {@link com.etendoerp.go.schemaforge.util.UserRoleSyncSupport}'s own
   * javadoc: real access checks read {@code AD_User_Roles}, not the UI-convenience pointer
   * field). {@code false} when {@code targetUser} doesn't currently hold an active client-admin
   * role at all — deactivating it then carries no lockout risk from this angle.
   */
  private boolean isLastActiveClientAdmin(User targetUser) {
    Client client = targetUser.getClient();
    if (client == null) {
      return false;
    }
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.createAlias(UserRoles.PROPERTY_ROLE, "role");
    criteria.createAlias(UserRoles.PROPERTY_USERCONTACT, "assignedUser");
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq("role." + Role.PROPERTY_CLIENTADMIN, true));
    criteria.add(Restrictions.eq("role." + Role.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq("assignedUser." + User.PROPERTY_ACTIVE, true));
    List<UserRoles> activeAdminAssignments = criteria.list();
    Set<String> activeAdminUserIds = new HashSet<>();
    for (UserRoles row : activeAdminAssignments) {
      activeAdminUserIds.add(row.getUserContact().getId());
    }
    return activeAdminUserIds.size() == 1 && activeAdminUserIds.contains(targetUser.getId());
  }

  /**
   * Post-hook dispatch: sends a company invitation after a {@code user} create, filters
   * bootstrap users out of a {@code user} list GET and attaches {@code invitationStatus} to
   * every surviving {@code user} GET row (list or single-record), or syncs {@code
   * AD_User_Roles} after a {@code user} update. See the class javadoc for why all these concerns
   * live in one handler.
   *
   * @return always {@code null} — every concern mutates {@code context.getPreviousResult()}'s
   *     body in place (or leaves it untouched) rather than replacing the response.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (METHOD_POST.equalsIgnoreCase(method)) {
      inviteNewlyCreatedUser(context);
      return null;
    }
    if (METHOD_GET.equalsIgnoreCase(method)) {
      if (context.getRecordId() == null) {
        hideBootstrapUsers(context);
      }
      attachInvitationStatus(context);
      attachOwnerFlag(context);
      return null;
    }
    return syncRoleAfterUpdate(context);
  }

  /**
   * Removes the "Admin"/"System" bootstrap-account rows (see class javadoc) from a {@code user}
   * list GET response, adjusting {@code totalRows} to match. Only ever invoked for a list GET
   * (the {@code afterHandle} dispatcher only calls this when {@code context.getRecordId() ==
   * null}) — a single-record fetch's {@code data} is ALSO a {@code JSONArray} of one element
   * (see {@link #inviteNewlyCreatedUser}'s javadoc, ETP-4830, confirmed against core's {@code
   * DefaultJsonDataService}), never a lone {@code JSONObject}, so {@code optJSONArray} would
   * work there too — this method simply never gets the chance to run on that path.
   */
  private void hideBootstrapUsers(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      JSONArray data = inner != null ? inner.optJSONArray(JsonConstants.RESPONSE_DATA) : null;
      if (data == null) {
        return;
      }
      JSONArray filtered = new JSONArray();
      int removed = 0;
      for (int i = 0; i < data.length(); i++) {
        JSONObject row = data.optJSONObject(i);
        String id = row != null ? row.optString(FIELD_ID, null) : null;
        if (id != null && HIDDEN_BOOTSTRAP_USER_IDS.contains(id)) {
          removed++;
          continue;
        }
        filtered.put(row);
      }
      if (removed == 0) {
        return;
      }
      inner.put(JsonConstants.RESPONSE_DATA, filtered);
      int totalRows = inner.optInt(FIELD_TOTAL_ROWS, -1);
      if (totalRows >= 0) {
        inner.put(FIELD_TOTAL_ROWS, Math.max(0, totalRows - removed));
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.hideBootstrapUsers error: {}", e.getMessage(), e);
    }
  }

  /**
   * On a successful {@code user} create, reads the newly-created record's {@code email} back
   * out of the response body and sends a company invitation via {@link
   * CompanyInvitationService#createInvitationForNewlyCreatedUser} (see concern (3) in the class
   * javadoc). {@link NeoContext#getRecordId()} is never populated for {@code POST}, so the
   * created record's fields are read from {@code previousResult.body.response.data} instead —
   * confirmed (ETP-4830, 2026-08-21) to ALWAYS be a {@code JSONArray}, never a lone {@code
   * JSONObject}: {@code DefaultJsonDataService.update()} (which {@code add()} delegates to, see
   * core's {@code modules_core/org.openbravo.service.json}) unconditionally does {@code
   * jsonResponse.put(RESPONSE_DATA, new JSONArray(jsonObjects))}, for both a single-record create
   * and a single-record GET by id (core wraps that one too, at its own {@code
   * new JSONArray(Collections.singleton(singleResult))} call in {@code fetch()}) — there is no
   * "lone object" shape anywhere in this response family. The created record is the array's one
   * and only element. This is long-standing core behavior (unchanged since at least Feb 2025,
   * long before this feature branch existed), NOT a regression from the {@code
   * mergeblock/ETP-4962}/{@code ETP-4793} rebase this branch just went through — that merge only
   * touched {@code NeoCrudHandler}'s error-envelope building (see its {@code
   * checkJsonServiceResponse}), never the success-path shape. {@code context.getObContext()} is
   * captured by the dispatcher before this method's own {@code
   * OBContext.setAdminMode(true)} runs, so it still reflects the real acting admin's
   * client/org/user — {@code setAdminMode} only lifts security checks, it never changes those.
   * Best-effort: any failure is logged and swallowed, never failing the parent {@code AD_User}
   * creation.
   *
   * <p>ETP-4830 diagnostic fix: {@link CompanyInvitationService#createInvitationForNewlyCreatedUser}
   * returns a {@code JSONObject} instead of throwing on every validation failure ({@code
   * error: true} with a {@code code}/{@code message}, see {@code CompanyInvitationService}'s
   * {@code errorResponse}) — a caller that discards the return value (as this method used to)
   * gets a completely silent no-op on any of those branches: no log line, no {@code
   * etgo_invitation} row, nothing. The result is now inspected and logged either way, so a clean
   * run and a silent-failure run are both visible without a DB query.
   *
   * <p>ETP-4830 pending-invite-pill fix: this method used to stop at logging the outcome, never
   * writing anything onto {@code data} itself. {@link #attachInvitationStatus} — the method that
   * DOES write {@code invitationStatus} onto a response row — only ran on the {@code GET} branch
   * of {@link #afterHandle}, so the frontend's initial render, built directly from this {@code
   * POST} response, legitimately had no {@code invitationStatus} field at all: the "pending
   * invite" pill correctly rendered nothing for a genuinely-absent field, and only appeared after
   * navigating away and back (a subsequent {@code GET}). Now, right after the invitation is
   * created above, {@link #attachInvitationStatusToRow} is reused to write that same field onto
   * {@code data} — the array-wrapped {@code response.data[0]} row this method already holds a
   * reference to — so the create response carries {@code invitationStatus} on its very first
   * paint. Isolated in its own try/catch (see the call site) so a lookup failure there can never
   * suppress the {@link #logInvitationResult} call below.
   */
  private void inviteNewlyCreatedUser(NeoContext context) {
    String email = null;
    String clientId = null;
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      JSONArray dataArray = inner != null ? inner.optJSONArray(JsonConstants.RESPONSE_DATA) : null;
      JSONObject data = dataArray != null && dataArray.length() > 0
          ? dataArray.optJSONObject(0) : null;
      if (data == null) {
        log.warn("UserRoleAssignmentHandler.inviteNewlyCreatedUser: no 'data[0]' entry in the "
            + "create response — cannot determine the created user's email, invitation not sent");
        return;
      }
      email = StringUtils.trimToNull(data.optString(FIELD_EMAIL, null));
      if (email == null) {
        log.warn("UserRoleAssignmentHandler.inviteNewlyCreatedUser: created user has no email "
            + "in the create response, invitation not sent");
        return;
      }
      String userId = StringUtils.trimToNull(data.optString(FIELD_ID, null));
      OBContext obContext = context.getObContext();
      clientId = obContext != null && obContext.getCurrentClient() != null
          ? obContext.getCurrentClient().getId() : null;
      OBContext.setAdminMode(true);
      JSONObject invitationResult;
      try {
        // ETP-4830 human-directed requirement: "create user -> assign personal role -> invite" —
        // MUST run before the invitation is created, so no other role ever gets a chance to land
        // on this user first. Best-effort (see the method's own javadoc): a failure here is
        // logged and swallowed, it never blocks the invitation that follows.
        ensurePersonalRoleForNewlyCreatedUser(userId, email, clientId, data);
        invitationResult = new CompanyInvitationService().createInvitationForNewlyCreatedUser(
            obContext, email.toLowerCase(), null, null);
        // ETP-4830 pending-invite-pill fix: attach invitationStatus onto THIS SAME create
        // response row right away — see this method's javadoc. Isolated in its own try/catch so
        // a lookup failure here never costs us the logInvitationResult() call below (still the
        // best-effort diagnostic for the invitation itself).
        attachInvitationStatusToRowSafely(data, clientId, email);
      } finally {
        OBContext.restorePreviousMode();
      }
      logInvitationResult(invitationResult, email, clientId);
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.inviteNewlyCreatedUser error for email={} clientId={}: {}",
          email, clientId, e.getMessage(), e);
    }
  }

  /**
   * Best-effort wrapper around {@link #attachInvitationStatusToRow} extracted out of {@link
   * #inviteNewlyCreatedUser} (was a nested {@code try} there) — a lookup failure here is logged
   * and swallowed, it must never cost the caller its {@link #logInvitationResult} diagnostic call.
   */
  private void attachInvitationStatusToRowSafely(JSONObject data, String clientId, String email) {
    try {
      attachInvitationStatusToRow(data, clientId);
    } catch (Exception attachError) {
      log.warn("UserRoleAssignmentHandler.inviteNewlyCreatedUser: failed to attach "
              + "invitationStatus to the create response for email={} clientId={}: {}",
          email, clientId, attachError.getMessage(), attachError);
    }
  }

  /**
   * ETP-4830 human-directed requirement: "the personal role should be created as soon as the
   * user is created ... so no other role gets assigned" — ensures the newly-created {@code
   * AD_User} already has its own personal role created AND assigned (both {@code
   * AD_User.Default_Ad_Role_ID} and {@code AD_User_Roles}) BEFORE {@link #inviteNewlyCreatedUser}
   * sends the create-user invitation. Called from inside that method's own {@code
   * OBContext.setAdminMode(true)} block — nesting here is safe, admin mode is stack-based
   * (push/pop), same reasoning {@link com.etendoerp.go.roles.UserRoleCompositionService}'s class
   * javadoc gives for its own nested admin-mode entries.
   *
   * <p>Uses {@link UserRoleCompositionService#createFreshPersonalRole(User)} — NOT the
   * get-or-create {@link UserRoleCompositionService#ensurePersonalRole(User)} the
   * template-composition flow relies on for an EXISTING user. Bug found while diagnosing a real
   * repro (ETP-4830): {@code ensurePersonalRole}'s reuse check treats a candidate {@code
   * Default_Ad_Role_ID} with zero {@code AD_User_Roles} rows as "safe to reuse" (a legitimate
   * accommodation for an existing, manually-configured user) — but a user THIS METHOD just
   * created can never legitimately already have a personal role, so any non-null {@code
   * Default_Ad_Role_ID} already on the row at this point is necessarily stale/incorrect data
   * (e.g. the frontend's now-fixed stale-{@code hook.editing} bug, see
   * {@code createFreshPersonalRole}'s own javadoc) — reusing it silently handed a brand-new user
   * an unrelated pre-existing role instead of the empty one this ticket requires. {@code
   * createFreshPersonalRole} closes that gap by never consulting {@code Default_Ad_Role_ID} at
   * all for a newly-created user, and {@link UserRoleSyncSupport#syncSingleActiveUserRole(User,
   * Role)} still does the actual {@code AD_User_Roles} write, the exact same mechanism {@link
   * #syncUserRole(String)} uses elsewhere in this class, instead of hand-rolling a new insert.</p>
   *
   * <p>Best-effort, same contract as the rest of this method (see its own javadoc): a failure
   * here must never block the parent {@code AD_User} creation or the invitation that follows —
   * every failure path is logged at WARN with enough context (userId/email/clientId) to diagnose
   * without a DB query, never silently swallowed.</p>
   *
   * <p>ETP-5277: {@code createFreshPersonalRole} above (via {@code
   * PersonalRoleAccessProvisioningService#applyUserDefaults}) also recomputes {@code
   * defaultClient}/{@code defaultOrganization}/{@code defaultWarehouse} on {@code user} from the
   * real client/organization/first-active-warehouse, and this method itself sets {@code
   * defaultRole} to the personal role right below — but none of that was ever reflected back into
   * the create response's {@code data} row, which was built from the request payload BEFORE these
   * writes ran. {@code NeoDefaultsService}'s create-defaults bootstrap can leak the CREATING
   * admin's own session-derived role/client/org/warehouse into that payload (no {@code
   * AD_Preference} configured for these 4 columns), so the create response briefly echoed back the
   * wrong user's values until a follow-up GET. {@link #patchUserDefaultsOntoRow} closes that gap by
   * re-reading the 4 fields off {@code user} once they are final, mirroring how {@link
   * #attachInvitationStatusToRowSafely} patches {@code invitationStatus} onto this same {@code
   * data} reference.</p>
   *
   * @param userId the newly-created user's {@code AD_User_ID}, read from the create response's
   *     {@code data[0].id} by the caller — {@code null} (missing from the response) is logged and
   *     treated as a no-op, since there is nothing to look up
   * @param email the newly-created user's email — used only for logging context
   * @param clientId the current client id — used only for logging context
   * @param data the create response's {@code data[0]} row (see {@link #inviteNewlyCreatedUser}) —
   *     patched in place with the final {@code defaultRole}/{@code defaultClient}/{@code
   *     defaultOrganization}/{@code defaultWarehouse} once this method's own writes succeed;
   *     {@code null} is tolerated (no-op patch, everything else still runs)
   */
  private void ensurePersonalRoleForNewlyCreatedUser(String userId, String email,
      String clientId, JSONObject data) {
    if (userId == null) {
      log.warn("UserRoleAssignmentHandler.ensurePersonalRoleForNewlyCreatedUser: no 'id' entry "
              + "in the create response for email={} clientId={} — personal role not created",
          email, clientId);
      return;
    }
    try {
      User user = OBDal.getInstance().get(User.class, userId);
      if (user == null) {
        log.warn("UserRoleAssignmentHandler.ensurePersonalRoleForNewlyCreatedUser: user {} not "
                + "found right after creation (email={} clientId={}) — personal role not created",
            userId, email, clientId);
        return;
      }
      Role personalRole = new UserRoleCompositionService().createFreshPersonalRole(user);
      user.setDefaultRole(personalRole);
      OBDal.getInstance().save(user);
      OBDal.getInstance().flush();
      UserRoleSyncSupport.syncSingleActiveUserRole(user, personalRole);
      log.info("UserRoleAssignmentHandler.ensurePersonalRoleForNewlyCreatedUser: assigned "
              + "personal role {} to newly-created user {} (email={} clientId={})",
          personalRole.getId(), userId, email, clientId);
      patchUserDefaultsOntoRowSafely(data, user, userId, email, clientId);
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.ensurePersonalRoleForNewlyCreatedUser error for user "
              + "{} email={} clientId={}: {}",
          userId, email, clientId, e.getMessage(), e);
    }
  }

  /**
   * ETP-5277: best-effort wrapper around {@link #patchUserDefaultsOntoRow} — isolated in its own
   * try/catch, same reasoning as {@link #attachInvitationStatusToRowSafely}: the {@code AD_User}
   * write above (personal role + {@code applyUserDefaults}) has already succeeded by the time this
   * runs, so a JSON failure here must be logged as its own thing rather than folded into the
   * caller's generic {@code catch}, which would misleadingly read as "personal role not created".
   */
  private void patchUserDefaultsOntoRowSafely(JSONObject data, User user, String userId,
      String email, String clientId) {
    try {
      patchUserDefaultsOntoRow(data, user);
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.ensurePersonalRoleForNewlyCreatedUser: failed to patch "
              + "default-* fields onto the create response for user={} email={} clientId={}: {}",
          userId, email, clientId, e.getMessage(), e);
    }
  }

  /**
   * ETP-5277: writes the FINAL, already-persisted {@code defaultRole}/{@code defaultClient}/
   * {@code defaultOrganization}/{@code defaultWarehouse} (plus their {@code $_identifier}
   * companions) from {@code user} onto the create response's {@code data} row — see {@link
   * #ensurePersonalRoleForNewlyCreatedUser}'s javadoc for why this is needed.
   *
   * <p>Identifier companions use {@link BaseOBObject#getIdentifier()}, the same convention {@code
   * NeoDefaultsService#tryInjectIdentifier} and core's {@code DefaultJsonDataService} already use
   * for every other FK field's {@code $_identifier} value — deliberately not a hand-rolled
   * per-entity {@code getName()}/hardcoded map (see {@code artifacts/user/decisions.json}'s own
   * {@code defaultRole} entry in the functional repo, which documents that exact mistake already
   * made and fixed once, 2026-07-27: a static value→name map only works for the tenant it was
   * built from).</p>
   *
   * <p>Any of the 4 references being {@code null} on {@code user} (e.g. {@code defaultWarehouse}
   * when the client has no active warehouse yet) is tolerated: that field is simply omitted from
   * the patch, left exactly as the create payload already had it — never forced to {@code
   * JSONObject.NULL}, since a merely-absent field and an explicitly-null one are handled
   * differently by the frontend's defaults-merge.</p>
   *
   * @param data the create response row to patch in place; a no-op if {@code null}
   * @param user the newly-created user, re-read after {@code createFreshPersonalRole}/{@code
   *     setDefaultRole}/{@code flush} so every getter below reflects the persisted final state
   */
  private void patchUserDefaultsOntoRow(JSONObject data, User user) throws JSONException {
    if (data == null || user == null) {
      return;
    }
    putDefaultFieldWithIdentifier(data, FIELD_DEFAULT_ROLE, user.getDefaultRole());
    putDefaultFieldWithIdentifier(data, FIELD_DEFAULT_CLIENT, user.getDefaultClient());
    putDefaultFieldWithIdentifier(data, FIELD_DEFAULT_ORGANIZATION, user.getDefaultOrganization());
    putDefaultFieldWithIdentifier(data, FIELD_DEFAULT_WAREHOUSE, user.getDefaultWarehouse());
  }

  /**
   * Writes {@code propertyName} (the referenced record's id) and {@code propertyName$_identifier}
   * (its display label, via {@link BaseOBObject#getIdentifier()}) onto {@code data} — skipped
   * entirely when {@code reference} is {@code null} (see {@link #patchUserDefaultsOntoRow}'s
   * javadoc on why that case is left untouched rather than nulled).
   */
  private static void putDefaultFieldWithIdentifier(JSONObject data, String propertyName,
      BaseOBObject reference) throws JSONException {
    if (reference == null) {
      return;
    }
    data.put(propertyName, reference.getId());
    data.put(propertyName + "$" + JsonConstants.IDENTIFIER, reference.getIdentifier());
  }

  /**
   * Logs the outcome of {@link CompanyInvitationService#createInvitationForNewlyCreatedUser} —
   * WARN with the error code/message when the returned JSON marks {@code error: true} (see the
   * class's {@code errorResponse} helper), INFO with the invitation status otherwise (see its
   * {@code invitationResponse}/{@code existingInvitationResponse} helpers, both of which set
   * {@code status: "success"} plus a nested {@code invitation.status} of {@code SENT}/{@code
   * DELIVERY_FAILED}/{@code PENDING}). Never throws — a malformed/{@code null} result is logged
   * as best it can be rather than failing the best-effort caller.
   */
  private void logInvitationResult(JSONObject invitationResult, String email, String clientId) {
    if (invitationResult == null) {
      log.warn("UserRoleAssignmentHandler.inviteNewlyCreatedUser: no response from "
          + "CompanyInvitationService for email={} clientId={}", email, clientId);
      return;
    }
    if (invitationResult.optBoolean(FIELD_ERROR, false)) {
      log.warn("UserRoleAssignmentHandler.inviteNewlyCreatedUser: invitation NOT created for "
              + "email={} clientId={} — code={} message={}",
          email, clientId, invitationResult.optString("code", null),
          invitationResult.optString(FIELD_MESSAGE, null));
      return;
    }
    JSONObject invitation = invitationResult.optJSONObject(FIELD_INVITATION);
    String invitationStatus = invitation != null ? invitation.optString(FIELD_STATUS, null) : null;
    log.info("UserRoleAssignmentHandler.inviteNewlyCreatedUser: invitation created status={} "
        + "for email={} clientId={}", invitationStatus, email, clientId);
  }

  /**
   * On a {@code user} GET (list or single-record), attaches an {@code invitationStatus} field
   * to every surviving row — {@code null} when no invitation was ever sent, otherwise one of
   * {@code PENDING}/{@code SENT}/{@code ACCEPTED}/{@code EXPIRED}/{@code REVOKED}/{@code
   * DELIVERY_FAILED} (see {@link CompanyInvitationService#findLatestInvitationStatus}) — so the
   * frontend can render a "pending invite" badge without a separate round trip. Both list and
   * single-record GET responses carry {@code data} as a {@code JSONArray} (see {@link
   * #inviteNewlyCreatedUser}'s javadoc, ETP-4830) — the {@code optJSONObject} fallback below is
   * defensive only, kept in case a future response shape genuinely drops the array wrapper for a
   * single record; it should never actually be exercised against the current core behavior.
   */
  private void attachInvitationStatus(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      if (inner == null) {
        return;
      }
      String clientId = context.getObContext() != null
          && context.getObContext().getCurrentClient() != null
          ? context.getObContext().getCurrentClient().getId() : null;
      if (clientId == null) {
        return;
      }
      OBContext.setAdminMode(true);
      try {
        JSONArray data = inner.optJSONArray(JsonConstants.RESPONSE_DATA);
        if (data != null) {
          for (int i = 0; i < data.length(); i++) {
            attachInvitationStatusToRow(data.optJSONObject(i), clientId);
          }
        } else {
          attachInvitationStatusToRow(inner.optJSONObject(JsonConstants.RESPONSE_DATA), clientId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.attachInvitationStatus error: {}", e.getMessage(), e);
    }
  }

  private void attachInvitationStatusToRow(JSONObject row, String clientId) {
    if (row == null) {
      return;
    }
    String email = StringUtils.trimToNull(row.optString(FIELD_EMAIL, null));
    String status = email == null ? null
        : CompanyInvitationService.findLatestInvitationStatus(clientId, email);
    try {
      row.put(FIELD_INVITATION_STATUS, status == null ? JSONObject.NULL : status);
    } catch (JSONException e) {
      log.warn("UserRoleAssignmentHandler.attachInvitationStatusToRow error: {}",
          e.getMessage(), e);
    }
  }

  /**
   * On a {@code user} GET (list or single-record), attaches a boolean {@code isOwner} field to
   * every surviving row — {@code true} only for the ONE {@code AD_User} per client flagged via
   * {@code EM_ETGO_Is_Owner} (see {@link OwnerSupport}, ETP-4830 item #4), so the frontend can
   * render an "Owner" badge without a separate round trip, the same pattern {@link
   * #attachInvitationStatus} already established for {@code invitationStatus}. Unlike that
   * method, no {@code clientId}/admin-mode scoping is needed — {@link OwnerSupport#isOwner}
   * reads straight off the row's own id via a native query, which does not go through OBContext's
   * row-level filtering at all. Same {@code JSONArray}-vs-lone-object defensive shape as {@link
   * #attachInvitationStatus} (see that method's own javadoc for why the fallback branch should
   * never actually be exercised against current core behavior).
   */
  private void attachOwnerFlag(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      if (inner == null) {
        return;
      }
      JSONArray data = inner.optJSONArray(JsonConstants.RESPONSE_DATA);
      if (data != null) {
        for (int i = 0; i < data.length(); i++) {
          attachOwnerFlagToRow(data.optJSONObject(i));
        }
      } else {
        attachOwnerFlagToRow(inner.optJSONObject(JsonConstants.RESPONSE_DATA));
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.attachOwnerFlag error: {}", e.getMessage(), e);
    }
  }

  private void attachOwnerFlagToRow(JSONObject row) {
    if (row == null) {
      return;
    }
    String userId = StringUtils.trimToNull(row.optString(FIELD_ID, null));
    boolean isOwner = userId != null && OwnerSupport.isOwner(userId);
    try {
      row.put(FIELD_IS_OWNER, isOwner);
    } catch (JSONException e) {
      log.warn("UserRoleAssignmentHandler.attachOwnerFlagToRow error: {}", e.getMessage(), e);
    }
  }

  /**
   * On a successful update of the {@code user} entity, ensures {@code AD_User_Roles} has
   * exactly one active row matching the saved {@code Default_Ad_Role_ID} (or zero rows if it
   * was cleared). See concern (1) in the class javadoc.
   *
   * @return always {@code null} — this is a side effect, never a response replacement.
   */
  private NeoResponse syncRoleAfterUpdate(NeoContext context) {
    String method = context.getHttpMethod();
    if (!METHOD_PUT.equalsIgnoreCase(method) && !METHOD_PATCH.equalsIgnoreCase(method)) {
      return null;
    }
    String userId = context.getRecordId();
    if (userId == null) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        syncUserRole(userId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.afterHandle error for user {}: {}", userId,
          e.getMessage(), e);
    }
    return null;
  }

  private void syncUserRole(String userId) {
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null) {
      return;
    }
    Role targetRole = user.getDefaultRole();
    UserRoleSyncSupport.syncSingleActiveUserRole(user, targetRole);
  }
}
