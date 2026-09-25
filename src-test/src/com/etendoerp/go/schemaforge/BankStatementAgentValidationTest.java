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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.financialmgmt.gl.GLItem;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link BankStatementAgentValidation} (ETP-5469): the checks the Etendo GO UI
 * applies to a manual / imported bank statement before it sends it, applied to an agent's
 * {@code createStatement} / {@code importStatement} input.
 *
 * <p>Most cases call {@link BankStatementAgentValidation#check} directly (it is what the dispatcher
 * runs after the contract); the few rules the declared contract enforces first (an empty
 * {@code lines} array, a month-13 header date) are exercised through
 * {@link BankStatementAgentActions#dispatch} so the agent-visible outcome is what is pinned.</p>
 */
@SuppressWarnings("java:S2187")
@DisplayName("BankStatementAgentValidation (ETP-5469)")
class BankStatementAgentValidationTest {

  private static final String CREATE = BankStatementAgentActions.CREATE_STATEMENT;
  private static final String IMPORT = BankStatementAgentActions.IMPORT_STATEMENT;
  private static final String ERROR = "error";
  private static final String MESSAGE = "message";
  private static final String VALID_DATE = "2026-02-28";
  private static final String DATE = "date";
  private static final String IN = "in";
  private static final String OUT = "out";
  private static final String REFERENCE = "reference";
  private static final String DESCRIPTION = "description";
  private static final String BP_NAME = "bpartnerName";
  private static final String BP_ID = "bpartnerId";
  private static final String GL_ID = "glItemId";
  private static final String LINE0 = "lines[0]: ";
  private static final String OWNED = "OWNED-1";
  private static final String FOREIGN = "FOREIGN-1";

  private BankStatementsHandler handler;
  private MockedStatic<OBContext> obContext;

  @BeforeEach
  void setUp() {
    handler = spy(new BankStatementsHandler());
    doReturn(false).when(handler).owns(any(), anyString());
    doReturn(true).when(handler).owns(any(), eq(OWNED));
    obContext = mockStatic(OBContext.class);
  }

  @AfterEach
  void tearDown() {
    obContext.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private static JSONObject line() throws Exception {
    return new JSONObject().put(DATE, VALID_DATE).put(IN, "10.00");
  }

  private static JSONObject create(JSONObject... lines) throws Exception {
    JSONArray arr = new JSONArray();
    for (JSONObject l : lines) {
      arr.put(l);
    }
    return new JSONObject().put(BankStatementAgentActions.P_NAME, "Statement Feb")
        .put(BankStatementAgentActions.P_LINES, arr);
  }

  private NeoResponse checkCreate(JSONObject params) {
    return BankStatementAgentValidation.check(handler, CREATE, params);
  }

  private NeoResponse checkLine(JSONObject line) throws Exception {
    return checkCreate(create(line));
  }

  private static String message(NeoResponse response) throws Exception {
    assertNotNull(response, "expected a refusal");
    assertEquals(NeoActionContract.SC_UNPROCESSABLE, response.getHttpStatus());
    return response.getBody().getJSONObject(ERROR).getString(MESSAGE);
  }

  private static void assertRefused(NeoResponse response, String expectedMessage)
      throws Exception {
    assertEquals(expectedMessage, message(response));
  }

  private static String chars(int n) {
    return "x".repeat(n);
  }

  /** Puts {@code raw} on {@code target}: "<absent>" skips it, "<null>" is JSON null. */
  private static JSONObject with(JSONObject target, String key, String raw) throws Exception {
    if ("<null>".equals(raw)) {
      return target.put(key, JSONObject.NULL);
    }
    return "<absent>".equals(raw) ? target : target.put(key, raw);
  }

  // ── whole-call behaviour ───────────────────────────────────────────────

  @Nested
  @DisplayName("createStatement as a whole")
  class WholeCall {

    @Test
    @DisplayName("a well-formed createStatement passes")
    void validCreatePasses() throws Exception {
      JSONObject params = create(line(), new JSONObject().put(DATE, "2026-03-01").put(OUT, 5))
          .put(BankStatementAgentActions.P_TRANSACTION_DATE, VALID_DATE)
          .put(BankStatementAgentActions.P_IMPORT_DATE, VALID_DATE)
          .put(BankStatementAgentActions.P_FILE_NAME, "manual.csv")
          .put(BankStatementAgentActions.P_NOTES, "ok");
      assertNull(checkCreate(params));
    }

    @Test
    @DisplayName("a createStatement without lines (contract bypassed) is a 422, not an exception")
    void missingLinesArrayIs422() throws Exception {
      String msg = message(checkCreate(new JSONObject()
          .put(BankStatementAgentActions.P_NAME, "S")));
      assertTrue(msg.startsWith("Invalid action parameters: "), msg);
    }

    @Test
    @DisplayName("the index of the offending line is named (second line bad → lines[1])")
    void offendingLineIndexIsNamed() throws Exception {
      NeoResponse response = checkCreate(create(line(), new JSONObject().put(DATE, VALID_DATE)));
      assertRefused(response, "lines[1]: needs an amount in either in or out.");
    }

    @Test
    @DisplayName("CA1: an empty lines array is refused through the dispatcher")
    void emptyLinesRefusedByDispatch() throws Exception {
      NeoResponse response = dispatch(create());
      assertEquals(NeoActionContract.SC_UNPROCESSABLE, response.getHttpStatus());
      JSONArray missing = response.getBody().getJSONObject(ERROR)
          .getJSONArray("missingParameters");
      assertEquals(1, missing.length());
      assertEquals(BankStatementAgentActions.P_LINES, missing.getString(0));
      verify(handler, never()).handleCreate(any());
    }
  }

  // ── CA2: amounts ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("CA2 — exactly one non-negative side")
  class Amounts {

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({ "in, -1", "out, -3.5" })
    @DisplayName("a negative amount on either side is refused")
    void negativeRefused(String side, String amount) throws Exception {
      assertRefused(checkLine(new JSONObject().put(DATE, VALID_DATE).put(side, amount)), LINE0
          + "amounts cannot be negative: use in for money received and out for money paid.");
    }

    @Test
    @DisplayName("in and out both > 0 is refused")
    void bothSides() throws Exception {
      assertRefused(checkLine(line().put(OUT, 1)),
          LINE0 + "has an amount in both in and out; a line is money in OR money out.");
    }

    @Test
    @DisplayName("neither side (missing, null, blank, zero) is refused")
    void neitherSide() throws Exception {
      String expected = LINE0 + "needs an amount in either in or out.";
      assertRefused(checkLine(new JSONObject().put(DATE, VALID_DATE)), expected);
      assertRefused(checkLine(new JSONObject().put(DATE, VALID_DATE).put(IN, JSONObject.NULL)
          .put(OUT, "  ")), expected);
      assertRefused(checkLine(new JSONObject().put(DATE, VALID_DATE).put(IN, 0)
          .put(OUT, "0.00")), expected);
    }

    @ParameterizedTest(name = "in=''{0}''")
    @ValueSource(strings = { "12.50", " 12.5 ", "1000", "0.01" })
    @DisplayName("a numeric string with a dot decimal separator is accepted")
    void dotDecimalAccepted(String amount) throws Exception {
      assertNull(checkLine(line().put(IN, amount)));
    }

    @Test
    @DisplayName("a comma decimal separator is refused, echoing the raw value")
    void commaDecimalRefused() throws Exception {
      assertRefused(checkLine(line().put(IN, "12,50")),
          LINE0 + "in must be a number with a dot decimal separator (got '12,50').");
    }

    @Test
    @DisplayName("a non-numeric type (boolean) is refused")
    void booleanRefused() throws Exception {
      assertRefused(checkLine(new JSONObject().put(DATE, VALID_DATE).put(OUT, true)),
          LINE0 + "out must be a number.");
    }
  }

  // ── CA3 + unknown keys ────────────────────────────────────────────────

  @Nested
  @DisplayName("CA3 — reference, and the closed set of line keys")
  class ReferenceAndKeys {

    @ParameterizedTest(name = "reference={0}")
    @ValueSource(strings = { "<absent>", "<null>", "", "   " })
    @DisplayName("a missing, null or blank reference is accepted (the handler stores '**')")
    void blankReferenceAccepted(String reference) throws Exception {
      assertNull(checkLine(with(line(), REFERENCE, reference)));
    }

    @Test
    @DisplayName("an unknown key is refused first, listing the accepted keys")
    void unknownKeyRefused() throws Exception {
      // No date and no amount either: the unknown key must still be what is reported.
      String msg = message(checkLine(new JSONObject().put("amount", "10")));
      assertTrue(msg.startsWith(LINE0 + "unknown field(s) amount. A line accepts only "), msg);
      for (String key : List.of(DATE, IN, OUT, DESCRIPTION, REFERENCE, BP_NAME, BP_ID, GL_ID)) {
        assertTrue(msg.contains(key), key);
      }
    }
  }

  // ── dates ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("dates")
  class Dates {

    @ParameterizedTest(name = "date={0}")
    @ValueSource(strings = { "<absent>", "<null>" })
    @DisplayName("a missing or null line date is refused as required")
    void lineDateRequired(String raw) throws Exception {
      assertRefused(checkLine(with(new JSONObject().put(IN, "1"), DATE, raw)),
          LINE0 + "date is required (yyyy-MM-dd).");
    }

    @ParameterizedTest(name = "date=''{0}''")
    @ValueSource(strings = { "2026-02-30", "2026-13-01", "2026-2-1", "01/02/2026", "",
        "20260101" })
    @DisplayName("an impossible, malformed or numeric line date is refused, echoing the raw value")
    void invalidLineDate(String raw) throws Exception {
      // An all-digit value is sent as a JSON number, the shape an agent would get wrong.
      Object value = raw.matches("\\d+") ? (Object) Long.valueOf(raw) : raw;
      assertRefused(checkLine(line().put(DATE, value)), LINE0
          + "date must be a valid date in yyyy-MM-dd format (got '" + raw + "').");
    }

    @ParameterizedTest(name = "date=''{0}''")
    @ValueSource(strings = { "2026-02-28", "2024-02-29", "2026-12-31" })
    @DisplayName("a real calendar date is accepted")
    void validLineDate(String raw) throws Exception {
      assertNull(checkLine(line().put(DATE, raw)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "transactionDate", "importDate" })
    @DisplayName("header dates follow the same rule, without the line prefix")
    void headerDates(String key) throws Exception {
      assertRefused(checkCreate(create(line()).put(key, "2026-02-30")),
          key + " must be a valid date in yyyy-MM-dd format (got '2026-02-30').");
      assertRefused(checkCreate(create(line()).put(key, 20260101)),
          key + " must be a valid date in yyyy-MM-dd format (got '20260101').");
      assertNull(checkCreate(create(line()).put(key, JSONObject.NULL)), "optional");
    }

    @Test
    @DisplayName("through the dispatcher a month-13 header date reaches this check (422)")
    void headerDatePassesContractRegexButIsRefused() throws Exception {
      NeoResponse response = dispatch(create(line())
          .put(BankStatementAgentActions.P_TRANSACTION_DATE, "2026-13-01"));
      assertEquals("transactionDate must be a valid date in yyyy-MM-dd format (got "
          + "'2026-13-01').", message(response));
      verify(handler, never()).handleCreate(any());
    }
  }

  // ── lengths (untrimmed) ────────────────────────────────────────────────

  @Nested
  @DisplayName("lengths are measured untrimmed")
  class Lengths {

    private NeoResponse checkField(String key, Object value, boolean onLine) throws Exception {
      return onLine ? checkLine(line().put(key, value))
          : checkCreate(create(line()).put(key, value));
    }

    @ParameterizedTest(name = "{0} <= {1}")
    @CsvSource({ "name, 60, false", "fileName, 255, false", "notes, 255, false",
        "reference, 30, true", "description, 2000, true", "bpartnerName, 60, true" })
    @DisplayName("max characters OK; a leading blank + max is refused")
    void boundary(String key, int max, boolean onLine) throws Exception {
      assertNull(checkField(key, chars(max), onLine));
      assertRefused(checkField(key, " " + chars(max), onLine), (onLine ? LINE0 : "") + key
          + " is " + (max + 1) + " characters; at most " + max + " are allowed.");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "name, false", "fileName, false", "notes, false", "description, true",
        "reference, true", "bpartnerName, true", "bpartnerId, true", "glItemId, true" })
    @DisplayName("a non-string text is refused")
    void nonStringText(String key, boolean onLine) throws Exception {
      assertRefused(checkField(key, 42, onLine), (onLine ? LINE0 : "") + key
          + " must be a string.");
    }
  }

  // ── referenced ids ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("contact / G/L item ids must belong to the company")
  class References {

    @Test
    @DisplayName("an owned bpartnerId and glItemId are accepted, in admin mode")
    void ownedAccepted() throws Exception {
      assertNull(checkLine(line().put(BP_ID, OWNED).put(GL_ID, OWNED)));
      verify(handler).owns(BusinessPartner.class, OWNED);
      verify(handler).owns(GLItem.class, OWNED);
      obContext.verify(() -> OBContext.setAdminMode(true), Mockito.times(2));
      obContext.verify(OBContext::restorePreviousMode, Mockito.times(2));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "bpartnerId, contact", "glItemId, G/L item" })
    @DisplayName("a foreign id is refused, and admin mode is restored")
    void foreignRefused(String key, String label) throws Exception {
      assertRefused(checkLine(line().put(key, FOREIGN)),
          LINE0 + key + " 'FOREIGN-1' is not a " + label + " of this company.");
      obContext.verify(OBContext::restorePreviousMode);
    }

    @Test
    @DisplayName("a blank id is not looked up")
    void blankIdSkipped() throws Exception {
      assertNull(checkLine(line().put(BP_ID, " ").put(GL_ID, "")));
      verify(handler, never()).owns(any(), anyString());
      obContext.verifyNoInteractions();
    }

    @Test
    @DisplayName("ids are only looked up once the amounts are valid")
    void amountsCheckedBeforeLookup() throws Exception {
      assertRefused(checkLine(new JSONObject().put(DATE, VALID_DATE).put(BP_ID, FOREIGN)),
          LINE0 + "needs an amount in either in or out.");
      verify(handler, never()).owns(any(), anyString());
    }
  }

  // ── import cap ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("importStatement size cap (1 MiB = 1,398,104 base64 characters)")
  class ImportCap {

    @Test
    @DisplayName("exactly the cap is accepted")
    void atCapAccepted() throws Exception {
      assertNull(BankStatementAgentValidation.check(handler, IMPORT, importOf(1_398_104)));
    }

    @Test
    @DisplayName("one character more is refused with the length and the limit")
    void overCapRefused() throws Exception {
      assertRefused(BankStatementAgentValidation.check(handler, IMPORT, importOf(1_398_105)),
          "contentBase64 is 1398105 characters; importStatement accepts at most 1398104 (a "
              + "1024 KB file). Split the statement into smaller files, or import it from the "
              + "Etendo GO UI.");
    }

    private JSONObject importOf(int length) throws Exception {
      return new JSONObject().put(BankStatementAgentActions.P_FILE_NAME, "big.n43")
          .put(BankStatementAgentActions.P_CONTENT_BASE64, "A".repeat(length));
    }
  }

  // ── dispatcher helper (with access granted) ────────────────────────────

  private NeoResponse dispatch(JSONObject params) {
    SFSpec spec = mock(SFSpec.class);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    NeoContext ctx = NeoContext.builder().specName("bank-statements").httpMethod("POST")
        .recordId("ACC-1").requestBody(params).sfEntity(entity)
        .endpointType(NeoEndpointType.ACTION).fieldName(CREATE).build();
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(any(), anyString())).thenReturn(true);
      return BankStatementAgentActions.dispatch(handler, ctx);
    }
  }
}
