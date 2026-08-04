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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link NeoMethodPolicy} — the single source of truth for the
 * {@code ETGO_SF_ENTITY} HTTP method flags (ETP-4254).
 *
 * <p>Before ETP-4254 this logic was duplicated (live in {@code NeoCrudHandler}, dead in
 * {@code NeoServlet}) and absent from the MCP write path. These tests pin the behaviour
 * the REST path always had, so the shared helper cannot drift from it.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoMethodPolicyTest {

  private static final String SPEC_NAME = "monitor-verifactu";
  private static final String ENTITY_NAME = "header";

  private SFEntity entity(boolean get, boolean getById, boolean post,
      boolean put, boolean patch, boolean delete) {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getName()).thenReturn(ENTITY_NAME);
    when(sfEntity.isGet()).thenReturn(get);
    when(sfEntity.isGetByID()).thenReturn(getById);
    when(sfEntity.isPost()).thenReturn(post);
    when(sfEntity.isPut()).thenReturn(put);
    when(sfEntity.isPatch()).thenReturn(patch);
    when(sfEntity.isDelete()).thenReturn(delete);
    return sfEntity;
  }

  private SFEntity allEnabled() {
    return entity(true, true, true, true, true, true);
  }

  private SFEntity allDisabled() {
    return entity(false, false, false, false, false, false);
  }

  /** A monitor/log entity: readable, every mutation off — the ETP-4254 target shape. */
  private SFEntity readOnlyEntity() {
    return entity(true, true, false, false, false, false);
  }

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<NeoMethodPolicy> constructor = NeoMethodPolicy.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Nested
  @DisplayName("isMethodEnabled")
  class IsMethodEnabled {

    @ParameterizedTest
    @ValueSource(strings = { "GET", "POST", "PUT", "PATCH", "DELETE" })
    void everyMethodEnabledWhenAllFlagsSet(String method) {
      assertTrue(NeoMethodPolicy.isMethodEnabled(allEnabled(), method));
    }

    @ParameterizedTest
    @ValueSource(strings = { "GET", "POST", "PUT", "PATCH", "DELETE" })
    void everyMethodDisabledWhenNoFlagSet(String method) {
      assertFalse(NeoMethodPolicy.isMethodEnabled(allDisabled(), method));
    }

    @Test
    @DisplayName("GET is enabled by ISGET alone")
    void getEnabledByListFlag() {
      assertTrue(NeoMethodPolicy.isMethodEnabled(
          entity(true, false, false, false, false, false), "GET"));
    }

    @Test
    @DisplayName("GET is enabled by ISGETBYID alone")
    void getEnabledByByIdFlag() {
      assertTrue(NeoMethodPolicy.isMethodEnabled(
          entity(false, true, false, false, false, false), "GET"));
    }

    @Test
    @DisplayName("each mutation flag only enables its own method")
    void mutationFlagsAreIndependent() {
      SFEntity postOnly = entity(false, false, true, false, false, false);
      assertTrue(NeoMethodPolicy.isMethodEnabled(postOnly, "POST"));
      assertFalse(NeoMethodPolicy.isMethodEnabled(postOnly, "PUT"));
      assertFalse(NeoMethodPolicy.isMethodEnabled(postOnly, "PATCH"));
      assertFalse(NeoMethodPolicy.isMethodEnabled(postOnly, "DELETE"));
      assertFalse(NeoMethodPolicy.isMethodEnabled(postOnly, "GET"));
    }

    @Test
    @DisplayName("an unknown method is never enabled, even with every flag set")
    void unknownMethodIsRejected() {
      assertFalse(NeoMethodPolicy.isMethodEnabled(allEnabled(), "OPTIONS"));
      assertFalse(NeoMethodPolicy.isMethodEnabled(allEnabled(), "post"));
    }

    @Test
    @DisplayName("null entity or null method enables nothing")
    void nullArgumentsAreRejected() {
      assertFalse(NeoMethodPolicy.isMethodEnabled(null, "GET"));
      assertFalse(NeoMethodPolicy.isMethodEnabled(allEnabled(), null));
    }
  }

  @Nested
  @DisplayName("hasMutableMethod / isReadOnly")
  class Mutability {

    @Test
    void readOnlyEntityHasNoMutableMethod() {
      SFEntity readOnly = readOnlyEntity();
      assertFalse(NeoMethodPolicy.hasMutableMethod(readOnly));
      assertTrue(NeoMethodPolicy.isReadOnly(readOnly));
    }

    @Test
    void anySingleMutationMakesTheEntityMutable() {
      assertTrue(NeoMethodPolicy.hasMutableMethod(entity(true, false, true, false, false, false)));
      assertTrue(NeoMethodPolicy.hasMutableMethod(entity(true, false, false, true, false, false)));
      assertTrue(NeoMethodPolicy.hasMutableMethod(entity(true, false, false, false, true, false)));
      assertTrue(NeoMethodPolicy.hasMutableMethod(entity(true, false, false, false, false, true)));
    }

    @Test
    @DisplayName("a mutable entity is not read-only")
    void mutableEntityIsNotReadOnly() {
      assertFalse(NeoMethodPolicy.isReadOnly(allEnabled()));
    }

    @Test
    @DisplayName("a fully disabled entity is neither mutable nor read-only")
    void fullyDisabledEntityIsNotReadOnly() {
      SFEntity disabled = allDisabled();
      assertFalse(NeoMethodPolicy.hasMutableMethod(disabled));
      assertFalse(NeoMethodPolicy.isReadOnly(disabled));
    }

    @Test
    void nullEntityIsNeitherMutableNorReadOnly() {
      assertFalse(NeoMethodPolicy.hasMutableMethod(null));
      assertFalse(NeoMethodPolicy.isReadOnly(null));
    }
  }

  @Nested
  @DisplayName("enabledMethods")
  class EnabledMethods {

    @Test
    @DisplayName("canonical order, GET listed once for both read flags")
    void canonicalOrder() {
      List<String> methods = NeoMethodPolicy.enabledMethods(allEnabled());
      assertEquals(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE"), methods);
    }

    @Test
    void readOnlyEntityListsOnlyGet() {
      assertEquals(Arrays.asList("GET"), NeoMethodPolicy.enabledMethods(readOnlyEntity()));
    }

    @Test
    void disabledEntityListsNothing() {
      assertTrue(NeoMethodPolicy.enabledMethods(allDisabled()).isEmpty());
      assertTrue(NeoMethodPolicy.enabledMethods(null).isEmpty());
    }
  }

  @Nested
  @DisplayName("messages")
  class Messages {

    @Test
    @DisplayName("REST message keeps the documented 405 wording")
    void restMessageWording() {
      assertEquals("POST not enabled for header",
          NeoMethodPolicy.buildNotEnabledMessage("POST", ENTITY_NAME));
    }

    @Test
    @DisplayName("MCP message names the spec, the entity, the enabled methods and the way out")
    void mcpMessageIsActionable() {
      String message = NeoMethodPolicy.buildMcpNotEnabledMessage(
          SPEC_NAME, ENTITY_NAME, "POST", readOnlyEntity());

      assertTrue(message.contains(SPEC_NAME), message);
      assertTrue(message.contains(ENTITY_NAME), message);
      assertTrue(message.contains("does not enable POST"), message);
      assertTrue(message.contains("Enabled methods: GET"), message);
      assertTrue(message.contains("read-only"), message);
      assertTrue(message.contains("CRUD writes"), message);
      assertTrue(message.contains("neo_action"), message);
      assertTrue(message.contains("Do not retry"), message);
    }

    @Test
    @DisplayName("MCP message reports 'none' and no read-only claim for a disabled entity")
    void mcpMessageForFullyDisabledEntity() {
      String message = NeoMethodPolicy.buildMcpNotEnabledMessage(
          SPEC_NAME, ENTITY_NAME, "PUT", allDisabled());

      assertTrue(message.contains("Enabled methods: none"), message);
      assertFalse(message.contains("read-only"), message);
      assertTrue(message.contains("neo_discover"), message);
    }

    @Test
    @DisplayName("MCP message tolerates a null entity")
    void mcpMessageWithNullEntity() {
      String message = NeoMethodPolicy.buildMcpNotEnabledMessage(
          SPEC_NAME, ENTITY_NAME, "DELETE", null);

      assertTrue(message.contains("Enabled methods: none"), message);
    }
  }
}
