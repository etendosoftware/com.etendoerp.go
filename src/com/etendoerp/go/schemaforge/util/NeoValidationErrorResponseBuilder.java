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

package com.etendoerp.go.schemaforge.util;

import java.util.Iterator;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Builds the structured 400 response for a {@code RPCREQUEST_STATUS_VALIDATION_ERROR} body.
 *
 * <p>ETP-5323: {@code DefaultJsonDataService.update()} catches a per-property setter failure
 * (e.g. {@code StringPropertyValidator} rejecting a value that exceeds its AD column's field
 * length) during JSON-to-entity conversion and reports it as a
 * {@code RPCREQUEST_STATUS_VALIDATION_ERROR} response, never as a thrown exception. Unlike the
 * {@code RPCREQUEST_STATUS_FAILURE} branch handled directly in {@code NeoCrudHandler}, this body
 * has no top-level {@code error} object — the raw {@code responseJson} used to be returned
 * verbatim as the response body, a shape {@code parseBackendErrorMessage} (app-shell) does not
 * recognize, so the frontend fell back to the bare {@code "Error 400"} even though the real
 * message was sitting one level down.
 *
 * <p>The failing property's message lives under {@code response.errors.<propertyName>} —
 * {@code DefaultJsonDataService} keys it by property name because a single request can touch
 * several bobs/properties, but in practice the reported case is a single offending field. The
 * FIRST entry is taken (deterministic per-request; multiple simultaneous property validation
 * failures are rare and still land inside the same JSON body for a curious caller), translated
 * and sanitized exactly like the FAILURE branch so behavior stays consistent between the two
 * ways core can report a rejected write.
 *
 * <p>Extracted out of {@code NeoCrudHandler} (ETP-5323 follow-up) purely to keep that class
 * under SonarQube's method-count limit (java:S1448); no behavior change.
 */
public final class NeoValidationErrorResponseBuilder {

  private NeoValidationErrorResponseBuilder() {
    // Utility class: no instances.
  }

  /**
   * Translates and sanitizes the first {@code response.errors} entry into a structured 400
   * {@link NeoResponse}, falling back to a generic message when the errors map is empty.
   *
   * @param innerResponse the parsed {@code response} object from the JsonDataService body
   * @return the structured 400 response, or a generic fallback if the errors map is empty
   */
  public static NeoResponse build(JSONObject innerResponse) {
    JSONObject errors = innerResponse.optJSONObject(JsonConstants.RESPONSE_ERRORS);
    if (errors == null || errors.length() == 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Validation failed");
    }
    String rawMsg = "Validation failed";
    Iterator<String> keys = errors.keys();
    if (keys.hasNext()) {
      rawMsg = errors.optString(keys.next(), rawMsg);
    }
    String translated = OBMessageUtils.messageBD(rawMsg);
    // Same defence-in-depth as the FAILURE branch: strip row dumps / object references before
    // this reaches the client.
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        NeoErrorSanitizer.stripRowDump(NeoErrorSanitizer.redactObjectReferences(
            NeoListReferenceError.enrich(translated))));
  }
}
