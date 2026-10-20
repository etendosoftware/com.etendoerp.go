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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.exception.GenericJDBCException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.DataToJsonConverter;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.service.json.JsonToDataConverter;
import org.openbravo.service.json.JsonUtils;

/**
 * Unit tests for NEO's write service: it persists like core's — committing a single write, leaving
 * a batch's transaction to the batch — and keeps the exception of a failed write so the failure is
 * classified by what it is (ETP-5782).
 *
 * @covers com.etendoerp.go.schemaforge.NeoBatchJsonDataService
 */
class NeoBatchJsonDataServiceTest {

  private static final String CORE_FAILURE =
      "{\"response\":{\"status\":-1,\"error\":{\"message\":\"m\"}}}";

  /** Core's pre/post actions run the injected extensions, which do not exist outside Etendo. */
  private static class TestableService extends NeoBatchJsonDataService {
    @Override
    protected String doPreAction(Map<String, String> parameters, String content,
        DataSourceAction action) {
      return content;
    }

    @Override
    protected String doPostAction(Map<String, String> parameters, String content,
        DataSourceAction action, String originalObject) {
      return content;
    }
  }

  private TestableService service;
  private OBDal dal;
  private OBProvider provider;
  private BaseOBObject bob;

  @BeforeEach
  void setUp() throws Exception {
    // DefaultJsonDataService's static initialiser asks Weld for its singleton.
    try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      service = new TestableService();
    }
    dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(mock(Session.class));
    provider = mock(OBProvider.class);
    bob = mock(BaseOBObject.class);
    Entity entity = mock(Entity.class);
    when(bob.getEntity()).thenReturn(entity);
    when(bob.getEntityName()).thenReturn("Order");
    when(bob.getId()).thenReturn("A");
    when(dal.get("Order", "A")).thenReturn(bob);
    DataToJsonConverter toJson = mock(DataToJsonConverter.class);
    when(toJson.toJsonObjects(any())).thenReturn(List.of(new JSONObject().put("id", "A")));
    when(provider.get(DataToJsonConverter.class)).thenReturn(toJson);
    JsonToDataConverter fromJson = mock(JsonToDataConverter.class);
    when(fromJson.toBaseOBObject(any())).thenReturn(bob);
    when(provider.get(JsonToDataConverter.class)).thenReturn(fromJson);
  }

  private Map<String, String> params() {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, "Order");
    params.put(JsonConstants.ID, "A");
    return params;
  }

  @Test
  @DisplayName("a single write commits, as core does")
  void singleWriteCommits() {
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obdal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> prov = mockStatic(OBProvider.class);
        MockedStatic<JsonUtils> json = mockStatic(JsonUtils.class)) {
      obdal.when(OBDal::getInstance).thenReturn(dal);
      prov.when(OBProvider::getInstance).thenReturn(provider);
      json.when(() -> JsonUtils.getAdditionalProperties(any())).thenReturn(Collections.emptyList());

      String result = service.update(params(), "{\"data\":{\"id\":\"A\"}}");

      assertNotNull(result);
      verify(dal).commitAndClose();
    }
  }

  @Test
  @DisplayName("a write inside a batch leaves the commit to the batch")
  void batchWriteDefersTheCommit() {
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obdal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> prov = mockStatic(OBProvider.class);
        MockedStatic<JsonUtils> json = mockStatic(JsonUtils.class)) {
      obdal.when(OBDal::getInstance).thenReturn(dal);
      prov.when(OBProvider::getInstance).thenReturn(provider);
      json.when(() -> JsonUtils.getAdditionalProperties(any())).thenReturn(Collections.emptyList());
      BatchService.beginCallerOwnedTransaction();
      try {
        service.update(params(), "{\"data\":{\"id\":\"A\"}}");
      } finally {
        BatchService.endCallerOwnedTransaction();
      }

      verify(dal, never()).commitAndClose();
    }
  }

  @Test
  @DisplayName("a delete refused because the row is still referenced is classified 409 record_in_use")
  void deleteOfAReferencedRowIsRecordInUse() throws Exception {
    // Core's delete commits the response first, then removes in a transaction of its own.
    doThrow(new GenericJDBCException("could not execute statement",
        new SQLException("ERROR: is still referenced from table \"c_orderline\"", "23503")))
        .when(dal).remove(bob);
    try (MockedStatic<OBDal> obdal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> prov = mockStatic(OBProvider.class);
        MockedStatic<JsonUtils> json = mockStatic(JsonUtils.class)) {
      obdal.when(OBDal::getInstance).thenReturn(dal);
      prov.when(OBProvider::getInstance).thenReturn(provider);
      json.when(() -> JsonUtils.convertExceptionToJson(any())).thenReturn(CORE_FAILURE);

      JSONObject result = new JSONObject(service.remove(params()));

      JSONObject classification = NeoErrorResponses.classificationOf(
          result.getJSONObject(JsonConstants.RESPONSE_RESPONSE));
      assertNotNull(classification);
      assertEquals(409, classification.getInt("status"));
      assertEquals(NeoErrorClassifier.CODE_RECORD_IN_USE, classification.getString("code"));
      verify(dal, times(1)).commitAndClose();
    }
  }

  @Test
  @DisplayName("a deferred trigger refusing the commit of a single write is still classified")
  void commitTimeRefusalIsClassified() throws Exception {
    doThrow(new GenericJDBCException("could not commit",
        new SQLException("ERROR: @20502@", "P0001"))).when(dal).commitAndClose();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obdal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> prov = mockStatic(OBProvider.class);
        MockedStatic<JsonUtils> json = mockStatic(JsonUtils.class);
        MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msg =
            mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
      obdal.when(OBDal::getInstance).thenReturn(dal);
      prov.when(OBProvider::getInstance).thenReturn(provider);
      json.when(() -> JsonUtils.getAdditionalProperties(any())).thenReturn(Collections.emptyList());
      json.when(() -> JsonUtils.convertExceptionToJson(any())).thenReturn(CORE_FAILURE);
      msg.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.translateError(any(), any(),
          any(), anyString())).thenReturn(new org.openbravo.erpCommon.utility.OBError());

      JSONObject result = new JSONObject(service.update(params(), "{\"data\":{\"id\":\"A\"}}"));

      JSONObject classification = NeoErrorResponses.classificationOf(
          result.getJSONObject(JsonConstants.RESPONSE_RESPONSE));
      assertEquals(NeoErrorClassifier.CODE_BUSINESS_RULE, classification.getString("code"));
    }
  }
}
