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

import java.util.List;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;

/**
 * The HTTP face of {@link FollowUpDocumentService}: serves
 * {@code POST /sws/neo/{spec}/{entity}/{id}/action/<name>} for every {@link FollowUpFlow} a header
 * handler registered, by creating that flow's follow-up document for record {@code id} (ETP-5576).
 * The flow is selected by matching the request's action name against
 * {@link FollowUpTarget#getActionName()} — the only comparison made here; no spec or entity name
 * is compared (the former {@code CreateInvoiceShipmentHandler} compared {@code "sales-invoice"}).
 *
 * <p><b>Not a CDI bean, on purpose.</b> It is obtained from {@link FollowUpSupport#actionHandler()}
 * and plugged into the source entity's {@link NeoHeaderActionRouter#dispatch} chain; being reached
 * only through that entity's own handler IS the binding.
 *
 * <p>Response — {@code 201}:
 * <pre>{"response":{"data":{"id":"…","documentNo":"…","followUp":"shipment",
 *   "spec":"goods-shipment","entity":"goodsShipment","lineCount":2}}}</pre>
 * Business rejection — {@link FollowUpException.Reason#getHttpStatus()} (404 / 400):
 * <pre>{"error":{"code":"FOLLOW_UP_NOTHING_PENDING","status":400,"message":"…"}}</pre>
 * A record outside the caller's readable clients/organizations ({@link TenantOwnership}) answers
 * {@code FOLLOW_UP_SOURCE_NOT_FOUND}, indistinguishable from a missing one.
 * Unexpected failure — {@code 500} with a generic message. Every non-2xx path rolls the
 * transaction back, so a rejected request never leaves a header or a link behind.
 */
final class FollowUpActionHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FollowUpActionHandler.class);

  private final Supplier<List<FollowUpFlow>> flows;

  /** @param flows the handler's registered flows; read once per request */
  FollowUpActionHandler(Supplier<List<FollowUpFlow>> flows) {
    this.flows = flows;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    FollowUpFlow flow = findByAction(flows.get(), context.getFieldName());
    if (flow == null) {
      return null;
    }
    FollowUpTarget target = flow.target();
    String actionName = target.getActionName();
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        // ETP-5576 review W4: NeoRequestRouter#handleWindowSpecRequest only checks the ROLE's
        // access to the spec's window for this method; nothing checks that THIS record belongs to
        // the caller's tenant, and everything below runs in admin mode. Same guard and same
        // answer as every other id-addressed NEO action: another tenant's record is "not found".
        if (TenantOwnership.loadOwned(flow.sourceEntity(), recordId) == null) {
          throw new FollowUpException(FollowUpException.Reason.NOT_FOUND);
        }
        TargetCreator.Result result = FollowUpDocumentService.create(recordId, flow);
        JSONObject data = new JSONObject();
        data.put("id", result.getId());
        data.put("documentNo", result.getDocumentNo());
        data.put("followUp", target.getKey());
        data.put("spec", target.getSpec());
        data.put("entity", target.getEntity());
        data.put("lineCount", result.getLineCount());
        return NeoResponse.createdWithData(data);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (FollowUpException e) {
      SessionHandler.getInstance().rollback();
      log.warn("Follow-up '{}' rejected for record {}: {} ({})", actionName, recordId,
          e.getMessage(), e.getReason().getCode());
      return rejection(e);
    } catch (OBException e) {
      SessionHandler.getInstance().rollback();
      log.warn("Follow-up '{}' failed for record {}: {}", actionName, recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      SessionHandler.getInstance().rollback();
      log.error("Follow-up '{}' failed for record {}", actionName, recordId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while creating the document");
    }
  }

  /** The first registered flow serving {@code actionName}, or {@code null}. */
  static FollowUpFlow findByAction(List<FollowUpFlow> registered, String actionName) {
    if (registered == null || actionName == null) {
      return null;
    }
    for (FollowUpFlow candidate : registered) {
      if (actionName.equals(candidate.target().getActionName())) {
        return candidate;
      }
    }
    return null;
  }

  /** Structured rejection body, same shape as {@code PRECONDITIONS_UNMET}. */
  static NeoResponse rejection(FollowUpException e) {
    int status = e.getReason().getHttpStatus();
    try {
      JSONObject errorObj = new JSONObject();
      errorObj.put("code", e.getReason().getCode());
      errorObj.put("status", status);
      errorObj.put("message", e.getMessage());
      JSONObject body = new JSONObject();
      body.put("error", errorObj);
      return NeoResponse.error(status, body);
    } catch (JSONException jsonError) {
      return NeoResponse.error(status, e.getMessage());
    }
  }
}
