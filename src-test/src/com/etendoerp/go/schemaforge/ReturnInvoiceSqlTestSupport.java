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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.openbravo.dal.service.OBDal;

/**
 * Stubs the two raw-JDBC reads the ETP-5381 create-and-confirm path performs before it writes
 * anything: the duplicate-invoice guard ({@code hasNonVoidedReturnInvoice}) and the candidate
 * lookup ({@code fetchRectifiableInvoices}, used both by the {@code rectifiableInvoices} action
 * and by {@code resolveRectifiedInvoiceIds}' fallback).
 *
 * <p>Both go through {@code OBDal.getInstance().getConnection()}, and both PROPAGATE their
 * failures as {@code OBException} on purpose — so a test that leaves the connection unstubbed
 * does not exercise the path it claims to: the NPE becomes a 400 that swallows whatever the test
 * was actually asserting. Stubbing is therefore mandatory for every {@code createReturnInvoice}
 * test, which is why it lives here instead of being copy-pasted per test class.
 *
 * <p>Statements are dispatched by SQL text rather than by call order: the completion path fires
 * its own unrelated statement ({@code populateVerifactuFieldsFromDocType}), so a positional
 * {@code thenReturn(a).thenReturn(b)} chain would silently shift.
 */
final class ReturnInvoiceSqlTestSupport {

  /** Only {@code hasNonVoidedReturnInvoice}'s query ends in a {@code LIMIT 1}. */
  private static final String HAS_INVOICE_MARKER = "LIMIT 1";
  /** Only {@code fetchRectifiableInvoices} walks back through {@code Canceled_Inoutline_ID}. */
  private static final String RECTIFIABLE_MARKER = "rl.Canceled_Inoutline_ID IS NOT NULL";

  private ReturnInvoiceSqlTestSupport() {
  }

  /**
   * Stubs both reads on the given DAL mock.
   *
   * @param dal the {@code OBDal} mock returned by the caller's {@code MockedStatic<OBDal>}
   * @param hasReturnInvoice what the duplicate-invoice guard should answer
   * @param rectifiableInvoiceIds the candidate invoice ids, in the order the SQL would return
   *     them (newest first); empty means "nothing to rectify"
   */
  static Connection stubReturnInvoiceQueries(OBDal dal, boolean hasReturnInvoice,
      List<String> rectifiableInvoiceIds) throws Exception {
    // Every mock is built and stubbed HERE, never inside the Answer below: stubbing a mock from
    // within another mock's answer leaves Mockito with an unfinished stubbing and the resulting
    // UnfinishedStubbingException surfaces as "Could not load the invoices available to rectify".
    ResultSet rectifiableRs = rectifiableResultSet(rectifiableInvoiceIds);
    PreparedStatement rectifiablePs = mock(PreparedStatement.class);
    when(rectifiablePs.executeQuery()).thenReturn(rectifiableRs);

    ResultSet hasInvoiceRs = mock(ResultSet.class);
    when(hasInvoiceRs.next()).thenReturn(hasReturnInvoice);
    PreparedStatement hasInvoicePs = mock(PreparedStatement.class);
    when(hasInvoicePs.executeQuery()).thenReturn(hasInvoiceRs);

    ResultSet emptyRs = mock(ResultSet.class);
    when(emptyRs.next()).thenReturn(false);
    PreparedStatement otherPs = mock(PreparedStatement.class);
    when(otherPs.executeQuery()).thenReturn(emptyRs);

    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains(RECTIFIABLE_MARKER)) {
        return rectifiablePs;
      }
      return sql.contains(HAS_INVOICE_MARKER) ? hasInvoicePs : otherPs;
    });
    return conn;
  }

  /** Convenience overload: no existing invoice, one rectifiable candidate. */
  static Connection stubReturnInvoiceQueries(OBDal dal, String... rectifiableInvoiceIds)
      throws Exception {
    return stubReturnInvoiceQueries(dal, false, Arrays.asList(rectifiableInvoiceIds));
  }

  /**
   * A result set shaped like {@code fetchRectifiableInvoices}' projection: id, documentNo,
   * invoiceDate, grandTotal, currency, business partner.
   */
  private static ResultSet rectifiableResultSet(List<String> ids) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    // Cursor restarts when it runs off the end, so a test may execute the query more than once
    // (the rectifiableInvoices action and resolveRectifiedInvoiceIds both read it).
    AtomicInteger cursor = new AtomicInteger(-1);
    when(rs.next()).thenAnswer(i -> {
      if (cursor.incrementAndGet() < ids.size()) {
        return true;
      }
      cursor.set(-1);
      return false;
    });
    when(rs.getString(1)).thenAnswer(i -> ids.get(cursor.get()));
    when(rs.getString(2)).thenAnswer(i -> "DOC-" + ids.get(cursor.get()));
    when(rs.getDate(3)).thenReturn(null);
    when(rs.getBigDecimal(4)).thenReturn(new BigDecimal("100.00"));
    when(rs.getString(5)).thenReturn("EUR");
    when(rs.getString(6)).thenReturn("Acme Corp");
    return rs;
  }
}
