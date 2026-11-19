/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/**
 * Builds the stable REST response for a write refused by {@link NeoReferenceSelectorValidation}
 * (ETP-6176): a reference field was set to a record the app's selector does not offer in that
 * context. Same 422 flat shape as {@link NeoInactiveReferenceResponse}.
 */
final class NeoInvalidReferenceResponse {

  static final String ERROR_CODE = "REFERENCE_NOT_ALLOWED";

  private static final int STATUS_UNPROCESSABLE = 422;
  private static final Logger log = LogManager.getLogger(NeoInvalidReferenceResponse.class);

  private NeoInvalidReferenceResponse() {
  }

  static String detail(NeoReferenceSelectorValidation.RefusedReference refused) {
    return refused.detail();
  }

  static String hint(NeoReferenceSelectorValidation.RefusedReference refused) {
    return "Choose a value the selector of '" + refused.fieldName() + "' returns for this record"
        + " — query the selector again with the record's current context.";
  }

  static NeoResponse build(NeoReferenceSelectorValidation.RefusedReference refused) {
    try {
      JSONObject error = new JSONObject();
      error.put(NeoCrudHandler.KEY_STATUS, STATUS_UNPROCESSABLE);
      error.put(NeoCrudHandler.KEY_ERROR, ERROR_CODE);
      error.put(NeoCrudHandler.KEY_DETAIL, detail(refused));
      error.put(NeoCrudHandler.KEY_FIELD, refused.fieldName());
      error.put("value", refused.value() == null ? JSONObject.NULL : refused.value());
      error.put(NeoCrudHandler.KEY_HINT, hint(refused));
      error.put(NeoCrudHandler.KEY_SEE_ALSO, "docs(topic:\"creating records\")");
      return NeoResponse.error(STATUS_UNPROCESSABLE, error);
    } catch (Exception e) {
      log.warn("Could not build {} body: {}", ERROR_CODE, e.getMessage());
      return NeoResponse.error(STATUS_UNPROCESSABLE, ERROR_CODE);
    }
  }
}
