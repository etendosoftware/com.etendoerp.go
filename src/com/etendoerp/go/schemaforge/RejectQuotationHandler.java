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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.RejectReason;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * NeoHandler that closes a Sales Quotation as "Closed - Rejected" (DocStatus
 * {@code CJ}). Invoked as an ACTION endpoint via:
 * <pre>POST /sws/neo/sales-quotation/quotation/{id}/action/rejectQuotation</pre>
 * Request body must carry the chosen rejection reason as
 * {@code { "rejectReason": "<C_Reject_Reason_ID>" }}.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code recordId} is required.</li>
 *   <li>The quotation must exist and currently be in {@code UE} (Under
 *       Evaluation). Any other status is rejected with HTTP 400.</li>
 *   <li>The supplied {@code rejectReason} must resolve to an active
 *       {@link RejectReason} row.</li>
 * </ul>
 *
 * <p><b>Also reached through {@code DocAction = RJ} (ETP-5535).</b> The {@code DocAction} button
 * lists "Reject" among its values, so an agent reads it as the way to reject a quotation. Run through
 * {@code C_Order_Post} it fails for want of a reason the caller cannot set ({@code rejectReason} is
 * read-only): core raises {@code @NoRejectReason@}. {@link SalesQuotationHeaderHandler} therefore
 * routes that request to {@link #reject}, the same flow this action runs for the UI, on every
 * channel. The reason travels in the same body, as {@code rejectReason}.</p>
 *
 * <p>On success the quotation is updated via direct OBDal writes (no
 * {@code C_Order_Post} invocation, mirroring the pattern used by
 * {@link CreateDraftInvoiceHandler#markQuotationAsInvoiceCreated(String)}):
 * <ul>
 *   <li>{@code DocStatus} → {@code CJ}.</li>
 *   <li>{@code C_Reject_Reason_ID} → the supplied id.</li>
 *   <li>{@code Processed} → {@code true} so the field-level
 *       {@code readOnlyLogic = @Processed@='Y'} locks the form.</li>
 * </ul>
 */
@Named("rejectQuotationHandler")
public class RejectQuotationHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(RejectQuotationHandler.class);
  private static final String ACTION_NAME = "rejectQuotation";
  private static final String STATUS_REJECTED = "CJ";
  private static final String STATUS_UNDER_EVALUATION = "UE";
  private static final String FIELD_REJECT_REASON = "rejectReason";
  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";
  /**
   * ETP-5535: names where the id comes from. The React modal cannot submit without a selected
   * reason, so only an API caller ever reads this message.
   */
  private static final String ERR_REASON_REQUIRED = "A rejection reason is required: send "
      + "rejectReason with the id of an active rejection reason (C_Reject_Reason_ID). List them "
      + "with the rejectReason selector of sales-quotation/quotation, or create one with the "
      + "createRejectReason action.";
  private static final String KEY_FIELD_VALUES = "fieldValues";
  private static final String ERR_REASON_NOT_FOUND = "Rejection reason not found: ";
  private static final String ERR_QUOTATION_NOT_FOUND = "Quotation not found: ";
  private static final String ERR_QUOTATION_NOT_UE = "Only quotations in Under Evaluation can be rejected";
  private static final String KEY_RESPONSE = "response";

  /**
   * The action as {@code etendo_schema(view:"actions")} publishes it (ETP-5535), declared by
   * {@link SalesQuotationHeaderHandler#actionContracts()}. Only for discovery: the body is still
   * judged by {@link #reject}, not by {@link NeoActionContract#validate}, so the React modal's
   * request is accepted exactly as before.
   */
  static final NeoActionContract CONTRACT = NeoActionContract.write(ACTION_NAME,
      "Rejects a quotation in Under Evaluation (documentStatus UE): it becomes Closed - Rejected "
          + "(CJ), keeps the reason and is locked. The same flow as the Reject button of the UI. "
          + "Also reached by DocAction with docAction RJ, which takes the same rejectReason.",
      NeoActionContract.Param.required(FIELD_REJECT_REASON, NeoActionContract.TYPE_STRING,
          "Id of an active rejection reason (C_Reject_Reason_ID). List them with the "
              + "rejectReason selector of this entity, or create one with createRejectReason."))
      .withIdDescription("The id of the quotation to reject.");

  /**
   * Entry point for ACTION requests. Returns {@code null} for any other
   * endpoint type so the dispatcher in {@link SalesQuotationHeaderHandler}
   * can fall through to the default CRUD pipeline.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName()) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }
    return reject(context);
  }

  /**
   * Rejects the quotation named by the context's record id with the reason carried in its body.
   * The action-name check is the caller's: {@link #handle} runs it for {@code rejectQuotation}, and
   * {@link SalesQuotationHeaderHandler} for {@code DocAction = RJ} (ETP-5535). Both therefore get
   * the same validation, the same writes and the same response.
   *
   * @param context an ACTION context whose record id is the quotation
   * @return the success envelope, or a 400 naming what is missing or wrong
   */
  public NeoResponse reject(NeoContext context) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }

    String reasonId = extractReasonId(context.getRequestBody());
    if (StringUtils.isBlank(reasonId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_REASON_REQUIRED);
    }

    try {
      OBContext.setAdminMode(true);
      try {
        Order quotation = OBDal.getInstance().get(Order.class, recordId);
        if (quotation == null) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_QUOTATION_NOT_FOUND + recordId);
        }
        if (!STATUS_UNDER_EVALUATION.equals(quotation.getDocumentStatus())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_QUOTATION_NOT_UE);
        }

        RejectReason reason = OBDal.getInstance().get(RejectReason.class, reasonId);
        if (reason == null || !Boolean.TRUE.equals(reason.isActive())) {
          return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_REASON_NOT_FOUND + reasonId);
        }

        quotation.setRejectReason(reason);
        quotation.setDocumentStatus(STATUS_REJECTED);
        quotation.setProcessed(true);
        OBDal.getInstance().save(quotation);
        OBDal.getInstance().flush();

        return buildSuccessResponse(quotation);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      log.warn("Error rejecting quotation {}: {}", recordId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error rejecting quotation {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while rejecting the quotation");
    }
  }

  /**
   * Reads {@code rejectReason} from the request body. Accepts both the camelCase
   * key (the one the React modal sends, matching the entity field name) and the
   * Etendo column name {@code C_Reject_Reason_ID} as a defensive fallback.
   *
   * <p>ETP-5535: when the root carries neither, the same two keys are read from a nested
   * {@code fieldValues} object, the body shape of a {@code DocAction} button request. The root
   * still wins, so the modal's request is read exactly as before.
   */
  protected String extractReasonId(JSONObject body) {
    if (body == null) {
      return null;
    }
    String reasonId = readReasonId(body);
    if (reasonId == null) {
      reasonId = readReasonId(body.optJSONObject(KEY_FIELD_VALUES));
    }
    return reasonId;
  }

  private static String readReasonId(JSONObject source) {
    if (source == null) {
      return null;
    }
    String reasonId = source.optString(FIELD_REJECT_REASON, null);
    if (StringUtils.isBlank(reasonId)) {
      reasonId = source.optString("C_Reject_Reason_ID", null);
    }
    return StringUtils.isBlank(reasonId) ? null : reasonId;
  }

  /**
   * Builds the HTTP 200 envelope returned to the React modal. Echoes back the
   * id, documentNo, and the new documentStatus so the caller can update its
   * local state without an extra round-trip.
   */
  protected NeoResponse buildSuccessResponse(Order quotation) throws JSONException {
    JSONObject data = new JSONObject();
    data.put("id", quotation.getId());
    data.put("documentNo", quotation.getDocumentNo());
    data.put("documentStatus", quotation.getDocumentStatus());

    JSONObject responseData = new JSONObject();
    responseData.put("data", data);

    JSONObject wrapper = new JSONObject();
    wrapper.put(KEY_RESPONSE, responseData);

    return NeoResponse.ok(wrapper);
  }
}
