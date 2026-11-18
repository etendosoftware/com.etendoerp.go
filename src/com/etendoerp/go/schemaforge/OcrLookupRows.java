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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.plm.Product;

/** Pure row and id helpers of {@link PurchaseInvoiceOcrLookups}. */
final class OcrLookupRows {

  private static final String KEY_ID = "id";
  private static final String KEY_NAME = "name";
  private static final String KEY_DATA = "data";

  private OcrLookupRows() {
  }

  /** The category ids (or unit ids) of the products, once each. */
  static Collection<String> idsOf(List<Product> products, boolean categories) {
    Set<String> out = new LinkedHashSet<>();
    for (Product product : products) {
      if (categories && product.getProductCategory() != null) {
        out.add(product.getProductCategory().getId());
      } else if (!categories && product.getUOM() != null) {
        out.add(product.getUOM().getId());
      }
    }
    return out;
  }

  /** Non-blank ids, once each, in first-seen order. */
  static Collection<String> distinctIds(Collection<String> ids) {
    Set<String> out = new LinkedHashSet<>();
    if (ids != null) {
      for (String id : ids) {
        if (!StringUtils.isBlank(id)) {
          out.add(id);
        }
      }
    }
    return out;
  }

  /** The rows of a similarity answer, which carries them under {@code data} or {@code response.data}. */
  static JSONArray rowsOf(JSONObject found) {
    if (found == null) {
      return null;
    }
    JSONArray data = found.optJSONArray(KEY_DATA);
    if (data != null) {
      return data;
    }
    JSONObject response = found.optJSONObject("response");
    return response == null ? null : response.optJSONArray(KEY_DATA);
  }

  static String labelOf(JSONObject item) {
    for (String key : new String[] { KEY_NAME, "label", "_identifier" }) {
      String value = item.optString(key, "");
      if (!value.isEmpty()) {
        return value;
      }
    }
    return item.optString(KEY_ID);
  }
}
