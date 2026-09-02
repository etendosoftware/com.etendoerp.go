/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
 * the License.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression guard for MCP quotation creates missing the tab-specific document subtype. */
@DisplayName("MCP quotation create resolves the tab document type")
class McpQuotationCreateDocTypeCallSiteTest {

  @Test
  @DisplayName("neo_create invokes the shared document-type resolver before persistence")
  void createPathReappliesTabDocumentType() throws IOException {
    String source = new String(Files.readAllBytes(routerSource()), StandardCharsets.UTF_8);
    int createStart = source.indexOf("private JSONObject handleCreate(");
    int updateStart = source.indexOf("private JSONObject handleUpdate(", createStart);

    assertTrue(createStart >= 0, "MCP create handler was not found");
    assertTrue(updateStart > createStart, "MCP update handler boundary was not found");

    String createBody = source.substring(createStart, updateStart);
    assertTrue(createBody.contains("DocTypeResolver.reapplyDocTypeFromTabFilter"),
        "neo_create must resolve the active tab subtype so sales quotations persist with "
            + "transactionDocument=documentType instead of the generic Standard Order default");
  }

  private Path routerSource() {
    Path modulePath = Paths.get("modules/com.etendoerp.go/src/com/etendoerp/go/mcp/McpToolRouter.java");
    if (Files.exists(modulePath)) {
      return modulePath;
    }
    return Paths.get("src/com/etendoerp/go/mcp/McpToolRouter.java");
  }
}
