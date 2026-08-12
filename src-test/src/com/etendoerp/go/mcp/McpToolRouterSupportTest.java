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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoActionSurface;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * Unit tests for {@link McpToolRouterSupport}.
 * Tests the pure utility methods that don't require DB access.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolRouterSupportTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<McpToolRouterSupport> constructor = McpToolRouterSupport.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  // ─── buildMethodsArray ──────────────────────────────────────────────

  @Nested
  @DisplayName("buildMethodsArray")
  class BuildMethodsArray {

    @Test
    void allMethodsEnabled() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(true);
      when(entity.isGetByID()).thenReturn(true);
      when(entity.isPost()).thenReturn(true);
      when(entity.isPut()).thenReturn(true);
      when(entity.isPatch()).thenReturn(true);
      when(entity.isDelete()).thenReturn(true);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(5, methods.length()); // GET, POST, PUT, PATCH, DELETE
      assertTrue(arrayContains(methods, "GET"));
      assertTrue(arrayContains(methods, "POST"));
      assertTrue(arrayContains(methods, "PUT"));
      assertTrue(arrayContains(methods, "PATCH"));
      assertTrue(arrayContains(methods, "DELETE"));
    }

    @Test
    void noMethodsEnabled() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(false);
      when(entity.isGetByID()).thenReturn(false);
      when(entity.isPost()).thenReturn(false);
      when(entity.isPut()).thenReturn(false);
      when(entity.isPatch()).thenReturn(false);
      when(entity.isDelete()).thenReturn(false);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(0, methods.length());
    }

    @Test
    void onlyGetByIdAddsGet() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(false);
      when(entity.isGetByID()).thenReturn(true);
      when(entity.isPost()).thenReturn(false);
      when(entity.isPut()).thenReturn(false);
      when(entity.isPatch()).thenReturn(false);
      when(entity.isDelete()).thenReturn(false);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(1, methods.length());
      assertTrue(arrayContains(methods, "GET"));
    }

    @Test
    void nullBooleansTreatedAsFalse() {
      SFEntity entity = mock(SFEntity.class);
      when(entity.isGet()).thenReturn(null);
      when(entity.isGetByID()).thenReturn(null);
      when(entity.isPost()).thenReturn(null);
      when(entity.isPut()).thenReturn(null);
      when(entity.isPatch()).thenReturn(null);
      when(entity.isDelete()).thenReturn(null);

      JSONArray methods = McpToolRouterSupport.buildMethodsArray(entity);
      assertEquals(0, methods.length());
    }
  }

  // ─── buildDiscoverEntity ────────────────────────────────────────────

  @Test
  @DisplayName("neo_discover marks bp-stats and bp-trend as read-only")
  void getOnlyBusinessPartnerEntitiesAreExplicitlyReadOnly() throws Exception {
    assertReadOnlyDiscoverEntity(getOnlyEntity("bp-stats"));
    assertReadOnlyDiscoverEntity(getOnlyEntity("bp-trend"));
  }

  @Test
  @DisplayName("neo_discover keeps writable tax data mutable")
  void writableSystemDataEntityIsNotMarkedReadOnly() throws Exception {
    SFEntity tax = writableEntity("tax");

    JSONObject discovered = McpToolRouterSupport.buildDiscoverEntity(tax);

    assertFalse(discovered.getBoolean("readOnly"));
    assertTrue(arrayContains(discovered.getJSONArray("methods"), "POST"));
  }

  @Test
  @DisplayName("neo_discover marks every individually writable entity as mutable")
  void eachMutationMethodPreventsReadOnly() throws Exception {
    assertMutableDiscoverEntity(entityWithMethods("post-only", true, false, true, false, false, false));
    assertMutableDiscoverEntity(entityWithMethods("put-only", true, false, false, true, false, false));
    assertMutableDiscoverEntity(entityWithMethods("patch-only", true, false, false, false, true, false));
    assertMutableDiscoverEntity(entityWithMethods("delete-only", true, false, false, false, false, true));
  }

  @Test
  @DisplayName("neo_discover does not label a fully disabled entity read-only")
  void entityWithoutReadOrWriteMethodsIsNotMarkedReadOnly() throws Exception {
    SFEntity disabled = entityWithMethods("disabled", false, false, false, false, false, false);

    JSONObject discovered = McpToolRouterSupport.buildDiscoverEntity(disabled);

    assertFalse(discovered.getBoolean("readOnly"));
  }

  private SFEntity getOnlyEntity(String name) {
    return entityWithMethods(name, true, false, false, false, false, false);
  }

  private SFEntity writableEntity(String name) {
    return entityWithMethods(name, true, true, true, true, true, true);
  }

  private SFEntity entityWithMethods(String name, boolean get, boolean getById, boolean post,
      boolean put, boolean patch, boolean delete) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn(name);
    when(entity.isGet()).thenReturn(get);
    when(entity.isGetByID()).thenReturn(getById);
    when(entity.isPost()).thenReturn(post);
    when(entity.isPut()).thenReturn(put);
    when(entity.isPatch()).thenReturn(patch);
    when(entity.isDelete()).thenReturn(delete);
    return entity;
  }

  private void assertReadOnlyDiscoverEntity(SFEntity entity) throws Exception {
    JSONObject discovered = McpToolRouterSupport.buildDiscoverEntity(entity);

    assertTrue(discovered.getBoolean("readOnly"));
    JSONArray methods = discovered.getJSONArray("methods");
    assertEquals(1, methods.length());
    assertTrue(arrayContains(methods, "GET"));
    assertFalse(arrayContains(methods, "POST"));
    assertFalse(arrayContains(methods, "PUT"));
    assertFalse(arrayContains(methods, "PATCH"));
    assertFalse(arrayContains(methods, "DELETE"));
  }

  private void assertMutableDiscoverEntity(SFEntity entity) throws Exception {
    assertFalse(McpToolRouterSupport.buildDiscoverEntity(entity).getBoolean("readOnly"));
  }

  // ─── hasSpecAccess ──────────────────────────────────────────────────

  @Nested
  @DisplayName("hasSpecAccess")
  class HasSpecAccess {

    private MockedStatic<NeoAccessUtils> accessMock;
    private MockedStatic<OBDal> obDalMock;

    @BeforeEach
    void setUp() {
      accessMock = mockStatic(NeoAccessUtils.class);
      // ETP-4254: the "W" branch now also consults isCatalogExcludedSpec, which queries
      // ETGO_SF_ENTITY. Default every spec to "no included entities", meaning no evidence of a
      // handler-only spec, so the pre-existing access-tiering assertions below stay unchanged.
      // The catalog-exclusion tests override this stub with real entities.
      obDalMock = mockStatic(OBDal.class);
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubEntityCriteria(obDal, Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
      accessMock.close();
      obDalMock.close();
    }

    /**
     * ETP-4510 BUG-3: {@code hasSpecAccess}'s "W" branch now delegates entirely to
     * {@link NeoAccessUtils#hasWindowAccessForSpec(SFSpec, String)}, which covers both
     * ordinary window specs and windowless/custom "combination" specs in one call —
     * it must run unconditionally (never skipped just because {@code spec.getADWindow()}
     * is null). The windowless tiering rules themselves are unit-tested directly on
     * {@code NeoAccessHelperTest#hasWindowAccessForSpec}; here we only verify the
     * delegation and method threading.
     */
    @Test
    void windowSpecWithAccessReturnsTrue() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    @Test
    void windowSpecWithoutAccessReturnsFalse() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(false);

      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    /**
     * ETP-4510 BUG-3: a windowless spec (spec.getADWindow() == null) no longer skips the
     * check unconditionally — it is now routed through hasWindowAccessForSpec, which
     * itself decides (based on combination data / no-role) whether to allow. This test
     * only proves the delegation happens; the windowless decision logic lives in
     * NeoAccessHelperTest.
     */
    @Test
    void windowlessSpecDelegatesToHasWindowAccessForSpec() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getADWindow()).thenReturn(null);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    /**
     * ETP-4510: the 2-arg overload defaults to a GET/read-tier check — a read-only
     * {@code AD_Window_Access} role must still see the spec in discovery/listing.
     */
    @Test
    void twoArgOverloadDefaultsToGetMethod() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "POST")).thenReturn(false);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    /**
     * ETP-4510 (BLOCKER fix): a write-tier check (POST/PUT/DELETE) for a window spec
     * must thread the HTTP-method-equivalent through to
     * {@link NeoAccessUtils#hasWindowAccessForSpec(SFSpec, String)} so a read-only
     * {@code AD_Window_Access} role is denied — this is the exact gap that let MCP
     * neo_create/neo_update/neo_delete/neo_batch bypass the REST tiering.
     */
    @Test
    void windowSpecWriteMethodDeniedForReadOnlyAccess() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "POST")).thenReturn(false);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "PUT")).thenReturn(false);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "DELETE")).thenReturn(false);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W", "GET"));
      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "W", "POST"));
      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "W", "PUT"));
      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "W", "DELETE"));
    }

    /**
     * ETP-4510: a full-access (read/write) role passes every method tier.
     */
    @Test
    void windowSpecWriteMethodAllowedForFullAccess() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "POST")).thenReturn(true);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "PUT")).thenReturn(true);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "DELETE")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W", "GET"));
      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W", "POST"));
      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W", "PUT"));
      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W", "DELETE"));
    }

    /**
     * ETP-4510: process/report specs remain binary regardless of the method passed —
     * the write-tier method string must not accidentally change process authorization.
     */
    @Test
    void processSpecIgnoresHttpMethod() {
      SFSpec spec = mock(SFSpec.class);
      Process process = mock(Process.class);
      when(spec.getProcess()).thenReturn(process);
      when(process.getId()).thenReturn("proc-3");
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess("proc-3")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "P", "POST"));
      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "P", "DELETE"));
    }

    @Test
    void processSpecWithAccessReturnsTrue() {
      SFSpec spec = mock(SFSpec.class);
      Process process = mock(Process.class);
      when(spec.getProcess()).thenReturn(process);
      when(process.getId()).thenReturn("proc-1");
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess("proc-1")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "P"));
    }

    /**
     * ETP-4596: the "R" branch now delegates to {@link NeoAccessUtils#hasReportSpecAccess}
     * rather than calling {@code hasProcessAccess} directly — this proves the delegation and
     * method threading; the tiering rules themselves are unit-tested on
     * {@code NeoAccessHelperTest#hasReportSpecAccess}.
     */
    @Test
    void reportSpecDelegatesToHasReportSpecAccess_allow() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(spec, "GET")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "R"));
    }

    @Test
    void reportSpecDelegatesToHasReportSpecAccess_deny() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(spec, "GET")).thenReturn(false);

      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "R"));
    }

    /**
     * ETP-4596: the write-tier method must thread through to
     * {@code hasReportSpecAccess} for a process-less report spec exactly like it does for a
     * "W" spec — the constituent-window check honors read-only vs. full-access tiering too.
     */
    @Test
    void reportSpecThreadsHttpMethodToHasReportSpecAccess() {
      SFSpec spec = mock(SFSpec.class);
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(spec, "GET")).thenReturn(true);
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(spec, "POST")).thenReturn(false);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "R", "GET"));
      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "R", "POST"));
    }

    @Test
    void unknownSpecTypeReturnsTrue() {
      SFSpec spec = mock(SFSpec.class);
      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "X"));
    }

    /**
     * ETP-4284 / G4 + ETP-4254: a handler-only window spec (the dashboard's widgets, whose
     * entities have no AD_Tab) is surfaced via neo_widget, so it must be excluded from the
     * type-W CRUD discovery catalog regardless of window access. ETP-4254 replaced the
     * hardcoded {@code "dashboard"} name match with this data-driven test, so the spec is
     * built here with handler-only entities rather than with the magic name.
     */
    @Test
    void handlerOnlyWindowSpecIsExcludedFromDiscovery() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn(McpConstants.SPEC_DASHBOARD);
      when(spec.getId()).thenReturn("spec-dashboard");
      // Window access deliberately GRANTED, so the exclusion can only come from the
      // handler-only rule and not from a missing access stub.
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      stubEntityCriteria(OBDal.getInstance(), List.of(handlerOnlyEntity()));

      assertFalse(McpToolRouterSupport.hasSpecAccess(spec, "W"),
          "handler-only (widget) spec must never surface through the W discovery catalog");
    }

    /**
     * ETP-4254 regression guard: "handler-only" alone must NOT hide a spec. A tab-less spec
     * whose handler serves ACTION requests ({@code not-posted-documents}' {@code post} /
     * {@code bulk-post}) has a genuine agentic surface, and this very gate also guards
     * {@code neo_action} ({@code McpToolRouter#route}) — excluding it would take a
     * transactional business action away from agents, the opposite of ETP-4254's goal.
     */
    @Test
    void handlerOnlySpecWithAnActionRouteStaysDiscoverable() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("not-posted-documents");
      when(spec.getId()).thenReturn("spec-not-posted");
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      stubEntityCriteria(OBDal.getInstance(), List.of(handlerOnlyEntity()));

      try (MockedStatic<NeoActionSurface> actionMock = mockStatic(NeoActionSurface.class)) {
        actionMock.when(() -> NeoActionSurface.hasActionSurface(anyList())).thenReturn(true);

        assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W"),
            "a tab-less spec that still serves /action must stay in the agentic catalog");
      }
    }

    /**
     * ETP-4254 regression guard: a read-only monitor spec (AD_Tab-backed entities, every
     * mutation flag off) must STILL be readable and discoverable. Only its write catalog
     * entry is removed — collapsing the two rules into one would have hidden the SII /
     * VeriFactu monitors from the agent entirely.
     */
    @Test
    void readOnlyWindowSpecStaysDiscoverable() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("monitor-verifactu");
      when(spec.getId()).thenReturn("spec-monitor");
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      stubEntityCriteria(OBDal.getInstance(), List.of(tabBackedEntity(false)));

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "W"));
    }

    /**
     * ETP-4254: report specs are handler-only by design (NeoReportCallability resolves them
     * through a Java_Qualifier, they have no AD_Tab), so the handler-only exclusion must be
     * scoped to type W. Applying it to type R would delete every report from discovery.
     */
    @Test
    void reportSpecIsNotSubjectToTheHandlerOnlyExclusion() {
      SFSpec spec = mock(SFSpec.class);
      Process process = mock(Process.class);
      when(spec.getProcess()).thenReturn(process);
      when(process.getId()).thenReturn("proc-report");
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess("proc-report")).thenReturn(true);
      // ETP-4596: hasSpecAccess's "R" branch now delegates to hasReportSpecAccess (which
      // internally resolves to hasProcessAccess for a process-backed spec), not directly to
      // hasProcessAccess — stub the entry point the code actually calls.
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(spec, "GET")).thenReturn(true);

      assertTrue(McpToolRouterSupport.hasSpecAccess(spec, "R"));
    }
  }

  // ─── isHandlerOnlySpec / isReadOnlySpec (ETP-4254) ──────────────────

  /**
   * The two data-driven catalog predicates that replaced the hardcoded
   * {@code McpConstants.SPEC_DASHBOARD} literal in {@code isWidgetSpec}.
   *
   * <p>Both are deliberately conservative: they fire only on positive evidence (the spec HAS
   * included entities and none of them qualifies). An empty list or a failed lookup keeps the
   * spec in the catalog, because the authoritative refusal is
   * {@code requireMethodEnabled}, not the catalog shape.</p>
   */
  @Nested
  @DisplayName("isHandlerOnlySpec / isReadOnlySpec (ETP-4254)")
  class SpecLevelPredicates {

    @Mock private OBDal mockOBDal;
    private MockedStatic<OBDal> obDalMock;

    @BeforeEach
    void setUp() {
      obDalMock = mockStatic(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    }

    @AfterEach
    void tearDown() {
      obDalMock.close();
    }

    private SFSpec spec(String name) {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn(name);
      when(spec.getId()).thenReturn(name + "-id");
      return spec;
    }

    @Test
    @DisplayName("a spec whose every entity lacks an AD_Tab is handler-only")
    void allHandlerEntitiesMakeTheSpecHandlerOnly() {
      stubEntityCriteria(mockOBDal, List.of(handlerOnlyEntity(), handlerOnlyEntity()));

      assertTrue(McpToolRouterSupport.isHandlerOnlySpec(spec("dashboard")));
    }

    @Test
    @DisplayName("one AD_Tab-backed entity is enough to make the spec CRUD-servable")
    void oneTabBackedEntityDisqualifiesHandlerOnly() {
      stubEntityCriteria(mockOBDal, List.of(handlerOnlyEntity(), tabBackedEntity(true)));

      assertFalse(McpToolRouterSupport.isHandlerOnlySpec(spec("sales-order")));
    }

    @Test
    @DisplayName("no entities is not evidence of a handler-only spec")
    void emptyEntityListIsNotHandlerOnly() {
      stubEntityCriteria(mockOBDal, Collections.emptyList());

      assertFalse(McpToolRouterSupport.isHandlerOnlySpec(spec("empty")));
    }

    /**
     * The catalog exclusion needs BOTH conditions. Being handler-only is the cheap shape test;
     * the action probe is what separates {@code dashboard} (no agentic surface at all) from
     * {@code not-posted-documents} (tab-less, but serves {@code post} / {@code bulk-post}).
     */
    @Test
    @DisplayName("handler-only AND actionless is excluded from the catalog")
    void handlerOnlyAndActionlessSpecIsCatalogExcluded() {
      stubEntityCriteria(mockOBDal, List.of(handlerOnlyEntity(), handlerOnlyEntity()));

      try (MockedStatic<NeoActionSurface> actionMock = mockStatic(NeoActionSurface.class)) {
        actionMock.when(() -> NeoActionSurface.hasActionSurface(anyList())).thenReturn(false);

        assertTrue(McpToolRouterSupport.isCatalogExcludedSpec(spec("dashboard")));
      }
    }

    @Test
    @DisplayName("handler-only but with an /action route stays in the catalog")
    void handlerOnlySpecWithActionRouteIsNotCatalogExcluded() {
      stubEntityCriteria(mockOBDal, List.of(handlerOnlyEntity()));

      try (MockedStatic<NeoActionSurface> actionMock = mockStatic(NeoActionSurface.class)) {
        actionMock.when(() -> NeoActionSurface.hasActionSurface(anyList())).thenReturn(true);

        assertFalse(McpToolRouterSupport.isCatalogExcludedSpec(spec("not-posted-documents")));
      }
    }

    /** The cheap shape test short-circuits, so an ordinary window never pays the CDI probe. */
    @Test
    @DisplayName("an AD_Tab-backed spec is never catalog-excluded and skips the action probe")
    void tabBackedSpecSkipsTheActionProbe() {
      stubEntityCriteria(mockOBDal, List.of(tabBackedEntity(true)));

      try (MockedStatic<NeoActionSurface> actionMock = mockStatic(NeoActionSurface.class)) {
        assertFalse(McpToolRouterSupport.isCatalogExcludedSpec(spec("sales-order")));

        actionMock.verifyNoInteractions();
      }
    }

    @Test
    void nullSpecIsNotCatalogExcluded() {
      assertFalse(McpToolRouterSupport.isCatalogExcludedSpec(null));
    }

    @Test
    @DisplayName("a spec with no mutable entity is read-only (ETP-4254 AC#1)")
    void allImmutableEntitiesMakeTheSpecReadOnly() {
      stubEntityCriteria(mockOBDal, List.of(tabBackedEntity(false), tabBackedEntity(false)));

      assertTrue(McpToolRouterSupport.isReadOnlySpec(spec("sii-monitor")));
    }

    @Test
    @DisplayName("one mutable entity keeps the whole spec writable")
    void oneMutableEntityKeepsTheSpecWritable() {
      stubEntityCriteria(mockOBDal, List.of(tabBackedEntity(false), tabBackedEntity(true)));

      assertFalse(McpToolRouterSupport.isReadOnlySpec(spec("sales-order")));
    }

    @Test
    @DisplayName("per-method capability reports only the verbs enabled by an entity")
    void perMethodCapabilityUsesTheExactEntityFlag() {
      SFEntity putOnly = tabBackedEntity(false);
      when(putOnly.isPut()).thenReturn(true);
      when(putOnly.isPatch()).thenReturn(true);
      stubEntityCriteria(mockOBDal, List.of(tabBackedEntity(false), putOnly));
      SFSpec spec = spec("monitor-verifactu");

      assertFalse(McpToolRouterSupport.hasEntityWithMethod(spec, "POST"));
      assertTrue(McpToolRouterSupport.hasEntityWithMethod(spec, "PUT"));
      assertFalse(McpToolRouterSupport.hasEntityWithMethod(spec, "DELETE"));
    }

    @Test
    @DisplayName("no entities is not evidence of a read-only spec")
    void emptyEntityListIsNotReadOnly() {
      stubEntityCriteria(mockOBDal, Collections.emptyList());

      assertFalse(McpToolRouterSupport.isReadOnlySpec(spec("empty")));
    }

    @Test
    @DisplayName("a failed entity lookup degrades to 'no evidence' for both predicates")
    void lookupFailureDegradesToNoEvidence() {
      when(mockOBDal.createCriteria(SFEntity.class))
          .thenThrow(new IllegalStateException("no session"));

      assertFalse(McpToolRouterSupport.isHandlerOnlySpec(spec("boom")));
      assertFalse(McpToolRouterSupport.isReadOnlySpec(spec("boom")));
    }

    @Test
    void nullSpecIsNeitherHandlerOnlyNorReadOnly() {
      assertFalse(McpToolRouterSupport.isHandlerOnlySpec((SFSpec) null));
      assertFalse(McpToolRouterSupport.isReadOnlySpec((SFSpec) null));
    }

    /** The list overloads are the single implementation; null/empty means "no evidence". */
    @Test
    void handlerOnlyListOverloadTreatsNullAndEmptyAsNoEvidence() {
      assertFalse(McpToolRouterSupport.isHandlerOnlySpec((List<SFEntity>) null));
      assertFalse(McpToolRouterSupport.isHandlerOnlySpec(Collections.emptyList()));
    }

    /** The list overload is the single implementation; null/empty means "no evidence". */
    @Test
    void listOverloadTreatsNullAndEmptyAsNoEvidence() {
      assertFalse(McpToolRouterSupport.isReadOnlySpec((List<SFEntity>) null));
      assertFalse(McpToolRouterSupport.isReadOnlySpec(Collections.emptyList()));
      assertTrue(McpToolRouterSupport.isReadOnlySpec(List.of(tabBackedEntity(false))));
      assertFalse(McpToolRouterSupport.isReadOnlySpec(List.of(tabBackedEntity(true))));
    }
  }

  // ─── validateArgs (ETP-4793 / IMP-17) ───────────────────────────────

  /**
   * An absent argument is the caller's mistake. It used to travel as an {@code
   * IllegalArgumentException}, which {@code route}'s catch-all could only flatten into a prose 500 —
   * telling an agent to give up on something one added key would have fixed.
   */
  @Nested
  @DisplayName("validateArgs (ETP-4793 / IMP-17)")
  class ValidateArgs {

    @Test
    @DisplayName("every required argument present passes silently")
    void allPresentPasses() throws Exception {
      JSONObject args = new JSONObject();
      args.put("spec", "sales-order");
      args.put("entity", "header");

      McpToolRouterSupport.validateArgs(args, "spec", "entity");
    }

    @Test
    @DisplayName("a missing argument raises a 422 naming it in 'field'")
    void missingArgumentNamesTheField() throws Exception {
      JSONObject args = new JSONObject();
      args.put("spec", "sales-order");

      McpRoutingException ex = assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.validateArgs(args, "spec", "entity"));

      JSONObject envelope = ex.toEnvelope();
      assertEquals(422, envelope.getInt("status"));
      assertEquals("validation_error", envelope.getString("error"));
      assertEquals("entity", envelope.getString("field"));
      assertTrue(envelope.getString("hint").contains("neo_schema"));
    }

    @Test
    @DisplayName("a JSON null counts as absent, and a null argument object is reported too")
    void nullsAreTreatedAsAbsent() throws Exception {
      JSONObject args = new JSONObject();
      args.put("entity", JSONObject.NULL);
      assertEquals("entity", assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.validateArgs(args, "entity")).toEnvelope().getString("field"));

      McpRoutingException noArgs = assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.validateArgs(null, "entity"));
      assertEquals(422, noArgs.toEnvelope().getInt("status"));
      assertFalse(noArgs.toEnvelope().has("field"), "no single argument is at fault");
    }
  }

  // ─── requireMethodEnabled (ETP-4254) ────────────────────────────────

  /**
   * The MCP-side entity method gate. Mirrors the REST {@code 405} in
   * {@code NeoCrudHandler#handleWindowEntityCrud}, but reports the refusal as an explained
   * MCP tool error so the agent knows the entity is read-only and must not retry.
   */
  @Nested
  @DisplayName("requireMethodEnabled (ETP-4254)")
  class RequireMethodEnabled {

    @Test
    @DisplayName("an enabled method passes silently")
    void enabledMethodPasses() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("sales-order");

      McpToolRouterSupport.requireMethodEnabled(spec, tabBackedEntity(true), "POST");
    }

    @Test
    @DisplayName("a read-only entity refuses POST, PUT and DELETE with an explained error")
    void readOnlyEntityRefusesEveryWrite() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("monitor-verifactu");
      SFEntity entity = tabBackedEntity(false);

      for (String method : List.of("POST", "PUT", "DELETE")) {
        McpRoutingException ex = assertThrows(McpRoutingException.class,
            () -> McpToolRouterSupport.requireMethodEnabled(spec, entity, method));

        assertTrue(ex.getMessage().contains("monitor-verifactu"), ex.getMessage());
        assertTrue(ex.getMessage().contains("does not enable " + method), ex.getMessage());
        assertTrue(ex.getMessage().contains("read-only"), ex.getMessage());
      }
    }

    /**
     * ETP-4793 / IMP-17: the refusal is a 405, kept out of the {@code validation_error} bucket for the
     * reason that code exists — the request is correct and the configuration forbids it, so no amount
     * of correcting values will make the call work.
     */
    @Test
    @DisplayName("the refusal carries a 405 method_not_allowed envelope, not a validation error")
    void readOnlyRefusalIsA405Envelope() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("monitor-verifactu");
      SFEntity entity = tabBackedEntity(false);

      McpRoutingException ex = assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.requireMethodEnabled(spec, entity, "POST"));

      JSONObject envelope = ex.toEnvelope();
      assertEquals(405, envelope.getInt("status"));
      assertEquals("method_not_allowed", envelope.getString("error"));
      assertFalse(envelope.has("hint"), "there is no corrective action to hint at");
    }

    @Test
    @DisplayName("GET is still allowed on a read-only entity")
    void readOnlyEntityStillAllowsGet() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("monitor-verifactu");

      McpToolRouterSupport.requireMethodEnabled(spec, tabBackedEntity(false), "GET");
    }
  }

  // ─── buildDiscoverSpec ──────────────────────────────────────────────

  @Nested
  @DisplayName("buildDiscoverSpec")
  class BuildDiscoverSpec {

    @Test
    void basicFieldsAreSet() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("sales-order");
      when(spec.getDescription()).thenReturn("Sales Order");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertEquals("sales-order", result.getString("name"));
      assertEquals("W", result.getString("type"));
      assertEquals("Sales Order", result.getString("description"));
    }

    @Test
    void nullDescriptionIsOmitted() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertFalse(result.has("description"));
    }

    @Test
    void entitiesArrayIsIncludedWhenProvided() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);
      JSONArray entities = new JSONArray();
      entities.put(new JSONObject().put("name", "header"));

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", entities, null, null);
      assertTrue(result.has("entities"));
      assertEquals(1, result.getJSONArray("entities").length());
    }

    @Test
    void nullEntitiesOmitsKey() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertFalse(result.has("entities"));
    }

    /**
     * IMP-9: {@code primaryEntity} is derived and passed in by the caller (handleDiscover), not
     * computed inside buildDiscoverSpec — this method stays DAL-free (ETP-4601 regression fix).
     * When entities are provided and a primaryEntity is given, it is surfaced.
     */
    @Test
    void primaryEntityIsIncludedWhenProvidedAlongsideEntities() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);
      JSONArray entities = new JSONArray();
      entities.put(new JSONObject().put("name", "header"));

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", entities, "header", null);
      assertEquals("header", result.getString("primaryEntity"));
    }

    /** A null primaryEntity (e.g. a spec with no included entities) omits the key entirely. */
    @Test
    void nullPrimaryEntityOmitsKey() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);
      JSONArray entities = new JSONArray();
      entities.put(new JSONObject().put("name", "header"));

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", entities, null, null);
      assertFalse(result.has("primaryEntity"));
    }

    /** A primaryEntity is only ever surfaced alongside entities — never for a non-W spec. */
    @Test
    void primaryEntityIgnoredWhenEntitiesNull() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("test");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, "header", null);
      assertFalse(result.has("primaryEntity"));
    }

    // ── ETP-4254 AC#4: spec-level readOnly marker ──────────────────────

    /**
     * AC#4: an agent scanning {@code neo_discover} must be able to tell writable W specs from
     * read-only ones without inspecting every entity of every spec.
     */
    @Test
    @DisplayName("a W spec with no mutable entity carries readOnly:true")
    void readOnlyWindowSpecCarriesTheMarker() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("monitor-verifactu");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W",
          new JSONArray(), null, List.of(tabBackedEntity(false), tabBackedEntity(false)));

      assertTrue(result.getBoolean("readOnly"));
    }

    /**
     * The key must be emitted ONLY when true — the ~44 writable W specs stay byte-identical,
     * and the negative case is already carried per entity inside {@code entities}.
     */
    @Test
    @DisplayName("a writable W spec omits the readOnly key entirely")
    void writableWindowSpecOmitsTheMarker() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("sales-order");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W",
          new JSONArray(), null, List.of(tabBackedEntity(false), tabBackedEntity(true)));

      assertFalse(result.has("readOnly"),
          "readOnly:false must not be added as noise to writable specs");
    }

    @Test
    @DisplayName("a W spec with no included entities omits the readOnly key")
    void emptyEntityListOmitsTheMarker() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("empty");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W",
          new JSONArray(), null, Collections.emptyList());

      assertFalse(result.has("readOnly"));
    }

    /**
     * The marker is scoped to type W: a report spec already advertises its nature through
     * {@code isReport}/{@code callable} and must not gain a readOnly key.
     */
    @Test
    @DisplayName("a report spec never gets the readOnly marker")
    void reportSpecNeverGetsTheMarker() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("aging-report");

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(false);
        callabilityMock.when(() -> NeoReportCallability.buildNotConfiguredMessage("aging-report"))
            .thenReturn("not configured");

        JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null,
            List.of(tabBackedEntity(false)));

        assertFalse(result.has("readOnly"));
      }
    }

    /**
     * The marker must cost no extra query: it is derived from the very list the entity summary
     * was built from. This test proves the code path never touches OBDal — OBDal is NOT mocked
     * here, so any query would blow up instead of quietly returning a mock.
     */
    @Test
    @DisplayName("the marker is derived from the passed list, not from a fresh query")
    void markerIsDerivedWithoutQuerying() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("sii-monitor");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W",
          new JSONArray(), null, List.of(tabBackedEntity(false)));

      assertTrue(result.getBoolean("readOnly"));
    }

    @Test
    void reportTypeAddsIsReportTrue() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("aging");
      when(spec.getDescription()).thenReturn(null);

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(true);

        JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);
        assertTrue(result.getBoolean("isReport"));
      }
    }

    /**
     * ETP-4255: discover output for a CALLABLE report spec (NEO-native handler backed)
     * carries {@code callable:true} and omits status/message.
     */
    @Test
    void callableReportSpecCarriesCallableTrueWithoutStatus() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("aging");
      when(spec.getDescription()).thenReturn(null);

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(true);

        JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);

        assertTrue(result.getBoolean("isReport"));
        assertTrue(result.getBoolean("callable"));
        assertFalse(result.has("status"));
        assertFalse(result.has("message"));
      }
    }

    /**
     * ETP-4255: discover output for a NON-callable report spec carries {@code callable:false}
     * plus the stable {@code not_configured_for_report_generation} status and a human message.
     */
    @Test
    void nonCallableReportSpecCarriesStatusAndMessage() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("invoice-report");
      when(spec.getDescription()).thenReturn(null);

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(false);
        callabilityMock.when(() -> NeoReportCallability.buildNotConfiguredMessage("invoice-report"))
            .thenCallRealMethod();

        JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);

        assertTrue(result.getBoolean("isReport"));
        assertFalse(result.getBoolean("callable"));
        assertEquals(NeoReportCallability.STATUS_NOT_CONFIGURED, result.getString("status"));
        assertTrue(result.getString("message").contains("not configured"));
      }
    }

    /**
     * ETP-4257: discover output for a CALLABLE report spec advertises the concrete report
     * tool ({@code reportTool = generate_<snake>}) so the agent calls it directly instead of
     * guessing an entity for neo_list.
     */
    @Test
    void callableReportSpecEmitsReportTool() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("financial-accounts-page");
      when(spec.getDescription()).thenReturn(null);

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(true);

        JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);

        assertTrue(result.getBoolean("callable"));
        assertEquals("generate_financial_accounts_page", result.getString("reportTool"));
      }
    }

    /**
     * ETP-4257: a NON-callable report spec never advertises a report tool (there is none) —
     * it keeps only the not_configured status/message.
     */
    @Test
    void nonCallableReportSpecOmitsReportTool() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("invoice-report");
      when(spec.getDescription()).thenReturn(null);

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(false);
        callabilityMock.when(() -> NeoReportCallability.buildNotConfiguredMessage("invoice-report"))
            .thenCallRealMethod();

        JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);

        assertFalse(result.getBoolean("callable"));
        assertFalse(result.has("reportTool"));
      }
    }

    @Test
    void windowTypeDoesNotAddIsReport() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("order");
      when(spec.getDescription()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertFalse(result.has("isReport"));
    }

    @Test
    void agentPromptIsIncludedWhenPresent() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("purchase-order");
      when(spec.getDescription()).thenReturn(null);
      when(spec.getAgentPrompt()).thenReturn("Always confirm before completing the order.");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertEquals("Always confirm before completing the order.", result.getString("agentPrompt"));
    }

    @Test
    void blankAgentPromptIsOmitted() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("purchase-order");
      when(spec.getDescription()).thenReturn(null);
      when(spec.getAgentPrompt()).thenReturn("   ");

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertFalse(result.has("agentPrompt"));
    }

    @Test
    void nullAgentPromptIsOmitted() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getName()).thenReturn("purchase-order");
      when(spec.getDescription()).thenReturn(null);
      when(spec.getAgentPrompt()).thenReturn(null);

      JSONObject result = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertFalse(result.has("agentPrompt"));
    }
  }

  // ─── isMandatoryValueMissing ────────────────────────────────────────

  @Nested
  @DisplayName("isMandatoryValueMissing")
  class IsMandatoryValueMissing {

    @Test
    void missingKeyReturnsTrue() throws Exception {
      JSONObject body = new JSONObject();
      assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void nullValueReturnsTrue() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", JSONObject.NULL);
      assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void emptyStringReturnsTrue() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", "");
      assertTrue(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void nonEmptyStringReturnsFalse() throws Exception {
      JSONObject body = new JSONObject();
      body.put("name", "Test");
      assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "name"));
    }

    @Test
    void numericValueReturnsFalse() throws Exception {
      JSONObject body = new JSONObject();
      body.put("amount", 42);
      assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "amount"));
    }

    @Test
    void booleanValueReturnsFalse() throws Exception {
      JSONObject body = new JSONObject();
      body.put("active", true);
      assertFalse(McpToolRouterSupport.isMandatoryValueMissing(body, "active"));
    }
  }

  // ─── coercePrimitiveFieldValue ──────────────────────────────────────

  @Nested
  @DisplayName("coercePrimitiveFieldValue")
  class CoercePrimitiveFieldValue {

    @Mock private Logger log;

    @Test
    void coercesStringToLong() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Long.class);
      JSONObject body = new JSONObject();
      body.put("lineNo", "42");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "lineNo", prop, true, log);
      assertEquals(42L, body.getLong("lineNo"));
    }

    @Test
    void coercesDecimalStringToLongByTruncating() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Long.class);
      JSONObject body = new JSONObject();
      body.put("seqNo", "10.0");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "seqNo", prop, true, log);
      assertEquals(10L, body.getLong("seqNo"));
    }

    @Test
    void coercesStringToBigDecimal() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) BigDecimal.class);
      JSONObject body = new JSONObject();
      body.put("amount", "123.45");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "amount", prop, true, log);
      assertEquals(new BigDecimal("123.45"), body.get("amount"));
    }

    @Test
    void coercesYStringToTrue() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      JSONObject body = new JSONObject();
      body.put("active", "Y");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "active", prop, true, log);
      assertTrue(body.getBoolean("active"));
    }

    @Test
    void coercesTrueStringToTrue() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      JSONObject body = new JSONObject();
      body.put("active", "true");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "active", prop, true, log);
      assertTrue(body.getBoolean("active"));
    }

    @Test
    void coercesLowercaseYStringToTrue() throws Exception {
      // ETP-4793: pins the shared NeoBooleanFormat behaviour on both write surfaces — the REST
      // coercer used to reject a lowercase "y" that this one accepted.
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      JSONObject body = new JSONObject();
      body.put("active", "y");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "active", prop, true, log);
      assertTrue(body.getBoolean("active"));
    }

    @Test
    void coercesNStringToFalse() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) Boolean.class);
      JSONObject body = new JSONObject();
      body.put("active", "N");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "active", prop, true, log);
      assertFalse(body.getBoolean("active"));
    }

    @Test
    void nonStringValueIsNotCoerced() throws Exception {
      Property prop = mock(Property.class);
      JSONObject body = new JSONObject();
      body.put("count", 5);

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "count", prop, true, log);
      assertEquals(5, body.getInt("count"));
    }

    @Test
    void emptyStringIsNotCoerced() throws Exception {
      Property prop = mock(Property.class);
      JSONObject body = new JSONObject();
      body.put("name", "");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "name", prop, true, log);
      assertEquals("", body.getString("name"));
    }

    /**
     * ETP-4793 / IMP-16. The MCP write path has its own coercer, independent of the REST one in
     * {@code NeoTypeCoercionHelper} — two implementations that must agree on dates, since a
     * {@code dd-MM-yyyy} value reaching the lenient DAL parser persists as year 0012 rather than
     * failing. These tests exist on both sides on purpose, so the pair cannot drift silently.
     */
    @Test
    @DisplayName("normalizes a dd-MM-yyyy date to ISO — the year-0012 regression")
    void coercesUiPatternDateToIso() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("orderDate", "06-08-2026");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "orderDate", prop, true, log);
      assertEquals("2026-08-06", body.getString("orderDate"));
    }

    @Test
    @DisplayName("leaves an already-ISO date byte-for-byte unchanged")
    void isoDateUnchanged() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("orderDate", "2026-08-06");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "orderDate", prop, true, log);
      assertEquals("2026-08-06", body.getString("orderDate"));
    }

    @Test
    @DisplayName("a datetime property keeps its time component")
    void datetimeKeepsTime() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDatetime()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("movementDate", "2026-08-06 18:55:31.567837+00");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "movementDate", prop, true, log);
      assertEquals("2026-08-06T18:55:31", body.getString("movementDate"));
    }

    /**
     * ETP-4793 / IMP-24 phase 2 changed the answer here, and the assertion is deliberately in two
     * halves. Phase 1 passed an unusable value through with a WARN; it is now reported so the caller
     * gets a 422 naming the field instead of the DAL's raw {@code status:-4} plus a
     * {@code ParseException} that names nothing. What did <b>not</b> change is that the value in the
     * body is still verbatim — the coercer never guesses a substitute, it only refuses.
     */
    @Test
    @DisplayName("an unrecognized date shape is reported, and left verbatim in the body")
    void unrecognizedDateIsReported() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("orderDate", "06/08/2026");

      JSONObject rejection =
          McpToolRouterSupport.coercePrimitiveFieldValue(body, "orderDate", prop, true, log);

      assertNotNull(rejection);
      assertEquals("orderDate", rejection.getString("name"));
      assertEquals("06/08/2026", rejection.getString("received"));
      assertEquals("yyyy-MM-dd", rejection.getString("expectedFormat"));
      assertEquals("06/08/2026", body.getString("orderDate"));
    }

    /**
     * An ISO-shaped value that is not a real calendar day. The strict resolver is what makes this a
     * rejection rather than a silent slide to February 28th, and it is the case that proves the
     * message has to echo the value back: the field name alone would suggest a format problem when
     * the format is fine.
     */
    @Test
    @DisplayName("an impossible calendar date is reported, not resolved to the 28th")
    void impossibleDateIsReported() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("orderDate", "2026-02-30");

      JSONObject rejection =
          McpToolRouterSupport.coercePrimitiveFieldValue(body, "orderDate", prop, true, log);

      assertNotNull(rejection);
      assertEquals("2026-02-30", rejection.getString("received"));
    }

    /**
     * The other half of the IMP-24 gate. A value the agent never sent cannot be fixed by the agent,
     * so rejecting a server-injected default would hand it an unactionable error. Those keep the
     * phase-1 pass-through, whose WARN is the signal that the default itself needs fixing.
     */
    @Test
    @DisplayName("a server-injected default in a bad shape is passed through, never rejected")
    void serverDefaultIsNotRejected() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDate()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("orderDate", "06/08/2026");

      assertNull(McpToolRouterSupport.coercePrimitiveFieldValue(body, "orderDate", prop, false, log));
      assertEquals("06/08/2026", body.getString("orderDate"));
    }

    @Test
    @DisplayName("a datetime rejection reports the datetime pattern, not the date one")
    void datetimeRejectionReportsDatetimePattern() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDatetime()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("movementDate", "2026-08-06T banana");

      JSONObject rejection =
          McpToolRouterSupport.coercePrimitiveFieldValue(body, "movementDate", prop, true, log);

      assertNotNull(rejection);
      assertEquals("yyyy-MM-dd'T'HH:mm:ss", rejection.getString("expectedFormat"));
      assertEquals("2026-08-10T14:30:00", rejection.getString("example"));
    }

    /**
     * A property outside the two eligible domain types is never judged, so an unusable-looking value
     * on one cannot produce a 422 either. The eligibility gate has to come first, or the rejection
     * would fire on values this class has no standing to read.
     */
    @Test
    @DisplayName("an ineligible domain type is never rejected, however odd the value")
    void ineligibleDomainTypeIsNeverRejected() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isTime()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("startTime", "06/08/2026");

      assertNull(McpToolRouterSupport.coercePrimitiveFieldValue(body, "startTime", prop, true, log));
      assertEquals("06/08/2026", body.getString("startTime"));
    }

    /**
     * The eligibility gate is the DAL domain type, not the Java type. Time-of-day and
     * timezone-free properties are {@code java.util.Date} as well, and
     * {@code JsonToDataConverter} reads only the time half of the first kind — rewriting such a
     * value to {@code yyyy-MM-dd} would delete exactly the part it uses.
     */
    @Test
    @DisplayName("a time-of-day property is left untouched — the gate is the domain type")
    void timePropertyIsNotTouched() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isTime()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("startTime", "2026-08-06T14:30:00");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "startTime", prop, true, log);
      assertEquals("2026-08-06T14:30:00", body.getString("startTime"));
    }

    @Test
    @DisplayName("an absolute-datetime property is left untouched, time included")
    void absoluteDateTimePropertyIsNotTouched() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isAbsoluteDateTime()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("created", "06-08-2026 14:30:00");

      McpToolRouterSupport.coercePrimitiveFieldValue(body, "created", prop, true, log);
      assertEquals("06-08-2026 14:30:00", body.getString("created"));
    }

    /**
     * A non-zero offset already reaches the DAL correctly, so normalizing it away would shift
     * the instant — the fix turning into the corruption. It must be refused, not converted.
     *
     * <p>ETP-4793 / IMP-24 phase 2 makes this the one case that has to be refused <b>without</b>
     * being rejected. It is the only value {@code toCanonical} turns down for being right rather than
     * wrong, so a 422 here would break a working call — which is why the null-return from
     * {@code toCanonical} needed classifying before the rejection could ship at all.
     */
    @Test
    @DisplayName("a non-zero offset is left alone and NOT reported — refused, not rejected")
    void nonZeroOffsetIsRefusedButNotRejected() throws Exception {
      Property prop = mock(Property.class);
      when(prop.getPrimitiveObjectType()).thenReturn((Class) java.util.Date.class);
      when(prop.isDatetime()).thenReturn(true);
      JSONObject body = new JSONObject();
      body.put("movementDate", "2026-08-06T14:30:00+02:00");

      assertNull(
          McpToolRouterSupport.coercePrimitiveFieldValue(body, "movementDate", prop, true, log));
      assertEquals("2026-08-06T14:30:00+02:00", body.getString("movementDate"));
    }
  }

  // ─── buildMissingFieldInfo ──────────────────────────────────────────

  @Nested
  @DisplayName("buildMissingFieldInfo")
  class BuildMissingFieldInfo {

    @Test
    void foreignKeyFieldHasSelectorInfo() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
      when(col.getReference()).thenReturn(ref);
      when(ref.getId()).thenReturn("19"); // TableDir
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");
      when(col.getName()).thenReturn("Business Partner");

      java.util.Set<String> selectorRefs = java.util.Set.of("19", "18", "30",
          NeoSelectorService.REF_OBUISEL);

      JSONObject result = McpToolRouterSupport.buildMissingFieldInfo(col, "businessPartner",
          selectorRefs);

      assertEquals("businessPartner", result.getString("name"));
      assertEquals("C_BPartner_ID", result.getString("column"));
      assertEquals("foreignKey", result.getString("type"));
      assertTrue(result.getBoolean("hasSelector"));
      assertEquals("Business Partner", result.getString("label"));
    }

    @Test
    void nonForeignKeyFieldHasOtherType() throws Exception {
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      org.openbravo.model.ad.domain.Reference ref = mock(org.openbravo.model.ad.domain.Reference.class);
      when(col.getReference()).thenReturn(ref);
      when(ref.getId()).thenReturn("10"); // String
      when(col.getDBColumnName()).thenReturn("Name");
      when(col.getName()).thenReturn("Name");

      java.util.Set<String> selectorRefs = java.util.Set.of("19", "18");

      JSONObject result = McpToolRouterSupport.buildMissingFieldInfo(col, "name", selectorRefs);

      assertEquals("other", result.getString("type"));
      assertFalse(result.has("hasSelector"));
    }
  }

  // ─── resolveMandatoryProperty ───────────────────────────────────────

  @Nested
  @DisplayName("resolveMandatoryProperty")
  class ResolveMandatoryProperty {

    @Test
    void inactiveColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(false);

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }

    @Test
    void nonMandatoryColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(false);

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }

    @Test
    void pkColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_Order_ID");

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }

    @Test
    void systemColumnReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("AD_Client_ID");

      java.util.Set<String> systemCols = java.util.Set.of("AD_CLIENT_ID");
      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, systemCols));
    }

    @Test
    void mandatoryNonSystemColumnReturnsProperty() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      Property prop = mock(Property.class);
      when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

      Property result = McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of());
      assertEquals(prop, result);
    }

    @Test
    void exceptionInPropertyLookupReturnsNull() {
      org.openbravo.model.ad.ui.Tab tab = mock(org.openbravo.model.ad.ui.Tab.class);
      org.openbravo.model.ad.datamodel.Table table = mock(org.openbravo.model.ad.datamodel.Table.class);
      when(tab.getTable()).thenReturn(table);
      when(table.getDBTableName()).thenReturn("C_Order");

      org.openbravo.base.model.Entity entity = mock(org.openbravo.base.model.Entity.class);
      when(entity.getPropertyByColumnName("BadCol")).thenThrow(new RuntimeException("boom"));

      org.openbravo.model.ad.datamodel.Column col = mock(org.openbravo.model.ad.datamodel.Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("BadCol");

      assertNull(McpToolRouterSupport.resolveMandatoryProperty(tab, entity, col, java.util.Set.of()));
    }
  }

  // ─── resolveIncludedEntityOrExplain (ETP-4257) ──────────────────────

  /**
   * Guard that turns an opaque entity-not-found error into a descriptive message when an entity-CRUD
   * tool (neo_list/get/create/...) is called on a report-type spec, while leaving type-W entity
   * resolution unchanged.
   *
   * <p>ETP-4793 / IMP-17: both report branches now raise an {@link McpRoutingException} — an
   * {@code OBException} subtype, so the messages and every existing catch stay as they were, while
   * {@code route} can render the IMP-5 envelope. The classification is {@code validation_error}, not
   * {@code not_found}: nothing the agent named is missing, the call is aimed at the wrong surface and
   * the message says which one to use, so a retry can succeed.</p>
   */
  @Nested
  @DisplayName("resolveIncludedEntityOrExplain (ETP-4257)")
  class ResolveIncludedEntityOrExplain {

    @Mock private OBDal mockOBDal;
    private MockedStatic<OBDal> obDalMock;

    @BeforeEach
    void setUp() {
      obDalMock = mockStatic(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    }

    @AfterEach
    void tearDown() {
      obDalMock.close();
    }

    /**
     * neo_list on a CALLABLE report spec: the error names the report type and points the
     * agent at the concrete {@code etendo_generate_<snake>} tool instead of an entity.
     */
    @Test
    void callableReportSpecExplainsGenerateTool() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getSpecType()).thenReturn("R");
      when(spec.getName()).thenReturn("financial-accounts-page");

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(true);

        McpRoutingException ex = assertThrows(McpRoutingException.class,
            () -> McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, "header"));

        assertTrue(ex.getMessage().contains("report type (R)"),
            "message must state the spec is a report type: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("etendo_generate_financial_accounts_page"),
            "message must name the concrete report tool: " + ex.getMessage());
        // ETP-4793 / IMP-17: carried as a 422, so an agent knows a corrected retry is worth making.
        JSONObject envelope = ex.toEnvelope();
        assertEquals(422, envelope.getInt("status"));
        assertEquals("validation_error", envelope.getString("error"));
        assertEquals("spec", envelope.getString("field"));
      }
    }

    /**
     * neo_list on a NON-callable report spec: the error is the stable ETP-4255
     * not_configured_for_report_generation message.
     */
    @Test
    void nonCallableReportSpecExplainsNotConfigured() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getSpecType()).thenReturn("R");
      when(spec.getName()).thenReturn("aging-report");

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(false);
        callabilityMock.when(() -> NeoReportCallability.buildNotConfiguredMessage("aging-report"))
            .thenCallRealMethod();

        org.openbravo.base.exception.OBException ex = assertThrows(
            org.openbravo.base.exception.OBException.class,
            () -> McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, "header"));

        assertTrue(ex.getMessage().contains("not configured for Etendo Go/MCP report"),
            "message must be the ETP-4255 not-configured text: " + ex.getMessage());
      }
    }

    /**
     * Regression (AC-3): a type-W spec resolves its included entity exactly as before —
     * the guard only fires for report specs, never for windows.
     */
    @Test
    @SuppressWarnings("unchecked")
    void windowSpecResolvesEntityUnchanged() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getSpecType()).thenReturn("W");
      when(spec.getId()).thenReturn("spec-w-1");

      SFEntity entity = mock(SFEntity.class);
      OBCriteria<SFEntity> crit = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFEntity.class)).thenReturn(crit);
      when(crit.add(org.mockito.ArgumentMatchers.any())).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      when(crit.list()).thenReturn(List.of(entity));

      SFEntity result = McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, "header");

      assertEquals(entity, result);
    }
  }

  // ─── aliasArg (IMP-8) ───────────────────────────────────────────────

  @Nested
  @DisplayName("aliasArg")
  class AliasArg {

    @Test
    @DisplayName("copies alias onto canonical when canonical is absent")
    void copiesAliasWhenCanonicalAbsent() throws Exception {
      JSONObject args = new JSONObject().put("field", "businessPartner");
      McpToolRouterSupport.aliasArg(args, "field", "column");
      assertEquals("businessPartner", args.getString("column"));
    }

    @Test
    @DisplayName("canonical wins when both keys are present")
    void canonicalWinsWhenBothPresent() throws Exception {
      JSONObject args = new JSONObject().put("field", "aaa").put("column", "bbb");
      McpToolRouterSupport.aliasArg(args, "field", "column");
      assertEquals("bbb", args.getString("column"));
    }

    @Test
    @DisplayName("no-op when alias is absent")
    void noopWhenAliasAbsent() {
      JSONObject args = new JSONObject();
      McpToolRouterSupport.aliasArg(args, "field", "column");
      assertFalse(args.has("column"));
    }

    @Test
    @DisplayName("null args is a no-op (does not throw)")
    void nullArgsIsNoop() {
      McpToolRouterSupport.aliasArg(null, "field", "column");
    }
  }

  // ─── isEmptySuccessResult (IMP-5) ───────────────────────────────────

  @Nested
  @DisplayName("isEmptySuccessResult")
  class IsEmptySuccessResult {

    private JSONObject wrap(JSONObject inner) throws Exception {
      return new JSONObject().put("response", inner);
    }

    @Test
    @DisplayName("true for a success with an empty data array")
    void trueForSuccessEmptyData() throws Exception {
      JSONObject inner = new JSONObject().put("status", 0).put("data", new JSONArray());
      assertTrue(McpToolRouterSupport.isEmptySuccessResult(wrap(inner)));
    }

    @Test
    @DisplayName("false when data has rows")
    void falseWhenDataHasRows() throws Exception {
      JSONObject inner = new JSONObject().put("status", 0)
          .put("data", new JSONArray().put(new JSONObject().put("id", "X")));
      assertFalse(McpToolRouterSupport.isEmptySuccessResult(wrap(inner)));
    }

    @Test
    @DisplayName("false on a failure status even with empty data")
    void falseOnFailureStatus() throws Exception {
      JSONObject inner = new JSONObject().put("status", -1).put("data", new JSONArray());
      assertFalse(McpToolRouterSupport.isEmptySuccessResult(wrap(inner)));
    }

    @Test
    @DisplayName("false when there is no response wrapper")
    void falseWhenNoResponseWrapper() {
      assertFalse(McpToolRouterSupport.isEmptySuccessResult(new JSONObject()));
    }

    @Test
    @DisplayName("false for null input")
    void falseForNull() {
      assertFalse(McpToolRouterSupport.isEmptySuccessResult(null));
    }
  }

  // ─── buildNotFoundError (IMP-5) ─────────────────────────────────────

  @Nested
  @DisplayName("buildNotFoundError")
  class BuildNotFoundError {

    @Test
    @DisplayName("builds a 404 not_found body with a descriptive detail")
    void buildsNotFoundBody() throws Exception {
      JSONObject envelope = McpToolRouterSupport
          .buildNotFoundError("sales-invoice", "header", "NONEXISTENT123");
      assertEquals(404, envelope.getInt("status"));
      assertEquals("not_found", envelope.getString("error"));
      assertTrue(envelope.getString("detail").contains("sales-invoice/header"));
      assertTrue(envelope.getString("detail").contains("NONEXISTENT123"));
    }

    @Test
    @DisplayName("points the agent to a docs recipe via seeAlso (IMP-10)")
    void includesSeeAlsoPointer() throws Exception {
      assertEquals("docs(topic:\"reading records\")", McpToolRouterSupport
          .buildNotFoundError("sales-invoice", "header", "NONEXISTENT123")
          .getString("seeAlso"));
    }

    /**
     * IMP-5 clause (iii): this envelope used to be returned inside {@code {"response":{…}}}, which
     * made it the one read-verb error whose shape differed from its siblings on the same tool. The
     * two tests above were rewritten from asserting on {@code .getJSONObject("response")}; this one
     * pins the absence directly, so a future edit that re-wraps it fails here rather than silently
     * restoring the asymmetry.
     */
    @Test
    @DisplayName("is flat — no 'response' wrapper (IMP-5 clause (iii))")
    void isFlat() throws Exception {
      assertFalse(McpToolRouterSupport
          .buildNotFoundError("sales-invoice", "header", "NONEXISTENT123").has("response"));
    }
  }

  // ─── flattenCoreResponse (IMP-5 clause (iii)) ───────────────────────

  @Nested
  @DisplayName("flattenCoreResponse")
  class FlattenCoreResponse {

    private JSONObject wrap(JSONObject inner) throws Exception {
      return new JSONObject().put("response", inner);
    }

    @Test
    @DisplayName("lifts data and the pagination keys to the top level")
    void liftsDataAndPagination() throws Exception {
      JSONObject inner = new JSONObject()
          .put("status", 0)
          .put("data", new JSONArray().put(new JSONObject().put("id", "X")))
          .put("startRow", 0)
          .put("endRow", 0)
          .put("totalRows", 1);
      JSONObject flat = McpToolRouterSupport.flattenCoreResponse(wrap(inner));
      assertFalse(flat.has("response"));
      assertEquals(1, flat.getJSONArray("data").length());
      assertEquals(0, flat.getInt("startRow"));
      assertEquals(0, flat.getInt("endRow"));
      assertEquals(1, flat.getInt("totalRows"));
    }

    /**
     * The DAL success code is dropped rather than translated: by this point the failure branches
     * have already returned, so it carries no information, and {@code status} on every other MCP
     * body is an HTTP code — an agent branching on it read {@code 0} where it expected {@code 200}.
     * Nothing is substituted, because the absence of {@code error} is already the success
     * discriminator the other verbs use.
     */
    @Test
    @DisplayName("drops the DAL status:0 and substitutes nothing")
    void dropsDalStatus() throws Exception {
      JSONObject flat = McpToolRouterSupport.flattenCoreResponse(
          wrap(new JSONObject().put("status", 0).put("data", new JSONArray())));
      assertFalse(flat.has("status"));
      assertFalse(flat.has("error"));
    }

    /**
     * Lifting by rule rather than by an allow-list is what keeps IMP-18's annotation working: it is
     * added inside the wrapper by {@code applyProjection} and reaches the agent at the top level
     * without this method naming it.
     */
    @Test
    @DisplayName("lifts a key it does not know about, such as IMP-18's unknownFields")
    void liftsUnknownKeys() throws Exception {
      JSONObject flat = McpToolRouterSupport.flattenCoreResponse(
          wrap(new JSONObject().put("status", 0).put("data", new JSONArray())
              .put("unknownFields", new JSONArray().put("nosuchfield"))));
      assertEquals("nosuchfield", flat.getJSONArray("unknownFields").getString(0));
    }

    @Test
    @DisplayName("returns an already-flat body untouched, so it is idempotent")
    void idempotentOnFlatBody() throws Exception {
      JSONObject flat = new JSONObject().put("data", new JSONArray()).put("totalRows", 0);
      assertSame(flat, McpToolRouterSupport.flattenCoreResponse(flat));
    }

    /**
     * A body carrying keys beside {@code response} is not a shape this layer produces, and merging
     * would have to guess at a collision. Passing it through is the conservative answer.
     */
    @Test
    @DisplayName("leaves a body that has keys beside 'response' alone")
    void leavesMixedBodyAlone() throws Exception {
      JSONObject mixed = wrap(new JSONObject().put("status", 0)).put("extra", "keep me");
      assertSame(mixed, McpToolRouterSupport.flattenCoreResponse(mixed));
    }

    @Test
    @DisplayName("null in, null out")
    void nullIsNull() throws Exception {
      assertNull(McpToolRouterSupport.flattenCoreResponse(null));
    }
  }

  // ─── buildDocsGuidance (IMP-10) ─────────────────────────────────────

  @Nested
  @DisplayName("buildDocsGuidance")
  class BuildDocsGuidance {

    @Test
    @DisplayName("advertises the docs tool with a recipe hint")
    void advertisesDocsTool() throws Exception {
      JSONObject guidance = McpToolRouterSupport.buildDocsGuidance();
      assertEquals("docs", guidance.getString("tool"));
      assertTrue(guidance.getString("hint").toLowerCase().contains("docs(topic"));
    }
  }

  @Nested
  @DisplayName("toMcpBatchFailure (IMP-15)")
  class ToMcpBatchFailure {

    /** The verbatim shape BatchService forwarded to agents before IMP-15. */
    private JSONObject rawDalFailure() throws Exception {
      JSONObject errors = new JSONObject();
      errors.put("id", "New object Currency(null)  (key: EUR_Currency) refered to but not present "
          + "in the import set");
      JSONObject response = new JSONObject();
      response.put("status", -4);
      response.put("errors", errors);
      JSONObject detail = new JSONObject();
      detail.put("response", response);

      JSONObject error = new JSONObject();
      error.put("status", 400);
      error.put("message", "Operation 'h1' rejected by server");
      error.put("detail", detail);
      JSONObject failedAt = new JSONObject();
      failedAt.put("index", 0);
      failedAt.put("id", "h1");
      JSONObject body = new JSONObject();
      body.put("committed", false);
      body.put("failedAt", failedAt);
      body.put("error", error);
      return body;
    }

    @Test
    @DisplayName("replaces the raw DAL detail with the IMP-5 envelope, keeping the failedAt pointer")
    void rewritesTheFailure() throws Exception {
      JSONObject result = McpToolRouterSupport.toMcpBatchFailure(rawDalFailure());

      JSONObject error = result.getJSONObject("error");
      assertEquals(400, error.getInt("status"));
      assertEquals("validation_error", error.getString("error"));
      assertEquals("docs(topic:\"creating records\")", error.getString("seeAlso"));
      // The DAL's own sentence survives — it names the value that could not be resolved.
      assertTrue(error.getString("detail").contains("EUR_Currency"));
      // …but its transport internals do not: status:-4 is not actionable by an agent.
      assertFalse(error.toString().contains("-4"));
      assertNull(error.optJSONObject("detail"));
      assertEquals("h1", result.getJSONObject("failedAt").getString("id"));
    }

    /**
     * ETP-4793 / IMP-17 (absorbing IMP-23 §9.4): a batch operation rejected for missing required
     * fields used to forward the REST layer's ETP-3894 {@code MISSING_REQUIRED_FIELDS} 400 verbatim,
     * so the same mistake reached agents in three different shapes depending on the tool. The REST
     * shape stays put — the React UI highlights fields from it — and the translation to IMP-24's
     * {@code missingFields} 422 happens here, which is exactly what this method exists for.
     */
    @Test
    @DisplayName("lifts a MISSING_REQUIRED_FIELDS rejection into the missingFields 422 shape")
    void liftsMissingRequiredFields() throws Exception {
      JSONArray fields = new JSONArray();
      fields.put("partnerAddress");
      JSONObject innerError = new JSONObject();
      innerError.put("code", "MISSING_REQUIRED_FIELDS");
      innerError.put("message", "Missing required fields");
      innerError.put("fields", fields);
      JSONObject detail = new JSONObject();
      detail.put("error", innerError);

      JSONObject error = new JSONObject();
      error.put("status", 400);
      error.put("message", "Operation 'h1' rejected by server");
      error.put("detail", detail);
      JSONObject body = new JSONObject();
      body.put("committed", false);
      body.put("error", error);

      JSONObject result = McpToolRouterSupport.toMcpBatchFailure(body);

      JSONObject mapped = result.getJSONObject("error");
      assertEquals(422, mapped.getInt("status"));
      assertEquals("validation_error", mapped.getString("error"));
      assertEquals("partnerAddress", mapped.getJSONArray("missingFields").getString(0));
      // The REST envelope's own nesting is gone: an agent parses one key, not three shapes.
      assertNull(mapped.optJSONObject("detail"));
      assertFalse(mapped.toString().contains("MISSING_REQUIRED_FIELDS"));
    }

    @Test
    @DisplayName("a committed batch and a body with no error object pass through untouched")
    void passesThroughNonFailures() throws Exception {
      JSONObject committed = new JSONObject();
      committed.put("committed", true);
      assertTrue(McpToolRouterSupport.toMcpBatchFailure(committed).getBoolean("committed"));

      JSONObject noError = new JSONObject();
      noError.put("committed", false);
      assertNull(McpToolRouterSupport.toMcpBatchFailure(noError).optJSONObject("error"));
      assertNull(McpToolRouterSupport.toMcpBatchFailure(null));
    }

    @Test
    @DisplayName("maps each status onto a stable code an agent can branch on")
    void mapsStatusesToCodes() {
      assertEquals("not_found", McpToolRouterSupport.errorCodeForStatus(404));
      assertEquals("method_not_allowed", McpToolRouterSupport.errorCodeForStatus(405));
      assertEquals("validation_error", McpToolRouterSupport.errorCodeForStatus(400));
      assertEquals("validation_error", McpToolRouterSupport.errorCodeForStatus(422));
      assertEquals("server_error", McpToolRouterSupport.errorCodeForStatus(500));
    }

    @Test
    @DisplayName("extracts a message from either DAL error shape, and none when there is none")
    void extractsDalMessages() throws Exception {
      JSONObject nested = new JSONObject();
      JSONObject inner = new JSONObject();
      inner.put("message", "Unit of Measure mismatch (product/transaction)");
      JSONObject response = new JSONObject();
      response.put("error", inner);
      nested.put("response", response);
      assertEquals("Unit of Measure mismatch (product/transaction)",
          McpToolRouterSupport.extractDalMessage(nested));

      JSONObject perField = new JSONObject();
      JSONObject errors = new JSONObject();
      errors.put("documentNo", "required");
      perField.put("errors", errors);
      assertEquals("documentNo: required", McpToolRouterSupport.extractDalMessage(perField));

      assertNull(McpToolRouterSupport.extractDalMessage(null));
      assertNull(McpToolRouterSupport.extractDalMessage(new JSONObject()));
    }
  }

  @Nested
  @DisplayName("toMcpHandlerError — the fourth error funnel (IMP-5 clause (iv))")
  class ToMcpHandlerError {

    @Test
    @DisplayName("flattens the nested NeoResponse.error shape into the canonical envelope")
    void flattensNestedError() throws Exception {
      // Verbatim from the live probe that found this funnel: generate_aging_receivable({}) after
      // passing contract validation, failing inside the handler (IMP-19 §6.3).
      JSONObject inner = new JSONObject();
      inner.put("message", "No accounting schema with currency is configured for organization "
          + "61849243BE89460EB70866880A545D50");
      inner.put("status", 422);
      JSONObject body = new JSONObject();
      body.put("error", inner);

      JSONObject envelope = McpToolRouterSupport.toMcpHandlerError(body, 422);

      assertEquals(422, envelope.getInt("status"));
      assertEquals("validation_error", envelope.getString("error"));
      assertTrue(envelope.getString("detail").startsWith("No accounting schema with currency"));
      // The nesting is gone: 'error' is a code an agent can branch on, not an object.
      assertNull(envelope.optJSONObject("error"));
    }

    @Test
    @DisplayName("adds no seeAlso — neither docs topic helps an instance-configuration failure")
    void addsNoSeeAlso() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("message", "No accounting schema with currency is configured");
      inner.put("status", 422);
      JSONObject body = new JSONObject();
      body.put("error", inner);

      // Pinned rather than left to a comment, on IMP-17 §4.3's precedent: a deliberate omission and
      // a forgotten key look identical in a response, so the test has to state which this is.
      assertFalse(McpToolRouterSupport.toMcpHandlerError(body, 422).has("seeAlso"));
    }

    @Test
    @DisplayName("leaves an already-canonical envelope untouched, so normalizing twice is safe")
    void isIdempotent() throws Exception {
      // The shape IMP-17 and IMP-24 build upstream. Re-flattening it would strip the very keys
      // that make it actionable, so the early return is what protects them.
      JSONObject canonical = new JSONObject();
      canonical.put("status", 422);
      canonical.put("error", "validation_error");
      canonical.put("detail", "Missing required fields");
      canonical.put("missingFields", new JSONArray().put("partnerAddress"));
      canonical.put("seeAlso", "docs(topic:\"creating records\")");

      JSONObject once = McpToolRouterSupport.toMcpHandlerError(canonical, 422);
      JSONObject twice = McpToolRouterSupport.toMcpHandlerError(once, 422);

      assertEquals("validation_error", twice.getString("error"));
      assertEquals("partnerAddress", twice.getJSONArray("missingFields").getString(0));
      assertEquals("docs(topic:\"creating records\")", twice.getString("seeAlso"));
      assertEquals("Missing required fields", twice.getString("detail"));
    }

    @Test
    @DisplayName("lifts the nested object's other keys instead of discarding them")
    void preservesHandlerDetail() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("message", "Period is not open");
      inner.put("status", 422);
      inner.put("field", "accountingDate");
      inner.put("available", new JSONArray().put("2026-08"));
      JSONObject body = new JSONObject();
      body.put("error", inner);

      JSONObject envelope = McpToolRouterSupport.toMcpHandlerError(body, 422);

      // A handler that named a field and its candidates meant the agent to see them; a
      // normalization that dropped them would trade one unusable error for another.
      assertEquals("accountingDate", envelope.getString("field"));
      assertEquals("2026-08", envelope.getJSONArray("available").getString(0));
      assertEquals("Period is not open", envelope.getString("detail"));
    }

    @Test
    @DisplayName("gives a body-less failure a status, a code and a detail")
    void handlesNullBody() throws Exception {
      JSONObject envelope = McpToolRouterSupport.toMcpHandlerError(null, 500);

      assertEquals(500, envelope.getInt("status"));
      assertEquals("server_error", envelope.getString("error"));
      assertEquals("Request failed with status 500", envelope.getString("detail"));
    }

    @Test
    @DisplayName("annotates an unrecognised body without removing anything from it")
    void annotatesUnknownShape() throws Exception {
      JSONObject body = new JSONObject();
      body.put("reportRows", new JSONArray().put("partial"));
      body.put("warning", "truncated");

      JSONObject envelope = McpToolRouterSupport.toMcpHandlerError(body, 500);

      assertEquals(500, envelope.getInt("status"));
      assertEquals("server_error", envelope.getString("error"));
      // Additive: a handler's own payload survives, because we cannot know it was not the point.
      assertEquals("truncated", envelope.getString("warning"));
      assertEquals("partial", envelope.getJSONArray("reportRows").getString(0));
    }
  }

  @Nested
  @DisplayName("toMcpBatchPreflightFailure — one shape per condition (IMP-5 clause (i))")
  class ToMcpBatchPreflightFailure {

    /** The resolver's structured error for an FK name that matched nothing (evidence C9). */
    private JSONObject fkError() throws Exception {
      JSONObject error = new JSONObject();
      error.put("status", 422);
      error.put("error", "not_found");
      error.put("detail", "No Currency matches 'EUR_Currency'");
      error.put("field", "currency");
      return error;
    }

    @Test
    @DisplayName("carries committed:false, the key an agent is told to branch on")
    void carriesCommitted() throws Exception {
      JSONObject body = McpToolRouterSupport.toMcpBatchPreflightFailure(fkError(), 1, "l1");

      // The whole of clause (i): this key was absent, so an agent following neo_batch's own
      // documented contract read false from a missing key by luck rather than by promise.
      assertTrue(body.has("committed"));
      assertFalse(body.getBoolean("committed"));
      assertEquals(1, body.getJSONObject("failedAt").getInt("index"));
      assertEquals("l1", body.getJSONObject("failedAt").getString("id"));
      assertEquals("not_found", body.getJSONObject("error").getString("error"));
      assertEquals("currency", body.getJSONObject("error").getString("field"));
    }

    @Test
    @DisplayName("claims atomic:true with an empty persisted list — true by construction here")
    void claimsAtomicity() throws Exception {
      JSONObject body = McpToolRouterSupport.toMcpBatchPreflightFailure(fkError(), 0, "h0");

      // Stronger than executeBatch can promise: the pre-pass runs before the transaction opens,
      // so nothing can have persisted. IMP-23 §1 found that this is exactly why FK failures
      // always looked atomic while persist-time failures were not.
      assertTrue(body.getBoolean("atomic"));
      assertEquals(0, body.getJSONArray("persisted").length());
      // And the hint must say why, not reuse the rollback wording: no rollback happened.
      assertTrue(body.getString("hint").contains("before the transaction opened"));
    }

    @Test
    @DisplayName("omits the failedAt id when the operation declared none")
    void omitsBlankOpId() throws Exception {
      assertFalse(McpToolRouterSupport.toMcpBatchPreflightFailure(fkError(), 2, null)
          .getJSONObject("failedAt").has("id"));
      assertFalse(McpToolRouterSupport.toMcpBatchPreflightFailure(fkError(), 2, "  ")
          .getJSONObject("failedAt").has("id"));
    }

    @Test
    @DisplayName("matches the outcome keys BatchService itself defines")
    void usesBatchServiceKeys() throws Exception {
      JSONObject body = McpToolRouterSupport.toMcpBatchPreflightFailure(fkError(), 0, "h0");

      // Pins the shared-constant decision rather than the literals: if BatchService renames an
      // outcome key, this fails here instead of drifting silently in a response body.
      assertTrue(body.has(com.etendoerp.go.schemaforge.BatchService.FIELD_COMMITTED));
      assertTrue(body.has(com.etendoerp.go.schemaforge.BatchService.FIELD_ATOMIC));
      assertTrue(body.has(com.etendoerp.go.schemaforge.BatchService.FIELD_PERSISTED));
      assertTrue(body.has(com.etendoerp.go.schemaforge.BatchService.FIELD_HINT));
    }
  }

  // ─── Helper ─────────────────────────────────────────────────────────

  /**
   * Stub {@code OBDal.createCriteria(SFEntity.class)} so
   * {@code McpToolRouterSupport#listIncludedEntities} returns {@code entities}.
   * {@code addOrder} is intentionally left unstubbed — the criteria's return value is
   * discarded by the production code.
   */
  @SuppressWarnings("unchecked")
  private static OBCriteria<SFEntity> stubEntityCriteria(OBDal obDal, List<SFEntity> entities) {
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(org.mockito.ArgumentMatchers.any(Criterion.class))).thenReturn(criteria);
    when(criteria.list()).thenReturn(entities);
    return criteria;
  }

  /** An entity served by a NeoHandler: no AD_Tab, readable, never mutable (dashboard shape). */
  private static SFEntity handlerOnlyEntity() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn("kpis");
    when(entity.getADTab()).thenReturn(null);
    when(entity.isGet()).thenReturn(true);
    return entity;
  }

  /**
   * An AD_Tab-backed CRUD entity.
   *
   * @param mutable when {@code true} every mutation flag is on; when {@code false} the entity
   *                is GET-only — the monitor/log shape ETP-4254 targets
   */
  private static SFEntity tabBackedEntity(boolean mutable) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn("header");
    when(entity.getADTab()).thenReturn(mock(Tab.class));
    when(entity.isGet()).thenReturn(true);
    when(entity.isGetByID()).thenReturn(true);
    when(entity.isPost()).thenReturn(mutable);
    when(entity.isPut()).thenReturn(mutable);
    when(entity.isPatch()).thenReturn(mutable);
    when(entity.isDelete()).thenReturn(mutable);
    return entity;
  }

  private static boolean arrayContains(JSONArray array, String value) {
    for (int i = 0; i < array.length(); i++) {
      try {
        if (value.equals(array.getString(i))) {
          return true;
        }
      } catch (Exception ignored) {
        // skip
      }
    }
    return false;
  }
}
