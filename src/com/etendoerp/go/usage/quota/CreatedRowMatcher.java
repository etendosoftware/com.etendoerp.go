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

package com.etendoerp.go.usage.quota;

import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usage.UsageQueryComposer;

/**
 * ETP-5051 — decides whether a just-created row is counted by a declarative resource in the
 * current period, by running the resource's own HQL restriction and the period's day bounds against
 * that one row. The restriction is never formatted
 * here: {@link UsageQueryComposer#composeRowMatchInRange} wraps it exactly as the usage job's counts
 * do.
 */
@FunctionalInterface
public interface CreatedRowMatcher {

  /**
   * Tells whether a quota's resource counts a created row <b>in a period</b>, and names the row's
   * tenant: the row must match the resource's restriction and its date property must fall inside
   * the period's counted window. A row dated outside it does not consume this period.
   *
   * @param quota the quota; its resource's counted entity, date property and restriction are used
   * @param recordId the id of the created row, flushed in the current transaction
   * @param period the period whose counted window the row must fall in
   * @return the row's tenant ({@code AD_CLIENT_ID}) when the resource counts it in the period,
   *     else null
   */
  String tenantIfCounted(QuotaDefinition quota, String recordId, QuotaPeriod period);

  /**
   * The production matcher. It runs on the request's own session on purpose: it must see the row
   * just inserted.
   *
   * @return the production matcher
   */
  static CreatedRowMatcher dal() {
    return (quota, recordId, period) -> {
      String hql = UsageQueryComposer.composeRowMatchInRange(quota.getCountedEntity(),
          quota.getDateProperty(), quota.getHqlRestriction());
      // A scalar on the raw session, like the usage counts: no DAL filter, no entity loaded.
      return OBDal.getInstance().getSession().createQuery(hql, String.class)
          .setParameter(UsageQueryComposer.PARAM_RECORD_ID, recordId)
          .setParameter(UsageQueryComposer.PARAM_DAY_START, period.getWindowFrom())
          .setParameter(UsageQueryComposer.PARAM_DAY_END, period.getWindowTo())
          .setMaxResults(1)
          .uniqueResult();
    };
  }
}
