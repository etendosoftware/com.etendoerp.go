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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/** Builds the stable REST response for a rejected curated read-only write. */
final class NeoReadOnlyFieldResponse {

  private static final int STATUS_UNPROCESSABLE = 422;
  private static final Logger log = LogManager.getLogger(NeoReadOnlyFieldResponse.class);

  private NeoReadOnlyFieldResponse() {
  }

  static NeoResponse build(ReadOnlyFieldRejectedException exception) {
    try {
      String fieldName = exception.getFieldName();
      JSONObject error = new JSONObject();
      error.put("status", STATUS_UNPROCESSABLE);
      error.put("error", "read_only_field");
      error.put("detail", "Field '" + fieldName
          + "' is read-only and cannot be set by the caller; its value was rejected, not silently"
          + " dropped, so the write does not answer 200 with the field left unset.");
      error.put("field", fieldName);
      error.put("hint", "Remove '" + fieldName + "' from the request. If this value must "
          + "be set, it is derived automatically (e.g. by a callout or a dedicated write path) — "
          + "check neo_schema's field descriptor for this entity before retrying.");
      error.put("seeAlso", "docs(topic:\"creating records\")");
      return NeoResponse.error(STATUS_UNPROCESSABLE, error);
    } catch (Exception e) {
      log.warn("Could not build READ_ONLY_FIELD_REJECTED body: {}", e.getMessage());
      return NeoResponse.error(STATUS_UNPROCESSABLE, ReadOnlyFieldRejectedException.ERROR_CODE);
    }
  }
}
