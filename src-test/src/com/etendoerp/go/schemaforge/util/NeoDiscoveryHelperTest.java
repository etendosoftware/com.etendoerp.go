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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.domain.Validation;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Element;
import org.openbravo.model.ad.ui.ElementTrl;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoDiscoveryHelper}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoDiscoveryHelperTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obCtxMock;
  private OBDal dal;
  private OBContext obContext;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obContext = mock(OBContext.class);
    obDalMock = mockStatic(OBDal.class);
    obCtxMock = mockStatic(OBContext.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    obCtxMock.when(OBContext::getOBContext).thenReturn(obContext);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obCtxMock.close();
  }

  @SuppressWarnings("unchecked")
  private <T extends BaseOBObject> OBCriteria<T> mockCriteria() {
    OBCriteria<T> criteria = mock(OBCriteria.class);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    return criteria;
  }

  private SFSpec createMockSpec(String id, String name, String type, String desc) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn(id);
    when(spec.getName()).thenReturn(name);
    when(spec.getSpecType()).thenReturn(type);
    when(spec.getDescription()).thenReturn(desc);
    return spec;
  }

  private SFEntity createMockEntity(String id, String name, boolean get, boolean getById,
      boolean post, boolean put, boolean patch, boolean delete) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn(id);
    when(entity.getName()).thenReturn(name);
    when(entity.isGet()).thenReturn(get);
    when(entity.isGetByID()).thenReturn(getById);
    when(entity.isPost()).thenReturn(post);
    when(entity.isPut()).thenReturn(put);
    when(entity.isPatch()).thenReturn(patch);
    when(entity.isDelete()).thenReturn(delete);
    return entity;
  }

  @Nested
  @DisplayName("handleDiscovery")
  class HandleDiscovery {

    @Test
    @DisplayName("should return ok response with empty specs when no specs exist")
    void shouldReturnEmptySpecs() throws Exception {
      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertNotNull(body);
      assertEquals(0, body.getJSONArray("specs").length());
    }

    @Test
    @DisplayName("should return window spec with entities")
    void shouldReturnWindowSpec() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Order management");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("win-1");
      when(spec.getADWindow()).thenReturn(window);
      Module module = mock(Module.class);
      when(module.getId()).thenReturn("mod-1");
      when(spec.getADModule()).thenReturn(module);

      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));

      // Mock entity summary criteria
      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      SFEntity entity = createMockEntity("ent1", "Order", true, false, true, false, false, false);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
        accessMock.when(() -> NeoAccessHelper.hasWindowAccess("win-1")).thenReturn(true);

        NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

        assertEquals(200, response.getHttpStatus());
        JSONObject body = response.getBody();
        JSONArray specs = body.getJSONArray("specs");
        assertEquals(1, specs.length());
        JSONObject specObj = specs.getJSONObject(0);
        assertEquals("spec1", specObj.getString("id"));
        assertEquals("Orders", specObj.getString("name"));
        assertEquals("W", specObj.getString("type"));
        assertEquals("win-1", specObj.getString("windowId"));
        assertEquals("mod-1", specObj.getString("moduleId"));
      }
    }

    @Test
    @DisplayName("should return process spec with processId")
    void shouldReturnProcessSpec() throws Exception {
      SFSpec spec = createMockSpec("spec2", "Generate Invoice", "P", "Invoice process");
      when(spec.getADWindow()).thenReturn(null);
      when(spec.getADModule()).thenReturn(null);

      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn("proc-1");

      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));

      try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
        accessMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);
        accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);

        NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

        assertEquals(200, response.getHttpStatus());
        JSONArray specs = response.getBody().getJSONArray("specs");
        assertEquals(1, specs.length());
        JSONObject specObj = specs.getJSONObject(0);
        assertEquals("proc-1", specObj.getString("processId"));
        assertFalse(specObj.has("isReport"));
      }
    }

    @Test
    @DisplayName("should return report spec with isReport flag")
    void shouldReturnReportSpec() throws Exception {
      SFSpec spec = createMockSpec("spec3", "Sales Report", "R", "Sales report");
      when(spec.getADWindow()).thenReturn(null);
      when(spec.getADModule()).thenReturn(null);

      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn("proc-2");

      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));

      try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
        accessMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);
        accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-2")).thenReturn(true);

        NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

        assertEquals(200, response.getHttpStatus());
        JSONObject specObj = response.getBody().getJSONArray("specs").getJSONObject(0);
        assertTrue(specObj.getBoolean("isReport"));
      }
    }

    @Test
    @DisplayName("should skip inaccessible window specs")
    void shouldSkipInaccessibleWindowSpecs() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Secret", "W", "No access");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("win-secret");
      when(spec.getADWindow()).thenReturn(window);

      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));

      try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
        accessMock.when(() -> NeoAccessHelper.hasWindowAccess("win-secret")).thenReturn(false);

        NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

        assertEquals(200, response.getHttpStatus());
        assertEquals(0, response.getBody().getJSONArray("specs").length());
      }
    }

    @Test
    @DisplayName("should skip inaccessible process specs")
    void shouldSkipInaccessibleProcessSpecs() throws Exception {
      SFSpec spec = createMockSpec("spec2", "Secret Process", "P", "No access");

      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn("proc-secret");

      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));

      try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
        accessMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);
        accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-secret")).thenReturn(false);

        NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

        assertEquals(200, response.getHttpStatus());
        assertEquals(0, response.getBody().getJSONArray("specs").length());
      }
    }

    @Test
    @DisplayName("should allow spec with null window (accessible by default)")
    void shouldAllowSpecWithNullWindow() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Open", "W", "Open spec");
      when(spec.getADWindow()).thenReturn(null);
      when(spec.getADModule()).thenReturn(null);

      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

      assertEquals(200, response.getHttpStatus());
      assertEquals(1, response.getBody().getJSONArray("specs").length());
    }

    @Test
    @DisplayName("should allow unknown spec type (accessible by default)")
    void shouldAllowUnknownSpecType() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Custom", "X", "Unknown type");
      when(spec.getADModule()).thenReturn(null);

      OBCriteria<SFSpec> specCriteria = mockCriteria();
      when(dal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.singletonList(spec));

      NeoResponse response = NeoDiscoveryHelper.handleDiscovery();

      assertEquals(200, response.getHttpStatus());
      assertEquals(1, response.getBody().getJSONArray("specs").length());
    }
  }

  @Nested
  @DisplayName("handleSpecDescribe")
  class HandleSpecDescribe {

    @Test
    @DisplayName("should describe spec with entities and fields")
    void shouldDescribeSpecWithEntities() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Order spec");
      Module module = mock(Module.class);
      when(module.getId()).thenReturn("mod-1");
      when(spec.getADModule()).thenReturn(module);

      SFEntity entity = createMockEntity("ent1", "Order", true, true, true, true, true, true);
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);
      when(adTab.getId()).thenReturn("tab-1");
      when(entity.getADTab()).thenReturn(adTab);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      // Mock fields criteria
      OBCriteria<SFField> fieldCriteria = mockCriteria();
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.emptyList());

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertEquals("spec1", body.getString("id"));
      assertEquals("Orders", body.getString("name"));
      assertEquals("mod-1", body.getString("moduleId"));

      JSONArray entities = body.getJSONArray("entities");
      assertEquals(1, entities.length());
      JSONObject entityObj = entities.getJSONObject(0);
      assertEquals("ent1", entityObj.getString("id"));
      assertEquals("Order", entityObj.getString("name"));
      assertEquals(0, entityObj.getLong("tabLevel"));
      assertEquals("tab-1", entityObj.getString("tabId"));
      assertTrue(entityObj.getBoolean("isGet"));
      assertTrue(entityObj.getBoolean("isPost"));
      assertTrue(entityObj.getBoolean("isPut"));
      assertTrue(entityObj.getBoolean("isPatch"));
      assertTrue(entityObj.getBoolean("isDelete"));
    }

    @Test
    @DisplayName("should describe spec without module")
    void shouldDescribeSpecWithoutModule() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      assertFalse(response.getBody().has("moduleId"));
    }

    @Test
    @DisplayName("should handle entity without tab")
    void shouldHandleEntityWithoutTab() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.emptyList());

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONObject entityObj = response.getBody().getJSONArray("entities").getJSONObject(0);
      assertFalse(entityObj.has("tabLevel"));
      assertFalse(entityObj.has("tabId"));
    }

    @Test
    @DisplayName("should include field details with selector info")
    void shouldIncludeFieldWithSelector() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      // Mock field with selector reference (TableDir = 19)
      SFField field = mock(SFField.class);
      when(field.getId()).thenReturn("field-1");
      when(field.isReadOnly()).thenReturn(false);
      when(field.isIncluded()).thenReturn(true);

      Column column = mock(Column.class);
      when(column.getId()).thenReturn("col-1");
      when(column.getDBColumnName()).thenReturn("C_BPartner_ID");
      when(column.getName()).thenReturn("Business Partner");
      when(column.isMandatory()).thenReturn(true);
      when(column.getValidation()).thenReturn(null);
      when(column.getApplicationElement()).thenReturn(null);
      Reference ref = mock(Reference.class);
      when(ref.getId()).thenReturn("19");
      when(column.getReference()).thenReturn(ref);
      when(field.getADColumn()).thenReturn(column);

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.singletonList(field));

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONArray fields = response.getBody().getJSONArray("entities")
          .getJSONObject(0).getJSONArray("fields");
      assertEquals(1, fields.length());
      JSONObject fieldObj = fields.getJSONObject(0);
      assertEquals("field-1", fieldObj.getString("id"));
      assertEquals("col-1", fieldObj.getString("columnId"));
      assertEquals("C_BPartner_ID", fieldObj.getString("name"));
      assertEquals("Business Partner", fieldObj.getString("label"));
      assertEquals("string", fieldObj.getString("columnType"));
      assertFalse(fieldObj.getBoolean("readOnly"));
      assertTrue(fieldObj.getBoolean("required"));
      assertTrue(fieldObj.getBoolean("hasSelector"));
      assertEquals("TableDir", fieldObj.getString("selectorType"));
    }

    @Test
    @DisplayName("should skip field when column is null")
    void shouldSkipFieldWhenColumnNull() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      SFField fieldNoColumn = mock(SFField.class);
      when(fieldNoColumn.getADColumn()).thenReturn(null);

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.singletonList(fieldNoColumn));

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONArray fields = response.getBody().getJSONArray("entities")
          .getJSONObject(0).getJSONArray("fields");
      assertEquals(0, fields.length());
    }

    @Test
    @DisplayName("should include translated label when translation exists")
    void shouldIncludeTranslatedLabel() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      SFField field = mock(SFField.class);
      when(field.getId()).thenReturn("field-1");
      when(field.isReadOnly()).thenReturn(false);
      when(field.isIncluded()).thenReturn(true);

      Column column = mock(Column.class);
      when(column.getId()).thenReturn("col-1");
      when(column.getDBColumnName()).thenReturn("Name");
      when(column.getName()).thenReturn("Name");
      when(column.isMandatory()).thenReturn(false);
      when(column.getValidation()).thenReturn(null);
      when(column.getReference()).thenReturn(null);
      Element element = mock(Element.class);
      when(column.getApplicationElement()).thenReturn(element);
      when(field.getADColumn()).thenReturn(column);

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      // Mock ElementTrl criteria
      ElementTrl trl = mock(ElementTrl.class);
      when(trl.getName()).thenReturn("Nombre");

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      OBCriteria<ElementTrl> trlCriteria = mockCriteria();
      when(trlCriteria.uniqueResult()).thenReturn(trl);

      // Return field criteria first, then trl criteria
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(dal.createCriteria(ElementTrl.class)).thenReturn(trlCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.singletonList(field));

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONObject fieldObj = response.getBody().getJSONArray("entities")
          .getJSONObject(0).getJSONArray("fields").getJSONObject(0);
      assertEquals("Nombre", fieldObj.getString("label"));
    }

    @Test
    @DisplayName("should fallback to column name when no translation exists")
    void shouldFallbackToColumnName() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      SFField field = mock(SFField.class);
      when(field.getId()).thenReturn("field-1");
      when(field.isReadOnly()).thenReturn(true);
      when(field.isIncluded()).thenReturn(true);

      Column column = mock(Column.class);
      when(column.getId()).thenReturn("col-1");
      when(column.getDBColumnName()).thenReturn("Name");
      when(column.getName()).thenReturn("Name EN");
      when(column.isMandatory()).thenReturn(false);
      when(column.getValidation()).thenReturn(null);
      when(column.getReference()).thenReturn(null);
      Element element = mock(Element.class);
      when(column.getApplicationElement()).thenReturn(element);
      when(field.getADColumn()).thenReturn(column);

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      OBCriteria<ElementTrl> trlCriteria = mockCriteria();
      when(trlCriteria.uniqueResult()).thenReturn(null);

      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(dal.createCriteria(ElementTrl.class)).thenReturn(trlCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.singletonList(field));

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONObject fieldObj = response.getBody().getJSONArray("entities")
          .getJSONObject(0).getJSONArray("fields").getJSONObject(0);
      assertEquals("Name EN", fieldObj.getString("label"));
    }

    @Test
    @DisplayName("should fallback to column name when element is null")
    void shouldFallbackWhenElementNull() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      SFField field = mock(SFField.class);
      when(field.getId()).thenReturn("field-1");
      when(field.isReadOnly()).thenReturn(false);
      when(field.isIncluded()).thenReturn(true);

      Column column = mock(Column.class);
      when(column.getId()).thenReturn("col-1");
      when(column.getDBColumnName()).thenReturn("Name");
      when(column.getName()).thenReturn("Fallback Name");
      when(column.isMandatory()).thenReturn(false);
      when(column.getValidation()).thenReturn(null);
      when(column.getReference()).thenReturn(null);
      when(column.getApplicationElement()).thenReturn(null);
      when(field.getADColumn()).thenReturn(column);

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.singletonList(field));

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONObject fieldObj = response.getBody().getJSONArray("entities")
          .getJSONObject(0).getJSONArray("fields").getJSONObject(0);
      assertEquals("Fallback Name", fieldObj.getString("label"));
    }

    @Test
    @DisplayName("should include validation params for selector fields")
    void shouldIncludeValidationParams() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      SFField field = mock(SFField.class);
      when(field.getId()).thenReturn("field-1");
      when(field.isReadOnly()).thenReturn(false);
      when(field.isIncluded()).thenReturn(true);

      Column column = mock(Column.class);
      when(column.getId()).thenReturn("col-1");
      when(column.getDBColumnName()).thenReturn("C_BPartner_ID");
      when(column.getName()).thenReturn("Business Partner");
      when(column.isMandatory()).thenReturn(false);
      when(column.getApplicationElement()).thenReturn(null);
      Reference ref = mock(Reference.class);
      when(ref.getId()).thenReturn("18");
      when(column.getReference()).thenReturn(ref);

      Validation validation = mock(Validation.class);
      when(validation.getValidationCode()).thenReturn("e.org = @AD_Org_ID@");
      when(column.getValidation()).thenReturn(validation);
      when(field.getADColumn()).thenReturn(column);

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.singletonList(field));

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONObject fieldObj = response.getBody().getJSONArray("entities")
          .getJSONObject(0).getJSONArray("fields").getJSONObject(0);
      assertTrue(fieldObj.getBoolean("hasSelector"));
      assertEquals("Table", fieldObj.getString("selectorType"));
      JSONArray selectorParams = fieldObj.getJSONArray("selectorParams");
      assertEquals(1, selectorParams.length());
      assertEquals("AD_Org_ID", selectorParams.getString(0));
    }

    @Test
    @DisplayName("should handle non-selector field without selector info")
    void shouldHandleNonSelectorField() throws Exception {
      SFSpec spec = createMockSpec("spec1", "Orders", "W", "Desc");
      when(spec.getADModule()).thenReturn(null);

      SFEntity entity = createMockEntity("ent1", "Order", true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(null);

      OBCriteria<SFEntity> entityCriteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.singletonList(entity));

      SFField field = mock(SFField.class);
      when(field.getId()).thenReturn("field-1");
      when(field.isReadOnly()).thenReturn(false);
      when(field.isIncluded()).thenReturn(true);

      Column column = mock(Column.class);
      when(column.getId()).thenReturn("col-1");
      when(column.getDBColumnName()).thenReturn("Name");
      when(column.getName()).thenReturn("Name");
      when(column.isMandatory()).thenReturn(false);
      when(column.getValidation()).thenReturn(null);
      when(column.getApplicationElement()).thenReturn(null);
      Reference ref = mock(Reference.class);
      when(ref.getId()).thenReturn("10");
      when(column.getReference()).thenReturn(ref);
      when(field.getADColumn()).thenReturn(column);

      Language lang = mock(Language.class);
      when(obContext.getLanguage()).thenReturn(lang);

      OBCriteria<SFField> fieldCriteria = mockCriteria();
      when(dal.createCriteria(SFField.class)).thenReturn(fieldCriteria);
      when(fieldCriteria.list()).thenReturn(Collections.singletonList(field));

      NeoResponse response = NeoDiscoveryHelper.handleSpecDescribe(spec);

      assertEquals(200, response.getHttpStatus());
      JSONObject fieldObj = response.getBody().getJSONArray("entities")
          .getJSONObject(0).getJSONArray("fields").getJSONObject(0);
      assertFalse(fieldObj.getBoolean("hasSelector"));
      assertFalse(fieldObj.has("selectorType"));
      assertFalse(fieldObj.has("selectorParams"));
    }
  }

  @Nested
  @DisplayName("buildEntitySummaryArray")
  class BuildEntitySummaryArray {

    @Test
    @DisplayName("should return empty array when no entities")
    void shouldReturnEmptyArray() throws Exception {
      OBCriteria<SFEntity> criteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      JSONArray result = NeoDiscoveryHelper.buildEntitySummaryArray("spec-1");

      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("should return entities with names and methods")
    void shouldReturnEntitiesWithMethods() throws Exception {
      SFEntity entity1 = createMockEntity("e1", "Order", true, false, true, false, false, false);
      SFEntity entity2 = createMockEntity("e2", "OrderLine", true, false, false, false, true, true);

      OBCriteria<SFEntity> criteria = mockCriteria();
      when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Arrays.asList(entity1, entity2));

      JSONArray result = NeoDiscoveryHelper.buildEntitySummaryArray("spec-1");

      assertEquals(2, result.length());
      assertEquals("Order", result.getJSONObject(0).getString("name"));
      assertEquals("OrderLine", result.getJSONObject(1).getString("name"));

      JSONArray methods1 = result.getJSONObject(0).getJSONArray("methods");
      assertEquals(2, methods1.length());

      JSONArray methods2 = result.getJSONObject(1).getJSONArray("methods");
      assertEquals(3, methods2.length());
    }
  }

  @Nested
  @DisplayName("buildMethodsArray")
  class BuildMethodsArray {

    @Test
    @DisplayName("should return empty array when no methods are enabled")
    void shouldReturnEmptyWhenNoMethods() {
      SFEntity entity = createMockEntity("e1", "Test", false, false, false, false, false, false);

      JSONArray methods = NeoDiscoveryHelper.buildMethodsArray(entity);

      assertEquals(0, methods.length());
    }

    @Test
    @DisplayName("should include GET when isGet is true")
    void shouldIncludeGetWhenIsGetTrue() {
      SFEntity entity = createMockEntity("e1", "Test", true, false, false, false, false, false);

      JSONArray methods = NeoDiscoveryHelper.buildMethodsArray(entity);

      assertEquals(1, methods.length());
      assertEquals("GET", methods.opt(0));
    }

    @Test
    @DisplayName("should include GET when isGetByID is true")
    void shouldIncludeGetWhenIsGetByIdTrue() {
      SFEntity entity = createMockEntity("e1", "Test", false, true, false, false, false, false);

      JSONArray methods = NeoDiscoveryHelper.buildMethodsArray(entity);

      assertEquals(1, methods.length());
      assertEquals("GET", methods.opt(0));
    }

    @Test
    @DisplayName("should include GET only once when both isGet and isGetByID are true")
    void shouldIncludeGetOnlyOnce() {
      SFEntity entity = createMockEntity("e1", "Test", true, true, false, false, false, false);

      JSONArray methods = NeoDiscoveryHelper.buildMethodsArray(entity);

      assertEquals(1, methods.length());
    }

    @Test
    @DisplayName("should include all methods when all are enabled")
    void shouldIncludeAllMethods() {
      SFEntity entity = createMockEntity("e1", "Test", true, false, true, true, true, true);

      JSONArray methods = NeoDiscoveryHelper.buildMethodsArray(entity);

      assertEquals(5, methods.length());
      Set<String> methodSet = new HashSet<>();
      for (int i = 0; i < methods.length(); i++) {
        methodSet.add(methods.optString(i));
      }
      assertTrue(methodSet.contains("GET"));
      assertTrue(methodSet.contains("POST"));
      assertTrue(methodSet.contains("PUT"));
      assertTrue(methodSet.contains("PATCH"));
      assertTrue(methodSet.contains("DELETE"));
    }

    @Test
    @DisplayName("should handle null boolean values gracefully")
    void shouldHandleNullBooleans() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(null);
      when(entity.isGetByID()).thenReturn(null);
      when(entity.isPost()).thenReturn(null);
      when(entity.isPut()).thenReturn(null);
      when(entity.isPatch()).thenReturn(null);
      when(entity.isDelete()).thenReturn(null);

      JSONArray methods = NeoDiscoveryHelper.buildMethodsArray(entity);

      assertEquals(0, methods.length());
    }
  }

  @Nested
  @DisplayName("mapSelectorType")
  class MapSelectorType {

    @Test
    @DisplayName("should return null for null refId")
    void shouldReturnNullForNull() {
      assertNull(NeoDiscoveryHelper.mapSelectorType(null));
    }

    @Test
    @DisplayName("should map 19 to TableDir")
    void shouldMapTableDir() {
      assertEquals("TableDir", NeoDiscoveryHelper.mapSelectorType("19"));
    }

    @Test
    @DisplayName("should map 18 to Table")
    void shouldMapTable() {
      assertEquals("Table", NeoDiscoveryHelper.mapSelectorType("18"));
    }

    @Test
    @DisplayName("should map 30 to Search")
    void shouldMapSearch() {
      assertEquals("Search", NeoDiscoveryHelper.mapSelectorType("30"));
    }

    @Test
    @DisplayName("should map OBUISEL reference to OBUISEL")
    void shouldMapObuisel() {
      assertEquals("OBUISEL",
          NeoDiscoveryHelper.mapSelectorType("95E2A8B50A254B2AAE6774B8C2F28120"));
    }

    @Test
    @DisplayName("should return null for unknown refId")
    void shouldReturnNullForUnknown() {
      assertNull(NeoDiscoveryHelper.mapSelectorType("999"));
    }
  }

  @Nested
  @DisplayName("mapReferenceToType")
  class MapReferenceToType {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"10", "14", "34"})
    @DisplayName("should map string-like references to 'string'")
    void shouldMapStringTypes(String refId) {
      assertEquals("string", NeoDiscoveryHelper.mapReferenceToType(refId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"11", "22", "29", "12", "800008", "800019"})
    @DisplayName("should map numeric references to 'number'")
    void shouldMapNumberTypes(String refId) {
      assertEquals("number", NeoDiscoveryHelper.mapReferenceToType(refId));
    }

    @Test
    @DisplayName("should map 20 to boolean")
    void shouldMapBoolean() {
      assertEquals("boolean", NeoDiscoveryHelper.mapReferenceToType("20"));
    }

    @Test
    @DisplayName("should map 15 to date")
    void shouldMapDate() {
      assertEquals("date", NeoDiscoveryHelper.mapReferenceToType("15"));
    }

    @Test
    @DisplayName("should map 16 to datetime")
    void shouldMapDatetime() {
      assertEquals("datetime", NeoDiscoveryHelper.mapReferenceToType("16"));
    }

    @Test
    @DisplayName("should map 24 to time")
    void shouldMapTime() {
      assertEquals("time", NeoDiscoveryHelper.mapReferenceToType("24"));
    }

    @Test
    @DisplayName("should map 28 to button")
    void shouldMapButton() {
      assertEquals("button", NeoDiscoveryHelper.mapReferenceToType("28"));
    }

    @Test
    @DisplayName("should map 17 to list")
    void shouldMapList() {
      assertEquals("list", NeoDiscoveryHelper.mapReferenceToType("17"));
    }

    @Test
    @DisplayName("should map 13 to id")
    void shouldMapId() {
      assertEquals("id", NeoDiscoveryHelper.mapReferenceToType("13"));
    }

    @Test
    @DisplayName("should default to string for unknown refId")
    void shouldDefaultToString() {
      assertEquals("string", NeoDiscoveryHelper.mapReferenceToType("99999"));
    }
  }

  @Nested
  @DisplayName("extractValidationParams")
  class ExtractValidationParams {

    @Test
    @DisplayName("should return empty array when column has no validation rule")
    void shouldReturnEmptyWhenNoValidation() {
      Column column = mock(Column.class);
      when(column.getValidation()).thenReturn(null);

      JSONArray result = NeoDiscoveryHelper.extractValidationParams(column);

      assertNotNull(result);
      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("should return empty array when validation code is null")
    void shouldReturnEmptyWhenValidationCodeNull() {
      Column column = mock(Column.class);
      Validation validation = mock(Validation.class);
      when(column.getValidation()).thenReturn(validation);
      when(validation.getValidationCode()).thenReturn(null);

      JSONArray result = NeoDiscoveryHelper.extractValidationParams(column);

      assertNotNull(result);
      assertEquals(0, result.length());
    }

    @Test
    @DisplayName("should extract single parameter from validation code")
    void shouldExtractSingleParam() {
      Column column = mock(Column.class);
      Validation validation = mock(Validation.class);
      when(column.getValidation()).thenReturn(validation);
      when(validation.getValidationCode()).thenReturn("e.id = @BusinessPartner@");

      JSONArray result = NeoDiscoveryHelper.extractValidationParams(column);

      assertEquals(1, result.length());
      assertEquals("BusinessPartner", result.opt(0));
    }

    @Test
    @DisplayName("should extract multiple unique parameters")
    void shouldExtractMultipleParams() {
      Column column = mock(Column.class);
      Validation validation = mock(Validation.class);
      when(column.getValidation()).thenReturn(validation);
      when(validation.getValidationCode()).thenReturn(
          "e.org = @Organization@ and e.bp = @BusinessPartner@ and e.wh = @Warehouse@");

      JSONArray result = NeoDiscoveryHelper.extractValidationParams(column);

      assertEquals(3, result.length());
      Set<String> params = new HashSet<>();
      for (int i = 0; i < result.length(); i++) {
        params.add(result.optString(i));
      }
      assertTrue(params.contains("Organization"));
      assertTrue(params.contains("BusinessPartner"));
      assertTrue(params.contains("Warehouse"));
    }

    @Test
    @DisplayName("should deduplicate repeated parameters")
    void shouldDeduplicateParams() {
      Column column = mock(Column.class);
      Validation validation = mock(Validation.class);
      when(column.getValidation()).thenReturn(validation);
      when(validation.getValidationCode()).thenReturn(
          "e.org = @Organization@ and e.parent.org = @Organization@");

      JSONArray result = NeoDiscoveryHelper.extractValidationParams(column);

      assertEquals(1, result.length());
      assertEquals("Organization", result.opt(0));
    }

    @Test
    @DisplayName("should return empty array when no parameters in validation code")
    void shouldReturnEmptyWhenNoParams() {
      Column column = mock(Column.class);
      Validation validation = mock(Validation.class);
      when(column.getValidation()).thenReturn(validation);
      when(validation.getValidationCode()).thenReturn("e.active = 'Y'");

      JSONArray result = NeoDiscoveryHelper.extractValidationParams(column);

      assertEquals(0, result.length());
    }
  }
}
