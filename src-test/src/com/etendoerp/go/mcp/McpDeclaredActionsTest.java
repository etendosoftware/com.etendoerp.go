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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link McpDeclaredActions} (ETP-5447): the catalog lookup (fail-open), the
 * contract lookup for {@code neo_action}, and running a declared action — required-parameter
 * validation before the handler, the declared HTTP method, the GET query-param flattening and the
 * "declared but not answered" 500.
 *
 * <p>{@link McpHookExecutor} is NOT mocked: {@code run} is exercised end to end through the real
 * context builder and pre-hook runner, with only {@code OBContext} stubbed.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpDeclaredActionsTest {

  private static final String SPEC = "financial-account";
  private static final String ENTITY = "account";
  private static final String RECORD_ID = "ACC-1";
  private static final String QUALIFIER = "financialAccountHandler";
  private static final String CREATE = "createStatement";
  private static final String LIST = "listStatements";
  private static final String NAME = "name";
  private static final String LINES = "lines";
  private static final String FILTER = "filter";
  private static final String KEY_CONTENT = "content";
  private static final String KEY_TEXT = "text";
  private static final String KEY_IS_ERROR = "isError";
  private static final String KEY_STATUS = "status";
  private static final String KEY_ERROR = "error";

  @Mock
  private NeoHandler handler;
  @Mock
  private SFEntity sfEntity;
  @Mock
  private Tab adTab;

  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(null);
    when(sfEntity.getADTab()).thenReturn(adTab);
    when(sfEntity.getJavaQualifier()).thenReturn(QUALIFIER);
  }

  @AfterEach
  void tearDown() {
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  private static NeoActionContract createContract() {
    return NeoActionContract.builder(CREATE)
        .param(NeoReportParam.required(NAME, NeoReportParam.TYPE_STRING, "Name"))
        .param(NeoReportParam.required(LINES, NeoReportParam.TYPE_ARRAY, "Lines"))
        .param(NeoReportParam.optional("notes", NeoReportParam.TYPE_STRING, "Notes"))
        .build();
  }

  private static NeoActionContract listContract() {
    return NeoActionContract.builder(LIST)
        .method(NeoActionContract.METHOD_GET)
        .readOnly(true)
        .build();
  }

  private static JSONObject envelopeOf(JSONObject result) throws Exception {
    return new JSONObject(result.getJSONArray(KEY_CONTENT).getJSONObject(0).getString(KEY_TEXT));
  }

  private static JSONObject validCreateParams() throws Exception {
    return new JSONObject()
        .put(NAME, "June")
        .put(LINES, new JSONArray().put(new JSONObject().put("in", 10)));
  }

  private NeoContext captureContext() {
    ArgumentCaptor<NeoContext> captor = ArgumentCaptor.forClass(NeoContext.class);
    verify(handler).handle(captor.capture());
    return captor.getValue();
  }

  // ── forCatalog ────────────────────────────────────────────────────────

  @Test
  void testForCatalogReturnsTheHandlerDeclarations() {
    List<NeoActionContract> declared = List.of(createContract(), listContract());
    when(handler.declaredActions(SPEC, ENTITY)).thenReturn(declared);

    try (MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER)).thenReturn(handler);

      assertEquals(declared, McpDeclaredActions.forCatalog(sfEntity, SPEC, ENTITY));
    }
  }

  @Test
  void testForCatalogIsEmptyWithoutAHandler() {
    try (MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER)).thenReturn(null);

      assertTrue(McpDeclaredActions.forCatalog(sfEntity, SPEC, ENTITY).isEmpty());
    }
  }

  @Test
  void testForCatalogIsEmptyWhenTheHandlerDeclaresNull() {
    when(handler.declaredActions(SPEC, ENTITY)).thenReturn(null);

    try (MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER)).thenReturn(handler);

      List<NeoActionContract> result = McpDeclaredActions.forCatalog(sfEntity, SPEC, ENTITY);
      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }

  @Test
  void testForCatalogFailsOpenWhenTheDeclarationThrows() {
    when(handler.declaredActions(SPEC, ENTITY)).thenThrow(new IllegalStateException("boom"));

    try (MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER)).thenReturn(handler);

      assertTrue(McpDeclaredActions.forCatalog(sfEntity, SPEC, ENTITY).isEmpty());
    }
  }

  @Test
  void testForCatalogFailsOpenWhenTheLookupThrows() {
    try (MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER))
          .thenThrow(new IllegalStateException("no CDI"));

      assertTrue(McpDeclaredActions.forCatalog(sfEntity, SPEC, ENTITY).isEmpty());
    }
  }

  @Test
  void testForCatalogFailsOpenForANullEntity() {
    assertTrue(McpDeclaredActions.forCatalog(null, SPEC, ENTITY).isEmpty());
  }

  // ── find ──────────────────────────────────────────────────────────────

  @Test
  void testFindReturnsTheMatchingContract() {
    NeoActionContract list = listContract();
    when(handler.declaredActions(SPEC, ENTITY)).thenReturn(List.of(createContract(), list));

    assertSame(list, McpDeclaredActions.find(handler, SPEC, ENTITY, LIST));
  }

  @Test
  void testFindReturnsNullForAnUndeclaredName() {
    when(handler.declaredActions(SPEC, ENTITY)).thenReturn(List.of(createContract()));

    assertNull(McpDeclaredActions.find(handler, SPEC, ENTITY, "Processed"));
  }

  @Test
  void testFindIsCaseSensitive() {
    when(handler.declaredActions(SPEC, ENTITY)).thenReturn(List.of(createContract()));

    assertNull(McpDeclaredActions.find(handler, SPEC, ENTITY, "createstatement"));
  }

  @Test
  void testFindReturnsNullForANullHandler() {
    assertNull(McpDeclaredActions.find(null, SPEC, ENTITY, CREATE));
  }

  @Test
  void testFindReturnsNullWhenTheDeclarationIsNull() {
    when(handler.declaredActions(SPEC, ENTITY)).thenReturn(null);

    assertNull(McpDeclaredActions.find(handler, SPEC, ENTITY, CREATE));
  }

  @Test
  void testFindTreatsAThrowingDeclarationAsNotDeclared() {
    when(handler.declaredActions(SPEC, ENTITY)).thenThrow(new IllegalStateException("boom"));

    assertNull(McpDeclaredActions.find(handler, SPEC, ENTITY, CREATE));
  }

  // ── run: required-parameter validation ────────────────────────────────

  @Test
  void testRunRejectsMissingRequiredParametersWithoutCallingTheHandler() throws Exception {
    JSONObject params = new JSONObject().put("notes", "only optional");

    JSONObject result = McpDeclaredActions.run(handler, createContract(), SPEC, ENTITY,
        RECORD_ID, params, sfEntity);

    assertTrue(result.getBoolean(KEY_IS_ERROR));
    JSONObject envelope = envelopeOf(result);
    assertEquals(422, envelope.getInt(KEY_STATUS));
    assertEquals("validation_error", envelope.getString(KEY_ERROR));
    assertTrue(envelope.getString("detail").contains(CREATE), envelope.toString());
    JSONArray missing = envelope.getJSONArray(McpDeclaredActions.KEY_MISSING_PARAMETERS);
    assertEquals(2, missing.length());
    assertEquals(NAME, missing.getString(0));
    assertEquals(LINES, missing.getString(1));
    assertTrue(envelope.getString("hint").contains("neo_schema"));
    verify(handler, never()).handle(any());
  }

  @Test
  void testRunTreatsBlankAndNullValuesAsMissing() throws Exception {
    JSONObject params = new JSONObject()
        .put(NAME, "   ")
        .put(LINES, JSONObject.NULL);

    JSONObject result = McpDeclaredActions.run(handler, createContract(), SPEC, ENTITY,
        RECORD_ID, params, sfEntity);

    JSONArray missing = envelopeOf(result)
        .getJSONArray(McpDeclaredActions.KEY_MISSING_PARAMETERS);
    assertEquals(2, missing.length());
    verify(handler, never()).handle(any());
  }

  @Test
  void testValidateRequiredReturnsNullWhenEverythingIsPresent() throws Exception {
    assertNull(McpDeclaredActions.validateRequired(createContract(), validCreateParams()));
  }

  @Test
  void testValidateRequiredReturnsNullForAContractWithoutParameters() throws Exception {
    assertNull(McpDeclaredActions.validateRequired(listContract(), new JSONObject()));
  }

  // ── run: context shape ────────────────────────────────────────────────

  @Test
  void testRunPostBuildsAnActionContextWithTheBodyAndEmptyQueryParams() throws Exception {
    JSONObject params = validCreateParams();
    when(handler.handle(any())).thenReturn(NeoResponse.created(new JSONObject().put("id", "S1")));

    McpDeclaredActions.run(handler, createContract(), SPEC, ENTITY, RECORD_ID, params,
        sfEntity);

    NeoContext ctx = captureContext();
    assertEquals(NeoActionContract.METHOD_POST, ctx.getHttpMethod());
    assertEquals(NeoEndpointType.ACTION, ctx.getEndpointType());
    assertEquals(CREATE, ctx.getFieldName());
    assertEquals(SPEC, ctx.getSpecName());
    assertEquals(ENTITY, ctx.getEntityName());
    assertEquals(RECORD_ID, ctx.getRecordId());
    assertSame(params, ctx.getRequestBody());
    assertNotNull(ctx.getQueryParams());
    assertTrue(ctx.getQueryParams().isEmpty());
    assertSame(adTab, ctx.getAdTab());
    assertSame(sfEntity, ctx.getSfEntity());
    assertTrue(ctx.isMcpOrigin());
  }

  @Test
  void testRunGetBuildsAnActionContextWithFlattenedQueryParams() throws Exception {
    JSONObject params = new JSONObject()
        .put("limit", 5)
        .put(FILTER, new JSONObject().put("processed", true))
        .put("dropped", JSONObject.NULL);
    when(handler.handle(any())).thenReturn(NeoResponse.ok(new JSONObject()));

    McpDeclaredActions.run(handler, listContract(), SPEC, ENTITY, RECORD_ID, params,
        sfEntity);

    NeoContext ctx = captureContext();
    assertEquals(NeoActionContract.METHOD_GET, ctx.getHttpMethod());
    assertEquals(NeoEndpointType.ACTION, ctx.getEndpointType());
    assertEquals(LIST, ctx.getFieldName());
    assertSame(params, ctx.getRequestBody(), "the body is kept on GET as well");
    Map<String, String> query = ctx.getQueryParams();
    assertNotNull(query);
    assertEquals("5", query.get("limit"));
    assertEquals(new JSONObject().put("processed", true).toString(), query.get(FILTER));
    assertFalse(query.containsKey("dropped"), "a JSON null is dropped from the query");
    assertEquals(2, query.size());
  }

  @Test
  void testRunGetWithNoParametersPassesAnEmptyQueryMap() throws Exception {
    when(handler.handle(any())).thenReturn(NeoResponse.ok(new JSONObject()));

    McpDeclaredActions.run(handler, listContract(), SPEC, ENTITY, RECORD_ID, new JSONObject(),
        sfEntity);

    Map<String, String> query = captureContext().getQueryParams();
    assertNotNull(query);
    assertTrue(query.isEmpty());
  }

  @Test
  void testToQueryParamsStringifiesScalarsAndDropsNulls() throws Exception {
    JSONObject params = new JSONObject()
        .put("flag", true)
        .put("text", "abc")
        .put("none", JSONObject.NULL);

    Map<String, String> query = McpDeclaredActions.toQueryParams(params);

    assertEquals(Map.of("flag", "true", "text", "abc"), query);
  }

  // ── run: outcome ──────────────────────────────────────────────────────

  @Test
  void testRunPassesTheHandlerPayloadThrough() throws Exception {
    JSONObject body = new JSONObject().put("id", "S1").put("lineCount", 3);
    when(handler.handle(any())).thenReturn(NeoResponse.created(body));

    JSONObject result = McpDeclaredActions.run(handler, createContract(), SPEC, ENTITY,
        RECORD_ID, validCreateParams(), sfEntity);

    assertFalse(result.has(KEY_IS_ERROR));
    JSONObject payload = envelopeOf(result);
    assertEquals("S1", payload.getString("id"));
    assertEquals(3, payload.getInt("lineCount"));
  }

  @Test
  void testRunSurfacesAHandlerErrorAsAnMcpError() throws Exception {
    when(handler.handle(any()))
        .thenReturn(NeoResponse.error(400, "Missing required field: transactionDate"));

    JSONObject result = McpDeclaredActions.run(handler, createContract(), SPEC, ENTITY,
        RECORD_ID, validCreateParams(), sfEntity);

    assertTrue(result.getBoolean(KEY_IS_ERROR));
    assertEquals(400, envelopeOf(result).getInt(KEY_STATUS));
  }

  @Test
  void testRunReportsADecliningPreHookAsAServerError() throws Exception {
    when(handler.handle(any())).thenReturn(null);

    JSONObject result = McpDeclaredActions.run(handler, createContract(), SPEC, ENTITY,
        RECORD_ID, validCreateParams(), sfEntity);

    assertTrue(result.getBoolean(KEY_IS_ERROR));
    JSONObject envelope = envelopeOf(result);
    assertEquals(500, envelope.getInt(KEY_STATUS));
    assertEquals("server_error", envelope.getString(KEY_ERROR));
    assertTrue(envelope.getString("detail").contains(CREATE), envelope.toString());
    assertTrue(envelope.getString("hint").contains("neo_feedback"), envelope.toString());
    verify(handler).handle(any());
  }
}
