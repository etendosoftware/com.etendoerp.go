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

package com.etendoerp.go.schemaforge.webhooks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;
import com.etendoerp.go.supportaccess.SupportUserExclusion;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns, for an admin caller, an aggregate overview of the CALLING TENANT's 5
 * fixed roles (ETP-4513 — "Configuración &gt; Roles"): each role's display name, raw AD
 * description, count of assigned users, and the list of Etendo GO windows it can reach ({@code
 * AD_Window_Access}, intersected with the windows Etendo GO actually exposes today, minus those it
 * serves over NEO/MCP but never shows in its UI — see {@link
 * RoleAccessMatrix#resolveActiveEtendoGoWindowsById()}) — plus (ETP-4907) an explicit {@code
 * windowCount} per role and a full window × role permission {@code matrix}, grouped by top-level
 * menu category.
 *
 * <p><b>Shared matrix builder (ETP-5485).</b> The window set, the tier resolution (real windows
 * plus the ETP-5071 proxy rows), {@code matrix}, {@code reportsMatrix} and each card's {@code
 * windows} array are all built by {@link RoleAccessMatrix} — the same builder {@link
 * SFSystemRoleTemplates} uses for its {@code includeMatrix=true} response (the User window's
 * "Roles del usuario" tab), so both views always list the same rows. This class only resolves
 * WHICH roles are columns (tenant role vs system-template fallback), their user counts and cards.
 *
 * <p>Unlike {@code SFWindowAccessMap}, which answers "what can the CURRENT caller's own role
 * reach" for any authenticated role, this endpoint is a cross-role aggregate: it always returns
 * data for all 5 of the caller's OWN tenant's roles, regardless of which one the caller happens
 * to be using. That is exactly why it is gated to admin/client-admin callers only
 * ({@link NeoAccessHelper#isAdminOrClientAdmin(Role)}) — a regular role has no legitimate reason
 * to see every other role's user count and window list. Anyone else (including a request with
 * no role assigned) gets an empty {@code roles} array, mirroring {@link SFListMenu}'s "deny
 * silently, don't 403" convention for this webhook family.</p>
 *
 * <p><b>Tenant-relative role resolution (fixed 2026-07-27, was GOClient-hardcoded).</b> The
 * original implementation always returned GOClient's 5 specific {@code AD_Role_ID}s, regardless
 * of the caller's own client — harmless while GOClient was the only tenant with these roles at
 * all, but broken the moment ETP-4515/4516 (Phase 7) gave every tenant its own equivalent role
 * set: a non-GOClient admin would see GOClient's role NAMES (their ids happened to still resolve
 * via a direct {@code OBDal.get()} by PK, which bypasses client filtering) but EMPTY user
 * counts/windows, because the dependent {@code UserRoles}/{@code WindowAccess} queries silently
 * filtered out GOClient's rows as unreadable from the caller's own (different) client context —
 * a live, reproducible bug (RolesPresa tenant, 2026-07-27). Now resolves the 4 fixed-name roles
 * (Finance/Sales/Purchasing/Inventory) plus whichever role has {@code is_client_admin='Y'} WITHIN
 * {@code currentRole.getClient()} — the same "resolve by name + is_client_admin, scoped to
 * :client_id" approach used by the now-retired-and-deleted {@code OnboardingRoleProvisioningService}
 * (ETP-4852) and {@code R16-tenant-roles-and-webhook-access.sql} in {@code etendo_schema_forge}. Every
 * OBCriteria below explicitly disables readable-client/org filtering (matching every sibling
 * webhook in this package) so cross-tenant filtering can never silently empty a same-tenant
 * result again.</p>
 *
 * <p><b>System-template fallback (ETP-4907 — the "Configuración &gt; Roles" read side of
 * ETP-4852's role-composition rework).</b> ETP-4852 introduced 4 single, system-owned ({@code
 * AD_Client_ID = '0'}) template roles ({@link SystemRoleTemplates}) that a tenant's users now
 * compose their access from ({@link UserRoleCompositionService}), rather than every tenant
 * keeping its OWN active copy of "Finance"/"Sales"/"Purchasing"/"Inventory". A tenant that has
 * migrated to this model (confirmed live for GOClient, 2026-08-18: its own 4 named roles are
 * {@code IsActive = 'N'}) would otherwise silently drop from 5 role cards to 1 (just its
 * client-admin role) — {@link #resolveTenantRoles(String)} only ever returns ACTIVE roles. For
 * each of the 4 fixed names with no active tenant-scoped match, this class now falls back to the
 * corresponding {@link SystemRoleTemplates#byName()} system role: its windows are resolved via
 * the SAME {@link RoleAccessMatrix#resolveTierMap(Role, Map)} used for a real tenant role (already
 * client/org-filter-disabled, so it works unchanged for a system-client role — no separate
 * "system template window resolution" was written), and its {@code userCount} is the number of
 * this client's users whose PERSONAL role currently composes that template — from {@link
 * UserRoleCompositionService#getAppliedTemplateRoleIdsForClient(String)} — never a direct {@code
 * AD_User_Roles} count against the template itself, which would always read zero (users are
 * never assigned a template role directly; see that class's own javadoc). Each role card carries
 * a {@code roleSource} field ({@code "tenant"} or {@code "systemTemplate"}) so the frontend never
 * has to guess which id-space a card's {@code id} lives in. Both paths can be present
 * side-by-side across the 4 fixed names within one response (a tenant may have migrated some
 * roles but not others) — this is intentional graceful coexistence, not a bug.</p>
 *
 * <p><b>{@code matrix} (ETP-4907).</b> A full window × role permission grid, for every window in
 * {@link RoleAccessMatrix#resolveActiveEtendoGoWindowsById()} (not just the ones a role happens
 * to have access to — a window absent from a role's own {@code windows} list still gets a {@code
 * "none"} entry here), grouped by the window's top-level {@code AD_Menu} folder name; a window
 * with no resolvable top-level folder falls back to {@value RoleAccessMatrix#OTHER_CATEGORY}. The
 * tri-state access value per window/role pair — {@code "full"} / {@code "read-only"} / {@code
 * "none"} — reuses the exact same tier strings the per-role {@code
 * windows} array already uses, keyed by each role's own {@code id} (from the {@code roles}
 * array) so the frontend can join the two without a second id-mapping table.</p>
 *
 * <p><b>{@code rawDescription} is NOT display copy.</b> {@code AD_Role.description} is
 * boilerplate for 4 of the 5 GOClient roles today ({@code "*** Please, do not edit this role.
 * Use Copy Record instead ***"}) — this backend has no i18n awareness, so it cannot produce
 * user-facing copy itself. The field is returned only as a raw/debug fallback; the frontend
 * (`RolesOverviewPage.jsx`) maps the 4 fixed role NAMES (and the {@code isClientAdmin} flag for
 * the 5th) to curated, i18n-keyed copy instead of rendering this field. The same applies to
 * {@code matrix}'s category names, which are the raw (English) {@code AD_Menu.name} of each
 * window's top-level folder — the frontend is expected to map/translate them, not render them
 * verbatim.</p>
 *
 * <p><b>3 windowless {@code matrix} rows (ETP-5071).</b> "Monitor Fiscal", "Modelos Fiscales" and
 * "Documentos no contabilizados" are real, visible Etendo GO sidebar entries with no {@code
 * AD_Window_ID} of their own — 3 of the "twelve matrix rows" {@code TemplateRoleWindowAccess}'s
 * javadoc documents as a known gap. This narrows that gap for exactly these 3 rows (the other ~9
 * remain unresolved, deliberately out of scope) via a human-chosen proxy: each synthetic row's
 * access is resolved from a REAL {@code AD_Window_Access}/{@code OBUIAPP_Process_Access} grant on
 * a different, related entity — see {@link RoleAccessMatrix}'s own javadoc for each mapping, its
 * rationale and how it is merged in. Only 2 of the 3 ever add a NEW {@code matrix} row, though —
 * SII Monitor already produces its own real row today (see {@link
 * RoleAccessMatrix#buildMatrix(Map, Map)}'s duplicate-id guard), so its proxy access data reaches
 * the matrix through that pre-existing row instead of a second one. None of the 3 ever affect a role
 * card's own {@code windows}/{@code windowCount}.</p>
 *
 * <p>The current role is captured once, at the very top of {@link #get(Map, Map)}, before
 * {@link OBContext#setAdminMode()} is entered — the same convention {@link SFListMenu} follows
 * and for the same reason: access decisions must always be made against the role actually
 * resolved for this request, never against whatever the ambient OBContext happens to expose
 * once admin mode is active.</p>
 *
 * GET /webhooks/SFRolesOverview
 */
public class SFRolesOverview extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFRolesOverview.class);

  /** JSON key for the roles array in the response. */
  private static final String ROLES = "roles";

  /** JSON key for a role's id. */
  private static final String ID = "id";

  /** JSON key for a role's display name. */
  private static final String NAME = "name";

  /**
   * JSON key for a role's raw {@code AD_Role.description} — debug/fallback only, NOT display
   * copy. See the class javadoc for why.
   */
  private static final String RAW_DESCRIPTION = "rawDescription";

  /**
   * JSON key marking the tenant's client-admin role — its NAME varies per tenant (e.g.
   * "RolesPresa Admin" vs "GOClient Admin"), so the frontend needs this flag to render it with
   * generic "Administrator" copy instead of the literal AD_Role.name.
   */
  private static final String IS_CLIENT_ADMIN = "isClientAdmin";

  /**
   * JSON key marking where a role card's identity comes from — {@link #SOURCE_TENANT} for a real
   * role owned by the caller's own client, {@link #SOURCE_SYSTEM_TEMPLATE} for the ETP-4852
   * system-template fallback. See the class javadoc.
   */
  private static final String ROLE_SOURCE = "roleSource";

  /** {@link #ROLE_SOURCE} value for a role card backed by the caller's own tenant role. */
  private static final String SOURCE_TENANT = "tenant";

  /**
   * {@link #ROLE_SOURCE} value for a role card backed by an ETP-4852 system-level template
   * (client {@code '0'}) — used when the tenant has no active own-client copy of that fixed
   * name.
   */
  private static final String SOURCE_SYSTEM_TEMPLATE = "systemTemplate";

  /** JSON key for a role's assigned-user count. */
  private static final String USER_COUNT = "userCount";

  /** JSON key for a role's assigned-windows array. */
  private static final String WINDOWS = "windows";

  /**
   * JSON key for a role's window count — {@code windows.length}, surfaced explicitly (ETP-4907)
   * so the frontend's role cards don't have to derive it themselves.
   */
  private static final String WINDOW_COUNT = "windowCount";

  /** JSON key for the full window × role permission matrix (ETP-4907). */
  private static final String MATRIX = "matrix";

  /**
   * ETP-5402 — the "Informes" (Reports) subsection: the exact 9 reports the real
   * `report-viewer` gallery shows (8 Finance, 1 Inventory — see {@link ReportAccessCatalog}'s own
   * class javadoc for the corrected, live-QA-verified inventory; only 4 of the 9 even have an
   * {@code ETGO_SF_SPEC} row at all, so none is a candidate for {@link
   * RoleAccessMatrix#resolveActiveEtendoGoWindowsById()}, which only ever queries {@code
   * SPEC_TYPE = 'W'}).
   * Deliberately a PARALLEL {@code reports}/{@code reportCount}/{@code reportsMatrix} addition —
   * never merged into {@code windows}/{@code windowCount}/{@code matrix} — so no existing
   * consumer's contract changes.
   *
   * <p>The row catalog itself ({@link ReportAccessCatalog#ROWS}) and its tier-resolution logic
   * ({@link ReportAccessCatalog#resolveTierMap(Role)}) live in the shared {@link
   * ReportAccessCatalog} utility, NOT duplicated here — {@code SFSystemRoleTemplates} (the User
   * window's "Roles del usuario" tab matrix columns) needs the exact same Informes resolution, and
   * the two webhooks must never be allowed to drift on which anchor id/category/kind backs a given
   * report row.</p>
   */

  /** JSON key for a role's assigned-reports array (ETP-5402, parallel to {@link #WINDOWS}). */
  private static final String REPORTS = "reports";

  /** JSON key for a role's report count (ETP-5402, parallel to {@link #WINDOW_COUNT}). */
  private static final String REPORT_COUNT = "reportCount";

  /**
   * JSON key for the full report × role permission matrix (ETP-5402, sibling of {@link #MATRIX},
   * deliberately not nested inside it — see the class's Informes design note above).
   */
  private static final String REPORTS_MATRIX = "reportsMatrix";

  /**
   * The 4 fixed non-admin role names every tenant gets (ETP-4515/4516), in the display order
   * this endpoint returns them (after the client-admin role, which always sorts first). Mirrors
   * the now-deleted {@code OnboardingRoleProvisioningService.ROLE_NAMES} / R16's role list in
   * {@code etendo_schema_forge} — keep in lockstep. Also matches {@link
   * SystemRoleTemplates#byName()}'s own key order (Finance/Sales/Purchasing/Inventory) — the
   * ETP-4907 system-template fallback iterates that map directly rather than re-declaring a
   * second name list.
   */
  private static final String[] FIXED_ROLE_NAMES = { "Finance", "Sales", "Purchasing", "Inventory" };

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE entering admin mode — see the class javadoc and
    // SFListMenu's identical convention for why: access decisions must always be made against
    // the role actually resolved for this request, never against whatever the ambient
    // OBContext happens to expose once admin mode is active.
    Role currentRole = NeoAccessHelper.resolveCurrentRole();

    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put("result", emptyResult().toString());
      return;
    }

    OBContext.setAdminMode();
    try {
      JSONObject result = buildRolesOverview(currentRole.getClient().getId());
      responseVars.put("result", result.toString());
    } catch (Exception e) {
      log.error("Error in SFRolesOverview", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Builds the empty result used when the current request has no role assigned, or has a role
   * that is not admin/client-admin.
   */
  private static JSONObject emptyResult() {
    try {
      JSONObject result = new JSONObject();
      result.put(ROLES, new JSONArray());
      return result;
    } catch (JSONException e) {
      // JSONObject#put never throws for a non-null key; unreachable in practice.
      throw new IllegalStateException("Unable to build empty roles-overview result", e);
    }
  }

  /**
   * Builds the full {@code {roles, matrix}} result for {@code clientId} — the client-admin role
   * first, then the 4 fixed names in {@link #FIXED_ROLE_NAMES} order, each resolved either from
   * the tenant's own active role or (ETP-4907 fallback) the matching system-level template. See
   * the class javadoc for the full resolution rules.
   */
  private JSONObject buildRolesOverview(String clientId) throws JSONException {
    Map<String, Window> goWindowsById = RoleAccessMatrix.resolveActiveEtendoGoWindowsById();
    List<Role> tenantRoles = resolveTenantRoles(clientId);

    Role adminRole = null;
    Map<String, Role> tenantFixedRolesByName = new LinkedHashMap<>();
    for (Role role : tenantRoles) {
      if (Boolean.TRUE.equals(role.isClientAdmin())) {
        adminRole = role;
      } else {
        tenantFixedRolesByName.put(role.getName(), role);
      }
    }

    List<JSONObject> roleCards = new ArrayList<>();
    Map<String, Map<String, String>> tierMapsByRoleId = new LinkedHashMap<>();
    Map<String, Map<String, String>> reportTierMapsByRoleId = new LinkedHashMap<>();

    if (adminRole != null) {
      addTenantRoleCard(adminRole, goWindowsById, roleCards, tierMapsByRoleId, reportTierMapsByRoleId);
    }

    // Lazily resolved on the first fixed-name role that actually needs composition data — either
    // branch below may need it now (ETP-5065 hybrid-state fix), so both pass the same lazily
    // populated map through and reuse whatever the other already resolved.
    Map<String, List<String>> composedTemplateUserIdsByUserId = null;
    for (Map.Entry<String, String> fixedRole : SystemRoleTemplates.byName().entrySet()) {
      Role tenantRole = tenantFixedRolesByName.get(fixedRole.getKey());
      if (tenantRole != null) {
        composedTemplateUserIdsByUserId = addTenantRoleCardWithTemplateOverlap(tenantRole,
            fixedRole.getValue(), clientId, goWindowsById, roleCards, tierMapsByRoleId,
            reportTierMapsByRoleId, composedTemplateUserIdsByUserId);
      } else {
        composedTemplateUserIdsByUserId = addSystemTemplateRoleCardIfResolvable(
            fixedRole.getValue(), clientId, goWindowsById, roleCards, tierMapsByRoleId,
            reportTierMapsByRoleId, composedTemplateUserIdsByUserId);
      }
    }

    JSONArray roles = new JSONArray();
    for (JSONObject card : roleCards) {
      roles.put(card);
    }

    JSONObject result = new JSONObject();
    result.put(ROLES, roles);
    result.put(MATRIX, RoleAccessMatrix.buildMatrix(goWindowsById, tierMapsByRoleId));
    result.put(REPORTS_MATRIX, RoleAccessMatrix.buildReportsMatrix(reportTierMapsByRoleId));
    return result;
  }

  /**
   * Resolves {@code role}'s window-tier map and appends its role card, mutating both {@code
   * roleCards} and {@code tierMapsByRoleId} — shared by the admin-role branch and the tenant-side
   * of the fixed-name loop in {@link #buildRolesOverview(String)}.
   */
  private void addTenantRoleCard(Role role, Map<String, Window> goWindowsById,
      List<JSONObject> roleCards, Map<String, Map<String, String>> tierMapsByRoleId,
      Map<String, Map<String, String>> reportTierMapsByRoleId) throws JSONException {
    Map<String, String> tiers = RoleAccessMatrix.resolveTierMap(role, goWindowsById);
    tierMapsByRoleId.put(role.getId(), tiers);
    Map<String, String> reportTiers = ReportAccessCatalog.resolveTierMap(role);
    reportTierMapsByRoleId.put(role.getId(), reportTiers);
    roleCards.add(buildRoleCardJson(role, tiers, reportTiers, goWindowsById, SOURCE_TENANT,
        resolveActiveUserIds(role).size()));
  }

  /**
   * ETP-5065 (hybrid-state fix) — like {@link #addTenantRoleCard}, but for a fixed-name role
   * (Finance/Sales/Purchasing/Inventory) specifically: {@code userCount} is the UNION of (a)
   * users directly assigned to the tenant's own active {@code tenantRole} ({@link
   * #resolveActiveUserIds}), and (b) users of {@code clientId} whose personal role currently
   * composes the matching SYSTEM TEMPLATE role ({@code templateId} — a separate {@code AD_Role}
   * row, owned by client {@code '0'}, {@code ISTEMPLATE = 'Y'} — see {@link
   * SystemRoleTemplates}).
   *
   * <p>Before this fix, a tenant in the (increasingly common, ETP-4852-adjacent) hybrid state —
   * its own copy of a fixed-name role still ACTIVE, while some real users reach that same
   * fixed-name access via a personal role's {@code AD_Role_Inheritance} pointing at the SEPARATE
   * system-template role — silently dropped every composed user from the card: {@link
   * #addTenantRoleCard} only ever saw direct assignees of {@code tenantRole}, and the
   * system-template branch ({@link #addSystemTemplateRoleCardIfResolvable}) was skipped entirely
   * because {@code tenantRole} being active took priority in {@link #buildRolesOverview(String)}
   * 's branch selection. Confirmed live on GOClient (2026-08-27): its own active "Sales"/
   * "Purchasing"/"Inventory" roles had zero direct assignees, showing {@code 0} on those cards,
   * despite 1-2 real invited users actually holding that access by composing the corresponding
   * system template onto their personal role. Windows/tier data for the card still comes from
   * {@code tenantRole} alone (unchanged, not reported as broken) — only {@code userCount} is a
   * union.</p>
   *
   * @return {@code composedTemplateUserIdsByUserId}, unchanged if it was already resolved, or
   *     newly populated if this was the first call in the request that needed it (mirrors {@link
   *     #addSystemTemplateRoleCardIfResolvable}'s identical laziness contract)
   */
  private Map<String, List<String>> addTenantRoleCardWithTemplateOverlap(Role tenantRole,
      String templateId, String clientId, Map<String, Window> goWindowsById,
      List<JSONObject> roleCards, Map<String, Map<String, String>> tierMapsByRoleId,
      Map<String, Map<String, String>> reportTierMapsByRoleId,
      Map<String, List<String>> composedTemplateUserIdsByUserId) throws JSONException {
    Map<String, String> tiers = RoleAccessMatrix.resolveTierMap(tenantRole, goWindowsById);
    tierMapsByRoleId.put(tenantRole.getId(), tiers);
    Map<String, String> reportTiers = ReportAccessCatalog.resolveTierMap(tenantRole);
    reportTierMapsByRoleId.put(tenantRole.getId(), reportTiers);

    Set<String> userIds = new LinkedHashSet<>(resolveActiveUserIds(tenantRole));
    Map<String, List<String>> composed = composedTemplateUserIdsByUserId != null
        ? composedTemplateUserIdsByUserId
        : new UserRoleCompositionService().getAppliedTemplateRoleIdsForClient(clientId);
    for (Map.Entry<String, List<String>> entry : composed.entrySet()) {
      if (entry.getValue().contains(templateId)) {
        userIds.add(entry.getKey());
      }
    }

    roleCards.add(buildRoleCardJson(tenantRole, tiers, reportTiers, goWindowsById, SOURCE_TENANT,
        userIds.size()));
    return composed;
  }

  /**
   * ETP-4907 system-template fallback for one fixed name with no active tenant-scoped role (see
   * the class javadoc). Resolves {@code templateId}, and — only if it is an active {@code Role}
   * — appends its role card, mutating both {@code roleCards} and {@code tierMapsByRoleId} exactly
   * like {@link #addTenantRoleCard}, sourcing {@code userCount} from composition instead of
   * direct {@code AD_User_Roles}. {@code composedTemplateUserIdsByUserId} is resolved lazily —
   * {@code null} in means "not resolved yet for this request"; this method resolves it on first
   * need and returns it so {@link #buildRolesOverview(String)}'s loop can reuse it for later
   * fixed names without querying the composition service more than once per request.
   *
   * @return {@code composedTemplateUserIdsByUserId}, unchanged if the template did not resolve,
   *     or newly populated if this was the first call in the request that needed it
   */
  private Map<String, List<String>> addSystemTemplateRoleCardIfResolvable(String templateId,
      String clientId, Map<String, Window> goWindowsById, List<JSONObject> roleCards,
      Map<String, Map<String, String>> tierMapsByRoleId,
      Map<String, Map<String, String>> reportTierMapsByRoleId,
      Map<String, List<String>> composedTemplateUserIdsByUserId) throws JSONException {
    Role templateRole = OBDal.getInstance().get(Role.class, templateId);
    if (templateRole == null || !Boolean.TRUE.equals(templateRole.isActive())) {
      // No active tenant role AND the system template itself is missing/inactive — nothing to
      // report for this fixed name; mirrors the pre-existing "fewer than 5 roles" degradation.
      return composedTemplateUserIdsByUserId;
    }
    Map<String, List<String>> composed = composedTemplateUserIdsByUserId != null
        ? composedTemplateUserIdsByUserId
        : new UserRoleCompositionService().getAppliedTemplateRoleIdsForClient(clientId);

    int userCount = countUsersComposingTemplate(composed, templateRole.getId());
    Map<String, String> tiers = RoleAccessMatrix.resolveTierMap(templateRole, goWindowsById);
    tierMapsByRoleId.put(templateRole.getId(), tiers);
    Map<String, String> reportTiers = ReportAccessCatalog.resolveTierMap(templateRole);
    reportTierMapsByRoleId.put(templateRole.getId(), reportTiers);
    roleCards.add(buildRoleCardJson(templateRole, tiers, reportTiers, goWindowsById,
        SOURCE_SYSTEM_TEMPLATE, userCount));
    return composed;
  }

  /**
   * Resolves {@code clientId}'s own client-admin role plus its 4 {@link #FIXED_ROLE_NAMES}
   * ACTIVE roles — a fixed name with no active tenant-scoped match is simply absent here; {@link
   * #buildRolesOverview(String)} is responsible for falling back to the matching system template
   * (ETP-4907). Scoped strictly to {@code clientId}.
   */
  @SuppressWarnings("unchecked")
  private List<Role> resolveTenantRoles(String clientId) {
    OBCriteria<Role> criteria = OBDal.getInstance().createCriteria(Role.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Role.PROPERTY_CLIENT + ".id", clientId));
    criteria.add(Restrictions.eq(Role.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.or(
        Restrictions.eq(Role.PROPERTY_CLIENTADMIN, true),
        Restrictions.in(Role.PROPERTY_NAME, (Object[]) FIXED_ROLE_NAMES)));

    List<String> fixedNameOrder = Arrays.asList(FIXED_ROLE_NAMES);
    List<Role> roles = new ArrayList<>((List<Role>) criteria.list());
    roles.sort((a, b) -> {
      boolean aAdmin = Boolean.TRUE.equals(a.isClientAdmin());
      boolean bAdmin = Boolean.TRUE.equals(b.isClientAdmin());
      if (aAdmin != bAdmin) {
        return aAdmin ? -1 : 1;
      }
      return Integer.compare(fixedNameOrder.indexOf(a.getName()), fixedNameOrder.indexOf(b.getName()));
    });
    return roles;
  }

  /**
   * Builds a single role card's JSON entry.
   */
  private JSONObject buildRoleCardJson(Role role, Map<String, String> tiers,
      Map<String, String> reportTiers, Map<String, Window> goWindowsById, String roleSource,
      int userCount) throws JSONException {
    JSONObject roleJson = new JSONObject();
    roleJson.put(ID, role.getId());
    roleJson.put(NAME, role.getName());
    roleJson.put(RAW_DESCRIPTION, role.getDescription());
    roleJson.put(IS_CLIENT_ADMIN, Boolean.TRUE.equals(role.isClientAdmin()));
    roleJson.put(ROLE_SOURCE, roleSource);
    roleJson.put(USER_COUNT, userCount);
    JSONArray windows = RoleAccessMatrix.windowsJson(tiers, goWindowsById);
    roleJson.put(WINDOWS, windows);
    roleJson.put(WINDOW_COUNT, windows.length());
    JSONArray reports = ReportAccessCatalog.reportsJson(reportTiers);
    roleJson.put(REPORTS, reports);
    roleJson.put(REPORT_COUNT, reports.length());
    return roleJson;
  }

  /**
   * The distinct users with an active {@code AD_User_Roles} row for {@code role}. Only valid for a
   * REAL, directly-assignable role (a tenant's own role, or the client-admin role) — never for a
   * system-level template, which users are never assigned to directly (see the class javadoc's
   * system-template-fallback section).
   *
   * <p><b>Cross-client bootstrap user excluded (ETP-5065).</b> Etendo core's standard
   * client-provisioning flow ({@code InitialClientSetup}/{@code InitialOrgSetup} reference-data
   * copy) automatically grants the seed {@code AD_User_ID = '100'} account ({@code username =
   * admin}, always {@code AD_Client_ID = '0'}/System — the classic Openbravo "admin/admin"
   * bootstrap login) an active {@code AD_User_Roles} row on every role of every newly created
   * client, as a safety-net login. That row is real and active, but the user it points to is not
   * a member of {@code role}'s own tenant — confirmed identically present across every client in
   * the DB, so this is systemic core behavior, not tenant-specific data corruption. Counting it
   * inflated a brand-new, single-owner tenant's "Administrador" card to 2 users. Restricting the
   * join to users whose OWN client matches {@code role}'s client excludes this (and any other
   * cross-client) row without special-casing the {@code '100'} id.</p>
   *
   * <p><b>Why direct-assignment counting remains correct here, even with personal roles live.</b>
   * {@link UserRoleCompositionService#resolveOrCreatePersonalRole} explicitly refuses to ever
   * reuse an {@code isClientAdmin()} role as a user's personal role, and the admin promotion
   * design being built alongside this fix assigns a promoted user's tenant admin role directly
   * in {@code AD_User_Roles} (unwiring, not deleting, their personal role) — so, unlike the 4
   * fixed-name roles, one or more real users holding a DIRECT {@code AD_User_Roles} row here is
   * always the correct, intended shape, both today and after that feature ships. This fix does
   * does not extend to the direct-assignee set for an active tenant-owned copy of a fixed-name role
   * (Finance/Sales/Purchasing/Inventory) composed onto via a personal role's {@code
   * AD_Role_Inheritance} — see {@link #addTenantRoleCardWithTemplateOverlap} for that separate fix,
   * folded into the same ETP-5065 ticket after further investigation.</p>
   *
   * <p>Returns ids instead of a count so {@link #addTenantRoleCardWithTemplateOverlap} can union the
   * direct-assignee set with template-composed users before taking a final size.</p>
   */
  @SuppressWarnings("unchecked")
  private Set<String> resolveActiveUserIds(Role role) {
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ACTIVE, true));
    // The user's OWN client, not the user-role row's — a role may only count assignees belonging
    // to the same tenant.
    //
    // This needs an explicit alias: a Hibernate Criteria resolves a one-level `property.id` (it is
    // the FK column on this very table) but NOT a two-level path like `userContact.client.id`,
    // which throws `could not resolve property` from AbstractEntityPersister.toColumns at query
    // time. ETP-5065 added the filter written that way and it blew up the whole Roles page with a
    // 500 — the failure is at RUNTIME, so nothing catches it until the request is actually made.
    criteria.createAlias(UserRoles.PROPERTY_USERCONTACT, "assignee");
    criteria.add(Restrictions.eq("assignee." + User.PROPERTY_CLIENT + ".id",
        role.getClient().getId()));

    Set<String> userIds = new LinkedHashSet<>();
    for (UserRoles userRole : (List<UserRoles>) criteria.list()) {
      if (userRole.getUserContact() != null) {
        userIds.add(userRole.getUserContact().getId());
      }
    }
    // ETP-5351 (T6): the tenant's "Soporte Etendo" user holds the admin role but is not one of
    // the tenant's users, so it never counts on a card.
    SupportUserExclusion.removeFrom(userIds, role.getClient().getId());
    return userIds;
  }

  /**
   * Counts, across {@code composedTemplateUserIdsByUserId} (one entry per user of the client,
   * from {@link UserRoleCompositionService#getAppliedTemplateRoleIdsForClient(String)}), how many
   * users currently have {@code templateId} applied to their personal role.
   */
  private static int countUsersComposingTemplate(
      Map<String, List<String>> composedTemplateUserIdsByUserId, String templateId) {
    int count = 0;
    for (List<String> appliedTemplateIds : composedTemplateUserIdsByUserId.values()) {
      if (appliedTemplateIds.contains(templateId)) {
        count++;
      }
    }
    return count;
  }

}
