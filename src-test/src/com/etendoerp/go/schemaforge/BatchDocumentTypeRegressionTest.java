/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
 * the License.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for document-type defaults through MCP {@code neo_batch}.
 *
 * <p>The batch service deliberately delegates creates to the common CRUD pipeline. This guard
 * prevents a future batch-specific shortcut from bypassing the tab-aware resolver and restoring
 * the invisible quotation/order bug for another transactional document family.</p>
 */
@DisplayName("Batch document creates preserve the tab document type resolver")
class BatchDocumentTypeRegressionTest {

  @Test
  @DisplayName("neo_batch creates enter the resolver-backed CRUD path")
  void batchCreateUsesCommonDocTypePath() throws IOException {
    String batchSource = readSource("src/com/etendoerp/go/schemaforge/BatchService.java");
    String crudSource = readSource("src/com/etendoerp/go/schemaforge/NeoCrudHandler.java");

    int createStart = batchSource.indexOf("private NeoResponse createRecord(");
    int createEnd = batchSource.indexOf("\n  /**", createStart);
    assertTrue(createStart >= 0 && createEnd > createStart,
        "Batch create method was not found");

    String createBody = batchSource.substring(createStart, createEnd);
    assertTrue(createBody.contains("return crudHandler.handleDefault(ctx)"),
        "neo_batch create must delegate to NeoCrudHandler#handleDefault");

    int cascadeStart = crudSource.indexOf("private void executePostCalloutCascade(");
    int cascadeEnd = crudSource.indexOf("\n  /**", cascadeStart);
    assertTrue(cascadeStart >= 0 && cascadeEnd > cascadeStart,
        "CRUD post-create pipeline was not found");

    String cascadeBody = crudSource.substring(cascadeStart, cascadeEnd);
    assertTrue(cascadeBody.contains("DocTypeResolver.reapplyDocTypeFromTabFilter"),
        "neo_batch must retain the shared tab-aware document-type resolver before persistence");
  }

  private String readSource(String relativePath) throws IOException {
    Path modulePath = Paths.get(relativePath);
    if (!Files.exists(modulePath)) {
      modulePath = Paths.get("modules/com.etendoerp.go").resolve(relativePath);
    }
    assertTrue(Files.exists(modulePath), "Source not found: " + relativePath);
    return new String(Files.readAllBytes(modulePath), StandardCharsets.UTF_8);
  }
}
