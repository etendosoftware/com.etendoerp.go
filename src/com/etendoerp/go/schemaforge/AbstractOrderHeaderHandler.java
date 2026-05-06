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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Shared base for order-type header handlers (Sales Order, Purchase Order).
 *
 * <p>The {@code afterHandle} post-hook appends {@code hasLinkedDocuments} to every
 * record in GET responses. Single-record GETs use a LIMIT 1 query; list GETs
 * use a single batch IN query to avoid N+1. Subclasses only need to implement
 * {@code handle()} with their window-specific action dispatching.
 *
 * <p>The static helper {@link #applyTotalDiscountBeforeComplete(NeoContext, TotalDiscountService, boolean)}
 * is called from the pre-hook ({@code handle()}) of each header subclass. It creates the discount
 * line just before the Complete action (documentAction=CO) is processed by the CRUD layer, so the
 * discount line reflects the final set of product lines and is included in the completed document.
 */
public abstract class AbstractOrderHeaderHandler implements NeoHandler {

  private final Logger log = LogManager.getLogger(getClass());

  /**
   * Creates (or re-creates) the total discount line immediately before the Complete action
   * (documentAction=CO) is processed by the default handler.
   *
   * <p>Must be called at the top of {@code handle()} in every header subclass that supports
   * total discount. It intercepts three paths:
   * <ul>
   *   <li><b>CRUD PATCH/PUT</b> — body contains {@code { documentAction: "CO" }}</li>
   *   <li><b>ACTION POST /documentAction</b> — frontend confirm button sends
   *       POST to {@code /action/documentAction} with body
   *       {@code { fieldValues: { documentAction: "CO" } }}</li>
   *   <li><b>ACTION POST /DocAction</b> — quotation {@code SendToEvaluationModal} sends
   *       POST to {@code /action/DocAction} with {@code { fieldValues: {} }}.
   *       This always syncs the discount line regardless of the stored action value.</li>
   * </ul>
   *
   * @param context   the current NeoContext
   * @param service   the TotalDiscountService CDI bean injected by the subclass
   * @param isInvoice {@code true} for invoice documents, {@code false} for order documents
   */
  static void applyTotalDiscountBeforeComplete(NeoContext context, TotalDiscountService service,
      boolean isInvoice) {
    if (service == null) {
      return;
    }
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isEmpty()) {
      return;
    }

    boolean isComplete = false;

    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      // CRUD: PATCH/PUT with { documentAction: "CO" } in the body.
      String method = context.getHttpMethod();
      if ("PATCH".equals(method) || "PUT".equals(method)) {
        JSONObject body = context.getRequestBody();
        if (body != null && "CO".equals(body.optString("documentAction", ""))) {
          isComplete = true;
        }
      }
    } else if (NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      // ACTION: POST to /action/documentAction.
      // Body format varies by caller:
      //   useDocumentAction hook  → { docAction: "CO" }
      //   handleSaveAndProcess    → { fieldValues: { documentAction: "CO" } }
      if ("documentAction".equals(context.getFieldName())) {
        JSONObject body = context.getRequestBody();
        if (body != null) {
          JSONObject fieldValues = body.optJSONObject("fieldValues");
          String docAction;
          if (fieldValues != null) {
            docAction = fieldValues.optString("documentAction", "");
          } else {
            docAction = body.optString("docAction", body.optString("documentAction", ""));
          }
          if ("CO".equals(docAction)) {
            isComplete = true;
          }
        }
      } else if ("DocAction".equals(context.getFieldName())) {
        // DocAction process-button path (used by quotations: SendToEvaluationModal sends
        // POST /action/DocAction { fieldValues: {} } with no explicit docAction value).
        // Always sync: recalculate() reads the stored pct and creates the discount line when
        // pct > 0 (CO path), or deletes any stale line when reopened (RE path).
        isComplete = true;
      }
    }

    if (!isComplete) {
      return;
    }
    try {
      LogManager.getLogger(AbstractOrderHeaderHandler.class)
          .info("Recalculating total discount before complete for {} id={}",
              isInvoice ? "invoice" : "order", recordId);
      service.recalculate(recordId, isInvoice);
    } catch (Exception e) {
      LogManager.getLogger(AbstractOrderHeaderHandler.class)
          .error("Error recalculating total discount before complete for {} id={}",
              isInvoice ? "invoice" : "order", recordId, e);
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      if (context.getRecordId() != null) {
        dataArr.getJSONObject(0).put("hasLinkedDocuments", checkLinkedDocuments(context.getRecordId()));
      } else {
        annotateListWithLinkedDocuments(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error computing hasLinkedDocuments (id={})", context.getRecordId(), e);
      return null;
    }
  }

  private void annotateListWithLinkedDocuments(JSONArray dataArr) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Set<String> withLinked = batchCheckLinkedDocuments(ids);
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      String id = rec.optString("id", null);
      rec.put("hasLinkedDocuments", id != null && withLinked.contains(id));
    }
  }

  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private Set<String> batchCheckLinkedDocuments(List<String> ids) {
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT DISTINCT C_Order_ID FROM C_Invoice WHERE C_Order_ID IN (" + placeholders + ") AND IsActive = 'Y' " +
        "UNION " +
        "SELECT DISTINCT C_Order_ID FROM M_InOut   WHERE C_Order_ID IN (" + placeholders + ") AND IsActive = 'Y'";
    Set<String> result = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) ps.setString(idx++, id);
      for (String id : ids) ps.setString(idx++, id);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    } catch (Exception e) {
      log.error("DB error in batch linked-documents check", e);
    }
    return result;
  }

  private boolean checkLinkedDocuments(String orderId) {
    String sql =
        "SELECT 1 FROM C_Invoice WHERE C_Order_ID = ? AND IsActive = 'Y' " +
        "UNION ALL " +
        "SELECT 1 FROM M_InOut   WHERE C_Order_ID = ? AND IsActive = 'Y' " +
        "LIMIT 1";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, orderId);
      ps.setString(2, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (Exception e) {
      log.error("DB error querying linked documents for order {}", orderId, e);
      return false;
    }
  }
}
