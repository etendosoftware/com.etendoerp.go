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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link McpToolRouter} static helpers, MCP content formatting,
 * route() authorization gating, column type mapping, and edge cases.
 * <p>
 * CRUD handler tests that require a full DAL session run against a live Etendo
 * instance via OBBaseTest. These tests cover the pure-logic, no-DAL parts
 * plus the authorization guard and exception-wrapping logic of {@code route()}.
 *
 * <p>ETP-5051: {@code etendo_create} runs its insert inside the plan-quota gate. The router method
 * needs a live DAL and an {@code AD_Tab}, so that wiring is pinned with {@link McpSourceScanner}
 * (as the other MCP call-site guards are); the gate's behaviour is covered by
 * {@code NeoQuotaWriteGateTest} and the refusal's MCP translation by {@code McpHookExecutorTest}.
 *
 * @covers com.etendoerp.go.mcp.McpToolRouter
 * @covers com.etendoerp.go.mcp.McpToolResponses
 * @covers com.etendoerp.go.mcp.McpResponseSanitizer
 * @covers com.etendoerp.go.mcp.McpSchemaFieldBuilder
 * @covers com.etendoerp.go.mcp.McpWriteRequestSupport
 * @covers com.etendoerp.go.schemaforge.ClassicMessageRemap
 */
public class McpToolRouterTest {

  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_IS_ERROR = "isError";
  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String TOOL_NEO_LIST = "etendo_list";
  private static final String TOOL_NEO_GET = "etendo_get";
  private static final String TOOL_NEO_CREATE = "etendo_create";
  private static final String TOOL_NEO_UPDATE = "etendo_update";
  private static final String TOOL_NEO_DELETE = "etendo_delete";
  private static final String TOOL_NEO_SELECTORS = "etendo_selectors";
  private static final String TOOL_NEO_DEFAULTS = "etendo_defaults";
  private static final String TOOL_NEO_SCHEMA = "etendo_schema";
  private static final String TOOL_NEO_DISCOVER = "etendo_discover";
  private static final String TOOL_NEO_BATCH = "etendo_batch";
  private static final String TOOL_COMPLETE_ORDER = "complete_order";
  private static final String TOOL_GENERATE_INVOICE = "generate_invoice_report";
  private static final String TOOL_DOCS = "docs";

  // ── wrapAsTextContent ──────────────────────────────────────────────────

  /** Tests that wrapAsTextContent produces a valid MCP text content structure. */
  @Test
  public void testWrapAsTextContentStructure() throws Exception {
    JSONObject result = McpToolRouter.wrapAsTextContent("hello world");

    assertNotNull(result);
    assertTrue(result.has(FIELD_CONTENT));
    assertFalse(result.has(FIELD_IS_ERROR));

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(1, content.length());

    JSONObject block = content.getJSONObject(0);
    assertEquals("text", block.getString("type"));
    assertEquals("hello world", block.getString("text"));
  }

  /** Tests that wrapAsTextContent preserves embedded JSON text verbatim. */
  @Test
  public void testWrapAsTextContentWithJson() throws Exception {
    String jsonText = "{\"records\": 5}";
    JSONObject result = McpToolRouter.wrapAsTextContent(jsonText);

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(jsonText, content.getJSONObject(0).getString("text"));
  }

  /** Tests that wrapAsTextContent handles an empty string. */
  @Test
  public void testWrapAsTextContentEmptyString() throws Exception {
    JSONObject result = McpToolRouter.wrapAsTextContent("");

    assertNotNull(result);
    assertFalse(result.has(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals("", content.getJSONObject(0).getString("text"));
  }

  /** Tests that wrapAsTextContent handles special characters including quotes and newlines. */
  @Test
  public void testWrapAsTextContentSpecialCharacters() throws Exception {
    String specialText = "Line1\nLine2\t\"quoted\" & <xml>";
    JSONObject result = McpToolRouter.wrapAsTextContent(specialText);

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(specialText, content.getJSONObject(0).getString("text"));
  }

  /** Tests that wrapAsTextContent handles a very long string without truncation. */
  @Test
  public void testWrapAsTextContentLongString() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      sb.append("data-row-").append(i).append(",");
    }
    String longText = sb.toString();
    JSONObject result = McpToolRouter.wrapAsTextContent(longText);

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(longText, content.getJSONObject(0).getString("text"));
  }

  // ── compact serialisation of JSON tool results (ETP-5639 / IMP-53) ────────

  /**
   * A nested JSON body used by the compactness tests: an object inside an array inside an object,
   * the shape that pretty-printing indents most.
   */
  private static JSONObject nestedBody() throws Exception {
    JSONObject row = new JSONObject();
    row.put("name", "Line 1\nsecond line");
    row.put("qty", 2);
    JSONArray rows = new JSONArray();
    rows.put(row);
    JSONObject body = new JSONObject();
    body.put("specs", rows);
    body.put("count", 1);
    return body;
  }

  /**
   * IMP-53: a JSON tool result is rendered compact — indentation was 41 % of etendo_discover's
   * bytes. A newline inside a string value is escaped as {@code \n}, so a raw newline in the text
   * can only be pretty-print indentation.
   */
  @Test
  public void testWrapAsTextContentJsonBodyIsCompact() throws Exception {
    JSONObject result = McpToolRouter.wrapAsTextContent(nestedBody());

    String text = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text");
    assertFalse("tool result must not be pretty-printed: " + text, text.contains("\n"));
    JSONObject parsed = new JSONObject(text);
    assertEquals("Line 1\nsecond line",
        parsed.getJSONArray("specs").getJSONObject(0).getString("name"));
  }

  /** IMP-53: the error counterpart is compact too — the same egress, the same rule. */
  @Test
  public void testWrapAsErrorContentJsonBodyIsCompact() throws Exception {
    JSONObject result = McpToolRouter.wrapAsErrorContent(nestedBody());

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    String text = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text");
    assertFalse("error result must not be pretty-printed: " + text, text.contains("\n"));
  }

  /**
   * ETP-6158: the root of an MCP error result is the error object the client reads, so a Classic
   * fiscal message recognised there (here already reworded for GO) publishes its AD_MESSAGE key
   * in a root {@code messageKeys}.
   */
  @Test
  public void testWrapAsErrorContentPublishesTheClassicMessageKeyAtTheRoot() throws Exception {
    JSONObject error = new JSONObject().put("status", "error").put("title", "Error: ")
        .put("message", "El contacto no está marcado como Factura Simplificada, así que su NIF/CIF es obligatorio. Complétalo en Contactos.").put("error", "validation_error")
        .put("detail", "Request failed with status 400");

    JSONObject result = McpToolRouter.wrapAsErrorContent(error);

    JSONObject parsed = new JSONObject(
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text"));
    assertEquals("El contacto no está marcado como Factura Simplificada, así que su NIF/CIF es obligatorio. Complétalo en Contactos.", parsed.getString("message"));
    assertEquals("TBAI_EmptyNifCode", parsed.getJSONArray("messageKeys").getString(0));
  }

  /** IMP-53: the unexpected-failure envelope is rendered compact. */
  @Test
  public void testUnexpectedErrorBodyIsCompact() throws Exception {
    String text = McpToolResponses.buildUnexpectedErrorBody(TOOL_NEO_LIST,
        new IllegalStateException("boom"));

    assertFalse("error envelope must not be pretty-printed: " + text, text.contains("\n"));
    assertEquals(TOOL_NEO_LIST, new JSONObject(text).getString("tool"));
  }

  /** IMP-53: the routing-failure envelope is rendered compact. */
  @Test
  public void testRoutingErrorBodyIsCompact() throws Exception {
    String text = McpToolResponses.buildRoutingErrorBody(
        McpRoutingException.specNotFound(SPEC_SALES_ORDER), TOOL_NEO_LIST);

    assertFalse("error envelope must not be pretty-printed: " + text, text.contains("\n"));
    assertEquals("spec", new JSONObject(text).getString("field"));
  }

  // ── wrapAsErrorContent ─────────────────────────────────────────────────

  /** Tests that wrapAsErrorContent sets isError flag and wraps the message. */
  @Test
  public void testWrapAsErrorContentStructure() throws Exception {
    JSONObject result = McpToolRouter.wrapAsErrorContent("Something failed");

    assertNotNull(result);
    assertTrue(result.has(FIELD_CONTENT));
    assertTrue(result.getBoolean(FIELD_IS_ERROR));

    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(1, content.length());

    JSONObject block = content.getJSONObject(0);
    assertEquals("text", block.getString("type"));
    assertEquals("Something failed", block.getString("text"));
  }

  /**
   * ETP-6158: an exception message passed as plain text can carry a Classic fiscal message; it
   * leaves the MCP reworded for GO, in the language captured for the request (here the current
   * {@code OBContext} language, nothing having been captured). The SII sentence and the raw
   * {@code @KEY@} token need no AD_MESSAGE row, so no database is involved.
   */
  @Test
  public void testWrapAsErrorContentRemapsAClassicMessageInThePlainText() throws Exception {
    try (MockedStatic<OBContext> ctx = contextInLanguage("es_ES")) {
      JSONObject result = McpToolRouter.wrapAsErrorContent("Process execution failed: "
          + "La factura debe contener información en el campo Clave tipo factura.");

      assertTrue(result.getBoolean(FIELD_IS_ERROR));
      assertEquals("Process execution failed: La factura debe contener información en el campo "
          + "Tipo factura.", result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text"));
    }
    try (MockedStatic<OBContext> ctx = contextInLanguage("en_US")) {
      JSONObject result = McpToolRouter.wrapAsErrorContent("Error: @TBAI_ConfigNotFound@");

      assertEquals("Error: There is no active TicketBAI configuration for this organization. "
          + "Check it in Fiscal configuration (TicketBAI tab).",
          result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text"));
    }
  }

  /**
   * ETP-6158: every JSON tool result rendered by {@link McpResponseSanitizer#render} has its
   * Classic fiscal messages reworded, at any depth; a plain (non-error) result gets no root
   * {@code messageKeys}, only the {@code error} object inside it does.
   */
  @Test
  public void testRenderRemapsClassicMessagesAtAnyDepth() throws Exception {
    JSONObject body = new JSONObject()
        .put("message", "Error: @TBAI_ConfigNotFound@")
        .put("results", new JSONArray().put(new JSONObject().put("error", new JSONObject()
            .put("message", "@TBAI_ConfigNotFound@ (organización 2)"))));

    String rendered;
    try (MockedStatic<OBContext> ctx = contextInLanguage("es_ES")) {
      rendered = McpResponseSanitizer.render(body);
    }

    JSONObject parsed = new JSONObject(rendered);
    String go = "No hay ninguna configuración de TicketBAI activa para esta organización. "
        + "Revísala en Configuración fiscal (pestaña TicketBAI).";
    assertEquals("Error: " + go, parsed.getString("message"));
    assertFalse(parsed.has("messageKeys"));
    JSONObject error = parsed.getJSONArray("results").getJSONObject(0).getJSONObject("error");
    assertEquals(go + " (organización 2)", error.getString("message"));
    assertEquals("TBAI_ConfigNotFound", error.getJSONArray("messageKeys").getString(0));
  }

  /** A static OBContext whose language is {@code code}; the caller closes it. */
  private static MockedStatic<OBContext> contextInLanguage(String code) {
    org.openbravo.model.ad.system.Language language =
        mock(org.openbravo.model.ad.system.Language.class);
    when(language.getLanguage()).thenReturn(code);
    OBContext context = mock(OBContext.class);
    when(context.getLanguage()).thenReturn(language);
    MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
    ctx.when(OBContext::getOBContext).thenReturn(context);
    return ctx;
  }

  /** Tests that wrapAsErrorContent handles an empty error message. */
  @Test
  public void testWrapAsErrorContentEmptyMessage() throws Exception {
    JSONObject result = McpToolRouter.wrapAsErrorContent("");

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals("", content.getJSONObject(0).getString("text"));
  }

  /** Tests that wrapAsErrorContent handles a JSON-structured error message. */
  @Test
  public void testWrapAsErrorContentWithJsonMessage() throws Exception {
    String jsonError = "{\"error\": \"not found\", \"code\": 404}";
    JSONObject result = McpToolRouter.wrapAsErrorContent(jsonError);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(jsonError, content.getJSONObject(0).getString("text"));
  }

  // ── McpToolResponses.deleteConfirmation (ETP-5474) ─────────────────────

  /**
   * Pins the single {@code etendo_delete} success shape shared by the generic removal path and the
   * delete pre-hook: one text content item whose JSON is exactly
   * {@code {"deleted": true, "id": <recordId>}}, and no {@code isError} flag.
   */
  @Test
  public void testDeleteConfirmationReturnsDeletedTrueAndIdAsTextContent() throws Exception {
    String recordId = "FA-CONFIRM-1";

    JSONObject result = McpToolResponses.deleteConfirmation(recordId);

    assertNotNull(result);
    assertFalse("a delete confirmation must not be flagged as an error",
        result.has(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(1, content.length());
    JSONObject block = content.getJSONObject(0);
    assertEquals("text", block.getString("type"));

    JSONObject payload = new JSONObject(block.getString("text"));
    assertEquals("the confirmation carries exactly deleted + id", 2, payload.length());
    assertTrue(payload.getBoolean("deleted"));
    assertEquals(recordId, payload.getString("id"));
  }

  // ── mapColumnType ───────────────────────────────────────────────

  /** Tests that mapColumnType maps string reference IDs correctly. */
  @Test
  public void testMapColumnTypeStringRefs() {
    assertEquals("string", McpSchemaFieldBuilder.mapColumnType("10"));
    assertEquals("string", McpSchemaFieldBuilder.mapColumnType("14"));
    assertEquals("string", McpSchemaFieldBuilder.mapColumnType("34"));
  }

  /** Tests that mapColumnType maps numeric reference IDs correctly. */
  @Test
  public void testMapColumnTypeNumericRefs() {
    assertEquals("number", McpSchemaFieldBuilder.mapColumnType("11"));
    assertEquals("number", McpSchemaFieldBuilder.mapColumnType("22"));
    assertEquals("number", McpSchemaFieldBuilder.mapColumnType("29"));
    assertEquals("number", McpSchemaFieldBuilder.mapColumnType("12"));
    assertEquals("number", McpSchemaFieldBuilder.mapColumnType("800008"));
    assertEquals("number", McpSchemaFieldBuilder.mapColumnType("800019"));
  }

  /** Tests that mapColumnType maps boolean reference ID correctly. */
  @Test
  public void testMapColumnTypeBooleanRef() {
    assertEquals("boolean", McpSchemaFieldBuilder.mapColumnType("20"));
  }

  /** Tests that mapColumnType maps date/time reference IDs correctly. */
  @Test
  public void testMapColumnTypeDateTimeRefs() {
    assertEquals("date", McpSchemaFieldBuilder.mapColumnType("15"));
    assertEquals("datetime", McpSchemaFieldBuilder.mapColumnType("16"));
    assertEquals("time", McpSchemaFieldBuilder.mapColumnType("24"));
  }

  /** Tests that mapColumnType maps button reference ID correctly. */
  @Test
  public void testMapColumnTypeButtonRef() {
    assertEquals("button", McpSchemaFieldBuilder.mapColumnType("28"));
  }

  /** Tests that mapColumnType maps list reference ID correctly. */
  @Test
  public void testMapColumnTypeListRef() {
    assertEquals("list", McpSchemaFieldBuilder.mapColumnType("17"));
  }

  /** Tests that mapColumnType maps ID reference correctly. */
  @Test
  public void testMapColumnTypeIdRef() {
    assertEquals("id", McpSchemaFieldBuilder.mapColumnType("13"));
  }

  /** Tests that mapColumnType maps foreign key reference IDs correctly. */
  @Test
  public void testMapColumnTypeForeignKeyRefs() {
    assertEquals("foreignKey", McpSchemaFieldBuilder.mapColumnType("19"));
    assertEquals("foreignKey", McpSchemaFieldBuilder.mapColumnType("18"));
    assertEquals("foreignKey", McpSchemaFieldBuilder.mapColumnType("30"));
    assertEquals("foreignKey", McpSchemaFieldBuilder.mapColumnType("95E2A8B50A254B2AAE6774B8C2F28120"));
  }

  /** Tests that mapColumnType returns string for null input. */
  @Test
  public void testMapColumnTypeNullRef() {
    assertEquals("string", McpSchemaFieldBuilder.mapColumnType(null));
  }

  /** Tests that mapColumnType returns string for unknown reference ID. */
  @Test
  public void testMapColumnTypeUnknownRef() {
    assertEquals("string", McpSchemaFieldBuilder.mapColumnType("9999"));
    assertEquals("string", McpSchemaFieldBuilder.mapColumnType("unknown"));
  }

  // ── mapSelectorType ─────────────────────────────────────────────

  /** Tests that mapSelectorType maps TableDir reference correctly. */
  @Test
  public void testMapSelectorTypeTableDir() {
    assertEquals("TableDir", McpSchemaFieldBuilder.mapSelectorType("19"));
  }

  /** Tests that mapSelectorType maps Table reference correctly. */
  @Test
  public void testMapSelectorTypeTable() {
    assertEquals("Table", McpSchemaFieldBuilder.mapSelectorType("18"));
  }

  /** Tests that mapSelectorType maps Search reference correctly. */
  @Test
  public void testMapSelectorTypeSearch() {
    assertEquals("Search", McpSchemaFieldBuilder.mapSelectorType("30"));
  }

  /** Tests that mapSelectorType maps OBUISEL reference correctly. */
  @Test
  public void testMapSelectorTypeObuisel() {
    assertEquals("OBUISEL", McpSchemaFieldBuilder.mapSelectorType("95E2A8B50A254B2AAE6774B8C2F28120"));
  }

  /** Tests that mapSelectorType returns null for null input. */
  @Test
  public void testMapSelectorTypeNullRef() {
    assertNull(McpSchemaFieldBuilder.mapSelectorType(null));
  }

  /** Tests that mapSelectorType returns null for non-selector reference IDs. */
  @Test
  public void testMapSelectorTypeNonSelectorRefs() {
    assertNull(McpSchemaFieldBuilder.mapSelectorType("10"));
    assertNull(McpSchemaFieldBuilder.mapSelectorType("11"));
    assertNull(McpSchemaFieldBuilder.mapSelectorType("20"));
    assertNull(McpSchemaFieldBuilder.mapSelectorType("9999"));
  }

  // ── ToolRegistry.resolveSpecName ───────────────────────────────────────

  /** Tests that resolveSpecName returns the spec argument for all CRUD tool names. */
  @Test
  public void testResolveSpecNameForCrudTool() throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC_SALES_ORDER);
    args.put("entity", "header");

    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_LIST, args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_GET, args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_CREATE, args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_UPDATE, args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_DELETE, args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_SELECTORS, args));
    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_DEFAULTS, args));
  }

  /** Tests that resolveSpecName returns the spec argument for etendo_schema. */
  @Test
  public void testResolveSpecNameForSchemaTool() throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC_SALES_ORDER);
    args.put("entity", "header");

    assertEquals(SPEC_SALES_ORDER, ToolRegistry.resolveSpecName(TOOL_NEO_SCHEMA, args));
  }

  /** Tests that resolveSpecName returns null for the discover tool which needs no spec. */
  @Test
  public void testResolveSpecNameForDiscoverTool() {
    assertNull(ToolRegistry.resolveSpecName(TOOL_NEO_DISCOVER, null));
  }

  /** Tests that resolveSpecName converts snake_case process tool names to kebab-case. */
  @Test
  public void testResolveSpecNameForProcessTool() {
    assertEquals("complete-order", ToolRegistry.resolveSpecName(TOOL_COMPLETE_ORDER, null));
    assertEquals("validate-invoice", ToolRegistry.resolveSpecName("validate_invoice", null));
  }

  /** Tests that resolveSpecName strips the generate_ prefix for report tools. */
  @Test
  public void testResolveSpecNameForReportTool() {
    assertEquals("invoice-report",
        ToolRegistry.resolveSpecName(TOOL_GENERATE_INVOICE, null));
    assertEquals("sales-summary",
        ToolRegistry.resolveSpecName("generate_sales_summary", null));
  }

  /** Tests that resolveSpecName returns null for CRUD tools with missing arguments. */
  @Test
  public void testResolveSpecNameForCrudToolWithoutSpec() {
    assertNull(ToolRegistry.resolveSpecName(TOOL_NEO_LIST, null));
  }

  /** Tests that resolveSpecName returns a value for CRUD tools with empty spec argument. */
  @Test
  public void testResolveSpecNameForCrudToolWithEmptySpec() throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", "");
    String result = ToolRegistry.resolveSpecName(TOOL_NEO_LIST, args);
    assertNotNull(result);
  }

  // ── ToolRegistry.isCrudTool ────────────────────────────────────────────

  /** Tests that isCrudTool returns true for all known CRUD tool names. */
  @Test
  public void testIsCrudToolTrue() {
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_DISCOVER));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_LIST));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_GET));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_CREATE));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_UPDATE));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_DELETE));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_SELECTORS));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_DEFAULTS));
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_SCHEMA));
  }

  /** Tests that isCrudTool returns false for process, report, and unknown tool names. */
  @Test
  public void testIsCrudToolFalse() {
    assertFalse(ToolRegistry.isCrudTool(TOOL_COMPLETE_ORDER));
    assertFalse(ToolRegistry.isCrudTool(TOOL_GENERATE_INVOICE));
    assertFalse(ToolRegistry.isCrudTool("etendo_other"));
    assertFalse(ToolRegistry.isCrudTool(""));
  }

  /** Tests that etendo_batch is treated as a CRUD tool so spec resolution is skipped. */
  @Test
  public void testNeoBatchIsCrudTool() {
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_BATCH));
  }

  /**
   * Tests that resolveSpecName returns null for etendo_batch even with arguments --
   * each operation carries its own spec, there is no top-level spec.
   */
  @Test
  public void testResolveSpecNameForBatchTool() throws Exception {
    JSONObject args = new JSONObject();
    JSONArray ops = new JSONArray();
    JSONObject op = new JSONObject();
    op.put("id", "h");
    op.put("spec", SPEC_SALES_ORDER);
    op.put("entity", "Header");
    ops.put(op);
    args.put("operations", ops);

    assertNull(ToolRegistry.resolveSpecName(TOOL_NEO_BATCH, args));
  }

  /**
   * Tests that the router rejects etendo_batch with missing/empty operations as an MCP
   * error content block, without dispatching to BatchService (no DAL touched).
   */
  @Test
  public void testHandleBatchEmptyOperationsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();

    JSONObject result1 = router.handleBatch(null);
    assertTrue(result1.optBoolean(FIELD_IS_ERROR, false));
    assertTrue(result1.getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString("text").toLowerCase().contains("operations"));

    JSONObject empty = new JSONObject();
    empty.put("operations", new JSONArray());
    JSONObject result2 = router.handleBatch(empty);
    assertTrue(result2.optBoolean(FIELD_IS_ERROR, false));

    JSONObject missing = new JSONObject();
    JSONObject result3 = router.handleBatch(missing);
    assertTrue(result3.optBoolean(FIELD_IS_ERROR, false));
  }

  // ── ToolRegistry.snakeToKebab ─────────────────────────────────────────

  /** Tests that snakeToKebab correctly converts underscores to hyphens. */
  @Test
  public void testSnakeToKebab() {
    assertEquals("complete-order", ToolRegistry.snakeToKebab(TOOL_COMPLETE_ORDER));
    assertEquals("sales-order-lines", ToolRegistry.snakeToKebab("sales_order_lines"));
    assertEquals("invoices", ToolRegistry.snakeToKebab("invoices"));
  }

  /** Tests that snakeToKebab handles a single-character segment. */
  @Test
  public void testSnakeToKebabSingleCharSegment() {
    assertEquals("a-b-c", ToolRegistry.snakeToKebab("a_b_c"));
  }

  // ── McpAuthorizationService ────────────────────────────────────────────

  /** Tests that write tools require write scope at execution time. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsCreateWithoutWriteScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_CREATE, Set.of("neo:read"));
  }

  /** Tests that write scope allows write tools at execution time. */
  @Test
  public void testAuthorizeToolCallAllowsCreateWithWriteScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_CREATE, Set.of("neo:write"));
  }

  /** Tests that report tools require report scope at execution time. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsReportWithoutReportScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_GENERATE_INVOICE, Set.of("neo:read"));
  }

  /** Tests that the wildcard scope allows every tool type at execution time. */
  @Test
  public void testAuthorizeToolCallAllowsWildcardScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_CREATE, Set.of("neo:*"));
    McpAuthorizationService.authorizeToolCall(TOOL_COMPLETE_ORDER, Set.of("neo:*"));
    McpAuthorizationService.authorizeToolCall(TOOL_GENERATE_INVOICE, Set.of("neo:*"));
  }

  /** Tests that read tools require read scope. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsListWithoutReadScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_LIST, Set.of("neo:write"));
  }

  /** Tests that read scope allows all read-only tools. */
  @Test
  public void testAuthorizeToolCallAllowsReadToolsWithReadScope() {
    Set<String> readScope = Set.of("neo:read");
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_DISCOVER, readScope);
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_LIST, readScope);
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_GET, readScope);
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_SELECTORS, readScope);
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_DEFAULTS, readScope);
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_SCHEMA, readScope);
  }

  /** Tests that update tools require write scope. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsUpdateWithoutWriteScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_UPDATE, Set.of("neo:read"));
  }

  /** Tests that delete tools require write scope. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsDeleteWithoutWriteScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_DELETE, Set.of("neo:read"));
  }

  /** Tests that process tools require process scope. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsProcessWithoutProcessScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_COMPLETE_ORDER, Set.of("neo:read"));
  }

  /** Tests that process scope allows process tools. */
  @Test
  public void testAuthorizeToolCallAllowsProcessWithProcessScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_COMPLETE_ORDER, Set.of("neo:process"));
  }

  /** Tests that report scope allows report tools. */
  @Test
  public void testAuthorizeToolCallAllowsReportWithReportScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_GENERATE_INVOICE, Set.of("neo:report"));
  }

  /** Tests that null scopes reject all tools. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsNullScopes() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_LIST, null);
  }

  /** Tests that empty scopes reject all tools. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsEmptyScopes() {
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_LIST, Collections.emptySet());
  }

  /** Tests that null tool name throws OBSecurityException. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsNullToolName() {
    McpAuthorizationService.authorizeToolCall(null, Set.of("neo:*"));
  }

  /** Tests that empty tool name throws OBSecurityException. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsEmptyToolName() {
    McpAuthorizationService.authorizeToolCall("", Set.of("neo:*"));
  }

  /** Tests that blank tool name throws OBSecurityException. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsBlankToolName() {
    McpAuthorizationService.authorizeToolCall("   ", Set.of("neo:*"));
  }

  /** Tests that the docs tool requires read scope at execution time. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsDocsWithoutReadScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_DOCS, Set.of("neo:process"));
  }

  /** Tests that read scope allows the docs tool. */
  @Test
  public void testAuthorizeToolCallAllowsDocsWithReadScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_DOCS, Set.of("neo:read"));
  }

  /** Tests that the wildcard scope allows the docs tool. */
  @Test
  public void testAuthorizeToolCallAllowsDocsWithWildcardScope() {
    McpAuthorizationService.authorizeToolCall(TOOL_DOCS, Set.of("neo:*"));
  }

  // ── McpAuthorizationService — etendo_widget (ETP-4284 / G4) ──────────────

  /** Tests that etendo_widget requires read scope at execution time. */
  @Test
  public void testAuthorizeToolCallAllowsWidgetWithReadScope() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_WIDGET, Set.of("neo:read"));
  }

  /** Tests that the wildcard scope allows etendo_widget. */
  @Test
  public void testAuthorizeToolCallAllowsWidgetWithWildcardScope() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_WIDGET, Set.of("neo:*"));
  }

  /** Tests that etendo_widget is rejected without read scope. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsWidgetWithoutReadScope() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_WIDGET, Set.of("neo:write"));
  }

  /** Tests that etendo_widget is rejected with process scope only. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsWidgetWithProcessScope() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_WIDGET, Set.of("neo:process"));
  }

  // ── McpAuthorizationService.parseScopes ───────────────────────────────

  /** Tests that parseScopes parses space-delimited scope strings. */
  @Test
  public void testParseScopesMultiple() {
    Set<String> scopes = McpAuthorizationService.parseScopes("neo:read neo:write");
    assertEquals(2, scopes.size());
    assertTrue(scopes.contains("neo:read"));
    assertTrue(scopes.contains("neo:write"));
  }

  /** Tests that parseScopes returns empty set for null input. */
  @Test
  public void testParseScopesNull() {
    Set<String> scopes = McpAuthorizationService.parseScopes(null);
    assertTrue(scopes.isEmpty());
  }

  /** Tests that parseScopes returns empty set for blank input. */
  @Test
  public void testParseScopesBlank() {
    Set<String> scopes = McpAuthorizationService.parseScopes("   ");
    assertFalse(scopes.contains("neo:read"));
  }

  /** Tests that parseScopes handles a single scope. */
  @Test
  public void testParseScopesSingle() {
    Set<String> scopes = McpAuthorizationService.parseScopes("neo:*");
    assertEquals(1, scopes.size());
    assertTrue(scopes.contains("neo:*"));
  }

  /** Tests that parseScopes handles leading/trailing whitespace. */
  @Test
  public void testParseScopesTrimmed() {
    Set<String> scopes = McpAuthorizationService.parseScopes("  neo:read  neo:write  ");
    assertTrue(scopes.contains("neo:read"));
    assertTrue(scopes.contains("neo:write"));
  }

  /** Tests that parseScopes handles extra whitespace between scopes. */
  @Test
  public void testParseScopesExtraWhitespace() {
    Set<String> scopes = McpAuthorizationService.parseScopes("neo:read   neo:write");
    assertTrue(scopes.contains("neo:read"));
    assertTrue(scopes.contains("neo:write"));
    assertEquals(2, scopes.size());
  }

  /** Tests that parseScopes handles empty string input. */
  @Test
  public void testParseScopesEmptyString() {
    Set<String> scopes = McpAuthorizationService.parseScopes("");
    assertTrue(scopes.isEmpty());
  }

  /** Tests that parseScopes deduplicates repeated scopes. */
  @Test
  public void testParseScopesDeduplicate() {
    Set<String> scopes = McpAuthorizationService.parseScopes("neo:read neo:read neo:read");
    assertEquals(1, scopes.size());
    assertTrue(scopes.contains("neo:read"));
  }

  // ── McpAuthorizationService.authorizeResourceRead ─────────────────────

  /** Tests that authorizeResourceRead allows read scope. */
  @Test
  public void testAuthorizeResourceReadAllowsReadScope() {
    McpAuthorizationService.authorizeResourceRead(Set.of("neo:read"));
  }

  /** Tests that authorizeResourceRead allows wildcard scope. */
  @Test
  public void testAuthorizeResourceReadAllowsWildcardScope() {
    McpAuthorizationService.authorizeResourceRead(Set.of("neo:*"));
  }

  /** Tests that authorizeResourceRead rejects without read scope. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeResourceReadRejectsWithoutReadScope() {
    McpAuthorizationService.authorizeResourceRead(Set.of("neo:write"));
  }

  /** Tests that authorizeResourceRead rejects null scopes. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeResourceReadRejectsNullScopes() {
    McpAuthorizationService.authorizeResourceRead(null);
  }

  // ── route() with wildcard scope — validates authorization is bypassed ──

  /**
   * Tests that route() with wildcard scope and null arguments for each CRUD tool
   * passes authorization but fails on missing DAL/args (not scope).
   */
  @Test
  public void testRoutePassesAuthorizationWithWildcardForAllCrudTools() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");
    String[] crudTools = {
        TOOL_NEO_DISCOVER, TOOL_NEO_LIST, TOOL_NEO_GET,
        TOOL_NEO_CREATE, TOOL_NEO_UPDATE, TOOL_NEO_DELETE,
        TOOL_NEO_SELECTORS, TOOL_NEO_DEFAULTS, TOOL_NEO_SCHEMA
    };

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      for (String tool : crudTools) {
        JSONObject result = router.route(tool, null, wildcard);
        assertTrue("Expected error for " + tool + " (no DAL available)",
            result.optBoolean(FIELD_IS_ERROR, false));
        String errorText = result.getJSONArray(FIELD_CONTENT)
            .getJSONObject(0).getString("text");
        assertFalse("Error for " + tool + " should not be about scope",
            errorText.contains("requires scope"));
      }
    }
  }

  /**
   * Tests that route() with wildcard scope passes authorization for process and report
   * tools, and the subsequent error is about DAL/spec resolution, not authorization.
   */
  @Test
  public void testRoutePassesAuthorizationForProcessAndReportTools() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject processResult = router.route(TOOL_COMPLETE_ORDER, null, wildcard);
      assertTrue(processResult.optBoolean(FIELD_IS_ERROR, false));
      String processError = processResult.getJSONArray(FIELD_CONTENT)
          .getJSONObject(0).getString("text");
      assertFalse("Process error should not be about scope",
          processError.contains("requires scope"));

      JSONObject reportResult = router.route(TOOL_GENERATE_INVOICE, null, wildcard);
      assertTrue(reportResult.optBoolean(FIELD_IS_ERROR, false));
      String reportError = reportResult.getJSONArray(FIELD_CONTENT)
          .getJSONObject(0).getString("text");
      assertFalse("Report error should not be about scope",
          reportError.contains("requires scope"));
    }
  }

  // ── route() — ETP-4510 write-tier enforcement (MCP BLOCKER fix) ───────

  /**
   * Router-level regression test for the ETP-4510 code-review BLOCKER: MCP write
   * tools (etendo_create/etendo_update/etendo_delete) must deny a role whose
   * {@code AD_Window_Access} row is read-only, exactly like the REST NEO Headless
   * path does. Before the fix, {@code route()} authorized every tool call through
   * the 1-arg (GET-tier) {@code hasWindowAccess}, so a read-only role could still
   * mutate data via MCP.
   */
  @Test
  public void testRouteDeniesWriteToolsForReadOnlyWindowAccess() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");
    SFSpec spec = mock(SFSpec.class);

    String[] writeTools = {TOOL_NEO_CREATE, TOOL_NEO_UPDATE, TOOL_NEO_DELETE};

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<McpToolRouterSupport> supportMock = mockStatic(McpToolRouterSupport.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      when(spec.getSpecType()).thenReturn("W");
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_SALES_ORDER))
          .thenReturn(spec);
      // Read-only role: GET (discovery/list) is allowed, every write method is denied —
      // this is what a real read-only AD_Window_Access row now produces via
      // NeoAccessHelper.hasWindowAccess(windowId, httpMethod).
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "GET"))
          .thenReturn(true);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST"))
          .thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "PUT"))
          .thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "DELETE"))
          .thenReturn(false);

      for (String tool : writeTools) {
        JSONObject args = new JSONObject().put("spec", SPEC_SALES_ORDER);
        JSONObject result = router.route(tool, args, wildcard);

        assertTrue("Expected " + tool + " to be denied for a read-only window",
            result.optBoolean(FIELD_IS_ERROR, false));
        String errorText = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text");
        assertTrue(tool + " should be denied with an access-denied message, got: " + errorText,
            errorText.contains("Access denied"));
      }
    }
  }

  /**
   * Companion to {@link #testRouteDeniesWriteToolsForReadOnlyWindowAccess}: the same
   * read-only window must still pass authorization for a read tool (etendo_list), proving
   * the fix only tightens writes and does not regress reads.
   */
  @Test
  public void testRouteAllowsReadToolsForReadOnlyWindowAccess() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");
    SFSpec spec = mock(SFSpec.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<McpToolRouterSupport> supportMock = mockStatic(McpToolRouterSupport.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);
      when(spec.getSpecType()).thenReturn("W");
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_SALES_ORDER))
          .thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "GET"))
          .thenReturn(true);

      JSONObject args = new JSONObject().put("spec", SPEC_SALES_ORDER);
      JSONObject result = router.route(TOOL_NEO_LIST, args, wildcard);

      // Authorization passed (no SecurityException) — any remaining error is about
      // missing DAL/entity resolution, never "Access denied".
      if (result.optBoolean(FIELD_IS_ERROR, false)) {
        String errorText = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text");
        assertFalse("etendo_list should not be blocked by access control for a read-only window, "
            + "got: " + errorText, errorText.contains("Access denied"));
      }
    }
  }

  // ── route() — null args for each tool type ────────────────────────────

  /**
   * Tests that route() with etendo_get and null arguments returns an error about
   * missing arguments.
   */
  @Test
  public void testRouteNeoGetNullArgsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(TOOL_NEO_GET, null, wildcard);
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    }
  }

  /**
   * Tests that route() with etendo_create and null arguments returns an error about
   * missing arguments.
   */
  @Test
  public void testRouteNeoCreateNullArgsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(TOOL_NEO_CREATE, null, wildcard);
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    }
  }

  /**
   * Tests that route() with etendo_delete and null arguments returns an error about
   * missing arguments.
   */
  @Test
  public void testRouteNeoDeleteNullArgsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(TOOL_NEO_DELETE, null, wildcard);
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    }
  }

  /**
   * Tests that route() with etendo_selectors and null arguments returns an error about
   * missing arguments.
   */
  @Test
  public void testRouteNeoSelectorsNullArgsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(TOOL_NEO_SELECTORS, null, wildcard);
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    }
  }

  /**
   * Tests that route() with etendo_defaults and null arguments returns an error about
   * missing arguments.
   */
  @Test
  public void testRouteNeoDefaultsNullArgsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(TOOL_NEO_DEFAULTS, null, wildcard);
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    }
  }

  /**
   * Tests that route() with etendo_schema and null arguments returns an error about
   * missing arguments.
   */
  @Test
  public void testRouteNeoSchemaNullArgsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(TOOL_NEO_SCHEMA, null, wildcard);
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    }
  }

  /**
   * Tests that when route() catches an exception, the error text includes the
   * original exception message for debugging.
   */
  @Test
  public void testRouteErrorIncludesExceptionMessage() throws Exception {
    McpToolRouter router = new McpToolRouter();
    Set<String> wildcard = Set.of("neo:*");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(TOOL_NEO_LIST, null, wildcard);
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
      String errorText = result.getJSONArray(FIELD_CONTENT)
          .getJSONObject(0).getString("text");
      assertTrue("Error should mention 'Missing arguments' or similar",
          errorText.toLowerCase().contains("missing")
              || errorText.toLowerCase().contains("argument"));
    }
  }

  // ── route() — etendo_widget (ETP-4284 / G4) ──────────────────────────────

  /**
   * Tests that route() with etendo_widget and a valid widget but no DAL passes
   * authorization (the subsequent error is about spec/DAL resolution, not scope).
   */
  @Test
  public void testRouteWidgetPassesAuthorizationWithReadScope() throws Exception {
    McpToolRouter router = new McpToolRouter();

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject args = new JSONObject();
      args.put("widget", "kpis");

      JSONObject result = router.route(McpConstants.TOOL_NEO_WIDGET, args, Set.of("neo:read"));
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
      String errorText = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text");
      assertFalse("etendo_widget error should not be about scope",
          errorText.contains("requires scope"));
    }
  }

  /**
   * Tests that route() rejects etendo_widget without read scope. Authorization runs
   * before the try/catch in route(), so the OBSecurityException propagates to the
   * caller (it is NOT wrapped as error content) — matching the other write/process
   * scope-rejection tests above.
   */
  @Test(expected = OBSecurityException.class)
  public void testRouteWidgetRejectedWithoutReadScope() throws Exception {
    McpToolRouter router = new McpToolRouter();

    JSONObject args = new JSONObject();
    args.put("widget", "kpis");

    router.route(McpConstants.TOOL_NEO_WIDGET, args, Set.of("neo:write"));
  }

  /**
   * Tests that route() with etendo_widget and null arguments returns an error
   * (the required 'widget' argument is missing).
   */
  @Test
  public void testRouteWidgetNullArgsReturnsError() throws Exception {
    McpToolRouter router = new McpToolRouter();

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(inv -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

      JSONObject result = router.route(McpConstants.TOOL_NEO_WIDGET, null, Set.of("neo:*"));
      assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    }
  }

  // ── McpToolRouterSupport.isMandatoryValueMissing ──────────────────────

  /** Tests that isMandatoryValueMissing returns true for missing keys. */
  @Test
  public void testIsMandatoryValueMissingForAbsentKey() throws Exception {
    JSONObject body = new JSONObject();
    assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "someField"));
  }

  /** Tests that isMandatoryValueMissing returns true for null values. */
  @Test
  public void testIsMandatoryValueMissingForNullValue() throws Exception {
    JSONObject body = new JSONObject();
    body.put("someField", JSONObject.NULL);
    assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "someField"));
  }

  /** Tests that isMandatoryValueMissing returns true for empty string values. */
  @Test
  public void testIsMandatoryValueMissingForEmptyString() throws Exception {
    JSONObject body = new JSONObject();
    body.put("someField", "");
    assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "someField"));
  }

  /** Tests that isMandatoryValueMissing returns false for non-empty string values. */
  @Test
  public void testIsMandatoryValueNotMissingForNonEmptyString() throws Exception {
    JSONObject body = new JSONObject();
    body.put("someField", "value123");
    assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "someField"));
  }

  /** Tests that isMandatoryValueMissing returns false for numeric values. */
  @Test
  public void testIsMandatoryValueNotMissingForNumericValue() throws Exception {
    JSONObject body = new JSONObject();
    body.put("someField", 42);
    assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "someField"));
  }

  /** Tests that isMandatoryValueMissing returns false for boolean values. */
  @Test
  public void testIsMandatoryValueNotMissingForBooleanValue() throws Exception {
    JSONObject body = new JSONObject();
    body.put("someField", true);
    assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "someField"));
  }

  // ── McpSchemaFieldBuilder.mapColumnType exhaustive test ────────────────

  /** Tests that all known reference IDs are mapped exhaustively via support class. */
  @Test
  public void testMapColumnTypeExhaustiveSwitch() {
    String[] stringRefs = {"10", "14", "34"};
    for (String ref : stringRefs) {
      assertEquals("string for ref " + ref, "string",
          McpSchemaFieldBuilder.mapColumnType(ref));
    }

    String[] numberRefs = {"11", "22", "29", "12", "800008", "800019"};
    for (String ref : numberRefs) {
      assertEquals("number for ref " + ref, "number",
          McpSchemaFieldBuilder.mapColumnType(ref));
    }

    assertEquals("boolean", McpSchemaFieldBuilder.mapColumnType("20"));
    assertEquals("date", McpSchemaFieldBuilder.mapColumnType("15"));
    assertEquals("datetime", McpSchemaFieldBuilder.mapColumnType("16"));
    assertEquals("time", McpSchemaFieldBuilder.mapColumnType("24"));
    assertEquals("button", McpSchemaFieldBuilder.mapColumnType("28"));
    assertEquals("list", McpSchemaFieldBuilder.mapColumnType("17"));
    assertEquals("id", McpSchemaFieldBuilder.mapColumnType("13"));

    assertEquals("foreignKey", McpSchemaFieldBuilder.mapColumnType("19"));
    assertEquals("foreignKey", McpSchemaFieldBuilder.mapColumnType("18"));
    assertEquals("foreignKey", McpSchemaFieldBuilder.mapColumnType("30"));

    assertEquals("string", McpSchemaFieldBuilder.mapColumnType("99999"));
    assertEquals("string", McpSchemaFieldBuilder.mapColumnType(null));
  }

  // ── McpSchemaFieldBuilder.mapSelectorType exhaustive test ──────────────

  /** Tests that all selector type mappings are correct via support class. */
  @Test
  public void testMapSelectorTypeExhaustiveSwitch() {
    assertEquals("TableDir", McpSchemaFieldBuilder.mapSelectorType("19"));
    assertEquals("Table", McpSchemaFieldBuilder.mapSelectorType("18"));
    assertEquals("Search", McpSchemaFieldBuilder.mapSelectorType("30"));
    assertEquals("OBUISEL", McpSchemaFieldBuilder.mapSelectorType(
        "95E2A8B50A254B2AAE6774B8C2F28120"));

    assertNull(McpSchemaFieldBuilder.mapSelectorType(null));
    assertNull(McpSchemaFieldBuilder.mapSelectorType("10"));
    assertNull(McpSchemaFieldBuilder.mapSelectorType("20"));
    assertNull(McpSchemaFieldBuilder.mapSelectorType("unknown"));
  }

  // ── McpToolException ──────────────────────────────────────────────────

  /** Tests that McpToolException preserves message and cause. */
  @Test
  public void testMcpToolExceptionMessageAndCause() {
    RuntimeException cause = new RuntimeException("root cause");
    McpToolException ex = new McpToolException("wrapper message", cause);

    assertEquals("wrapper message", ex.getMessage());
    assertEquals(cause, ex.getCause());
    assertEquals("root cause", ex.getCause().getMessage());
  }

  /** Tests that McpToolException is a RuntimeException. */
  @Test
  public void testMcpToolExceptionIsRuntimeException() {
    McpToolException ex = new McpToolException("test", new Exception("inner"));
    assertTrue(ex instanceof RuntimeException);
  }

  // ── wrapAsTextContent / wrapAsErrorContent — content array assertions ─

  /** Tests that text content always has exactly one element in the content array. */
  @Test
  public void testWrapAsTextContentSingleElement() throws Exception {
    JSONObject result = McpToolRouter.wrapAsTextContent("test");
    assertEquals(1, result.getJSONArray(FIELD_CONTENT).length());
  }

  /** Tests that error content always has exactly one element in the content array. */
  @Test
  public void testWrapAsErrorContentSingleElement() throws Exception {
    JSONObject result = McpToolRouter.wrapAsErrorContent("error");
    assertEquals(1, result.getJSONArray(FIELD_CONTENT).length());
  }

  /** Tests that text content does NOT have the isError flag. */
  @Test
  public void testWrapAsTextContentHasNoIsErrorFlag() throws Exception {
    JSONObject result = McpToolRouter.wrapAsTextContent("ok");
    assertFalse(result.has(FIELD_IS_ERROR));
  }

  /** Tests that error content has isError set to true (not just present). */
  @Test
  public void testWrapAsErrorContentIsErrorIsTrue() throws Exception {
    JSONObject result = McpToolRouter.wrapAsErrorContent("fail");
    assertTrue(result.has(FIELD_IS_ERROR));
    assertEquals(true, result.getBoolean(FIELD_IS_ERROR));
  }

  // ── Multiple scopes in one set ────────────────────────────────────────

  /** Tests that a set containing multiple scopes authorizes tools from any matching scope. */
  @Test
  public void testMultipleScopesAuthorizeCorrectly() {
    Set<String> multiScope = new HashSet<>();
    multiScope.add("neo:read");
    multiScope.add("neo:write");
    multiScope.add("neo:process");
    multiScope.add("neo:report");

    McpAuthorizationService.authorizeToolCall(TOOL_NEO_LIST, multiScope);
    McpAuthorizationService.authorizeToolCall(TOOL_NEO_CREATE, multiScope);
    McpAuthorizationService.authorizeToolCall(TOOL_COMPLETE_ORDER, multiScope);
    McpAuthorizationService.authorizeToolCall(TOOL_GENERATE_INVOICE, multiScope);
  }

  // ── ToolRegistry — kebabToSnake ───────────────────────────────────────

  /** Tests that kebabToSnake converts hyphens to underscores. */
  @Test
  public void testKebabToSnake() {
    assertEquals("complete_order", ToolRegistry.kebabToSnake("complete-order"));
    assertEquals("sales_order_lines", ToolRegistry.kebabToSnake("sales-order-lines"));
    assertEquals("invoices", ToolRegistry.kebabToSnake("invoices"));
  }

  /** Tests that kebabToSnake handles single-character segments. */
  @Test
  public void testKebabToSnakeSingleChar() {
    assertEquals("a_b", ToolRegistry.kebabToSnake("a-b"));
  }

  // ── etendo_schema isCrudTool ─────────────────────────────────────────────

  /** Tests that etendo_schema is classified as a CRUD tool. */
  @Test
  public void testNeoSchemaIsCrudTool() {
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_SCHEMA));
  }

  // ── McpConstants field access ─────────────────────────────────────────

  /** Tests that McpConstants GENERATE_PREFIX is "generate_". */
  @Test
  public void testGeneratePrefixConstant() {
    assertEquals("generate_", McpConstants.GENERATE_PREFIX);
  }

  /** Tests that McpConstants PARAM_ENTITY is "entity". */
  @Test
  public void testParamEntityConstant() {
    assertEquals("entity", McpConstants.PARAM_ENTITY);
  }

  /** Tests that McpConstants PARAM_FIELDS is "fields". */
  @Test
  public void testParamFieldsConstant() {
    assertEquals("fields", McpConstants.PARAM_FIELDS);
  }

  /** Tests that McpConstants PARAM_COLUMN is "column". */
  @Test
  public void testParamColumnConstant() {
    assertEquals("column", McpConstants.PARAM_COLUMN);
  }

  /** Tests that McpConstants PARAM_PARENT_ID is "parentId". */
  @Test
  public void testParamParentIdConstant() {
    assertEquals("parentId", McpConstants.PARAM_PARENT_ID);
  }

  /** Tests that McpConstants TYPE_STRING is "string". */
  @Test
  public void testTypeStringConstant() {
    assertEquals("string", McpConstants.TYPE_STRING);
  }

  /** Tests that McpConstants TYPE_OBJECT is "object". */
  @Test
  public void testTypeObjectConstant() {
    assertEquals("object", McpConstants.TYPE_OBJECT);
  }

  // ── checkJsonServiceError envelopes (ETP-4793 / IMP-17) ───────────────

  /**
   * Invokes the classifier, which before IMP-17 returned core's prose as a bare String — the leak
   * evidence B13 recorded. Moved from {@link McpToolRouter} to {@link McpWriteRequestSupport}
   * (ETP-4793, method-count split), where it is package-private static, so no instance is needed.
   */
  private JSONObject invokeCheckJsonServiceError(JSONObject responseJson, String seeAlso)
      throws Exception {
    java.lang.reflect.Method m = McpWriteRequestSupport.class.getDeclaredMethod(
        "checkJsonServiceError", JSONObject.class, String.class);
    m.setAccessible(true);
    return (JSONObject) m.invoke(null, responseJson, seeAlso);
  }

  private JSONObject dalFailure(String message) throws Exception {
    JSONObject error = new JSONObject();
    error.put("message", message);
    JSONObject inner = new JSONObject();
    inner.put("status", -1);
    inner.put("error", error);
    JSONObject json = new JSONObject();
    json.put("response", inner);
    return json;
  }

  /**
   * A callout rejecting a write is the caller's mistake: 422, so an agent knows a corrected retry is
   * worth making. The message is passed through untranslated on purpose — it comes from AD_Message in
   * the session user's language, and pinning the MCP locale is a separate change.
   */
  @Test
  public void testCalloutRejectionOnWriteIsA422() throws Exception {
    String callout = "La fecha de operación no puede ser posterior a la fecha de la factura.";
    JSONObject envelope = invokeCheckJsonServiceError(
        dalFailure(callout), McpConstants.SEE_ALSO_WRITING);

    assertNotNull(envelope);
    assertEquals(422, envelope.getInt("status"));
    assertEquals("validation_error", envelope.getString("error"));
    assertEquals(callout, envelope.getString("detail"));
    assertEquals(McpConstants.SEE_ALSO_WRITING, envelope.getString("seeAlso"));
    // No invented field: a callout rejects a combination of values more often than a single one.
    assertFalse(envelope.has("field"));
  }

  /**
   * The same core status on a read is a 500: there is nothing the agent submitted to correct, so
   * inviting a retry-with-corrections would be a loop with no exit.
   */
  @Test
  public void testDalFailureOnReadIsA500() throws Exception {
    JSONObject envelope = invokeCheckJsonServiceError(
        dalFailure("Query execution failed"), McpConstants.SEE_ALSO_READING);

    assertNotNull(envelope);
    assertEquals(500, envelope.getInt("status"));
    assertEquals("server_error", envelope.getString("error"));
  }

  /** A duplicate business key is a conflict on either verb, and the hint names the way out. */
  @Test
  public void testDuplicateKeyFailureIsA409() throws Exception {
    String duplicate = "There is already a Business Partner with the same (Client, Organization, "
        + "Search Key). (Client, Organization, Search Key) must be unique. You must change the "
        + "values entered.";
    JSONObject envelope = invokeCheckJsonServiceError(
        dalFailure(duplicate), McpConstants.SEE_ALSO_WRITING);

    assertNotNull(envelope);
    assertEquals(409, envelope.getInt("status"));
    assertEquals("conflict", envelope.getString("error"));
    assertTrue(envelope.getString("hint").contains("etendo_list"));
  }

  /** The failing-row dump must never reach an agent — it is both an internals leak and an ACE cost. */
  @Test
  public void testRowDumpIsStrippedFromTheEnvelope() throws Exception {
    String dumped = "insert failed  Detail: Failing row contains (" + "Z".repeat(300) + ").";
    JSONObject envelope = invokeCheckJsonServiceError(
        dalFailure(dumped), McpConstants.SEE_ALSO_WRITING);

    assertNotNull(envelope);
    assertFalse(envelope.toString().contains("ZZZZ"));
  }

  /**
   * Core's per-field validation failure used to arrive as {@code "Validation error: " +
   * innerResponse.toString()} — the raw DAL transport, {@code status:-4} and all. Only the per-field
   * map was ever actionable, so it is lifted and the transport dropped.
   */
  @Test
  public void testValidationErrorLiftsFieldErrorsAndDropsTransport() throws Exception {
    JSONObject errors = new JSONObject();
    errors.put("orderDate", "must not be later than the invoice date");
    JSONObject inner = new JSONObject();
    inner.put("status", -4);
    inner.put("errors", errors);
    JSONObject json = new JSONObject();
    json.put("response", inner);

    JSONObject envelope = invokeCheckJsonServiceError(json, McpConstants.SEE_ALSO_WRITING);

    assertNotNull(envelope);
    assertEquals(422, envelope.getInt("status"));
    assertEquals("validation_error", envelope.getString("error"));
    assertEquals("must not be later than the invoice date",
        envelope.getJSONObject("fieldErrors").getString("orderDate"));
    assertFalse(envelope.toString().contains("-4"));
  }

  /** A validation failure naming no field still gets a 422, with a hint that can be acted on. */
  @Test
  public void testValidationErrorWithoutFieldsStillExplainsItself() throws Exception {
    JSONObject inner = new JSONObject();
    inner.put("status", -4);
    JSONObject json = new JSONObject();
    json.put("response", inner);

    JSONObject envelope = invokeCheckJsonServiceError(json, McpConstants.SEE_ALSO_WRITING);

    assertNotNull(envelope);
    assertEquals(422, envelope.getInt("status"));
    assertFalse(envelope.has("fieldErrors"));
    assertTrue(envelope.getString("hint").contains("etendo_schema"));
  }

  /** A successful response, and one with no wrapper at all, must not be classified as a failure. */
  @Test
  public void testNonFailureResponsesReturnNull() throws Exception {
    JSONObject inner = new JSONObject();
    inner.put("status", 0);
    JSONObject ok = new JSONObject();
    ok.put("response", inner);
    assertNull(invokeCheckJsonServiceError(ok, McpConstants.SEE_ALSO_READING));

    JSONObject bare = new JSONObject();
    bare.put("data", "rows");
    assertNull(invokeCheckJsonServiceError(bare, McpConstants.SEE_ALSO_READING));
  }

  // ── buildUnexpectedErrorBody (ETP-4793 / IMP-17, closing evidence C14) ─

  /**
   * The last leak IMP-5 left open: {@code route}'s catch-all flattened every routing failure into
   * {@code "Error executing etendo_list: …"}. The envelope is a {@code server_error} on purpose — a
   * validation code would invite a retry-with-corrections that cannot succeed.
   */
  @Test
  public void testUnexpectedErrorBodyIsAServerErrorEnvelope() throws Exception {
    String body = McpToolResponses.buildUnexpectedErrorBody("etendo_list",
        new RuntimeException("null pointer somewhere"));

    JSONObject envelope = new JSONObject(body);
    assertEquals(500, envelope.getInt("status"));
    assertEquals("server_error", envelope.getString("error"));
    assertEquals("etendo_list", envelope.getString("tool"));
    assertTrue(envelope.getString("hint").contains("will not help"));
    assertFalse(body.startsWith("Error executing"));
  }

  /**
   * ETP-5051 — the quota gate wraps the insert of {@code etendo_create}: the add is the create
   * handed to {@code NeoQuotaWriteGate.run} (which picks its service, refuses before the write when
   * the client's quotas cannot be read, and checks the created row right after it), and a refusal
   * leaves the router through the MCP error translation before anything reads the add result.
   */
  @Test
  public void testCreateRunsItsInsertInsideTheQuotaGate() {
    String body = McpSourceScanner.stripComments(McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleCreate"));

    // Anchored on the calls, never on local variable names, and each searched after the one
    // before it, so a rename does not break it and an earlier unrelated call cannot satisfy it.
    int run = indexOf(body, "NeoQuotaWriteGate.run(", 0);
    int add = indexOf(body, ".add(", run);
    int refusal = indexOf(body, "neoResponseToMcpResult(", add);
    int successRead = indexOf(body, "new JSONObject(", refusal);

    assertTrue("the add is the create handed to the gate (never run before it)", run < add);
    assertTrue("a refusal returns before the add result is read as a success",
        add < refusal && refusal < successRead);
    assertEquals("etendo_create inserts exactly once", 1,
        body.split("\\.\\s*add\\s*\\(", -1).length - 1);
  }

  private static int indexOf(String body, String code, int from) {
    int at = body.replaceAll("\\s+", " ").indexOf(code, from);
    assertTrue("handleCreate no longer contains `" + code + "`: the ETP-5051 quota gate was"
        + " dropped from etendo_create, or refactored and this guard needs updating", at >= 0);
    return at;
  }

  // ── handleCreate: what the pre-hook can tell about the agent's choices ─

  /**
   * {@code etendo_create} fills the mandatory defaults into the body BEFORE it dispatches the
   * entity's pre-hook, so key presence there no longer says what the agent sent (a contact's
   * {@code customer=true} is the AD default as often as a choice). The pre-hook must therefore be
   * handed the key set of {@code userProvided} — the snapshot taken before the defaults pass and
   * never reassigned — on the very context it is dispatched with. The consumer side (a defaulted
   * key outside that set is not explicit) is specified in {@code ContactTypeRulesTest}.
   *
   * <p>Asserted on the source, as the other {@code handleCreate} call-site guards are
   * ({@link McpSourceScanner}): reaching the dispatch needs {@code DefaultJsonDataService},
   * {@code ModelProvider} and an {@code AD_Tab}, which cannot run in a unit test.</p>
   */
  @Test
  public void testCreatePreHookIsHandedTheKeysTheAgentSentBeforeDefaults() {
    String body = McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleCreate")
        .replaceAll("\\s+", "");

    int snapshot = indexOrFail(body, "JSONObjectuserProvided=newJSONObject(filteredBody.toString());");
    int defaults = indexOrFail(body, "NeoMandatoryDefaultsService.injectMandatoryDefaults(");
    int hookContext = indexOrFail(body, "NeoContexthookCtx=McpHookExecutor.buildHookContext(");
    int attribute = indexOrFail(body, "hookCtx.setAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS,"
        + "NeoCrudHelper.snapshotBodyFields(userProvided));");
    int handedOver = indexOrFail(body, ".context(hookCtx)");
    int dispatch = indexOrFail(body, "NeoExtensionDispatcher.dispatch(");

    assertTrue("userProvided must be captured before injectMandatoryDefaults fills the body",
        snapshot < defaults);
    assertEquals("userProvided must not be reassigned after the snapshot", 1,
        body.split("userProvided=", -1).length - 1);
    assertTrue("the client-provided keys must be set on the hook context before it is dispatched",
        hookContext < attribute && attribute < handedOver && handedOver < dispatch);
  }

  // ── property-path fields (ETP-6148) ─────────────────────────────────

  /**
   * ETP-6148: {@code etendo_list} and {@code etendo_get} read the property-path fields exactly as
   * the REST list and GET by id do: the paths are applied to the datasource parameters before
   * core runs the fetch, and the field filter hands the values back under their API keys after
   * it. Asserted on the source ({@link McpSourceScanner}) like the other router call sites; the
   * rewriting itself is specified in {@code NeoPropertyPathsTest}.
   */
  @Test
  public void testListAndGetApplyThePropertyPathsBeforeTheFetch() {
    String source = McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java");
    for (String method : new String[] { "handleList", "handleGet" }) {
      String body = McpSourceScanner.stripComments(McpSourceScanner.methodBody(source, method))
          .replaceAll("\\s+", "");
      int apply = body.indexOf("NeoPropertyPaths.of(fieldFilter).applyToReadParams(params);");
      int fetch = body.indexOf("jsonService.fetch(params)");
      int restore = body.indexOf("fieldFilter.filterGetResponse(responseJson);");
      assertTrue(method + " no longer applies the property paths to its read params",
          apply >= 0);
      assertTrue(method + ": the paths must be applied before the fetch", apply < fetch);
      assertTrue(method + ": the field filter restores the API keys after the fetch",
          fetch < restore);
    }
  }

  /** ETP-6148: etendo_schema publishes the property-path descriptors with the virtual fields. */
  @Test
  public void testSchemaPublishesThePropertyPathFields() {
    String body = McpSourceScanner.stripComments(McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleSchema"))
        .replaceAll("\\s+", "");
    int paths = body.indexOf("McpSchemaFieldBuilder.appendVirtualFields(fieldsArray,"
        + "McpPathFieldSchema.build(sfEntity,dalEntity));");
    int preconditions = body.indexOf("McpSchemaFieldBuilder.applyPreconditionRequirements(");
    assertTrue("handleSchema no longer appends the property-path descriptors", paths >= 0);
    assertTrue("the path descriptors must be in the array the views are built from",
        paths < preconditions);
  }

  private static int indexOrFail(String body, String anchor) {
    int index = body.indexOf(anchor);
    assertTrue("handleCreate no longer contains " + anchor + " — either the call was dropped or"
        + " the method was refactored and this guard needs updating", index >= 0);
    return index;
  }

  // ── classified failures (ETP-5782) ───────────────────────────────────

  /** A keyed refusal thrown out of a tool is a business rule the agent can act on, not a fault. */
  @Test
  public void testUnexpectedKeyedRefusalIsABusinessRuleEnvelope() throws Exception {
    org.openbravo.erpCommon.utility.OBError translated =
        new org.openbravo.erpCommon.utility.OBError();
    translated.setMessage("Cannot change business partner or price list if there are lines.");
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class);
        MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msg =
            mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
      obdal.when(org.openbravo.dal.service.OBDal::getInstance)
          .thenReturn(mock(org.openbravo.dal.service.OBDal.class));
      msg.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.translateError(
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
          .thenReturn(translated);

      JSONObject envelope = new JSONObject(McpToolResponses.buildUnexpectedErrorBody(
          "etendo_update", new org.openbravo.base.exception.OBException("@20502@")));

      assertEquals(422, envelope.getInt("status"));
      assertEquals("validation_error", envelope.getString("error"));
      assertEquals("business_rule", envelope.getString("code"));
      assertEquals("Cannot change business partner or price list if there are lines.",
          envelope.getString("detail"));
      assertEquals("20502", envelope.getJSONArray("messageKeys").getString(0));
      assertFalse("a refusal is not a bug to report", envelope.has("feedback"));
      String hint = envelope.getString("hint").toLowerCase(java.util.Locale.ROOT);
      assertFalse("a post-hook can fail after the main write committed",
          hint.contains("nothing was written"));
      assertTrue("the caller must reconcile the current state before retrying",
          hint.matches("(?s).*(check|verify|inspect|confirm).*"));
    }
  }

  /** A row still referenced, captured by NEO's write service, is a 409 conflict on MCP too. */
  @Test
  public void testCapturedWriteFailureFollowsItsClassification() throws Exception {
    JSONObject response = dalFailure("Some core prose");
    JSONObject classification = new JSONObject().put("status", 409)
        .put("code", "record_in_use").put("message", "This record cannot be deleted because "
            + "other records use it.").put("messageKeys", new JSONArray());
    response.getJSONObject("response").getJSONObject("error")
        .put("neoClassification", classification);

    JSONObject envelope = invokeCheckJsonServiceError(response, McpConstants.SEE_ALSO_WRITING);

    assertEquals(409, envelope.getInt("status"));
    assertEquals("conflict", envelope.getString("error"));
    assertEquals("record_in_use", envelope.getString("code"));
    assertEquals(McpConstants.SEE_ALSO_WRITING, envelope.getString("seeAlso"));
  }

  /**
   * ETP-6158: a classified write failure that already carries a key keeps it; the key of the
   * Classic message recognised in its {@code detail} is appended once, also when the error result
   * is rendered a second time (the text is then already the GO one).
   */
  @Test
  public void testClassifiedFailureKeepsItsKeysAndAppendsTheClassicOneOnce() throws Exception {
    JSONObject response = dalFailure("Some core prose");
    response.getJSONObject("response").getJSONObject("error").put("neoClassification",
        new JSONObject().put("status", 422).put("code", "business_rule")
            .put("message", "No se pudo completar: @TBAI_ConfigNotFound@")
            .put("messageKeys", new JSONArray().put("APRM_PAYMENTMETHOD_MISSING")));
    String go = "No hay ninguna configuración de TicketBAI activa para esta organización. "
        + "Revísala en Configuración fiscal (pestaña TicketBAI).";

    JSONObject first;
    JSONObject second;
    try (MockedStatic<OBContext> ctx = contextInLanguage("es_ES")) {
      JSONObject envelope = invokeCheckJsonServiceError(response, McpConstants.SEE_ALSO_WRITING);
      first = new JSONObject(contentText(McpToolRouter.wrapAsErrorContent(envelope)));
      second = new JSONObject(contentText(McpToolRouter.wrapAsErrorContent(
          new JSONObject(first.toString()))));
    }

    for (JSONObject rendered : List.of(first, second)) {
      assertEquals("No se pudo completar: " + go, rendered.getString("detail"));
      JSONArray keys = rendered.getJSONArray("messageKeys");
      assertEquals(rendered.toString(), 2, keys.length());
      assertEquals("APRM_PAYMENTMETHOD_MISSING", keys.getString(0));
      assertEquals("TBAI_ConfigNotFound", keys.getString(1));
    }
  }

  /** A captured fault keeps this channel's existing answer for a refused write (declared, §4.12.9). */
  @Test
  public void testCapturedServerFaultKeepsTheExistingWriteAnswer() throws Exception {
    JSONObject response = dalFailure("Some core prose");
    response.getJSONObject("response").getJSONObject("error").put("neoClassification",
        new JSONObject().put("status", 500).put("code", "server_error"));

    JSONObject envelope = invokeCheckJsonServiceError(response, McpConstants.SEE_ALSO_WRITING);

    assertEquals(422, envelope.getInt("status"));
    assertEquals("Some core prose", envelope.getString("detail"));
  }

  /**
   * An id that resolves to no row: the generic answer, identical whichever entity and id were sent,
   * so it cannot tell an agent whether the id exists for another tenant.
   */
  @Test
  public void testUnknownReferenceIsGenericAndIdentical() throws Exception {
    JSONObject first = invokeCheckJsonServiceError(unknownReference("BusinessPartner", "4028E6C7"),
        McpConstants.SEE_ALSO_WRITING);
    JSONObject second = invokeCheckJsonServiceError(unknownReference("PriceList", "FFFF0000"),
        McpConstants.SEE_ALSO_WRITING);

    assertEquals(422, first.getInt("status"));
    assertEquals("unknown_reference", first.getString("code"));
    assertFalse(first.toString().contains("BusinessPartner"));
    assertFalse(first.toString().contains("4028E6C7"));
    assertEquals(first.toString(), second.toString());
  }

  private JSONObject unknownReference(String entity, String id) throws Exception {
    JSONObject errors = new JSONObject().put("id", "New object " + entity + "(null)  (key: " + id
        + "_" + entity + ") refered to but not present in the import set");
    JSONObject inner = new JSONObject().put("status", -4).put("errors", errors);
    return new JSONObject().put("response", inner);
  }

  // ── ETP-5684: accounting status never reaches a role without showAccountingFields ──

  private static String contentText(JSONObject result) throws Exception {
    return result.getJSONArray("content").getJSONObject(0).getString("text");
  }

  private static JSONObject recordWithAccounting() throws Exception {
    JSONObject row = new JSONObject().put("id", "INV-1").put("posted", "Y")
        .put("posted$_identifier", "Posted").put("etblkpAccountingstatus", "Y")
        .put("documentNo", "FV1000003");
    return new JSONObject().put("data", new JSONArray().put(row));
  }

  /** Tests that every accounting property is removed, at any depth, for a role without access. */
  @Test
  public void testAccountingPropertiesStrippedWithoutTheCapability() throws Exception {
    try (MockedStatic<com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy> policy =
        mockStatic(com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
      policy.when(com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy::currentRoleSeesAccounting)
          .thenReturn(false);
      JSONObject row = new JSONObject(contentText(McpToolRouter.wrapAsTextContent(recordWithAccounting())))
          .getJSONArray("data").getJSONObject(0);
      assertFalse(row.has("posted"));
      assertFalse(row.has("posted$_identifier"));
      assertFalse(row.has("etblkpAccountingstatus"));
      assertEquals("FV1000003", row.getString("documentNo"));
    }
  }

  /** Tests that a role with access keeps the accounting properties. */
  @Test
  public void testAccountingPropertiesKeptWithTheCapability() throws Exception {
    try (MockedStatic<com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy> policy =
        mockStatic(com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
      policy.when(com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy::currentRoleSeesAccounting)
          .thenReturn(true);
      JSONObject row = new JSONObject(contentText(McpToolRouter.wrapAsTextContent(recordWithAccounting())))
          .getJSONArray("data").getJSONObject(0);
      assertEquals("Y", row.getString("posted"));
      assertEquals("Y", row.getString("etblkpAccountingstatus"));
    }
  }

  /** Tests that a body with no accounting property never reads the role flag. */
  @Test
  public void testNoAccountingPropertyNeverReadsTheRole() throws Exception {
    try (MockedStatic<com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy> policy =
        mockStatic(com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
      McpToolRouter.wrapAsTextContent(new JSONObject().put("documentNo", "FV1"));
      policy.verify(com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy::currentRoleSeesAccounting,
          org.mockito.Mockito.never());
    }
  }

  // ── handleUpdate: product change re-derives uOM (QA CP-15, AD message 20111) ──

  /**
   * {@code etendo_update} on a line that changes {@code product} must re-derive {@code uOM} from
   * the NEW product, as the REST update does. The agent cannot send uOM (read-only), so without
   * this the line hits the C_OrderLine trigger with the old unit and is refused (20111).
   *
   * <p>Wiring is asserted on the source, as the neighbouring {@code handleCreate} guards are:
   * reaching it needs {@code DefaultJsonDataService}, {@code ModelProvider} and an AD_Tab. The
   * derivation itself is specified in {@code NeoCommercialLinePolicyTest}.</p>
   */
  @Test
  public void testUpdateRederivesUomRightAfterTheAgentPriceAndNeverDefersToAClientValue() {
    String body = McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleUpdate")
        .replaceAll("\\s+", "");

    int price = indexOrFail(body, "McpLinePriceInjector.applyAgentPriceOnUpdate(");
    int uom = indexOrFail(body, "injectLineUomIfApplicable(filteredBody,dalEntity,false);");
    int mandatory = indexOrFail(body, "McpWriteRequestSupport.rejectClearedMandatoryFields(");
    int write = indexOrFail(body, "NeoExtensionDispatcher.dispatch(");

    assertTrue("uOM is re-derived after the agent price and before the write is dispatched",
        price < uom && uom < mandatory && mandatory < write);
    assertEquals("handleUpdate derives uOM exactly once, never deferring to a sent value", 1,
        body.split("injectLineUomIfApplicable\\(", -1).length - 1);
  }

  private static void invokeInjectLineUom(JSONObject body,
      org.openbravo.base.model.Entity entity) throws Exception {
    java.lang.reflect.Method m = McpToolRouter.class.getDeclaredMethod(
        "injectLineUomIfApplicable", JSONObject.class, org.openbravo.base.model.Entity.class,
        boolean.class);
    m.setAccessible(true);
    m.invoke(new McpToolRouter(), body, entity, false);
  }

  private static org.openbravo.base.model.Entity lineEntity(boolean declaresUom,
      boolean transactional) {
    org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
    when(entity.hasProperty("uOM")).thenReturn(declaresUom);
    when(entity.hasProperty("orderedQuantity")).thenReturn(transactional);
    return entity;
  }

  private static org.openbravo.model.common.plm.Product productWithUom(String uomId) {
    org.openbravo.model.common.uom.UOM uom = mock(org.openbravo.model.common.uom.UOM.class);
    when(uom.getId()).thenReturn(uomId);
    org.openbravo.model.common.plm.Product product =
        mock(org.openbravo.model.common.plm.Product.class);
    when(product.getUOM()).thenReturn(uom);
    return product;
  }

  /** A changed product writes the NEW product's uOM, replacing whatever the body held. */
  @Test
  public void testUpdateLineUomIsTheNewProductsAndReplacesAnyClientValue() throws Exception {
    JSONObject body = new JSONObject().put("product", "P-NEW").put("uOM", "STALE-UOM");
    org.openbravo.model.common.plm.Product product = productWithUom("UOM-NEW");
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
      obdal.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
      when(dal.get(org.openbravo.model.common.plm.Product.class, "P-NEW")).thenReturn(product);

      invokeInjectLineUom(body, lineEntity(true, true));

      assertEquals("UOM-NEW", body.getString("uOM"));
    }
  }

  /** A price- or quantity-only update (no product key) is left untouched and never reads the DAL. */
  @Test
  public void testUpdateWithoutProductAddsNoUom() throws Exception {
    JSONObject body = new JSONObject().put("unitPrice", 12);
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class)) {
      invokeInjectLineUom(body, lineEntity(true, true));

      assertFalse(body.has("uOM"));
      obdal.verifyNoInteractions();
    }
  }

  /** Entities that do not declare uOM, or are not transactional lines, are untouched. */
  @Test
  public void testUpdateOnEntityWithoutUomOrNotATransactionalLineIsUntouched() throws Exception {
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class)) {
      JSONObject noUom = new JSONObject().put("product", "P-NEW");
      invokeInjectLineUom(noUom, lineEntity(false, true));
      assertFalse(noUom.has("uOM"));

      JSONObject notALine = new JSONObject().put("product", "P-NEW");
      invokeInjectLineUom(notALine, lineEntity(true, false));
      assertFalse(notALine.has("uOM"));

      obdal.verifyNoInteractions();
    }
  }

  /** A {@code $ref:} product is a batch placeholder, not an id: nothing is resolved or written. */
  @Test
  public void testUpdateWithRefPrefixedProductAddsNoUom() throws Exception {
    JSONObject body = new JSONObject().put("product", "$ref:line1.product");
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class)) {
      invokeInjectLineUom(body, lineEntity(true, true));

      assertFalse(body.has("uOM"));
      obdal.verifyNoInteractions();
    }
  }

  /** Unknown product, or one without a UOM: no uOM injected and no exception (the trigger decides). */
  @Test
  public void testUpdateWithUnresolvableProductOrUomInjectsNothingAndDoesNotThrow()
      throws Exception {
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class)) {
      org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
      obdal.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
      org.openbravo.model.common.plm.Product noUom =
          mock(org.openbravo.model.common.plm.Product.class);
      when(dal.get(org.openbravo.model.common.plm.Product.class, "P-MISSING")).thenReturn(null);
      when(dal.get(org.openbravo.model.common.plm.Product.class, "P-NOUOM")).thenReturn(noUom);

      JSONObject missing = new JSONObject().put("product", "P-MISSING");
      invokeInjectLineUom(missing, lineEntity(true, true));
      assertFalse(missing.has("uOM"));

      JSONObject withoutUom = new JSONObject().put("product", "P-NOUOM");
      invokeInjectLineUom(withoutUom, lineEntity(true, true));
      assertFalse(withoutUom.has("uOM"));
    }
  }
}
