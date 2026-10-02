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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * ETP-5547 regression tests for {@link ConversionRateDocumentSync}.
 *
 * <p>The sync writes {@code C_Conversion_Rate_Document} through raw JDBC on the request's own
 * connection. When a write failed — e.g. {@code c_conversion_rate_document_trg} raising
 * {@code @20501@} on a posted invoice — PostgreSQL aborted the whole request transaction even
 * though the caller swallowed the exception: the 2xx response was already written, and the final
 * commit silently rolled back the confirmed payment / the cloned invoice. Every write must
 * therefore run under a JDBC savepoint and roll back to it on failure, so a swallowed error can
 * no longer poison the request transaction. The exception still propagates to the caller.
 *
 * <p>Also pins the no-op when the existing row already carries the same currency, rate and
 * foreign amount: no UPDATE is issued at all, so an unchanged invoice never touches the table
 * (and never trips the posted-document trigger) on every action POST.
 *
 * <p>Statements are routed by the leading SQL keyword, not by call order, so the tests survive
 * the SELECT growing extra columns or the writes being reordered.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversionRateDocumentSyncTest {

  private static final String INVOICE_ID = "inv-5547";
  private static final String ORG_CURRENCY_ID = "eur-id";
  private static final String DOC_CURRENCY_ID = "usd-id";
  private static final String OTHER_CURRENCY_ID = "gbp-id";
  private static final String EXISTING_ROW_ID = "crd-existing";
  private static final String USER_ID = "user-5547";
  private static final String POSTED_ERROR = "@20501@";
  private static final String COL_ID = "c_conversion_rate_document_id";
  private static final String COL_CURRENCY = "c_currency_id";
  private static final String COL_RATE = "rate";
  private static final String COL_FOREIGN_AMOUNT = "foreign_amount";
  private static final String SQL_UPDATE = "UPDATE";
  private static final String SQL_INSERT = "INSERT";
  private static final String SQL_DELETE = "DELETE";

  /** doc→org multiplier as computed by the caller (1 / 1.16, 12 decimals). */
  private static final BigDecimal DOC_RATE = new BigDecimal("0.862068965517");
  private static final BigDecimal FOREIGN_AMOUNT = new BigDecimal("25.34");

  @Mock
  private OBDal dal;
  @Mock
  private OBContext obContext;
  @Mock
  private User user;
  @Mock
  private Connection conn;
  @Mock
  private Savepoint savepoint;
  @Mock
  private PreparedStatement selectPs;
  @Mock
  private ResultSet selectRs;
  @Mock
  private PreparedStatement updatePs;
  @Mock
  private PreparedStatement insertPs;
  @Mock
  private PreparedStatement deletePs;
  @Mock
  private Invoice invoice;
  @Mock
  private Currency docCurrency;
  @Mock
  private Client client;
  @Mock
  private Organization organization;

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;

  @BeforeEach
  void setUp() throws Exception {
    dalStatic = mockStatic(OBDal.class);
    contextStatic = mockStatic(OBContext.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getUser()).thenReturn(user);
    when(user.getId()).thenReturn(USER_ID);

    when(dal.getConnection()).thenReturn(conn);
    when(conn.setSavepoint()).thenReturn(savepoint);
    when(conn.setSavepoint(anyString())).thenReturn(savepoint);
    when(conn.prepareStatement(anyString())).thenAnswer(inv -> route(inv.getArgument(0)));
    when(selectPs.executeQuery()).thenReturn(selectRs);

    when(invoice.getId()).thenReturn(INVOICE_ID);
    when(invoice.getCurrency()).thenReturn(docCurrency);
    when(docCurrency.getId()).thenReturn(DOC_CURRENCY_ID);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(organization);
    when(organization.getId()).thenReturn("org-1");
  }

  @AfterEach
  void tearDown() {
    contextStatic.close();
    dalStatic.close();
  }

  private PreparedStatement route(String sql) {
    String head = sql.trim().toUpperCase(Locale.ROOT);
    if (head.startsWith(SQL_UPDATE)) {
      return updatePs;
    }
    if (head.startsWith(SQL_INSERT)) {
      return insertPs;
    }
    if (head.startsWith(SQL_DELETE)) {
      return deletePs;
    }
    return selectPs;
  }

  /** One existing row, exposed both by column index (declaration order) and by column name. */
  private void givenExistingRow(String currencyId, BigDecimal rate, BigDecimal foreignAmount)
      throws SQLException {
    when(selectRs.next()).thenReturn(true, false);
    when(selectRs.getString(1)).thenReturn(EXISTING_ROW_ID);
    when(selectRs.getString(COL_ID)).thenReturn(EXISTING_ROW_ID);
    when(selectRs.getString(2)).thenReturn(currencyId);
    when(selectRs.getString(COL_CURRENCY)).thenReturn(currencyId);
    when(selectRs.getBigDecimal(3)).thenReturn(rate);
    when(selectRs.getBigDecimal(COL_RATE)).thenReturn(rate);
    when(selectRs.getBigDecimal(4)).thenReturn(foreignAmount);
    when(selectRs.getBigDecimal(COL_FOREIGN_AMOUNT)).thenReturn(foreignAmount);
  }

  private void givenNoExistingRow() throws SQLException {
    when(selectRs.next()).thenReturn(false);
  }

  private static boolean startsWithKeyword(String sql, String keyword) {
    return sql != null && sql.trim().toUpperCase(Locale.ROOT).startsWith(keyword);
  }

  // ── savepoint around the writes ─────────────────────────────────────────────

  @Test
  void testUpsertRollsBackToSavepointWhenUpdateFails() throws Exception {
    givenExistingRow(OTHER_CURRENCY_ID, new BigDecimal("0.5"), new BigDecimal("10.00"));
    SQLException triggerError = new SQLException(POSTED_ERROR);
    when(updatePs.executeUpdate()).thenThrow(triggerError);

    SQLException thrown = assertThrows(SQLException.class,
        () -> ConversionRateDocumentSync.upsert(invoice, ORG_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT));

    assertSame(triggerError, thrown);
    verify(conn, atLeastOnce()).setSavepoint();
    verify(conn).rollback(savepoint);
    // A full-transaction rollback would discard everything the request already did.
    verify(conn, never()).rollback();
  }

  @Test
  void testUpsertRollsBackToSavepointWhenInsertFails() throws Exception {
    givenNoExistingRow();
    SQLException triggerError = new SQLException(POSTED_ERROR);
    when(insertPs.executeUpdate()).thenThrow(triggerError);

    SQLException thrown = assertThrows(SQLException.class,
        () -> ConversionRateDocumentSync.upsert(invoice, ORG_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT));

    assertSame(triggerError, thrown);
    verify(conn, atLeastOnce()).setSavepoint();
    verify(conn).rollback(savepoint);
    verify(conn, never()).rollback();
  }

  @Test
  void testDeleteAllForInvoiceRollsBackToSavepointWhenDeleteFails() throws Exception {
    givenExistingRow(DOC_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT);
    SQLException triggerError = new SQLException(POSTED_ERROR);
    when(deletePs.executeUpdate()).thenThrow(triggerError);

    SQLException thrown = assertThrows(SQLException.class,
        () -> ConversionRateDocumentSync.deleteAllForInvoice(INVOICE_ID, ORG_CURRENCY_ID));

    assertSame(triggerError, thrown);
    verify(conn, atLeastOnce()).setSavepoint();
    verify(conn).rollback(savepoint);
    verify(conn, never()).rollback();
  }

  @Test
  void testUpsertDoesNotRollBackWhenUpdateSucceeds() throws Exception {
    givenExistingRow(OTHER_CURRENCY_ID, new BigDecimal("0.5"), new BigDecimal("10.00"));

    ConversionRateDocumentSync.upsert(invoice, ORG_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT);

    verify(updatePs).executeUpdate();
    verify(conn, never()).rollback(any(Savepoint.class));
    verify(conn, never()).rollback();
  }

  // ── unchanged values → no UPDATE ────────────────────────────────────────────

  @Test
  void testUpsertSkipsUpdateWhenExistingRowAlreadyMatches() throws Exception {
    // Same currency, same rate at a DIFFERENT scale (compareTo-equal, not equals-equal) and the
    // same foreign amount: nothing changed, so nothing must be written.
    givenExistingRow(DOC_CURRENCY_ID, new BigDecimal("0.86206896551700"), new BigDecimal("25.340"));

    ConversionRateDocumentSync.upsert(invoice, ORG_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT);

    verify(conn, never()).prepareStatement(argThat((String sql) -> startsWithKeyword(sql, SQL_UPDATE)));
    verify(conn, never()).prepareStatement(argThat((String sql) -> startsWithKeyword(sql, SQL_INSERT)));
    verify(conn, never()).prepareStatement(argThat((String sql) -> startsWithKeyword(sql, SQL_DELETE)));
    verify(updatePs, never()).executeUpdate();
  }

  @Test
  void testUpsertUpdatesWhenRateChanged() throws Exception {
    givenExistingRow(DOC_CURRENCY_ID, new BigDecimal("0.800000000000"), FOREIGN_AMOUNT);

    ConversionRateDocumentSync.upsert(invoice, ORG_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT);

    verify(updatePs).executeUpdate();
    verify(updatePs).setBigDecimal(anyInt(), argThat(v -> v != null && v.compareTo(DOC_RATE) == 0));
  }

  @Test
  void testUpsertUpdatesWhenForeignAmountChanged() throws Exception {
    givenExistingRow(DOC_CURRENCY_ID, DOC_RATE, new BigDecimal("20.00"));

    ConversionRateDocumentSync.upsert(invoice, ORG_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT);

    verify(updatePs).executeUpdate();
  }

  @Test
  void testUpsertUpdatesWhenCurrencyChanged() throws Exception {
    givenExistingRow(OTHER_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT);

    ConversionRateDocumentSync.upsert(invoice, ORG_CURRENCY_ID, DOC_RATE, FOREIGN_AMOUNT);

    verify(updatePs).executeUpdate();
  }
}
