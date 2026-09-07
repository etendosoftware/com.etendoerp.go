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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Image;

/**
 * Unit tests for {@link McpImageFieldSupport} (ETP-5184).
 *
 * <p><b>Generic-by-type is the property under test.</b> Every case here is driven by the AD
 * reference id alone: the same assertions are run against {@code M_Product.AD_Image_ID} and against
 * {@code AD_OrgInfo.Your_Company_Document_Image}, which is the proof that no window-specific code
 * exists and that enabling another image column needs none.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpImageFieldSupportTest {

  private static final String EXISTING_IMAGE_ID = "95E2A8B50A254B2AAE6774B8C2F28120";
  private static final String MISSING_IMAGE_ID = "00000000000000000000000000000000";

  /**
   * Builds a tab carrying one image column plus one ordinary text column, so every test also proves
   * the guard leaves non-image fields alone.
   *
   * <p>DB column names only: a {@code Tab} models the AD side, where columns are identified by
   * {@code getDBColumnName()}. The DAL property a column maps to belongs to the {@code Entity},
   * which {@link #entityMapping} builds — so this helper took a {@code propertyName} it could not
   * use, making its call site read as if it constrained the mapping (S1172).
   *
   * @param dbColumnName the image column's DB name
   */
  private static Tab tabWithImageColumn(String dbColumnName) {
    Reference imageRef = mock(Reference.class);
    when(imageRef.getId()).thenReturn(McpConstants.REF_IMAGE_BLOB);
    Column imageColumn = mock(Column.class);
    when(imageColumn.isActive()).thenReturn(true);
    when(imageColumn.getReference()).thenReturn(imageRef);
    when(imageColumn.getDBColumnName()).thenReturn(dbColumnName);

    Reference textRef = mock(Reference.class);
    when(textRef.getId()).thenReturn("10");
    Column textColumn = mock(Column.class);
    when(textColumn.isActive()).thenReturn(true);
    when(textColumn.getReference()).thenReturn(textRef);
    when(textColumn.getDBColumnName()).thenReturn("Description");

    Table table = mock(Table.class);
    when(table.getADColumnList()).thenReturn(List.of(imageColumn, textColumn));
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    return tab;
  }

  private static Entity entityMapping(String dbColumnName, String propertyName) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(propertyName);
    Entity entity = mock(Entity.class);
    when(entity.getPropertyByColumnName(eq(dbColumnName), eq(false))).thenReturn(property);
    return entity;
  }

  /** Runs {@code validateImageFields} with {@code EXISTING_IMAGE_ID} as the only known image row. */
  private static JSONObject validate(JSONObject body, String dbColumnName, String propertyName)
      throws Exception {
    Tab tab = tabWithImageColumn(dbColumnName);
    Entity entity = entityMapping(dbColumnName, propertyName);
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(eq(Image.class), any())).thenReturn(null);
      when(obDal.get(eq(Image.class), eq(EXISTING_IMAGE_ID))).thenReturn(mock(Image.class));
      return McpImageFieldSupport.validateImageFields(body, tab, entity);
    }
  }

  /**
   * The generic-by-type proof: the identical body/assertion pair on the product image
   * ({@code M_Product.AD_Image_ID}) and on the organization logo
   * ({@code AD_OrgInfo.Your_Company_Document_Image}), with no per-window configuration anywhere.
   */
  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> imageFields() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("AD_Image_ID", "image"),
        org.junit.jupiter.params.provider.Arguments.of(
            "Your_Company_Document_Image", "yourCompanyDocumentImage"));
  }

  @Nested
  @DisplayName("decorateImageField")
  class DecorateImageField {

    @Test
    @DisplayName("turns an untyped field into a self-describing one")
    void emitsFormatAndGuidance() throws Exception {
      JSONObject field = new JSONObject();
      field.put("type", McpConstants.TYPE_IMAGE);
      McpImageFieldSupport.decorateImageField(field);

      assertEquals(McpConstants.FORMAT_IMAGE_ID, field.getString("format"));
      assertEquals(McpConstants.TYPE_STRING, field.getString("valueType"));
      assertNotNull(field.getString(McpConstants.KEY_HINT));
      assertNotNull(field.getString(McpConstants.KEY_DESCRIPTION));
    }

    @Test
    @DisplayName("the guidance forbids base64 and URLs and names both upload tools")
    void guidanceIsActionable() {
      String hint = McpImageFieldSupport.IMAGE_FIELD_HINT;
      assertTrue(hint.contains("base64"), hint);
      assertTrue(hint.contains("URL"), hint);
      assertTrue(hint.contains(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD), hint);
      assertTrue(hint.contains(McpConstants.TOOL_NEO_UPLOAD_IMAGE), hint);
      assertTrue(hint.indexOf(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD)
              < hint.indexOf(McpConstants.TOOL_NEO_UPLOAD_IMAGE),
          "the cheap path must be named first: " + hint);
    }

    @Test
    @DisplayName("a null field is tolerated")
    void nullFieldIsTolerated() throws Exception {
      McpImageFieldSupport.decorateImageField(null);
    }
  }

  @Nested
  @DisplayName("mapColumnType wiring")
  class TypeWiring {

    @Test
    @DisplayName("the Image BLOB reference maps to the image type, not to string")
    void imageReferenceMapsToImage() {
      assertEquals(McpConstants.TYPE_IMAGE,
          McpSchemaFieldBuilder.mapColumnType(McpConstants.REF_IMAGE_BLOB));
    }

    @Test
    @DisplayName("the reference is recognised by isImageReference and nothing else is")
    void referenceRecognition() {
      assertTrue(McpImageFieldSupport.isImageReference(McpConstants.REF_IMAGE_BLOB));
      assertFalse(McpImageFieldSupport.isImageReference("19"));
      assertFalse(McpImageFieldSupport.isImageReference(null));
    }
  }

  @Nested
  @DisplayName("isImageIdShaped")
  class IsImageIdShaped {

    @ParameterizedTest
    @ValueSource(strings = {
        "95E2A8B50A254B2AAE6774B8C2F28120",
        "95e2a8b50a254b2aae6774b8c2f28120" })
    void acceptsA32CharHexId(String value) {
      assertTrue(McpImageFieldSupport.isImageIdShaped(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "1",
        "95E2A8B50A254B2AAE6774B8C2F2812",
        "95E2A8B50A254B2AAE6774B8C2F281200",
        "95E2A8B5-0A25-4B2A-AE67-74B8C2F2812",
        "ZZE2A8B50A254B2AAE6774B8C2F28120" })
    void rejectsAnythingElse(String value) {
      assertFalse(McpImageFieldSupport.isImageIdShaped(value));
    }

    @Test
    void rejectsNull() {
      assertFalse(McpImageFieldSupport.isImageIdShaped(null));
    }
  }

  @Nested
  @DisplayName("validateImageFields — the write guard")
  class ValidateImageFields {

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource(
        "com.etendoerp.go.mcp.McpImageFieldSupportTest#imageFields")
    @DisplayName("an existing image id is accepted")
    void existingIdIsAccepted(String column, String property) throws Exception {
      JSONObject body = new JSONObject();
      body.put(property, EXISTING_IMAGE_ID);
      assertNull(validate(body, column, property));
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource(
        "com.etendoerp.go.mcp.McpImageFieldSupportTest#imageFields")
    @DisplayName("a base64 payload is refused with an error that names the upload tools")
    void base64IsRefusedSelfCorrectably(String column, String property) throws Exception {
      JSONObject body = new JSONObject();
      body.put(property, "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==");
      JSONObject error = validate(body, column, property);

      assertNotNull(error, "a base64 payload must not reach the DAL");
      assertEquals(McpConstants.STATUS_UNPROCESSABLE, error.getInt(McpConstants.KEY_STATUS));
      assertEquals(McpConstants.ERROR_INVALID_IMAGE_REFERENCE,
          error.getString(McpConstants.KEY_ERROR));
      String hint = error.getString(McpConstants.KEY_HINT);
      assertTrue(hint.contains(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD), hint);
      assertTrue(hint.contains(McpConstants.TOOL_NEO_UPLOAD_IMAGE), hint);
      JSONArray rejected = error.getJSONArray("invalidImageFields");
      assertEquals(1, rejected.length());
      assertEquals(property, rejected.getJSONObject(0).getString("field"));
      assertEquals("base64_data_uri", rejected.getJSONObject(0).getString("reason"));
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource(
        "com.etendoerp.go.mcp.McpImageFieldSupportTest#imageFields")
    @DisplayName("a URL is refused and reported as such")
    void urlIsRefused(String column, String property) throws Exception {
      JSONObject body = new JSONObject();
      body.put(property, "https://example.test/logo.png");
      JSONObject error = validate(body, column, property);
      assertNotNull(error);
      assertEquals("url",
          error.getJSONArray("invalidImageFields").getJSONObject(0).getString("reason"));
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource(
        "com.etendoerp.go.mcp.McpImageFieldSupportTest#imageFields")
    @DisplayName("a well-shaped id that names no row is refused")
    void unknownIdIsRefused(String column, String property) throws Exception {
      JSONObject body = new JSONObject();
      body.put(property, MISSING_IMAGE_ID);
      JSONObject error = validate(body, column, property);
      assertNotNull(error);
      assertEquals("unknown_image_id",
          error.getJSONArray("invalidImageFields").getJSONObject(0).getString("reason"));
    }

    @Test
    @DisplayName("the DB column name is guarded too, not just the DAL property name")
    void dbColumnNameIsGuarded() throws Exception {
      JSONObject body = new JSONObject();
      body.put("AD_Image_ID", "not-an-id");
      assertNotNull(validate(body, "AD_Image_ID", "image"),
          "mapFieldsToDalProperties passes an unmapped key through untouched, so both spellings "
              + "have to be checked");
    }

    @Test
    @DisplayName("a blank value is allowed — clearing an image is a legitimate write")
    void blankValueClearsTheField() throws Exception {
      JSONObject body = new JSONObject();
      body.put("image", "");
      assertNull(validate(body, "AD_Image_ID", "image"));
    }

    @Test
    @DisplayName("non-image fields are never inspected")
    void otherFieldsAreUntouched() throws Exception {
      JSONObject body = new JSONObject();
      body.put("description", "data:image/png;base64,AAAA");
      assertNull(validate(body, "AD_Image_ID", "image"));
    }

    @Test
    @DisplayName("a tab with no image column short-circuits without touching the DAL")
    void noImageColumnIsANoOp() throws Exception {
      Reference textRef = mock(Reference.class);
      when(textRef.getId()).thenReturn("10");
      Column textColumn = mock(Column.class);
      when(textColumn.isActive()).thenReturn(true);
      when(textColumn.getReference()).thenReturn(textRef);
      when(textColumn.getDBColumnName()).thenReturn("Description");
      Table table = mock(Table.class);
      when(table.getADColumnList()).thenReturn(List.of(textColumn));
      Tab tab = mock(Tab.class);
      when(tab.getTable()).thenReturn(table);

      JSONObject body = new JSONObject();
      body.put("description", "anything");
      assertNull(McpImageFieldSupport.validateImageFields(body, tab, null));
    }

    @Test
    @DisplayName("a null body or tab is tolerated")
    void nullInputsAreTolerated() throws Exception {
      assertNull(McpImageFieldSupport.validateImageFields(null, null, null));
      assertNull(McpImageFieldSupport.validateImageFields(new JSONObject(), null, null));
    }
  }
}
