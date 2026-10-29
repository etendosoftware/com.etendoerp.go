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

/**
 * Builds the stable REST response for a write refused by {@link NeoReferenceActiveness}
 * (ETP-5802): a reference field was set to a value whose target row is inactive.
 *
 * <p>Sibling of {@link NeoReadOnlyFieldResponse}: same 422 status, same flat
 * status/error/detail/field/hint/seeAlso shape, so an MCP agent and the React client branch on the
 * same {@code error} discriminator rather than on prose.
 */
final class NeoInactiveReferenceResponse {

  static final String ERROR_CODE = "INACTIVE_REFERENCE_VALUE";

  private static final int STATUS_UNPROCESSABLE = 422;
  private static final Logger log = LogManager.getLogger(NeoInactiveReferenceResponse.class);

  private NeoInactiveReferenceResponse() {
  }

  static NeoResponse build(NeoReferenceActiveness.InactiveReference inactive) {
    try {
      String fieldName = inactive.fieldName();
      String targetEntityName = inactive.targetEntityName();
      JSONObject error = new JSONObject();
      error.put(NeoCrudHandler.KEY_STATUS, STATUS_UNPROCESSABLE);
      error.put(NeoCrudHandler.KEY_ERROR, ERROR_CODE);
      error.put(NeoCrudHandler.KEY_DETAIL, "Field '" + fieldName + "' was set to a record of '"
          + targetEntityName + "' that is inactive; the write was rejected rather than silently"
          + " accepted, so the record does not end up carrying a value the UI can no longer offer.");
      error.put(NeoCrudHandler.KEY_FIELD, fieldName);
      error.put(NeoCrudHandler.KEY_HINT, "Choose an active record for '" + fieldName
          + "' — list or search the entity again; the inactive id is no longer a valid selection.");
      error.put(NeoCrudHandler.KEY_SEE_ALSO, "docs(topic:\"creating records\")");
      return NeoResponse.error(STATUS_UNPROCESSABLE, error);
    } catch (Exception e) {
      log.warn("Could not build {} body: {}", ERROR_CODE, e.getMessage());
      return NeoResponse.error(STATUS_UNPROCESSABLE, ERROR_CODE);
    }
  }
}
