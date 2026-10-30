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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.GregorianCalendar;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * {@link McpPeriodRefusal}: which action refusals it enriches, and the {@code period} it names —
 * the period covering the record's accounting date in the controlling organization's calendar,
 * with its status for the document's category, or {@code exists:false}.
 *
 * @covers com.etendoerp.go.mcp.McpPeriodRefusal
 */
class McpPeriodRefusalTest {

  private static final String RECORD_ID = "REC";
  private static final String ENTITY_NAME = "MaterialMgmtShipmentInOut";

  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private SFEntity sfEntity;
  private Entity entity;
  private BaseOBObject document;
  private OBCriteria<Period> periodCrit;
  private OBCriteria<PeriodControl> controlCrit;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    sfEntity = mock(SFEntity.class, RETURNS_DEEP_STUBS);
    when(sfEntity.getADTab().getTable().getDBTableName()).thenReturn("M_InOut");
    entity = mock(Entity.class);
    when(entity.getName()).thenReturn(ENTITY_NAME);
    when(entity.hasProperty(anyString())).thenReturn(true);
    ModelProvider provider = mock(ModelProvider.class);
    when(provider.getEntityByTableName("M_InOut")).thenReturn(entity);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(provider);

    document = mock(BaseOBObject.class);
    Organization org = mock(Organization.class, RETURNS_DEEP_STUBS);
    when(org.getClient().getId()).thenReturn("CLIENT");
    when(document.get("accountingDate"))
        .thenReturn(new GregorianCalendar(2026, java.util.Calendar.DECEMBER, 15).getTime());
    when(document.get("organization")).thenReturn(org);
    DocumentType docType = mock(DocumentType.class);
    when(docType.getDocumentCategory()).thenReturn("MMR");
    when(document.get("documentType")).thenReturn(docType);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(ENTITY_NAME, RECORD_ID)).thenReturn(document);
    periodCrit = mock(OBCriteria.class);
    controlCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(Period.class)).thenReturn(periodCrit);
    when(obDal.createCriteria(PeriodControl.class)).thenReturn(controlCrit);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);

    Organization controlling = mock(Organization.class);
    when(controlling.getCalendar()).thenReturn(mock(Calendar.class));
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    when(osp.getPeriodControlAllowedOrganization(org)).thenReturn(controlling);
    OBContext obContext = mock(OBContext.class);
    when(obContext.getOrganizationStructureProvider("CLIENT")).thenReturn(osp);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
  }

  @AfterEach
  void tearDown() {
    modelProviderMock.close();
    obDalMock.close();
    obContextMock.close();
  }

  private static JSONObject keyed(String key) throws Exception {
    return new JSONObject().put("messageKeys", new JSONArray().put(key));
  }

  @Test
  void recognisesBothPeriodKeysFlatAndNested() throws Exception {
    assertTrue(McpPeriodRefusal.isPeriodRefusal(keyed("PeriodNotAvailable")));
    assertTrue(McpPeriodRefusal.isPeriodRefusal(keyed("PeriodNotValid")));
    assertTrue(McpPeriodRefusal.isPeriodRefusal(
        new JSONObject().put("error", keyed("PeriodNotAvailable"))));
  }

  @Test
  void otherRefusalsAreNotPeriodRefusals() throws Exception {
    assertFalse(McpPeriodRefusal.isPeriodRefusal(keyed("20501")));
    assertFalse(McpPeriodRefusal.isPeriodRefusal(new JSONObject().put("processMessage", "x")));
  }

  @Test
  void leavesAnyOtherRefusalUntouchedWithoutReadingTheRecord() throws Exception {
    SFEntity untouched = mock(SFEntity.class);
    JSONObject body = keyed("20501");

    McpPeriodRefusal.addTo(body, untouched, RECORD_ID);

    assertFalse(body.has(McpPeriodRefusal.KEY_PERIOD));
    verifyNoInteractions(untouched);
  }

  @Test
  void namesThePeriodAndItsStatusForTheDocumentCategory() throws Exception {
    Period period = mock(Period.class);
    when(period.getId()).thenReturn("PERIOD");
    when(period.getIdentifier()).thenReturn("Dec-26");
    when(periodCrit.uniqueResult()).thenReturn(period);
    PeriodControl control = mock(PeriodControl.class);
    when(control.getPeriodStatus()).thenReturn("N");
    when(controlCrit.uniqueResult()).thenReturn(control);
    JSONObject body = keyed("PeriodNotAvailable");

    McpPeriodRefusal.addTo(body, sfEntity, RECORD_ID);

    JSONObject named = body.getJSONObject(McpPeriodRefusal.KEY_PERIOD);
    assertEquals("2026-12-15", named.getString("accountingDate"));
    assertEquals("PERIOD", named.getString("id"));
    assertEquals("Dec-26", named.getString("name"));
    assertEquals("MMR", named.getString("documentCategory"));
    assertEquals("neverOpened", named.getString("status"));
  }

  @Test
  void saysTheDateHasNoPeriodWhenTheCalendarHasNone() throws Exception {
    when(periodCrit.uniqueResult()).thenReturn(null);
    JSONObject body = keyed("PeriodNotValid");

    McpPeriodRefusal.addTo(body, sfEntity, RECORD_ID);

    JSONObject named = body.getJSONObject(McpPeriodRefusal.KEY_PERIOD);
    assertEquals("2026-12-15", named.getString("accountingDate"));
    assertFalse(named.getBoolean("exists"));
    assertFalse(named.has("id"));
  }

  @Test
  void addsNothingForARecordWithoutAnAccountingDate() throws Exception {
    when(entity.hasProperty("accountingDate")).thenReturn(false);
    JSONObject body = keyed("PeriodNotAvailable");

    McpPeriodRefusal.addTo(body, sfEntity, RECORD_ID);

    assertFalse(body.has(McpPeriodRefusal.KEY_PERIOD));
  }

  @Test
  void aFailedLookupLeavesTheRefusalAsItWas() throws Exception {
    when(periodCrit.uniqueResult()).thenThrow(new IllegalStateException("db down"));
    when(periodCrit.add(any())).thenReturn(periodCrit);
    JSONObject body = keyed("PeriodNotAvailable");

    McpPeriodRefusal.addTo(body, sfEntity, RECORD_ID);

    assertFalse(body.has(McpPeriodRefusal.KEY_PERIOD));
  }
}
