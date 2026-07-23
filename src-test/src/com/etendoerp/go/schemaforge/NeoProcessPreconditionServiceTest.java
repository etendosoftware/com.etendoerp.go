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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link NeoProcessPreconditionService}, the generic process-precondition
 * choke-point invoked by {@code NeoProcessService.executeProcess}. The DAL access
 * ({@link OBDal}) is stubbed via {@code mockStatic} so the full resolve → evaluate → build
 * path runs without a database, using the real "Create Amortization" (AD_Process 800125)
 * {@code currency} rule as the fixture.
 */
public class NeoProcessPreconditionServiceTest {

  private static final String CURRENCY_RULE = "{ \"800125\": [ { \"field\": \"currency\" } ] }";
  private static final String ENTITY_NAME = "FinancialMgmtAsset";

  private Process mockProcess() {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("800125");
    when(process.getName()).thenReturn("Create Amortization");
    return process;
  }

  private JSONObject params(String tabId, String recordId) throws Exception {
    JSONObject params = new JSONObject();
    if (tabId != null) {
      params.put("inpTabId", tabId);
    }
    if (recordId != null) {
      params.put("recordId", recordId);
    }
    return params;
  }

  /** Wires an SFEntity (with the given preconditions JSON) resolvable through {@code obdal}. */
  @SuppressWarnings("unchecked")
  private SFEntity stubEntity(OBDal obdal, String preconditionsJson) {
    Table table = mock(Table.class);
    when(table.getName()).thenReturn(ENTITY_NAME);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(tab);
    when(entity.get(NeoProcessPreconditionValidator.PRECONDITIONS_PROPERTY))
        .thenReturn(preconditionsJson);
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(criteria.uniqueResult()).thenReturn(entity);
    when(obdal.createCriteria(SFEntity.class)).thenReturn(criteria);
    return entity;
  }

  @Test
  public void validateReturnsNullWhenNoTabContext() throws Exception {
    // No inpTabId/tabId => generic no-op (fail-open); never touches the DAL.
    assertNull(NeoProcessPreconditionService.validate(mockProcess(), params(null, null)));
  }

  @Test
  public void validateReturnsPreconditionsUnmetWhenFieldMissing() throws Exception {
    OBDal obdal = mock(OBDal.class);
    stubEntity(obdal, CURRENCY_RULE);
    BaseOBObject targetRecord = mock(BaseOBObject.class);
    when(targetRecord.get("currency")).thenReturn(null);
    when(obdal.get(ENTITY_NAME, "A1")).thenReturn(targetRecord);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obdal);

      NeoResponse resp = NeoProcessPreconditionService.validate(mockProcess(), params("T1", "A1"));

      assertNotNull("An unmet precondition must yield a structured response", resp);
      assertEquals(400, resp.getHttpStatus());
      String body = resp.getBody().toString();
      assertTrue("body should carry the PRECONDITIONS_UNMET code, got " + body,
          body.contains("PRECONDITIONS_UNMET"));
      assertTrue("body should list the missing 'currency' field, got " + body,
          body.contains("currency"));
    }
  }

  @Test
  public void validateReturnsNullWhenAllPreconditionsMet() throws Exception {
    OBDal obdal = mock(OBDal.class);
    stubEntity(obdal, CURRENCY_RULE);
    BaseOBObject targetRecord = mock(BaseOBObject.class);
    when(targetRecord.get("currency")).thenReturn("EUR");
    when(obdal.get(ENTITY_NAME, "A1")).thenReturn(targetRecord);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obdal);

      assertNull("All preconditions met must continue to normal execution (null)",
          NeoProcessPreconditionService.validate(mockProcess(), params("T1", "A1")));
    }
  }
}
