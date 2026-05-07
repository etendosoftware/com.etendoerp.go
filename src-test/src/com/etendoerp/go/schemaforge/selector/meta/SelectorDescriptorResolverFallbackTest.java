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
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.base.model.Entity;

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
    return entity;
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
}
