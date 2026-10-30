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

package com.etendoerp.go.schemaforge.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.client.application.Process;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Server-side gate for accounting entities (ETP-5648) — the single source of truth behind the
 * {@code showAccountingFields} UI capability ({@code SFWindowAccessMap}) and the REST, batch and
 * MCP access checks, so the UI and the API can never disagree on who sees accounting.
 *
 * <p><b>Accounting entity</b> is decided by AD structure only, never by a spec or entity name:
 * a child tab ({@code TabLevel > 0}) whose table has a {@code C_AcctSchema_ID} column
 * ({@code Fact_Acct} tabs, the {@code *_Acct} configuration tabs). Headers that carry
 * {@code C_AcctSchema_ID} ({@code AD_Org}, {@code GL_Journal}) are excluded by the tab level.</p>
 *
 * <p><b>Who may see it</b>: the admin/client-admin bypass, or a role with
 * {@code AD_Role.EM_ETGO_Show_Acct_Fields = 'Y'}. A {@code null} role is denied (fail closed).</p>
 */
public final class NeoAccountingAccessPolicy {

  /** Stable identity of the posting refusal; a plain string, as in ETP-5692. */
  public static final String KEY_ACCOUNTING_ACCESS_DENIED = "ETGO_AccountingAccessDenied";

  static final String MSG_POSTING_DENIED =
      "Access denied to accounting for current role: posting and unposting need Show Accounting Fields";

  /** The generic posting actions {@code DocumentPostingService} serves for every posted table. */
  private static final Set<String> POSTING_ACTIONS = Set.of("post", "unpost");

  /**
   * DAL properties of the accounting columns: {@code Posted}, {@code EM_Etblkp_Accountingstatus}
   * and the {@code EM_Etblkp_Bulkposting} button. Kept in step with {@link #ACCOUNTING_STATUS_COLUMNS}
   * and the posting buttons by {@code NeoAccountingAccessPolicyTest}.
   */
  private static final Set<String> ACCOUNTING_PROPERTIES =
      Set.of("posted", "etblkpAccountingstatus", "etblkpBulkposting");

  private static final String IDENTIFIER_SUFFIX = "$_identifier";

  /** DB columns (lower case) that store a document's accounting status. */
  private static final Set<String> ACCOUNTING_STATUS_COLUMNS =
      Set.of("posted", "em_etblkp_accountingstatus");

  /** Structural verdict per {@code AD_Tab_ID}; AD metadata does not change without a restart. */
  private static final Map<String, Boolean> ACCOUNTING_TAB_CACHE = new ConcurrentHashMap<>();

  private NeoAccountingAccessPolicy() {
  }

  /**
   * Whether {@code entity} is an accounting entity: its AD tab is a child tab and its table
   * has a {@code C_AcctSchema_ID} column.
   *
   * @param entity the entity to classify (may be {@code null})
   * @return {@code true} for an accounting entity; {@code false} otherwise
   */
  public static boolean isAccountingEntity(SFEntity entity) {
    Tab tab = entity == null ? null : entity.getADTab();
    if (tab == null) {
      return false;
    }
    String tabId = tab.getId();
    return tabId == null ? isAccountingTab(tab)
        : ACCOUNTING_TAB_CACHE.computeIfAbsent(tabId, id -> isAccountingTab(tab));
  }

  private static boolean isAccountingTab(Tab tab) {
    Long level = tab.getTabLevel();
    if (level == null || level <= 0 || tab.getTable() == null) {
      return false;
    }
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT 1 FROM ad_column WHERE ad_table_id = :tableId"
            + " AND lower(columnname) = 'c_acctschema_id'");
    query.setParameter("tableId", tab.getTable().getId());
    query.setMaxResults(1);
    return !query.getResultList().isEmpty();
  }

  /**
   * Whether {@code role} may see accounting entities. Costs one query (the role flag) unless the
   * role is admin/client-admin, so callers check {@link #isAccountingEntity(SFEntity)} first.
   *
   * @param role the current role ({@code null} → denied)
   * @return {@code true} for admin/client-admin, or when {@code EM_ETGO_Show_Acct_Fields = 'Y'}
   */
  public static boolean canSeeAccounting(Role role) {
    return role != null
        && (NeoAccessHelper.isAdminOrClientAdmin(role) || readShowAccountingFieldsFlag(role));
  }

  /**
   * Reads {@code AD_Role.EM_ETGO_Show_Acct_Fields} via native SQL: the column was added straight
   * to the physical table (ETP-4520) and is not mapped as a typed entity property.
   *
   * @param role the role to read (never {@code null})
   * @return {@code true} when the column is {@code 'Y'}; {@code false} otherwise
   */
  public static boolean readShowAccountingFieldsFlag(Role role) {
    // Object, not String: Hibernate maps a PostgreSQL char(1) to Character for a plain scalar
    // native query, and a List<String>.get(0) would throw ClassCastException (ETP-4515).
    NativeQuery<Object> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT em_etgo_show_acct_fields FROM ad_role WHERE ad_role_id = :roleId");
    query.setParameter("roleId", role.getId());
    List<Object> results = query.getResultList();
    return !results.isEmpty() && results.get(0) != null && "Y".equals(results.get(0).toString());
  }

  /**
   * Whether the current role may reach {@code entity}. The role flag is only read when the
   * entity is an accounting one, so non-accounting entities cost no query.
   *
   * @param entity the entity the request targets
   * @return {@code false} when {@code entity} is an accounting entity the role cannot see
   */
  public static boolean isEntityAllowed(SFEntity entity) {
    return !isAccountingEntity(entity) || canSeeAccounting(NeoAccessHelper.resolveCurrentRole());
  }

  /**
   * The entities of {@code entities} the current role may see. The role flag is only read when
   * the list holds an accounting entity, so a spec without one costs no query.
   *
   * @param entities the entities to filter; may be {@code null}
   * @return {@code entities} itself when nothing is hidden, otherwise a new list without the
   *     accounting entities
   */
  public static List<SFEntity> visibleEntities(List<SFEntity> entities) {
    if (entities == null
        || entities.stream().noneMatch(NeoAccountingAccessPolicy::isAccountingEntity)
        || canSeeAccounting(NeoAccessHelper.resolveCurrentRole())) {
      return entities;
    }
    return entities.stream()
        .filter(entity -> !isAccountingEntity(entity))
        .collect(Collectors.toList());
  }

  /**
   * Whether the current role may reach the entity named {@code entityName} in {@code spec}.
   *
   * <p>Fails closed against lookup drift: the handlers resolve entities case-insensitively and
   * some ignore {@code isIncluded}, so this looks the name up with {@code ilike}, ignoring
   * {@code isIncluded}/{@code isActive}, and denies when ANY matching row is an accounting
   * entity. A name with no match is allowed here — the handler then answers 404 as before.</p>
   *
   * @param spec the spec the request targets
   * @param entityName the entity name from the request path or batch operation
   * @return {@code false} when the name resolves to an accounting entity the role cannot see
   */
  @SuppressWarnings("unchecked")
  public static boolean isEntityNameAllowed(SFSpec spec, String entityName) {
    if (spec == null || entityName == null) {
      return true;
    }
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.setFilterOnActive(false);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName));
    for (SFEntity entity : (List<SFEntity>) criteria.list()) {
      if (!isEntityAllowed(entity)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether {@code action} on {@code entity} creates or removes ledger entries (ETP-5684): the
   * {@code post}/{@code unpost} actions {@code DocumentPostingService} serves, or an AD button whose
   * process is the shared Post process — {@code Posted} through its fallback,
   * {@code EM_Etblkp_Bulkposting} explicitly. Decided by the process, never by a column name.
   *
   * @param entity the entity the action is addressed to; may be {@code null}
   * @param action the action as typed (field or DB column name for a button)
   * @return {@code true} for a posting action
   */
  public static boolean isPostingAction(SFEntity entity, String action) {
    if (StringUtils.isBlank(action)) {
      return false;
    }
    if (POSTING_ACTIONS.contains(action.toLowerCase(Locale.ROOT))) {
      return true;
    }
    return entity != null
        && isPostingButton(NeoButtonActionHelper.findButtonColumn(entity.getId(), action));
  }

  /**
   * Whether {@code column} is a button that runs the shared Post process, either declared on the
   * column or resolved through the {@code Posted} fallback.
   *
   * @param column the AD column; may be {@code null}
   * @return {@code true} for a posting button
   */
  public static boolean isPostingButton(Column column) {
    if (column == null) {
      return false;
    }
    Process process = column.getOBUIAPPProcess();
    if (process == null) {
      process = NeoAccessHelper.resolveFallbackObuiappProcess(column);
    }
    return process != null && NeoAccessHelper.DEFAULT_POST_PROCESS_ID.equals(process.getId());
  }

  /**
   * Whether {@code column} is accounting data a role without {@code showAccountingFields} must not
   * see through the MCP (ETP-5684): a column that stores the document's accounting status, or a
   * posting button. The status columns have no shared AD reference ({@code Posted} is a button over
   * list 234, {@code EM_Etblkp_Accountingstatus} a plain list), hence the explicit set.
   *
   * @param column the AD column; may be {@code null}
   * @return {@code true} for an accounting-status column or a posting button
   */
  public static boolean isAccountingColumn(Column column) {
    return column != null && column.getDBColumnName() != null
        && (ACCOUNTING_STATUS_COLUMNS.contains(column.getDBColumnName().toLowerCase(Locale.ROOT))
            || isPostingButton(column));
  }

  /**
   * Whether {@code key} is a record property that carries accounting data (ETP-5684): the DAL
   * property of an accounting column ({@link #isAccountingColumn}), or its {@code $_identifier}.
   * By key because the MCP filters rendered bodies, and some handlers build {@code posted} by hand
   * (financial-account) rather than through the DAL serializer.
   *
   * @param key the JSON key
   * @return {@code true} for an accounting property
   */
  public static boolean isAccountingProperty(String key) {
    if (key == null) {
      return false;
    }
    String base = key.endsWith(IDENTIFIER_SUFFIX)
        ? key.substring(0, key.length() - IDENTIFIER_SUFFIX.length()) : key;
    return ACCOUNTING_PROPERTIES.contains(base);
  }

  /**
   * Whether the current role may see accounting data.
   *
   * @return {@link #canSeeAccounting(Role)} for the current role
   */
  public static boolean currentRoleSeesAccounting() {
    return canSeeAccounting(NeoAccessHelper.resolveCurrentRole());
  }

  /**
   * {@code actions} without the posting ones when the current role may not post (ETP-5684). The
   * role flag is read only when a posting action is present.
   *
   * @param <V> the value type
   * @param actions the actions by name
   * @return {@code actions} itself when nothing is dropped, otherwise a filtered copy
   */
  public static <V> Map<String, V> withoutPostingActions(Map<String, V> actions) {
    if (actions == null || actions.keySet().stream().noneMatch(a -> isPostingAction(null, a))
        || currentRoleSeesAccounting()) {
      return actions;
    }
    Map<String, V> kept = new LinkedHashMap<>();
    actions.forEach((name, value) -> {
      if (!isPostingAction(null, name)) {
        kept.put(name, value);
      }
    });
    return kept;
  }

  /**
   * The refusal for a posting action the current role may not run, or {@code null} (ETP-5684).
   * Checked on REST and MCP before the entity's customization or the AD button runs, so no
   * channel can post or unpost without the {@code showAccountingFields} capability. The role flag
   * is read only for a posting action.
   *
   * @param entity the entity the action is addressed to
   * @param action the action as typed
   * @return a 403 with {@code messageKeys: [ETGO_AccountingAccessDenied]}, or {@code null}
   */
  public static NeoResponse refusalForPostingAction(SFEntity entity, String action) {
    if (!isPostingAction(entity, action)
        || canSeeAccounting(NeoAccessHelper.resolveCurrentRole())) {
      return null;
    }
    try {
      JSONObject body = new JSONObject();
      body.put("success", false);
      body.put("message", MSG_POSTING_DENIED);
      body.put("messageKeys", new JSONArray(List.of(KEY_ACCOUNTING_ACCESS_DENIED)));
      // The JSONObject overload: the String one nests the text under error.message (ETP-4706).
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN, body);
    } catch (JSONException e) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN, MSG_POSTING_DENIED);
    }
  }
}
