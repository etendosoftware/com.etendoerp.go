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

  @Test
  void actionBatchRequiresTheSameWriteScopeAsEtendoAction() {
    for (String tool : new String[] {"etendo_action", McpConstants.TOOL_NEO_ACTION_BATCH}) {
      assertThrows(OBSecurityException.class,
          () -> McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:read")), tool);
      assertThrows(OBSecurityException.class,
          () -> McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:process")), tool);
      McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:write"));
    }
  }

  /** ETP-5715: getting the stored PDF of a document is a read, so a read-only session may do it. */
  @Test
  void documentPdfToolIsReadTier() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_GET_DOCUMENT_PDF,
        Set.of("etendo:read"));
    assertThrows(OBSecurityException.class, () -> McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_GET_DOCUMENT_PDF, Set.of("etendo:process")));
  }

  /** ETP-5715: sending a document by email delivers a real message, so it needs the write scope. */
  @Test
  void sendDocumentEmailToolIsWriteTier() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL,
        Set.of("etendo:write"));
    assertThrows(OBSecurityException.class, () -> McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL, Set.of("etendo:read")));
    assertThrows(OBSecurityException.class, () -> McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL, Set.of("etendo:process")));
  }

  /**
   * ETP-5715: staging a PDF and reading it with a paid external model are steps of the write flow
   * that creates a document from it, so both need the write scope.
   */
  @Test
  void documentExtractionToolsAreWriteTier() {
    for (String tool : new String[] {McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD,
        McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT}) {
      McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:write"));
      assertThrows(OBSecurityException.class,
          () -> McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:read")), tool);
      assertThrows(OBSecurityException.class,
          () -> McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:process")), tool);
    }
  }

  /** ETP-5715: a read-only session may list a document's attachments; changing them needs write. */
  @Test
  void attachmentToolsAreSplitBetweenReadAndWrite() {
    McpAuthorizationService.authorizeToolCall(McpConstants.TOOL_NEO_LIST_ATTACHMENTS,
        Set.of("etendo:read"));
    for (String tool : new String[] {McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD,
        McpConstants.TOOL_NEO_DELETE_ATTACHMENT}) {
      assertThrows(OBSecurityException.class,
          () -> McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:read")), tool);
      assertThrows(OBSecurityException.class,
          () -> McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:process")), tool);
      McpAuthorizationService.authorizeToolCall(tool, Set.of("etendo:write"));
    }
  }
}
