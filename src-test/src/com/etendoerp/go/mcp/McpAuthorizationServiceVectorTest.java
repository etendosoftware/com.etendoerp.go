package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBSecurityException;

/**
 * Authorization contract of the MCP tools: the semantic-search read tier, and the etendo:/neo:
 * scope equivalence (ETP-5602).
 *
 * @covers com.etendoerp.go.mcp.McpAuthorizationService
 */
class McpAuthorizationServiceVectorTest {

  @Test
  void vectorSearchRequiresReadScope() {
    assertThrows(OBSecurityException.class,
        () -> McpAuthorizationService.authorizeToolCall(
            McpConstants.TOOL_NEO_VECTOR_SEARCH, Set.of("neo:write")));
  }

  @Test
  void vectorSearchAcceptsReadScope() {
    McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_VECTOR_SEARCH, Set.of("neo:read"));
  }

  @Test
  void vectorSearchAcceptsTheEtendoReadScope() {
    McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_VECTOR_SEARCH, Set.of("etendo:read"));
  }

  @Test
  void writeToolsRequireWriteUnderEitherPrefix() {
    assertThrows(OBSecurityException.class,
        () -> McpAuthorizationService.authorizeToolCall("etendo_create", Set.of("etendo:read")));
    assertThrows(OBSecurityException.class,
        () -> McpAuthorizationService.authorizeToolCall("etendo_create", Set.of("neo:read")));
    McpAuthorizationService.authorizeToolCall("etendo_create", Set.of("neo:write"));
    McpAuthorizationService.authorizeToolCall("etendo_create", Set.of("etendo:*"));
    McpAuthorizationService.authorizeToolCall("generate_tax_report", Set.of("neo:*"));
  }

  @Test
  void theRefusalNamesTheEtendoScope() {
    OBSecurityException e = assertThrows(OBSecurityException.class,
        () -> McpAuthorizationService.authorizeToolCall("etendo_create", Set.of("neo:read")));
    org.junit.jupiter.api.Assertions.assertTrue(
        e.getMessage().contains("'etendo:write'"), e.getMessage());
  }
}
