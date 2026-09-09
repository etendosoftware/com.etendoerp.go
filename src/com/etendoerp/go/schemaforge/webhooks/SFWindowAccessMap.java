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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that hands the current authenticated user/role its window-access tier and
 * accounting-visibility capability up front, before the frontend renders anything — the
 * proactive counterpart to {@link NeoAccessHelper#hasWindowAccess} (§7 of {@code
 * docs/neo-headless.md}), which only enforces access reactively per-request.
 *
 * <p>The current role is captured once, at the very top of {@link #get(Map, Map)}, before
 * {@link OBContext#setAdminMode()} is entered — the same convention {@link SFListMenu} follows
 * and for the same reason: admin mode is only used to bypass row-level security on the
 * underlying queries, never to decide access. A request with no role assigned gets both maps
 * empty, without even touching the database.</p>
 *
 * <p>Resolution order (mirrors {@link NeoAccessHelper#hasWindowAccess(Role, String, String)}):</p>
 * <ol>
 *   <li>No role assigned → {@code {"windowAccess": {}, "capabilities": {}}}.</li>
 *   <li>Admin/client-admin bypass ({@link NeoAccessHelper#isAdminOrClientAdmin(Role)}) → every
 *       window Etendo GO exposes resolves to {@code "full"}: the union of (a) every distinct
 *       {@code AD_Window} backing an active, {@code SPEC_TYPE = 'W'} {@code ETGO_SF_SPEC}, and
 *       (b) every distinct {@code AD_Window} with at least one active {@code AD_Window_Access}
 *       row for ANY role (ETP-5240 — some windows, e.g. the report-viewer "permission-anchor"
 *       windows Financial Reports/Smart Scan/Inventory Stock Report, are granted directly via
 *       {@code AD_Window_Access} and never get an {@code ETGO_SF_SPEC} row at all, since they
 *       have no real Etendo GO spec behind them; without (b) the admin bypass would miss them
 *       and admin/client-admin would see "none" for a window a real role can already open).
 *       {@code capabilities.showAccountingFields} / {@code capabilities.isAdminOrClientAdmin}
 *       are both always {@code true}.</li>
 *   <li>Otherwise, for every active {@code AD_Window_Access} row the role has: {@code
 *       IsReadWrite = true} → {@code "full"}; {@code IsReadWrite = false} → {@code "read-only"}.
 *       A window with no active row is simply absent from the map — the frontend treats a
 *       missing key as {@code "none"}. {@code capabilities.showAccountingFields} is read
 *       directly off {@code AD_Role.EM_ETGO_Show_Acct_Fields} for the resolved role, and
 *       {@code capabilities.isAdminOrClientAdmin} is {@code false} (this branch is only reached
 *       when the bypass check above already failed).</li>
 * </ol>
 *
 * <p>{@code capabilities.isAdminOrClientAdmin} (ETP-4513) is the proactive signal the frontend
 * uses to decide whether to show admin-only settings entries — e.g. the "Configuración &gt;
 * Roles" menu item, backed by {@code SFRolesOverview} — up front, instead of showing them to
 * every role and handling denial only once the page itself loads.</p>
 *
 * GET /webhooks/SFWindowAccessMap
 */
public class SFWindowAccessMap extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFWindowAccessMap.class);

  /** JSON key used for the per-window access-tier map. */
  private static final String WINDOW_ACCESS = "windowAccess";

  /** JSON key used for the capabilities map. */
  private static final String CAPABILITIES = "capabilities";

  /** JSON key used for the accounting-visibility capability. */
  private static final String SHOW_ACCOUNTING_FIELDS = "showAccountingFields";

  /** JSON key used for the admin/client-admin capability (ETP-4513). */
  private static final String IS_ADMIN_OR_CLIENT_ADMIN = "isAdminOrClientAdmin";

  /** Access-tier value for a role with full (read+write) access to a window. */
  private static final String FULL = "full";

  /** Access-tier value for a role with read-only access to a window. */
  private static final String READ_ONLY = "read-only";

  /** {@code ETGO_SF_SPEC.SPEC_TYPE} value identifying a window/CRUD spec. */
  private static final String SPEC_TYPE_WINDOW = "W";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE entering admin mode — see the class javadoc and
    // SFListMenu's identical convention for why: access decisions must always be made against
    // the role actually resolved for this request, never against whatever the ambient
    // OBContext happens to expose once admin mode is active.
    Role currentRole = NeoAccessHelper.resolveCurrentRole();

    if (currentRole == null) {
      responseVars.put("result", emptyResult().toString());
      return;
    }

    OBContext.setAdminMode();
    try {
      JSONObject result = buildAccessMap(currentRole);
      responseVars.put("result", result.toString());
    } catch (Exception e) {
      log.error("Error in SFWindowAccessMap", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Builds the empty result used when the current request has no role assigned.
   */
  private static JSONObject emptyResult() {
    try {
      JSONObject result = new JSONObject();
      result.put(WINDOW_ACCESS, new JSONObject());
      result.put(CAPABILITIES, new JSONObject());
      return result;
    } catch (JSONException e) {
      // JSONObject#put never throws for a non-null key; unreachable in practice.
      throw new IllegalStateException("Unable to build empty access-map result", e);
    }
  }

  /**
   * Builds the {@code windowAccess}/{@code capabilities} result for {@code role}, branching on
   * the admin/client-admin bypass first (see class javadoc for the full resolution order).
   */
  private JSONObject buildAccessMap(Role role) throws JSONException {
    JSONObject windowAccess = new JSONObject();
    JSONObject capabilities = new JSONObject();

    if (NeoAccessHelper.isAdminOrClientAdmin(role)) {
      Set<String> windowIds = new LinkedHashSet<>(resolveActiveEtendoGoWindowIds());
      windowIds.addAll(resolveWindowsWithAnyGrant());
      for (String windowId : windowIds) {
        windowAccess.put(windowId, FULL);
      }
      capabilities.put(SHOW_ACCOUNTING_FIELDS, true);
      capabilities.put(IS_ADMIN_OR_CLIENT_ADMIN, true);
    } else {
      populateWindowAccessForRole(role, windowAccess);
      capabilities.put(SHOW_ACCOUNTING_FIELDS, resolveShowAccountingFields(role));
      capabilities.put(IS_ADMIN_OR_CLIENT_ADMIN, false);
    }

    JSONObject result = new JSONObject();
    result.put(WINDOW_ACCESS, windowAccess);
    result.put(CAPABILITIES, capabilities);
    return result;
  }

  /**
   * Resolves every distinct {@code AD_Window} backing an active, {@code SPEC_TYPE = 'W'}
   * {@code ETGO_SF_SPEC} — i.e. every window Etendo GO actually exposes today. Used only for
   * the admin/client-admin bypass, which has no {@code AD_Window_Access} rows of its own to
   * enumerate.
   *
   * @return the distinct window IDs (insertion order)
   */
  @SuppressWarnings("unchecked")
  private Set<String> resolveActiveEtendoGoWindowIds() {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, SPEC_TYPE_WINDOW));

    Set<String> windowIds = new LinkedHashSet<>();
    for (SFSpec spec : (List<SFSpec>) criteria.list()) {
      Window window = spec.getADWindow();
      if (window != null) {
        windowIds.add(window.getId());
      }
    }
    return windowIds;
  }

  /**
   * Resolves every distinct {@code AD_Window} with at least one active {@code AD_Window_Access}
   * row for ANY role — i.e. every window Etendo GO exposes via a real permission grant,
   * regardless of whether it has a backing {@code ETGO_SF_SPEC}. Used only for the admin/
   * client-admin bypass, unioned with {@link #resolveActiveEtendoGoWindowIds()}, so that
   * "permission-anchor" windows (report-viewer pages such as Financial Reports, Smart Scan,
   * Inventory Stock Report — granted straight via {@code AD_Window_Access} to gate access, with
   * no {@code ETGO_SF_SPEC} row behind them at all) are not silently dropped from the admin's
   * map (ETP-5240). Mirrors {@link #populateWindowAccessForRole(Role, JSONObject)}'s query shape
   * minus the per-role restriction.
   *
   * @return the distinct window IDs (insertion order) with at least one active grant
   */
  @SuppressWarnings("unchecked")
  private Set<String> resolveWindowsWithAnyGrant() {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));

    Set<String> windowIds = new LinkedHashSet<>();
    for (WindowAccess access : (List<WindowAccess>) criteria.list()) {
      Window window = access.getWindow();
      if (window != null) {
        windowIds.add(window.getId());
      }
    }
    return windowIds;
  }

  /**
   * Populates {@code windowAccess} with one entry per active {@code AD_Window_Access} row
   * {@code role} has, resolving the tier from {@code IsReadWrite} the same way {@link
   * NeoAccessHelper#hasWindowAccess(Role, String, String)} does. A window with no active row is
   * simply absent from the map.
   */
  @SuppressWarnings("unchecked")
  private void populateWindowAccessForRole(Role role, JSONObject windowAccess) throws JSONException {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));

    for (WindowAccess access : (List<WindowAccess>) criteria.list()) {
      Window window = access.getWindow();
      if (window == null) {
        continue;
      }
      String tier = Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY;
      windowAccess.put(window.getId(), tier);
    }
  }

  /**
   * Reads {@code AD_Role.EM_ETGO_Show_Acct_Fields} directly for {@code role} via native SQL,
   * rather than through the DAL entity model — this extension column was added straight to the
   * physical table (ETP-4520) and is not yet mapped as a typed entity property, so a native
   * query is the safe, immediately-functional way to read it without requiring an entity-model
   * regeneration first.
   *
   * @param role the role to resolve the capability for (never the admin/client-admin bypass,
   *             which always answers {@code true} without querying this column)
   * @return {@code true} when the column is {@code 'Y'} for this role; {@code false} otherwise
   *         (including when the role cannot be found, or the column is {@code 'N'}/unset)
   */
  private static boolean resolveShowAccountingFields(Role role) {
    Session session = OBDal.getInstance().getSession();
    // Result type is deliberately Object, not String: Hibernate maps a PostgreSQL char(1) column
    // to Character for a plain scalar native query (no explicit addScalar type), and a
    // generics-erased List<String>.get(0) throws ClassCastException: Character cannot be cast to
    // String on any real row (confirmed live, ETP-4515 onboarding — same query shape).
    NativeQuery<Object> query = session.createNativeQuery(
        "SELECT em_etgo_show_acct_fields FROM ad_role WHERE ad_role_id = :roleId");
    query.setParameter("roleId", role.getId());
    List<Object> results = query.getResultList();
    return !results.isEmpty() && results.get(0) != null && "Y".equals(results.get(0).toString());
  }
}
