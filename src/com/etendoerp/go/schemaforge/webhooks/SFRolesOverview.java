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
import java.util.Collections;
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
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.client.application.Process;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns, for an admin caller, an aggregate overview of the CALLING TENANT's 5
 * fixed roles (ETP-4513 — "Configuración &gt; Roles"): each role's display name, raw AD
 * description, count of assigned users, and the list of Etendo GO windows it can reach ({@code
 * AD_Window_Access}, intersected with the windows Etendo GO actually exposes today, minus those it
 * serves over NEO/MCP but never shows in its UI — see {@link #resolveActiveEtendoGoWindowsById()}
 * and {@link #UI_EXCLUDED_WINDOW_IDS}) — plus (ETP-4907) an explicit {@code windowCount} per
 * role and a full window × role permission {@code matrix}, grouped by top-level menu category.
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
 * the SAME {@link #resolveWindowTierMap(Role, Set)} used for a real tenant role (already
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
 * {@link #resolveActiveEtendoGoWindowsById()} (not just the ones a role happens to have access
 * to — a window absent from a role's own {@code windows} list still gets a {@code "none"} entry
 * here), grouped by the window's top-level {@code AD_Menu} folder name (via {@link
 * #resolveWindowCategories(Set)}; a window with no resolvable top-level folder falls back to
 * {@value #OTHER_CATEGORY}). The tri-state access value per window/role pair — {@code "full"} /
 * {@code "read-only"} / {@code "none"} — reuses the exact same tier strings the per-role {@code
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
 * a different, related entity — see {@link #FISCAL_MONITOR_PROXY_WINDOW_ID}, {@link
 * #TAX_MODELS_PROXY_WINDOW_ID} and {@link #NOT_POSTED_DOCS_PROXY_PROCESS_ID}'s own javadoc for
 * each mapping and its rationale, and {@link #mergeProxyAccessTiers(Role, Map)} for how it is
 * merged in. These 3 rows never affect a role card's own {@code windows}/{@code windowCount} —
 * only the {@code matrix}.</p>
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

  /** JSON key for a window entry's access tier. */
  private static final String TIER = "tier";

  /** Access-tier value for a window with full (read+write) access. */
  private static final String FULL = "full";

  /** Access-tier value for a window with read-only access. */
  private static final String READ_ONLY = "read-only";

  /** Access-tier value for a window a role cannot reach at all — {@code matrix}-only. */
  private static final String NONE = "none";

  /** {@code ETGO_SF_SPEC.SPEC_TYPE} value identifying a window/CRUD spec. */
  private static final String SPEC_TYPE_WINDOW = "W";

  /**
   * AD windows Etendo GO deliberately does NOT surface anywhere in its own UI, even though they
   * still have an active {@code SPEC_TYPE = 'W'} {@code ETGO_SF_SPEC} because NEO/MCP keeps
   * serving them read-only.
   *
   * <p>Filtered out in {@link #resolveActiveEtendoGoWindowsById()}, which is the single source
   * every downstream structure derives from — each role's {@code windows} array, its {@code
   * windowCount}, and the {@code matrix} — so ONE entry here removes the window from
   * "Configuración &gt; Roles" AND from "Usuario &gt; Roles" (whose React tab intersects {@code
   * SFListMenu}'s raw AD tree against the union of these {@code windows} arrays — see
   * {@code UserRolesTab.jsx}'s {@code activeWindowIds}).
   *
   * <p>ETP-5068 — "Conversion Rate Downloader Log"
   * ({@code 6FEBA130CDE24CC09041FFA6117ADFA9}): an internal log of the conversion-rate
   * downloader job, dropped from the Etendo Go menu because it adds no value to the end user.
   * Administrators read it in Etendo classic, so the GO template roles deliberately KEEP their
   * {@code AD_Window_Access} grant (see {@code TemplateRoleWindowAccess}) — which is precisely
   * why the window cannot be hidden by revoking access, and why the exclusion lives here and
   * not in {@code SFListMenu}, whose tree must keep reporting the native AD menu as-is for its
   * other consumers.
   *
   * <p>Note {@code Set.of(...)} rejects {@code contains(null)} with an NPE rather than returning
   * {@code false}, so callers must guard the id before probing this set.
   */
  private static final Set<String> UI_EXCLUDED_WINDOW_IDS = Set.of("6FEBA130CDE24CC09041FFA6117ADFA9");

  /**
   * ETP-5071 — proxy {@code AD_Window_ID} standing in for "Monitor Fiscal" in the {@code matrix}.
   *
   * <p>"Monitor Fiscal" is one of {@code TemplateRoleWindowAccess}'s own documented "twelve
   * matrix rows... intentionally NOT represented" (see that class's javadoc, and {@code
   * docs/neo-headless.md} §7 in this module): it is a pure Etendo-GO-native custom page with no
   * {@code AD_Window_ID} of its own, so it can never appear in {@link
   * #resolveActiveEtendoGoWindowsById()} or get a real {@link #resolveWindowTierMap(Role, Set)}
   * entry the way an ordinary window does. Its frontend page internally aggregates data from 3
   * real classic windows — SII Monitor ({@value}), Monitor Verifactu
   * ({@code F4675DAB02134762B66881DAE4672AD0}), and TBAI Facturas Enviadas
   * ({@code 71F24BF89DE748B483BE87594747D6FB}) — and the product owner picked SII Monitor,
   * arbitrarily but explicitly, as the single representative window whose {@code
   * AD_Window_Access} grant stands in for "can this role see Monitor Fiscal at all" (any of the
   * three would have worked equally well per the product owner).
   *
   * <p>Spot-checked live against this module's own dev DB (2026-09-04): every role across the
   * environment that grants access to ANY of the three candidate windows grants IDENTICAL tiers
   * (full/read-only) on all three — no role was found where they diverge — so SII Monitor is a
   * safe representative, not merely an arbitrary one.
   *
   * <p>This is a deliberately narrow, human-chosen proxy for exactly this one row (ETP-5071) —
   * NOT a general solution to the other ~9 rows in {@code TemplateRoleWindowAccess}'s "twelve
   * rows" list, which remain out of scope and unresolved.
   */
  private static final String FISCAL_MONITOR_PROXY_WINDOW_ID = "FEF76C3E0F104F06A89AAD15A4A4A35C";

  /**
   * ETP-5071 — proxy {@code AD_Window_ID} standing in for "Modelos Fiscales" in the {@code
   * matrix}, same windowless-page situation as {@link #FISCAL_MONITOR_PROXY_WINDOW_ID} (also one
   * of {@code TemplateRoleWindowAccess}'s "twelve rows"). The product owner chose the Tax Report
   * window as this row's access proxy.
   */
  private static final String TAX_MODELS_PROXY_WINDOW_ID = "3E8FEA1EA7404D979306C9EE7FD2E7E8";

  /**
   * ETP-5071 — proxy {@code OBUIAPP_Process_ID} standing in for "Documentos no contabilizados" in
   * the {@code matrix}, same windowless-page situation as {@link
   * #FISCAL_MONITOR_PROXY_WINDOW_ID} — except this page has no candidate classic WINDOW at all to
   * proxy through (it is a report-type spec with zero classic AD entity), so its access is
   * resolved from the real {@code OBUIAPP_Process_Access} grant on the "Not Posted Documents"
   * process instead. See {@link #resolveProcessTierMap(Role, String)}.
   */
  private static final String NOT_POSTED_DOCS_PROXY_PROCESS_ID = "D6AB95CE52D34E1599590526115E26C6";

  /**
   * A single {@code matrix} row's identity (id + display name) — either a real {@link Window}
   * (adapted from {@link #resolveActiveEtendoGoWindowsById()}) or one of the {@link
   * #PROXY_MATRIX_ROWS} synthetic ETP-5071 rows that have no backing {@code Window} entity at
   * all. {@link #buildMatrix(Map, Map)} groups/sorts/renders both kinds identically once
   * expressed as this common shape.
   */
  private static final class MatrixRow {
    private final String id;
    private final String name;

    MatrixRow(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  /**
   * The 3 ETP-5071 synthetic {@code matrix} rows — see each proxy id constant's own javadoc for
   * why they exist. Fallback display names only; per the class's existing {@code matrix} javadoc
   * convention, the frontend's own {@code menu.json} is expected to override both name and
   * category for a matched id in practice, so these are seen only if that override somehow fails
   * to apply.
   */
  private static final List<MatrixRow> PROXY_MATRIX_ROWS = List.of(
      new MatrixRow(FISCAL_MONITOR_PROXY_WINDOW_ID, "Fiscal Monitor"),
      new MatrixRow(TAX_MODELS_PROXY_WINDOW_ID, "Fiscal Models"),
      new MatrixRow(NOT_POSTED_DOCS_PROXY_PROCESS_ID, "Not Posted Documents"));

  /** JSON key for the full window × role permission matrix (ETP-4907). */
  private static final String MATRIX = "matrix";

  /** JSON key for the matrix's category buckets. */
  private static final String CATEGORIES = "categories";

  /** JSON key for a matrix window entry's per-role access map. */
  private static final String ACCESS = "access";

  /**
   * Category bucket used for a window whose top-level {@code AD_Menu} folder could not be
   * resolved (should not happen for a window Etendo GO actually exposes, but degrading to a
   * named bucket is safer than silently dropping the window from the matrix).
   */
  private static final String OTHER_CATEGORY = "Other";

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

  private static final String WINDOW_CATEGORY_SQL =
      "WITH RECURSIVE menu_tree AS ("
      + "  SELECT tn.node_id, tn.parent_id, m.ad_window_id, tn.node_id AS top_id"
      + "  FROM ad_treenode tn JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND tn.parent_id = '0' AND m.isactive = 'Y'"
      + "  UNION ALL"
      + "  SELECT tn.node_id, tn.parent_id, m.ad_window_id, mt.top_id"
      + "  FROM ad_treenode tn JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  JOIN menu_tree mt ON tn.parent_id = mt.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND m.isactive = 'Y'"
      + ") "
      + "SELECT DISTINCT ON (mt.ad_window_id) mt.ad_window_id, top.name"
      + " FROM menu_tree mt JOIN ad_menu top ON top.ad_menu_id = mt.top_id"
      + " WHERE mt.ad_window_id IN (:windowIds)"
      + " ORDER BY mt.ad_window_id, top.name";

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
    Map<String, Window> goWindowsById = resolveActiveEtendoGoWindowsById();
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

    if (adminRole != null) {
      addTenantRoleCard(adminRole, goWindowsById, roleCards, tierMapsByRoleId);
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
            composedTemplateUserIdsByUserId);
      } else {
        composedTemplateUserIdsByUserId = addSystemTemplateRoleCardIfResolvable(
            fixedRole.getValue(), clientId, goWindowsById, roleCards, tierMapsByRoleId,
            composedTemplateUserIdsByUserId);
      }
    }

    JSONArray roles = new JSONArray();
    for (JSONObject card : roleCards) {
      roles.put(card);
    }

    JSONObject result = new JSONObject();
    result.put(ROLES, roles);
    result.put(MATRIX, buildMatrix(goWindowsById, tierMapsByRoleId));
    return result;
  }

  /**
   * Resolves {@code role}'s window-tier map and appends its role card, mutating both {@code
   * roleCards} and {@code tierMapsByRoleId} — shared by the admin-role branch and the tenant-side
   * of the fixed-name loop in {@link #buildRolesOverview(String)}.
   */
  private void addTenantRoleCard(Role role, Map<String, Window> goWindowsById,
      List<JSONObject> roleCards, Map<String, Map<String, String>> tierMapsByRoleId) throws JSONException {
    Map<String, String> tiers = resolveWindowTierMap(role, goWindowsById.keySet());
    mergeProxyAccessTiers(role, tiers);
    tierMapsByRoleId.put(role.getId(), tiers);
    roleCards.add(buildRoleCardJson(role, tiers, goWindowsById, SOURCE_TENANT,
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
      Map<String, List<String>> composedTemplateUserIdsByUserId) throws JSONException {
    Map<String, String> tiers = resolveWindowTierMap(tenantRole, goWindowsById.keySet());
    mergeProxyAccessTiers(tenantRole, tiers);
    tierMapsByRoleId.put(tenantRole.getId(), tiers);

    Set<String> userIds = new LinkedHashSet<>(resolveActiveUserIds(tenantRole));
    Map<String, List<String>> composed = composedTemplateUserIdsByUserId != null
        ? composedTemplateUserIdsByUserId
        : new UserRoleCompositionService().getAppliedTemplateRoleIdsForClient(clientId);
    for (Map.Entry<String, List<String>> entry : composed.entrySet()) {
      if (entry.getValue().contains(templateId)) {
        userIds.add(entry.getKey());
      }
    }

    roleCards.add(buildRoleCardJson(tenantRole, tiers, goWindowsById, SOURCE_TENANT, userIds.size()));
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
    Map<String, String> tiers = resolveWindowTierMap(templateRole, goWindowsById.keySet());
    mergeProxyAccessTiers(templateRole, tiers);
    tierMapsByRoleId.put(templateRole.getId(), tiers);
    roleCards.add(buildRoleCardJson(templateRole, tiers, goWindowsById, SOURCE_SYSTEM_TEMPLATE, userCount));
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
      Map<String, Window> goWindowsById, String roleSource, int userCount) throws JSONException {
    JSONObject roleJson = new JSONObject();
    roleJson.put(ID, role.getId());
    roleJson.put(NAME, role.getName());
    roleJson.put(RAW_DESCRIPTION, role.getDescription());
    roleJson.put(IS_CLIENT_ADMIN, Boolean.TRUE.equals(role.isClientAdmin()));
    roleJson.put(ROLE_SOURCE, roleSource);
    roleJson.put(USER_COUNT, userCount);
    JSONArray windows = windowsJsonFromTierMap(tiers, goWindowsById);
    roleJson.put(WINDOWS, windows);
    roleJson.put(WINDOW_COUNT, windows.length());
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

  /**
   * Resolves {@code role}'s active {@code AD_Window_Access} rows into a window-id → tier map
   * ({@link #FULL} for {@code IsReadWrite = true}, {@link #READ_ONLY} otherwise), intersected
   * with {@code goWindowIds} — a role may hold native Etendo window-access rows for windows
   * Etendo GO never exposes (e.g. inherited/legacy grants), and those must not leak into this
   * "assigned windows" view. Client/organization filtering is explicitly disabled so this works
   * unchanged for a system-client ({@code AD_Client_ID = '0'}) role too — see the class javadoc's
   * system-template-fallback section for why that matters.
   */
  @SuppressWarnings("unchecked")
  private Map<String, String> resolveWindowTierMap(Role role, Set<String> goWindowIds) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));

    Map<String, String> tiers = new LinkedHashMap<>();
    for (WindowAccess access : (List<WindowAccess>) criteria.list()) {
      Window window = access.getWindow();
      if (window == null || !goWindowIds.contains(window.getId())) {
        continue;
      }
      tiers.put(window.getId(), Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY);
    }
    return tiers;
  }

  /**
   * ETP-5071 — merges the 3 proxy access tiers ({@link #FISCAL_MONITOR_PROXY_WINDOW_ID}, {@link
   * #TAX_MODELS_PROXY_WINDOW_ID}, {@link #NOT_POSTED_DOCS_PROXY_PROCESS_ID}) for {@code role}
   * into {@code tiers}, in place, right after {@code tiers} has been resolved from {@code role}'s
   * real Etendo-GO windows — so the {@code matrix}'s 3 synthetic {@link #PROXY_MATRIX_ROWS} rows
   * get real per-role access data instead of always reading {@link #NONE}.
   *
   * <p>Never pollutes {@link #windowsJsonFromTierMap(Map, Map)}'s output (each role card's own
   * {@code windows}/{@code windowCount}, which must stay exactly as before this change): {@link
   * #TAX_MODELS_PROXY_WINDOW_ID} and {@link #NOT_POSTED_DOCS_PROXY_PROCESS_ID} are never keys in
   * {@code goWindowsById}, so that method's existing {@code goWindowsById.get(...) == null} skip
   * already excludes them. {@link #FISCAL_MONITOR_PROXY_WINDOW_ID} (SII Monitor) IS a real,
   * separately-exposed Etendo GO window today, already included in {@code tiers} by the caller's
   * own {@code resolveWindowTierMap(role, goWindowsById.keySet())} call — re-resolving it here is
   * a harmless, idempotent re-derivation of the identical value, not a new entry.</p>
   */
  private void mergeProxyAccessTiers(Role role, Map<String, String> tiers) {
    tiers.putAll(resolveWindowTierMap(role, Set.of(FISCAL_MONITOR_PROXY_WINDOW_ID)));
    tiers.putAll(resolveWindowTierMap(role, Set.of(TAX_MODELS_PROXY_WINDOW_ID)));
    tiers.putAll(resolveProcessTierMap(role, NOT_POSTED_DOCS_PROXY_PROCESS_ID));
  }

  /**
   * ETP-5071 — the {@code OBUIAPP_Process_Access} / {@code Process} (OBUIAPP) equivalent of
   * {@link #resolveWindowTierMap(Role, Set)}, for a single target process id (there is only ever
   * one here — {@link #NOT_POSTED_DOCS_PROXY_PROCESS_ID} — so no set-intersection is needed the
   * way a window-id set requires). Same tier logic: {@link #FULL} for {@code IsEditableField =
   * true}, {@link #READ_ONLY} otherwise. Client/organization filtering is explicitly disabled to
   * match every other query in this class.
   *
   * @return a single-entry map ({@code obuiappProcessId -> tier}), or empty if {@code role} has
   *     no active grant for it — mirrors {@code resolveWindowTierMap}'s "absent means no grant"
   *     shape so both can be merged into the same tier map uniformly
   */
  @SuppressWarnings("unchecked")
  private Map<String, String> resolveProcessTierMap(Role role, String obuiappProcessId) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));

    Map<String, String> tiers = new LinkedHashMap<>();
    for (ProcessAccess access : (List<ProcessAccess>) criteria.list()) {
      Process process = access.getObuiappProcess();
      if (process == null || !obuiappProcessId.equals(process.getId())) {
        continue;
      }
      tiers.put(obuiappProcessId, Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY);
    }
    return tiers;
  }

  /**
   * Turns a window-id → tier map into the sorted-by-name {@code windows} JSON array a role card
   * carries.
   */
  private JSONArray windowsJsonFromTierMap(Map<String, String> tiers, Map<String, Window> goWindowsById)
      throws JSONException {
    List<JSONObject> windowJsons = new ArrayList<>();
    for (Map.Entry<String, String> entry : tiers.entrySet()) {
      Window window = goWindowsById.get(entry.getKey());
      if (window == null) {
        continue;
      }
      JSONObject windowJson = new JSONObject();
      windowJson.put(ID, window.getId());
      windowJson.put(NAME, window.getName());
      windowJson.put(TIER, entry.getValue());
      windowJsons.add(windowJson);
    }

    windowJsons.sort((a, b) -> {
      try {
        return a.getString(NAME).compareToIgnoreCase(b.getString(NAME));
      } catch (JSONException e) {
        return 0;
      }
    });

    JSONArray windows = new JSONArray();
    for (JSONObject windowJson : windowJsons) {
      windows.put(windowJson);
    }
    return windows;
  }

  /**
   * Resolves every distinct {@code AD_Window} backing an active, {@code SPEC_TYPE = 'W'}
   * {@code ETGO_SF_SPEC} — i.e. every window Etendo GO actually exposes today, keyed by id for
   * O(1) lookups while building both the per-role {@code windows} arrays and the {@code matrix}
   * — minus the windows Etendo GO serves over NEO/MCP but never shows in its UI
   * ({@link #UI_EXCLUDED_WINDOW_IDS}).
   *
   * @return the distinct UI-exposed windows, keyed by id (insertion order)
   */
  @SuppressWarnings("unchecked")
  private Map<String, Window> resolveActiveEtendoGoWindowsById() {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, SPEC_TYPE_WINDOW));

    Map<String, Window> windowsById = new LinkedHashMap<>();
    for (SFSpec spec : (List<SFSpec>) criteria.list()) {
      Window window = spec.getADWindow();
      String windowId = window == null ? null : window.getId();
      if (windowId != null && !UI_EXCLUDED_WINDOW_IDS.contains(windowId)) {
        windowsById.put(windowId, window);
      }
    }
    return windowsById;
  }

  /**
   * Builds the ETP-4907 {@code matrix}: every window in {@code goWindowsById} PLUS (ETP-5071)
   * the 3 {@link #PROXY_MATRIX_ROWS} synthetic windowless rows, grouped by top-level {@code
   * AD_Menu} category ({@link #resolveWindowCategories(Set)}), each with a per-role tri-state
   * {@code access} map built from {@code tierMapsByRoleId} — a role/row pair absent from that
   * role's tier map resolves to {@link #NONE}. Real windows are adapted to the common {@link
   * MatrixRow} shape purely for uniform grouping/sorting/rendering with the synthetic rows; their
   * own id/name/access JSON output is unchanged.
   */
  private JSONObject buildMatrix(Map<String, Window> goWindowsById,
      Map<String, Map<String, String>> tierMapsByRoleId) throws JSONException {
    List<MatrixRow> rows = new ArrayList<>();
    for (Window window : goWindowsById.values()) {
      rows.add(new MatrixRow(window.getId(), window.getName()));
    }
    rows.addAll(PROXY_MATRIX_ROWS);

    // Category lookup includes the 2 window-based proxy ids (SII Monitor, Tax Report) — both are
    // real AD_Window_IDs that may legitimately resolve a classic-AD-menu-tree category via the
    // same SQL. NOT_POSTED_DOCS_PROXY_PROCESS_ID is deliberately excluded — it is a process id,
    // not a window id, so the windowId-keyed SQL cannot resolve a category for it at all; it
    // falls back to OTHER_CATEGORY below. Whichever category any of these 3 synthetic rows lands
    // in is functionally irrelevant once the frontend's menu.json override applies (ETP-5071) —
    // that override always replaces both category and name for a matched id — so this is not
    // worth over-engineering further.
    Set<String> categoryLookupIds = new LinkedHashSet<>(goWindowsById.keySet());
    categoryLookupIds.add(FISCAL_MONITOR_PROXY_WINDOW_ID);
    categoryLookupIds.add(TAX_MODELS_PROXY_WINDOW_ID);
    Map<String, String> categoryByWindowId = resolveWindowCategories(categoryLookupIds);

    Map<String, List<MatrixRow>> rowsByCategory = new LinkedHashMap<>();
    for (MatrixRow row : rows) {
      String category = categoryByWindowId.getOrDefault(row.id, OTHER_CATEGORY);
      rowsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(row);
    }

    List<String> sortedCategories = new ArrayList<>(rowsByCategory.keySet());
    sortedCategories.sort(String.CASE_INSENSITIVE_ORDER);

    JSONArray categories = new JSONArray();
    for (String category : sortedCategories) {
      List<MatrixRow> rowsInCategory = rowsByCategory.get(category);
      rowsInCategory.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

      JSONArray windowsJson = new JSONArray();
      for (MatrixRow row : rowsInCategory) {
        JSONObject windowJson = new JSONObject();
        windowJson.put(ID, row.id);
        windowJson.put(NAME, row.name);

        JSONObject access = new JSONObject();
        for (Map.Entry<String, Map<String, String>> roleEntry : tierMapsByRoleId.entrySet()) {
          access.put(roleEntry.getKey(), roleEntry.getValue().getOrDefault(row.id, NONE));
        }
        windowJson.put(ACCESS, access);
        windowsJson.put(windowJson);
      }

      JSONObject categoryJson = new JSONObject();
      categoryJson.put(NAME, category);
      categoryJson.put(WINDOWS, windowsJson);
      categories.put(categoryJson);
    }

    JSONObject matrix = new JSONObject();
    matrix.put(CATEGORIES, categories);
    return matrix;
  }

  /**
   * Resolves each window id in {@code windowIds} to the name of its top-level {@code AD_Menu}
   * folder (tree {@code '10'}, the same menu tree {@link SFListMenu} walks) via one recursive-CTE
   * native query — a window linked from two different top-level folders deterministically picks
   * the alphabetically-first one ({@code DISTINCT ON} + {@code ORDER BY ... top.name}), and a
   * window absent from the result (no menu entry at all — not expected for a window Etendo GO
   * actually exposes, but not fatal either) is simply missing from the returned map; {@link
   * #buildMatrix(Map, Map)} falls back to {@link #OTHER_CATEGORY} for those.
   *
   * @param windowIds the Etendo GO window ids to resolve a category for
   * @return window id → top-level {@code AD_Menu.name}; never {@code null}, empty when {@code
   *     windowIds} is empty (short-circuits before touching the database)
   */
  @SuppressWarnings("unchecked")
  private Map<String, String> resolveWindowCategories(Set<String> windowIds) {
    if (windowIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Session session = OBDal.getInstance().getSession();
    NativeQuery<Object[]> query = session.createNativeQuery(WINDOW_CATEGORY_SQL);
    query.setParameterList("windowIds", windowIds);

    Map<String, String> categoryByWindowId = new LinkedHashMap<>();
    for (Object[] row : (List<Object[]>) query.getResultList()) {
      String windowId = row[0] == null ? null : row[0].toString();
      String category = row[1] == null ? null : row[1].toString();
      if (windowId != null && category != null) {
        categoryByWindowId.put(windowId, category);
      }
    }
    return categoryByWindowId;
  }
}
