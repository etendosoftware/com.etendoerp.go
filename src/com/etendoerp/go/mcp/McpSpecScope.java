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

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoParentTabFilterResolver;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Keeps {@code etendo_get} inside its spec (ETP-5852, M-51): a spec whose AD tab filters its table
 * (sales invoices are {@code C_Invoice} rows with {@code salesTransaction = true}, purchase
 * invoices the others) reads a record by id only when that filter admits it, as
 * {@code etendo_list} already does. Core's read by id ({@code DefaultJsonDataService} with an
 * {@code id}) ignores the tab filter, so before this a purchase invoice id read through
 * {@code sales-invoice/header} came back as if it were a sales invoice.
 *
 * <p>A record outside the filter is answered 404, naming the spec(s) over the same table that hold
 * it when the caller's role can see it, so the agent retries on the right one instead of
 * concluding it does not exist. MCP only: the REST GET by id is unchanged.</p>
 */
final class McpSpecScope {

  private static final Logger log = LogManager.getLogger(McpSpecScope.class);

  /** Extra key of the not-found envelope: the {@code spec/entity} pairs that hold the record. */
  static final String KEY_FOUND_IN = "foundIn";

  private McpSpecScope() {
  }

  /**
   * The tab's own HQL filter, the clause the list applies. A clause that still needs a parent
   * record ({@code @…@} placeholder) cannot be judged without one and is left out.
   *
   * @param adTab the entity's tab, may be {@code null}
   * @return the clause, or {@code null} when the tab has none usable
   */
  static String tabWhere(Tab adTab) {
    if (adTab == null) {
      return null;
    }
    String where = StringUtils.trimToNull(NeoParentTabFilterResolver.resolveTabWhere(adTab, null));
    return where == null || where.contains("@") ? null : where;
  }

  /**
   * The 404 of a read by id whose record exists in the table but outside the spec's filter.
   *
   * @param specName   the spec of the call
   * @param entityName the entity of the call
   * @param adTab      the entity's tab, may be {@code null} (nothing to judge)
   * @param recordId   the id the caller passed
   * @return the error envelope, or {@code null} when the read may go on — no filter, the filter
   *         admits the record, or no such row at all (the read's own not-found answers that)
   */
  static JSONObject refusalFor(String specName, String entityName, Tab adTab, String recordId)
      throws JSONException {
    String where = tabWhere(adTab);
    if (where == null) {
      return null;
    }
    String dalEntityName = adTab.getTable().getName();
    if (!admits(dalEntityName, null, recordId) || admits(dalEntityName, where, recordId)) {
      return null;
    }
    JSONObject error = McpToolRouterSupport.buildNotFoundError(specName, entityName, recordId);
    List<String> holders = specsHolding(adTab, recordId);
    if (!holders.isEmpty()) {
      error.put(McpConstants.KEY_DETAIL, "Record " + recordId + " is not a " + specName + "/"
          + entityName + " record: it belongs to " + String.join(", ", holders)
          + ". Read it there.");
      error.put(KEY_FOUND_IN, new JSONArray(holders));
    }
    return error;
  }

  /**
   * The {@code spec/entity} pairs over the same table whose tab filter admits the record, for a
   * record the caller's role can see. Empty when it cannot see it or no other spec holds it.
   */
  private static List<String> specsHolding(Tab adTab, String recordId) {
    List<String> holders = new ArrayList<>();
    String dalEntityName = adTab.getTable().getName();
    BaseOBObject subject = OBDal.getInstance().get(dalEntityName, recordId);
    if (subject == null || !McpActionAvailabilityContext.isVisibleToRole(subject)) {
      return holders;
    }
    OBQuery<SFEntity> others = OBDal.getInstance().createQuery(SFEntity.class,
        "as e where e." + SFEntity.PROPERTY_ADTAB + ".table.id = :tableId"
            + " and e." + SFEntity.PROPERTY_ADTAB + ".id <> :tabId"
            + " and e." + SFEntity.PROPERTY_ISINCLUDED + " = true"
            + " and e." + SFEntity.PROPERTY_ETGOSFSPEC + "." + SFSpec.PROPERTY_ISACTIVE + " = true");
    others.setNamedParameter("tableId", adTab.getTable().getId());
    others.setNamedParameter("tabId", adTab.getId());
    for (SFEntity other : others.list()) {
      // A filter that needs a parent or session value (@…@) cannot be judged here: no claim.
      String raw = NeoParentTabFilterResolver.resolveTabWhere(other.getADTab(), null);
      if (StringUtils.contains(raw, "@")) {
        continue;
      }
      if (admits(dalEntityName, tabWhere(other.getADTab()), recordId)) {
        holders.add(other.getETGOSFSpec().getName() + "/" + other.getName());
      }
    }
    return holders;
  }

  private static boolean admits(String dalEntityName, String where, String recordId) {
    String hql = "select count(e) from " + dalEntityName + " as e where e.id = :id"
        + (where == null ? "" : " and (" + where + ")");
    try {
      Number count = (Number) OBDal.getInstance().getSession().createQuery(hql)
          .setParameter("id", recordId).uniqueResult();
      return count != null && count.longValue() > 0;
    } catch (RuntimeException e) {
      log.debug("Could not judge spec scope with [{}]: {}", where, e.getMessage());
      return false;
    }
  }
}
