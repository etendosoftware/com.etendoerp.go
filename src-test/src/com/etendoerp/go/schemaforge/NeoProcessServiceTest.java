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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/** Tests for {@link NeoProcessService}. */
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
}
