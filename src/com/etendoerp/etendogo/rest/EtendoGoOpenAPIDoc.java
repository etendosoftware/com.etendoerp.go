package com.etendoerp.etendogo.rest;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.openapi.model.OpenAPIEndpoint;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

public class EtendoGoOpenAPIDoc implements OpenAPIEndpoint {

  private static final List<String> TAG = Collections.singletonList("EtendoGo");
  private static final String APPLICATION_JSON = "application/json";
  private static final String BASE_PATH = "/sws/etendogo";

  @Override
  public boolean isValid(String tag) {
    return StringUtils.equalsIgnoreCase(tag, "etendogo");
  }

  @Override
  public void add(OpenAPI openAPI) {
    if (openAPI.getPaths() == null) {
      openAPI.setPaths(new Paths());
    }
    addDummyGetEndpoint(openAPI);
    addDummyPostEndpoint(openAPI);
    addDummyPutEndpoint(openAPI);
    addDummyDeleteEndpoint(openAPI);
  }

  private void addDummyGetEndpoint(OpenAPI openAPI) {
    var operation = new Operation();
    operation.setSummary("Test GET endpoint");
    operation.setDescription("Returns a dummy response to verify the service is running.");
    operation.setTags(TAG);
    operation.setOperationId("etendogoGet");
    operation.responses(buildDummyResponses());

    var pathItem = getOrCreatePathItem(openAPI, BASE_PATH);
    pathItem.setGet(operation);
  }

  private void addDummyPostEndpoint(OpenAPI openAPI) {
    var operation = new Operation();
    operation.setSummary("Test POST endpoint");
    operation.setDescription("Accepts a JSON body and returns a dummy response.");
    operation.setTags(TAG);
    operation.setOperationId("etendogoPost");
    operation.responses(buildDummyResponses());

    var pathItem = getOrCreatePathItem(openAPI, BASE_PATH);
    pathItem.setPost(operation);
  }

  private void addDummyPutEndpoint(OpenAPI openAPI) {
    var operation = new Operation();
    operation.setSummary("Test PUT endpoint");
    operation.setDescription("Accepts a JSON body and returns a dummy response.");
    operation.setTags(TAG);
    operation.setOperationId("etendogoPut");
    operation.responses(buildDummyResponses());

    var pathItem = getOrCreatePathItem(openAPI, BASE_PATH);
    pathItem.setPut(operation);
  }

  private void addDummyDeleteEndpoint(OpenAPI openAPI) {
    var operation = new Operation();
    operation.setSummary("Test DELETE endpoint");
    operation.setDescription("Returns a dummy response confirming deletion.");
    operation.setTags(TAG);
    operation.setOperationId("etendogoDelete");
    operation.responses(buildDummyResponses());

    var pathItem = getOrCreatePathItem(openAPI, BASE_PATH);
    pathItem.setDelete(operation);
  }

  private static ApiResponses buildDummyResponses() {
    var schema = new ObjectSchema()
        .addProperties("status", new StringSchema().description("Always 'ok'"))
        .addProperties("method", new StringSchema().description("HTTP method used"))
        .addProperties("path", new StringSchema().description("Request path"))
        .addProperties("message", new StringSchema().description("Dummy message"));

    var response = new ApiResponse()
        .description("Successful dummy response")
        .content(new Content().addMediaType(APPLICATION_JSON,
            new MediaType().schema(schema)));

    return new ApiResponses().addApiResponse("200", response);
  }

  private static PathItem getOrCreatePathItem(OpenAPI openAPI, String path) {
    PathItem existing = openAPI.getPaths().get(path);
    if (existing != null) {
      return existing;
    }
    var pathItem = new PathItem();
    openAPI.getPaths().put(path, pathItem);
    return pathItem;
  }
}
