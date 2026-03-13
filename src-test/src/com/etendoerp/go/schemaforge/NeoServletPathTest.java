package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link NeoServlet} path parsing logic.
 * Tests the parsePath method which extracts specName, entityName, and recordId from the URL.
 */
public class NeoServletPathTest {

  private NeoServlet servlet;

  @Before
  public void setUp() {
    servlet = new NeoServlet();
  }

  @Test
  public void testParsePathSpecAndEntity() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Product");

    assertEquals("mySpec", info.specName);
    assertEquals("Product", info.entityName);
    assertNull(info.recordId);
  }

  @Test
  public void testParsePathWithRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Product/ABC123");

    assertEquals("mySpec", info.specName);
    assertEquals("Product", info.entityName);
    assertEquals("ABC123", info.recordId);
  }

  @Test
  public void testParsePathWithUuidRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/sales/Order/550e8400-e29b-41d4-a716-446655440000");

    assertEquals("sales", info.specName);
    assertEquals("Order", info.entityName);
    assertEquals("550e8400-e29b-41d4-a716-446655440000", info.recordId);
  }

  @Test
  public void testParsePathWithoutLeadingSlash() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("mySpec/Product");

    assertEquals("mySpec", info.specName);
    assertEquals("Product", info.entityName);
    assertNull(info.recordId);
  }

  @Test
  public void testParsePathExtraSegmentsIgnored() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/spec/entity/id/extra/stuff");

    assertEquals("spec", info.specName);
    assertEquals("entity", info.entityName);
    assertEquals("id", info.recordId);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testParsePathNull() {
    servlet.parsePath(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testParsePathEmpty() {
    servlet.parsePath("");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testParsePathOnlySpec() {
    servlet.parsePath("/mySpec");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testParsePathSlashOnly() {
    servlet.parsePath("/");
  }

  @Test
  public void testParsePathWithSpecialCharacters() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/my-spec/my_entity");

    assertEquals("my-spec", info.specName);
    assertEquals("my_entity", info.entityName);
  }

  @Test
  public void testParsePathCaseSensitive() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/MySpec/MyEntity");

    assertEquals("MySpec", info.specName);
    assertEquals("MyEntity", info.entityName);
  }

  @Test
  public void testNeoPathInfoFields() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", "r");

    assertEquals("s", info.specName);
    assertEquals("e", info.entityName);
    assertEquals("r", info.recordId);
  }

  @Test
  public void testNeoPathInfoNullRecordId() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", null);

    assertNull(info.recordId);
  }

  @Test
  public void testParsePathSelectorsList() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Product/selectors");

    assertEquals("mySpec", info.specName);
    assertEquals("Product", info.entityName);
    assertTrue(info.isSelector);
    assertNull(info.selectorField);
    assertNull(info.recordId);
  }

  @Test
  public void testParsePathSelectorField() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Product/selectors/C_BPartner_ID");

    assertEquals("mySpec", info.specName);
    assertEquals("Product", info.entityName);
    assertTrue(info.isSelector);
    assertEquals("C_BPartner_ID", info.selectorField);
    assertNull(info.recordId);
  }

  @Test
  public void testParsePathRecordIdNotSelector() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Product/ABC123");

    assertEquals("mySpec", info.specName);
    assertEquals("Product", info.entityName);
    assertFalse(info.isSelector);
    assertEquals("ABC123", info.recordId);
  }

  @Test
  public void testParsePathActionList() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/REC123/action");

    assertEquals("mySpec", info.specName);
    assertEquals("Order", info.entityName);
    assertEquals("REC123", info.recordId);
    assertTrue(info.isAction);
    assertNull(info.actionName);
    assertFalse(info.isSelector);
  }

  @Test
  public void testParsePathActionWithColumnName() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/REC123/action/DocAction");

    assertEquals("mySpec", info.specName);
    assertEquals("Order", info.entityName);
    assertEquals("REC123", info.recordId);
    assertTrue(info.isAction);
    assertEquals("DocAction", info.actionName);
    assertFalse(info.isSelector);
  }

  @Test
  public void testParsePathActionWithUuidRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath(
        "/sales/Invoice/550e8400-e29b-41d4-a716-446655440000/action/Posted");

    assertEquals("sales", info.specName);
    assertEquals("Invoice", info.entityName);
    assertEquals("550e8400-e29b-41d4-a716-446655440000", info.recordId);
    assertTrue(info.isAction);
    assertEquals("Posted", info.actionName);
  }

  @Test
  public void testParsePathNonActionSubPath() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/spec/entity/id/other");

    assertEquals("spec", info.specName);
    assertEquals("entity", info.entityName);
    assertEquals("id", info.recordId);
    assertFalse(info.isAction);
    assertNull(info.actionName);
  }

  @Test
  public void testNeoPathInfoDefaultActionFields() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", "r");

    assertFalse(info.isAction);
    assertNull(info.actionName);
  }

  @Test
  public void testNeoPathInfoSelectorConstructorDefaultActionFields() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", null, true, "field");

    assertTrue(info.isSelector);
    assertFalse(info.isAction);
    assertNull(info.actionName);
  }

  @Test
  public void testParsePathEvaluateDisplay() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/evaluate-display");

    assertEquals("mySpec", info.specName);
    assertEquals("Order", info.entityName);
    assertTrue(info.isEvaluateDisplay);
    assertNull(info.recordId);
    assertFalse(info.isSelector);
    assertFalse(info.isAction);
  }

  @Test
  public void testParsePathEvaluateDisplayNotConfusedWithRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/evaluate-display");

    assertNull(info.recordId);
    assertTrue(info.isEvaluateDisplay);
  }

  @Test
  public void testParsePathRegularRecordIdNotEvaluateDisplay() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/ABC123");

    assertEquals("ABC123", info.recordId);
    assertFalse(info.isEvaluateDisplay);
  }

  @Test
  public void testNeoPathInfoDefaultEvaluateDisplayFalse() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", "r");

    assertFalse(info.isEvaluateDisplay);
  }

  // ── Callout path tests ─────────────────────────────────────────────

  @Test
  public void testParsePathCallout() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/callout");

    assertEquals("mySpec", info.specName);
    assertEquals("Order", info.entityName);
    assertTrue(info.isCallout);
    assertNull(info.recordId);
    assertFalse(info.isSelector);
    assertFalse(info.isAction);
    assertFalse(info.isEvaluateDisplay);
  }

  @Test
  public void testParsePathCalloutNotConfusedWithRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/callout");

    assertNull(info.recordId);
    assertTrue(info.isCallout);
  }

  @Test
  public void testParsePathRegularRecordIdNotCallout() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/mySpec/Order/ABC123");

    assertEquals("ABC123", info.recordId);
    assertFalse(info.isCallout);
  }

  @Test
  public void testNeoPathInfoDefaultCalloutFalse() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", "r");

    assertFalse(info.isCallout);
  }

  @Test
  public void testParsePathCalloutDistinctFromSelectors() {
    NeoServlet.NeoPathInfo calloutInfo = servlet.parsePath("/spec/entity/callout");
    NeoServlet.NeoPathInfo selectorInfo = servlet.parsePath("/spec/entity/selectors");

    assertTrue(calloutInfo.isCallout);
    assertFalse(calloutInfo.isSelector);

    assertFalse(selectorInfo.isCallout);
    assertTrue(selectorInfo.isSelector);
  }
}
