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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Unit tests for the boolean-criteria normalizer in {@link NeoCrudHandler}
 * ({@code normalizeBooleanClause} and {@code normalizeBooleanCriteriaArray}).
 *
 * <p>Background (ETP-4705): the frontend serializes every boolean list-filter column
 * to the char {@code "Y"}/{@code "N"}. That is correct for AD button/list columns the
 * DAL exposes as {@code String} (e.g. {@code Posted}), which core matches verbatim.
 * But for columns the DAL exposes as a genuine {@code Boolean} property (Yes/No
 * reference, e.g. {@code IsDefault}), core {@code AdvancedQueryBuilder} coerces the
 * value with {@code Boolean.valueOf("Y") == false}, silently inverting the filter.
 * The normalizer rewrites {@code "Y"/"N"} to a real boolean ONLY for Boolean-typed DAL
 * properties, leaving String columns and non-Y/N values untouched.
 */
public class NeoCrudHandlerBooleanCriteriaTest {

  private final NeoCrudHandler handler =
      new NeoCrudHandler(mock(NeoServlet.class), mock(NeoTelemetryService.class));

  // ─── helpers ─────────────────────────────────────────────────────────────────

  /** Builds an Entity whose {@code fieldName} resolves to a property of the given DAL type. */
  private Entity entityWithProperty(String fieldName, Class<?> primitiveType) {
    Property prop = mock(Property.class);
    when(prop.getPrimitiveObjectType()).thenReturn((Class) primitiveType);

    Entity entity = mock(Entity.class);
    when(entity.getProperty(fieldName, false)).thenReturn(prop);
    // Keep the case-insensitive fallback loop safe.
    when(entity.getProperties()).thenReturn(Collections.emptyList());
    return entity;
  }

  /** Builds an Entity that resolves no property for any field name. */
  private Entity entityWithNoProperties() {
    Entity entity = mock(Entity.class);
    when(entity.getProperties()).thenReturn(Collections.emptyList());
    return entity;
  }

  private JSONObject clause(String fieldName, Object value) throws Exception {
    JSONObject c = new JSONObject();
    c.put("fieldName", fieldName);
    c.put("operator", "equals");
    c.put("value", value);
    return c;
  }

  // ─── normalizeBooleanClause ────────────────────────────────────────────────────

  /** TC-1: Boolean column, "Y" → real boolean TRUE, returns true. */
  @Test
  public void testBooleanColumnYBecomesTrue() throws Exception {
    Entity entity = entityWithProperty("default", Boolean.class);
    JSONObject c = clause("default", "Y");

    boolean changed = handler.normalizeBooleanClause(c, entity);

    assertTrue("clause should be rewritten", changed);
    assertEquals("value should be a real Boolean, not the String \"Y\"",
        Boolean.TRUE, c.get("value"));
  }

  /** TC-2: Boolean column, "N" → real boolean FALSE, returns true. */
  @Test
  public void testBooleanColumnNBecomesFalse() throws Exception {
    Entity entity = entityWithProperty("default", Boolean.class);
    JSONObject c = clause("default", "N");

    boolean changed = handler.normalizeBooleanClause(c, entity);

    assertTrue(changed);
    assertEquals(Boolean.FALSE, c.get("value"));
  }

  /** TC-3: Boolean column, lowercase "y" → TRUE (case-insensitive), returns true. */
  @Test
  public void testBooleanColumnLowercaseYBecomesTrue() throws Exception {
    Entity entity = entityWithProperty("default", Boolean.class);
    JSONObject c = clause("default", "y");

    boolean changed = handler.normalizeBooleanClause(c, entity);

    assertTrue(changed);
    assertEquals(Boolean.TRUE, c.get("value"));
  }

  /** TC-4: String column left untouched — "Y" stays the String "Y", returns false. */
  @Test
  public void testStringColumnLeftUntouched() throws Exception {
    Entity entity = entityWithProperty("posted", String.class);
    JSONObject c = clause("posted", "Y");

    boolean changed = handler.normalizeBooleanClause(c, entity);

    assertFalse("String columns must not be rewritten", changed);
    assertEquals("value should still be the String \"Y\"", "Y", c.get("value"));
  }

  /** TC-5: Raw JSON boolean value is not re-processed — stays boolean true, returns false. */
  @Test
  public void testRawBooleanValueUntouched() throws Exception {
    Entity entity = entityWithProperty("default", Boolean.class);
    JSONObject c = new JSONObject();
    c.put("fieldName", "default");
    c.put("operator", "equals");
    c.put("value", true);

    boolean changed = handler.normalizeBooleanClause(c, entity);

    assertFalse("a value that is already a boolean must not be re-processed", changed);
    assertEquals(Boolean.TRUE, c.get("value"));
  }

  /** TC-6: Non-Y/N string value is left untouched, returns false. */
  @Test
  public void testNonYNStringUntouched() throws Exception {
    Entity entity = entityWithProperty("posted", String.class);
    JSONObject c = clause("posted", "CO");

    boolean changed = handler.normalizeBooleanClause(c, entity);

    assertFalse(changed);
    assertEquals("CO", c.get("value"));
  }

  /** TC-7: Unknown field (no resolvable property) → no-op, returns false. */
  @Test
  public void testUnknownFieldIsNoOp() throws Exception {
    Entity entity = entityWithNoProperties();
    JSONObject c = clause("doesNotExist", "Y");

    boolean changed = handler.normalizeBooleanClause(c, entity);

    assertFalse(changed);
    assertEquals("Y", c.get("value"));
  }

  // ─── normalizeBooleanCriteriaArray ──────────────────────────────────────────────

  /** TC-8: Nested and/or composite — recursion rewrites the boolean clause only. */
  @Test
  public void testNestedCompositeRecursion() throws Exception {
    Property boolProp = mock(Property.class);
    when(boolProp.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
    Property strProp = mock(Property.class);
    when(strProp.getPrimitiveObjectType()).thenReturn((Class) String.class);

    Entity entity = mock(Entity.class);
    when(entity.getProperty("default", false)).thenReturn(boolProp);
    when(entity.getProperty("posted", false)).thenReturn(strProp);
    when(entity.getProperties()).thenReturn(Collections.emptyList());

    JSONArray nested = new JSONArray();
    nested.put(clause("default", "Y"));
    nested.put(clause("posted", "Y"));

    JSONObject composite = new JSONObject();
    composite.put("operator", "and");
    composite.put("criteria", nested);

    JSONArray root = new JSONArray();
    root.put(composite);

    boolean changed = handler.normalizeBooleanCriteriaArray(root, entity);

    assertTrue("recursion should report a rewrite happened", changed);
    JSONArray outNested = root.getJSONObject(0).getJSONArray("criteria");
    assertEquals("nested Boolean clause should become real boolean true",
        Boolean.TRUE, outNested.getJSONObject(0).get("value"));
    assertEquals("nested String clause should stay \"Y\"",
        "Y", outNested.getJSONObject(1).get("value"));
  }

  /** TC-9: Mixed flat clauses — only the Boolean column changes, returns true. */
  @Test
  public void testMixedFlatClausesOnlyBooleanChanges() throws Exception {
    Property boolProp = mock(Property.class);
    when(boolProp.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
    Property strProp = mock(Property.class);
    when(strProp.getPrimitiveObjectType()).thenReturn((Class) String.class);

    Entity entity = mock(Entity.class);
    when(entity.getProperty("default", false)).thenReturn(boolProp);
    when(entity.getProperty("posted", false)).thenReturn(strProp);
    when(entity.getProperties()).thenReturn(Collections.emptyList());

    JSONArray arr = new JSONArray();
    arr.put(clause("posted", "Y"));
    arr.put(clause("default", "N"));

    boolean changed = handler.normalizeBooleanCriteriaArray(arr, entity);

    assertTrue("at least one clause was rewritten", changed);
    assertEquals("String column untouched", "Y", arr.getJSONObject(0).get("value"));
    assertEquals("Boolean column rewritten", Boolean.FALSE, arr.getJSONObject(1).get("value"));
  }

  /** TC-10: Array with no Boolean-typed Y/N clauses → returns false, nothing rewritten. */
  @Test
  public void testArrayWithNoRewritesReturnsFalse() throws Exception {
    Property strProp = mock(Property.class);
    when(strProp.getPrimitiveObjectType()).thenReturn((Class) String.class);

    Entity entity = mock(Entity.class);
    when(entity.getProperty("posted", false)).thenReturn(strProp);
    when(entity.getProperties()).thenReturn(Collections.emptyList());

    JSONArray arr = new JSONArray();
    arr.put(clause("posted", "Y"));

    boolean changed = handler.normalizeBooleanCriteriaArray(arr, entity);

    assertFalse(changed);
    assertEquals("Y", arr.getJSONObject(0).get("value"));
  }
}
