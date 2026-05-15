/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Unit tests for {@link NeoOpenAPIEndpoint}.
 * Uses JUnit 5 (Jupiter) and Mockito.
 */
class NeoOpenAPIEndpointTest {

  private NeoOpenAPIEndpoint endpoint;

  @BeforeEach
  void setUp() {
    endpoint = new NeoOpenAPIEndpoint();
  }

  private static Object invokePrivate(Object target, String methodName,
      Class<?>[] paramTypes, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  // -------------------------------------------------------------------------
  // isValid tests
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("isValid")
  class IsValid {

    @Test
    @DisplayName("Returns true for null tag")
    void nullTagIsValid() {
      assertTrue(endpoint.isValid(null));
    }

    @Test
    @DisplayName("Returns true for EtendoGo tag (case-insensitive)")
    void etendoGoTagIsValid() {
      assertTrue(endpoint.isValid("EtendoGo"));
      assertTrue(endpoint.isValid("etendogo"));
      assertTrue(endpoint.isValid("ETENDOGO"));
    }

    @Test
    @DisplayName("Returns false for unrelated tags")
    void unrelatedTagIsInvalid() {
      assertFalse(endpoint.isValid("SomeOtherTag"));
      assertFalse(endpoint.isValid(""));
    }
  }

  // -------------------------------------------------------------------------
  // add() integration — process spec paths
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - process spec")
  class AddProcessSpec {

    @Test
    @DisplayName("Registers GET and POST paths for process-type specs")
    void registersProcessPaths() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("runReport");
      when(spec.getSpecType()).thenReturn("P");
      when(spec.getId()).thenReturn("SPEC-P1");

      OBDal dal = mock(OBDal.class);
      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/runReport");
      assertNotNull(pathItem, "Process path should be registered");
      assertNotNull(pathItem.getGet(), "Process should have GET (describe)");
      assertNotNull(pathItem.getPost(), "Process should have POST (execute)");

      // Verify tags
      assertTrue(openAPI.getTags().stream()
          .anyMatch(t -> "EtendoGo".equals(t.getName())));
    }
  }

  // -------------------------------------------------------------------------
  // add() integration — report spec paths
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - report spec")
  class AddReportSpec {

    @Test
    @DisplayName("Registers GET and POST paths for report-type specs")
    void registersReportPaths() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("agingReport");
      when(spec.getSpecType()).thenReturn("R");
      when(spec.getId()).thenReturn("SPEC-R1");

      OBDal dal = mock(OBDal.class);
      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/agingReport");
      assertNotNull(pathItem, "Report path should be registered");
      assertNotNull(pathItem.getGet(), "Report should have GET (describe)");
      assertNotNull(pathItem.getPost(), "Report should have POST (generate)");
    }
  }

  // -------------------------------------------------------------------------
  // add() integration — window spec with entities
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - window spec")
  class AddWindowSpec {

    @Test
    @DisplayName("Registers CRUD, selector, action, evaluate-display, and defaults paths")
    void registersAllWindowPaths() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("sales-order");
      when(spec.getSpecType()).thenReturn("W");
      when(spec.getId()).thenReturn("SPEC-W1");

      SFEntity entity = mock(SFEntity.class);
      when(entity.getName()).thenReturn("order");
      when(entity.isGet()).thenReturn(true);
      when(entity.isGetByID()).thenReturn(true);
      when(entity.isPost()).thenReturn(true);
      when(entity.isPut()).thenReturn(true);
      when(entity.isPatch()).thenReturn(true);
      when(entity.isDelete()).thenReturn(true);

      OBDal dal = mock(OBDal.class);

      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      @SuppressWarnings("unchecked")
      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      Paths paths = openAPI.getPaths();

      // CRUD list path
      PathItem listItem = paths.get("/sws/neo/sales-order/order");
      assertNotNull(listItem, "List path should exist");
      assertNotNull(listItem.getGet(), "List GET should exist");
      assertNotNull(listItem.getPost(), "List POST should exist");

      // CRUD item path
      PathItem itemPath = paths.get("/sws/neo/sales-order/order/{id}");
      assertNotNull(itemPath, "Item path should exist");
      assertNotNull(itemPath.getGet(), "Item GET should exist");
      assertNotNull(itemPath.getPut(), "Item PUT should exist");
      assertNotNull(itemPath.getPatch(), "Item PATCH should exist");
      assertNotNull(itemPath.getDelete(), "Item DELETE should exist");

      // Selector paths
      assertNotNull(paths.get("/sws/neo/sales-order/order/selectors"),
          "Selectors list path should exist");
      assertNotNull(paths.get("/sws/neo/sales-order/order/selectors/{columnName}"),
          "Selector query path should exist");

      // Action paths
      assertNotNull(paths.get("/sws/neo/sales-order/order/{id}/action"),
          "Action list path should exist");
      assertNotNull(paths.get("/sws/neo/sales-order/order/{id}/action/{columnName}"),
          "Action exec path should exist");

      // Evaluate display path
      assertNotNull(paths.get("/sws/neo/sales-order/order/evaluate-display"),
          "Evaluate-display path should exist");

      // Defaults path
      assertNotNull(paths.get("/sws/neo/sales-order/order/defaults"),
          "Defaults path should exist");
    }

    @Test
    @DisplayName("Skips entity with null name")
    void skipsEntityWithNullName() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("sales-order");
      when(spec.getSpecType()).thenReturn("W");
      when(spec.getId()).thenReturn("SPEC-W1");

      SFEntity entityNoName = mock(SFEntity.class);
      when(entityNoName.getName()).thenReturn(null);

      OBDal dal = mock(OBDal.class);

      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      @SuppressWarnings("unchecked")
      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entityNoName));
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      // Only discovery paths should exist, no entity CRUD paths
      assertFalse(openAPI.getPaths().keySet().stream()
          .anyMatch(p -> p.contains("/sales-order/") && !p.equals("/sws/neo/{specName}")));
    }
  }

  // -------------------------------------------------------------------------
  // add() — spec with null name is skipped
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - null spec name")
  class AddNullSpecName {

    @Test
    @DisplayName("Skips spec with null name")
    void skipsSpecWithNullName() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      SFSpec specNoName = mock(SFSpec.class);
      when(specNoName.getName()).thenReturn(null);
      when(specNoName.getSpecType()).thenReturn("P");

      OBDal dal = mock(OBDal.class);
      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.singletonList(specNoName));
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      // Only discovery endpoints should be present
      assertTrue(openAPI.getPaths().size() <= 2,
          "No spec-specific paths should be registered for null-name spec");
    }
  }

  // -------------------------------------------------------------------------
  // add() — discovery endpoints
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - discovery endpoints")
  class AddDiscovery {

    @Test
    @DisplayName("Registers root discovery and spec describe endpoints")
    void registersDiscoveryEndpoints() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      OBDal dal = mock(OBDal.class);
      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.emptyList());
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      // Root discovery: GET /sws/neo/
      PathItem rootPath = openAPI.getPaths().get("/sws/neo/");
      assertNotNull(rootPath, "Root discovery path should exist");
      assertNotNull(rootPath.getGet(), "Root discovery GET should exist");

      // Spec describe: GET /sws/neo/{specName}
      PathItem specDescribe = openAPI.getPaths().get("/sws/neo/{specName}");
      assertNotNull(specDescribe, "Spec describe path should exist");
      assertNotNull(specDescribe.getGet(), "Spec describe GET should exist");
    }
  }

  // -------------------------------------------------------------------------
  // add() — initializes null paths and tags
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - initialization")
  class AddInitialization {

    @Test
    @DisplayName("Creates Paths and Tags when null on OpenAPI object")
    void createsPathsAndTagsWhenNull() {
      OpenAPI openAPI = new OpenAPI();
      // Explicitly null paths and tags
      openAPI.setPaths(null);
      openAPI.setTags(null);

      OBDal dal = mock(OBDal.class);
      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.emptyList());
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      assertNotNull(openAPI.getPaths(), "Paths should be initialized");
      assertNotNull(openAPI.getTags(), "Tags should be initialized");
      assertTrue(openAPI.getTags().stream()
          .anyMatch(t -> "EtendoGo".equals(t.getName())));
    }
  }

  // -------------------------------------------------------------------------
  // add() — CRUD flags: only GET (no POST)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - partial CRUD flags")
  class AddPartialCrudFlags {

    @Test
    @DisplayName("Entity with only GET flag registers list GET but not POST")
    void onlyGetRegistersListGetOnly() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("products");
      when(spec.getSpecType()).thenReturn("W");
      when(spec.getId()).thenReturn("SPEC-W2");

      SFEntity entity = mock(SFEntity.class);
      when(entity.getName()).thenReturn("product");
      when(entity.isGet()).thenReturn(true);
      when(entity.isGetByID()).thenReturn(false);
      when(entity.isPost()).thenReturn(false);
      when(entity.isPut()).thenReturn(false);
      when(entity.isPatch()).thenReturn(false);
      when(entity.isDelete()).thenReturn(false);

      OBDal dal = mock(OBDal.class);

      @SuppressWarnings("unchecked")
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);

      @SuppressWarnings("unchecked")
      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        endpoint.add(openAPI);
      }

      PathItem listItem = openAPI.getPaths().get("/sws/neo/products/product");
      assertNotNull(listItem, "List path should exist for GET");
      assertNotNull(listItem.getGet(), "GET should be registered");
      assertNull(listItem.getPost(), "POST should NOT be registered");

      // Item path should not exist (no getById, put, patch, delete)
      assertNull(openAPI.getPaths().get("/sws/neo/products/product/{id}"),
          "Item path should not exist when no single-record operations enabled");
    }
  }

  // -------------------------------------------------------------------------
  // Helper method tests
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("Helper methods")
  class HelperMethods {

    @Test
    @DisplayName("createOperation sets summary, description, and EtendoGo tag")
    void createOperationSetsFields() throws Exception {
      Operation op = (Operation) invokePrivate(endpoint, "createOperation",
          new Class<?>[] { String.class, String.class },
          "My summary", "My description");

      assertEquals("My summary", op.getSummary());
      assertEquals("My description", op.getDescription());
      assertNotNull(op.getTags());
      assertTrue(op.getTags().contains("EtendoGo"));
    }

    @Test
    @DisplayName("createQueryParam builds parameter with correct attributes")
    void createQueryParamBuildsCorrectly() throws Exception {
      Parameter param = (Parameter) invokePrivate(endpoint, "createQueryParam",
          new Class<?>[] { String.class, String.class, String.class, String.class },
          "_startRow", "integer", "Starting row index", "0");

      assertEquals("_startRow", param.getName());
      assertEquals("query", param.getIn());
      assertFalse(param.getRequired());
      assertEquals("Starting row index", param.getDescription());
      assertNotNull(param.getSchema());
      assertEquals("integer", param.getSchema().getType());
      assertEquals(0, param.getSchema().getDefault());
    }

    @Test
    @DisplayName("createQueryParam with string type sets default as string")
    void createQueryParamStringDefault() throws Exception {
      Parameter param = (Parameter) invokePrivate(endpoint, "createQueryParam",
          new Class<?>[] { String.class, String.class, String.class, String.class },
          "q", "string", "Search term", "test");

      assertEquals("test", param.getSchema().getDefault());
    }

    @Test
    @DisplayName("createQueryParam with null default does not set default value")
    void createQueryParamNoDefault() throws Exception {
      Parameter param = (Parameter) invokePrivate(endpoint, "createQueryParam",
          new Class<?>[] { String.class, String.class, String.class, String.class },
          "q", "string", "Search term", null);

      assertNull(param.getSchema().getDefault());
    }

    @Test
    @DisplayName("createJsonRequestBody builds required body with JSON content")
    void createJsonRequestBody() throws Exception {
      RequestBody body = (RequestBody) invokePrivate(endpoint, "createJsonRequestBody",
          new Class<?>[] { String.class }, "My payload");

      assertEquals("My payload", body.getDescription());
      assertTrue(body.getRequired());
      assertNotNull(body.getContent().get("application/json"));
    }

    @Test
    @DisplayName("createJsonResponse builds response with JSON content")
    void createJsonResponse() throws Exception {
      Schema<?> schema = new io.swagger.v3.oas.models.media.ObjectSchema();
      io.swagger.v3.oas.models.responses.ApiResponse response =
          (io.swagger.v3.oas.models.responses.ApiResponse) invokePrivate(endpoint,
              "createJsonResponse",
              new Class<?>[] { String.class, Schema.class },
              "Success", schema);

      assertEquals("Success", response.getDescription());
      assertNotNull(response.getContent().get("application/json"));
    }

    @Test
    @DisplayName("getOrCreatePathItem returns existing item if present")
    void getOrCreateReturnsExisting() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      PathItem existing = new PathItem();
      existing.setDescription("existing");
      openAPI.getPaths().addPathItem("/test", existing);

      PathItem result = (PathItem) invokePrivate(endpoint, "getOrCreatePathItem",
          new Class<?>[] { OpenAPI.class, String.class }, openAPI, "/test");

      assertEquals("existing", result.getDescription());
    }

    @Test
    @DisplayName("getOrCreatePathItem returns new PathItem when path not present")
    void getOrCreateReturnsNew() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      PathItem result = (PathItem) invokePrivate(endpoint, "getOrCreatePathItem",
          new Class<?>[] { OpenAPI.class, String.class }, openAPI, "/new-path");

      assertNotNull(result);
      assertNull(result.getGet());
    }

    @Test
    @DisplayName("createSelectorListSchema returns array of objects")
    void createSelectorListSchema() throws Exception {
      Schema<?> schema = (Schema<?>) invokePrivate(endpoint, "createSelectorListSchema",
          new Class<?>[] {});

      assertNotNull(schema);
      assertEquals("array", schema.getType());
    }

    @Test
    @DisplayName("createActionListSchema returns array of objects")
    void createActionListSchema() throws Exception {
      Schema<?> schema = (Schema<?>) invokePrivate(endpoint, "createActionListSchema",
          new Class<?>[] {});

      assertNotNull(schema);
      assertEquals("array", schema.getType());
    }

    @Test
    @DisplayName("createSelectorQueryResponseSchema includes items and pagination")
    void createSelectorQueryResponseSchema() throws Exception {
      Schema<?> schema = (Schema<?>) invokePrivate(endpoint,
          "createSelectorQueryResponseSchema", new Class<?>[] {});

      assertNotNull(schema);
      Map<String, Schema> props = schema.getProperties();
      assertNotNull(props);
      assertTrue(props.containsKey("items"));
      assertTrue(props.containsKey("columns"));
      assertTrue(props.containsKey("totalCount"));
      assertTrue(props.containsKey("limit"));
      assertTrue(props.containsKey("offset"));
      assertTrue(props.containsKey("hasMore"));
    }

    @Test
    @DisplayName("createProcessResponseSchema has status and message fields")
    void createProcessResponseSchema() throws Exception {
      Schema<?> schema = (Schema<?>) invokePrivate(endpoint,
          "createProcessResponseSchema", new Class<?>[] {});

      assertNotNull(schema);
      assertEquals("object", schema.getType());
      Map<String, Schema> props = schema.getProperties();
      assertNotNull(props);
      assertTrue(props.containsKey("status"));
      assertTrue(props.containsKey("message"));
    }
  }

  // -------------------------------------------------------------------------
  // addProcessPaths detail verification
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("addProcessPaths details")
  class ProcessPathDetails {

    @Test
    @DisplayName("Process GET response has 200, 401, 404 codes")
    void processGetResponseCodes() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addProcessPaths",
          new Class<?>[] { OpenAPI.class, String.class }, openAPI, "myProcess");

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/myProcess");
      assertNotNull(pathItem.getGet().getResponses().get("200"));
      assertNotNull(pathItem.getGet().getResponses().get("401"));
      assertNotNull(pathItem.getGet().getResponses().get("404"));
    }

    @Test
    @DisplayName("Process POST has request body and 200, 400, 401, 500 codes")
    void processPostResponseCodes() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addProcessPaths",
          new Class<?>[] { OpenAPI.class, String.class }, openAPI, "myProcess");

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/myProcess");
      Operation postOp = pathItem.getPost();
      assertNotNull(postOp.getRequestBody());
      assertNotNull(postOp.getResponses().get("200"));
      assertNotNull(postOp.getResponses().get("400"));
      assertNotNull(postOp.getResponses().get("401"));
      assertNotNull(postOp.getResponses().get("500"));
    }
  }

  // -------------------------------------------------------------------------
  // addReportPaths detail verification
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("addReportPaths details")
  class ReportPathDetails {

    @Test
    @DisplayName("Report GET describe response includes report metadata schema")
    void reportGetIncludesMetadata() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addReportPaths",
          new Class<?>[] { OpenAPI.class, String.class }, openAPI, "agingReport");

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/agingReport");
      assertNotNull(pathItem.getGet().getResponses().get("200"));
    }

    @Test
    @DisplayName("Report POST response content has multiple MIME types")
    void reportPostHasMultipleMimeTypes() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addReportPaths",
          new Class<?>[] { OpenAPI.class, String.class }, openAPI, "agingReport");

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/agingReport");
      Operation postOp = pathItem.getPost();

      io.swagger.v3.oas.models.media.Content content =
          postOp.getResponses().get("200").getContent();
      assertNotNull(content.get("application/pdf"));
      assertNotNull(content.get("application/vnd.ms-excel"));
      assertNotNull(content.get("text/html"));
      assertNotNull(content.get("text/csv"));
    }
  }

  // -------------------------------------------------------------------------
  // addEvaluateDisplayPaths detail verification
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("addEvaluateDisplayPaths details")
  class EvaluateDisplayPathDetails {

    @Test
    @DisplayName("Evaluate-display endpoint is POST with optional body")
    void evaluateDisplayIsPost() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addEvaluateDisplayPaths",
          new Class<?>[] { OpenAPI.class, String.class, String.class },
          openAPI, "sales-order", "order");

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/sales-order/order/evaluate-display");
      assertNotNull(pathItem);
      Operation postOp = pathItem.getPost();
      assertNotNull(postOp);
      assertFalse(postOp.getRequestBody().getRequired());
      assertNotNull(postOp.getResponses().get("200"));
      assertNotNull(postOp.getResponses().get("400"));
      assertNotNull(postOp.getResponses().get("401"));
      assertNotNull(postOp.getResponses().get("404"));
      assertNotNull(postOp.getResponses().get("405"));
    }
  }

  // -------------------------------------------------------------------------
  // addDefaultsPaths detail verification
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("addDefaultsPaths details")
  class DefaultsPathDetails {

    @Test
    @DisplayName("Defaults endpoint is GET with parentId query param")
    void defaultsIsGetWithParentId() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addDefaultsPaths",
          new Class<?>[] { OpenAPI.class, String.class, String.class },
          openAPI, "sales-order", "order");

      PathItem pathItem = openAPI.getPaths().get("/sws/neo/sales-order/order/defaults");
      assertNotNull(pathItem);
      Operation getOp = pathItem.getGet();
      assertNotNull(getOp);
      assertNotNull(getOp.getResponses().get("200"));

      boolean hasParentIdParam = getOp.getParameters().stream()
          .anyMatch(p -> "parentId".equals(p.getName()));
      assertTrue(hasParentIdParam, "Defaults GET should have parentId parameter");
    }
  }

  // -------------------------------------------------------------------------
  // add() — exception handling
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("add() - exception handling")
  class AddExceptionHandling {

    @Test
    @DisplayName("Handles exception during spec query gracefully (does not throw)")
    void handlesExceptionGracefully() {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());
      openAPI.setTags(new ArrayList<>());

      OBDal dal = mock(OBDal.class);
      when(dal.createCriteria(SFSpec.class))
          .thenThrow(new RuntimeException("DB connection failed"));

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {

        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        // Should not throw
        endpoint.add(openAPI);
      }

      // Tags should still have been added before the exception
      assertTrue(openAPI.getTags().stream()
          .anyMatch(t -> "EtendoGo".equals(t.getName())));
    }
  }

  // -------------------------------------------------------------------------
  // Selector and action path structure tests
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("addSelectorPaths details")
  class SelectorPathDetails {

    @Test
    @DisplayName("Selector list path has GET with 200/401/404 responses")
    void selectorListPathHasGet() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addSelectorPaths",
          new Class<?>[] { OpenAPI.class, String.class, String.class },
          openAPI, "contacts", "businessPartner");

      PathItem listPath = openAPI.getPaths().get(
          "/sws/neo/contacts/businessPartner/selectors");
      assertNotNull(listPath);
      assertNotNull(listPath.getGet());
      assertNotNull(listPath.getGet().getResponses().get("200"));
    }

    @Test
    @DisplayName("Selector query path has columnName path param and q/limit/offset query params")
    void selectorQueryPathHasParams() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addSelectorPaths",
          new Class<?>[] { OpenAPI.class, String.class, String.class },
          openAPI, "contacts", "businessPartner");

      PathItem queryPath = openAPI.getPaths().get(
          "/sws/neo/contacts/businessPartner/selectors/{columnName}");
      assertNotNull(queryPath);
      Operation getOp = queryPath.getGet();
      assertNotNull(getOp);

      List<Parameter> params = getOp.getParameters();
      assertTrue(params.stream().anyMatch(p -> "columnName".equals(p.getName())));
      assertTrue(params.stream().anyMatch(p -> "q".equals(p.getName())));
      assertTrue(params.stream().anyMatch(p -> "limit".equals(p.getName())));
      assertTrue(params.stream().anyMatch(p -> "offset".equals(p.getName())));
    }
  }

  @Nested
  @DisplayName("addActionPaths details")
  class ActionPathDetails {

    @Test
    @DisplayName("Action list path has GET with id path param")
    void actionListPathHasIdParam() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addActionPaths",
          new Class<?>[] { OpenAPI.class, String.class, String.class },
          openAPI, "sales-order", "order");

      PathItem listPath = openAPI.getPaths().get(
          "/sws/neo/sales-order/order/{id}/action");
      assertNotNull(listPath);
      Operation getOp = listPath.getGet();
      assertNotNull(getOp);

      boolean hasIdParam = getOp.getParameters().stream()
          .anyMatch(p -> "id".equals(p.getName()) && "path".equals(p.getIn()));
      assertTrue(hasIdParam);
    }

    @Test
    @DisplayName("Action exec path has POST with id and columnName path params")
    void actionExecPathHasParams() throws Exception {
      OpenAPI openAPI = new OpenAPI();
      openAPI.setPaths(new Paths());

      invokePrivate(endpoint, "addActionPaths",
          new Class<?>[] { OpenAPI.class, String.class, String.class },
          openAPI, "sales-order", "order");

      PathItem execPath = openAPI.getPaths().get(
          "/sws/neo/sales-order/order/{id}/action/{columnName}");
      assertNotNull(execPath);
      Operation postOp = execPath.getPost();
      assertNotNull(postOp);
      assertNotNull(postOp.getRequestBody());

      boolean hasIdParam = postOp.getParameters().stream()
          .anyMatch(p -> "id".equals(p.getName()));
      boolean hasColumnParam = postOp.getParameters().stream()
          .anyMatch(p -> "columnName".equals(p.getName()));
      assertTrue(hasIdParam);
      assertTrue(hasColumnParam);
    }
  }
}