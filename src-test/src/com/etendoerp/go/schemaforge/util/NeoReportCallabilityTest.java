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
import java.util.Optional;

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

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
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

    /**
     * A qualifier is still resolved from the first entity that declares one — but since
     * ETP-4793 / IMP-19 that alone no longer makes the spec callable. Five of the eight
     * published {@code generate_*} tools named a UI handler that dispatches on an
     * {@code action} query parameter and could never answer a report POST, so callability
     * now also requires a deployed handler that declares a report contract. With no CDI
     * container running here, no handler resolves, so the spec reads as non-callable even
     * though the qualifier is present — which is exactly the distinction being asserted.
     */
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
        assertFalse(NeoReportCallability.isReportCallable(spec),
            "a qualifier alone is not a report contract");
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

  // ── declared report contract (ETP-4793 / IMP-19) ───────────────────────

  /**
   * {@code contractOf} is the seam every report surface shares: the tool schema, discover,
   * the router's validation and the NEO HTTP endpoint all read the contract from the same
   * handler object, so an agent cannot be shown one contract and judged against another.
   */
  @Nested
  @DisplayName("contractOf — the handler's declared report contract")
  class ContractResolution {

    @Test
    @DisplayName("no handler deployed → no contract")
    void nullHandlerHasNoContract() {
      assertTrue(NeoReportCallability.contractOf(null, "agingReportHandler").isEmpty());
    }

    /**
     * The default {@code reportParameters()} is {@code Optional.empty()}, which is how the
     * five UI handlers that were advertised as report generators now drop out of the catalog.
     */
    @Test
    @DisplayName("handler that declares nothing → no contract (default is empty)")
    void undeclaredHandlerHasNoContract() {
      NeoHandler handler = new NeoHandler() {
        @Override
        public NeoResponse handle(NeoContext context) {
          return null;
        }
      };
      assertTrue(NeoReportCallability.contractOf(handler, "financialAccountsPageHandler").isEmpty());
    }

    /**
     * An empty <i>list</i> is a different statement from an empty {@code Optional}: it means
     * "a real report that takes no inputs" and must stay callable.
     */
    @Test
    @DisplayName("handler declaring an empty parameter list → callable contract, JSON only")
    void emptyListIsAContract() {
      NeoReportContract contract = NeoReportCallability
          .contractOf(declaring(List.of()), "inventoryStockReportHandler")
          .orElseThrow();

      assertEquals("inventoryStockReportHandler", contract.getQualifier());
      assertTrue(contract.getParameters().isEmpty());
      assertTrue(contract.getRequiredParameterNames().isEmpty());
      assertEquals(NeoReportParam.FORMAT_JSON, contract.getDefaultFormat());
    }

    @Test
    @DisplayName("required parameters are reported, optional ones are not")
    void requiredParametersAreReported() {
      NeoReportContract contract = NeoReportCallability.contractOf(declaring(List.of(
          NeoReportParam.required("dateFrom", NeoReportParam.TYPE_DATE, "Start."),
          NeoReportParam.required("dateTo", NeoReportParam.TYPE_DATE, "End."),
          NeoReportParam.optional("orgId", NeoReportParam.TYPE_STRING, "Organization."),
          NeoReportParam.options("recOrPay", "Side.", List.of("RECEIVABLES", "PAYABLES")))),
          "agingReportHandler").orElseThrow();

      assertEquals(List.of("dateFrom", "dateTo"), contract.getRequiredParameterNames());
      assertTrue(contract.findParameter("orgId").isPresent());
      assertTrue(contract.findParameter("nosuch").isEmpty());
      // options() is a closed set, and optional: the handlers that use it supply a default.
      NeoReportParam recOrPay = contract.findParameter("recOrPay").orElseThrow();
      assertFalse(recOrPay.isRequired());
      assertEquals(List.of("RECEIVABLES", "PAYABLES"), recOrPay.getAllowedValues());
    }

    @Test
    @DisplayName("format matching is case-insensitive, and an omitted format is accepted")
    void formatMatching() {
      NeoReportContract contract = NeoReportCallability
          .contractOf(declaring(List.of()), "taxReportHandler").orElseThrow();

      assertTrue(contract.supportsFormat(null), "omitted format means the default");
      assertTrue(contract.supportsFormat(""));
      assertTrue(contract.supportsFormat("JSON"));
      // The old schema advertised pdf/xlsx/csv and never read the argument; nothing here
      // renders documents, so those must now be refused rather than silently ignored.
      assertFalse(contract.supportsFormat("pdf"));
    }

    @Test
    @DisplayName("a handler that throws while declaring is non-callable, not fatal")
    void throwingHandlerIsNonCallable() {
      NeoHandler handler = new NeoHandler() {
        @Override
        public NeoResponse handle(NeoContext context) {
          return null;
        }

        @Override
        public Optional<List<NeoReportParam>> reportParameters() {
          throw new IllegalStateException("bad declaration");
        }
      };
      assertTrue(NeoReportCallability.contractOf(handler, "brokenHandler").isEmpty());
    }

    private NeoHandler declaring(List<NeoReportParam> params) {
      return new NeoHandler() {
        @Override
        public NeoResponse handle(NeoContext context) {
          return null;
        }

        @Override
        public Optional<List<NeoReportParam>> reportParameters() {
          return Optional.of(params);
        }
      };
    }
  }
}
