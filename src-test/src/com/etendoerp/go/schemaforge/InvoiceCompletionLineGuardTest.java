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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * {@link InvoiceCompletionLineGuard} (ETP-5710): a purchase invoice whose lines kept
 * {@code LineNetAmt 0} despite a quantity and a price used to complete (CO) with a total of 0, no
 * payment plan and {@code paymentComplete = Y}. The guard refuses the completion before the
 * document action runs.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceCompletionLineGuard
 */
class InvoiceCompletionLineGuardTest {

  private static final String INVOICE = "INV-1";

  private static NeoContext completePatch(String recordId) throws Exception {
    return NeoContext.builder().specName("purchase-invoice").entityName("header")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(recordId)
        .requestBody(new JSONObject().put("documentAction", "CO")).build();
  }

  /** A row of the offending-lines query: line, product name, search key, quantity. */
  private static Object[] row(long line, String name, String key, String qty) {
    return new Object[] { line, name, key, new java.math.BigDecimal(qty) };
  }

  /** Stubs OBDal so the offending-lines query answers {@code rows}. */
  private static MockedStatic<OBDal> dalAnswering(Connection conn, Object[]... rows)
      throws SQLException {
    OBDal dal = mock(OBDal.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    int[] cursor = { -1 };
    when(rs.next()).thenAnswer(inv -> ++cursor[0] < rows.length);
    when(rs.getLong(1)).thenAnswer(inv -> rows[cursor[0]][0]);
    when(rs.getString(2)).thenAnswer(inv -> rows[cursor[0]][1]);
    when(rs.getString(3)).thenAnswer(inv -> rows[cursor[0]][2]);
    when(rs.getBigDecimal(4)).thenAnswer(inv -> rows[cursor[0]][3]);
    MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    return dalMock;
  }

  @Test
  void completionWithLinesWithoutAmountIsRefusedBeforeTheActionRuns() throws Exception {
    Connection conn = mock(Connection.class);
    try (MockedStatic<OBDal> dal = dalAnswering(conn, row(10, "Agua", "AG", "2"), row(30, "Queso", "QS", "1"));
        MockedStatic<OBContext> ob = mockStatic(OBContext.class)) {
      NeoResponse response = InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(
          completePatch(INVOICE));

      assertNotNull(response, "the completion must be refused");
      assertEquals(422, response.getHttpStatus());
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals("invoiceLinesWithoutAmount", error.getString("code"));
      assertEquals(2, error.getJSONArray("lines").length());
      assertEquals(10, error.getJSONArray("lines").getJSONObject(0).getInt("line"));
      assertEquals("Agua", error.getJSONArray("lines").getJSONObject(0).getString("product"));
      assertEquals("Queso", error.getJSONArray("lines").getJSONObject(1).getString("product"));
      assertTrue(error.getString("message").contains("the lines of Agua, Queso have"),
          error.getString("message"));
      assertFalse(error.getString("message").contains("10"), error.getString("message"));
    }
  }

  @Test
  void completionOfAnInvoiceWhoseLinesAllHaveAmountsProceeds() throws Exception {
    Connection conn = mock(Connection.class);
    try (MockedStatic<OBDal> dal = dalAnswering(conn);
        MockedStatic<OBContext> ob = mockStatic(OBContext.class)) {
      assertNull(InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(completePatch(INVOICE)));
    }
  }

  @Test
  void theActionEndpointIsGuardedToo() throws Exception {
    Connection conn = mock(Connection.class);
    NeoContext action = NeoContext.builder().specName("purchase-invoice").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("documentAction")
        .recordId(INVOICE).requestBody(new JSONObject().put("fieldValues",
            new JSONObject().put("documentAction", "CO"))).build();
    try (MockedStatic<OBDal> dal = dalAnswering(conn, row(10, "Agua", "AG", "2"));
        MockedStatic<OBContext> ob = mockStatic(OBContext.class)) {
      NeoResponse response = InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(action);

      assertNotNull(response);
      assertEquals(422, response.getHttpStatus());
    }
  }

  @Test
  void aRequestThatIsNotACompletionIsNeverChecked() throws Exception {
    Connection conn = mock(Connection.class);
    NeoContext edit = NeoContext.builder().httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .recordId(INVOICE).requestBody(new JSONObject().put("description", "x")).build();
    try (MockedStatic<OBDal> dal = dalAnswering(conn, row(10, "Agua", "AG", "2"));
        MockedStatic<OBContext> ob = mockStatic(OBContext.class)) {
      assertNull(InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(edit));
      verify(conn, never()).prepareStatement(anyString());
    }
  }

  @Test
  void aCompletionWithoutRecordIdIsLeftToTheCompletionService() throws Exception {
    Connection conn = mock(Connection.class);
    try (MockedStatic<OBDal> dal = dalAnswering(conn, row(10, "Agua", "AG", "2"));
        MockedStatic<OBContext> ob = mockStatic(OBContext.class)) {
      assertNull(InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(completePatch(null)));
      verify(conn, never()).prepareStatement(anyString());
    }
  }

  /** A guard that cannot run must not become a new reason a document cannot be completed. */
  @Test
  void aFailingCheckDoesNotBlockTheCompletion() throws Exception {
    OBDal dal = mock(OBDal.class);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> ob = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      assertNull(InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(completePatch(INVOICE)));
    }
  }

  @Test
  void productsAreListedNaturallyAndFallBackToTheSearchKey() {
    java.util.List<InvoiceCompletionLineGuard.Offender> three = java.util.Arrays.asList(
        new InvoiceCompletionLineGuard.Offender(10, "Agua", "1"),
        new InvoiceCompletionLineGuard.Offender(20, "Cerveza", "1"),
        new InvoiceCompletionLineGuard.Offender(30, "Queso Sardo", "1"));
    assertTrue(InvoiceCompletionLineGuard.message(three)
        .contains("the lines of Agua, Cerveza, Queso Sardo have a quantity"));
  }

  @Test
  void twoLinesOfTheSameProductAreToldApartByQuantity() {
    java.util.List<InvoiceCompletionLineGuard.Offender> dup = java.util.Arrays.asList(
        new InvoiceCompletionLineGuard.Offender(10, "Agua", "3"),
        new InvoiceCompletionLineGuard.Offender(20, "Agua", "5"));
    assertEquals(java.util.Arrays.asList("Agua (\u00d73)", "Agua (\u00d75)"),
        InvoiceCompletionLineGuard.labels(dup));
  }

  @Test
  void aBlankProductNameUsesTheSearchKey() throws Exception {
    Connection conn = mock(Connection.class);
    try (MockedStatic<OBDal> dal = dalAnswering(conn, row(10, "", "AG-01", "2"));
        MockedStatic<OBContext> ob = mockStatic(OBContext.class)) {
      NeoResponse response = InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(
          completePatch(INVOICE));
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("the lines of AG-01 have"));
    }
  }
}
