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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoReportCallability} — the single source of truth for report
 * callability and the canonical {@code not_configured_for_report_generation} contract
 * (ETP-4255).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoReportCallabilityTest {

  private static final String SPEC_ID = "report-spec-id";
  private static final String SPEC_NAME = "invoice-report";

  @SuppressWarnings("unchecked")
  private OBCriteria<SFEntity> stubEntityCriteria(OBDal obDal, List<SFEntity> entities) {
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.list()).thenReturn(entities);
    return criteria;
  }

  private SFSpec mockSpec() {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn(SPEC_ID);
    when(spec.getName()).thenReturn(SPEC_NAME);
    return spec;
  }

  // ── public-contract guard (top level) ──────────────────────────────────

  @Test
  @DisplayName("NeoReportCallability is a stateless utility (final + single private ctor)")
  void isUtilityClass() throws ReflectiveOperationException {
    assertTrue(Modifier.isFinal(NeoReportCallability.class.getModifiers()),
        "utility class must be final");

    Constructor<?>[] ctors = NeoReportCallability.class.getDeclaredConstructors();
    assertEquals(1, ctors.length, "utility class must declare a single constructor");

    Constructor<?> ctor = ctors[0];
    assertTrue(Modifier.isPrivate(ctor.getModifiers()),
        "utility class constructor must be private");

    // Reflection must still be able to invoke the private no-arg constructor
    // (its sole purpose is to block public instantiation, not all instantiation).
    ctor.setAccessible(true);
    assertNotNull(ctor.newInstance(),
        "private constructor must be invocable via reflection");
  }

  // ── not-configured contract (no DB needed) ─────────────────────────────

  @Nested
  @DisplayName("not-configured contract")
  class NotConfiguredContract {

    @Test
    @DisplayName("STATUS_NOT_CONFIGURED is the stable canonical status string")
    void statusConstantIsStable() {
      assertEquals("not_configured_for_report_generation",
          NeoReportCallability.STATUS_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("buildNotConfiguredMessage names the spec and explains the removal")
    void messageNamesSpec() {
      String message = NeoReportCallability.buildNotConfiguredMessage(SPEC_NAME);
      assertTrue(message.contains(SPEC_NAME));
      assertTrue(message.contains("not configured"));
    }

    @Test
    @DisplayName("buildNotConfiguredResponse carries name/type/callable/status/message")
    void responseHasCanonicalShape() throws Exception {
      JSONObject body = NeoReportCallability.buildNotConfiguredResponse(SPEC_NAME);
      assertEquals(SPEC_NAME, body.getString("name"));
      assertEquals("report", body.getString("type"));
      assertFalse(body.getBoolean("callable"));
      assertEquals(NeoReportCallability.STATUS_NOT_CONFIGURED, body.getString("status"));
      assertEquals(NeoReportCallability.buildNotConfiguredMessage(SPEC_NAME),
          body.getString("message"));
    }
  }

  // ── handler-qualifier resolution / callability ─────────────────────────

  @Nested
  @DisplayName("resolveReportHandlerQualifier / isReportCallable")
  class QualifierResolution {

    @Test
    @DisplayName("returns the first non-blank Java_Qualifier among the spec entities")
    void returnsFirstNonBlankQualifier() {
      SFSpec spec = mockSpec();
      SFEntity blank = mock(SFEntity.class);
      when(blank.getJavaQualifier()).thenReturn("  ");
      SFEntity handler = mock(SFEntity.class);
      when(handler.getJavaQualifier()).thenReturn("agingReportHandler");

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(obDal);
        stubEntityCriteria(obDal, List.of(blank, handler));

        assertEquals("agingReportHandler",
            NeoReportCallability.resolveReportHandlerQualifier(spec));
        assertTrue(NeoReportCallability.isReportCallable(spec));
      }
    }

    @Test
    @DisplayName("returns null when no entity declares a qualifier (non-callable)")
    void returnsNullWhenNoQualifier() {
      SFSpec spec = mockSpec();
      SFEntity blank = mock(SFEntity.class);
      when(blank.getJavaQualifier()).thenReturn(null);

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(obDal);
        stubEntityCriteria(obDal, List.of(blank));

        assertNull(NeoReportCallability.resolveReportHandlerQualifier(spec));
        assertFalse(NeoReportCallability.isReportCallable(spec));
      }
    }

    @Test
    @DisplayName("returns null (non-callable) when the entity query throws — never propagates")
    void returnsNullOnException() {
      SFSpec spec = mockSpec();

      try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(obDal);
        when(obDal.createCriteria(SFEntity.class))
            .thenThrow(new RuntimeException("DB unavailable"));

        assertNull(NeoReportCallability.resolveReportHandlerQualifier(spec));
        assertFalse(NeoReportCallability.isReportCallable(spec));
      }
    }
  }
}
