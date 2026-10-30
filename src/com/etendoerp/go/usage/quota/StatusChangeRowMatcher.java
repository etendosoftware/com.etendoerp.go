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
 * ETP-6060 — the {@link CreatedRowMatcher} question asked of a row <em>before</em> a status change:
 * would the quota's resource count it in the period once the property holds the target value. The
 * restriction is rewritten by {@link UsageQueryComposer#composeRowMatchInRangeAs}, never here.
 */
@FunctionalInterface
public interface StatusChangeRowMatcher {

  /**
   * Tells whether a quota's resource would count a row in a period after a status change, and
   * names the row's tenant.
   *
   * @param quota the quota; its resource's counted entity, date property and restriction are used
   * @param recordId the id of the row, as it stands in the current transaction
   * @param period the period whose counted window the row must fall in
   * @param property the changed property, as the restriction names it after {@code e.}
   * @param targetValue the value the change leaves in it
   * @return the row's tenant when the resource would count it in the period, else null
   */
  String tenantIfCountedAs(QuotaDefinition quota, String recordId, QuotaPeriod period,
      String property, String targetValue);

  /**
   * The production matcher, on the request's own session like {@link CreatedRowMatcher#dal()}.
   *
   * @return the production matcher
   */
  static StatusChangeRowMatcher dal() {
    return (quota, recordId, period, property, targetValue) -> {
      String hql = UsageQueryComposer.composeRowMatchInRangeAs(quota.getCountedEntity(),
          quota.getDateProperty(), quota.getHqlRestriction(), property);
      return OBDal.getInstance().getSession().createQuery(hql, String.class)
          .setParameter(UsageQueryComposer.PARAM_RECORD_ID, recordId)
          .setParameter(UsageQueryComposer.PARAM_DAY_START, period.getWindowFrom())
          .setParameter(UsageQueryComposer.PARAM_DAY_END, period.getWindowTo())
          .setParameter(UsageQueryComposer.PARAM_TARGET_VALUE, targetValue)
          .setMaxResults(1)
          .uniqueResult();
    };
  }
}
