package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.openapi.model.OpenAPIEndpoint;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * OpenAPI endpoint that dynamically registers all NEO Headless endpoints
 * based on ETGO_SF_SPEC and ETGO_SF_ENTITY configuration in the database.
 *
 * Discovered via CDI (beans.xml has bean-discovery-mode="all").
 */
public class NeoOpenAPIEndpoint implements OpenAPIEndpoint {

  private static final Logger log = LogManager.getLogger(NeoOpenAPIEndpoint.class);

  private static final String TAG_NAME = "EtendoGo";
  private static final String BASE_PATH = "/sws/neo/";

  @Override
  public boolean isValid(String tag) {
    return tag == null || TAG_NAME.equalsIgnoreCase(tag);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void add(OpenAPI openAPI) {
    try {
      OBContext.setAdminMode();

      // Ensure paths exist
      if (openAPI.getPaths() == null) {
        openAPI.setPaths(new Paths());
      }

      // Add the EtendoGo tag
      if (openAPI.getTags() == null) {
        openAPI.setTags(new ArrayList<>());
      }
      openAPI.getTags().add(new Tag()
          .name(TAG_NAME)
          .description("NEO Headless 2.0 endpoints for SchemaForge specs"));

      // Query all active specs
      OBCriteria<BaseOBObject> specCriteria = OBDal.getInstance().createCriteria("ETGO_SF_Spec");
      specCriteria.add(Restrictions.eq("isActive", true));
      List<BaseOBObject> specs = specCriteria.list();

      for (BaseOBObject spec : specs) {
        String specName = (String) spec.get("name");
        String specType = (String) spec.get("specType");

        if (specName == null) {
          continue;
        }

        if ("P".equals(specType)) {
          addProcessPaths(openAPI, specName);
        } else if ("W".equals(specType)) {
          addWindowPaths(openAPI, spec, specName);
        }
      }

      // Discovery endpoints
      addDiscoveryEndpoints(openAPI);

      log.info("NeoOpenAPIEndpoint: registered NEO endpoints for {} specs", specs.size());

    } catch (Exception e) {
      log.error("Error generating NEO OpenAPI documentation: {}", e.getMessage(), e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Add OpenAPI paths for a process-type spec.
   */
  private void addProcessPaths(OpenAPI openAPI, String specName) {
    String path = BASE_PATH + specName;

    PathItem pathItem = getOrCreatePathItem(openAPI, path);

    // GET - describe process
    Operation describeOp = createOperation(
        "Describe process " + specName,
        "Returns metadata about the process parameters and configuration.");
    describeOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Process metadata",
            createObjectSchema("Process metadata with parameter definitions")))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Spec not found")));
    pathItem.get(describeOp);

    // POST - execute process
    Operation executeOp = createOperation(
        "Execute process " + specName,
        "Executes the process with the provided parameters.");
    executeOp.setRequestBody(createJsonRequestBody("Process parameters"));
    executeOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Process result",
            createProcessResponseSchema()))
        .addApiResponse("400", new ApiResponse().description("Bad request"))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("500", new ApiResponse().description("Internal server error")));
    pathItem.post(executeOp);

    openAPI.getPaths().addPathItem(path, pathItem);
  }

  /**
   * Add OpenAPI paths for a window-type spec, iterating its included entities.
   */
  @SuppressWarnings("unchecked")
  private void addWindowPaths(OpenAPI openAPI, BaseOBObject spec, String specName) {
    String specId = (String) spec.getId();

    // Query active, included entities for this spec
    OBCriteria<BaseOBObject> entityCriteria = OBDal.getInstance().createCriteria("ETGO_SF_Entity");
    entityCriteria.add(Restrictions.eq("eTGOSFSpec.id", specId));
    entityCriteria.add(Restrictions.eq("isActive", true));
    entityCriteria.add(Restrictions.eq("isIncluded", true));
    List<BaseOBObject> entities = entityCriteria.list();

    for (BaseOBObject entity : entities) {
      String entityName = (String) entity.get("name");
      if (entityName == null) {
        continue;
      }

      addCrudPaths(openAPI, specName, entityName, entity);
      addSelectorPaths(openAPI, specName, entityName);
      addActionPaths(openAPI, specName, entityName);
    }
  }

  /**
   * Add CRUD paths (GET list, GET by ID, POST, PUT, PATCH, DELETE) based on entity flags.
   */
  private void addCrudPaths(OpenAPI openAPI, String specName, String entityName,
      BaseOBObject entity) {

    String listPath = BASE_PATH + specName + "/" + entityName;
    String itemPath = BASE_PATH + specName + "/" + entityName + "/{id}";

    boolean isGet = Boolean.TRUE.equals(entity.get("get"));
    boolean isPost = Boolean.TRUE.equals(entity.get("post"));
    boolean isGetById = Boolean.TRUE.equals(entity.get("getByID"));
    boolean isPut = Boolean.TRUE.equals(entity.get("put"));
    boolean isPatch = Boolean.TRUE.equals(entity.get("patch"));
    boolean isDelete = Boolean.TRUE.equals(entity.get("delete"));

    // List path (GET list, POST create)
    if (isGet || isPost) {
      PathItem listItem = getOrCreatePathItem(openAPI, listPath);

      if (isGet) {
        Operation getOp = createOperation(
            "List " + entityName + " records",
            "Retrieves a paginated list of " + entityName + " records.");
        getOp.addParametersItem(createQueryParam("_startRow", "integer",
            "Starting row index", "0"));
        getOp.addParametersItem(createQueryParam("_endRow", "integer",
            "Ending row index", "100"));
        getOp.responses(new ApiResponses()
            .addApiResponse("200", createJsonResponse("List of records",
                createObjectSchema(entityName + " records")))
            .addApiResponse("401", new ApiResponse().description("Unauthorized"))
            .addApiResponse("404", new ApiResponse().description("Spec or entity not found")));
        listItem.get(getOp);
      }

      if (isPost) {
        Operation postOp = createOperation(
            "Create " + entityName + " record",
            "Creates a new " + entityName + " record.");
        postOp.setRequestBody(createJsonRequestBody(entityName + " data"));
        postOp.responses(new ApiResponses()
            .addApiResponse("200", createJsonResponse("Created record",
                createObjectSchema("Created " + entityName + " record")))
            .addApiResponse("400", new ApiResponse().description("Bad request"))
            .addApiResponse("401", new ApiResponse().description("Unauthorized")));
        listItem.post(postOp);
      }

      openAPI.getPaths().addPathItem(listPath, listItem);
    }

    // Item path (GET by ID, PUT, PATCH, DELETE)
    if (isGetById || isPut || isPatch || isDelete) {
      PathItem itemItem = getOrCreatePathItem(openAPI, itemPath);

      Parameter idParam = new Parameter()
          .in("path")
          .name("id")
          .required(true)
          .schema(new Schema<String>().type("string"))
          .description("Record ID");

      if (isGetById) {
        Operation getByIdOp = createOperation(
            "Get " + entityName + " by ID",
            "Retrieves a single " + entityName + " record by its ID.");
        getByIdOp.addParametersItem(idParam);
        getByIdOp.responses(new ApiResponses()
            .addApiResponse("200", createJsonResponse("Record data",
                createObjectSchema(entityName + " record")))
            .addApiResponse("401", new ApiResponse().description("Unauthorized"))
            .addApiResponse("404", new ApiResponse().description("Record not found")));
        itemItem.get(getByIdOp);
      }

      if (isPut) {
        Operation putOp = createOperation(
            "Update " + entityName + " record",
            "Fully updates an existing " + entityName + " record.");
        putOp.addParametersItem(idParam);
        putOp.setRequestBody(createJsonRequestBody(entityName + " data"));
        putOp.responses(new ApiResponses()
            .addApiResponse("200", createJsonResponse("Updated record",
                createObjectSchema("Updated " + entityName + " record")))
            .addApiResponse("400", new ApiResponse().description("Bad request"))
            .addApiResponse("401", new ApiResponse().description("Unauthorized"))
            .addApiResponse("404", new ApiResponse().description("Record not found")));
        itemItem.put(putOp);
      }

      if (isPatch) {
        Operation patchOp = createOperation(
            "Partially update " + entityName + " record",
            "Partially updates an existing " + entityName + " record.");
        patchOp.addParametersItem(idParam);
        patchOp.setRequestBody(createJsonRequestBody(entityName + " partial data"));
        patchOp.responses(new ApiResponses()
            .addApiResponse("200", createJsonResponse("Updated record",
                createObjectSchema("Updated " + entityName + " record")))
            .addApiResponse("400", new ApiResponse().description("Bad request"))
            .addApiResponse("401", new ApiResponse().description("Unauthorized"))
            .addApiResponse("404", new ApiResponse().description("Record not found")));
        itemItem.patch(patchOp);
      }

      if (isDelete) {
        Operation deleteOp = createOperation(
            "Delete " + entityName + " record",
            "Deletes an existing " + entityName + " record.");
        deleteOp.addParametersItem(idParam);
        deleteOp.responses(new ApiResponses()
            .addApiResponse("204", new ApiResponse().description("No Content"))
            .addApiResponse("401", new ApiResponse().description("Unauthorized"))
            .addApiResponse("404", new ApiResponse().description("Record not found")));
        itemItem.delete(deleteOp);
      }

      openAPI.getPaths().addPathItem(itemPath, itemItem);
    }
  }

  /**
   * Add selector paths for an entity (list selectors and query selector values).
   */
  private void addSelectorPaths(OpenAPI openAPI, String specName, String entityName) {
    // GET /sws/neo/{specName}/{entityName}/selectors
    String selectorListPath = BASE_PATH + specName + "/" + entityName + "/selectors";
    PathItem selectorListItem = getOrCreatePathItem(openAPI, selectorListPath);

    Operation listSelectorsOp = createOperation(
        "List " + entityName + " FK selectors",
        "Returns the list of foreign key selector columns available for " + entityName + ".");
    listSelectorsOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Selector list",
            createSelectorListSchema()))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Entity not found")));
    selectorListItem.get(listSelectorsOp);
    openAPI.getPaths().addPathItem(selectorListPath, selectorListItem);

    // GET /sws/neo/{specName}/{entityName}/selectors/{columnName}
    String selectorQueryPath = BASE_PATH + specName + "/" + entityName
        + "/selectors/{columnName}";
    PathItem selectorQueryItem = getOrCreatePathItem(openAPI, selectorQueryPath);

    Operation querySelectorOp = createOperation(
        "Query " + entityName + " selector values",
        "Queries possible values for a specific FK selector column.");
    querySelectorOp.addParametersItem(new Parameter()
        .in("path")
        .name("columnName")
        .required(true)
        .schema(new Schema<String>().type("string"))
        .description("Column name of the selector"));
    querySelectorOp.addParametersItem(createQueryParam("q", "string",
        "Search term to filter selector values", null));
    querySelectorOp.addParametersItem(createQueryParam("limit", "integer",
        "Maximum number of results to return", "20"));
    querySelectorOp.addParametersItem(createQueryParam("offset", "integer",
        "Number of results to skip", "0"));
    querySelectorOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Selector values",
            createObjectSchema("Selector query results")))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Selector not found")));
    selectorQueryItem.get(querySelectorOp);
    openAPI.getPaths().addPathItem(selectorQueryPath, selectorQueryItem);
  }

  /**
   * Add action paths for an entity (list actions and execute action).
   */
  private void addActionPaths(OpenAPI openAPI, String specName, String entityName) {
    // GET /sws/neo/{specName}/{entityName}/{id}/action
    String actionListPath = BASE_PATH + specName + "/" + entityName + "/{id}/action";
    PathItem actionListItem = getOrCreatePathItem(openAPI, actionListPath);

    Parameter idParam = new Parameter()
        .in("path")
        .name("id")
        .required(true)
        .schema(new Schema<String>().type("string"))
        .description("Record ID");

    Operation listActionsOp = createOperation(
        "List " + entityName + " button actions",
        "Returns available button process actions for a " + entityName + " record.");
    listActionsOp.addParametersItem(idParam);
    listActionsOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Action list",
            createActionListSchema()))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Entity not found")));
    actionListItem.get(listActionsOp);
    openAPI.getPaths().addPathItem(actionListPath, actionListItem);

    // POST /sws/neo/{specName}/{entityName}/{id}/action/{columnName}
    String actionExecPath = BASE_PATH + specName + "/" + entityName
        + "/{id}/action/{columnName}";
    PathItem actionExecItem = getOrCreatePathItem(openAPI, actionExecPath);

    Operation execActionOp = createOperation(
        "Execute " + entityName + " button action",
        "Executes a button process action on a specific " + entityName + " record.");
    execActionOp.addParametersItem(idParam);
    execActionOp.addParametersItem(new Parameter()
        .in("path")
        .name("columnName")
        .required(true)
        .schema(new Schema<String>().type("string"))
        .description("Column name of the button action"));
    execActionOp.setRequestBody(createJsonRequestBody("Action parameters"));
    execActionOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Action result",
            createProcessResponseSchema()))
        .addApiResponse("400", new ApiResponse().description("Bad request"))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Action not found")));
    actionExecItem.post(execActionOp);
    openAPI.getPaths().addPathItem(actionExecPath, actionExecItem);
  }

  /**
   * Add discovery endpoints: GET /sws/neo/ and GET /sws/neo/{specName}.
   */
  private void addDiscoveryEndpoints(OpenAPI openAPI) {
    // GET /sws/neo/ — list all active specs
    String rootPath = BASE_PATH;
    PathItem rootItem = getOrCreatePathItem(openAPI, rootPath);

    Operation listSpecsOp = createOperation(
        "List all NEO specs",
        "Returns all active specs the current user can access, "
            + "including their entities and enabled HTTP methods.");

    ObjectSchema specSchema = new ObjectSchema();
    specSchema.addProperties("name", new Schema<String>().type("string")
        .description("Spec name"));
    specSchema.addProperties("type", new Schema<String>().type("string")
        .description("Spec type: W (window) or P (process)"));

    ObjectSchema entitySummarySchema = new ObjectSchema();
    entitySummarySchema.addProperties("name", new Schema<String>().type("string")
        .description("Entity name"));
    entitySummarySchema.addProperties("methods", new ArraySchema()
        .items(new Schema<String>().type("string"))
        .description("Enabled HTTP methods (GET, POST, PUT, PATCH, DELETE)"));
    specSchema.addProperties("entities", new ArraySchema()
        .items(entitySummarySchema)
        .description("Entities under this spec (window specs only)"));

    ObjectSchema discoveryResponseSchema = new ObjectSchema();
    discoveryResponseSchema.addProperties("specs", new ArraySchema().items(specSchema));

    listSpecsOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("List of all specs", discoveryResponseSchema))
        .addApiResponse("401", new ApiResponse().description("Unauthorized")));
    rootItem.get(listSpecsOp);
    openAPI.getPaths().addPathItem(rootPath, rootItem);

    // GET /sws/neo/{specName} — describe a spec with entities and fields
    String specDescribePath = BASE_PATH + "{specName}";
    PathItem specDescribeItem = getOrCreatePathItem(openAPI, specDescribePath);

    Operation describeSpecOp = createOperation(
        "Describe a NEO spec",
        "Returns detailed metadata for a spec, including entities, fields, "
            + "types, selectors, and enabled methods.");
    describeSpecOp.addParametersItem(new Parameter()
        .in("path")
        .name("specName")
        .required(true)
        .schema(new Schema<String>().type("string"))
        .description("Name of the spec to describe"));

    ObjectSchema fieldSchema = new ObjectSchema();
    fieldSchema.addProperties("name", new Schema<String>().type("string")
        .description("DB column name"));
    fieldSchema.addProperties("label", new Schema<String>().type("string")
        .description("Human-readable label"));
    fieldSchema.addProperties("columnType", new Schema<String>().type("string")
        .description("Type: string, number, boolean, date, datetime, list, id, button"));
    fieldSchema.addProperties("readOnly", new Schema<Boolean>().type("boolean"));
    fieldSchema.addProperties("required", new Schema<Boolean>().type("boolean"));
    fieldSchema.addProperties("hasSelector", new Schema<Boolean>().type("boolean")
        .description("Whether this field has an FK selector"));
    fieldSchema.addProperties("selectorType", new Schema<String>().type("string")
        .description("Selector type: TableDir, Table, Search, OBUISEL"));

    ObjectSchema entityDetailSchema = new ObjectSchema();
    entityDetailSchema.addProperties("name", new Schema<String>().type("string"));
    entityDetailSchema.addProperties("tabLevel", new Schema<Integer>().type("integer")
        .description("Tab hierarchy level (0 = header, 1+ = child)"));
    entityDetailSchema.addProperties("methods", new ArraySchema()
        .items(new Schema<String>().type("string")));
    entityDetailSchema.addProperties("fields", new ArraySchema().items(fieldSchema));

    ObjectSchema specDetailSchema = new ObjectSchema();
    specDetailSchema.addProperties("name", new Schema<String>().type("string"));
    specDetailSchema.addProperties("type", new Schema<String>().type("string"));
    specDetailSchema.addProperties("entities", new ArraySchema().items(entityDetailSchema));

    describeSpecOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Spec metadata", specDetailSchema))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Spec not found")));
    specDescribeItem.get(describeSpecOp);
    openAPI.getPaths().addPathItem(specDescribePath, specDescribeItem);
  }

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------

  private Operation createOperation(String summary, String description) {
    Operation op = new Operation()
        .summary(summary)
        .description(description);
    op.setTags(new ArrayList<>());
    op.getTags().add(TAG_NAME);
    return op;
  }

  private PathItem getOrCreatePathItem(OpenAPI openAPI, String path) {
    if (openAPI.getPaths().containsKey(path)) {
      return openAPI.getPaths().get(path);
    }
    return new PathItem();
  }

  private Parameter createQueryParam(String name, String type, String description,
      String defaultValue) {
    Schema<Object> schema = new Schema<>();
    schema.type(type);
    if (defaultValue != null) {
      if ("integer".equals(type)) {
        schema.setDefault(Integer.parseInt(defaultValue));
      } else {
        schema.setDefault(defaultValue);
      }
    }
    return new Parameter()
        .in("query")
        .name(name)
        .required(false)
        .schema(schema)
        .description(description);
  }

  private RequestBody createJsonRequestBody(String description) {
    return new RequestBody()
        .description(description)
        .required(true)
        .content(new Content()
            .addMediaType("application/json",
                new MediaType().schema(new ObjectSchema())));
  }

  private ApiResponse createJsonResponse(String description, Schema<?> schema) {
    return new ApiResponse()
        .description(description)
        .content(new Content()
            .addMediaType("application/json",
                new MediaType().schema(schema)));
  }

  private Schema<?> createObjectSchema(String description) {
    return new ObjectSchema().description(description);
  }

  /**
   * Schema for selector list response: array of {columnName, referenceType, type, targetEntity}.
   */
  private Schema<?> createSelectorListSchema() {
    Schema<Object> itemSchema = new Schema<>();
    itemSchema.type("object");
    itemSchema.addProperties("columnName", new Schema<String>().type("string"));
    itemSchema.addProperties("referenceType", new Schema<String>().type("string"));
    itemSchema.addProperties("type", new Schema<String>().type("string"));
    itemSchema.addProperties("targetEntity", new Schema<String>().type("string"));

    ArraySchema arraySchema = new ArraySchema();
    arraySchema.items(itemSchema);
    return arraySchema;
  }

  /**
   * Schema for action list response: array of {columnName, processType, processName}.
   */
  private Schema<?> createActionListSchema() {
    Schema<Object> itemSchema = new Schema<>();
    itemSchema.type("object");
    itemSchema.addProperties("columnName", new Schema<String>().type("string"));
    itemSchema.addProperties("processType", new Schema<String>().type("string"));
    itemSchema.addProperties("processName", new Schema<String>().type("string"));

    ArraySchema arraySchema = new ArraySchema();
    arraySchema.items(itemSchema);
    return arraySchema;
  }

  /**
   * Schema for process execution response: {status, message}.
   */
  private Schema<?> createProcessResponseSchema() {
    Schema<Object> schema = new Schema<>();
    schema.type("object");
    schema.addProperties("status", new Schema<String>().type("string"));
    schema.addProperties("message", new Schema<String>().type("string"));
    return schema;
  }
}
