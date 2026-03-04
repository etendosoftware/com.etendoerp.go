package com.etendoerp.etendogo.contract;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import com.etendoerp.etendogo.rest.EndpointHandler;
import com.etendoerp.etendogo.rest.HandlerRegistry;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

public class ContractCoverageTest {

  private static OpenAPI openAPI;

  @BeforeClass
  public static void setUp() {
    String specPath = findSpecPath();
    SwaggerParseResult result = new OpenAPIV3Parser().readLocation(specPath, null, null);
    assertNotNull("Could not parse OpenAPI spec at " + specPath, result);
    openAPI = result.getOpenAPI();
    assertNotNull("OpenAPI spec is null", openAPI);
  }

  @Test
  public void allContractOperationsHaveHandlers() {
    Set<String> contractOperationIds = extractOperationIds(openAPI);
    Map<String, EndpointHandler> handlersByOperationId = HandlerRegistry.getInstance()
        .getAllHandlers()
        .stream()
        .collect(Collectors.toMap(EndpointHandler::getOperationId, h -> h));

    Set<String> missing = new HashSet<>();
    for (String operationId : contractOperationIds) {
      if (!handlersByOperationId.containsKey(operationId)) {
        missing.add(operationId);
      }
    }

    if (!missing.isEmpty()) {
      fail("The following contract operations have no registered handler: " + missing);
    }
  }

  @Test
  public void allHandlersMatchContractOperations() {
    Set<String> contractOperationIds = extractOperationIds(openAPI);
    Set<String> orphan = new HashSet<>();

    for (EndpointHandler handler : HandlerRegistry.getInstance().getAllHandlers()) {
      if (!contractOperationIds.contains(handler.getOperationId())) {
        orphan.add(handler.getOperationId());
      }
    }

    if (!orphan.isEmpty()) {
      fail("The following handlers have no matching contract operation: " + orphan);
    }
  }

  private static Set<String> extractOperationIds(OpenAPI api) {
    Set<String> ids = new HashSet<>();
    for (Map.Entry<String, PathItem> entry : api.getPaths().entrySet()) {
      PathItem pathItem = entry.getValue();
      addIfPresent(ids, pathItem.getGet());
      addIfPresent(ids, pathItem.getPost());
      addIfPresent(ids, pathItem.getPut());
      addIfPresent(ids, pathItem.getDelete());
      addIfPresent(ids, pathItem.getPatch());
    }
    return ids;
  }

  private static void addIfPresent(Set<String> ids, Operation op) {
    if (op != null && op.getOperationId() != null) {
      ids.add(op.getOperationId());
    }
  }

  private static String findSpecPath() {
    File moduleDir = new File(System.getProperty("user.dir"));
    File specFile = new File(moduleDir, "modules/com.etendoerp.etendogo/openapi-contracts/index.yaml");
    if (specFile.exists()) {
      return specFile.getAbsolutePath();
    }
    specFile = new File(moduleDir, "openapi-contracts/index.yaml");
    if (specFile.exists()) {
      return specFile.getAbsolutePath();
    }
    // Try relative to classpath
    return new File("openapi-contracts/index.yaml").getAbsolutePath();
  }
}
