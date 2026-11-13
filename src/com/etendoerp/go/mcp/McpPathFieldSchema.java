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

package com.etendoerp.go.mcp;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.NeoPropertyPaths;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * The {@code etendo_schema} descriptors of an entity's property-path fields (ETP-6148). Generic:
 * built from the paths the entity's field filter declares, never from the entity's identity.
 * Split out of {@link McpSchemaFieldBuilder} (Sonar java:S1448), whose type mapping and field
 * keys it reuses.
 */
final class McpPathFieldSchema {

  private McpPathFieldSchema() {
  }

  /**
   * ETP-6148: the descriptors of the entity's property-path fields — values of a related record
   * reached through a dotted path ({@code NeoPropertyPaths}). Always {@code readOnly:true}: the
   * write tools refuse them like any read-only field. {@code type} and {@code label} come from the
   * column the path ends on; {@code propertyPath} says where the value really lives, the way
   * {@code backingTable} does for a virtual field.
   *
   * @param sfEntity  the SchemaForge entity
   * @param dalEntity the DAL entity of its tab
   * @return the descriptors, empty when the entity declares none
   * @throws JSONException if a descriptor cannot be built
   */
  static JSONArray build(SFEntity sfEntity, Entity dalEntity)
      throws JSONException {
    JSONArray fields = new JSONArray();
    if (sfEntity == null || dalEntity == null) {
      return fields;
    }
    for (NeoPropertyPaths.PathField pathField
        : NeoPropertyPaths.forEntity(sfEntity, dalEntity.getName()).fields()) {
      Property target = pathField.getTarget();
      Column column = target.getColumnId() == null ? null
          : OBDal.getInstance().get(Column.class, target.getColumnId());
      String refId = column != null && column.getReference() != null
          ? (String) column.getReference().getId() : null;
      JSONObject field = new JSONObject();
      field.put("name", pathField.getApiKey());
      field.put(McpConstants.KEY_LABEL, column != null ? column.getName() : target.getName());
      field.put("type", McpSchemaFieldBuilder.mapColumnType(refId));
      field.put("required", false);
      field.put(McpSchemaFieldBuilder.KEY_READ_ONLY, true);
      field.put(McpSchemaFieldBuilder.KEY_VISIBILITY, McpSchemaFieldBuilder.VISIBILITY_READ_ONLY);
      field.put("propertyPath", pathField.getPath());
      field.put("businessCritical", false);
      fields.put(field);
    }
    return fields;
  }
}
