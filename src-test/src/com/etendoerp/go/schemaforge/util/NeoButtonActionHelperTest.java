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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Process;
import com.etendoerp.go.schemaforge.NeoProcessService;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoServlet;
import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Unit tests for {@link NeoButtonActionHelper}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoButtonActionHelperTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<NeoAccessHelper> accessHelperMock;
  private MockedStatic<NeoProcessService> processServiceMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private OBDal dal;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    accessHelperMock = mockStatic(NeoAccessHelper.class);
    processServiceMock = mockStatic(NeoProcessService.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    // Mock ModelProvider so BaseOBObject.getEntity() doesn't NPE when mocking DAL entities
    ModelProvider mp = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(mp);
    when(mp.getEntity(any(String.class))).thenReturn(entity);
    when(mp.getEntity(any(Class.class))).thenReturn(entity);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (accessHelperMock != null) {
      accessHelperMock.close();
    }
    if (processServiceMock != null) {
      processServiceMock.close();
    }
    if (modelProviderMock != null) {
      modelProviderMock.close();
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends BaseOBObject> OBCriteria<T> mockCriteria() {
    OBCriteria<T> criteria = mock(OBCriteria.class);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    return criteria;
  }

  private Reference createButtonReference() {
    Reference ref = mock(Reference.class);
    when(ref.getId()).thenReturn("28");
    return ref;
  }

  private Reference createNonButtonReference() {
    Reference ref = mock(Reference.class);
    when(ref.getId()).thenReturn("10");
    return ref;
  }

  private SFField createMockField(Column column, String javaQualifier) {
    SFField field = mock(SFField.class);
    when(field.getADColumn()).thenReturn(column);
    when(field.getJavaQualifier()).thenReturn(javaQualifier);
    return field;
  }

  private Column createButtonColumn(String dbColumnName,
      Process classicProcess,
      org.openbravo.client.application.Process obuiappProcess) {
    Reference btnRef = createButtonReference();
    Column column = mock(Column.class);
    doReturn(dbColumnName).when(column).getDBColumnName();
    doReturn(btnRef).when(column).getReference();
    doReturn(classicProcess).when(column).getProcess();
    doReturn(obuiappProcess).when(column).getOBUIAPPProcess();
    return column;
  }

  private void setupFieldCriteria(List<SFField> fields) {
    OBCriteria<SFField> criteria = mockCriteria();
    when(criteria.list()).thenReturn(fields);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
  }

  private NeoPathInfo createPathInfo(String actionName, String recordId) {
    try {
      java.lang.reflect.Constructor<NeoPathInfo> ctor = NeoPathInfo.class.getDeclaredConstructor(
          String.class, String.class, String.class,
          boolean.class, String.class, boolean.class, String.class);
      ctor.setAccessible(true);
      return ctor.newInstance("spec", "entity", recordId, false, null, true, actionName);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private HttpServletRequest createMockRequest(String body) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    if (body != null) {
      when(request.getAttribute(NeoServlet.ACTION_REQUEST_BODY_ATTR)).thenReturn(body);
    } else {
      when(request.getAttribute(NeoServlet.ACTION_REQUEST_BODY_ATTR)).thenReturn(null);
      byte[] empty = "".getBytes(StandardCharsets.UTF_8);
      ByteArrayInputStream bais = new ByteArrayInputStream(empty);
      ServletInputStream sis = new ServletInputStream() {
        @Override
        public int read() {
          return bais.read();
        }

        @Override
        public boolean isFinished() {
          return bais.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          // no-op
        }
      };
      when(request.getInputStream()).thenReturn(sis);
    }
    return request;
  }

  // ── listButtonActions ─────────────────────────────────────────────────

  @Nested
  @DisplayName("listButtonActions")
  class ListButtonActions {

    @Test
    @DisplayName("should return empty actions array when no fields exist")
    void shouldReturnEmptyActionsWhenNoFields() throws Exception {
      setupFieldCriteria(Collections.emptyList());

      NeoResponse response = NeoButtonActionHelper.listButtonActions("entity1");

      assertEquals(200, response.getHttpStatus());
      JSONArray actions = response.getBody().getJSONArray("actions");
      assertEquals(0, actions.length());
    }

    @Test
    @DisplayName("should include button fields in actions")
    void shouldIncludeButtonFields() throws Exception {
      org.openbravo.client.application.Process obuiProc = mock(
          org.openbravo.client.application.Process.class);
      when(obuiProc.getName()).thenReturn("TestProcess");

      Column btnColumn = createButtonColumn("Processing", null, obuiProc);
      SFField btnField = createMockField(btnColumn, "processNow");

      List<SFField> fields = new ArrayList<>();
      fields.add(btnField);
      setupFieldCriteria(fields);

      NeoResponse response = NeoButtonActionHelper.listButtonActions("entity1");

      assertEquals(200, response.getHttpStatus());
      JSONArray actions = response.getBody().getJSONArray("actions");
      assertEquals(1, actions.length());
      assertEquals("Processing", actions.getJSONObject(0).getString("columnName"));
    }

    @Test
    @DisplayName("should exclude non-button fields from actions")
    void shouldExcludeNonButtonFields() throws Exception {
      Column nonBtnColumn = mock(Column.class);
      Reference nonButtonReference = createNonButtonReference();
      when(nonBtnColumn.getReference()).thenReturn(nonButtonReference);
      SFField nonBtnField = createMockField(nonBtnColumn, "name");

      List<SFField> fields = new ArrayList<>();
      fields.add(nonBtnField);
      setupFieldCriteria(fields);

      NeoResponse response = NeoButtonActionHelper.listButtonActions("entity1");

      assertEquals(200, response.getHttpStatus());
      JSONArray actions = response.getBody().getJSONArray("actions");
      assertEquals(0, actions.length());
    }
  }

  // ── buildButtonActionEntry ────────────────────────────────────────────

  @Nested
  @DisplayName("buildButtonActionEntry")
  class BuildButtonActionEntry {

    @Test
    @DisplayName("should return null when column is null")
    void shouldReturnNullWhenColumnIsNull() throws Exception {
      SFField field = mock(SFField.class);
      when(field.getADColumn()).thenReturn(null);

      JSONObject result = NeoButtonActionHelper.buildButtonActionEntry(field);

      assertNull(result);
    }

    @Test
    @DisplayName("should return null when reference is not 28")
    void shouldReturnNullWhenReferenceIsNot28() throws Exception {
      Column column = mock(Column.class);
      Reference nonButtonReference = createNonButtonReference();
      when(column.getReference()).thenReturn(nonButtonReference);
      SFField field = createMockField(column, "someField");

      JSONObject result = NeoButtonActionHelper.buildButtonActionEntry(field);

      assertNull(result);
    }

    @Test
    @DisplayName("should return OBUIAPP entry when obuiapp process is set")
    void shouldReturnObuiappEntry() throws Exception {
      org.openbravo.client.application.Process obuiProc = mock(
          org.openbravo.client.application.Process.class);
      when(obuiProc.getName()).thenReturn("MyObuiProcess");

      Column column = createButtonColumn("DocAction", null, obuiProc);
      SFField field = createMockField(column, "documentAction");

      JSONObject result = NeoButtonActionHelper.buildButtonActionEntry(field);

      assertNotNull(result);
      assertEquals("DocAction", result.getString("columnName"));
      assertEquals("OBUIAPP", result.getString("processType"));
      assertEquals("MyObuiProcess", result.getString("processName"));
    }

    @Test
    @DisplayName("should return Classic entry when only classic process is set")
    void shouldReturnClassicEntry() throws Exception {
      Process classicProc = mock(Process.class);
      when(classicProc.getName()).thenReturn("MyClassicProcess");

      Column column = createButtonColumn("Posted", classicProc, null);
      SFField field = createMockField(column, "posted");

      JSONObject result = NeoButtonActionHelper.buildButtonActionEntry(field);

      assertNotNull(result);
      assertEquals("Posted", result.getString("columnName"));
      assertEquals("Classic", result.getString("processType"));
      assertEquals("MyClassicProcess", result.getString("processName"));
    }

    @Test
    @DisplayName("should return null when no process linked and fallback returns null")
    void shouldReturnNullWhenNoProcess() throws Exception {
      Column column = createButtonColumn("Processing", null, null);
      SFField field = createMockField(column, "processNow");

      accessHelperMock.when(() -> NeoAccessHelper.resolveFallbackObuiappProcess(column))
          .thenReturn(null);

      JSONObject result = NeoButtonActionHelper.buildButtonActionEntry(field);

      assertNull(result);
    }

    @Test
    @DisplayName("should use fallback obuiapp process when direct processes are null")
    void shouldUseFallbackObuiappProcess() throws Exception {
      org.openbravo.client.application.Process fallbackProc = mock(
          org.openbravo.client.application.Process.class);
      when(fallbackProc.getName()).thenReturn("FallbackProcess");

      Column column = createButtonColumn("Processing", null, null);
      SFField field = createMockField(column, "processNow");

      accessHelperMock.when(() -> NeoAccessHelper.resolveFallbackObuiappProcess(column))
          .thenReturn(fallbackProc);

      JSONObject result = NeoButtonActionHelper.buildButtonActionEntry(field);

      assertNotNull(result);
      assertEquals("OBUIAPP", result.getString("processType"));
      assertEquals("FallbackProcess", result.getString("processName"));
    }
  }

  // ── executeButtonAction ───────────────────────────────────────────────

  @Nested
  @DisplayName("executeButtonAction")
  class ExecuteButtonAction {

    @Test
    @DisplayName("should return 404 when column not found")
    void shouldReturn404WhenColumnNotFound() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      setupFieldCriteria(Collections.emptyList());

      NeoPathInfo pathInfo = createPathInfo("nonExistent", "rec1");
      HttpServletRequest request = createMockRequest(null);

      NeoResponse response = NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);

      assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("Action not found"));
    }

    @Test
    @DisplayName("should return 400 when column is not a button")
    void shouldReturn400WhenNotButton() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();

      Column nonBtnColumn = mock(Column.class);
      Reference nonButtonReference = createNonButtonReference();
      when(nonBtnColumn.getDBColumnName()).thenReturn("Name");
      when(nonBtnColumn.getReference()).thenReturn(nonButtonReference);
      SFField field = createMockField(nonBtnColumn, "name");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      NeoPathInfo pathInfo = createPathInfo("Name", "rec1");
      HttpServletRequest request = createMockRequest(null);

      NeoResponse response = NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("not a button"));
    }

    @Test
    @DisplayName("should return 400 when no process linked to button")
    void shouldReturn400WhenNoProcess() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      Column btnColumn = createButtonColumn("Processing", null, null);
      SFField field = createMockField(btnColumn, "processNow");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.resolveFallbackObuiappProcess(btnColumn))
          .thenReturn(null);

      NeoPathInfo pathInfo = createPathInfo("Processing", "rec1");
      HttpServletRequest request = createMockRequest("{}");

      NeoResponse response = NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("No process linked"));
    }

    @Test
    @DisplayName("should return 403 when OBUIAPP process access denied")
    void shouldReturn403WhenObuiappAccessDenied() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      org.openbravo.client.application.Process obuiProc = mock(
          org.openbravo.client.application.Process.class);
      doReturn("proc1").when(obuiProc).getId();
      when(obuiProc.getName()).thenReturn("TestProc");

      Column btnColumn = createButtonColumn("DocAction", null, obuiProc);
      SFField field = createMockField(btnColumn, "documentAction");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess("proc1"))
          .thenReturn(false);

      NeoPathInfo pathInfo = createPathInfo("DocAction", "rec1");
      HttpServletRequest request = createMockRequest("{}");

      NeoResponse response = NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);

      assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("Access denied"));
    }

    @Test
    @DisplayName("should return 403 when Classic process access denied")
    void shouldReturn403WhenClassicAccessDenied() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      Process classicProc = mock(Process.class);
      doReturn("cproc1").when(classicProc).getId();
      when(classicProc.getName()).thenReturn("ClassicProc");

      Column btnColumn = createButtonColumn("Posted", classicProc, null);
      SFField field = createMockField(btnColumn, "posted");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasProcessAccess("cproc1"))
          .thenReturn(false);

      NeoPathInfo pathInfo = createPathInfo("Posted", "rec1");
      HttpServletRequest request = createMockRequest("{}");

      NeoResponse response = NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);

      assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("Access denied"));
    }

    @Test
    @DisplayName("should execute OBUIAPP process successfully")
    void shouldExecuteObuiappProcessSuccessfully() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      org.openbravo.client.application.Process obuiProc = mock(
          org.openbravo.client.application.Process.class);
      doReturn("proc1").when(obuiProc).getId();
      when(obuiProc.getName()).thenReturn("TestProc");

      Column btnColumn = createButtonColumn("DocAction", null, obuiProc);
      SFField field = createMockField(btnColumn, "documentAction");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess("proc1"))
          .thenReturn(true);

      NeoResponse expectedResponse = NeoResponse.ok(new JSONObject("{\"result\":\"success\"}"));
      processServiceMock.when(
          () -> NeoProcessService.executeObuiappProcess(
              eq(obuiProc), any(JSONObject.class)))
          .thenReturn(expectedResponse);

      NeoPathInfo pathInfo = createPathInfo("DocAction", "rec1");
      HttpServletRequest request = createMockRequest("{}");

      NeoResponse response = NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
    }

    @Test
    @DisplayName("should execute Classic process successfully")
    void shouldExecuteClassicProcessSuccessfully() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      Process classicProc = mock(Process.class);
      doReturn("cproc1").when(classicProc).getId();
      when(classicProc.getName()).thenReturn("ClassicProc");

      Column btnColumn = createButtonColumn("Posted", classicProc, null);
      SFField field = createMockField(btnColumn, "posted");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasProcessAccess("cproc1"))
          .thenReturn(true);

      NeoResponse expectedResponse = NeoResponse.ok(new JSONObject("{\"result\":\"done\"}"));
      processServiceMock.when(
          () -> NeoProcessService.executeProcess(
              eq(classicProc), any(JSONObject.class)))
          .thenReturn(expectedResponse);

      NeoPathInfo pathInfo = createPathInfo("Posted", "rec1");
      HttpServletRequest request = createMockRequest("{}");

      NeoResponse response = NeoButtonActionHelper.executeButtonAction(entity, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
    }
  }

  // ── executeButtonActionCore ───────────────────────────────────────────

  @Nested
  @DisplayName("executeButtonActionCore")
  class ExecuteButtonActionCore {

    @Test
    @DisplayName("should return 404 when action not found")
    void shouldReturn404WhenActionNotFound() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      setupFieldCriteria(Collections.emptyList());

      NeoResponse response = NeoButtonActionHelper.executeButtonActionCore(
          entity, "rec1", "nonExistent", null);

      assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("Action not found"));
    }

    @Test
    @DisplayName("should return 400 when field is not a button")
    void shouldReturn400WhenFieldNotButton() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();

      Column nonBtnColumn = mock(Column.class);
      Reference nonButtonReference = createNonButtonReference();
      when(nonBtnColumn.getDBColumnName()).thenReturn("Name");
      when(nonBtnColumn.getReference()).thenReturn(nonButtonReference);
      SFField field = createMockField(nonBtnColumn, "name");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      NeoResponse response = NeoButtonActionHelper.executeButtonActionCore(
          entity, "rec1", "Name", null);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("not a button"));
    }

    @Test
    @DisplayName("should return 400 when no process linked to button")
    void shouldReturn400WhenNoProcess() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      Column btnColumn = createButtonColumn("Processing", null, null);
      SFField field = createMockField(btnColumn, "processNow");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.resolveFallbackObuiappProcess(btnColumn))
          .thenReturn(null);

      NeoResponse response = NeoButtonActionHelper.executeButtonActionCore(
          entity, "rec1", "Processing", new JSONObject());

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("No process linked"));
    }

    @Test
    @DisplayName("should return 403 when OBUIAPP process access denied")
    void shouldReturn403WhenObuiappAccessDenied() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      org.openbravo.client.application.Process obuiProc = mock(
          org.openbravo.client.application.Process.class);
      doReturn("proc1").when(obuiProc).getId();
      when(obuiProc.getName()).thenReturn("TestProc");

      Column btnColumn = createButtonColumn("DocAction", null, obuiProc);
      SFField field = createMockField(btnColumn, "documentAction");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess("proc1"))
          .thenReturn(false);

      NeoResponse response = NeoButtonActionHelper.executeButtonActionCore(
          entity, "rec1", "DocAction", null);

      assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("Access denied"));
    }

    @Test
    @DisplayName("should return 403 when Classic process access denied")
    void shouldReturn403WhenClassicAccessDenied() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      Process classicProc = mock(Process.class);
      doReturn("cproc1").when(classicProc).getId();
      when(classicProc.getName()).thenReturn("ClassicProc");

      Column btnColumn = createButtonColumn("Posted", classicProc, null);
      SFField field = createMockField(btnColumn, "posted");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasProcessAccess("cproc1"))
          .thenReturn(false);

      NeoResponse response = NeoButtonActionHelper.executeButtonActionCore(
          entity, "rec1", "Posted", null);

      assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getHttpStatus());
      assertTrue(response.getBody().getString("error").contains("Access denied"));
    }

    @Test
    @DisplayName("should execute OBUIAPP process successfully and return 200")
    void shouldExecuteObuiappSuccessfully() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      org.openbravo.client.application.Process obuiProc = mock(
          org.openbravo.client.application.Process.class);
      doReturn("proc1").when(obuiProc).getId();
      when(obuiProc.getName()).thenReturn("TestProc");

      Column btnColumn = createButtonColumn("DocAction", null, obuiProc);
      SFField field = createMockField(btnColumn, "documentAction");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess("proc1"))
          .thenReturn(true);

      NeoResponse successResponse = NeoResponse.ok(new JSONObject("{\"status\":\"success\",\"message\":\"Done\"}"));
      processServiceMock.when(
          () -> NeoProcessService.executeObuiappProcess(
              eq(obuiProc), any(JSONObject.class)))
          .thenReturn(successResponse);

      NeoResponse response = NeoButtonActionHelper.executeButtonActionCore(
          entity, "rec1", "DocAction", null);

      assertEquals(200, response.getHttpStatus());
      assertEquals("success", response.getBody().getString("status"));
    }

    @Test
    @DisplayName("should treat null params as empty object without NPE")
    void shouldHandleNullParamsGracefully() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      doReturn("entity1").when(entity).getId();
      when(entity.getADTab()).thenReturn(null);

      Process classicProc = mock(Process.class);
      doReturn("cproc2").when(classicProc).getId();
      when(classicProc.getName()).thenReturn("ClassicProc");

      Column btnColumn = createButtonColumn("Posted", classicProc, null);
      SFField field = createMockField(btnColumn, "posted");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      accessHelperMock.when(() -> NeoAccessHelper.hasProcessAccess("cproc2"))
          .thenReturn(true);

      NeoResponse successResponse = NeoResponse.ok(new JSONObject("{\"status\":\"success\"}"));
      processServiceMock.when(
          () -> NeoProcessService.executeProcess(
              eq(classicProc), any(JSONObject.class)))
          .thenReturn(successResponse);

      // null params — should not throw NPE
      NeoResponse response = NeoButtonActionHelper.executeButtonActionCore(
          entity, "rec1", "Posted", null);

      assertEquals(200, response.getHttpStatus());
    }
  }

  // ── findButtonColumn ──────────────────────────────────────────────────

  @Nested
  @DisplayName("findButtonColumn")
  class FindButtonColumn {

    @Test
    @DisplayName("should match by DB column name")
    void shouldMatchByDbColumnName() {
      Column column = mock(Column.class);
      when(column.getDBColumnName()).thenReturn("Processing");
      SFField field = createMockField(column, "processNow");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      Column result = NeoButtonActionHelper.findButtonColumn("entity1", "Processing");

      assertNotNull(result);
      assertEquals("Processing", result.getDBColumnName());
    }

    @Test
    @DisplayName("should match by Java qualifier")
    void shouldMatchByJavaQualifier() {
      Column column = mock(Column.class);
      when(column.getDBColumnName()).thenReturn("Processing");
      SFField field = createMockField(column, "processNow");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      Column result = NeoButtonActionHelper.findButtonColumn("entity1", "processNow");

      assertNotNull(result);
      assertEquals("Processing", result.getDBColumnName());
    }

    @Test
    @DisplayName("should return null when no match found")
    void shouldReturnNullWhenNotFound() {
      Column column = mock(Column.class);
      when(column.getDBColumnName()).thenReturn("Processing");
      SFField field = createMockField(column, "processNow");

      List<SFField> fields = new ArrayList<>();
      fields.add(field);
      setupFieldCriteria(fields);

      Column result = NeoButtonActionHelper.findButtonColumn("entity1", "nonExistent");

      assertNull(result);
    }

    @Test
    @DisplayName("should return null when fields list is empty")
    void shouldReturnNullWhenFieldsEmpty() {
      setupFieldCriteria(Collections.emptyList());

      Column result = NeoButtonActionHelper.findButtonColumn("entity1", "Processing");

      assertNull(result);
    }

    @Test
    @DisplayName("should skip fields with null column")
    void shouldSkipFieldsWithNullColumn() {
      SFField nullColumnField = createMockField(null, "nullField");

      Column column = mock(Column.class);
      when(column.getDBColumnName()).thenReturn("Processing");
      SFField validField = createMockField(column, "processNow");

      List<SFField> fields = new ArrayList<>();
      fields.add(nullColumnField);
      fields.add(validField);
      setupFieldCriteria(fields);

      Column result = NeoButtonActionHelper.findButtonColumn("entity1", "Processing");

      assertNotNull(result);
      assertEquals("Processing", result.getDBColumnName());
    }
  }

  // ── loadEntityFields ──────────────────────────────────────────────────

  @Nested
  @DisplayName("loadEntityFields")
  class LoadEntityFields {

    @Test
    @DisplayName("should return fields from criteria query")
    void shouldReturnFieldsFromCriteria() {
      SFField field1 = mock(SFField.class);
      SFField field2 = mock(SFField.class);
      List<SFField> expectedFields = List.of(field1, field2);

      setupFieldCriteria(expectedFields);

      List<SFField> result = NeoButtonActionHelper.loadEntityFields("entity1");

      assertEquals(2, result.size());
    }

    @Test
    @DisplayName("should return empty list when no fields match")
    void shouldReturnEmptyListWhenNoFields() {
      setupFieldCriteria(Collections.emptyList());

      List<SFField> result = NeoButtonActionHelper.loadEntityFields("entity1");

      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }
}
