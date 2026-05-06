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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.RejectReason;

/**
 * NeoHandler that creates a new {@link RejectReason} (table {@code C_Reject_Reason})
 * from the React reject-quotation flow when the user clicks "+ Crear razón" in
 * the typeahead. Invoked as an ACTION endpoint via:
 * <pre>POST /sws/neo/sales-quotation/quotation/{recordId}/action/createRejectReason</pre>
 *
 * <p>The endpoint is hosted on the quotation entity for routing convenience —
 * NEO action endpoints carry a {@code recordId}, but this handler doesn't use
 * it (creating a new master-data row, not mutating the quotation). The recordId
 * is required by the URL pattern but ignored here.
 *
 * <p>Request body: {@code { "name": "...", "description"?: "..." }}.
 * Response: HTTP 201 with {@code { id, name }} so the caller can preselect the
 * new row in its typeahead without an extra round-trip.
 *
 * <p>The new {@link RejectReason} is created under the current OBContext client
 * and organization, with {@code active = true}. Duplicate names are not blocked
 * — Etendo allows multiple reject reasons with the same display name as long
 * as the row id differs, matching the behavior of the standard AD window.
 */
@Named("createRejectReasonHandler")
public class CreateRejectReasonHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CreateRejectReasonHandler.class);
  private static final String ACTION_NAME = "createRejectReason";
  private static final String ERR_NAME_REQUIRED = "Name is required";
  private static final String KEY_RESPONSE = "response";
  private static final int NAME_MAX_LENGTH = 60;

  /**
   * Entry point for ACTION requests. Returns {@code null} for any other
   * endpoint type so the dispatcher in {@link SalesQuotationHeaderHandler}
   * can fall through to the next handler.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    JSONObject body = context.getRequestBody();
    String name = body != null ? body.optString("name", "").trim() : "";
    if (StringUtils.isBlank(name)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_NAME_REQUIRED);
    }
    if (name.length() > NAME_MAX_LENGTH) {
      name = name.substring(0, NAME_MAX_LENGTH);
    }
    String description = body != null ? body.optString("description", null) : null;

    try {
      OBContext.setAdminMode(true);
      try {
        RejectReason created = persistNewReason(name, description);
        return buildSuccessResponse(created);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error creating reject reason '{}': {}", name, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating reject reason '{}': {}", name, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the rejection reason");
    }
  }

  /**
   * Persists a new {@link RejectReason} under the current OBContext client and
   * organization with {@code active = true}. The {@code searchKey} is set to
   * the name so the row remains usable from the classic AD window which keys
   * on that column.
   */
  protected RejectReason persistNewReason(String name, String description) {
    RejectReason reason = OBProvider.getInstance().get(RejectReason.class);
    reason.setClient(OBContext.getOBContext().getCurrentClient());
    reason.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    reason.setActive(true);
    reason.setName(name);
    reason.setSearchKey(name);
    if (StringUtils.isNotBlank(description)) {
      reason.setDescription(description);
    }
    OBDal.getInstance().save(reason);
    OBDal.getInstance().flush();
    return reason;
  }

  /**
   * Builds the HTTP 201 envelope returned to the React modal. Echoes back the
   * id and name so the caller can append the new row to its typeahead cache
   * and preselect it.
   */
  protected NeoResponse buildSuccessResponse(RejectReason reason) throws JSONException {
    JSONObject data = new JSONObject();
    data.put("id", reason.getId());
    data.put("name", reason.getName());

    JSONObject responseData = new JSONObject();
    responseData.put("data", data);

    JSONObject wrapper = new JSONObject();
    wrapper.put(KEY_RESPONSE, responseData);

    return NeoResponse.created(wrapper);
  }
}
