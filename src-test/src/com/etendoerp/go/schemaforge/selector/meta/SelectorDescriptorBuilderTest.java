/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.selector.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Validation;

import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * Unit tests for {@link SelectorDescriptorBuilder}.
 */
class SelectorDescriptorBuilderTest {

  private static MockedStatic<NeoSelectorService> neoSelectorServiceMock;

  @BeforeAll
  static void setUpClass() {
    neoSelectorServiceMock = Mockito.mockStatic(NeoSelectorService.class);
  }

  @AfterAll
  static void tearDownClass() {
    neoSelectorServiceMock.close();
  }

  private static Column columnWithName(String dbColumnName) {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    when(column.getValidation()).thenReturn(null);
    return column;
  }

  private static Column columnWithValidation(String dbColumnName, String validationCode) {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    Validation validation = mock(Validation.class);
    when(validation.getValidationCode()).thenReturn(validationCode);
    when(column.getValidation()).thenReturn(validation);
    return column;
  }

  private static SelectorMeta simpleMeta(String entityName, String displayProperty) {
    return new SelectorMeta(entityName, displayProperty, null);
  }

  private static SelectorMeta richMeta(String entityName, String displayProperty) {
    return new SelectorMeta.Builder(entityName, displayProperty)
        .isRich(true)
        .build();
  }

  private static SelectorMeta metaWithAuxFields(String entityName, String displayProperty,
      List<AuxFieldMeta> auxFields) {
    return new SelectorMeta.Builder(entityName, displayProperty)
        .auxFields(auxFields)
        .build();
  }

  @Nested
  @DisplayName("buildListSelectorItem")
  class BuildListSelectorItem {

    @Test
    @DisplayName("Returns JSON with columnName, referenceType=List, type=list")
    void returnsCorrectJsonStructure() throws Exception {
      Column column = columnWithName("C_BPartner_ID");

      JSONObject result = SelectorDescriptorBuilder.buildListSelectorItem(column);

      assertEquals("C_BPartner_ID", result.getString("columnName"));
      assertEquals("List", result.getString("referenceType"));
      assertEquals("list", result.getString("type"));
      assertEquals(3, result.length(), "Should contain exactly three keys");
    }

    @Test
    @DisplayName("Uses the column DB name directly")
    void usesColumnDbName() throws Exception {
      Column column = columnWithName("AD_Org_ID");

      JSONObject result = SelectorDescriptorBuilder.buildListSelectorItem(column);

      assertEquals("AD_Org_ID", result.getString("columnName"));
    }
  }

  @Nested
  @DisplayName("buildSelectorItem — rich selector")
  class BuildSelectorItemRich {

    @Test
    @DisplayName("Rich selector produces referenceType=OBUISEL and type=rich")
    void richSelectorReferenceType() throws Exception {
      Column column = columnWithName("M_Product_ID");
      SelectorMeta meta = richMeta("Product", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertEquals("OBUISEL", result.getString("referenceType"));
      assertEquals("rich", result.getString("type"));
    }

    @Test
    @DisplayName("Rich selector populates targetEntity and displayProperty")
    void richSelectorEntityAndDisplay() throws Exception {
      Column column = columnWithName("M_Product_ID");
      SelectorMeta meta = richMeta("Product", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertEquals("Product", result.getString("targetEntity"));
      assertEquals("name", result.getString("displayProperty"));
    }
  }

  @Nested
  @DisplayName("buildSelectorItem — simple FK selector")
  class BuildSelectorItemSimple {

    @Test
    @DisplayName("Simple selector with refId=18 produces referenceType=Table")
    void simpleSelectorTable() throws Exception {
      Column column = columnWithName("C_BPartner_ID");
      SelectorMeta meta = simpleMeta("BusinessPartner", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertEquals("Table", result.getString("referenceType"));
      assertEquals("simple", result.getString("type"));
    }

    @Test
    @DisplayName("Simple selector with refId=19 produces referenceType=TableDir")
    void simpleSelectorTableDir() throws Exception {
      Column column = columnWithName("C_BPartner_ID");
      SelectorMeta meta = simpleMeta("BusinessPartner", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "19", meta, new HashSet<>());

      assertEquals("TableDir", result.getString("referenceType"));
      assertEquals("simple", result.getString("type"));
    }

    @Test
    @DisplayName("Simple selector with unknown refId produces referenceType=Search")
    void simpleSelectorSearch() throws Exception {
      Column column = columnWithName("C_BPartner_ID");
      SelectorMeta meta = simpleMeta("BusinessPartner", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "30", meta, new HashSet<>());

      assertEquals("Search", result.getString("referenceType"));
      assertEquals("simple", result.getString("type"));
    }
  }

  @Nested
  @DisplayName("buildSelectorItem — auxFields")
  class BuildSelectorItemAuxFields {

    @Test
    @DisplayName("AuxFields are included when present")
    void auxFieldsIncluded() throws Exception {
      List<AuxFieldMeta> auxFields = new ArrayList<>();
      auxFields.add(new AuxFieldMeta("_LOC", "loc", "Location", "location.name"));
      auxFields.add(new AuxFieldMeta("_CTY", "cty", "Country", "country.name"));

      Column column = columnWithName("C_BPartner_Location_ID");
      SelectorMeta meta = metaWithAuxFields("BPLocation", "name", auxFields);

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "30", meta, new HashSet<>());

      assertTrue(result.has("auxFields"));
      JSONArray auxArray = result.getJSONArray("auxFields");
      assertEquals(2, auxArray.length());
      assertEquals("_LOC", auxArray.getJSONObject(0).getString("suffix"));
      assertEquals("Location", auxArray.getJSONObject(0).getString("name"));
      assertEquals("_CTY", auxArray.getJSONObject(1).getString("suffix"));
      assertEquals("Country", auxArray.getJSONObject(1).getString("name"));
    }

    @Test
    @DisplayName("AuxFields key is absent when list is empty")
    void auxFieldsAbsentWhenEmpty() throws Exception {
      Column column = columnWithName("C_BPartner_ID");
      SelectorMeta meta = simpleMeta("BusinessPartner", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertFalse(result.has("auxFields"));
    }
  }

  @Nested
  @DisplayName("buildSelectorItem — selectorParams via extractSelectorParams")
  class BuildSelectorItemParams {

    @Test
    @DisplayName("Params are extracted from validation code")
    void paramsExtracted() throws Exception {
      Column column = columnWithValidation("M_Product_ID",
          "e.organization.id = @AD_Org_ID@ AND e.active = @IsActive@");
      SelectorMeta meta = simpleMeta("Product", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertTrue(result.has("selectorParams"));
      JSONArray params = result.getJSONArray("selectorParams");
      assertEquals(2, params.length());
      Set<String> paramSet = new HashSet<>();
      for (int i = 0; i < params.length(); i++) {
        paramSet.add(params.getString(i));
      }
      assertTrue(paramSet.contains("AD_Org_ID"));
      assertTrue(paramSet.contains("IsActive"));
    }

    @Test
    @DisplayName("Session params (#) are excluded")
    void sessionParamsExcluded() throws Exception {
      Column column = columnWithValidation("M_Product_ID",
          "e.org = @#AD_Org_ID@ AND e.client = @#AD_Client_ID@ AND e.name = @ProductName@");
      SelectorMeta meta = simpleMeta("Product", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertTrue(result.has("selectorParams"));
      JSONArray params = result.getJSONArray("selectorParams");
      assertEquals(1, params.length());
      assertEquals("ProductName", params.getString(0));
    }

    @Test
    @DisplayName("Known session params are excluded even without # prefix")
    void knownSessionParamsExcluded() throws Exception {
      Column column = columnWithValidation("M_Product_ID",
          "e.org = @AD_Org_ID@ AND e.warehouse = @M_Warehouse_ID@");
      SelectorMeta meta = simpleMeta("Product", "name");
      Set<String> sessionParams = new HashSet<>();
      sessionParams.add("AD_Org_ID");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, sessionParams);

      assertTrue(result.has("selectorParams"));
      JSONArray params = result.getJSONArray("selectorParams");
      assertEquals(1, params.length());
      assertEquals("M_Warehouse_ID", params.getString(0));
    }

    @Test
    @DisplayName("Duplicate params are filtered")
    void duplicatesFiltered() throws Exception {
      Column column = columnWithValidation("M_Product_ID",
          "e.org = @AD_Org_ID@ AND e.org2 = @AD_Org_ID@ AND e.name = @Name@");
      SelectorMeta meta = simpleMeta("Product", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertTrue(result.has("selectorParams"));
      JSONArray params = result.getJSONArray("selectorParams");
      assertEquals(2, params.length());
      Set<String> paramSet = new HashSet<>();
      for (int i = 0; i < params.length(); i++) {
        paramSet.add(params.getString(i));
      }
      assertTrue(paramSet.contains("AD_Org_ID"));
      assertTrue(paramSet.contains("Name"));
    }

    @Test
    @DisplayName("No selectorParams key when validation rule is null")
    void noParamsWhenNoValidation() throws Exception {
      Column column = columnWithName("M_Product_ID");
      SelectorMeta meta = simpleMeta("Product", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertFalse(result.has("selectorParams"));
    }

    @Test
    @DisplayName("No selectorParams key when validation code is blank")
    void noParamsWhenBlankValidation() throws Exception {
      Column column = columnWithValidation("M_Product_ID", "   ");
      SelectorMeta meta = simpleMeta("Product", "name");

      JSONObject result = SelectorDescriptorBuilder.buildSelectorItem(
          column, "18", meta, new HashSet<>());

      assertFalse(result.has("selectorParams"));
    }
  }
}
