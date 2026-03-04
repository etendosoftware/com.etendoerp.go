package com.etendoerp.etendogo.contract;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

public class ContractSchemaTest {

  private static final String MODEL_PACKAGE = "com.etendoerp.etendogo.rest.contract.model";
  private static OpenAPI openAPI;

  @BeforeClass
  public static void setUp() {
    String specPath = findSpecPath();
    SwaggerParseResult result = new OpenAPIV3Parser().readLocation(specPath, null, null);
    assertNotNull("Could not parse OpenAPI spec", result);
    openAPI = result.getOpenAPI();
    assertNotNull("OpenAPI spec is null", openAPI);
  }

  @Test
  public void allSchemaPropertiesHaveGetters() {
    Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
    List<String> errors = new ArrayList<>();

    for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
      String schemaName = entry.getKey();
      Schema<?> schema = entry.getValue();

      Class<?> clazz;
      try {
        clazz = Class.forName(MODEL_PACKAGE + "." + schemaName);
      } catch (ClassNotFoundException e) {
        errors.add("No generated class for schema: " + schemaName);
        continue;
      }

      if (schema.getProperties() == null) {
        continue;
      }

      for (Object propName : schema.getProperties().keySet()) {
        String prop = (String) propName;
        String getterName = "get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        boolean found = false;
        for (Method m : clazz.getMethods()) {
          if (m.getName().equals(getterName) || m.getName().equals(booleanGetter(prop))) {
            found = true;
            break;
          }
        }
        if (!found) {
          errors.add(schemaName + "." + prop + " has no getter (" + getterName + ")");
        }
      }
    }

    if (!errors.isEmpty()) {
      fail("Schema/class mismatches:\n  " + String.join("\n  ", errors));
    }
  }

  private static String booleanGetter(String prop) {
    return "is" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
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
    return new File("openapi-contracts/index.yaml").getAbsolutePath();
  }
}
