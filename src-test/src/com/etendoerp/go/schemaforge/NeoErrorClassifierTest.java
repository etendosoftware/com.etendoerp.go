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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.PropertyValueException;
import org.hibernate.TransientObjectException;
import org.hibernate.exception.GenericJDBCException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.service.json.JsonUtils;

import com.etendoerp.go.schemaforge.NeoErrorClassifier.Classification;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;

/**
 * Unit tests for the error classification shared by every NEO channel: what status and code a
 * failure gets, from the exception alone, and how that becomes a response.
 *
 * @covers com.etendoerp.go.schemaforge.util.NeoValidationErrorResponseBuilder
 * @covers com.etendoerp.go.schemaforge.NeoErrorClassifier
 * @covers com.etendoerp.go.schemaforge.NeoErrorResponses
 */
class NeoErrorClassifierTest {

  private static final String TRIGGER_MESSAGE =
      "ERROR: @20502@\n  Where: PL/pgSQL function c_order_chk_restrinctions_trg() line 20 at RAISE";

  /** A PostgreSQL error as Hibernate wraps it on flush. */
  private static RuntimeException dbFailure(String sqlState, String message) {
    return new GenericJDBCException("could not execute statement",
        new SQLException(message, sqlState));
  }

  @Test
  void untranslatedTriggerDoesNotExposeDatabaseInternals() {
    Classification c = NeoErrorClassifier.classify(
        dbFailure("P0001", "ERROR: relation private_table does not exist"), "PUT");
    assertEquals(422, c.status());
    assertEquals("A business rule rejected this operation.", NeoErrorClassifier.displayMessage(c));
  }

  @Test
  void translatedConstraintKeepsItsLanguageAcronymAndLines() {
    Classification c = NeoErrorClassifier.classify(
        dbFailure("23503", "ERROR: violates foreign key constraint"), "DELETE");
    OBError translated = new OBError();
    translated.setMessage("NIF: Registro utilizado.\nRevise los documentos relacionados.");
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
          .thenReturn(translated);
      assertEquals(translated.getMessage(), NeoErrorClassifier.displayMessage(c));
    }
  }

  @Test
  void staleExceptionIsAConflictBeforeItsMessageKeyIsClassified() {
    Classification c = NeoErrorClassifier.classify(
        new org.openbravo.service.json.OBStaleObjectException("@OBJSON_StaleDate@"), "PUT");
    assertEquals(409, c.status());
    assertEquals("stale_record", c.code());
  }

  @Test
  void invalidPropertyPathIsNotAnUnknownReference() throws Exception {
    JSONObject errors = new JSONObject().put("id", "Property not found invalid error when parsing path");
    assertFalse(com.etendoerp.go.schemaforge.util.NeoValidationErrorResponseBuilder
        .isUnknownReference(errors));
    errors.put("id", "New object object (key: missing) refered to but not present in the import set");
    assertTrue(com.etendoerp.go.schemaforge.util.NeoValidationErrorResponseBuilder
        .isUnknownReference(errors));
  }

  @Nested
  @DisplayName("classify — by SQLState")
  class BySqlState {

    @ParameterizedTest(name = "{0} on {1} -> {2} {3}")
    @CsvSource({
        "P0001, PUT,    422, business_rule",
        "23505, POST,   409, duplicate_key",
        "23502, POST,   400, missing_required_value",
        "23503, POST,   400, unknown_reference",
        "23503, DELETE, 409, record_in_use",
        "23514, PUT,    422, invalid_value",
        "22001, PUT,    422, invalid_value",
        "22P02, POST,   422, invalid_value",
        "40P01, PUT,    409, retryable_conflict",
        "40001, PUT,    409, retryable_conflict",
        "57014, GET,    503, service_unavailable",
        "08006, PUT,    503, service_unavailable",
        "53300, PUT,    503, service_unavailable",
        "57P01, PUT,    503, service_unavailable",
        "42P01, PUT,    500, server_error",
    })
    void classifiesByState(String state, String method, int status, String code) {
      Classification c = NeoErrorClassifier.classify(dbFailure(state, "ERROR: x"), method);

      assertEquals(status, c.status());
      assertEquals(code, c.code());
    }

    @Test
    @DisplayName("a trigger refusal keeps its AD message key and loses the PL/pgSQL location")
    void triggerRefusalCarriesItsKey() {
      Classification c = NeoErrorClassifier.classify(dbFailure("P0001", TRIGGER_MESSAGE), "PUT");

      assertEquals(List.of("20502"), c.messageKeys());
      assertEquals("@20502@", c.rawMessage());
    }

    @Test
    @DisplayName("the PostgreSQL error hidden behind a JDBC batch decides, not the batch wrapper")
    void readsTheNextExceptionOfABatch() {
      BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", "XX000",
          new int[0]);
      batch.setNextException(new SQLException(TRIGGER_MESSAGE, "P0001"));

      Classification c = NeoErrorClassifier.classify(
          new GenericJDBCException("could not execute batch", batch), "POST");

      assertEquals(NeoErrorClassifier.CODE_BUSINESS_RULE, c.code());
    }

    @Test
    @DisplayName("an unrecognised SQL failure is a 500 whose message names no SQL")
    void unknownSqlFailureIsSanitized() {
      Classification c = NeoErrorClassifier.classify(
          dbFailure("42703", "ERROR: column \"secret_col\" does not exist"), "PUT");

      assertEquals(500, c.status());
      assertFalse(NeoErrorClassifier.displayMessage(c).contains("secret_col"));
    }
  }

  @Nested
  @DisplayName("classify — by exception type")
  class ByType {

    @Test
    @DisplayName("a refused DAL access is a 403, even when wrapped")
    void securityIsForbidden() {
      Classification c = NeoErrorClassifier.classify(
          new OBException("wrapper", new OBSecurityException("Entity C_Order is not readable")),
          "GET");

      assertEquals(403, c.status());
      assertEquals(NeoErrorClassifier.CODE_FORBIDDEN, c.code());
    }

    @Test
    @DisplayName("a reference to a row that does not exist (Hibernate transient) is unknown_reference")
    void transientIsUnknownReference() {
      Classification c = NeoErrorClassifier.classify(
          new TransientObjectException("object references an unsaved transient instance"),
          "POST");

      assertEquals(400, c.status());
      assertEquals(NeoErrorClassifier.CODE_UNKNOWN_REFERENCE, c.code());
    }

    @Test
    @DisplayName("a null mandatory property (Hibernate) is missing_required_value")
    void propertyValueIsMissingRequiredValue() {
      Classification c = NeoErrorClassifier.classify(
          new PropertyValueException("not-null property references a null value", "Order",
              "businessPartner"), "POST");

      assertEquals(400, c.status());
      assertEquals(NeoErrorClassifier.CODE_MISSING_REQUIRED_VALUE, c.code());
    }

    @Test
    @DisplayName("an exception carrying its AD message key is a business rule")
    void messageKeyedIsBusinessRule() {
      MessageKeyedException keyed = mock(MessageKeyedException.class);
      when(keyed.getMessageKey()).thenReturn("NotEnoughStock");
      when(keyed.getMessage()).thenReturn("Not enough stock");

      Classification c = NeoErrorClassifier.classify(keyed, "POST");

      assertEquals(422, c.status());
      assertEquals(List.of("NotEnoughStock"), c.messageKeys());
    }

    @Test
    @DisplayName("an OBException whose message holds an @Key@ token is a business rule")
    void keyedOBExceptionIsBusinessRule() {
      Classification c = NeoErrorClassifier.classify(new OBException("@PeriodClosed@"), "POST");

      assertEquals(422, c.status());
      assertEquals(List.of("PeriodClosed"), c.messageKeys());
    }

    @Test
    @DisplayName("a plain OBException with no key stays a 500 (nothing says it is a refusal)")
    void plainOBExceptionIsServerError() {
      Classification c = NeoErrorClassifier.classify(new OBException("Something broke"), "POST");

      assertEquals(500, c.status());
      assertEquals("Something broke", c.rawMessage());
    }

    @Test
    @DisplayName("a keyed OBException wrapping a foreign fault is that fault, a 500")
    void keyedWrapperOfAFaultIsServerError() {
      Classification c = NeoErrorClassifier.classify(
          new OBException("@ProcessRunError@", new NullPointerException()), "POST");

      assertEquals(500, c.status());
    }

    @Test
    @DisplayName("a bare NullPointerException is a 500 named by its type, never 'null'")
    void bareNpeIsNamedByType() {
      Classification c = NeoErrorClassifier.classify(new NullPointerException(), "POST");

      assertEquals(500, c.status());
      assertEquals("NullPointerException", c.rawMessage());
    }

    @Test
    @DisplayName("no throwable at all is a 500")
    void nullIsServerError() {
      assertEquals(500, NeoErrorClassifier.classify(null, "GET").status());
    }

    @Test
    @DisplayName("a cyclic cause chain terminates")
    void cyclicChainTerminates() {
      SQLException first = new SQLException("a");
      SQLException second = new SQLException("b");
      first.setNextException(second);
      second.setNextException(first);

      assertEquals(500, NeoErrorClassifier.classify(first, "GET").status());
    }
  }

  @Nested
  @DisplayName("displayMessage")
  class DisplayMessage {

    @Test
    @DisplayName("a business rule shows its translated AD message")
    void businessRuleIsTranslated() {
      Classification c = NeoErrorClassifier.classify(dbFailure("P0001", TRIGGER_MESSAGE), "PUT");
      OBError translated = new OBError();
      translated.setMessage("Cannot change business partner or price list if there are lines.");

      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(translated);

        assertEquals("Cannot change business partner or price list if there are lines.",
            NeoErrorClassifier.displayMessage(c));
      }
    }

    @Test
    @DisplayName("a check violation without its own AD message shows a generic text, no names")
    void untranslatedCheckIsGeneric() {
      String raw = "ERROR: new row for relation \"c_order\" violates check constraint \"c_order_chk\"";
      Classification c = NeoErrorClassifier.classify(dbFailure("23514", raw), "PUT");
      OBError untranslated = new OBError();
      untranslated.setMessage(raw);

      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(untranslated);

        assertEquals(NeoErrorClassifier.MSG_INVALID_VALUE, NeoErrorClassifier.displayMessage(c));
      }
    }

    @Test
    @DisplayName("a check violation with its own AD message shows that message")
    void translatedCheckIsShown() {
      Classification c = NeoErrorClassifier.classify(
          dbFailure("23514", "ERROR: violates check constraint \"c_order_qty_chk\""), "PUT");
      OBError translated = new OBError();
      translated.setMessage("The quantity must be positive.");

      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(translated);

        assertEquals("The quantity must be positive.", NeoErrorClassifier.displayMessage(c));
      }
    }

    @Test
    @DisplayName("a row still in use shows core's translated foreign-key text, not a fixed English one")
    void recordInUseKeepsCoresTranslation() {
      Classification c = NeoErrorClassifier.classify(
          dbFailure("23503", "ERROR: update or delete on table \"c_order\" violates foreign key "
              + "constraint \"c_orderline_c_order\" on table \"c_orderline\""), "DELETE");
      OBError translated = new OBError();
      translated.setMessage("No se puede borrar: hay líneas de pedido que lo usan.");
      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(translated);

        assertEquals("No se puede borrar: hay líneas de pedido que lo usan.",
            NeoErrorClassifier.displayMessage(c));
      }
    }

    @Test
    @DisplayName("generic classes name nothing, and a duplicate keeps the wording the import UI matches")
    void genericTexts() {
      assertEquals(NeoErrorClassifier.MSG_UNKNOWN_REFERENCE, NeoErrorClassifier.displayMessage(
          NeoErrorClassifier.classify(dbFailure("23503", "ERROR: key (id)=(X) is not present"),
              "POST")));
      assertEquals(NeoErrorClassifier.MSG_RECORD_IN_USE, NeoErrorClassifier.displayMessage(
          NeoErrorClassifier.classify(dbFailure("23503", "ERROR: still referenced from \"t\""),
              "DELETE")));
      assertEquals(NeoErrorSanitizer.DUPLICATE_KEY_ERROR, NeoErrorClassifier.displayMessage(
          NeoErrorClassifier.classify(dbFailure("23505", "ERROR: duplicate key"), "POST")));
    }

    @Test
    @DisplayName("forbidden falls back to a generic text when the AD message cannot be resolved")
    void forbiddenFallsBackToGeneric() {
      Classification c = NeoErrorClassifier.classify(new OBSecurityException("x"), "GET");
      OBError unresolved = new OBError();
      unresolved.setMessage("@OBUIAPP_ActionNotAllowed@");

      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(unresolved);

        assertEquals(NeoErrorClassifier.MSG_FORBIDDEN, NeoErrorClassifier.displayMessage(c));
      }
    }

    @Test
    @DisplayName("primaryLine keeps only the first line, without the severity prefix")
    void primaryLine() {
      assertEquals("@20502@", NeoErrorClassifier.primaryLine(TRIGGER_MESSAGE));
      assertEquals("Plain text", NeoErrorClassifier.primaryLine("Plain text"));
      assertNull(NeoErrorClassifier.primaryLine(null));
    }
  }

  @Nested
  @DisplayName("NeoErrorResponses")
  class Responses {

    @Test
    @DisplayName("fromException answers the nested body with code and keys, and rolls back")
    void fromExceptionBuildsNestedBodyAndRollsBack() throws Exception {
      OBDal dal = mock(OBDal.class);
      OBError translated = new OBError();
      translated.setMessage("Cannot change business partner or price list if there are lines.");
      try (MockedStatic<OBDal> obdal = mockStatic(OBDal.class);
          MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        obdal.when(OBDal::getInstance).thenReturn(dal);
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(translated);

        NeoResponse response = NeoErrorResponses.fromException(
            dbFailure("P0001", TRIGGER_MESSAGE), "PUT", "test", "Prefix: ");

        assertEquals(422, response.getHttpStatus());
        JSONObject error = response.getBody().getJSONObject("error");
        assertEquals("business_rule", error.getString("code"));
        assertEquals(422, error.getInt("status"));
        assertEquals("Cannot change business partner or price list if there are lines.",
            error.getString("message"), "a 4xx is never prefixed");
        assertEquals("20502", error.getJSONArray("messageKeys").getString(0));
        verify(dal).rollbackAndClose();
      }
    }

    @Test
    @DisplayName("fromException prefixes and sanitizes a 5xx")
    void fromExceptionSanitizesServerErrors() throws Exception {
      try (MockedStatic<OBDal> obdal = mockStatic(OBDal.class)) {
        obdal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

        NeoResponse response = NeoErrorResponses.fromException(
            dbFailure("42P01", "ERROR: relation \"secret_table\" does not exist"), "PUT", "test",
            "Hook handler error: ");

        assertEquals(500, response.getHttpStatus());
        String message = response.getBody().getJSONObject("error").getString("message");
        assertTrue(message.startsWith("Hook handler error: "), message);
        assertFalse(message.contains("secret_table"), message);
      }
    }

    @Test
    @DisplayName("fromException leaves the session alone while a batch owns the transaction")
    void fromExceptionDoesNotRollBackABatch() {
      OBDal dal = mock(OBDal.class);
      try (MockedStatic<OBDal> obdal = mockStatic(OBDal.class)) {
        obdal.when(OBDal::getInstance).thenReturn(dal);
        BatchService.beginCallerOwnedTransaction();
        try {
          NeoErrorResponses.fromException(new OBException("@X@"), "POST", "test", null);
        } finally {
          BatchService.endCallerOwnedTransaction();
        }
        verify(dal, never()).rollbackAndClose();
      }
    }

    @Test
    @DisplayName("writeFailure attaches the classification to core's body, and it reads back")
    void writeFailureAnnotatesCoreBody() throws Exception {
      String coreBody = "{\"response\":{\"status\":-1,\"error\":{\"message\":\"m\"}}}";
      try (MockedStatic<JsonUtils> json = mockStatic(JsonUtils.class)) {
        json.when(() -> JsonUtils.convertExceptionToJson(any())).thenReturn(coreBody);

        RuntimeException fk = dbFailure("23503", "ERROR: still referenced");
        JSONObject written = new JSONObject(NeoErrorResponses.writeFailure(fk, fk, "DELETE"));
        JSONObject inner = written.getJSONObject("response");

        assertEquals("m", inner.getJSONObject("error").getString("message"),
            "core's own message is untouched");
        JSONObject classification = NeoErrorResponses.classificationOf(inner);
        assertNotNull(classification);
        assertEquals(409, classification.getInt("status"));
        assertEquals("record_in_use", classification.getString("code"));

        NeoResponse response = NeoErrorResponses.fromClassifiedWriteFailure(inner);
        assertEquals(409, response.getHttpStatus());
        assertEquals(NeoErrorClassifier.MSG_RECORD_IN_USE,
            response.getBody().getJSONObject("error").getString("message"));
      }
    }

    @Test
    @DisplayName("a captured fault uses its sanitized classification; an uncaptured body is unchanged")
    void serverFaultsAndUncapturedAreNotAnswered() throws Exception {
      JSONObject fault = new JSONObject("{\"status\":-1,\"error\":{\"message\":\"m\","
          + "\"neoClassification\":{\"status\":500,\"code\":\"server_error\"}}}");
      JSONObject uncaptured = new JSONObject("{\"status\":-1,\"error\":{\"message\":\"m\"}}");

      assertEquals(500, NeoErrorResponses.fromClassifiedWriteFailure(fault).getHttpStatus());
      assertNull(NeoErrorResponses.fromClassifiedWriteFailure(uncaptured));
      assertNull(NeoErrorResponses.classificationOf(uncaptured));
    }

    @Test
    void batchKeepsAnAlreadyTranslatedMessageAndItsParameters() throws Exception {
      String message = "NIF: Rechazado ABC.\nRevise el documento.";
      JSONObject innerError = new JSONObject().put("code", "business_rule")
          .put("message", message).put("messageKeys", new org.codehaus.jettison.json.JSONArray()
              .put("20502"));
      JSONObject failure = new JSONObject().put("error", new JSONObject().put("message", message)
          .put("detail", new JSONObject().put("error", innerError)));
      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        NeoErrorResponses.retranslateBatchFailure(failure);
        msg.verifyNoInteractions();
      }
      assertEquals(message, failure.getJSONObject("error").getString("message"));
      assertEquals(message, innerError.getString("message"));
    }

    @Test
    void batchRetranslatesRetainedKeysAfterGenericFallback() throws Exception {
      JSONObject failure = new JSONObject("{\"error\":{\"status\":422,"
          + "\"message\":\"A business rule rejected this operation.\",\"detail\":{\"error\":{"
          + "\"message\":\"A business rule rejected this operation.\","
          + "\"code\":\"business_rule\",\"messageKeys\":[\"20502\"]}}}}");
      OBError translated = new OBError();
      translated.setMessage("NIF: No se permite.\nRevise el documento.");
      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(translated);
        NeoErrorResponses.retranslateBatchFailure(failure);
      }
      JSONObject error = failure.getJSONObject("error");
      assertEquals(translated.getMessage(), error.getString("message"));
      assertEquals(translated.getMessage(),
          error.getJSONObject("detail").getJSONObject("error").getString("message"));
    }

    @Test
    @DisplayName("a batch failure still holding an @Key@ is re-translated after the rollback")
    void retranslatesBatchFailure() throws Exception {
      JSONObject failure = new JSONObject("{\"error\":{\"status\":422,\"message\":\"x\","
          + "\"detail\":{\"error\":{\"message\":\"@20502@\",\"code\":\"business_rule\"}}}}");
      OBError translated = new OBError();
      translated.setMessage("Cannot change business partner or price list if there are lines.");
      try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
        msg.when(() -> OBMessageUtils.translateError(any(), any(), any(), anyString()))
            .thenReturn(translated);

        NeoErrorResponses.retranslateBatchFailure(failure);
      }

      JSONObject error = failure.getJSONObject("error");
      assertEquals("x", error.getString("message"), "a message without a key is untouched");
      assertEquals("Cannot change business partner or price list if there are lines.",
          error.getJSONObject("detail").getJSONObject("error").getString("message"));
    }
  }
}
