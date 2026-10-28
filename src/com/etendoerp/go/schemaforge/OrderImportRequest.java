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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * One order of an {@code importOrderLines} request into a draft invoice (ETP-5828), and the parsing
 * of that request's body: {@code orders:[{orderId, lines?}]} or the single {@code orderId} +
 * {@code lines}, plus {@code carryGlobalDiscount}. Used only by
 * {@link ImportOrderLinesIntoInvoiceHandler}.
 */
final class OrderImportRequest {

  static final String PARAM_ORDER_ID = "orderId";
  static final String PARAM_ORDERS = "orders";
  static final String PARAM_CARRY_DISCOUNT = "carryGlobalDiscount";
  /** Most orders one call takes (and most rows a discovery returns). */
  static final int MAX_ORDERS = 50;

  final String orderId;
  /** {@code sourceLineId -> quantity}; empty means everything still pending. */
  final Map<String, BigDecimal> requested;

  OrderImportRequest(String orderId, Map<String, BigDecimal> requested) {
    this.orderId = orderId;
    this.requested = requested;
  }

  /**
   * The orders of the request: {@code orders:[{orderId, lines?}]}, or the single
   * {@code orderId} + {@code lines} of the original shape; a key sent as JSON null counts as
   * absent (MCP clients fill optional parameters with null). Mixing both, an empty or oversized
   * {@code orders}, an entry without {@code orderId} or the same order twice is a 400.
   */
  static List<OrderImportRequest> parseOrderRequests(JSONObject body) {
    if (!present(body, PARAM_ORDERS)) {
      return List.of(new OrderImportRequest(
          AbstractLineImportHandler.requireParam(body, PARAM_ORDER_ID),
          DocumentLineImport.parseRequestedLines(body)));
    }
    JSONArray orders = body.optJSONArray(PARAM_ORDERS);
    if (orders == null || orders.length() == 0 || orders.length() > MAX_ORDERS) {
      throw badRequest(PARAM_ORDERS + " must be a list of 1 to " + MAX_ORDERS
          + " entries {orderId, lines?}");
    }
    if (present(body, PARAM_ORDER_ID) || present(body, DocumentLineImport.KEY_LINES)) {
      throw badRequest("Send either orderId (+ lines) or orders, not both: the lines of each "
          + "order go inside its orders entry");
    }
    List<OrderImportRequest> requests = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < orders.length(); i++) {
      JSONObject entry = orders.optJSONObject(i);
      String orderId = DocumentLineImport.text(entry, PARAM_ORDER_ID);
      if (orderId == null) {
        throw badRequest(PARAM_ORDERS + "[" + i + "] needs an orderId");
      }
      if (!seen.add(orderId)) {
        throw badRequest("Order " + orderId + " is listed twice in " + PARAM_ORDERS
            + ": put all its lines in one entry");
      }
      requests.add(new OrderImportRequest(orderId, DocumentLineImport.parseRequestedLines(entry)));
    }
    return requests;
  }

  /** {@code carryGlobalDiscount}: absent or null is {@code true}; a non-boolean is a 400. */
  static boolean parseCarryDiscount(JSONObject body) {
    Object value = body == null ? null : body.opt(PARAM_CARRY_DISCOUNT);
    if (value == null || JSONObject.NULL.equals(value)) {
      return true;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String text = String.valueOf(value).trim();
    if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
      return Boolean.parseBoolean(text);
    }
    throw badRequest(PARAM_CARRY_DISCOUNT + " must be true or false");
  }

  /** Whether {@code key} carries a value: a key sent as JSON null counts as absent. */
  private static boolean present(JSONObject body, String key) {
    return body != null && body.has(key) && !body.isNull(key);
  }

  private static DocumentLineImport.ImportRefusal badRequest(String message) {
    return new DocumentLineImport.ImportRefusal(HttpServletResponse.SC_BAD_REQUEST,
        DocumentLineImport.ERROR_INVALID_LINE, message, null);
  }
}
