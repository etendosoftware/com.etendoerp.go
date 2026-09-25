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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link BankStatementEntityHandler} (ETP-5447): ACTION requests on
 * {@code importedBankStatements} go to {@link BankStatementActionsSupport}; generic CRUD writes on
 * {@code importedBankStatements} and {@code bankStatementLines} are refused with a 405 that names
 * the action to use instead; reads, defaults and selectors pass through.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
  private static final String POST_LOWER = "post";
  private static final String DELETE_LOWER = "delete";
  private static final String ACTION_UPDATE = "update";

  @Mock
  private BankStatementActionsSupport support;

  private BankStatementEntityHandler handler;

  @BeforeEach
  void setUp() {
    handler = new BankStatementEntityHandler();
    handler.setBankStatementActions(support);
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

  private static String messageOf(NeoResponse response) {
    return response.getBody().toString();
  }

  private static String errorMessageOf(NeoResponse response) {
    assertNotNull(response);
    return response.getBody().optJSONObject("error").optString("message");
  }

  // ── generic CRUD writes are refused ───────────────────────────────────

  @Test
  void testGenericCreateOfAStatementAnswers405NamingCreateStatement() {
    NeoResponse response = handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, POST));

    assertEquals(405, response.getHttpStatus());
    assertTrue(messageOf(response).contains("createStatement"), messageOf(response));
    assertTrue(messageOf(response).contains("importStatement"), messageOf(response));
  }

  @Test
  void testGenericPutOfAStatementAnswers405NamingUpdate() {
    NeoResponse response = handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, PUT));

    assertEquals(405, response.getHttpStatus());
    assertTrue(messageOf(response).contains("'update'"), messageOf(response));
    assertTrue(messageOf(response).contains("reactivate"), messageOf(response));
  }

  @Test
  void testGenericPatchOfAStatementAnswers405NamingUpdate() {
    NeoResponse response = handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, PATCH));

    assertEquals(405, response.getHttpStatus());
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_UPDATE_DISABLED,
        response.getBody().optJSONObject("error").optString("message"));
  }

  @Test
  void testGenericDeleteOfAStatementAnswers405NamingDelete() {
    NeoResponse response = handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, DELETE));

    assertEquals(405, response.getHttpStatus());
    assertTrue(messageOf(response).contains("'delete'"), messageOf(response));
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_DELETE_DISABLED,
        response.getBody().optJSONObject("error").optString("message"));
  }

  @Test
  void testEveryGenericWriteOnLinesAnswers405NamingUpdate() {
    for (String method : List.of(POST, PUT, PATCH, DELETE)) {
      NeoResponse response = handler.handle(context(LINES, NeoEndpointType.CRUD, method));

      assertEquals(405, response.getHttpStatus(), method);
      assertTrue(messageOf(response).contains("'update'"), method + ": " + messageOf(response));
      assertTrue(messageOf(response).contains(STATEMENTS), method + ": " + messageOf(response));
    }
  }

  @Test
  void testAWriteWithoutAnEndpointTypeIsTreatedAsCrudAndRefused() {
    NeoResponse response = handler.handle(context(STATEMENTS, null, POST));

    assertEquals(405, response.getHttpStatus());
  }

  @Test
  void testTheRefusalNeverReachesTheActionSupport() {
    handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, POST));
    handler.handle(context(LINES, NeoEndpointType.CRUD, DELETE));

    verify(support, never()).handle(any());
  }

  @Test
  void testRefuseGenericWriteIgnoresAnUnguardedEntity() {
    assertNull(BankStatementEntityHandler.refuseGenericWrite("account", POST));
  }

  // ── reads pass through ────────────────────────────────────────────────

  @Test
  void testGenericReadsPassThrough() {
    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, GET)));
    assertNull(handler.handle(context(LINES, NeoEndpointType.CRUD, GET)));
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
  void testACrudRequestWithoutAMethodPassesThrough() {
    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, null)));
  }

  @Test
  void testALowerCaseWriteMethodIsRefused() {
    NeoResponse post = handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, POST_LOWER));
    NeoResponse delete = handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, DELETE_LOWER));

    assertNotNull(post);
    assertEquals(405, post.getHttpStatus());
    assertNotNull(delete);
    assertEquals(405, delete.getHttpStatus());
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_DELETE_DISABLED, errorMessageOf(delete));
  }

  @Test
  void testALowerCaseWriteOnLinesIsRefused() {
    NeoResponse response = handler.handle(context(LINES, NeoEndpointType.CRUD, POST_LOWER));

    assertNotNull(response);
    assertEquals(405, response.getHttpStatus());
  }

  @Test
  void testRefuseGenericWriteNamesTheActionRegardlessOfCase() {
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_CREATE_DISABLED,
        errorMessageOf(BankStatementEntityHandler.refuseGenericWrite(STATEMENTS, POST_LOWER)));
    assertEquals(BankStatementEntityHandler.MSG_STATEMENT_DELETE_DISABLED,
        errorMessageOf(BankStatementEntityHandler.refuseGenericWrite(STATEMENTS, DELETE_LOWER)));
  }

  @Test
  void testACrudRequestWithoutAMethodPassesThroughOnBothEntitiesWithoutError() {
    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, null)));
    assertNull(handler.handle(context(LINES, NeoEndpointType.CRUD, null)));
    assertNull(handler.handle(context(STATEMENTS, null, null)));
    verify(support, never()).handle(any());
  }

  @Test
  void testALowerCaseReadIsNotRefused() {
    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.CRUD, "get")));
  }

  // ── ACTION requests ───────────────────────────────────────────────────

  @Test
  void testActionOnStatementsIsDelegatedToTheSupport() {
    NeoContext ctx = context(STATEMENTS, NeoEndpointType.ACTION, POST);
    NeoResponse expected = NeoResponse.ok(new JSONObject());
    when(support.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
    verify(support).handle(ctx);
  }

  @Test
  void testActionThatTheSupportDeclinesReturnsNullNotA405() {
    NeoContext ctx = context(STATEMENTS, NeoEndpointType.ACTION, POST);
    when(support.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  @Test
  void testActionOnLinesIsNotDelegatedAndPassesThrough() {
    NeoContext ctx = context(LINES, NeoEndpointType.ACTION, POST);

    assertNull(handler.handle(ctx));
    verify(support, never()).handle(any());
  }

  @Test
  void testActionWithoutASupportPassesThrough() {
    handler.setBankStatementActions(null);

    assertNull(handler.handle(context(STATEMENTS, NeoEndpointType.ACTION, POST)));
  }

  // ── afterHandle / declarations ────────────────────────────────────────

  @Test
  void testAfterHandleNeverReplacesTheResponse() {
    assertNull(handler.afterHandle(context(STATEMENTS, NeoEndpointType.CRUD, GET)));
    assertNull(handler.afterHandle(context(STATEMENTS, NeoEndpointType.ACTION, POST)));
  }

  @Test
  void testDeclaredActionsForStatementsComeFromTheSupport() {
    List<NeoActionContract> declared =
        List.of(NeoActionContract.builder(ACTION_UPDATE).build());
    when(support.declaredActions(STATEMENTS)).thenReturn(declared);

    assertSame(declared, handler.declaredActions(SPEC, STATEMENTS));
  }

  @Test
  void testDeclaredActionsForLinesAndOthersAreEmpty() {
    assertTrue(handler.declaredActions(SPEC, LINES).isEmpty());
    assertTrue(handler.declaredActions(SPEC, "account").isEmpty());
    verify(support, never()).declaredActions(anyString());
  }

  @Test
  void testDeclaredActionsWithoutASupportAreEmpty() {
    handler.setBankStatementActions(null);

    List<NeoActionContract> declared = handler.declaredActions(SPEC, STATEMENTS);
    assertNotNull(declared);
    assertTrue(declared.isEmpty());
  }

  @Test
  void testServesActions() {
    assertTrue(handler.servesActions());
  }

  @Test
  void testIsRegisteredUnderItsQualifier() {
    Named named = BankStatementEntityHandler.class.getAnnotation(Named.class);

    assertNotNull(named);
    assertEquals("bankStatementEntityHandler", named.value());
  }
}
