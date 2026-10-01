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
package com.etendoerp.go.roles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-5565 — propagates the system template roles' access rows to the personal roles that inherit
 * them, on the live database.
 *
 * <p><b>Why this exists.</b> Core's {@code RoleInheritanceManager} copies a template's accesses
 * onto every role that inherits it, but it only reacts to Hibernate events. The templates are
 * maintained by {@code EnsureSystemRoleTemplatesScript}, a JDBC module script, so a template
 * change never reached the personal roles composed before it: existing users kept the old
 * permissions and lacked the new ones (ETP-5116 QA, CP-1). On production the script does not even
 * run on the live database: the deploy runs it on a clone and ships a data delta that only carries
 * system-client rows, so personal roles (tenant clients) can only be realigned at runtime.</p>
 *
 * <p><b>The rule: recompute, never replay.</b> For every personal role P and every element E
 * (window, classic process, OBUIAPP process), the desired inherited row is computed from P's
 * current active inheritances of active system templates that hold an ACTIVE grant on E:
 * <ul>
 *   <li>none → P must have no inherited row for E (removed);</li>
 *   <li>otherwise → level = the most permissive among them, source ({@code Inherited_From}) = the
 *       highest-{@code SeqNo} one among those granting that level, row active, client/org = P's
 *       own. That is the end state role composition already guarantees (the ETP-5507
 *       {@code AbstractTemplateAssignmentIntegrationTest#assertMatchesTemplateUnion} invariant: a
 *       row's source always justifies its level).</li>
 * </ul>
 * Rows with {@code Inherited_From IS NULL} (manual grants) are never touched, and they block the
 * insert of an inherited row for the same element, so a manual grant always wins. The sweep is
 * order-independent and idempotent: a second run changes nothing.</p>
 *
 * <p><b>Scope.</b> Only system templates ({@code IsTemplate='Y' AND AD_Client_ID='0'}). A role
 * that also inherits from any other role is skipped entirely ({@link #eligibleRoles}), so its
 * rows are left to core's own propagation instead of being half-managed by two rules.</p>
 *
 * <p>Everything here is native SQL on the current DAL session and never commits: callers own the
 * transaction (see {@code TemplateRoleAccessStartup} for chunking, locking and the lease, and
 * {@link UserRoleCompositionService} for the per-composition sweep).</p>
 */
public class TemplateAccessPropagationService {

  /**
   * Version of the fingerprint and sweep rule. Bump it when either changes, so every live
   * database re-sweeps once. A task never overwrites a fingerprint stored by a newer version.
   */
  public static final int ALGO_VERSION = 1;

  /**
   * Sweeps nothing: for plain unit tests that mock {@link OBDal} without a real session (the
   * sweep is native SQL), same idea as {@link UserRoleWriteLock#NO_OP}.
   */
  static final TemplateAccessPropagationService NO_OP = new TemplateAccessPropagationService() {
    @Override
    public SweepCounts sweepRole(String roleId) {
      return new SweepCounts();
    }
  };

  /** System user stamped on the rows this service writes. */
  private static final String SYSTEM_USER_ID = "0";

  private static final String SYSTEM_TEMPLATES_SQL = "SELECT tr.ad_role_id FROM ad_role tr "
      + "WHERE tr.istemplate = 'Y' AND tr.ad_client_id = '0'";

  private static final String ROLES_PARAM = "roles";

  /** One of the three access tables the templates hold rows in. */
  enum AccessTable {
    WINDOW("ad_window_access", "ad_window_access_id", "ad_window_id", "W"),
    PROCESS("ad_process_access", "ad_process_access_id", "ad_process_id", "P"),
    OBUIAPP_PROCESS("obuiapp_process_access", "obuiapp_process_access_id", "obuiapp_process_id",
        "O");

    final String table;
    final String pk;
    final String element;
    final String fingerprintTag;

    AccessTable(String table, String pk, String element, String fingerprintTag) {
      this.table = table;
      this.pk = pk;
      this.element = element;
      this.fingerprintTag = fingerprintTag;
    }

    String sql(String template) {
      return template.replace("{t}", table).replace("{pk}", pk).replace("{el}", element);
    }
  }

  /** Rows changed by a sweep, per kind of change. */
  public static final class SweepCounts {
    private int removed;
    private int updated;
    private int inserted;

    public int getRemoved() {
      return removed;
    }

    public int getUpdated() {
      return updated;
    }

    public int getInserted() {
      return inserted;
    }

    public int total() {
      return removed + updated + inserted;
    }

    public void add(SweepCounts other) {
      removed += other.removed;
      updated += other.updated;
      inserted += other.inserted;
    }
  }

  /** Classification of one active process grant of a template, for the post-change diagnostic. */
  public static final class ProcessGrantDiagnostic {
    /** Button on a window the template has full access to. */
    public static final String BUTTON_ON_FULL_WINDOW = "button on a full window";
    /** A standalone grant declared in {@link TemplateRoleWindowAccess}. */
    public static final String STANDALONE = "standalone in code";
    /** Button only on windows the template has read-only access to. */
    public static final String BUTTON_ON_READ_ONLY_WINDOW = "button only on a read-only window";
    /** None of the above. */
    public static final String UNEXPLAINED = "unexplained";

    private final String templateId;
    private final String kind;
    private final String processId;
    private final String processName;
    private final String category;

    ProcessGrantDiagnostic(String templateId, String kind, String processId, String processName,
        String category) {
      this.templateId = templateId;
      this.kind = kind;
      this.processId = processId;
      this.processName = processName;
      this.category = category;
    }

    public String getTemplateId() {
      return templateId;
    }

    /** {@code "classic"} or {@code "obuiapp"}. */
    public String getKind() {
      return kind;
    }

    public String getProcessId() {
      return processId;
    }

    public String getProcessName() {
      return processName;
    }

    public String getCategory() {
      return category;
    }
  }

  // ---------------------------------------------------------------------
  // Fingerprints
  // ---------------------------------------------------------------------

  /**
   * md5 per system template over its active, distinct grants ({@code W|P|O:element:isreadwrite},
   * ordered), so it changes exactly when the access a template hands out changes. Inactive rows
   * do not count, so purging them never changes it. Costs one query, independent of the number of
   * personal roles.
   *
   * @return template id → fingerprint, for every system template (an empty template has the md5
   *     of the empty string)
   */
  public Map<String, String> fingerprints() {
    StringBuilder grants = new StringBuilder();
    for (AccessTable access : AccessTable.values()) {
      if (grants.length() > 0) {
        grants.append(" UNION ");
      }
      grants.append(access.sql("SELECT g.ad_role_id, '" + access.fingerprintTag
          + ":' || g.{el} || ':' || g.isreadwrite AS k FROM {t} g WHERE g.isactive = 'Y' "
          + "AND g.ad_role_id IN (" + SYSTEM_TEMPLATES_SQL + ")"));
    }
    String sql = "SELECT t.ad_role_id, md5(COALESCE(string_agg(x.k, '|' ORDER BY x.k), '')) "
        + "FROM ad_role t LEFT JOIN (" + grants + ") x ON x.ad_role_id = t.ad_role_id "
        + "WHERE t.istemplate = 'Y' AND t.ad_client_id = '0' GROUP BY t.ad_role_id "
        + "ORDER BY t.ad_role_id";
    Map<String, String> result = new LinkedHashMap<>();
    for (Object[] row : rows(session().createNativeQuery(sql))) {
      result.put((String) row[0], (String) row[1]);
    }
    return result;
  }

  // ---------------------------------------------------------------------
  // Which roles to sweep
  // ---------------------------------------------------------------------

  /**
   * Personal roles with an active inheritance of any of {@code templateIds}, filtered by {@link
   * #eligibleRoles}, sorted by id.
   */
  public List<String> rolesInheriting(Collection<String> templateIds) {
    if (templateIds.isEmpty()) {
      return Collections.emptyList();
    }
    NativeQuery<?> query = session().createNativeQuery(
        "SELECT DISTINCT i.ad_role_id FROM ad_role_inheritance i "
            + "WHERE i.isactive = 'Y' AND i.inherit_from IN (:templates) ORDER BY 1");
    query.setParameterList("templates", templateIds);
    return eligibleRoles(strings(query));
  }

  /**
   * Roles holding an inherited copy (from a system template) that is inactive, or whose source
   * template no longer holds an active grant for that element — what a composition that bypassed
   * {@link #sweepRole} (Etendo Classic's Role window, core's "Recalculate Permissions") can leave
   * behind while an inactive template row exists. Cheap enough for every periodic tick at the
   * current scale. Filtered by {@link #eligibleRoles}, sorted by id.
   */
  public List<String> rolesWithStaleCopies() {
    StringBuilder sql = new StringBuilder();
    for (AccessTable access : AccessTable.values()) {
      if (sql.length() > 0) {
        sql.append(" UNION ");
      }
      sql.append(access.sql("SELECT a.ad_role_id FROM {t} a "
          + "WHERE a.inherited_from IN (" + SYSTEM_TEMPLATES_SQL + ") "
          + "AND (a.isactive <> 'Y' OR NOT EXISTS (SELECT 1 FROM {t} s "
          + "WHERE s.ad_role_id = a.inherited_from AND s.{el} = a.{el} AND s.isactive = 'Y'))"));
    }
    sql.append(" ORDER BY 1");
    return eligibleRoles(strings(session().createNativeQuery(sql.toString())));
  }

  /**
   * {@code roleIds} minus the roles that are themselves templates and the roles with an active
   * inheritance of anything that is not a system template (left to core's own propagation, see
   * the class javadoc). Keeps the input order.
   */
  List<String> eligibleRoles(List<String> roleIds) {
    if (roleIds.isEmpty()) {
      return roleIds;
    }
    NativeQuery<?> query = session().createNativeQuery(
        "SELECT r.ad_role_id FROM ad_role r WHERE r.ad_role_id IN (:roles) "
            + "AND (r.istemplate = 'Y' OR EXISTS (SELECT 1 FROM ad_role_inheritance i "
            + "WHERE i.ad_role_id = r.ad_role_id AND i.isactive = 'Y' "
            + "AND i.inherit_from NOT IN (" + SYSTEM_TEMPLATES_SQL + ")))");
    Set<String> excluded = new LinkedHashSet<>();
    for (List<String> chunk : chunks(roleIds, 1000)) {
      query.setParameterList(ROLES_PARAM, chunk);
      excluded.addAll(strings(query));
    }
    List<String> eligible = new ArrayList<>(roleIds);
    eligible.removeAll(excluded);
    return eligible;
  }

  /**
   * Every user that can own one of {@code roleIds}, sorted by id so two lockers always take the
   * same locks in the same order: the role's {@code EM_ETGO_Personal_Owner_ID}, every user whose
   * default role it is, and every user with an active {@code AD_User_Roles} row on it. Usually
   * one user per role; several only in the ETP-4604 multi-row anomaly.
   */
  public List<String> ownersOf(Collection<String> roleIds) {
    if (roleIds.isEmpty()) {
      return Collections.emptyList();
    }
    NativeQuery<?> query = session().createNativeQuery(
        "SELECT r.em_etgo_personal_owner_id FROM ad_role r "
            + "WHERE r.ad_role_id IN (:roles) AND r.em_etgo_personal_owner_id IS NOT NULL "
            + "UNION SELECT u.ad_user_id FROM ad_user u WHERE u.default_ad_role_id IN (:roles) "
            + "UNION SELECT ur.ad_user_id FROM ad_user_roles ur "
            + "WHERE ur.ad_role_id IN (:roles) AND ur.isactive = 'Y'");
    query.setParameterList(ROLES_PARAM, roleIds);
    return new ArrayList<>(new TreeSet<>(strings(query)));
  }

  // ---------------------------------------------------------------------
  // Sweep
  // ---------------------------------------------------------------------

  /**
   * Realigns the inherited rows of {@code roleIds} with their templates (see the class javadoc
   * for the rule). Does not lock, filter or commit: the caller passes eligible roles and holds
   * their owners' {@link UserRoleWriteLock}s.
   */
  public SweepCounts sweepRoles(Collection<String> roleIds) {
    SweepCounts counts = new SweepCounts();
    if (roleIds.isEmpty()) {
      return counts;
    }
    for (AccessTable access : AccessTable.values()) {
      if (access == AccessTable.WINDOW) {
        removeChildrenOfOrphanWindowAccess(roleIds);
      }
      counts.removed += execute(access.sql(REMOVE_ORPHANS_SQL), roleIds);
      counts.updated += execute(access.sql(UPDATE_SQL), roleIds);
      counts.inserted += execute(access.sql(INSERT_SQL), roleIds);
    }
    return counts;
  }

  /**
   * {@link #sweepRoles} for one personal role inside a composition request (ETP-5565, the
   * composition hook): flushes the session first, so the sweep sees what core just propagated,
   * and leaves the session's copies of the role's access rows stale, so the caller must refresh
   * or evict them (see {@code UserRoleCompositionService#assignTemplateRoles}). A no-op for a
   * role {@link #eligibleRoles} excludes. The caller already holds the owner's write lock.
   */
  public SweepCounts sweepRole(String roleId) {
    OBDal.getInstance().flush();
    List<String> eligible = eligibleRoles(Collections.singletonList(roleId));
    return sweepRoles(eligible);
  }

  /**
   * The desired inherited rows of the {@code :roles} personal roles for one access table: one per
   * (role, element) that at least one active system template of the role grants actively.
   */
  private static final String DESIRED_CTE = "WITH inh AS ("
      + "SELECT i.ad_role_id AS role_id, i.inherit_from AS tpl_id, i.seqno "
      + "FROM ad_role_inheritance i JOIN ad_role t ON t.ad_role_id = i.inherit_from "
      + "WHERE i.isactive = 'Y' AND t.isactive = 'Y' AND t.istemplate = 'Y' "
      + "AND t.ad_client_id = '0' AND i.ad_role_id IN (:roles)), "
      + "desired AS ("
      + "SELECT inh.role_id, g.{el} AS el, "
      + "CASE WHEN bool_or(g.isreadwrite = 'Y') THEN 'Y' ELSE 'N' END AS rw, "
      + "(array_agg(inh.tpl_id ORDER BY g.isreadwrite = 'Y' DESC, inh.seqno DESC, "
      + "inh.tpl_id DESC))[1] AS src "
      + "FROM inh JOIN {t} g ON g.ad_role_id = inh.tpl_id AND g.isactive = 'Y' "
      + "GROUP BY inh.role_id, g.{el}) ";

  /** Inherited rows (from a system template) whose element no active template grants. */
  private static final String ORPHAN_PREDICATE = "a.ad_role_id IN (:roles) "
      + "AND a.inherited_from IN (" + SYSTEM_TEMPLATES_SQL + ") "
      + "AND NOT EXISTS (SELECT 1 FROM desired d WHERE d.role_id = a.ad_role_id "
      + "AND d.el = a.{el})";

  private static final String REMOVE_ORPHANS_SQL = DESIRED_CTE
      + "DELETE FROM {t} a WHERE " + ORPHAN_PREDICATE;

  private static final String UPDATE_SQL = DESIRED_CTE
      + "UPDATE {t} a SET isreadwrite = d.rw, inherited_from = d.src, isactive = 'Y', "
      + "ad_client_id = r.ad_client_id, ad_org_id = r.ad_org_id, updated = now(), "
      + "updatedby = '" + SYSTEM_USER_ID + "' "
      + "FROM desired d, ad_role r "
      + "WHERE a.ad_role_id = d.role_id AND a.{el} = d.el AND r.ad_role_id = a.ad_role_id "
      + "AND a.inherited_from IN (" + SYSTEM_TEMPLATES_SQL + ") "
      + "AND (a.isreadwrite <> d.rw OR a.inherited_from <> d.src OR a.isactive <> 'Y' "
      + "OR a.ad_client_id <> r.ad_client_id OR a.ad_org_id <> r.ad_org_id)";

  /**
   * {@code NOT EXISTS} on any row of the element, active or not, manual or inherited: a manual
   * row always wins. {@code obuiapp_process_access} has no unique key on (role, element), so this
   * is the only duplicate guard there; it holds because the caller holds the owner's write lock.
   */
  private static final String INSERT_SQL = DESIRED_CTE
      + "INSERT INTO {t} ({pk}, ad_client_id, ad_org_id, isactive, created, createdby, updated, "
      + "updatedby, ad_role_id, {el}, isreadwrite, inherited_from) "
      + "SELECT get_uuid(), r.ad_client_id, r.ad_org_id, 'Y', now(), '" + SYSTEM_USER_ID
      + "', now(), '" + SYSTEM_USER_ID + "', d.role_id, d.el, d.rw, d.src "
      + "FROM desired d JOIN ad_role r ON r.ad_role_id = d.role_id "
      + "WHERE NOT EXISTS (SELECT 1 FROM {t} a WHERE a.ad_role_id = d.role_id AND a.{el} = d.el)";

  /**
   * {@code AD_Tab_Access} (and its {@code AD_Field_Access}) reference {@code AD_Window_Access}
   * with no cascade, so they go first. The templates hold no tab access today, so this is
   * normally a no-op.
   */
  private void removeChildrenOfOrphanWindowAccess(Collection<String> roleIds) {
    String orphanIds = AccessTable.WINDOW.sql(
        DESIRED_CTE + "SELECT a.ad_window_access_id FROM ad_window_access a WHERE "
            + ORPHAN_PREDICATE);
    NativeQuery<?> query = session().createNativeQuery(orphanIds);
    query.setParameterList(ROLES_PARAM, roleIds);
    List<String> ids = strings(query);
    if (ids.isEmpty()) {
      return;
    }
    for (List<String> chunk : chunks(ids, 1000)) {
      NativeQuery<?> fields = session().createNativeQuery(
          "DELETE FROM ad_field_access WHERE ad_tab_access_id IN (SELECT ad_tab_access_id "
              + "FROM ad_tab_access WHERE ad_window_access_id IN (:ids))");
      fields.setParameterList("ids", chunk);
      fields.executeUpdate();
      NativeQuery<?> tabs = session().createNativeQuery(
          "DELETE FROM ad_tab_access WHERE ad_window_access_id IN (:ids)");
      tabs.setParameterList("ids", chunk);
      tabs.executeUpdate();
    }
  }

  // ---------------------------------------------------------------------
  // Purge
  // ---------------------------------------------------------------------

  /**
   * True when some system template holds an inactive access row last updated more than {@code
   * graceDays} days ago. Lets the periodic tick check for work without taking the lease.
   */
  public boolean hasPurgeableTemplateRows(int graceDays) {
    for (AccessTable access : AccessTable.values()) {
      NativeQuery<?> query = session().createNativeQuery(access.sql(
          "SELECT 1 FROM {t} g WHERE g.isactive = 'N' AND g.ad_role_id IN ("
              + SYSTEM_TEMPLATES_SQL + ") AND g.updated < now() - make_interval(days => :days) "
              + "LIMIT 1"));
      query.setParameter("days", graceDays);
      if (!query.list().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Hard-deletes the system templates' inactive access rows last updated more than {@code
   * graceDays} days ago. {@code EnsureSystemRoleTemplatesScript} soft-deletes removed grants so
   * the removal reaches production through the deploy delta; once that is done, an inactive
   * template row only does harm, because core's composition copies inactive rows too and lets
   * them win by precedence. This runs on the live database only, so the delta never sees it (the
   * next build's clone starts without the row and the script does not recreate it).
   *
   * @return rows deleted
   */
  public int purgeInactiveTemplateRows(int graceDays) {
    int deleted = 0;
    for (AccessTable access : AccessTable.values()) {
      if (access == AccessTable.WINDOW) {
        NativeQuery<?> fields = session().createNativeQuery(
            "DELETE FROM ad_field_access WHERE ad_tab_access_id IN (SELECT ta.ad_tab_access_id "
                + "FROM ad_tab_access ta JOIN ad_window_access g "
                + "ON g.ad_window_access_id = ta.ad_window_access_id WHERE g.isactive = 'N' "
                + "AND g.ad_role_id IN (" + SYSTEM_TEMPLATES_SQL + ") "
                + "AND g.updated < now() - make_interval(days => :days))");
        fields.setParameter("days", graceDays);
        fields.executeUpdate();
        NativeQuery<?> tabs = session().createNativeQuery(
            "DELETE FROM ad_tab_access ta USING ad_window_access g "
                + "WHERE g.ad_window_access_id = ta.ad_window_access_id AND g.isactive = 'N' "
                + "AND g.ad_role_id IN (" + SYSTEM_TEMPLATES_SQL + ") "
                + "AND g.updated < now() - make_interval(days => :days)");
        tabs.setParameter("days", graceDays);
        tabs.executeUpdate();
      }
      NativeQuery<?> query = session().createNativeQuery(access.sql(
          "DELETE FROM {t} g WHERE g.isactive = 'N' AND g.ad_role_id IN ("
              + SYSTEM_TEMPLATES_SQL + ") AND g.updated < now() - make_interval(days => :days)"));
      query.setParameter("days", graceDays);
      deleted += query.executeUpdate();
    }
    return deleted;
  }

  // ---------------------------------------------------------------------
  // Diagnostic
  // ---------------------------------------------------------------------

  /**
   * Classifies every active classic and OBUIAPP process grant of {@code templateIds} the way
   * {@code EnsureSystemRoleTemplatesScript#reconcileProcessAccess} derives them: a button (active
   * tab, column and field) on a window the template has full access to, a standalone grant from
   * {@link TemplateRoleWindowAccess}, a button only on read-only windows, or unexplained. After
   * the script has run, only the first two should exist; anything else on a live database is
   * worth a look. Read-only.
   */
  public List<ProcessGrantDiagnostic> diagnoseProcessGrants(Collection<String> templateIds) {
    List<ProcessGrantDiagnostic> result = new ArrayList<>();
    if (templateIds.isEmpty()) {
      return result;
    }
    result.addAll(diagnose(templateIds, "classic", "ad_process_access", "ad_process_id",
        "c.ad_process_id", "ad_process", "ad_process_id",
        TemplateRoleWindowAccess.standaloneClassicProcessGrantsByRoleId()));
    result.addAll(diagnose(templateIds, "obuiapp", "obuiapp_process_access",
        "obuiapp_process_id", "c.em_obuiapp_process_id", "obuiapp_process", "obuiapp_process_id",
        TemplateRoleWindowAccess.standaloneProcessGrantsByRoleId()));
    return result;
  }

  @SuppressWarnings("squid:S107") // a private helper over two near-identical table shapes
  private List<ProcessGrantDiagnostic> diagnose(Collection<String> templateIds, String kind,
      String accessTable, String accessColumn, String buttonColumn, String processTable,
      String processPk, Map<String, List<String>> standaloneByRoleId) {
    List<String> standaloneKeys = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : standaloneByRoleId.entrySet()) {
      for (String processId : entry.getValue()) {
        standaloneKeys.add(entry.getKey() + ":" + processId);
      }
    }
    standaloneKeys.add("-"); // never empty: IN () is not valid SQL
    String sql = "WITH b AS (SELECT DISTINCT t.ad_window_id, " + buttonColumn + " AS pid "
        + "FROM ad_field f JOIN ad_column c ON c.ad_column_id = f.ad_column_id "
        + "JOIN ad_tab t ON t.ad_tab_id = f.ad_tab_id WHERE t.isactive = 'Y' "
        + "AND c.isactive = 'Y' AND f.isactive = 'Y' AND " + buttonColumn + " IS NOT NULL), "
        + "g AS (SELECT DISTINCT ad_role_id, " + accessColumn + " AS pid FROM " + accessTable
        + " WHERE isactive = 'Y' AND ad_role_id IN (:templates)) "
        + "SELECT g.ad_role_id, g.pid, p.name, CASE "
        + "WHEN EXISTS (SELECT 1 FROM ad_window_access w JOIN b ON b.ad_window_id = w.ad_window_id "
        + "WHERE w.ad_role_id = g.ad_role_id AND w.isactive = 'Y' AND w.isreadwrite = 'Y' "
        + "AND b.pid = g.pid) THEN 1 "
        + "WHEN g.ad_role_id || ':' || g.pid IN (:standalone) THEN 2 "
        + "WHEN EXISTS (SELECT 1 FROM ad_window_access w JOIN b ON b.ad_window_id = w.ad_window_id "
        + "WHERE w.ad_role_id = g.ad_role_id AND w.isactive = 'Y' AND b.pid = g.pid) THEN 3 "
        + "ELSE 4 END FROM g LEFT JOIN " + processTable + " p ON p." + processPk + " = g.pid "
        + "ORDER BY 1, 4, 3";
    NativeQuery<?> query = session().createNativeQuery(sql);
    query.setParameterList("templates", templateIds);
    query.setParameterList("standalone", standaloneKeys);
    List<ProcessGrantDiagnostic> result = new ArrayList<>();
    for (Object[] row : rows(query)) {
      result.add(new ProcessGrantDiagnostic((String) row[0], kind, (String) row[1],
          (String) row[2], category(((Number) row[3]).intValue())));
    }
    return result;
  }

  private static String category(int code) {
    switch (code) {
      case 1:
        return ProcessGrantDiagnostic.BUTTON_ON_FULL_WINDOW;
      case 2:
        return ProcessGrantDiagnostic.STANDALONE;
      case 3:
        return ProcessGrantDiagnostic.BUTTON_ON_READ_ONLY_WINDOW;
      default:
        return ProcessGrantDiagnostic.UNEXPLAINED;
    }
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private int execute(String sql, Collection<String> roleIds) {
    NativeQuery<?> query = session().createNativeQuery(sql);
    query.setParameterList(ROLES_PARAM, roleIds);
    return query.executeUpdate();
  }

  private static Session session() {
    return OBDal.getInstance().getSession();
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> rows(NativeQuery<?> query) {
    return (List<Object[]>) query.list();
  }

  private static List<String> strings(NativeQuery<?> query) {
    List<String> result = new ArrayList<>();
    for (Object value : query.list()) {
      result.add((String) value);
    }
    return result;
  }

  static <T> List<List<T>> chunks(List<T> items, int size) {
    List<List<T>> result = new ArrayList<>();
    for (int start = 0; start < items.size(); start += size) {
      result.add(items.subList(start, Math.min(items.size(), start + size)));
    }
    return result;
  }
}
