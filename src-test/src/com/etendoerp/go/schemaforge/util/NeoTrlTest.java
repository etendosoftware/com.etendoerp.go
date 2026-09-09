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
  private Entity uomBaseEntity;

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
    when(base.getProperties()).thenReturn(Collections.singletonList(nameProp));
    uomBaseEntity = base;

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

  @Test
  @DisplayName("resolveSearchMeta returns the *_Trl metadata for a translatable entity")
  void resolveSearchMetaForTranslatable() {
    stubUomModel();
    NeoTrl.TrlSearchMeta meta = NeoTrl.resolveSearchMeta("UOM");
    assertNotNull(meta);
    assertEquals("UOMTrl", meta.trlEntityName);
    assertEquals("uOM", meta.backRefProperty);
    assertEquals("name", meta.nameProperty);
  }

  @Test
  @DisplayName("resolveSearchMeta returns null when the entity has no *_Trl sibling")
  void resolveSearchMetaNonTranslatable() {
    Entity base = mock(Entity.class);
    when(base.getName()).thenReturn("SomeEntity");
    when(modelInstance.getEntity(eq("SomeEntity"), eq(false))).thenReturn(base);
    when(modelInstance.getEntity(eq("SomeEntityTrl"), eq(false))).thenReturn(null);

    assertNull(NeoTrl.resolveSearchMeta("SomeEntity"));
  }

  @Test
  @DisplayName("resolveSearchMeta returns null for blank input")
  void resolveSearchMetaBlank() {
    assertNull(NeoTrl.resolveSearchMeta(null));
    assertNull(NeoTrl.resolveSearchMeta("  "));
  }

  /**
   * A {@code UOMTrl} row for {@code translatedName} whose base UOM holds {@code baseName} in its
   * own name column.
   *
   * <p>{@code getIdentifier()} is stubbed to the TRANSLATED name on purpose: that is what
   * Openbravo's identifier provider really returns under a translated context (verified against
   * a live es_ES instance — Algeria's identifier comes back as "Argelia"). An earlier version of
   * this helper stubbed the identifier to the BASE name, which encoded the assumption under test
   * instead of reality, so it happily passed while the production code was silently a no-op for
   * every translated term. Any resolver that reaches for the identifier now fails here.
   */
  /**
   * A {@code UOMTrl} row whose base record carries {@code baseName} and whose own name is
   * {@code translatedName}, with {@code getIdentifier()} stubbed to the translated name the way
   * Openbravo really returns it under a non-base language.
   *
   * <p>Call this BEFORE opening a stubbing: it stubs mocks of its own, so evaluating it inside
   * {@code when(query.list()).thenReturn(...)} nests one stubbing in another and Mockito throws
   * {@code UnfinishedStubbingException}.</p>
   */
  private BaseOBObject trlRowFor(String baseName, String translatedName) {
    BaseOBObject base = mock(BaseOBObject.class);
    when(base.getEntity()).thenReturn(uomBaseEntity);
    when(base.get("name")).thenReturn(baseName);
    when(base.getIdentifier()).thenReturn(translatedName);
    BaseOBObject row = mock(BaseOBObject.class);
    when(row.get("uOM")).thenReturn(base);
    when(row.get("name")).thenReturn(translatedName);
    return row;
  }

  @Test
  @DisplayName("baseNameForTranslation rewrites a session-language term into its base name")
  void baseNameForTranslationRewrites() {
    // The bug this fixes: trigram similarity only ever compares against the base row, so
    // "Unidad" scores 0.333 against "Unit" and resolves nothing. Handing the matcher "Unit"
    // instead lets it match at 100% without changing the matcher itself.
    stubUomModel();
    OBQuery<BaseOBObject> query = stubTrlQuery();
    BaseOBObject row = trlRowFor("Unit", "Unidad");
    when(query.list()).thenReturn(Collections.singletonList(row));

    assertEquals("Unit", NeoTrl.baseNameForTranslation("UOM", "Unidad", "es_ES"));
    obContext.verify(() -> OBContext.setAdminMode(true), atLeastOnce());
    obContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }

  @Test
  @DisplayName("baseNameForTranslation ignores surrounding whitespace in the typed term")
  void baseNameForTranslationTrims() {
    stubUomModel();
    OBQuery<BaseOBObject> query = stubTrlQuery();
    BaseOBObject row = trlRowFor("Unit", "Unidad");
    when(query.list()).thenReturn(Collections.singletonList(row));

    assertEquals("Unit", NeoTrl.baseNameForTranslation("UOM", "  Unidad  ", "es_ES"));
  }

  @Test
  @DisplayName("baseNameForTranslation refuses to rewrite an ambiguous term")
  void baseNameForTranslationAmbiguous() {
    // Two different base rows share one translated name; picking either would be arbitrary,
    // so the original term goes to the matcher untouched and the user disambiguates.
    stubUomModel();
    OBQuery<BaseOBObject> query = stubTrlQuery();
    BaseOBObject unit = trlRowFor("Unit", "Unidad");
    BaseOBObject each = trlRowFor("Each", "Unidad");
    when(query.list()).thenReturn(Arrays.asList(unit, each));

    assertNull(NeoTrl.baseNameForTranslation("UOM", "Unidad", "es_ES"));
  }

  @Test
  @DisplayName("baseNameForTranslation returns null when the translation IS the base name")
  void baseNameForTranslationNoOp() {
    // A base-language session (or a country named the same in both languages, e.g. Argentina)
    // has nothing to rewrite. Returning null keeps that request on exactly the path it takes
    // today rather than round-tripping an identical term.
    stubUomModel();
    OBQuery<BaseOBObject> query = stubTrlQuery();
    BaseOBObject row = trlRowFor("Unit", "unit");
    when(query.list()).thenReturn(Collections.singletonList(row));

    assertNull(NeoTrl.baseNameForTranslation("UOM", "Unit", "en_US"));
  }

  @Test
  @DisplayName("baseNameForTranslation short-circuits blank inputs with no model or DB work")
  void baseNameForTranslationBlankInputs() {
    assertNull(NeoTrl.baseNameForTranslation("  ", "Unidad", "es_ES"));
    assertNull(NeoTrl.baseNameForTranslation("UOM", "  ", "es_ES"));
    assertNull(NeoTrl.baseNameForTranslation("UOM", "Unidad", null));
    obContext.verify(() -> OBContext.setAdminMode(true), never());
    verify(obDalInstance, never()).createQuery(anyString(), anyString());
  }

  @Test
  @DisplayName("baseNameForTranslation returns null for an entity with no *_Trl sibling")
  void baseNameForTranslationNonTranslatable() {
    Entity base = mock(Entity.class);
    when(base.getName()).thenReturn("SomeEntity");
    when(modelInstance.getEntity(eq("SomeEntity"), eq(false))).thenReturn(base);
    when(modelInstance.getEntity(eq("SomeEntityTrl"), eq(false))).thenReturn(null);

    assertNull(NeoTrl.baseNameForTranslation("SomeEntity", "Cualquiera", "es_ES"));
    verify(obDalInstance, never()).createQuery(anyString(), anyString());
  }

  @Test
  @DisplayName("baseNameForTranslation swallows query errors and restores admin mode")
  void baseNameForTranslationSwallowsErrors() {
    // A failing translation lookup must degrade to "search the term as typed", never to a
    // failed simsearch request — the caller is an import preview with 5000 other rows to show.
    stubUomModel();
    when(obDalInstance.createQuery(eq("UOMTrl"), anyString()))
        .thenThrow(new RuntimeException("boom"));

    assertNull(NeoTrl.baseNameForTranslation("UOM", "Unidad", "es_ES"));
    obContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }

  @Test
  @DisplayName("pickUniqueBaseName collapses repeated rows for one base record")
  void pickUniqueBaseNameCollapsesDuplicates() {
    // Duplicate trl rows for the same base record are not ambiguity — they name one winner.
    assertEquals("Unit", NeoTrl.pickUniqueBaseName("Unidad", Arrays.asList("Unit", "unit")));
  }

  @Test
  @DisplayName("pickUniqueBaseName skips blank names and returns null when none remain")
  void pickUniqueBaseNameSkipsBlanks() {
    assertEquals("Unit", NeoTrl.pickUniqueBaseName("Unidad", Arrays.asList(null, "  ", "Unit")));
    assertNull(NeoTrl.pickUniqueBaseName("Unidad", Arrays.asList(null, "  ")));
    assertNull(NeoTrl.pickUniqueBaseName("Unidad", Collections.emptyList()));
  }

  @Test
  @DisplayName("baseNameForTranslation reads the base name column, never getIdentifier()")
  void baseNameForTranslationIgnoresTranslatedIdentifier() {
    // The regression this locks down: Openbravo's identifier provider honours the context
    // language, so under es_ES the base row's identifier IS the translated name. Resolving
    // through it returned the same term that was passed in, pickUniqueBaseName's
    // same-as-input guard discarded it as a no-op, and translation silently never happened
    // for any term that actually had a translation — i.e. for every term that needed it.
    stubUomModel();
    BaseOBObject baseRow = mock(BaseOBObject.class);
    when(baseRow.getEntity()).thenReturn(uomBaseEntity);
    when(baseRow.get("name")).thenReturn("Unit");
    when(baseRow.getIdentifier()).thenReturn("Unidad");   // what Openbravo really returns
    BaseOBObject row = mock(BaseOBObject.class);
    when(row.get("uOM")).thenReturn(baseRow);
    when(row.get("name")).thenReturn("Unidad");
    OBQuery<BaseOBObject> query = stubTrlQuery();
    when(query.list()).thenReturn(Collections.singletonList(row));

    assertEquals("Unit", NeoTrl.baseNameForTranslation("UOM", "Unidad", "es_ES"));
    verify(baseRow, never()).getIdentifier();
  }

  @Test
  @DisplayName("baseNameForTranslation skips a base row that has no name property")
  void baseNameForTranslationNoNameProperty() {
    // Defensive: resolveNameProperty can fall back to the conventional "name" column off the
    // TRL side, which the base entity is not guaranteed to expose. No property means no
    // rewrite, never an exception.
    stubUomModel();
    Entity bare = mock(Entity.class);
    when(bare.getProperties()).thenReturn(Collections.emptyList());
    BaseOBObject base = mock(BaseOBObject.class);
    when(base.getEntity()).thenReturn(bare);
    BaseOBObject row = mock(BaseOBObject.class);
    when(row.get("uOM")).thenReturn(base);
    OBQuery<BaseOBObject> query = stubTrlQuery();
    when(query.list()).thenReturn(Collections.singletonList(row));

    assertNull(NeoTrl.baseNameForTranslation("UOM", "Unidad", "es_ES"));
  }
}
