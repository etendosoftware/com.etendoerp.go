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

package com.etendoerp.go.schemaforge;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFField;

/**
 * The record context a {@code /defaults} call carries in its query (ETP-5852): the values the
 * caller already has for fields of the record, from which the callout cascade derives the rest.
 */
final class NeoDefaultsRecordContext {

  private NeoDefaultsRecordContext() {
  }

  /**
   * Puts the query-param values that name an included field of this entity into
   * {@code defaults} (with the reference's identifier), so the callout cascade starts from the
   * record as the caller knows it. Keys that are not included fields — REST control params, a
   * misspelt name — are ignored, never an error: the response then simply carries no derived
   * values for them.
   *
   * @return the property names applied, which the cascade must not overwrite
   */
  static Set<String> apply(NeoContext ctx, List<SFField> fields, Entity dalEntity,
      JSONObject defaults) throws JSONException {
    Map<String, String> params = ctx.getQueryParams();
    Set<String> applied = new HashSet<>();
    if (params == null || params.isEmpty() || dalEntity == null) {
      return applied;
    }
    for (SFField sfField : fields) {
      Column adColumn = sfField.getADColumn();
      String propertyName = adColumn == null ? null
          : NeoDefaultsCascadeHelper.resolvePropertyName(dalEntity, adColumn.getDBColumnName());
      String value = propertyName == null ? null : params.get(propertyName);
      if (StringUtils.isNotBlank(value)) {
        defaults.put(propertyName, value);
        defaults.remove(propertyName + "$_identifier");
        NeoDefaultsService.tryInjectIdentifier(defaults, dalEntity, propertyName, value);
        applied.add(propertyName);
      }
    }
    return applied;
  }
}
