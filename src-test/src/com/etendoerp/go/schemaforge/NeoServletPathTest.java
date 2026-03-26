package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoServlet} path parsing logic.
 * Tests the parsePath method which extracts specName, entityName, and recordId from the URL.
 */
class NeoServletPathTest {

  private static final String SPEC_NAME = "mySpec";

  private NeoServlet servlet;

  @BeforeEach
  void setUp() {
    servlet = new NeoServlet();
  }

  @Test
  void testParsePathSpecAndEntity() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Product");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Product", info.entityName);
    assertNull(info.recordId);
  }

  @Test
  void testParsePathWithRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Product/ABC123");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Product", info.entityName);
    assertEquals("ABC123", info.recordId);
  }

  @Test
  void testParsePathWithUuidRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/sales/Order/550e8400-e29b-41d4-a716-446655440000");

    assertEquals("sales", info.specName);
    assertEquals("Order", info.entityName);
    assertEquals("550e8400-e29b-41d4-a716-446655440000", info.recordId);
  }

  @Test
  void testParsePathWithoutLeadingSlash() {
    NeoServlet.NeoPathInfo info = servlet.parsePath(SPEC_NAME + "/Product");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Product", info.entityName);
    assertNull(info.recordId);
  }

  @Test
  void testParsePathExtraSegmentsIgnored() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/spec/entity/id/extra/stuff");

    assertEquals("spec", info.specName);
    assertEquals("entity", info.entityName);
    assertEquals("id", info.recordId);
  }

  /** Returns discovery mode (all nulls) when path is null. */
  @Test
  void testParsePathNullReturnsDiscoveryMode() {
    NeoServlet.NeoPathInfo info = servlet.parsePath(null);
    assertNull(info.specName);
    assertNull(info.entityName);
    assertNull(info.recordId);
  }

  /** Returns discovery mode (all nulls) when path is empty. */
  @Test
  void testParsePathEmptyReturnsDiscoveryMode() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("");
    assertNull(info.specName);
    assertNull(info.entityName);
    assertNull(info.recordId);
  }

  /** Returns only specName when path has a single segment (process spec). */
  @Test
  void testParsePathOnlySpecReturnsProcessSpec() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME);
    assertEquals(SPEC_NAME, info.specName);
    assertNull(info.entityName);
    assertNull(info.recordId);
  }

  /** Returns discovery mode (all nulls) when path is a bare slash. */
  @Test
  void testParsePathSlashOnlyReturnsDiscoveryMode() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/");
    assertNull(info.specName);
    assertNull(info.entityName);
    assertNull(info.recordId);
  }

  @Test
  void testParsePathWithSpecialCharacters() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/my-spec/my_entity");

    assertEquals("my-spec", info.specName);
    assertEquals("my_entity", info.entityName);
  }

  @Test
  void testParsePathCaseSensitive() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/MySpec/MyEntity");

    assertEquals("MySpec", info.specName);
    assertEquals("MyEntity", info.entityName);
  }

  @Test
  void testNeoPathInfoFields() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", "r");

    assertEquals("s", info.specName);
    assertEquals("e", info.entityName);
    assertEquals("r", info.recordId);
  }

  @Test
  void testNeoPathInfoNullRecordId() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", null);

    assertNull(info.recordId);
  }

  @Test
  void testParsePathSelectorsList() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Product/selectors");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Product", info.entityName);
    assertTrue(info.isSelector);
    assertNull(info.selectorField);
    assertNull(info.recordId);
  }

  @Test
  void testParsePathSelectorField() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Product/selectors/C_BPartner_ID");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Product", info.entityName);
    assertTrue(info.isSelector);
    assertEquals("C_BPartner_ID", info.selectorField);
    assertNull(info.recordId);
  }

  @Test
  void testParsePathRecordIdNotSelector() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Product/ABC123");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Product", info.entityName);
    assertFalse(info.isSelector);
    assertEquals("ABC123", info.recordId);
  }

  @Test
  void testParsePathActionList() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Order/REC123/action");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Order", info.entityName);
    assertEquals("REC123", info.recordId);
    assertTrue(info.isAction);
    assertNull(info.actionName);
    assertFalse(info.isSelector);
  }

  @Test
  void testParsePathActionWithColumnName() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Order/REC123/action/DocAction");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Order", info.entityName);
    assertEquals("REC123", info.recordId);
    assertTrue(info.isAction);
    assertEquals("DocAction", info.actionName);
    assertFalse(info.isSelector);
  }

  @Test
  void testParsePathActionWithUuidRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath(
        "/sales/Invoice/550e8400-e29b-41d4-a716-446655440000/action/Posted");

    assertEquals("sales", info.specName);
    assertEquals("Invoice", info.entityName);
    assertEquals("550e8400-e29b-41d4-a716-446655440000", info.recordId);
    assertTrue(info.isAction);
    assertEquals("Posted", info.actionName);
  }

  @Test
  void testParsePathNonActionSubPath() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/spec/entity/id/other");

    assertEquals("spec", info.specName);
    assertEquals("entity", info.entityName);
    assertEquals("id", info.recordId);
    assertFalse(info.isAction);
    assertNull(info.actionName);
  }

  @Test
  void testNeoPathInfoDefaultActionFields() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", "r");

    assertFalse(info.isAction);
    assertNull(info.actionName);
  }

  @Test
  void testNeoPathInfoSelectorConstructorDefaultActionFields() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", null, true, "field");

    assertTrue(info.isSelector);
    assertFalse(info.isAction);
    assertNull(info.actionName);
  }

  @Test
  void testParsePathEvaluateDisplay() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Order/evaluate-display");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Order", info.entityName);
    assertTrue(info.isEvaluateDisplay);
    assertNull(info.recordId);
    assertFalse(info.isSelector);
    assertFalse(info.isAction);
  }

  @Test
  void testParsePathEvaluateDisplayNotConfusedWithRecordId() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Order/evaluate-display");

    assertNull(info.recordId);
    assertTrue(info.isEvaluateDisplay);
  }

  @Test
  void testParsePathRegularRecordIdNotEvaluateDisplay() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Order/ABC123");

    assertEquals("ABC123", info.recordId);
    assertFalse(info.isEvaluateDisplay);
  }

  @Test
  void testNeoPathInfoDefaultEvaluateDisplayFalse() {
    NeoServlet.NeoPathInfo info = new NeoServlet.NeoPathInfo("s", "e", "r");

    assertFalse(info.isEvaluateDisplay);
  }

  // ── Callout path tests ─────────────────────────────────────────────

  @Test
  void testParsePathCallout() {
    NeoServlet.NeoPathInfo info = servlet.parsePath("/" + SPEC_NAME + "/Order/callout");

    assertEquals(SPEC_NAME, info.specName);
    assertEquals("Order", info.entityName);
    assertTrue(info.isCallout);
    assertNull(info.recordId);
    assertFalse(info.isSelector);
    assertFalse(info.isAction);
    assertFalse(info.isEvaluateDisplay);
  }

}
