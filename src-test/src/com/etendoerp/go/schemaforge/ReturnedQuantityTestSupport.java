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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.BiFunction;

import org.codehaus.jettison.json.JSONObject;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Shared mock builders for the ETP-5711 over-return tests.
 *
 * <p>Every return write path asks {@link ReturnableQuantity} how much of a source line is already
 * returned through one JDBC query, and the import path additionally reads the next line number
 * through another. The same connection fixture and the same source-line mocks were needed by the
 * header, line, create-from-document and utility tests, so they live here once.
 */
final class ReturnedQuantityTestSupport {

  private ReturnedQuantityTestSupport() {
  }

  /**
   * Wires {@code dal.getConnection()} so that the already-returned query answers through
   * {@code returned}, and any other statement (the next-line-number read) answers a single row
   * whose long is {@code maxLineNo}.
   *
   * @param returned receives the source line id and the excluded return line id ({@code null} when
   *                 the query is not asked to exclude one) and returns the already-returned total
   */
  static Connection stubReturnedQuantities(OBDal dal, long maxLineNo,
      BiFunction<String, String, BigDecimal> returned) throws Exception {
    return stubReturnedQuantities(dal, maxLineNo, returned, (src, excl) -> List.of());
  }

  /**
   * The same fixture, also answering the "which returns hold this source line" query of
   * {@link ReturnableQuantity#returnsHolding} (ETP-6054).
   *
   * @param holding receives the source line id and the excluded return document id and returns one
   *                {@code {id, documentNo, documentStatus, quantity}} row per holding return
   */
  static Connection stubReturnedQuantities(OBDal dal, long maxLineNo,
      BiFunction<String, String, BigDecimal> returned,
      BiFunction<String, String, List<String[]>> holding) throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if (isHoldingReturnsQuery(sql)) {
        return holdingStatement(holding);
      }
      return isReturnedQuantityQuery(sql) ? returnedStatement(returned) : lineNoStatement(maxLineNo);
    });
    return conn;
  }

  static boolean isHoldingReturnsQuery(String sql) {
    return sql.contains("GROUP BY rh.M_InOut_ID");
  }

  private static PreparedStatement holdingStatement(
      BiFunction<String, String, List<String[]>> holding) throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    String[] params = new String[2];
    doAnswer(inv -> {
      params[(int) inv.getArgument(0) - 1] = inv.getArgument(1);
      return null;
    }).when(ps).setString(anyInt(), anyString());
    when(ps.executeQuery()).thenAnswer(inv -> {
      List<String[]> rows = holding.apply(params[0], params[1]);
      ResultSet rs = mock(ResultSet.class);
      int[] i = {-1};
      when(rs.next()).thenAnswer(x -> ++i[0] < rows.size());
      when(rs.getString(1)).thenAnswer(x -> rows.get(i[0])[0]);
      when(rs.getString(2)).thenAnswer(x -> rows.get(i[0])[1]);
      when(rs.getString(3)).thenAnswer(x -> rows.get(i[0])[2]);
      when(rs.getBigDecimal(4)).thenAnswer(x -> new BigDecimal(rows.get(i[0])[3]));
      return rs;
    });
    return ps;
  }

  /** The same fixture for tests that do not care about the line number. */
  static Connection stubReturnedQuantities(OBDal dal,
      BiFunction<String, String, BigDecimal> returned) throws Exception {
    return stubReturnedQuantities(dal, 10L, returned);
  }

  static boolean isReturnedQuantityQuery(String sql) {
    return sql.contains("SUM(ABS(rl.MovementQty))") && sql.contains("rl.Canceled_Inoutline_ID = ?")
        && !isHoldingReturnsQuery(sql);
  }

  private static PreparedStatement lineNoStatement(long maxLineNo) throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getLong(1)).thenReturn(maxLineNo);
    return ps;
  }

  private static PreparedStatement returnedStatement(BiFunction<String, String, BigDecimal> returned)
      throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    String[] params = new String[2];
    doAnswer(inv -> {
      params[(int) inv.getArgument(0) - 1] = inv.getArgument(1);
      return null;
    }).when(ps).setString(anyInt(), anyString());
    when(ps.executeQuery()).thenAnswer(inv -> {
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getBigDecimal(1)).thenReturn(returned.apply(params[0], params[1]));
      return rs;
    });
    return ps;
  }

  /** A source (receipt or shipment) line with its product and the quantity it moved. */
  static ShipmentInOutLine sourceLine(String id, String productName, String movementQty) {
    ShipmentInOutLine line = mock(ShipmentInOutLine.class);
    when(line.getId()).thenReturn(id);
    when(line.isActive()).thenReturn(true);
    when(line.getMovementQuantity()).thenReturn(new BigDecimal(movementQty));
    Product product = mock(Product.class);
    when(product.getId()).thenReturn("prod-" + id);
    when(product.getName()).thenReturn(productName);
    when(line.getProduct()).thenReturn(product);
    return line;
  }

  /** A completed source document carrying {@code lines}. */
  static ShipmentInOut completedSource(String id, ShipmentInOutLine... lines) {
    ShipmentInOut source = mock(ShipmentInOut.class);
    when(source.getId()).thenReturn(id);
    when(source.getDocumentStatus()).thenReturn("CO");
    when(source.getMaterialMgmtShipmentInOutLineList()).thenReturn(java.util.Arrays.asList(lines));
    return source;
  }

  /**
   * Edits the quantity of the existing return line {@code ret-line-1} through {@code handler} and
   * answers what the handler said. The line returns {@code ownQuantity} of a source line that moved
   * {@code received}, and other non-voided returns hold {@code returnedByOthers} of it. The source
   * line the already-returned query was asked to exclude is added to {@code excludedSeen}.
   *
   * @param method {@code PATCH} or {@code PUT}
   */
  static NeoResponse editLineQuantity(NeoHandler handler, String method, String newQuantity,
      String received, String returnedByOthers, String ownQuantity, List<String> excludedSeen)
      throws Exception {
    try (MockedStatic<OBContext> ignored = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOutLine source = sourceLine("src-1", "Agua", received);
      ShipmentInOutLine own = mock(ShipmentInOutLine.class);
      when(own.getId()).thenReturn("ret-line-1");
      when(own.getCanceledInoutLine()).thenReturn(source);
      when(own.getMovementQuantity()).thenReturn(new BigDecimal(ownQuantity).negate());
      when(dal.get(ShipmentInOutLine.class, "ret-line-1")).thenReturn(own);
      stubReturnedQuantities(dal, (src, excl) -> {
        excludedSeen.add(excl);
        BigDecimal others = new BigDecimal(returnedByOthers);
        return excl == null ? others.add(new BigDecimal(ownQuantity)) : others;
      });

      JSONObject body = new JSONObject().put("movementQuantity", new BigDecimal(newQuantity));
      NeoContext ctx = NeoContext.builder()
          .httpMethod(method).endpointType(NeoEndpointType.CRUD)
          .recordId("ret-line-1").requestBody(body).build();
      return handler.handle(ctx);
    }
  }
}
