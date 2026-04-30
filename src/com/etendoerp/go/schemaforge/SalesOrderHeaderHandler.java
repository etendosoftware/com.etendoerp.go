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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the Sales Order header entity.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createShipment} → {@link CreateShipmentHandler}</li>
 *   <li>{@code createDraftInvoice} / {@code checkDraftInvoice} / {@code listInvoices} → {@link CreateDraftInvoiceHandler}</li>
 * </ul>
 *
 * On GET requests the post-hook appends {@code hasLinkedDocuments} to every
 * record in the response. For single-record GET the check runs per ID; for list
 * GET a single batch query covers all IDs at once. The frontend uses this field
 * to hide the Reactivate action (detail view) and to count skipped rows as errors
 * in the bulk completion dialog.
 */
@Named("salesOrderHeaderHandler")
public class SalesOrderHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(SalesOrderHeaderHandler.class);

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse result = cloneRecordHandler.handle(context);
    if (result != null) {
      return result;
    }
    result = new CreateShipmentHandler().handle(context);
    if (result != null) {
      return result;
    }
    return new CreateDraftInvoiceHandler().handle(context);
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
        // Single-record GET
        dataArr.getJSONObject(0).put("hasLinkedDocuments", checkLinkedDocuments(context.getRecordId()));
      } else {
        // List GET — batch query to avoid N+1
        annotateListWithLinkedDocuments(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("SalesOrderHeaderHandler: error computing hasLinkedDocuments (id={})", context.getRecordId(), e);
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
      log.error("SalesOrderHeaderHandler: DB error in batch linked-documents check", e);
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
      log.error("SalesOrderHeaderHandler: DB error querying linked documents for order {}", orderId, e);
      return false;
    }
  }
}
