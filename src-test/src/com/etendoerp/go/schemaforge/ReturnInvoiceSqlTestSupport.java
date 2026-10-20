package com.etendoerp.go.schemaforge;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.openbravo.dal.service.OBDal;

/**
 * Stubs the raw-JDBC reads the ETP-5381 create-and-confirm path performs before it writes
 * anything: the duplicate-invoice guard ({@code hasNonVoidedReturnInvoice}) and the two candidate
 * lookups — {@code fetchAutoDetectedInvoices} (the {@code Canceled_Inoutline_ID} chain, which
 * drives the suggestion and {@code resolveRectifiedInvoiceIds}' fallback) and
 * {@code fetchSelectableInvoices} (every confirmed invoice of the flow, which drives the list the
 * user may pick from).
 *
 * <p>All three go through {@code OBDal.getInstance().getConnection()}, and all three PROPAGATE
 * their failures as {@code OBException} on purpose — so a test that leaves the connection
 * unstubbed does not exercise the path it claims to: the NPE becomes a 400 that swallows whatever
 * the test was actually asserting. Stubbing is therefore mandatory for every
 * {@code createReturnInvoice} test, which is why it lives here instead of being copy-pasted per
 * test class.
 *
 * <p>Statements are dispatched by SQL text rather than by call order: the completion path fires
 * its own unrelated statement ({@code populateVerifactuFieldsFromDocType}), so a positional
 * {@code thenReturn(a).thenReturn(b)} chain would silently shift.
 */
final class ReturnInvoiceSqlTestSupport {

  /** Only {@code hasNonVoidedReturnInvoice}'s query ends in a {@code LIMIT 1}. */
  private static final String HAS_INVOICE_MARKER = "LIMIT 1";
  /** Only {@code fetchAutoDetectedInvoices} walks back through {@code Canceled_Inoutline_ID}. */
  private static final String AUTO_DETECTED_MARKER = "rl.Canceled_Inoutline_ID IS NOT NULL";
  /** Only {@code fetchSelectableInvoices} correlates the invoice flow with the return document. */
  private static final String SELECTABLE_MARKER = "i.IsSOTrx = ret.IsSOTrx";
  /**
   * Only {@code fetchReturnInvoices} (the non-voided rectificatives the return already has, which
   * ETP-5712 reports as {@code previousReturnInvoices}) selects the return line's owner first.
   */
  private static final String PREVIOUS_MARKER = "SELECT DISTINCT l.M_InOut_ID, i.C_Invoice_ID";

  private ReturnInvoiceSqlTestSupport() {
  }

  /**
   * Stubs all three reads on the given DAL mock, with the selectable list stubbed independently
   * from the auto-detected one.
   *
   * <p>The two lists are separate arguments because after ETP-5381 they genuinely differ: a
   * standalone return has an EMPTY chain and a NON-empty selectable list, and collapsing them
   * into one stub would make that exact production case untestable.
   *
   * @param dal the {@code OBDal} mock returned by the caller's {@code MockedStatic<OBDal>}
   * @param hasReturnInvoice what the duplicate-invoice guard should answer
   * @param autoDetectedInvoiceIds the ids the {@code Canceled_Inoutline_ID} chain yields, in the
   *     order the SQL would return them (newest first); empty means "no chain"
   * @param selectableInvoiceIds the ids the user may pick from, newest first
   */
  static Connection stubReturnInvoiceQueries(OBDal dal, boolean hasReturnInvoice,
      List<String> autoDetectedInvoiceIds, List<String> selectableInvoiceIds) throws Exception {
    return stubReturnInvoiceQueries(dal, hasReturnInvoice, autoDetectedInvoiceIds,
        selectableInvoiceIds, Collections.<String[]>emptyList());
  }

  /**
   * Same as the four-argument form, plus the rectificatives the return document already has
   * (ETP-5712): each entry is {@code {invoiceId, documentNo, docStatus, returnId}} of a
   * non-voided invoice whose line points at one of the lines of the return {@code returnId}.
   */
  static Connection stubReturnInvoiceQueries(OBDal dal, boolean hasReturnInvoice,
      List<String> autoDetectedInvoiceIds, List<String> selectableInvoiceIds,
      List<String[]> previousReturnInvoices) throws Exception {
    ResultSet previousRs = previousInvoicesResultSet(previousReturnInvoices);
    PreparedStatement previousPs = mock(PreparedStatement.class);
    when(previousPs.executeQuery()).thenReturn(previousRs);
    // Both result sets are built BEFORE any when(...) opens: invoiceResultSet stubs a mock of its
    // own, and creating it inside a thenReturn() argument leaves Mockito's stubbing unfinished.
    ResultSet autoDetectedRs = invoiceResultSet(autoDetectedInvoiceIds);
    ResultSet selectableRs = invoiceResultSet(selectableInvoiceIds);

    PreparedStatement autoDetectedPs = mock(PreparedStatement.class);
    when(autoDetectedPs.executeQuery()).thenReturn(autoDetectedRs);

    PreparedStatement selectablePs = mock(PreparedStatement.class);
    when(selectablePs.executeQuery()).thenReturn(selectableRs);

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
      if (sql.contains(PREVIOUS_MARKER)) {
        return previousPs;
      }
      if (sql.contains(AUTO_DETECTED_MARKER)) {
        return autoDetectedPs;
      }
      if (sql.contains(SELECTABLE_MARKER)) {
        return selectablePs;
      }
      return sql.contains(HAS_INVOICE_MARKER) ? hasInvoicePs : otherPs;
    });
    return conn;
  }

  /**
   * Convenience overload for the common case where every chain-detected invoice is also offered
   * in the selectable list — i.e. the return WAS created from an invoiced document.
   */
  static Connection stubReturnInvoiceQueries(OBDal dal, boolean hasReturnInvoice,
      List<String> rectifiableInvoiceIds) throws Exception {
    return stubReturnInvoiceQueries(dal, hasReturnInvoice, rectifiableInvoiceIds,
        rectifiableInvoiceIds);
  }

  /** Convenience overload: no existing invoice, chain and selectable list alike. */
  static Connection stubReturnInvoiceQueries(OBDal dal, String... rectifiableInvoiceIds)
      throws Exception {
    return stubReturnInvoiceQueries(dal, false, Arrays.asList(rectifiableInvoiceIds));
  }

  /** A result set shaped like {@code fetchReturnInvoices}: owner, invoice id, number, status, total, ISO. */
  static ResultSet previousInvoicesResultSet(List<String[]> invoices) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    AtomicInteger cursor = new AtomicInteger(-1);
    when(rs.next()).thenAnswer(i -> {
      if (cursor.incrementAndGet() < invoices.size()) {
        return true;
      }
      cursor.set(-1);
      return false;
    });
    when(rs.getString(1)).thenAnswer(i -> invoices.get(cursor.get())[3]);
    when(rs.getString(2)).thenAnswer(i -> invoices.get(cursor.get())[0]);
    when(rs.getString(3)).thenAnswer(i -> invoices.get(cursor.get())[1]);
    when(rs.getString(4)).thenAnswer(i -> invoices.get(cursor.get())[2]);
    when(rs.getBigDecimal(5)).thenReturn(new BigDecimal("-10.00"));
    when(rs.getString(6)).thenReturn("EUR");
    return rs;
  }

  /**
   * A result set shaped like the invoice-candidate projection both queries share: id, documentNo,
   * invoiceDate, grandTotal, currency, business partner.
   */
  static ResultSet invoiceResultSet(List<String> ids) throws Exception {
    ResultSet rs = mock(ResultSet.class);
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
