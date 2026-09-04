package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBSecurityException;

/** Authorization contract for the global semantic-search MCP tool. */
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
}
