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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * Base class for {@link NeoHandler} implementations that perform smart deactivation.
 *
 * <p>Provides the standard pre-hook skeleton: guard on PUT method, guard on explicit
 * {@code active=false}, run {@link #smartDeactivate} under admin mode. Any unexpected exception
 * surfaces as a 500 rather than silently falling through to the default CRUD — falling through
 * would bypass the pending-invoices check and allow deactivation without verification.
 * Concrete handlers supply only {@link #smartDeactivate} and their {@code @Named} qualifier.
 *
 * <p>{@link #deletedResponse()} and {@link #isExplicitlyDeactivating} are {@code protected
 * static} for use in subclass {@code smartDeactivate} and {@code afterHandle} implementations.
 */
public abstract class AbstractSmartDeactivationHandler implements NeoHandler {

  protected static final String METHOD_PUT = "PUT";
  private static final String FIELD_ACTIVE = "active";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!METHOD_PUT.equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    if (!isExplicitlyDeactivating(context.getRequestBody())) {
      return null;
    }
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        return smartDeactivate(recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      LogManager.getLogger(getClass()).error(
          "handle: unexpected error during smart deactivation for {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error checking deactivation conditions: " + e.getMessage());
    }
  }

  protected abstract NeoResponse smartDeactivate(String recordId) throws JSONException;

  protected static NeoResponse deletedResponse() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("deleted", true);
    return NeoResponse.ok(body);
  }

  protected static boolean isExplicitlyDeactivating(JSONObject body) {
    if (body == null || !body.has(FIELD_ACTIVE)) {
      return false;
    }
    Object value = body.opt(FIELD_ACTIVE);
    if (value instanceof Boolean) {
      return !(Boolean) value;
    }
    if (value instanceof String) {
      return "false".equalsIgnoreCase((String) value) || "N".equalsIgnoreCase((String) value);
    }
    return false;
  }
}
