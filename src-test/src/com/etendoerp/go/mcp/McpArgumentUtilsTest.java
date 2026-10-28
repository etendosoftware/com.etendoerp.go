package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Tests the null-safe argument normalization used by MCP tool adapters.
 *
 * @covers com.etendoerp.go.mcp.McpArgumentUtils
 */
public class McpArgumentUtilsTest {

  @Test
  public void optionalStringReturnsNullForMissingOrNullArguments() throws Exception {
    assertNull(McpArgumentUtils.optionalString(null, "limit"));
    assertNull(McpArgumentUtils.optionalString(new JSONObject(), "limit"));
    assertNull(McpArgumentUtils.optionalString(new JSONObject("{\"limit\":null}"), "limit"));
  }

  @Test
  public void optionalStringConvertsPresentValuesToText() throws Exception {
    JSONObject arguments = new JSONObject("{\"limit\":25,\"enabled\":true}");

    assertEquals("25", McpArgumentUtils.optionalString(arguments, "limit"));
    assertEquals("true", McpArgumentUtils.optionalString(arguments, "enabled"));
  }

  @Test
  public void joinStringArrayReturnsNullForNullInput() {
    assertNull(McpArgumentUtils.joinStringArray(null));
  }

  @Test
  public void joinStringArrayOmitsBlankValuesAndPreservesOrder() throws Exception {
    JSONArray values = new JSONArray("[\" sales-quotation \",\" \",null,\"purchase-order\"]");

    assertEquals(" sales-quotation ,null,purchase-order", McpArgumentUtils.joinStringArray(values));
  }

  @Test
  public void copyTopLevelParentIdCopiesANonBlankValueOverTheFieldsOwn() throws Exception {
    JSONObject args = new JSONObject("{\"parentId\":\"TOP\"}");
    JSONObject fields = new JSONObject("{\"parentId\":\"INNER\"}");

    McpArgumentUtils.copyTopLevelParentId(args, fields);

    assertEquals("TOP", fields.getString("parentId"));
  }

  @Test
  public void copyTopLevelParentIdCopiesIntoFieldsThatHaveNone() throws Exception {
    JSONObject fields = new JSONObject();

    McpArgumentUtils.copyTopLevelParentId(new JSONObject("{\"parentId\":\"TOP\"}"), fields);

    assertEquals("TOP", fields.getString("parentId"));
  }

  @Test
  public void copyTopLevelParentIdKeepsTheFieldsParentIdWhenTheArgumentIsNull() throws Exception {
    JSONObject fields = new JSONObject("{\"parentId\":\"INNER\"}");

    McpArgumentUtils.copyTopLevelParentId(new JSONObject("{\"parentId\":null}"), fields);

    assertEquals("INNER", fields.getString("parentId"));
  }

  @Test
  public void copyTopLevelParentIdKeepsTheFieldsParentIdWhenTheArgumentIsBlank() throws Exception {
    JSONObject fields = new JSONObject("{\"parentId\":\"INNER\"}");

    McpArgumentUtils.copyTopLevelParentId(new JSONObject("{\"parentId\":\"  \"}"), fields);

    assertEquals("INNER", fields.getString("parentId"));
  }

  @Test
  public void copyTopLevelParentIdWritesNothingWhenTheKeyIsAbsent() throws Exception {
    JSONObject fields = new JSONObject();

    McpArgumentUtils.copyTopLevelParentId(new JSONObject("{\"other\":\"x\"}"), fields);

    assertFalse(fields.has("parentId"));
  }
}
