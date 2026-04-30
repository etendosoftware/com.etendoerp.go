/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.selector.meta.RichFieldMeta;

/** Shared helpers for selector response payloads and normalized selector ids. */
final class SelectorResponseSupport {

  private static final String FIELD_ITEMS = "items";
  private static final String FIELD_COLUMNS = "columns";
  private static final String FIELD_TOTAL_COUNT = "totalCount";
  private static final String FIELD_HAS_MORE = "hasMore";
  private static final String FIELD_KEY = "key";
  private static final String FIELD_LABEL = "label";
  private static final String FIELD_PATH = "path";

  private SelectorResponseSupport() {
  }

  static NeoResponse buildSelectorResponse(JSONArray items, JSONArray columns,
      int totalCount, int limit, int offset) throws Exception {
    JSONObject result = new JSONObject();
    result.put(FIELD_ITEMS, items);
    result.put(FIELD_COLUMNS, columns);
    result.put(FIELD_TOTAL_COUNT, totalCount);
    result.put(FIELD_HAS_MORE, offset + limit < totalCount);
    return NeoResponse.ok(result);
  }

  static JSONArray buildGridColumnMetadata(List<RichFieldMeta> gridFields)
      throws Exception {
    JSONArray columns = new JSONArray();
    for (RichFieldMeta fieldMeta : gridFields) {
      JSONObject col = new JSONObject();
      col.put(FIELD_KEY, fieldMeta.propertyKey);
      col.put(FIELD_LABEL, fieldMeta.label);
      col.put(FIELD_PATH, fieldMeta.property);
      columns.put(col);
    }
    return columns;
  }

  static String normalizeEntityId(String rawId) {
    if (rawId != null && rawId.length() == 64 && rawId.matches("[0-9A-Fa-f]{64}")) {
      return rawId.substring(32);
    }
    return rawId;
  }
}
