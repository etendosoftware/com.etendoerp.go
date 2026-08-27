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
package com.etendoerp.go.schemaforge.selector.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;

/**
 * Unit tests for {@link SelectorDescriptorResolver#ensureSearchableFallback} —
 * mirrors the classic Etendo behavior where the display field is always part of
 * the suggestion-box search predicate, plus a sane {@code name}/{@code searchKey}
 * fallback for selectors whose display property is an identifier alias.
 */
class SelectorDescriptorResolverFallbackTest {

  private static Entity entityWithProperties(String... properties) {
    Entity entity = mock(Entity.class);
    Set<String> known = new java.util.HashSet<>(Arrays.asList(properties));
    lenient().when(entity.hasProperty(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(inv -> known.contains(inv.<String>getArgument(0)));
    lenient().when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    return entity;
  }

  private static Property primitiveProperty(String name) {
    Property prop = mock(Property.class);
    lenient().when(prop.getName()).thenReturn(name);
    lenient().when(prop.isPrimitive()).thenReturn(true);
    return prop;
  }

  private static Property fkProperty(String name, Entity target) {
    Property prop = mock(Property.class);
    lenient().when(prop.getName()).thenReturn(name);
    lenient().when(prop.isPrimitive()).thenReturn(false);
    lenient().when(prop.getTargetEntity()).thenReturn(target);
    return prop;
  }

  @Test
  @DisplayName("Adds displayProp when entity exposes it as a real DAL property")
  void addsDisplayPropertyWhenPresent() {
    List<String> props = new ArrayList<>();
    Entity entity = entityWithProperties("name", "searchKey");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "name", "searchKey");

    assertEquals(List.of("name"), props);
  }

  @Test
  @DisplayName("Falls back to name + searchKey when displayProp is the identifier alias")
  void fallsBackToNameAndSearchKeyForIdentifierAlias() {
    List<String> props = new ArrayList<>();
    Entity entity = entityWithProperties("name", "searchKey");

    // _identifier is not a real DAL property — addIfPropertyExists rejects it.
    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "_identifier", "searchKey");

    assertTrue(props.contains("name"), "name should be added");
    assertTrue(props.contains("searchKey"), "searchKey should be added");
    assertEquals(2, props.size());
  }

  @Test
  @DisplayName("Skips dotted display paths (nested properties) and falls back")
  void skipsDottedDisplayPath() {
    List<String> props = new ArrayList<>();
    Entity entity = entityWithProperties("name", "searchKey");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity,
        "productCategory.name", "searchKey");

    assertTrue(props.contains("name"));
    assertTrue(props.contains("searchKey"));
  }

  @Test
  @DisplayName("Preserves pre-existing searchable props and still adds displayProp")
  void mergesWithExistingSearchableProps() {
    List<String> props = new ArrayList<>(List.of("upc"));
    Entity entity = entityWithProperties("name", "searchKey", "upc");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "name", "searchKey");

    assertEquals(List.of("upc", "name"), props);
  }

  @Test
  @DisplayName("Does not duplicate displayProp when already present in the list")
  void doesNotDuplicateDisplayProp() {
    List<String> props = new ArrayList<>(List.of("name"));
    Entity entity = entityWithProperties("name", "searchKey");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "name", "searchKey");

    assertEquals(List.of("name"), props);
  }

  @Test
  @DisplayName("Skips valueProp 'id' in the fallback (never useful for ilike)")
  void skipsIdValueProp() {
    List<String> props = new ArrayList<>();
    Entity entity = entityWithProperties("name");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "_identifier", "id");

    assertEquals(List.of("name"), props);
  }

  @Test
  @DisplayName("Returns empty when entity has neither name nor searchKey and displayProp is alias")
  void emptyWhenEntityHasNoUsableProperties() {
    List<String> props = new ArrayList<>();
    Entity entity = entityWithProperties("randomProp");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "_identifier", "id");

    assertTrue(props.isEmpty());
  }

  @Test
  @DisplayName("Skips displayProp 'id' to avoid ilike matches against the PK")
  void skipsIdDisplayProp() {
    List<String> props = new ArrayList<>();
    Entity entity = entityWithProperties("id", "name", "searchKey");

    // findIdentifierProperty falls back to "id" when the entity has no
    // primitive identifier property and no name/searchKey. We must never
    // emit `lower(cast(e.id as string)) LIKE '%q%'` — it produces random
    // UUID matches (e.g. q=04 matching unrelated rows).
    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "id", "id");

    assertTrue(props.contains("name"));
    assertTrue(props.contains("searchKey"));
    assertEquals(2, props.size());
  }

  @Test
  @DisplayName("No-op when entity is null")
  void noOpWhenEntityIsNull() {
    List<String> props = new ArrayList<>(List.of("name"));

    SelectorDescriptorResolver.ensureSearchableFallback(props, null, "name", "searchKey");

    assertEquals(List.of("name"), props);
  }

  @Test
  @DisplayName("Expands a non-primitive identifier FK into its referenced identifier paths")
  void expandsNonPrimitiveIdentifierFk() {
    List<String> props = new ArrayList<>();
    // Build property mocks first — nesting them inside thenReturn(...) trips
    // Mockito's "stubbing inside stubbing" guard.
    Property searchKeyProp = primitiveProperty("searchKey");
    Property nameProp = primitiveProperty("name");
    // Target entity (e.g. C_ElementValue) exposes primitive searchKey + name identifiers.
    Entity refEntity = entityWithProperties();
    when(refEntity.getIdentifierProperties()).thenReturn(List.of(searchKeyProp, nameProp));
    // Source entity (e.g. AccountingCombination): displayProp "combination" + FK "account".
    Property accountFk = fkProperty("account", refEntity);
    Entity entity = entityWithProperties("combination");
    when(entity.getIdentifierProperties()).thenReturn(List.of(accountFk));

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "combination", "id");

    assertTrue(props.contains("combination"), "local display prop kept");
    assertTrue(props.contains("account.searchKey"), "FK searchKey path added");
    assertTrue(props.contains("account.name"), "FK name path added");
    assertEquals(3, props.size());
  }

  @Test
  @DisplayName("Does not add FK paths when the identifier is primitive (no regression)")
  void primitiveIdentifierAddsNoFkPaths() {
    List<String> props = new ArrayList<>();
    Property nameProp = primitiveProperty("name");
    Entity entity = entityWithProperties("name", "searchKey");
    when(entity.getIdentifierProperties()).thenReturn(List.of(nameProp));

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "name", "searchKey");

    assertEquals(List.of("name"), props);
  }

  @Test
  @DisplayName("Skips a FK identifier whose target entity is null (no NPE)")
  void skipsFkWithNullTarget() {
    List<String> props = new ArrayList<>();
    Property accountFk = fkProperty("account", null);
    Entity entity = entityWithProperties("combination");
    when(entity.getIdentifierProperties()).thenReturn(List.of(accountFk));

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "combination", "id");

    assertEquals(List.of("combination"), props);
  }

  // =========================================================================
  // "description" fallback — regression coverage for the IAE Activity Type bug
  // (epiae_type: Key + Description, no explicit OBUISEL_Selector_Field config).
  // Search for "alquiler" matched 0 rows because the fallback only ever added
  // the short identifier key (e.g. "3"), never "description".
  // =========================================================================

  @Test
  @DisplayName("Adds description alongside a short identifier key from displayProp")
  void addsDescriptionAlongsideShortIdentifierKey() {
    List<String> props = new ArrayList<>();
    // Mirrors epiae_type: displayProp resolves to the short key property
    // ("searchKey", e.g. "3"), and the entity also exposes "description"
    // ("Alquiler de viviendas"). Neither is explicitly configured as
    // searchable in AD — both must come from this fallback.
    Entity entity = entityWithProperties("searchKey", "description");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "searchKey", "id");

    assertTrue(props.contains("searchKey"), "short key should still be added");
    assertTrue(props.contains("description"), "description must be added so free-text search works");
    assertEquals(2, props.size());
  }

  @Test
  @DisplayName("Does not add description when the entity has no such property (no-op)")
  void skipsDescriptionWhenEntityHasNone() {
    List<String> props = new ArrayList<>();
    Entity entity = entityWithProperties("searchKey");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "searchKey", "id");

    assertEquals(List.of("searchKey"), props);
  }

  @Test
  @DisplayName("Empty-searchableProps fallback (name/searchKey) still runs when entity also has description")
  void emptyFallbackStillRunsAlongsideDescription() {
    List<String> props = new ArrayList<>();
    // displayProp is an identifier alias (not a real property), so the
    // name/searchKey/valueProp block must still fire even though the entity
    // also has "description" — description must never short-circuit it.
    Entity entity = entityWithProperties("name", "searchKey", "description");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "_identifier", "id");

    assertTrue(props.contains("name"), "name fallback must still run");
    assertTrue(props.contains("searchKey"), "searchKey fallback must still run");
    assertTrue(props.contains("description"), "description must be added too");
    assertEquals(3, props.size());
  }

  @Test
  @DisplayName("Does not duplicate description when already present in the list")
  void doesNotDuplicateDescription() {
    List<String> props = new ArrayList<>(List.of("description"));
    Entity entity = entityWithProperties("searchKey", "description");

    SelectorDescriptorResolver.ensureSearchableFallback(props, entity, "searchKey", "id");

    assertEquals(List.of("description", "searchKey"), props);
  }
}
