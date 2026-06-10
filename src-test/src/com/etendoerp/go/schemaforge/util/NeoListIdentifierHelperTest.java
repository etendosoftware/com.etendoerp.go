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

package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Unit tests for {@link NeoListIdentifierHelper#enrichListIdentifiers}.
 *
 * <p>Uses {@link MockedStatic} for {@link OBDal}, {@link ModelProvider}, and
 * {@link NeoSelectorService} so no live database is required.</p>
 */
public class NeoListIdentifierHelperTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<NeoSelectorService> selectorServiceMock;
  private OBDal dal;
  private ModelProvider modelProvider;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    modelProvider = mock(ModelProvider.class);

    obDalMock = mockStatic(OBDal.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    selectorServiceMock = mockStatic(NeoSelectorService.class);

    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
  }

  @After
  public void tearDown() {
    selectorServiceMock.close();
    modelProviderMock.close();
    obDalMock.close();
  }

  // ── null sfEntity guard ───────────────────────────────────────────────────

  @Test
  public void enrichListIdentifiers_nullSfEntity_doesNothing() throws Exception {
    JSONObject response = new JSONObject();
    response.put("response", new JSONObject().put("data", new JSONArray()));
    // Must not throw
    NeoListIdentifierHelper.enrichListIdentifiers(response, null);
  }

  // ── sfEntity with null adTab ──────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_sfEntityWithNullAdTab_doesNothing() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-1");
    when(sfEntity.getADTab()).thenReturn(null);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    JSONObject response = buildResponseJson(new JSONArray());
    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);
    // No identifier keys should have been added (nothing to add)
    assertFalse(response.toString().contains("$_identifier"));
  }

  // ── sfEntity with adTab but null table ────────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_sfEntityWithNullTable_doesNothing() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-2");
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(null);
    when(sfEntity.getADTab()).thenReturn(tab);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    JSONObject response = buildResponseJson(new JSONArray());
    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);
    assertFalse(response.toString().contains("$_identifier"));
  }

  // ── sfEntity with adTab and table but dal entity not found ────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_dalEntityNotFound_doesNothing() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-3");
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("c_order");
    when(tab.getTable()).thenReturn(table);
    when(sfEntity.getADTab()).thenReturn(tab);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    when(modelProvider.getEntityByTableName("c_order")).thenReturn(null);

    JSONObject response = buildResponseJson(new JSONArray());
    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);
    assertFalse(response.toString().contains("$_identifier"));
  }

  // ── list field with reference 17 is enriched ─────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_listField_addsIdentifier() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-4");

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("c_order");
    when(tab.getTable()).thenReturn(table);
    when(sfEntity.getADTab()).thenReturn(tab);

    // DAL entity: has a property "docType" mapped to DB column "C_DocType_ID"
    Entity dalEntity = mock(Entity.class);
    when(modelProvider.getEntityByTableName("c_order")).thenReturn(dalEntity);

    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn("docType");
    when(dalEntity.getPropertyByColumnName("DOC_STATUS")).thenReturn(prop);

    // Reference type 17 — List
    org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
    when(ref.getId()).thenReturn("17");

    // Reference search key (list reference ID)
    org.openbravo.model.ad.domain.Reference refSK =
        mock(org.openbravo.model.ad.domain.Reference.class);
    when(refSK.getId()).thenReturn("docstatus-ref-list-id");

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("DOC_STATUS");
    when(col.getReference()).thenReturn(ref);
    when(col.getReferenceSearchKey()).thenReturn(refSK);

    SFField sfField = mock(SFField.class);
    when(sfField.isIncluded()).thenReturn(Boolean.TRUE);
    when(sfField.getADColumn()).thenReturn(col);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(sfField));

    // Selector service returns labels map
    Map<String, String> labels = new HashMap<>();
    labels.put("CO", "Completed");
    labels.put("DR", "Draft");
    selectorServiceMock.when(
        () -> NeoSelectorService.getListLabels("docstatus-ref-list-id")).thenReturn(labels);

    // Build a response JSON with a single record having docType = "CO"
    JSONObject recordJson = new JSONObject();
    recordJson.put("docType", "CO");
    JSONArray dataArray = new JSONArray();
    dataArray.put(recordJson);
    JSONObject response = buildResponseJson(dataArray);

    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);

    // After enrichment, the record must have docType$_identifier = "Completed"
    JSONObject enrichedRecord = response.getJSONObject("response").getJSONArray("data")
        .getJSONObject(0);
    assertEquals("Completed", enrichedRecord.optString("docType$_identifier"));
  }

  // ── list field with unknown value (no label) ──────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_unknownValue_doesNotAddIdentifier() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-5");

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("c_invoice");
    when(tab.getTable()).thenReturn(table);
    when(sfEntity.getADTab()).thenReturn(tab);

    Entity dalEntity = mock(Entity.class);
    when(modelProvider.getEntityByTableName("c_invoice")).thenReturn(dalEntity);

    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn("status");
    when(dalEntity.getPropertyByColumnName("STATUS_COL")).thenReturn(prop);

    org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
    when(ref.getId()).thenReturn("17");

    org.openbravo.model.ad.domain.Reference refSK =
        mock(org.openbravo.model.ad.domain.Reference.class);
    when(refSK.getId()).thenReturn("status-list-ref");

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("STATUS_COL");
    when(col.getReference()).thenReturn(ref);
    when(col.getReferenceSearchKey()).thenReturn(refSK);

    SFField sfField = mock(SFField.class);
    when(sfField.isIncluded()).thenReturn(Boolean.TRUE);
    when(sfField.getADColumn()).thenReturn(col);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(sfField));

    // No label for "UNKNOWN_STATUS"
    selectorServiceMock.when(
        () -> NeoSelectorService.getListLabels("status-list-ref"))
        .thenReturn(Collections.emptyMap());

    JSONObject recordJson = new JSONObject();
    recordJson.put("status", "UNKNOWN_STATUS");
    JSONArray dataArray = new JSONArray();
    dataArray.put(recordJson);
    JSONObject response = buildResponseJson(dataArray);

    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);

    JSONObject enrichedRecord = response.getJSONObject("response").getJSONArray("data")
        .getJSONObject(0);
    assertNull("No identifier should be added for unknown value",
        enrichedRecord.opt("status$_identifier"));
  }

  // ── single record response (not array) ───────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_singleRecordResponse_addsIdentifier() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-6");

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("c_order");
    when(tab.getTable()).thenReturn(table);
    when(sfEntity.getADTab()).thenReturn(tab);

    Entity dalEntity = mock(Entity.class);
    when(modelProvider.getEntityByTableName("c_order")).thenReturn(dalEntity);

    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn("priority");
    when(dalEntity.getPropertyByColumnName("PRIORITY_COL")).thenReturn(prop);

    org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
    when(ref.getId()).thenReturn("17");
    // no reference search key — fall back to ref.getId()
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("PRIORITY_COL");
    when(col.getReference()).thenReturn(ref);
    when(col.getReferenceSearchKey()).thenReturn(null);

    SFField sfField = mock(SFField.class);
    when(sfField.isIncluded()).thenReturn(Boolean.TRUE);
    when(sfField.getADColumn()).thenReturn(col);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(sfField));

    Map<String, String> labels = new HashMap<>();
    labels.put("H", "High");
    selectorServiceMock.when(
        () -> NeoSelectorService.getListLabels("17")).thenReturn(labels);

    // Single record, not inside array
    JSONObject recordJson = new JSONObject();
    recordJson.put("priority", "H");
    JSONObject inner = new JSONObject();
    inner.put("data", recordJson); // note: single object, not array
    JSONObject response = new JSONObject();
    response.put("response", inner);

    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);

    JSONObject enrichedRecord = response.getJSONObject("response").getJSONObject("data");
    assertEquals("High", enrichedRecord.optString("priority$_identifier"));
  }

  // ── field not included (isIncluded = false) ───────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_fieldNotIncluded_doesNotAddIdentifier() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-7");

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("c_order");
    when(tab.getTable()).thenReturn(table);
    when(sfEntity.getADTab()).thenReturn(tab);

    Entity dalEntity = mock(Entity.class);
    when(modelProvider.getEntityByTableName("c_order")).thenReturn(dalEntity);

    SFField sfField = mock(SFField.class);
    when(sfField.isIncluded()).thenReturn(Boolean.FALSE); // excluded
    Column col = mock(Column.class);
    when(sfField.getADColumn()).thenReturn(col);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(sfField));

    JSONObject recordJson = new JSONObject();
    recordJson.put("someField", "A");
    JSONArray dataArray = new JSONArray();
    dataArray.put(recordJson);
    JSONObject response = buildResponseJson(dataArray);

    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);
    assertFalse(response.toString().contains("$_identifier"));
  }

  // ── field with non-17 reference ───────────────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void enrichListIdentifiers_nonListReference_doesNotAddIdentifier() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn("entity-id-8");

    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("c_order");
    when(tab.getTable()).thenReturn(table);
    when(sfEntity.getADTab()).thenReturn(tab);

    Entity dalEntity = mock(Entity.class);
    when(modelProvider.getEntityByTableName("c_order")).thenReturn(dalEntity);

    // Reference type 19 (TableDir) — NOT a list reference
    org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
    when(ref.getId()).thenReturn("19");

    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("C_BPARTNER_ID");
    when(col.getReference()).thenReturn(ref);

    SFField sfField = mock(SFField.class);
    when(sfField.isIncluded()).thenReturn(Boolean.TRUE);
    when(sfField.getADColumn()).thenReturn(col);

    OBCriteria<SFField> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFField.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(sfField));

    JSONObject recordJson = new JSONObject();
    recordJson.put("businessPartner", "bp-id-123");
    JSONArray dataArray = new JSONArray();
    dataArray.put(recordJson);
    JSONObject response = buildResponseJson(dataArray);

    NeoListIdentifierHelper.enrichListIdentifiers(response, sfEntity);
    assertFalse(response.toString().contains("$_identifier"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private JSONObject buildResponseJson(JSONArray dataArray) throws Exception {
    JSONObject inner = new JSONObject();
    inner.put("data", dataArray);
    JSONObject response = new JSONObject();
    response.put("response", inner);
    return response;
  }
}
