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

  @Test
  public void exceptionExposesFieldList() {
    MissingRequiredFieldsException ex =
        new MissingRequiredFieldsException(Arrays.asList("bpartner", "priceList"));
    assertNotNull(ex.getFields());
    assertEquals(2, ex.getFields().size());
    assertTrue(ex.getFields().contains("bpartner"));
    assertTrue(ex.getFields().contains("priceList"));
    assertTrue(ex.getMessage().contains(MissingRequiredFieldsException.ERROR_CODE));
  }

  @Test
  public void exceptionTreatsNullAsEmptyList() {
    MissingRequiredFieldsException ex = new MissingRequiredFieldsException(null);
    assertNotNull(ex.getFields());
    assertEquals(0, ex.getFields().size());
  }

  @Test
  public void exceptionFieldListIsImmutable() {
    MissingRequiredFieldsException ex =
        new MissingRequiredFieldsException(Collections.singletonList("contact"));
    try {
      ex.getFields().add("priceList");
      fail("Expected UnsupportedOperationException — getFields() must return an unmodifiable view");
    } catch (UnsupportedOperationException expected) {
      // ok
    }
  }

  @Test
  public void findMissingMandatoryFieldsHandlesNullBody() {
    // Should never throw on null inputs, even before DAL is initialized.
    java.util.List<String> result = NeoDefaultsService.findMissingMandatoryFields(null, null);
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void findMissingMandatoryFieldsHandlesNullTab() {
    java.util.List<String> result =
        NeoDefaultsService.findMissingMandatoryFields(new JSONObject(), null);
    assertNotNull(result);
    assertEquals(0, result.size());
  }
}
