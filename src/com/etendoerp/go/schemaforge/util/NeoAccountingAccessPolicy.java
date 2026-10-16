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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.ui.Tab;

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
   */
  public static boolean isEntityAllowed(SFEntity entity) {
    return !isAccountingEntity(entity) || canSeeAccounting(NeoAccessHelper.resolveCurrentRole());
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
}
