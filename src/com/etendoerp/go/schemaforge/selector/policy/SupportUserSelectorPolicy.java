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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.supportaccess.SupportUserExclusion;

/**
 * ETP-5351 (T6) — keeps the tenant's "Soporte Etendo" user out of every selector whose target is
 * {@code AD_User} (supervisor, responsible, sales representative, drop-ship contact…), whatever
 * reference or validation rule the column uses: the filter is keyed by the target entity, not by
 * column, so a selector added later is covered too.
 *
 * <p>The filter only names the bind parameter {@link SupportUserExclusion#HQL_PARAM};
 * {@code NeoSelectorService} binds it to the current client's support user id. The SQL-rule combo
 * route never reaches this policy and is covered by {@link ComboRowSelectorPolicy}.</p>
 */
public final class SupportUserSelectorPolicy implements SelectorContextPolicy {

  @Override
  public boolean supports(String entityName) {
    return User.ENTITY_NAME.equals(entityName);
  }

  @Override
  public String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
    if (SupportUserExclusion.currentClientId() == null) {
      return null;
    }
    String effectiveAlias = StringUtils.isNotBlank(alias) ? alias : "e";
    return effectiveAlias + ".id <> :" + SupportUserExclusion.HQL_PARAM;
  }
}
