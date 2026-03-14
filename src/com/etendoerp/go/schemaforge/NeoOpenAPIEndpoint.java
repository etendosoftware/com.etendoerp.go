package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.openapi.model.OpenAPIEndpoint;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BinarySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
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
      OBCriteria<SFSpec> specCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
      specCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
      List<SFSpec> specs = specCriteria.list();

      for (SFSpec spec : specs) {
        String specName = spec.getName();
        String specType = spec.getSpecType();

        if (specName == null) {
          continue;
        }

        if ("P".equals(specType)) {
          addProcessPaths(openAPI, specName);
        } else if ("W".equals(specType)) {
          addWindowPaths(openAPI, spec, specName);
        } else if ("R".equals(specType)) {
          addReportPaths(openAPI, specName);
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
   * Add OpenAPI paths for a report-type spec.
   * GET describes the report (parameters + supported formats).
   * POST generates the report binary.
   */
  private void addReportPaths(OpenAPI openAPI, String specName) {
    String path = BASE_PATH + specName;

    PathItem pathItem = getOrCreatePathItem(openAPI, path);

    // GET - describe report
    Operation describeOp = createOperation(
        "Describe report " + specName,
        "Returns metadata about the report parameters, supported export formats, "
            + "and configuration.");
    ObjectSchema describeResponseSchema = new ObjectSchema();
    describeResponseSchema.setDescription("Report metadata with parameter definitions and supported formats");
    describeResponseSchema.addProperties("isReport", new BooleanSchema().description("Always true for report specs"));
    describeResponseSchema.addProperties("supportedFormats", new ArraySchema()
        .items(new StringSchema())
        .description("Available export formats: PDF, XLS, XLSX, HTML, CSV"));
    describeOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Report metadata", describeResponseSchema))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Spec not found")));
    pathItem.get(describeOp);

    // POST - generate report
    Operation generateOp = createOperation(
        "Generate report " + specName,
        "Generates the report with the provided parameters and export type. "
            + "Returns the report as a binary file download.");

    // Request body schema
    ObjectSchema requestSchema = new ObjectSchema();
    StringSchema exportTypeSchema = new StringSchema();
    exportTypeSchema.addEnumItem("PDF");
    exportTypeSchema.addEnumItem("XLS");
    exportTypeSchema.addEnumItem("XLSX");
    exportTypeSchema.addEnumItem("HTML");
    exportTypeSchema.addEnumItem("CSV");
    exportTypeSchema.setDefault("PDF");
    exportTypeSchema.setDescription("Export format for the report");
    requestSchema.addProperties("exportType", exportTypeSchema);
    requestSchema.addProperties("params", new ObjectSchema()
        .description("Report parameters keyed by DB column name"));

    generateOp.setRequestBody(new RequestBody()
        .description("Report generation request")
        .required(true)
        .content(new Content()
            .addMediaType("application/json",
                new MediaType().schema(requestSchema))));

    // Response: binary file with multiple possible content types
    Content responseContent = new Content();
    BinarySchema binarySchema = new BinarySchema();
    responseContent.addMediaType("application/pdf", new MediaType().schema(binarySchema));
    responseContent.addMediaType("application/vnd.ms-excel", new MediaType().schema(binarySchema));
    responseContent.addMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new MediaType().schema(binarySchema));
    responseContent.addMediaType("text/html", new MediaType().schema(binarySchema));
    responseContent.addMediaType("text/csv", new MediaType().schema(binarySchema));

    generateOp.responses(new ApiResponses()
        .addApiResponse("200", new ApiResponse()
            .description("Report file download")
            .content(responseContent))
        .addApiResponse("400", new ApiResponse().description("Bad request (invalid parameters)"))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Spec not found"))
        .addApiResponse("500", new ApiResponse().description("Report generation failed")));
    pathItem.post(generateOp);

    openAPI.getPaths().addPathItem(path, pathItem);
  }

  /**
   * Add OpenAPI paths for a window-type spec, iterating its included entities.
   */
  private void addWindowPaths(OpenAPI openAPI, SFSpec spec, String specName) {
    String specId = spec.getId();

    // Query active, included entities for this spec
    OBCriteria<SFEntity> entityCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    List<SFEntity> entities = entityCriteria.list();

    for (SFEntity entity : entities) {
      String entityName = entity.getName();
      if (entityName == null) {
        continue;
      }

      addCrudPaths(openAPI, specName, entityName, entity);
      addSelectorPaths(openAPI, specName, entityName);
      addActionPaths(openAPI, specName, entityName);
      addEvaluateDisplayPaths(openAPI, specName, entityName);
      addDefaultsPaths(openAPI, specName, entityName);
    }
  }

  /**
   * Add CRUD paths (GET list, GET by ID, POST, PUT, PATCH, DELETE) based on entity flags.
   */
  private void addCrudPaths(OpenAPI openAPI, String specName, String entityName,
      SFEntity entity) {

    String listPath = BASE_PATH + specName + "/" + entityName;
    String itemPath = BASE_PATH + specName + "/" + entityName + "/{id}";

    boolean isGet = Boolean.TRUE.equals(entity.isGet());
    boolean isPost = Boolean.TRUE.equals(entity.isPost());
    boolean isGetById = Boolean.TRUE.equals(entity.isGetByID());
    boolean isPut = Boolean.TRUE.equals(entity.isPut());
    boolean isPatch = Boolean.TRUE.equals(entity.isPatch());
    boolean isDelete = Boolean.TRUE.equals(entity.isDelete());

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
        "Queries possible values for a specific FK selector column. "
            + "Selectors may accept context parameters to filter results based on "
            + "dependent fields (e.g., ?C_BPartner_ID=xyz to filter locations by BP). "
            + "Use the describe endpoint to discover which selectorParams each field requires.");
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
        .description("Spec type: W (window), P (process), or R (report)"));

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
    fieldSchema.addProperties("selectorParams", new ArraySchema()
        .items(new Schema<String>().type("string"))
        .description("Column names that must be passed as query params to filter this selector "
            + "(e.g., C_BPartner_ID). Derived from the column's validation rule."));

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

  /**
   * Register /evaluate-display endpoint in OpenAPI for each entity.
   * Called from addWindowPaths() alongside addSelectorPaths() and addActionPaths().
   */
  private void addEvaluateDisplayPaths(OpenAPI openAPI, String specName, String entityName) {
    String path = BASE_PATH + specName + "/" + entityName + "/evaluate-display";
    PathItem pathItem = getOrCreatePathItem(openAPI, path);

    // Request body schema
    Schema<?> fieldValuesSchema = new ObjectSchema()
        .description("Current field values from the form. "
            + "Keys are property names (camelCase) as returned by GET responses.")
        .additionalProperties(new Schema<>());

    ObjectSchema requestSchema = new ObjectSchema();
    requestSchema.addProperties("fieldValues", fieldValuesSchema);

    RequestBody requestBody = new RequestBody()
        .required(false)
        .description("Field values for expression evaluation. "
            + "Empty body evaluates using only session/preference context.")
        .content(new Content().addMediaType("application/json",
            new MediaType().schema(requestSchema)));

    // Response schema
    Schema<?> visibilityMapSchema = new ObjectSchema()
        .description("Display logic results. true = visible, false = hidden. "
            + "Fields without displayLogic are omitted (default visible).")
        .additionalProperties(new BooleanSchema());

    Schema<?> readOnlyMapSchema = new ObjectSchema()
        .description("ReadOnly logic results. true = read-only, false = editable. "
            + "Fields without readOnlyLogic are omitted (default editable).")
        .additionalProperties(new BooleanSchema());

    ObjectSchema responseSchema = new ObjectSchema();
    responseSchema.addProperties("visibility", visibilityMapSchema);
    responseSchema.addProperties("readOnly", readOnlyMapSchema);

    // Operation
    Operation evalOp = createOperation(
        "Evaluate display logic for " + entityName,
        "Evaluates all displayLogic and readOnlyLogic expressions for the fields "
            + "of this entity. Uses the raw AD expressions with full server-side "
            + "variable resolution (session context, preferences, accounting "
            + "dimensions, auxiliary inputs). Returns a map of field visibility "
            + "and read-only states.");

    evalOp.setRequestBody(requestBody);
    evalOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse(
            "Evaluated display logic for all fields", responseSchema))
        .addApiResponse("400", new ApiResponse().description("Invalid request body"))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Spec or entity not found"))
        .addApiResponse("405", new ApiResponse().description("Method not allowed")));

    pathItem.post(evalOp);
    openAPI.getPaths().addPathItem(path, pathItem);
  }

  /**
   * Register /defaults endpoint in OpenAPI for each entity.
   * Called from addWindowPaths() alongside other sub-path registrations.
   */
  private void addDefaultsPaths(OpenAPI openAPI, String specName, String entityName) {
    String path = BASE_PATH + specName + "/" + entityName + "/defaults";
    PathItem pathItem = getOrCreatePathItem(openAPI, path);

    // Response schema
    ObjectSchema defaultsMapSchema = new ObjectSchema();
    defaultsMapSchema.setDescription(
        "Default values map. Keys are field names (camelCase), values are resolved defaults.");
    defaultsMapSchema.setAdditionalProperties(new Schema<>());

    ObjectSchema metadataSchema = new ObjectSchema();
    metadataSchema.addProperties("unresolvedFields", new ArraySchema()
        .items(new StringSchema())
        .description("Fields whose defaults could not be resolved (SQL expressions, missing context)"));
    metadataSchema.addProperties("sequenceFields", new ArraySchema()
        .items(new StringSchema())
        .description("Fields with auto-generated sequence values (preview only, not consumed)"));

    ObjectSchema responseSchema = new ObjectSchema();
    responseSchema.addProperties("defaults", defaultsMapSchema);
    responseSchema.addProperties("metadata", metadataSchema);

    // Operation
    Operation getOp = createOperation(
        "Get default values for new " + entityName,
        "Returns server-resolved default values for creating a new " + entityName
            + " record. Resolves literal defaults from AD_Column.DefaultValue, "
            + "session context variables (@#AD_Org_ID@, @#Date@, etc.), "
            + "and sequence previews (DocumentNo). "
            + "Use parentId query parameter for child entities that need a parent link.");

    getOp.addParametersItem(createQueryParam("parentId", "string",
        "Parent record ID for child entities (auto-fills parent link column)", null));

    getOp.responses(new ApiResponses()
        .addApiResponse("200", createJsonResponse("Default values for new record", responseSchema))
        .addApiResponse("401", new ApiResponse().description("Unauthorized"))
        .addApiResponse("404", new ApiResponse().description("Spec or entity not found")));

    pathItem.get(getOp);
    openAPI.getPaths().addPathItem(path, pathItem);
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
