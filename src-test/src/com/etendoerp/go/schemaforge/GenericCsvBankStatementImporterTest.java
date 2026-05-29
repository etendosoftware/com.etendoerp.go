/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License. All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

import com.etendoerp.go.schemaforge.GenericCsvBankStatementImporter.CsvParseException;

/**
 * Unit tests for {@link GenericCsvBankStatementImporter}. Exercises the CSV
 * tokeniser (quoted fields, doubled-quote escape, CRLF handling), header
 * validation, decimal-separator auto-detection (comma vs dot vs both vs
 * neither), date parsing, amount parsing and the integration with OBDal/
 * OBProvider for line creation. All static framework hooks are mocked so the
 * tests run fully offline.
 */
@RunWith(MockitoJUnitRunner.class)
public class GenericCsvBankStatementImporterTest {

  private FIN_BankStatement statement;
  private Client client;
  private Organization org;
  private List<FIN_BankStatementLine> savedLines;

  @Before
  public void setUp() {
    statement = mock(FIN_BankStatement.class);
    client = mock(Client.class);
    org = mock(Organization.class);
    when(statement.getClient()).thenReturn(client);
    when(statement.getOrganization()).thenReturn(org);
    when(statement.getFINBankStatementLineList()).thenReturn(Collections.emptyList());
    savedLines = new ArrayList<>();
  }

  // ── Default + parameterised constructors ────────────────────────────────

  @Test
  public void defaultConstructorUsesCommaDelimiterAndDdMMyyyy() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,100.50,Sample line\n";
    int count = runWithMocks(importer, csv);
    assertEquals(1, count);
  }

  @Test
  public void parameterisedConstructorAcceptsSemicolonAndAlternateDate() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter(';', "yyyy-MM-dd");
    String csv = "Transaction Date;Reference No.;Business Partner Name;Amount OUT;Amount IN;Description\n"
        + "2026-02-01;REF-1;Acme;0;100.50;Sample\n";
    int count = runWithMocks(importer, csv);
    assertEquals(1, count);
  }

  // ── loadFile happy path ────────────────────────────────────────────────

  @Test
  public void loadFileReturnsZeroForEmptyStream() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    int count = runWithMocks(importer, "");
    assertEquals(0, count);
  }

  @Test
  public void loadFileParsesMultipleRowsAndSavesEach() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,100.50,Inflow line\n"
        + "02/02/2026,REF-2,Beta,50.25,0,Outflow line\n";
    int count = runWithMocks(importer, csv);
    assertEquals(2, count);
    assertEquals(2, savedLines.size());
  }

  @Test
  public void loadFileSkipsBlankRows() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,100,Line\n"
        + "\n"
        + ",,,,,\n"
        + "02/02/2026,REF-2,Beta,50,0,Line2\n";
    int count = runWithMocks(importer, csv);
    assertEquals(2, count);
  }

  @Test
  public void loadFileHandlesQuotedFieldsWithCommasAndDoubleQuotes() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,\"REF,001\",\"Acme \"\"LLC\"\",Madrid\",0,100,\"Line with, comma\"\n";
    int count = runWithMocks(importer, csv);
    assertEquals(1, count);
  }

  @Test
  public void loadFileHandlesCrlfNewlinesAndMissingTrailingNewline() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\r\n"
        + "01/02/2026,REF-1,Acme,0,100,Line\r\n"
        + "02/02/2026,REF-2,Beta,50,0,Line2"; // no trailing newline on purpose
    int count = runWithMocks(importer, csv);
    assertEquals(2, count);
  }

  @Test
  public void loadFileDefaultsReferenceToTwoStarsWhenBlank() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,,Acme,0,100,Sample\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setReferenceNo("**");
  }

  @Test
  public void loadFileTruncatesLongStrings() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String longRef = repeat("R", 50);  // truncate to 30
    String longBp = repeat("B", 70);   // truncate to 60
    String longDesc = repeat("D", 2050); // truncate to 2000
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026," + longRef + "," + longBp + ",0,100," + longDesc + "\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setReferenceNo(repeat("R", 30));
    verify(savedLines.get(0)).setBpartnername(repeat("B", 60));
    verify(savedLines.get(0)).setDescription(repeat("D", 2000));
  }

  // ── Validation errors ─────────────────────────────────────────────────

  @Test
  public void loadFileFailsWhenRequiredColumnMissing() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "REF-1,Acme,0,100,Line\n";
    try {
      runWithMocks(importer, csv);
      fail("Expected CsvParseException because Transaction Date column is missing");
    } catch (CsvParseException expected) {
      assertTrue(expected.getMessage().contains("Transaction Date"));
    }
  }

  @Test
  public void loadFileFailsOnInvalidDate() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "not-a-date,REF-1,Acme,0,100,Line\n";
    try {
      runWithMocks(importer, csv);
      fail("Expected CsvParseException for bad date");
    } catch (CsvParseException expected) {
      assertTrue(expected.getMessage().contains("Invalid date"));
    }
  }

  @Test
  public void loadFileFailsOnUnparseableAmount() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,not-a-number,100,Line\n";
    try {
      runWithMocks(importer, csv);
      fail("Expected CsvParseException for malformed amount");
    } catch (CsvParseException expected) {
      assertTrue(expected.getMessage().contains("Impossible to parse"));
    }
  }

  @Test
  public void loadFileWrapsIoExceptionAsCsvParseException() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    InputStream broken = new InputStream() {
      @Override public int read() throws IOException { throw new IOException("disk fail"); }
    };
    try {
      runWithMocks(importer, broken);
      fail("Expected CsvParseException wrapping the IOException");
    } catch (CsvParseException expected) {
      assertTrue(expected.getMessage().contains("Failed to read CSV"));
      assertNotNull("cause must be preserved", expected.getCause());
    }
  }

  // ── Decimal-separator auto-detection ──────────────────────────────────

  @Test
  public void detectsCommaAsDecimalSeparator() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,\"0\",\"1234,56\",Line\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setCramount(new BigDecimal("1234.56"));
  }

  @Test
  public void detectsDotAsDecimalSeparator() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,1234.56,Line\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setCramount(new BigDecimal("1234.56"));
  }

  @Test
  public void detectsBothSeparatorsAndPicksRightmostAsDecimal() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    // "1.234,56" → comma is decimal (Spanish format)
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,\"1.234,56\",Line\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setCramount(new BigDecimal("1234.56"));
  }

  @Test
  public void parseAmountReturnsZeroForBlank() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,,,Line\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setDramount(BigDecimal.ZERO);
    verify(savedLines.get(0)).setCramount(BigDecimal.ZERO);
  }

  // ── nextLineSeed ──────────────────────────────────────────────────────

  @Test
  public void lineSeedStartsAtTenWhenStatementHasNoLines() throws Exception {
    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,100,Line\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setLineNo(10L);
  }

  @Test
  public void lineSeedPicksUpAfterExistingMax() throws Exception {
    FIN_BankStatementLine existing = mock(FIN_BankStatementLine.class);
    when(existing.getLineNo()).thenReturn(30L);
    when(statement.getFINBankStatementLineList())
        .thenReturn(java.util.Arrays.asList(existing));

    GenericCsvBankStatementImporter importer = new GenericCsvBankStatementImporter();
    String csv = "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,100,Line\n";
    runWithMocks(importer, csv);
    verify(savedLines.get(0)).setLineNo(40L);
  }

  // ── CsvParseException constructors ────────────────────────────────────

  @Test
  public void csvParseExceptionMessageOnlyConstructor() {
    CsvParseException e = new CsvParseException("boom");
    assertEquals("boom", e.getMessage());
  }

  @Test
  public void csvParseExceptionMessageAndCauseConstructor() {
    RuntimeException cause = new RuntimeException("root");
    CsvParseException e = new CsvParseException("boom", cause);
    assertEquals("boom", e.getMessage());
    assertEquals(cause, e.getCause());
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  /**
   * Drives {@code importer.loadFile} with the given CSV text while mocking
   * {@link OBProvider#get} and {@link OBDal#save} so the imported lines stay
   * in memory and can be asserted on later.
   */
  private int runWithMocks(GenericCsvBankStatementImporter importer, String csv) throws Exception {
    return runWithMocks(importer, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
  }

  private int runWithMocks(GenericCsvBankStatementImporter importer, InputStream stream) throws Exception {
    try (MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBProvider provider = mock(OBProvider.class);
      OBDal dal = mock(OBDal.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      AtomicReference<FIN_BankStatementLine> nextLine = new AtomicReference<>();
      when(provider.get(FIN_BankStatementLine.class)).thenAnswer(inv -> {
        FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
        nextLine.set(line);
        return line;
      });
      // Capture every save() call so individual tests can assert on attached fields.
      doAnswer(inv -> {
        savedLines.add(inv.getArgument(0));
        return null;
      }).when(dal).save(any(FIN_BankStatementLine.class));

      int count = importer.loadFile(stream, statement);
      // Sanity check: OBDal.save is invoked once per parsed line.
      verify(dal, times(savedLines.size())).save(any(FIN_BankStatementLine.class));
      return count;
    }
  }

  private static String repeat(String s, int times) {
    StringBuilder sb = new StringBuilder(s.length() * times);
    for (int i = 0; i < times; i++) sb.append(s);
    return sb.toString();
  }
}
