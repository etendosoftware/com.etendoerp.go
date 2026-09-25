/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BankStatementEntityHandler} (ETP-5447): generic CRUD writes on
 * {@code importedBankStatements} and {@code bankStatementLines} are refused with a 405 that points
 * to the {@code bank-statements} actions; reads, defaults and selectors pass through.
 */
class BankStatementEntityHandlerTest {

  private static final String SPEC = "financial-account";
  private static final String STATEMENTS = "importedBankStatements";
  private static final String LINES = "bankStatementLines";
  private static final String RECORD_ID = "BS-1";
  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String PATCH = "PATCH";
  private static final String DELETE = "DELETE";
  private static final String GET = "GET";
  private static final List<String> WRITE_METHODS = List.of(POST, PUT, PATCH, DELETE);
  private static final String ACTIONS_SPEC = "bank-statements";
  private static final int METHOD_NOT_ALLOWED = 405;

  private BankStatementEntityHandler handler;

  @BeforeEach
  void setUp() {
    handler = new BankStatementEntityHandler();
  }

  private static NeoContext context(String entity, NeoEndpointType type, String method) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(entity)
        .endpointType(type)
        .httpMethod(method)
        .recordId(RECORD_ID)
        .requestBody(new JSONObject())
        .build();
  }

  private static String errorMessageOf(NeoResponse response) {
    assertNotNull(response);
    JSONObject error = response.getBody().optJSONObject("error");
    assertNotNull(error, response.getBody().toString());
    return error.optString("message");
  }

  /** A 405 whose message points the caller to the bank-statements actions. */
  private static void assertRefused(NeoResponse response, String label) {
    assertNotNull(response, label);
    assertEquals(METHOD_NOT_ALLOWED, response.getHttpStatus(), label);
    String message = errorMessageOf(response);
    assertTrue(message.contains(ACTIONS_SPEC), label + ": " + message);
  }

  // ── generic CRUD writes are refused ───────────────────────────────────

  @Test
  void testEveryGenericWriteOnStatementsAnswers405PointingToTheBankStatementsActions() {
    for (String method : WRITE_METHODS) {
      assertRefused(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, method)), method);
    }
  }

  @Test
  void testEveryGenericWriteOnLinesAnswers405PointingToTheBankStatementsActions() {
    for (String method : WRITE_METHODS) {
      assertRefused(handler.handle(context(LINES, NeoEndpointType.CRUD, method)), method);
    }
  }

  @Test
  void testAWriteWithoutAnEndpointTypeIsTreatedAsCrudAndRefused() {
    assertRefused(handler.handle(context(STATEMENTS, null, POST)), POST);
    assertRefused(handler.handle(context(LINES, null, DELETE)), DELETE);
  }

  @Test
  void testALowerCaseWriteMethodIsRefusedOnBothEntities() {
    for (String method : WRITE_METHODS) {
      String lower = method.toLowerCase(Locale.ROOT);
      assertRefused(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, lower)), lower);
      assertRefused(handler.handle(context(LINES, NeoEndpointType.CRUD, lower)), lower);
    }
  }

  @Test
  void testAWriteOnAnUnguardedEntityPassesThrough() {
    assertNull(handler.handle(context("account", NeoEndpointType.CRUD, POST)));
    assertNull(handler.handle(context("account", NeoEndpointType.CRUD, DELETE)));
  }

  @Test
  void testEachStatementWriteGetsItsOwnMessage() {
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_CREATE_DISABLED,
        errorMessageOf(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, POST))));
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_UPDATE_DISABLED,
        errorMessageOf(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, PUT))));
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_UPDATE_DISABLED,
        errorMessageOf(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, PATCH))));
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_DELETE_DISABLED,
        errorMessageOf(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, DELETE))));
  }

  @Test
  void testEveryLineWriteGetsTheLinesMessage() {
    for (String method : WRITE_METHODS) {
      assertEquals(BankStatementEntityHandler.MSG_LINES_WRITE_DISABLED,
          errorMessageOf(handler.handle(context(LINES, NeoEndpointType.CRUD, method))), method);
    }
  }

  @Test
  void testRefuseGenericWriteIgnoresAnUnguardedEntity() {
    assertNull(BankStatementEntityHandler.refuseGenericWrite("account", POST));
  }

  @Test
  void testRefuseGenericWriteSelectsTheMessageRegardlessOfCase() {
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_CREATE_DISABLED,
        errorMessageOf(BankStatementEntityHandler.refuseGenericWrite(STATEMENTS, "post")));
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_DELETE_DISABLED,
        errorMessageOf(BankStatementEntityHandler.refuseGenericWrite(STATEMENTS, "delete")));
  }

  /** Named actions are served by the ETP-5468 mechanism, not this handler: ACTION passes through. */
  @Test
  void testActionRequestsPassThroughOnBothEntities() {
    for (String method : WRITE_METHODS) {
      assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.ACTION, method)), method);
      assertNull(handler.handle(context(LINES, NeoEndpointType.ACTION, method)), method);
    }
  }

  // ── reads pass through ────────────────────────────────────────────────

  @Test
  void testGenericReadsPassThrough() {
    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, GET)));
    assertNull(handler.handle(context(LINES, NeoEndpointType.CRUD, GET)));
  }

  @Test
  void testALowerCaseReadIsNotRefused() {
    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, "get")));
    assertNull(handler.handle(context(LINES, NeoEndpointType.CRUD, "get")));
  }

  @Test
  void testDefaultsAndSelectorsPassThrough() {
    for (NeoEndpointType type : NeoEndpointType.values()) {
      if (type == NeoEndpointType.CRUD || type == NeoEndpointType.ACTION) {
        continue;
      }
      assertNull(handler.handle(context(STATEMENTS, type, POST)), type.name());
      assertNull(handler.handle(context(LINES, type, GET)), type.name());
    }
  }

  @Test
  void testACrudRequestWithoutAMethodPassesThroughOnBothEntitiesWithoutError() {
    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, null)));
    assertNull(handler.handle(context(LINES, NeoEndpointType.CRUD, null)));
    assertNull(handler.handle(context(STATEMENTS, null, null)));
  }

  // ── afterHandle / registration ────────────────────────────────────────

  @Test
  void testAfterHandleNeverReplacesTheResponse() {
    assertNull(handler.afterHandle(context(STATEMENTS, NeoEndpointType.CRUD, GET)));
    assertNull(handler.afterHandle(context(LINES, NeoEndpointType.CRUD, POST)));
  }

  @Test
  void testIsRegisteredUnderItsQualifier() {
    Named named = BankStatementEntityHandler.class.getAnnotation(Named.class);

    assertNotNull(named);
    assertEquals("bankStatementEntityHandler", named.value());
  }
}
