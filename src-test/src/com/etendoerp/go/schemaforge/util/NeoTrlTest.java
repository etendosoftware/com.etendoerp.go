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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

/**
 * Unit tests for {@link NeoTrl} — the generic {@code *_Trl} name resolver (ETP-4304).
 * Uses Mockito static mocking of {@link ModelProvider} / {@link OBDal} / {@link OBContext}; no DB.
 */
class NeoTrlTest {

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<ModelProvider> modelProvider;
  private OBDal obDalInstance;
  private ModelProvider modelInstance;

  @BeforeEach
  void setUp() {
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    modelProvider = mockStatic(ModelProvider.class);
    obDalInstance = mock(OBDal.class);
    modelInstance = mock(ModelProvider.class);
    obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
    modelProvider.when(ModelProvider::getInstance).thenReturn(modelInstance);
  }

  @AfterEach
  void tearDown() {
    modelProvider.close();
    obDal.close();
    obContext.close();
  }

  /** Wire the UOM → UOMTrl model (back-ref {@code uOM}, identifier {@code name}). */
  private void stubUomModel() {
    Entity base = mock(Entity.class);
    Entity trl = mock(Entity.class);
    when(base.getName()).thenReturn("UOM");
    when(trl.getName()).thenReturn("UOMTrl");

    Property nameProp = mock(Property.class);
    when(nameProp.getName()).thenReturn("name");
    when(base.getIdentifierProperties()).thenReturn(Collections.singletonList(nameProp));

    Property backRef = mock(Property.class);
    when(backRef.getName()).thenReturn("uOM");
    when(backRef.getTargetEntity()).thenReturn(base);
    when(trl.getProperties()).thenReturn(Arrays.asList(backRef, nameProp));

    when(modelInstance.getEntity(eq("UOM"), eq(false))).thenReturn(base);
    when(modelInstance.getEntity(eq("UOMTrl"), eq(false))).thenReturn(trl);
  }

  @SuppressWarnings("unchecked")
  private OBQuery<BaseOBObject> stubTrlQuery() {
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(obDalInstance.createQuery(eq("UOMTrl"), anyString())).thenReturn(query);
    when(query.setNamedParameter(anyString(), any())).thenReturn(query);
    return query;
  }

  private BaseOBObject trlRow(String uomId, String name) {
    BaseOBObject base = mock(BaseOBObject.class);
    when(base.getId()).thenReturn(uomId);
    BaseOBObject row = mock(BaseOBObject.class);
    when(row.get("uOM")).thenReturn(base);
    when(row.get("name")).thenReturn(name);
    return row;
  }

  @Test
  @DisplayName("translatedNames maps record id → translated name for a translatable entity")
  void translatesNames() {
    stubUomModel();
    OBQuery<BaseOBObject> query = stubTrlQuery();
    // Build the rows first: trlRow() stubs its own mocks, so calling it inside
    // when(query.list()).thenReturn(...) would open a nested stubbing and throw
    // UnfinishedStubbingException.
    BaseOBObject row1 = trlRow("U1", "Centímetro");
    BaseOBObject row2 = trlRow("U2", "Metro");
    when(query.list()).thenReturn(Arrays.asList(row1, row2));

    Map<String, String> result = NeoTrl.translatedNames("UOM", Arrays.asList("U1", "U2"), "es_ES");

    assertEquals("Centímetro", result.get("U1"));
    assertEquals("Metro", result.get("U2"));
    // Trl reads run in admin mode, always restored.
    obContext.verify(() -> OBContext.setAdminMode(true), atLeastOnce());
    obContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }

  @Test
  @DisplayName("translatedNames short-circuits (no model/DB work) for empty inputs")
  void shortCircuitsEmptyInputs() {
    assertTrue(NeoTrl.translatedNames("UOM", Collections.emptyList(), "es_ES").isEmpty());
    assertTrue(NeoTrl.translatedNames("UOM", Arrays.asList("U1"), "  ").isEmpty());
    assertTrue(NeoTrl.translatedNames("  ", Arrays.asList("U1"), "es_ES").isEmpty());
    obContext.verify(() -> OBContext.setAdminMode(true), never());
    verify(obDalInstance, never()).createQuery(anyString(), anyString());
  }

  @Test
  @DisplayName("translatedNames returns empty when the entity has no *_Trl sibling")
  void emptyWhenNoTrlEntity() {
    Entity base = mock(Entity.class);
    when(base.getName()).thenReturn("SomeEntity");
    when(modelInstance.getEntity(eq("SomeEntity"), eq(false))).thenReturn(base);
    when(modelInstance.getEntity(eq("SomeEntityTrl"), eq(false))).thenReturn(null);

    assertTrue(NeoTrl.translatedNames("SomeEntity", Arrays.asList("X1"), "es_ES").isEmpty());
    verify(obDalInstance, never()).createQuery(anyString(), anyString());
  }

  @Test
  @DisplayName("translatedNames swallows query errors and restores admin mode")
  void swallowsQueryErrors() {
    stubUomModel();
    when(obDalInstance.createQuery(eq("UOMTrl"), anyString()))
        .thenThrow(new RuntimeException("boom"));

    assertTrue(NeoTrl.translatedNames("UOM", Arrays.asList("U1"), "es_ES").isEmpty());
    obContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }
}
