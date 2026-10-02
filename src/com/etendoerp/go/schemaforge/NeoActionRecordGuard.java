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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Tenant ownership of the record an action runs on (ETP-5558), checked once for every action of
 * every entity, on both channels, before the entity's customization or the AD button runs.
 *
 * <p><b>Why.</b> Both channels run the whole request in admin mode, and nothing between the request
 * and the action looked at the record id: {@code NeoButtonActionHelper#executeButtonActionCore} only
 * passes it to the process, and a customization such as {@code ReactivatePaymentHandler} resolved it
 * with a bare {@code OBDal.get}, which adds no tenant predicate (see {@link TenantOwnership}). So
 * {@code POST …/finPayment/<another tenant's id>/action/eTPRRemovePayment} removed that tenant's
 * payment.</p>
 *
 * <p><b>The rule — structure only.</b> The id is looked up in the table of the entity's own AD tab.
 * When a row with that id exists there and the current tenant cannot read it, the action is
 * refused with the same 404 an unknown record gets, so an id cannot be probed for existence. Nothing
 * else is decided here:</p>
 * <ul>
 *   <li>no record id (an action that is not about a record) — passes;</li>
 *   <li>no tab, or a tab without a DAL entity (a report or tab-less spec) — passes;</li>
 *   <li>an id that is not a row of that table (an action whose id names another table, such as a
 *       financial account on a report spec) — passes; the handler resolves it, and the handlers of
 *       that kind already go through {@link TenantOwnership#loadOwned};</li>
 *   <li>a row of a table without a client (system/reference data) — visible, as
 *       {@link TenantOwnership#isVisibleToCurrentTenant} decides for every caller.</li>
 * </ul>
 */
public final class NeoActionRecordGuard {

  private static final Logger log = LogManager.getLogger(NeoActionRecordGuard.class);

  /** Same text as an unknown record, on purpose. */
  static final String MSG_RECORD_NOT_FOUND = "Record not found";

  private NeoActionRecordGuard() {
  }

  /**
   * The refusal for an action on another tenant's record, or {@code null} when it may run.
   *
   * @param entity   the SchemaForge entity the action is addressed to
   * @param recordId the record id of the request; blank when the action is not about a record
   * @return a 404, or {@code null}
   */
  public static NeoResponse refusalFor(SFEntity entity, String recordId) {
    return isForeignRecord(entity, recordId)
        ? NeoResponse.error(404, MSG_RECORD_NOT_FOUND)
        : null;
  }

  /**
   * {@link TenantOwnership#loadOwned} for the customizations outside this package: the entity, or
   * {@code null} when the id is blank, unknown, or owned by another tenant.
   */
  public static <T extends BaseOBObject> T loadOwned(Class<T> entityClass, String id) {
    return TenantOwnership.loadOwned(entityClass, id);
  }

  /**
   * Whether {@code recordId} is a row of the entity's tab table that the current tenant cannot read
   * — or whether that cannot be decided because the lookup failed (fails closed).
   */
  static boolean isForeignRecord(SFEntity entity, String recordId) {
    if (entity == null || StringUtils.isBlank(recordId)) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      Tab tab = entity.getADTab();
      if (tab == null || tab.getTable() == null) {
        return false;
      }
      Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(tab.getTable().getId());
      if (dalEntity == null) {
        return false;
      }
      BaseOBObject row = (BaseOBObject) OBDal.getInstance().get(dalEntity.getName(), recordId);
      if (row == null || TenantOwnership.isVisibleToCurrentTenant(row)) {
        return false;
      }
      log.warn("Action on entity '{}' refused: record {} of {} is not visible to the current "
          + "tenant", entity.getName(), recordId, dalEntity.getName());
      return true;
    } catch (RuntimeException e) {
      // Fails CLOSED: a lookup that throws cannot prove the record is the tenant's, and letting the
      // action run on an undecided ownership is the hole this guard closes. Every NEO tab table has
      // a string key, so a well-formed id never lands here; an id that is simply not a row of the
      // table returns null above and still passes. The refusal reads like an unknown id, so nothing
      // leaks; the log names the request, not the exception's message, which may carry data.
      log.warn("Action on spec '{}', entity '{}', record {} refused: the ownership lookup failed "
          + "({})", specNameOf(entity), entity.getName(), recordId, e.getClass().getSimpleName());
      return true;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String specNameOf(SFEntity entity) {
    try {
      return entity.getETGOSFSpec() != null ? entity.getETGOSFSpec().getName() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
