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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFField;

/**
 * ETP-5852: {@link NeoDefaultsService#applyRecordContext} — the values the caller already knows
 * must be on the record before the callout cascade derives the fields that depend on them (a
 * document's business partner → its payment terms), and only real fields of the entity count.
 *
 * @covers com.etendoerp.go.schemaforge.NeoDefaultsRecordContext
 */
class NeoDefaultsRecordContextTest {

  private static SFField field(Entity entity, String column, String property) {
    Column adColumn = mock(Column.class);
    when(adColumn.getDBColumnName()).thenReturn(column);
    SFField sfField = mock(SFField.class);
    when(sfField.getADColumn()).thenReturn(adColumn);
    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn(property);
    when(prop.isPrimitive()).thenReturn(true);
    when(entity.getPropertyByColumnName(column)).thenReturn(prop);
    when(entity.getProperty(property)).thenReturn(prop);
    return sfField;
  }

  private static NeoContext context(Map<String, String> params) {
    return NeoContext.builder().queryParams(params).build();
  }

  @Test
  void contextValueReplacesTheGenericDefaultAndIsProtectedFromTheCascade() throws Exception {
    Entity entity = mock(Entity.class);
    List<SFField> fields = List.of(field(entity, "C_BPartner_ID", "businessPartner"),
        field(entity, "C_PaymentTerm_ID", "paymentTerms"));
    JSONObject defaults = new JSONObject().put("paymentTerms", "generic-term")
        .put("businessPartner$_identifier", "stale");

    Set<String> applied = NeoDefaultsRecordContext.apply(
        context(Map.of("businessPartner", "bp-1")), fields, entity, defaults);

    assertEquals(Set.of("businessPartner"), applied);
    assertEquals("bp-1", defaults.getString("businessPartner"));
    assertFalse(defaults.has("businessPartner$_identifier"),
        "a label of the previous value must not survive next to the new id");
    assertEquals("generic-term", defaults.getString("paymentTerms"),
        "dependent fields are left to the cascade, not cleared here");
  }

  @Test
  void keysThatAreNotFieldsOfTheEntityAreIgnored() throws Exception {
    Entity entity = mock(Entity.class);
    List<SFField> fields = List.of(field(entity, "C_BPartner_ID", "businessPartner"));
    JSONObject defaults = new JSONObject();

    Set<String> applied = NeoDefaultsRecordContext.apply(context(Map.of("view", "minimal",
        "noSuchField", "x", "businessPartner", "")), fields, entity, defaults);

    assertTrue(applied.isEmpty());
    assertEquals(0, defaults.length());
  }

  @Test
  void noQueryParamsChangesNothing() throws Exception {
    Entity entity = mock(Entity.class);
    JSONObject defaults = new JSONObject().put("paymentTerms", "generic-term");

    assertTrue(NeoDefaultsRecordContext.apply(context(Map.of()),
        List.of(field(entity, "C_BPartner_ID", "businessPartner")), entity, defaults).isEmpty());
    assertEquals(1, defaults.length());
  }
}
