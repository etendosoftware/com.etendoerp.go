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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Static helpers for access control and process resolution.
 */
public final class NeoAccessHelper {

  private static final Logger log = LogManager.getLogger(NeoAccessHelper.class);

  private static final String DEFAULT_POST_PROCESS_ID = "57496FB9CF9E4E8F847224017941570E";

  private NeoAccessHelper() {
  }

  /**
   * Checks whether the current role has (read) access to the given AD window.
   *
   * <p>Equivalent to {@link #hasWindowAccess(String, String)} with a {@code GET} method —
   * i.e. answers "is this window visible/reachable at all for the current role", regardless
   * of whether that role's access is read-only or full. Used by discovery/listing call sites
   * (MCP tool discovery, window sub-endpoint discovery) that don't have a concrete HTTP verb.</p>
   *
   * @param windowId the ID of the AD window to check
   * @return {@code true} if the current role has an active window-access record, is the
   *         System Administrator role, or is a client-admin role for the current client
   */
  public static boolean hasWindowAccess(String windowId) {
    return hasWindowAccess(windowId, "GET");
  }

  /**
   * Checks whether {@code role} has (read) access to the given AD window.
   *
   * <p>Same semantics as {@link #hasWindowAccess(String)} but takes an explicit role instead of
   * resolving it from the ambient {@link OBContext}. Use this overload when the caller has
   * already captured the role of interest up front — e.g. before entering
   * {@link OBContext#setAdminMode()} — so the access decision does not depend on whatever role
   * the ambient context happens to expose at the time of the check.</p>
   *
   * @param role the role to check (may be {@code null}, in which case access is denied)
   * @param windowId the ID of the AD window to check
   * @return {@code true} if {@code role} has an active window-access record, is the System
   *         Administrator role, or is a client-admin role for the current client
   */
  public static boolean hasWindowAccess(Role role, String windowId) {
    return hasWindowAccess(role, windowId, "GET");
  }

  /**
   * Checks whether the current role has access to the given AD window for the given HTTP
   * method, enforcing the read-only vs. full-access tiering declared on {@code AD_Window_Access}.
   *
   * <p>Resolution order:</p>
   * <ol>
   *   <li>No role assigned (role is {@code null}) → deny.</li>
   *   <li>System Administrator role ({@code "0"}) or a client-admin role
   *       ({@link Role#isClientAdmin()}) → always allow, any method.</li>
   *   <li>No active {@code WindowAccess} row for role+window → deny.</li>
   *   <li>Read methods ({@code GET}) → allow whenever an active row exists.</li>
   *   <li>Write methods ({@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}) → allow only
   *       when the row's {@code IsReadWrite} flag ({@link WindowAccess#isEditableField()})
   *       is {@code true}; a read-only row denies them.</li>
   * </ol>
   *
   * @param windowId the ID of the AD window to check
   * @param httpMethod the HTTP method of the current request (e.g. {@code GET}, {@code POST})
   * @return {@code true} if the current role is allowed to perform {@code httpMethod} on
   *         {@code windowId}
   */
  public static boolean hasWindowAccess(String windowId, String httpMethod) {
    return hasWindowAccess(resolveCurrentRole(), windowId, httpMethod);
  }

  /**
   * Checks whether {@code role} has access to the given AD window for the given HTTP method,
   * enforcing the read-only vs. full-access tiering declared on {@code AD_Window_Access}.
   *
   * <p>Same resolution order as {@link #hasWindowAccess(String, String)}, but operates on an
   * explicitly-supplied role rather than resolving it from the ambient {@link OBContext}. See
   * {@link #hasWindowAccess(Role, String)} for why an explicit role matters.</p>
   *
   * @param role the role to check (may be {@code null}, in which case access is denied)
   * @param windowId the ID of the AD window to check
   * @param httpMethod the HTTP method of the current request (e.g. {@code GET}, {@code POST})
   * @return {@code true} if {@code role} is allowed to perform {@code httpMethod} on {@code windowId}
   */
  public static boolean hasWindowAccess(Role role, String windowId, String httpMethod) {
    if (role == null) {
      return false;
    }
    if (isAdminOrClientAdmin(role)) {
      return true;
    }
    WindowAccess access = findActiveWindowAccess(windowId, role.getId());
    if (access == null) {
      return false;
    }
    if (isWriteMethod(httpMethod)) {
      return Boolean.TRUE.equals(access.isEditableField());
    }
    return true;
  }

  /**
   * Checks whether the current role has access to {@code spec} for the given HTTP method,
   * covering both ordinary window specs and windowless/custom "combination" specs
   * (ETP-4510 BUG-3).
   *
   * <p>Before this fix, {@code spec.getADWindow() == null} skipped the access check
   * entirely for every caller — including a request with no role assigned at all, which
   * contradicts "a user with no role assigned is denied on every window." This method
   * closes that gap in three tiers, in priority order:</p>
   * <ol>
   *   <li><b>No role assigned → always deny.</b> Checked first and unconditionally,
   *       regardless of whether the spec has a window or not.</li>
   *   <li><b>Spec has a directly linked {@code AD_Window}</b> → delegates straight to
   *       {@link #hasWindowAccess(String, String)} for that window.</li>
   *   <li><b>Windowless spec ({@code spec.getADWindow() == null}):</b> delegates to
   *       {@link #hasAccessToConstituentWindows(SFSpec, String)} — see that method for the
   *       "combination of windows" mechanism and its permissive fallback. As of ETP-4596
   *       the only specs still relying on that fallback here (no {@code AD_Process}, no
   *       constituent {@code AD_TAB_ID} data) are the windowless {@code "W"}-type specs
   *       {@code not-posted-documents} and {@code dashboard} — every windowless "R" (report)
   *       spec goes through {@link #hasReportSpecAccess(SFSpec, String)} instead, which
   *       shares this same constituent-window helper.</li>
   * </ol>
   *
   * @param spec the spec to check (may be {@code null}, in which case access is denied)
   * @param httpMethod the HTTP method of the current request (e.g. {@code GET}, {@code POST})
   * @return {@code true} if the current role is allowed to perform {@code httpMethod}
   *         against {@code spec}
   */
  public static boolean hasWindowAccessForSpec(SFSpec spec, String httpMethod) {
    if (spec == null || resolveCurrentRole() == null) {
      return false;
    }
    Window window = spec.getADWindow();
    if (window != null) {
      return hasWindowAccess(window.getId(), httpMethod);
    }
    return hasAccessToConstituentWindows(spec, httpMethod);
  }

  /**
   * Checks whether the current role has access to a report-type ({@code spec_type = "R"})
   * spec for the given HTTP method (ETP-4596).
   *
   * <p>Before this fix, report specs were never routed through any window/process access
   * check at all: {@code NeoRequestRouter}'s report dispatch, MCP tool discovery/execution
   * ({@code ToolRegistry}, {@code McpToolRouterSupport}), and spec-discovery listing
   * ({@code NeoDiscoveryHelper}) all either skipped the check entirely or fell through a
   * {@code spec.getProcess() == null} guard that is always true for NEO-native report
   * handlers (they have no linked classic {@code AD_Process}) — so every "R" spec was
   * reachable by any authenticated role regardless of {@code AD_Window_Access}. This method
   * is the single gate those 4 call sites now delegate to for "R" specs, in tiers:</p>
   * <ol>
   *   <li><b>No role assigned → always deny.</b></li>
   *   <li><b>Spec has a real, linked {@code AD_Process}</b>
   *       ({@code spec.getProcess() != null}) → delegates straight to
   *       {@link #hasProcessAccess(String)}. This is the correct equivalent when a report
   *       genuinely wraps a classic process/report definition — e.g. once
   *       {@code tax-report}/{@code inventory-stock-report} get a confirmed, FK-verified
   *       {@code AD_Process} linked, they gate here with zero further code changes.</li>
   *   <li><b>No linked {@code AD_Process}</b> — delegates to the same
   *       {@link #hasAccessToConstituentWindows(SFSpec, String)} "combination of windows"
   *       check used by {@link #hasWindowAccessForSpec(SFSpec, String)} for windowless
   *       {@code "W"} specs, keyed off each active/included {@code SFEntity}'s
   *       {@code AD_TAB_ID}: {@code financial-accounts-page},
   *       {@code financial-account-transactions}, {@code bank-statements},
   *       {@code bank-reconciliation} and {@code financial-account-bank-connection} gate on
   *       their constituent window (the classic "Financial Account" window) this way; specs
   *       this ticket does not touch ({@code aging-receivable}, {@code tax-report},
   *       {@code inventory-stock-report}) have no {@code AD_TAB_ID} data yet and keep
   *       today's permissive behavior unchanged.</li>
   * </ol>
   *
   * @param spec the spec to check (may be {@code null}, in which case access is denied)
   * @param httpMethod the HTTP method of the current request (e.g. {@code GET}, {@code POST})
   * @return {@code true} if the current role is allowed to perform {@code httpMethod}
   *         against {@code spec}
   */
  public static boolean hasReportSpecAccess(SFSpec spec, String httpMethod) {
    if (spec == null || resolveCurrentRole() == null) {
      return false;
    }
    Process process = spec.getProcess();
    if (process != null) {
      return hasProcessAccess(process.getId());
    }
    return hasAccessToConstituentWindows(spec, httpMethod);
  }

  /**
   * Shared "combination of windows" check used by both
   * {@link #hasWindowAccessForSpec(SFSpec, String)} (windowless {@code "W"} specs) and
   * {@link #hasReportSpecAccess(SFSpec, String)} (process-less {@code "R"} specs): every
   * distinct {@link Tab#getWindow()} reachable through the spec's active/included
   * {@code SFEntity} rows (via {@link #resolveConstituentWindowIds(SFSpec)}) must be
   * accessible for {@code httpMethod} — the role needs read access to all of them for a
   * read, or full/write access to all of them for a write. Deny if any one is inaccessible.
   *
   * <p>When NO entity has a populated {@code AD_TAB_ID} (no combination data exists at all)
   * this falls back to a permissive allow: there is no per-window data to check against for
   * such a spec, and {@code AD_Window_Access} provisioning was never modeled for it either;
   * skipping the check (rather than denying everyone) avoids a hard regression for the
   * specs that still have no combination data today.</p>
   *
   * @param spec the spec whose constituent windows are checked
   * @param httpMethod the HTTP method of the current request
   * @return {@code true} when every constituent window is accessible for {@code httpMethod},
   *         or when the spec has no combination data at all
   */
  private static boolean hasAccessToConstituentWindows(SFSpec spec, String httpMethod) {
    List<String> constituentWindowIds = resolveConstituentWindowIds(spec);
    if (constituentWindowIds.isEmpty()) {
      return true;
    }
    for (String windowId : constituentWindowIds) {
      if (!hasWindowAccess(windowId, httpMethod)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Resolves the distinct {@code AD_Window} IDs reachable through {@code spec}'s
   * "combination of windows" — every active, included {@link SFEntity} of the spec whose
   * {@code AD_TAB_ID} is populated, mapped to its {@link Tab#getWindow()}. Shared by
   * {@link #hasWindowAccessForSpec(SFSpec, String)} and
   * {@link #hasReportSpecAccess(SFSpec, String)} via
   * {@link #hasAccessToConstituentWindows(SFSpec, String)}.
   *
   * @param spec the spec whose constituent windows are needed
   * @return the distinct window IDs (insertion order), or an empty list when no entity of
   *         this spec has a populated {@code AD_TAB_ID}
   */
  private static List<String> resolveConstituentWindowIds(SFSpec spec) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    Set<String> windowIds = new LinkedHashSet<>();
    for (SFEntity entity : criteria.list()) {
      Tab tab = entity.getADTab();
      if (tab != null && tab.getWindow() != null) {
        windowIds.add(tab.getWindow().getId());
      }
    }
    return new ArrayList<>(windowIds);
  }

  /**
   * Checks whether the current role has access to the given AD process.
   *
   * <p>Process access remains binary (no read/write tiering) — any active
   * {@code ProcessAccess} row grants full access to execute the process.</p>
   *
   * @param processId the ID of the AD process to check
   * @return {@code true} if the current role has an active process-access record, or if the
   *         role is the System Administrator role or a client-admin role
   */
  public static boolean hasProcessAccess(String processId) {
    return hasProcessAccess(resolveCurrentRole(), processId);
  }

  /**
   * Checks whether {@code role} has access to the given AD process.
   *
   * <p>Same semantics as {@link #hasProcessAccess(String)}, but operates on an explicitly-supplied
   * role rather than resolving it from the ambient {@link OBContext}. See
   * {@link #hasWindowAccess(Role, String)} for why an explicit role matters.</p>
   *
   * @param role the role to check (may be {@code null}, in which case access is denied)
   * @param processId the ID of the AD process to check
   * @return {@code true} if {@code role} has an active process-access record, is the System
   *         Administrator role, or is a client-admin role for the current client
   */
  public static boolean hasProcessAccess(Role role, String processId) {
    if (role == null) {
      return false;
    }
    if (isAdminOrClientAdmin(role)) {
      return true;
    }
    String roleId = role.getId();
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_PROCESS + ".id", processId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  /**
   * Resolves the current role from the {@link OBContext}, tolerating a missing context or
   * a request with no role assigned.
   *
   * <p>Public so callers that must capture the role explicitly before entering
   * {@link OBContext#setAdminMode()} — e.g. {@code SFWindowAccessMap} and {@code SFListMenu},
   * which both need the role resolved from the ambient context up front, never re-resolved once
   * admin mode is active — can reuse this exact resolution instead of each keeping its own
   * private copy.</p>
   *
   * @return the current {@link Role}, or {@code null} if there is no context or no role
   */
  public static Role resolveCurrentRole() {
    OBContext context = OBContext.getOBContext();
    return context == null ? null : context.getRole();
  }

  /**
   * Whether {@code role} should bypass window/process access checks entirely: the true
   * System Administrator role ({@code "0"}), or a per-client "GO Admin" role
   * ({@code AD_Role.is_client_admin = 'Y'}).
   *
   * <p>Public so callers outside this class that need the same admin/client-admin bypass
   * semantics can reuse this exact resolution instead of re-implementing it — e.g.
   * {@code SFWindowAccessMap} (ETP-4520), which reports "full access to every window" and
   * "every capability true" for these roles instead of resolving them one {@code AD_Window_Access}
   * row at a time; and {@code SFRolesOverview} (ETP-4513), a cross-role aggregate webhook that
   * must answer "is the CALLER an admin" rather than "does the caller's role reach window X".</p>
   *
   * @param role the role to evaluate (never {@code null})
   * @return {@code true} if this role always has full access
   */
  public static boolean isAdminOrClientAdmin(Role role) {
    return "0".equals(role.getId()) || Boolean.TRUE.equals(role.isClientAdmin());
  }

  /**
   * Looks up the single active {@code WindowAccess} row for the given role+window, if any.
   *
   * @param windowId the ID of the AD window to check
   * @param roleId the ID of the role to check
   * @return the active {@link WindowAccess} row, or {@code null} if none exists
   */
  private static WindowAccess findActiveWindowAccess(String windowId, String roleId) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW + ".id", windowId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    List<WindowAccess> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Whether {@code httpMethod} is a write/mutating HTTP verb that requires full
   * (not read-only) window access.
   *
   * @param httpMethod the HTTP method of the current request
   * @return {@code true} for {@code POST}, {@code PUT}, {@code PATCH}, or {@code DELETE}
   *         (case-insensitive)
   */
  private static boolean isWriteMethod(String httpMethod) {
    return "POST".equalsIgnoreCase(httpMethod)
        || "PUT".equalsIgnoreCase(httpMethod)
        || "PATCH".equalsIgnoreCase(httpMethod)
        || "DELETE".equalsIgnoreCase(httpMethod);
  }

  /**
   * Checks whether the current role has access to the given OBUIAPP process definition.
   *
   * @param processId the ID of the OBUIAPP process definition to check
   * @return {@code true} if the current role has an active OBUIAPP process-access record,
   *         or if the role is the System Administrator role or a client-admin role;
   *         {@code false} if no role is assigned to the current context
   */
  public static boolean hasObuiappProcessAccess(String processId) {
    return hasObuiappProcessAccess(resolveCurrentRole(), processId);
  }

  /**
   * Checks whether {@code role} has access to the given OBUIAPP process definition.
   *
   * <p>Same semantics as {@link #hasObuiappProcessAccess(String)}, but operates on an
   * explicitly-supplied role rather than resolving it from the ambient {@link OBContext}. See
   * {@link #hasWindowAccess(Role, String)} for why an explicit role matters.</p>
   *
   * @param role the role to check (may be {@code null}, in which case access is denied)
   * @param processId the ID of the OBUIAPP process definition to check
   * @return {@code true} if {@code role} has an active OBUIAPP process-access record, is the
   *         System Administrator role, or is a client-admin role for the current client
   */
  public static boolean hasObuiappProcessAccess(Role role, String processId) {
    if (role == null) {
      return false;
    }
    if (isAdminOrClientAdmin(role)) {
      return true;
    }
    String roleId = role.getId();
    OBCriteria<org.openbravo.client.application.ProcessAccess> criteria = OBDal.getInstance()
        .createCriteria(org.openbravo.client.application.ProcessAccess.class);
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_OBUIAPPPROCESS + ".id",
        processId));
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  /**
   * Resolves the default post (accounting) process used for the Posted button.
   *
   * @return the default {@code Process} instance, or {@code null} if it cannot be found
   */
  public static org.openbravo.client.application.Process resolveDefaultPostProcess() {
    try {
      return OBDal.getInstance().get(
          org.openbravo.client.application.Process.class, DEFAULT_POST_PROCESS_ID);
    } catch (Exception e) {
      log.debug("Default Post process not found: {}", DEFAULT_POST_PROCESS_ID);
      return null;
    }
  }

  /**
   * Resolve the shared fallback OBUIAPP process for button columns that do not declare one explicitly.
   *
   * @param column the AD_Column being evaluated
   * @return the fallback OBUIAPP process when the column matches the shared Posted convention,
   *         or {@code null} when no fallback applies
   */
  public static org.openbravo.client.application.Process resolveFallbackObuiappProcess(
      org.openbravo.model.ad.datamodel.Column column) {
    if (column == null || !"Posted".equals(column.getDBColumnName())) {
      return null;
    }
    return resolveDefaultPostProcess();
  }

  /**
   * Returns the AD process linked to the given spec.
   *
   * @param spec the Schema Forge spec whose associated process is needed
   * @return the {@link org.openbravo.model.ad.ui.Process} configured on the spec, or {@code null} if none
   */
  public static Process resolveProcess(SFSpec spec) {
    return spec.getProcess();
  }
}
