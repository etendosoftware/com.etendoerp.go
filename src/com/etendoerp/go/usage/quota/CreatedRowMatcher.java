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
 * ETP-5051 — decides whether a just-created row is counted by a declarative resource, by running
 * the resource's own HQL restriction against that one row. The restriction is never formatted
 * here: {@link UsageQueryComposer#composeRowMatch} wraps it exactly as the usage job's counts do.
 */
@FunctionalInterface
public interface CreatedRowMatcher {

  /**
   * Tells whether a resource counts a created row, and names the row's tenant.
   *
   * @param resource the declarative resource
   * @param recordId the id of the created row, flushed in the current transaction
   * @return the row's tenant ({@code AD_CLIENT_ID}) when the resource counts it, else null
   */
  String tenantIfCounted(QuotaEntityIndex.IndexedResource resource, String recordId);

  /**
   * The production matcher. It runs on the request's own session on purpose: it must see the row
   * just inserted.
   *
   * @return the production matcher
   */
  static CreatedRowMatcher dal() {
    return (resource, recordId) -> {
      String hql = UsageQueryComposer.composeRowMatch(resource.getCountedEntity(),
          resource.getHqlRestriction());
      // A scalar on the raw session, like the usage counts: no DAL filter, no entity loaded.
      return OBDal.getInstance().getSession().createQuery(hql, String.class)
          .setParameter(UsageQueryComposer.PARAM_RECORD_ID, recordId)
          .setMaxResults(1)
          .uniqueResult();
    };
  }
}
