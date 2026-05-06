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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link MissingRequiredFieldsException} and the pure-data branch of
 * {@link NeoDefaultsService#findMissingMandatoryFields}.
 *
 * <p>These tests do not require a database. Tests that exercise DAL/Tab introspection
 * are covered by the manual smoke matrix listed in the ETP-3894 plan.</p>
 */
public class MissingRequiredFieldsExceptionTest {

  /**
   * Verifies that the field list is stored and accessible via {@code getFields()},
   * and that the exception message contains the error code.
   */
  @Test
  public void testExceptionExposesFieldList() {
    MissingRequiredFieldsException ex = new MissingRequiredFieldsException(Arrays.asList("bpartner", "priceList"));
    assertNotNull(ex.getFields());
    assertEquals(2, ex.getFields().size());
    assertTrue(ex.getFields().contains("bpartner"));
    assertTrue(ex.getFields().contains("priceList"));
    assertTrue(ex.getMessage().contains(MissingRequiredFieldsException.ERROR_CODE));
  }

  /**
   * Verifies that passing {@code null} as the field list is treated as an empty list
   * rather than propagating a NullPointerException.
   */
  @Test
  public void testExceptionTreatsNullAsEmptyList() {
    MissingRequiredFieldsException ex = new MissingRequiredFieldsException(null);
    assertNotNull(ex.getFields());
    assertEquals(0, ex.getFields().size());
  }

  /**
   * Verifies that {@code getFields()} returns an unmodifiable view so callers
   * cannot mutate the internal field list after construction.
   */
  @Test
  public void testExceptionFieldListIsImmutable() {
    MissingRequiredFieldsException ex = new MissingRequiredFieldsException(Collections.singletonList("contact"));
    try {
      ex.getFields().add("priceList");
      fail("Expected UnsupportedOperationException — getFields() must return an unmodifiable view");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  /**
   * Verifies that a {@code null} body returns an empty list without throwing,
   * even before the DAL is initialized.
   */
  @Test
  public void testFindMissingMandatoryFieldsHandlesNullBody() {
    java.util.List<String> result = NeoDefaultsService.findMissingMandatoryFields(null, null);
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  /**
   * Verifies that a {@code null} tab returns an empty list without throwing,
   * exercising the fast-path guard at the top of the method.
   */
  @Test
  public void testFindMissingMandatoryFieldsHandlesNullTab() {
    java.util.List<String> result = NeoDefaultsService.findMissingMandatoryFields(new JSONObject(), null);
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  /**
   * Verifies that the fast-path null-tab check is not bypassed when
   * {@code userSubmittedFields} is provided — the method must return empty
   * without attempting DAL access.
   */
  @Test
  public void testFindMissingMandatoryFieldsWithUserSubmittedFieldsHandlesNullTab() {
    java.util.Set<String> submitted = new java.util.HashSet<>(java.util.Arrays.asList("businessPartner", "priceList"));
    java.util.List<String> result = NeoDefaultsService.findMissingMandatoryFields(new JSONObject(), null, submitted);
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  /**
   * Verifies that the 2-param backward-compatible overload produces the same result
   * as calling the 3-param variant with {@code null} for the user-submission filter.
   */
  @Test
  public void testFindMissingMandatoryFieldsBackwardCompatOverloadDelegatesToThreeParam() {
    java.util.List<String> twoParam = NeoDefaultsService.findMissingMandatoryFields(new JSONObject(), null);
    java.util.List<String> threeParam = NeoDefaultsService.findMissingMandatoryFields(new JSONObject(), null, null);
    assertEquals(twoParam, threeParam);
  }
}
