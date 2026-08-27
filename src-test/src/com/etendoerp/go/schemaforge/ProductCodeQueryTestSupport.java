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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.openbravo.dal.service.OBDal;

/**
 * Shared JDBC mock builders for the ETP-4941 {@code NeoHandlerUtils#fetchProductCodesForLines}
 * query.
 *
 * <p>{@code OrderLineHandlerTest}, {@code InvoiceLineHandlerTest} and {@code NeoHandlerUtilsTest}
 * each exercise the same {@code dal.getConnection() → Connection → PreparedStatement →
 * ResultSet} chain (only the line table/column and the SKU values differ), so the wiring was
 * being rebuilt near-verbatim in every test method. It lives here once instead — same pattern as
 * {@link LocatorTestSupport} for the ETP-4863 locator-anchoring tests.
 */
final class ProductCodeQueryTestSupport {

  private ProductCodeQueryTestSupport() {
  }

  /**
   * Wires {@code dal.getConnection()} through to a {@code ResultSet} yielding exactly ONE row —
   * {@code rs.next()} returns {@code true} once then {@code false} — with column 2
   * ({@code p.value}, the SKU) stubbed to {@code sku}.
   *
   * <p>Column 1 (the line id) is stubbed ONLY when {@code lineId} is non-null. This matters for
   * callers running under Mockito's default STRICT_STUBS (no {@code @MockitoSettings(LENIENT)}
   * override, e.g. {@code InvoiceLineHandlerTest}): the blank-SKU short-circuit in
   * {@code fetchProductCodesForLines} never reads column 1 once column 2 comes back blank, so
   * stubbing it there would fail the test with an unnecessary-stubbing error. Pass {@code null}
   * for that case; pass the expected line id for the happy-path case.
   *
   * @return the mocked {@code Connection}, so callers that need to assert the SQL passed to
   *         {@code prepareStatement} (e.g. which line table it targets) can still verify it
   */
  static Connection mockSingleRowProductCodeQuery(OBDal dal, String lineId, String sku)
      throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    if (lineId != null) {
      when(rs.getString(1)).thenReturn(lineId);
    }
    when(rs.getString(2)).thenReturn(sku);
    return conn;
  }

  /**
   * Wires {@code dal.getConnection()} through to a {@code ResultSet} yielding NO rows at all
   * ({@code rs.next()} returns {@code false} immediately) — for callers that only need to assert
   * the SQL shape (table/column/join), not any row content.
   *
   * @return the mocked {@code Connection}, for capturing/verifying the SQL passed to
   *         {@code prepareStatement}
   */
  static Connection mockEmptyProductCodeQuery(OBDal dal) throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);
    return conn;
  }
}
