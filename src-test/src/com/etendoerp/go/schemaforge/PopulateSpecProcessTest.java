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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;

/** Tests for {@link PopulateSpecProcess}. */
public class PopulateSpecProcessTest {

  @Test
  public void successfulPopulation() throws Exception {
    PopulateSpecProcess process = new PopulateSpecProcess();
    ProcessBundle bundle = mock(ProcessBundle.class);
    Map<String, Object> params = new HashMap<>();
    params.put("ETGO_SF_Spec_ID", "spec-1");
    when(bundle.getParams()).thenReturn(params);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PopulateSpecHelper> helperMock = mockStatic(PopulateSpecHelper.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      helperMock.when(() -> PopulateSpecHelper.populate("spec-1", true))
          .thenReturn(new int[]{3, 15});

      process.doExecute(bundle);

      ArgumentCaptor<OBError> errorCaptor = ArgumentCaptor.forClass(OBError.class);
      verify(bundle).setResult(errorCaptor.capture());
      assertEquals("Success", errorCaptor.getValue().getType());
    }
  }

  @Test
  public void alternativeParamKey() throws Exception {
    PopulateSpecProcess process = new PopulateSpecProcess();
    ProcessBundle bundle = mock(ProcessBundle.class);
    Map<String, Object> params = new HashMap<>();
    params.put("Etgo_SF_Spec_ID", "spec-2");
    when(bundle.getParams()).thenReturn(params);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PopulateSpecHelper> helperMock = mockStatic(PopulateSpecHelper.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      helperMock.when(() -> PopulateSpecHelper.populate("spec-2", true))
          .thenReturn(new int[]{1, 5});

      process.doExecute(bundle);

      ArgumentCaptor<OBError> errorCaptor = ArgumentCaptor.forClass(OBError.class);
      verify(bundle).setResult(errorCaptor.capture());
      assertEquals("Success", errorCaptor.getValue().getType());
    }
  }

  @Test
  public void missingSpecIdSetsError() throws Exception {
    PopulateSpecProcess process = new PopulateSpecProcess();
    ProcessBundle bundle = mock(ProcessBundle.class);
    Map<String, Object> params = new HashMap<>();
    when(bundle.getParams()).thenReturn(params);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      process.doExecute(bundle);

      ArgumentCaptor<OBError> errorCaptor = ArgumentCaptor.forClass(OBError.class);
      verify(bundle).setResult(errorCaptor.capture());
      assertEquals("Error", errorCaptor.getValue().getType());
    }
  }

  @Test
  public void populateHelperExceptionSetsError() throws Exception {
    PopulateSpecProcess process = new PopulateSpecProcess();
    ProcessBundle bundle = mock(ProcessBundle.class);
    Map<String, Object> params = new HashMap<>();
    params.put("ETGO_SF_Spec_ID", "spec-1");
    when(bundle.getParams()).thenReturn(params);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PopulateSpecHelper> helperMock = mockStatic(PopulateSpecHelper.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      helperMock.when(() -> PopulateSpecHelper.populate(anyString(), anyBoolean()))
          .thenThrow(new RuntimeException("Window not found"));

      process.doExecute(bundle);

      ArgumentCaptor<OBError> errorCaptor = ArgumentCaptor.forClass(OBError.class);
      verify(bundle).setResult(errorCaptor.capture());
      assertEquals("Error", errorCaptor.getValue().getType());
    }
  }
}
