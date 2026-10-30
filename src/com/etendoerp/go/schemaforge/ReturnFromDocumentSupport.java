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

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * What both "create the return from the receipt / shipment" actions have in common (ETP-5711):
 * {@code createPurchaseReturn} on a goods receipt ({@link CreatePurchaseReturnHandler}) and
 * {@code createReturn} on a goods shipment ({@link NeoReturnReceiptService}).
 *
 * <ul>
 *   <li>the <b>plan</b>: which source lines go back and how many units each — the lines the caller
 *       listed, or, when it listed none, <b>everything still returnable</b> — checked against
 *       {@link ReturnableQuantity} before anything is created;</li>
 *   <li>the <b>answer</b>: the new return's id, number and status and each of its lines with the
 *       source line it returns, so an agent can report the linkage;</li>
 *   <li>the <b>contract</b> both are published with.</li>
 * </ul>
 *
 * <p>The return is always left in DRAFT: neither action completes it.
 */
final class ReturnFromDocumentSupport {

  private static final Logger log = LogManager.getLogger(ReturnFromDocumentSupport.class);

  /** The reason does not fit the description column of the return. */
  static final String ERROR_REASON_TOO_LONG = "returnReasonTooLong";

  /** Length of {@code M_InOut.Description} in the AD, used only if the model cannot be read. */
  private static final int DESCRIPTION_FALLBACK_LENGTH = 255;

  static final String PARAM_LINES = "lines";
  static final String PARAM_REASON = "reason";
  static final String KEY_LINE_ID = "lineId";
  static final String KEY_RETURN_QUANTITY = "returnQuantity";

  private ReturnFromDocumentSupport() {
  }

  /** What to return, or the refusal that stops it. Exactly one of the two is set. */
  static final class Plan {
    final ReturnableQuantity.Requests requests;
    final NeoResponse refusal;

    private Plan(ReturnableQuantity.Requests requests, NeoResponse refusal) {
      this.requests = requests;
      this.refusal = refusal;
    }

    static Plan of(ReturnableQuantity.Requests requests) {
      return new Plan(requests, null);
    }

    static Plan refused(NeoResponse refusal) {
      return new Plan(null, refusal);
    }
  }

  /** A return line that was created, with the source line it returns and its quantity. */
  static final class CreatedLine {
    final ShipmentInOutLine line;
    final ShipmentInOutLine source;
    final BigDecimal quantity;

    CreatedLine(ShipmentInOutLine line, ShipmentInOutLine source, BigDecimal quantity) {
      this.line = line;
      this.source = source;
      this.quantity = quantity;
    }
  }

  /**
   * Decides what a create-from-document request returns. With no {@code lines} (absent or empty) it
   * is every line of {@code source} for what is still returnable; with {@code lines} it is exactly
   * those, the quantities of a line listed twice summed. Either way the quantities are checked
   * against what is left before the caller creates anything.
   */
  static Plan plan(ShipmentInOut source, JSONObject body) throws SQLException {
    if (!"CO".equals(source.getDocumentStatus())) {
      return Plan.refused(ReturnableQuantity.refusal(ReturnableQuantity.ERROR_SOURCE_NOT_COMPLETED,
          "The document must be completed before goods can be returned from it."));
    }
    NeoResponse tooLong = refuseReasonTooLong(body);
    if (tooLong != null) {
      return Plan.refused(tooLong);
    }
    Map<String, ShipmentInOutLine> eligible = eligibleLines(source);
    JSONArray lines = body == null ? null : body.optJSONArray(PARAM_LINES);
    if (lines == null || lines.length() == 0) {
      return planEverything(eligible);
    }
    return planGivenLines(eligible, lines);
  }

  private static Plan planEverything(Map<String, ShipmentInOutLine> eligible) throws SQLException {
    // The quantities below are read from the database: lock the lines first (ETP-5711, W1).
    ReturnableQuantity.lockSourceLines(eligible.keySet());
    ReturnableQuantity.Requests requests = new ReturnableQuantity.Requests();
    for (ShipmentInOutLine line : eligible.values()) {
      BigDecimal available = ReturnableQuantity.available(line, null);
      if (available.signum() > 0) {
        requests.add(line, available);
      }
    }
    if (requests.isEmpty()) {
      return Plan.refused(ReturnableQuantity.refusal(ReturnableQuantity.ERROR_NOTHING_LEFT,
          "Nothing is left to return from this document: every line has already been returned."));
    }
    return Plan.of(requests);
  }

  private static Plan planGivenLines(Map<String, ShipmentInOutLine> eligible, JSONArray lines)
      throws SQLException {
    ReturnableQuantity.Requests requests = new ReturnableQuantity.Requests();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject entry = lines.optJSONObject(i);
      String lineId = entry == null ? null : entry.optString(KEY_LINE_ID, null);
      BigDecimal quantity = entry == null ? null
          : ReturnableQuantity.parseQuantity(entry.opt(KEY_RETURN_QUANTITY));
      if (StringUtils.isBlank(lineId) || quantity == null || quantity.signum() <= 0) {
        continue;
      }
      ShipmentInOutLine line = eligible.get(lineId);
      if (line == null) {
        return Plan.refused(ReturnableQuantity.refusal(ReturnableQuantity.ERROR_LINE_NOT_IN_DOCUMENT,
            "Line " + lineId + " is not a line of this document."));
      }
      requests.add(line, quantity);
    }
    if (requests.isEmpty()) {
      return Plan.refused(NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "No valid lines to return"));
    }
    NeoResponse exceeded = ReturnableQuantity.refuseIfExceeds(requests);
    return exceeded == null ? Plan.of(requests) : Plan.refused(exceeded);
  }

  /** The active product lines of {@code source}, by id, in document order. */
  private static Map<String, ShipmentInOutLine> eligibleLines(ShipmentInOut source) {
    Map<String, ShipmentInOutLine> eligible = new LinkedHashMap<>();
    for (ShipmentInOutLine line : source.getMaterialMgmtShipmentInOutLineList()) {
      if (line.isActive() && line.getProduct() != null) {
        eligible.put(line.getId(), line);
      }
    }
    return eligible;
  }

  /**
   * The {@code reason} is stored as the description of the return, whose column has a fixed length
   * in the AD. A longer one is refused with a 422 rather than cut: silently dropping the end of what
   * the user wrote is worse than asking them to shorten it, and it matches the other length checks
   * of the module.
   */
  private static NeoResponse refuseReasonTooLong(JSONObject body) {
    String reason = body == null ? null : body.optString(PARAM_REASON, null);
    int max = descriptionMaxLength();
    if (StringUtils.isBlank(reason) || reason.length() <= max) {
      return null;
    }
    return ReturnableQuantity.refusal(ERROR_REASON_TOO_LONG,
        "The reason is too long: it can have at most " + max + " characters (it has "
            + reason.length() + ").");
  }

  /** The AD length of {@code M_InOut.Description}, read from the model. */
  static int descriptionMaxLength() {
    try {
      int length = ModelProvider.getInstance().getEntity(ShipmentInOut.ENTITY_NAME)
          .getProperty(ShipmentInOut.PROPERTY_DESCRIPTION).getFieldLength();
      return length > 0 ? length : DESCRIPTION_FALLBACK_LENGTH;
    } catch (RuntimeException e) {
      log.warn("Could not read the description length of the return, using {}: {}",
          DESCRIPTION_FALLBACK_LENGTH, e.getMessage());
      return DESCRIPTION_FALLBACK_LENGTH;
    }
  }

  /** Keeps the optional {@code reason} of the request as the return's description. */
  static void applyReason(ShipmentInOut returnDoc, JSONObject body) {
    String reason = body == null ? null : body.optString(PARAM_REASON, null);
    if (StringUtils.isNotBlank(reason)) {
      returnDoc.setDescription(reason);
    }
  }

  /**
   * The {@code data} of the 201 an action answers: the new return and each line with the source
   * line it returns. Per line it also says what OTHER returns already hold of that source line
   * ({@code alreadyReturnedQuantity}, same rule as {@link ReturnableQuantity#alreadyReturned}) and
   * which returns those are ({@code returns}: id, documentNo, documentStatus).
   *
   * @param returnDoc the return just created
   * @param created   its lines with the source line each returns
   * @return the {@code data} of the 201
   * @throws JSONException when the answer cannot be built
   */
  static JSONObject describe(ShipmentInOut returnDoc, List<CreatedLine> created)
      throws JSONException {
    JSONObject data = new JSONObject();
    data.put("id", returnDoc.getId());
    data.put("documentNo", returnDoc.getDocumentNo() != null ? returnDoc.getDocumentNo() : "");
    data.put("documentStatus", returnDoc.getDocumentStatus());
    JSONArray lines = new JSONArray();
    for (CreatedLine c : created) {
      JSONObject line = new JSONObject();
      line.put("id", c.line.getId());
      line.put("product", c.source.getProduct() != null ? c.source.getProduct().getId() : null);
      line.put("productName", ReturnableQuantity.productName(c.source));
      line.put("returnedQuantity", c.quantity);
      line.put("originalQuantity",
          c.source.getMovementQuantity() == null ? null : c.source.getMovementQuantity().abs());
      line.put("sourceLineId", c.source.getId());
      putHoldingReturns(line, c.source.getId(), returnDoc.getId());
      lines.put(line);
    }
    data.put(PARAM_LINES, lines);
    return data;
  }

  /**
   * Adds what other returns already hold of one source line, and which returns they are. The data
   * is informational and read after the return was written, so a failing lookup is logged and the
   * two keys are left out: creating a return never fails (or rolls back) because of it.
   */
  private static void putHoldingReturns(JSONObject line, String sourceLineId, String returnDocId)
      throws JSONException {
    List<ReturnableQuantity.HoldingReturn> holding;
    try {
      holding = ReturnableQuantity.returnsHolding(sourceLineId, returnDocId);
    } catch (SQLException | RuntimeException e) {
      log.warn("Could not read the returns holding source line {}: {}", sourceLineId,
          e.getMessage());
      return;
    }
    BigDecimal total = BigDecimal.ZERO;
    JSONArray returns = new JSONArray();
    for (ReturnableQuantity.HoldingReturn h : holding) {
      total = total.add(h.quantity);
      JSONObject r = new JSONObject();
      r.put("id", h.id);
      r.put("documentNo", h.documentNo);
      r.put("documentStatus", h.documentStatus);
      returns.put(r);
    }
    line.put("alreadyReturnedQuantity", total);
    line.put("returns", returns);
  }

  /**
   * The contract an agent sees for a create-from-document action. Both directions share the shape,
   * so one builder keeps the two descriptions from drifting.
   *
   * @param action       the action name ({@code createPurchaseReturn} / {@code createReturn})
   * @param sourceNoun   the document the goods go back from ({@code goods receipt} / {@code goods shipment})
   * @param returnNoun   the document created ({@code return to vendor} / {@code return material receipt})
   * @param nextStep     what the caller does with the draft (the rest of the flow)
   */
  static NeoActionContract contract(String action, String sourceNoun, String returnNoun,
      String nextStep) {
    return NeoActionContract.write(action,
        "Creates a " + returnNoun + " in DRAFT from this completed " + sourceNoun + ", each line "
            + "linked to the " + sourceNoun + " line it returns, and answers 201 with {id, "
            + "documentNo, documentStatus, lines:[{id, product, productName, returnedQuantity, "
            + "originalQuantity, sourceLineId, alreadyReturnedQuantity, returns:[{id, documentNo, "
            + "documentStatus}]}]}, where alreadyReturnedQuantity is what OTHER returns (drafts "
            + "included, voided excluded) already hold of that source line and returns lists "
            + "them. The return is left in draft, never completed here: " + nextStep
            + " Precondition: the " + sourceNoun + " is completed (status CO). Lines: omit lines to "
            + "return everything still returnable of every line (a line with nothing left is "
            + "skipped; when nothing is left on any line it answers 422 nothingLeftToReturn). "
            + "Send lines to return exactly those, a line listed twice counting both. What is "
            + "already returned is every non-voided return line linked to the source line, "
            + "drafts included: asking for more than is left answers 422 "
            + "returnQuantityExceedsAvailable naming the product, the requested and the available "
            + "quantity, and creates nothing. A second partial return with what is left works.",
        NeoActionContract.Param.array(PARAM_LINES, NeoActionContract.TYPE_OBJECT, false,
            "Lines to return, each {lineId, returnQuantity}: lineId is the id of a line of this "
                + sourceNoun + ", returnQuantity a positive number not above what is still "
                + "returnable. Omit to return everything still returnable."),
        NeoActionContract.Param.optional(PARAM_REASON, NeoActionContract.TYPE_STRING,
            "Why the goods go back; kept as the description of the return."))
        .withIdDescription("The id of the completed " + sourceNoun + " to return goods from.")
        .availableWhen(DocumentAvailability.WHEN_COMPLETED);
  }
}
