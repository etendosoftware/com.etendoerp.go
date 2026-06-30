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
import static org.mockito.Mockito.mockStatic;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;

/**
 * Unit tests for {@link McpToolRouter} static helpers, MCP content formatting,
 * route() authorization gating, column type mapping, and edge cases.
 * <p>
 * CRUD handler tests that require a full DAL session run against a live Etendo
 * instance via OBBaseTest. These tests cover the pure-logic, no-DAL parts
 * plus the authorization guard and exception-wrapping logic of {@code route()}.
 */
public class McpToolRouterTest {

  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_IS_ERROR = "isError";
  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String TOOL_NEO_LIST = "neo_list";
  private static final String TOOL_NEO_GET = "neo_get";
  private static final String TOOL_NEO_CREATE = "neo_create";
  private static final String TOOL_NEO_UPDATE = "neo_update";
  private static final String TOOL_NEO_DELETE = "neo_delete";
  private static final String TOOL_NEO_SELECTORS = "neo_selectors";
  private static final String TOOL_NEO_DEFAULTS = "neo_defaults";
  private static final String TOOL_NEO_SCHEMA = "neo_schema";
  private static final String TOOL_NEO_DISCOVER = "neo_discover";
  private static final String TOOL_NEO_BATCH = "neo_batch";
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

  // ── mapColumnTypeStatic ───────────────────────────────────────────────

  /** Tests that mapColumnTypeStatic maps string reference IDs correctly. */
  @Test
  public void testMapColumnTypeStringRefs() {
    assertEquals("string", McpToolRouter.mapColumnTypeStatic("10"));
    assertEquals("string", McpToolRouter.mapColumnTypeStatic("14"));
    assertEquals("string", McpToolRouter.mapColumnTypeStatic("34"));
  }

  /** Tests that mapColumnTypeStatic maps numeric reference IDs correctly. */
  @Test
  public void testMapColumnTypeNumericRefs() {
    assertEquals("number", McpToolRouter.mapColumnTypeStatic("11"));
    assertEquals("number", McpToolRouter.mapColumnTypeStatic("22"));
    assertEquals("number", McpToolRouter.mapColumnTypeStatic("29"));
    assertEquals("number", McpToolRouter.mapColumnTypeStatic("12"));
    assertEquals("number", McpToolRouter.mapColumnTypeStatic("800008"));
    assertEquals("number", McpToolRouter.mapColumnTypeStatic("800019"));
  }

  /** Tests that mapColumnTypeStatic maps boolean reference ID correctly. */
  @Test
  public void testMapColumnTypeBooleanRef() {
    assertEquals("boolean", McpToolRouter.mapColumnTypeStatic("20"));
  }

  /** Tests that mapColumnTypeStatic maps date/time reference IDs correctly. */
  @Test
  public void testMapColumnTypeDateTimeRefs() {
    assertEquals("date", McpToolRouter.mapColumnTypeStatic("15"));
    assertEquals("datetime", McpToolRouter.mapColumnTypeStatic("16"));
    assertEquals("time", McpToolRouter.mapColumnTypeStatic("24"));
  }

  /** Tests that mapColumnTypeStatic maps button reference ID correctly. */
  @Test
  public void testMapColumnTypeButtonRef() {
    assertEquals("button", McpToolRouter.mapColumnTypeStatic("28"));
  }

  /** Tests that mapColumnTypeStatic maps list reference ID correctly. */
  @Test
  public void testMapColumnTypeListRef() {
    assertEquals("list", McpToolRouter.mapColumnTypeStatic("17"));
  }

  /** Tests that mapColumnTypeStatic maps ID reference correctly. */
  @Test
  public void testMapColumnTypeIdRef() {
    assertEquals("id", McpToolRouter.mapColumnTypeStatic("13"));
  }

  /** Tests that mapColumnTypeStatic maps foreign key reference IDs correctly. */
  @Test
  public void testMapColumnTypeForeignKeyRefs() {
    assertEquals("foreignKey", McpToolRouter.mapColumnTypeStatic("19"));
    assertEquals("foreignKey", McpToolRouter.mapColumnTypeStatic("18"));
    assertEquals("foreignKey", McpToolRouter.mapColumnTypeStatic("30"));
    assertEquals("foreignKey", McpToolRouter.mapColumnTypeStatic("95E2A8B50A254B2AAE6774B8C2F28120"));
  }

  /** Tests that mapColumnTypeStatic returns string for null input. */
  @Test
  public void testMapColumnTypeNullRef() {
    assertEquals("string", McpToolRouter.mapColumnTypeStatic(null));
  }

  /** Tests that mapColumnTypeStatic returns string for unknown reference ID. */
  @Test
  public void testMapColumnTypeUnknownRef() {
    assertEquals("string", McpToolRouter.mapColumnTypeStatic("9999"));
    assertEquals("string", McpToolRouter.mapColumnTypeStatic("unknown"));
  }

  // ── mapSelectorTypeStatic ─────────────────────────────────────────────

  /** Tests that mapSelectorTypeStatic maps TableDir reference correctly. */
  @Test
  public void testMapSelectorTypeTableDir() {
    assertEquals("TableDir", McpToolRouter.mapSelectorTypeStatic("19"));
  }

  /** Tests that mapSelectorTypeStatic maps Table reference correctly. */
  @Test
  public void testMapSelectorTypeTable() {
    assertEquals("Table", McpToolRouter.mapSelectorTypeStatic("18"));
  }

  /** Tests that mapSelectorTypeStatic maps Search reference correctly. */
  @Test
  public void testMapSelectorTypeSearch() {
    assertEquals("Search", McpToolRouter.mapSelectorTypeStatic("30"));
  }

  /** Tests that mapSelectorTypeStatic maps OBUISEL reference correctly. */
  @Test
  public void testMapSelectorTypeObuisel() {
    assertEquals("OBUISEL", McpToolRouter.mapSelectorTypeStatic("95E2A8B50A254B2AAE6774B8C2F28120"));
  }

  /** Tests that mapSelectorTypeStatic returns null for null input. */
  @Test
  public void testMapSelectorTypeNullRef() {
    assertNull(McpToolRouter.mapSelectorTypeStatic(null));
  }

  /** Tests that mapSelectorTypeStatic returns null for non-selector reference IDs. */
  @Test
  public void testMapSelectorTypeNonSelectorRefs() {
    assertNull(McpToolRouter.mapSelectorTypeStatic("10"));
    assertNull(McpToolRouter.mapSelectorTypeStatic("11"));
    assertNull(McpToolRouter.mapSelectorTypeStatic("20"));
    assertNull(McpToolRouter.mapSelectorTypeStatic("9999"));
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

  /** Tests that resolveSpecName returns the spec argument for neo_schema. */
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
    assertFalse(ToolRegistry.isCrudTool("neo_other"));
    assertFalse(ToolRegistry.isCrudTool(""));
  }

  /** Tests that neo_batch is treated as a CRUD tool so spec resolution is skipped. */
  @Test
  public void testNeoBatchIsCrudTool() {
    assertTrue(ToolRegistry.isCrudTool(TOOL_NEO_BATCH));
  }

  /**
   * Tests that resolveSpecName returns null for neo_batch even with arguments --
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
   * Tests that the router rejects neo_batch with missing/empty operations as an MCP
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

  // ── McpAuthorizationService — neo_widget (ETP-4284 / G4) ──────────────

  /** Tests that neo_widget requires read scope at execution time. */
  @Test
  public void testAuthorizeToolCallAllowsWidgetWithReadScope() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_WIDGET, Set.of("neo:read"));
  }

  /** Tests that the wildcard scope allows neo_widget. */
  @Test
  public void testAuthorizeToolCallAllowsWidgetWithWildcardScope() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_WIDGET, Set.of("neo:*"));
  }

  /** Tests that neo_widget is rejected without read scope. */
  @Test(expected = OBSecurityException.class)
  public void testAuthorizeToolCallRejectsWidgetWithoutReadScope() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_WIDGET, Set.of("neo:write"));
  }

  /** Tests that neo_widget is rejected with process scope only. */
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

  // ── route() — null args for each tool type ────────────────────────────

  /**
   * Tests that route() with neo_get and null arguments returns an error about
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
   * Tests that route() with neo_create and null arguments returns an error about
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
   * Tests that route() with neo_delete and null arguments returns an error about
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
   * Tests that route() with neo_selectors and null arguments returns an error about
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
   * Tests that route() with neo_defaults and null arguments returns an error about
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
   * Tests that route() with neo_schema and null arguments returns an error about
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

  // ── route() — neo_widget (ETP-4284 / G4) ──────────────────────────────

  /**
   * Tests that route() with neo_widget and a valid widget but no DAL passes
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
      assertFalse("neo_widget error should not be about scope",
          errorText.contains("requires scope"));
    }
  }

  /** Tests that route() rejects neo_widget without read scope. */
  @Test
  public void testRouteWidgetRejectedWithoutReadScope() throws Exception {
    McpToolRouter router = new McpToolRouter();

    JSONObject args = new JSONObject();
    args.put("widget", "kpis");

    JSONObject result = router.route(McpConstants.TOOL_NEO_WIDGET, args, Set.of("neo:write"));
    assertTrue(result.optBoolean(FIELD_IS_ERROR, false));
    String errorText = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString("text");
    assertTrue("neo_widget without read scope must fail on scope",
        errorText.contains("requires scope"));
  }

  /**
   * Tests that route() with neo_widget and null arguments returns an error
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

  // ── McpToolRouterSupport.mapColumnType exhaustive test ────────────────

  /** Tests that all known reference IDs are mapped exhaustively via support class. */
  @Test
  public void testMapColumnTypeExhaustiveSwitch() {
    String[] stringRefs = {"10", "14", "34"};
    for (String ref : stringRefs) {
      assertEquals("string for ref " + ref, "string",
          McpToolRouterSupport.mapColumnType(ref));
    }

    String[] numberRefs = {"11", "22", "29", "12", "800008", "800019"};
    for (String ref : numberRefs) {
      assertEquals("number for ref " + ref, "number",
          McpToolRouterSupport.mapColumnType(ref));
    }

    assertEquals("boolean", McpToolRouterSupport.mapColumnType("20"));
    assertEquals("date", McpToolRouterSupport.mapColumnType("15"));
    assertEquals("datetime", McpToolRouterSupport.mapColumnType("16"));
    assertEquals("time", McpToolRouterSupport.mapColumnType("24"));
    assertEquals("button", McpToolRouterSupport.mapColumnType("28"));
    assertEquals("list", McpToolRouterSupport.mapColumnType("17"));
    assertEquals("id", McpToolRouterSupport.mapColumnType("13"));

    assertEquals("foreignKey", McpToolRouterSupport.mapColumnType("19"));
    assertEquals("foreignKey", McpToolRouterSupport.mapColumnType("18"));
    assertEquals("foreignKey", McpToolRouterSupport.mapColumnType("30"));

    assertEquals("string", McpToolRouterSupport.mapColumnType("99999"));
    assertEquals("string", McpToolRouterSupport.mapColumnType(null));
  }

  // ── McpToolRouterSupport.mapSelectorType exhaustive test ──────────────

  /** Tests that all selector type mappings are correct via support class. */
  @Test
  public void testMapSelectorTypeExhaustiveSwitch() {
    assertEquals("TableDir", McpToolRouterSupport.mapSelectorType("19"));
    assertEquals("Table", McpToolRouterSupport.mapSelectorType("18"));
    assertEquals("Search", McpToolRouterSupport.mapSelectorType("30"));
    assertEquals("OBUISEL", McpToolRouterSupport.mapSelectorType(
        "95E2A8B50A254B2AAE6774B8C2F28120"));

    assertNull(McpToolRouterSupport.mapSelectorType(null));
    assertNull(McpToolRouterSupport.mapSelectorType("10"));
    assertNull(McpToolRouterSupport.mapSelectorType("20"));
    assertNull(McpToolRouterSupport.mapSelectorType("unknown"));
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

  // ── neo_schema isCrudTool ─────────────────────────────────────────────

  /** Tests that neo_schema is classified as a CRUD tool. */
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

}
