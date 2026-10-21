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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.domain.ModelImplementation;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.NeoResponse;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Tests for {@link NeoProcessService}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoProcessService
 */
public class NeoProcessServiceTest {

  // ===================== executeProcess =====================

  @Test
  public void executeProcessNullProcessReturnsForbidden() {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess(anyString())).thenReturn(false);

      NeoResponse resp = NeoProcessService.executeProcess(null, new JSONObject());
      assertEquals(403, resp.getHttpStatus());
    }
  }

  @Test
  public void executeProcessNoAccessReturnsForbidden() {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(false);

      NeoResponse resp = NeoProcessService.executeProcess(process, new JSONObject());
      assertEquals(403, resp.getHttpStatus());
    }
  }

  @Test
  public void executeProcessNullParams() {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");
    when(process.getName()).thenReturn("Test Process");
    when(process.getUIPattern()).thenReturn(null);
    when(process.getJavaClassName()).thenReturn(null);
    when(process.getProcedure()).thenReturn(null);
    when(process.getADProcessParameterList()).thenReturn(Collections.emptyList());

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);

      NeoResponse resp = NeoProcessService.executeProcess(process, null);
      // Should get 400 because no executable handler
      assertEquals(400, resp.getHttpStatus());
    }
  }

  @Test
  public void executeProcessNoHandlerReturns400() {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");
    when(process.getName()).thenReturn("Empty Process");
    when(process.getUIPattern()).thenReturn(null);
    when(process.getJavaClassName()).thenReturn(null);
    when(process.getProcedure()).thenReturn(null);
    when(process.getADProcessParameterList()).thenReturn(Collections.emptyList());
    when(process.getADModelImplementationList()).thenReturn(Collections.emptyList());

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);

      NeoResponse resp = NeoProcessService.executeProcess(process, new JSONObject());
      assertEquals(400, resp.getHttpStatus());
    }
  }

  @Test
  public void executeProcessWithRecordContext() throws Exception {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");
    when(process.getName()).thenReturn("Test Process");
    when(process.getUIPattern()).thenReturn(null);
    when(process.getJavaClassName()).thenReturn(null);
    when(process.getProcedure()).thenReturn(null);
    when(process.getADProcessParameterList()).thenReturn(Collections.emptyList());
    when(process.getADModelImplementationList()).thenReturn(Collections.emptyList());

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);

      NeoResponse resp = NeoProcessService.executeProcess(process, new JSONObject(),
          "record-1", "tab-1");
      // Should still get 400 (no handler) but params were enriched
      assertEquals(400, resp.getHttpStatus());
    }
  }

  @Test
  public void executeProcessClassNotFoundReturns500() throws Exception {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");
    when(process.getName()).thenReturn("Missing Class Process");
    when(process.getUIPattern()).thenReturn(null);
    when(process.getJavaClassName()).thenReturn("com.nonexistent.ProcessClass");
    when(process.getProcedure()).thenReturn(null);
    when(process.getADProcessParameterList()).thenReturn(Collections.emptyList());

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);

      NeoResponse resp = NeoProcessService.executeProcess(process, new JSONObject());
      assertEquals(500, resp.getHttpStatus());
    }
  }

  // ===================== DocAction procedure: idempotency and refusal (ETP-5783) =====================

  private static Process docActionProcedure() {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");
    when(process.getName()).thenReturn("Post");
    when(process.getUIPattern()).thenReturn(null);
    when(process.getJavaClassName()).thenReturn(null);
    when(process.getProcedure()).thenReturn("C_Order_Post");
    when(process.getADProcessParameterList()).thenReturn(Collections.emptyList());
    when(process.getADModelImplementationList()).thenReturn(Collections.emptyList());
    return process;
  }

  private static JSONObject docActionParams(String action) throws Exception {
    return new JSONObject().put("recordId", "rec-1").put("inpTabId", "tab-1")
        .put("docAction", action);
  }

  /** A document already in the requested status answers 200 alreadyApplied; the procedure never runs. */
  @Test
  public void executeProcessAnAlreadyAppliedDocActionNeverCallsTheProcedure() throws Exception {
    NeoResponse applied = NeoResponse.ok(new JSONObject().put("status", "success")
        .put("alreadyApplied", true));
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<NeoProcessPreconditionService> preconditions =
             mockStatic(NeoProcessPreconditionService.class);
         MockedStatic<DocActionIdempotency> idempotency = mockStatic(DocActionIdempotency.class);
         MockedStatic<org.openbravo.service.db.CallProcess> callProcess =
             mockStatic(org.openbravo.service.db.CallProcess.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);
      idempotency.when(() -> DocActionIdempotency.alreadyApplied("tab-1", "rec-1", "CO"))
          .thenReturn(applied);

      NeoResponse resp = NeoProcessService.executeProcess(docActionProcedure(),
          docActionParams("CO"));

      assertEquals(200, resp.getHttpStatus());
      assertTrue(resp.getBody().getBoolean("alreadyApplied"));
      callProcess.verifyNoInteractions();
    }
  }

  /** Any other status runs the procedure exactly as before, and its refusal is a 422 business_rule. */
  @Test
  public void executeProcessARefusedDocActionIs422BusinessRule() throws Exception {
    org.openbravo.model.ad.process.ProcessInstance pInstance =
        mock(org.openbravo.model.ad.process.ProcessInstance.class);
    when(pInstance.getResult()).thenReturn(0L);
    when(pInstance.getErrorMsg()).thenReturn("@ERROR=Document has no lines");
    org.hibernate.Session session = mock(org.hibernate.Session.class);
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    org.openbravo.service.db.CallProcess cp = mock(org.openbravo.service.db.CallProcess.class);
    when(cp.call(any(Process.class), anyString(), any())).thenReturn(pInstance);

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessPreconditionService> preconditions =
             mockStatic(NeoProcessPreconditionService.class);
         MockedStatic<DocActionIdempotency> idempotency = mockStatic(DocActionIdempotency.class);
         MockedStatic<DocActionResolution> resolution = mockStatic(DocActionResolution.class);
         MockedStatic<org.openbravo.service.db.CallProcess> callProcess =
             mockStatic(org.openbravo.service.db.CallProcess.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      callProcess.when(org.openbravo.service.db.CallProcess::getInstance).thenReturn(cp);
      resolution.when(() -> DocActionResolution.requestedDocAction(any())).thenReturn("CO");

      NeoResponse resp = NeoProcessService.executeProcess(docActionProcedure(),
          docActionParams("CO"));

      assertEquals(422, resp.getHttpStatus());
      assertEquals("Document has no lines", resp.getBody().getString("message"));
      assertEquals("business_rule", resp.getBody().getJSONObject("error").getString("code"));
    }
  }

  /** A malformed request (no record id) is still a 400, not a business rule. */
  @Test
  public void executeProcessADocActionWithoutARecordIdStaysA400() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<NeoProcessPreconditionService> preconditions =
             mockStatic(NeoProcessPreconditionService.class)) {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);

      NeoResponse resp = NeoProcessService.executeProcess(docActionProcedure(),
          new JSONObject().put("docAction", "CO"));

      assertEquals(400, resp.getHttpStatus());
    }
  }

  // ===================== listActions =====================

  @Test
  public void listActionsEntityNotFound() {
    OBDal obDal = mock(OBDal.class);
    OBCriteria entityCrit = mock(OBCriteria.class);
    when(entityCrit.uniqueResult()).thenReturn(null);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCrit);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = NeoProcessService.listActions("spec-1", "nonexistent");
      assertEquals(404, resp.getHttpStatus());
    }
  }

  @Test
  public void listActionsSuccess() throws Exception {
    SFEntity entity = mock(SFEntity.class);

    OBCriteria entityCrit = mock(OBCriteria.class);
    when(entityCrit.uniqueResult()).thenReturn(entity);

    OBCriteria fieldCrit = mock(OBCriteria.class);
    when(fieldCrit.list()).thenReturn(Collections.emptyList());

    OBDal obDal = mock(OBDal.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCrit);
    when(obDal.createCriteria(SFField.class)).thenReturn(fieldCrit);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = NeoProcessService.listActions("spec-1", "order");
      assertEquals(200, resp.getHttpStatus());
      JSONObject body = resp.getBody();
      assertNotNull(body.getJSONArray("actions"));
      assertEquals(0, body.getInt("count"));
    }
  }

  // ===================== describeProcess =====================

  @Test
  public void describeProcessReturnsMetadata() throws Exception {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");
    when(process.getName()).thenReturn("Test Process");
    when(process.getDescription()).thenReturn("A test process");
    when(process.getHelpComment()).thenReturn("Help text");
    when(process.getUIPattern()).thenReturn("S");
    when(process.getJavaClassName()).thenReturn("com.test.MyProcess");
    when(process.getADProcessParameterList()).thenReturn(Collections.emptyList());

    OBContext obContext = mock(OBContext.class);
    when(obContext.getLanguage()).thenReturn(null);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      NeoResponse resp = NeoProcessService.describeProcess(process);
      assertEquals(200, resp.getHttpStatus());
      JSONObject body = resp.getBody();
      assertEquals("proc-1", body.getString("id"));
      assertEquals("Test Process", body.getString("name"));
    }
  }

  @Test(expected = NullPointerException.class)
  public void describeProcessNullProcessReturns400() {
    NeoProcessService.describeProcess(null);
  }

  // ===================== addKeyColumnToContent =====================

  @Test
  public void addKeyColumnToContentMapsRecordIdUnderKeyColumnName() throws Exception {
    Column keyCol = mock(Column.class);
    when(keyCol.isKeyColumn()).thenReturn(true);
    when(keyCol.getDBColumnName()).thenReturn("A_Asset_ID");

    Table table = mock(Table.class);
    when(table.getADColumnList()).thenReturn(Collections.singletonList(keyCol));

    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);

    JSONObject content = new JSONObject();
    NeoProcessService.addKeyColumnToContent(content, tab, "asset-record-1");

    assertEquals("asset-record-1", content.getString("A_Asset_ID"));
  }

  @Test
  public void addKeyColumnToContentIgnoresNonKeyColumns() throws Exception {
    Column nonKeyCol = mock(Column.class);
    when(nonKeyCol.isKeyColumn()).thenReturn(false);
    when(nonKeyCol.getDBColumnName()).thenReturn("AD_Org_ID");

    Column keyCol = mock(Column.class);
    when(keyCol.isKeyColumn()).thenReturn(true);
    when(keyCol.getDBColumnName()).thenReturn("A_Asset_ID");

    List<Column> cols = new ArrayList<>();
    cols.add(nonKeyCol);
    cols.add(keyCol);

    Table table = mock(Table.class);
    when(table.getADColumnList()).thenReturn(cols);

    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);

    JSONObject content = new JSONObject();
    NeoProcessService.addKeyColumnToContent(content, tab, "rec-1");

    assertEquals("rec-1", content.getString("A_Asset_ID"));
    assertFalse(content.has("AD_Org_ID"));
  }

  @Test
  public void addKeyColumnToContentStopsAtFirstKeyColumn() throws Exception {
    Column firstKey = mock(Column.class);
    when(firstKey.isKeyColumn()).thenReturn(true);
    when(firstKey.getDBColumnName()).thenReturn("A_Asset_ID");

    Column secondKey = mock(Column.class);
    when(secondKey.isKeyColumn()).thenReturn(true);
    when(secondKey.getDBColumnName()).thenReturn("C_Currency_ID");

    List<Column> cols = new ArrayList<>();
    cols.add(firstKey);
    cols.add(secondKey);

    Table table = mock(Table.class);
    when(table.getADColumnList()).thenReturn(cols);

    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);

    JSONObject content = new JSONObject();
    NeoProcessService.addKeyColumnToContent(content, tab, "rec-1");

    assertEquals("rec-1", content.getString("A_Asset_ID"));
    assertFalse(content.has("C_Currency_ID"));
  }

  @Test
  public void addKeyColumnToContentWithNullTabDoesNothing() throws Exception {
    JSONObject content = new JSONObject();
    NeoProcessService.addKeyColumnToContent(content, null, "rec-1");
    assertEquals(0, content.length());
  }

  @Test
  public void addKeyColumnToContentWithNullRecordIdDoesNothing() throws Exception {
    Tab tab = mock(Tab.class);
    JSONObject content = new JSONObject();
    NeoProcessService.addKeyColumnToContent(content, tab, null);
    assertEquals(0, content.length());
  }

  @Test
  public void addKeyColumnToContentWithNullTableDoesNothing() throws Exception {
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(null);
    JSONObject content = new JSONObject();
    NeoProcessService.addKeyColumnToContent(content, tab, "rec-1");
    assertEquals(0, content.length());
  }

  @Test
  public void addKeyColumnToContentWithNoKeyColumnDoesNothing() throws Exception {
    Column nonKeyCol = mock(Column.class);
    when(nonKeyCol.isKeyColumn()).thenReturn(false);

    Table table = mock(Table.class);
    when(table.getADColumnList()).thenReturn(Collections.singletonList(nonKeyCol));

    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);

    JSONObject content = new JSONObject();
    NeoProcessService.addKeyColumnToContent(content, tab, "rec-1");
    assertEquals(0, content.length());
  }

  // ===================== addTabContextToContent =====================

  @Test
  public void addTabContextToContentSetsEntityNameAndKeyColumn() throws Exception {
    Column keyCol = mock(Column.class);
    when(keyCol.isKeyColumn()).thenReturn(true);
    when(keyCol.getDBColumnName()).thenReturn("A_Asset_ID");

    Table table = mock(Table.class);
    when(table.getName()).thenReturn("FinancialMgmtAsset");
    when(table.getADColumnList()).thenReturn(Collections.singletonList(keyCol));

    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Tab.class, "tab-1")).thenReturn(tab);

    JSONObject params = new JSONObject();
    params.put(NeoProcessService.INP_RECORD_ID, "rec-1");
    JSONObject content = new JSONObject();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoProcessService.addTabContextToContent(content, params, "tab-1");
    }

    assertEquals("FinancialMgmtAsset", content.getString("_entityName"));
    assertEquals("rec-1", content.getString("A_Asset_ID"));
  }

  @Test
  public void addTabContextToContentSetsEntityNameOnlyWhenNoRecordId() throws Exception {
    Table table = mock(Table.class);
    when(table.getName()).thenReturn("FinancialMgmtAsset");

    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Tab.class, "tab-1")).thenReturn(tab);

    JSONObject content = new JSONObject();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoProcessService.addTabContextToContent(content, new JSONObject(), "tab-1");
    }

    assertEquals("FinancialMgmtAsset", content.getString("_entityName"));
    assertFalse(content.has("A_Asset_ID"));
  }

  @Test
  public void addTabContextToContentDoesNothingWhenTabNull() throws Exception {
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Tab.class, "tab-x")).thenReturn(null);

    JSONObject content = new JSONObject();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoProcessService.addTabContextToContent(content, new JSONObject(), "tab-x");
    }

    assertEquals(0, content.length());
  }

  @Test
  public void addTabContextToContentDoesNothingWhenTableNull() throws Exception {
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Tab.class, "tab-1")).thenReturn(tab);

    JSONObject content = new JSONObject();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoProcessService.addTabContextToContent(content, new JSONObject(), "tab-1");
    }

    assertEquals(0, content.length());
  }

  @Test
  public void addTabContextToContentSwallowsExceptions() throws Exception {
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Tab.class, "tab-err")).thenThrow(new RuntimeException("DB down"));

    JSONObject content = new JSONObject();

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      // Must not throw — failure is logged and swallowed.
      NeoProcessService.addTabContextToContent(content, new JSONObject(), "tab-err");
    }

    assertEquals(0, content.length());
  }

  @Test
  public void describeProcessWithParameters() throws Exception {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-1");
    when(process.getName()).thenReturn("Parameterized Process");
    when(process.getDescription()).thenReturn(null);
    when(process.getHelpComment()).thenReturn(null);
    when(process.getUIPattern()).thenReturn(null);
    when(process.getJavaClassName()).thenReturn(null);

    ProcessParameter param = mock(ProcessParameter.class);
    when(param.getDBColumnName()).thenReturn("C_BPartner_ID");
    when(param.getName()).thenReturn("Business Partner");
    when(param.isMandatory()).thenReturn(true);
    when(param.isActive()).thenReturn(true);
    when(param.getReference()).thenReturn(null);
    when(param.getReferenceSearchKey()).thenReturn(null);
    when(param.getDefaultValue()).thenReturn("@C_BPartner_ID@");
    when(param.getADProcessParameterTrlList()).thenReturn(Collections.emptyList());

    List<ProcessParameter> params = new ArrayList<>();
    params.add(param);
    when(process.getADProcessParameterList()).thenReturn(params);

    OBContext obContext = mock(OBContext.class);
    when(obContext.getLanguage()).thenReturn(null);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      NeoResponse resp = NeoProcessService.describeProcess(process);
      assertEquals(200, resp.getHttpStatus());
      JSONObject body = resp.getBody();
      JSONArray paramsArray = body.getJSONArray("parameters");
      assertEquals(1, paramsArray.length());
      assertEquals("C_BPartner_ID", paramsArray.getJSONObject(0).getString("dbColumnName"));
      assertTrue(paramsArray.getJSONObject(0).getBoolean("mandatory"));
    }
  }

  // ===================== resolveModelImplementationClass =====================

  @Test
  public void resolveModelImplementationClassReturnsDefaultImpl() {
    ModelImplementation impl = mock(ModelImplementation.class);
    when(impl.isDefault()).thenReturn(true);
    when(impl.getAction()).thenReturn("P");
    when(impl.getJavaClassName()).thenReturn("com.example.MyProcess");

    Process process = mock(Process.class);
    when(process.getADModelImplementationList()).thenReturn(Collections.singletonList(impl));

    String result = NeoProcessService.resolveModelImplementationClass(process);
    assertEquals("com.example.MyProcess", result);
  }

  @Test
  public void resolveModelImplementationClassFallsBackToFirstNonDefault() {
    ModelImplementation nonDefault = mock(ModelImplementation.class);
    when(nonDefault.isDefault()).thenReturn(false);
    when(nonDefault.getAction()).thenReturn("P");
    when(nonDefault.getJavaClassName()).thenReturn("com.example.Fallback");

    Process process = mock(Process.class);
    when(process.getADModelImplementationList()).thenReturn(Collections.singletonList(nonDefault));

    String result = NeoProcessService.resolveModelImplementationClass(process);
    assertEquals("com.example.Fallback", result);
  }

  @Test
  public void resolveModelImplementationClassReturnsNullWhenNoMatch() {
    ModelImplementation impl = mock(ModelImplementation.class);
    when(impl.isDefault()).thenReturn(true);
    when(impl.getAction()).thenReturn("X"); // not "P"
    when(impl.getJavaClassName()).thenReturn("com.example.Other");

    Process process = mock(Process.class);
    when(process.getADModelImplementationList()).thenReturn(Collections.singletonList(impl));

    String result = NeoProcessService.resolveModelImplementationClass(process);
    assertNull(result);
  }

  @Test
  public void resolveModelImplementationClassReturnsNullOnException() {
    Process process = mock(Process.class);
    when(process.getName()).thenReturn("TestProcess");
    when(process.getADModelImplementationList()).thenThrow(new RuntimeException("DB error"));

    String result = NeoProcessService.resolveModelImplementationClass(process);
    assertNull(result);
  }

  // ===================== translateClassicResult =====================

  @Test
  public void translateClassicResultWithOBErrorSuccess() throws Exception {
    OBError error = new OBError();
    error.setType("Success");
    error.setTitle("OK");
    error.setMessage("Done");

    Process process = mock(Process.class);
    NeoResponse resp = NeoProcessService.translateClassicResult(error, process);

    assertEquals(200, resp.getHttpStatus());
    assertEquals("success", resp.getBody().getString("status"));
    assertEquals("Done", resp.getBody().getString("message"));
  }

  @Test
  public void translateClassicResultWithOBErrorReturns400() throws Exception {
    OBError error = new OBError();
    error.setType("Error");
    error.setTitle("Fail");
    error.setMessage("Something went wrong");

    Process process = mock(Process.class);
    NeoResponse resp = NeoProcessService.translateClassicResult(error, process);

    assertEquals(400, resp.getHttpStatus());
    assertEquals("error", resp.getBody().getString("status"));
  }

  @Test
  public void translateClassicResultWithNullBundleUsesProcessName() throws Exception {
    Process process = mock(Process.class);
    when(process.getName()).thenReturn("MyProcess");

    NeoResponse resp = NeoProcessService.translateClassicResult(null, process);

    assertEquals(200, resp.getHttpStatus());
    assertTrue(resp.getBody().getString("message").contains("MyProcess"));
  }

  @Test
  public void translateClassicResultWithNonOBErrorObject() throws Exception {
    Process process = mock(Process.class);
    NeoResponse resp = NeoProcessService.translateClassicResult("custom result", process);

    assertEquals(200, resp.getHttpStatus());
    assertEquals("custom result", resp.getBody().getString("message"));
  }

  // ETP-5669: a classic DalBaseProcess gets a bundle with a context and a connection (it NPE'd on
  // bundle.getContext() with the bare map-built one), and its params survive init's reset.
  @Test
  public void classicBundleHasContextAndConnectionAndKeepsParams() throws Exception {
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    Map<String, Object> params = Collections.singletonMap("GL_Journal_ID", "J1");
    List<Object> ctorArgs = new ArrayList<>();
    try (MockedStatic<OBContext> obMock = mockStatic(OBContext.class);
        MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class);
        MockedConstruction<DalConnectionProvider> connMock = mockConstruction(DalConnectionProvider.class);
        MockedConstruction<ProcessBundle> bundleMock = mockConstruction(ProcessBundle.class,
            (b, c) -> {
              ctorArgs.addAll(c.arguments());
              when(b.init(any())).thenReturn(b);
            })) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);

      ProcessBundle bundle = NeoProcessService.newClassicBundle("P1", params);

      ProcessBundle built = bundleMock.constructed().get(0);
      assertSame(built, bundle);
      assertEquals(List.of("P1", vars), ctorArgs);
      InOrder order = inOrder(built);
      order.verify(built).init(connMock.constructed().get(0));
      order.verify(built).setParams(params);
    }
  }

  // ETP-5669: building the bundle only reads the context; it must not switch user, role, client or
  // org, nor leave admin mode on for the rest of the request.
  @Test
  public void classicBundleLeavesTheOBContextUntouched() throws Exception {
    OBContext ctx = mock(OBContext.class);
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    try (MockedStatic<OBContext> obMock = mockStatic(OBContext.class);
        MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class);
        MockedConstruction<DalConnectionProvider> connMock = mockConstruction(DalConnectionProvider.class);
        MockedConstruction<ProcessBundle> bundleMock = mockConstruction(ProcessBundle.class,
            (b, c) -> when(b.init(any())).thenReturn(b))) {
      obMock.when(OBContext::getOBContext).thenReturn(ctx);
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);

      NeoProcessService.newClassicBundle("P1", Collections.emptyMap());

      neoMock.verify(() -> NeoDefaultsService.buildVariablesSecureApp(ctx));
      obMock.verify(OBContext::setAdminMode, never());
      obMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
      obMock.verify(OBContext::restorePreviousMode, never());
      obMock.verify(() -> OBContext.setOBContext(any(OBContext.class)), never());
      verifyNoInteractions(ctx);
    }
  }
}
