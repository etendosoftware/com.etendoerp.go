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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.ReconciliationHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * ETP-5468 — {@code neo_schema} on a report spec whose handler declares named actions
 * ({@code bank-reconciliation}) answers with the action catalog BEFORE the generic path, which
 * rejects every {@code SPEC_TYPE=R} spec as not CRUD-capable. Every other spec keeps the old path.
 *
 * <p>{@code McpToolRouterSupport} is mocked with {@code CALLS_REAL_METHODS}: only the DAL lookups
 * ({@code findActiveSpecByName}, {@code findIncludedEntity}, {@code listIncludedEntities}) and the
 * generic-path entry ({@code resolveIncludedEntityOrExplain}) are stubbed, so {@code validateArgs}
 * runs for real.</p>
 */
@DisplayName("neo_schema on an action report spec (ETP-5468)")
class McpSchemaReportActionsTest {

  private static final String SPEC = "bank-reconciliation";
  private static final String SPEC_ID = "spec-rec";
  private static final String REC_Q = "rec-q";
  private static final String PLAIN_Q = "plain-q";

  /** Thrown by the stubbed generic path, to prove a call reached it. */
  private static final class GenericPathReached extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private MockedStatic<McpToolRouterSupport> support;
  private MockedStatic<NeoHandlerLookup> lookup;
  private MockedStatic<OBDal> obDal;
  private SFSpec spec;
  /**
   * What {@code NeoActionContract.resolve(spec)} sees as the spec's active included entities
   * (BUG-4: the pre-step returns before any entity lookup unless one of them declares actions).
   */
  private final List<SFEntity> resolveEntities = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn(SPEC_ID);
    when(spec.getName()).thenReturn(SPEC);
    when(spec.getSpecType()).thenReturn("R");

    support = mockStatic(McpToolRouterSupport.class, Mockito.CALLS_REAL_METHODS);
    support.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenReturn(spec);
    support.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(any(), anyString()))
        .thenThrow(new GenericPathReached());

    NeoHandler plain = context -> null;
    lookup = mockStatic(NeoHandlerLookup.class);
    lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(REC_Q))
        .thenReturn(new ReconciliationHandler());
    lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(PLAIN_Q)).thenReturn(plain);

    // Default: the spec declares actions through its bank-reconciliation entity.
    resolveEntities.add(entity(SPEC, REC_Q));
    OBDal dal = mock(OBDal.class);
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.list()).thenAnswer(inv -> new ArrayList<>(resolveEntities));
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
  }

  /** Makes the spec one whose handlers declare NO actions (every report spec but ours). */
  private void specDeclaresNoActions() {
    resolveEntities.clear();
    resolveEntities.add(entity("header", PLAIN_Q));
  }

  @AfterEach
  void tearDown() {
    support.close();
    lookup.close();
    obDal.close();
    Mockito.framework().clearInlineMocks();
  }

  private static SFEntity entity(String name, String qualifier) {
    SFEntity e = mock(SFEntity.class);
    when(e.getName()).thenReturn(name);
    when(e.getJavaQualifier()).thenReturn(qualifier);
    return e;
  }

  private static JSONObject handleSchema(JSONObject args) throws Exception {
    Method m = McpToolRouter.class.getDeclaredMethod("handleSchema", String.class,
        JSONObject.class);
    m.setAccessible(true);
    try {
      return (JSONObject) m.invoke(new McpToolRouter(), SPEC, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  /** @return the JSON body inside the MCP text content */
  private static JSONObject body(JSONObject result) throws Exception {
    return new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  private static void assertCatalog(JSONObject result, String entityName) throws Exception {
    JSONObject body = body(result);
    assertEquals(SPEC, body.getString("spec"));
    assertEquals(entityName, body.getString("entity"));
    assertEquals(9, body.getInt("actionCount"));
    assertEquals(9, body.getJSONArray("actions").length());
  }

  @Test
  @DisplayName("report spec + entity that declares actions → the catalog (any view)")
  void entityWithActions() throws Exception {
    SFEntity rec = entity(SPEC, REC_Q);
    support.when(() -> McpToolRouterSupport.findIncludedEntity(SPEC_ID, SPEC)).thenReturn(rec);

    for (String view : List.of("actions", "create", "fields")) {
      assertCatalog(handleSchema(new JSONObject().put("entity", SPEC).put("view", view)), SPEC);
    }
    assertCatalog(handleSchema(new JSONObject().put("entity", SPEC)), SPEC);
    support.verify(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(any(), anyString()),
        never());
  }

  @Test
  @DisplayName("declared-actions spec + known entity WITHOUT actions → the generic path")
  void entityWithoutActions() throws Exception {
    SFEntity other = entity("other", PLAIN_Q);
    support.when(() -> McpToolRouterSupport.findIncludedEntity(SPEC_ID, "other"))
        .thenReturn(other);
    assertThrows(GenericPathReached.class,
        () -> handleSchema(new JSONObject().put("entity", "other")));
  }

  @Test
  @DisplayName("BUG-4: declared-actions spec + unknown entity → the 404 listing valid entities")
  void unknownEntityOnActionSpecIs404() {
    McpRoutingException notFound = McpRoutingException.entityNotFound("nope", SPEC,
        List.of(SPEC));
    support.when(() -> McpToolRouterSupport.findIncludedEntity(SPEC_ID, "nope"))
        .thenThrow(notFound);
    McpRoutingException ex = assertThrows(McpRoutingException.class,
        () -> handleSchema(new JSONObject().put("entity", "nope")));
    assertSame(notFound, ex);
    assertTrue(ex.getMessage().contains("No entity 'nope' in spec 'bank-reconciliation'"));
    support.verify(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(any(), anyString()),
        never());
  }

  @Test
  @DisplayName("BUG-4: a report spec WITHOUT declared actions + unknown entity → original path, "
      + "no entity lookup")
  void noActionsSpecUnknownEntityKeepsOriginalPath() {
    specDeclaresNoActions();
    when(spec.getName()).thenReturn("aging-receivable");
    support.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenReturn(spec);

    assertThrows(GenericPathReached.class,
        () -> handleSchema(new JSONObject().put("entity", "header")));
    support.verify(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()),
        never());
    support.verify(() -> McpToolRouterSupport.listIncludedEntities(anyString()), never());
    support.verify(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(spec, "header"));
  }

  @Test
  @DisplayName("BUG-4: without declared actions the real generic path answers not-CRUD-capable "
      + "(the generate_* / not-configured 422)")
  void noActionsSpecRealGenericRefusal() {
    specDeclaresNoActions();
    // Let the real resolveIncludedEntityOrExplain run: for an R spec it refuses before any lookup.
    support.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(any(), anyString()))
        .thenCallRealMethod();
    try (MockedStatic<NeoReportCallability> callability =
        mockStatic(NeoReportCallability.class)) {
      callability.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(true);
      McpRoutingException ex = assertThrows(McpRoutingException.class,
          () -> handleSchema(new JSONObject().put("entity", "header")));
      assertTrue(ex.getMessage().contains("generate_bank_reconciliation"), ex.getMessage());
    }
    support.verify(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()),
        never());
  }

  @Test
  @DisplayName("BUG-4: without declared actions and no entity → still 'Missing required argument'")
  void noActionsSpecNoEntity() {
    specDeclaresNoActions();
    McpRoutingException ex = assertThrows(McpRoutingException.class,
        () -> handleSchema(new JSONObject()));
    assertTrue(ex.getMessage().contains("Missing required argument: entity"));
    support.verify(() -> McpToolRouterSupport.listIncludedEntities(anyString()), never());
  }

  @Test
  @DisplayName("no entity + exactly one action entity → its catalog")
  void noEntitySingleActionEntity() throws Exception {
    support.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
        .thenReturn(List.of(entity("other", PLAIN_Q), entity(SPEC, REC_Q)));
    assertCatalog(handleSchema(new JSONObject().put("view", "actions")), SPEC);
    assertCatalog(handleSchema(new JSONObject()), SPEC);
  }

  @Test
  @DisplayName("no entity + no action entity → 'Missing required argument: entity'")
  void noEntityZeroActionEntities() {
    specDeclaresNoActions();
    support.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
        .thenReturn(List.of(entity("other", PLAIN_Q)));
    McpRoutingException ex = assertThrows(McpRoutingException.class,
        () -> handleSchema(new JSONObject()));
    assertTrue(ex.getMessage().contains("Missing required argument: entity"), ex.getMessage());
  }

  @Test
  @DisplayName("no entity + two action entities → ambiguous → 'Missing required argument: entity'")
  void noEntityTwoActionEntities() {
    resolveEntities.add(entity("second", REC_Q));
    support.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
        .thenReturn(List.of(entity(SPEC, REC_Q), entity("second", REC_Q)));
    McpRoutingException ex = assertThrows(McpRoutingException.class,
        () -> handleSchema(new JSONObject().put("view", "actions")));
    assertTrue(ex.getMessage().contains("Missing required argument: entity"), ex.getMessage());
  }

  @Test
  @DisplayName("a blank entity counts as absent")
  void blankEntityCountsAsAbsent() throws Exception {
    support.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
        .thenReturn(List.of(entity(SPEC, REC_Q)));
    assertCatalog(handleSchema(new JSONObject().put("entity", "  ")), SPEC);
  }

  @Test
  @DisplayName("a window spec is unchanged: straight to the generic path, no action probe")
  void windowSpecUnchanged() {
    when(spec.getSpecType()).thenReturn("W");
    assertThrows(GenericPathReached.class,
        () -> handleSchema(new JSONObject().put("entity", "header")));
    support.verify(() -> McpToolRouterSupport.listIncludedEntities(anyString()), never());
    support.verify(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()),
        never());
  }

  @Test
  @DisplayName("a window spec without entity still gets 'Missing required argument: entity'")
  void windowSpecMissingEntity() {
    when(spec.getSpecType()).thenReturn("W");
    McpRoutingException ex = assertThrows(McpRoutingException.class,
        () -> handleSchema(new JSONObject()));
    assertTrue(ex.getMessage().contains("Missing required argument: entity"));
  }

  @Test
  @DisplayName("an unknown spec falls through to the old path's own refusal")
  void unknownSpec() {
    RuntimeException notFound = new IllegalStateException("spec not found");
    support.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenThrow(notFound);
    Exception ex = assertThrows(Exception.class,
        () -> handleSchema(new JSONObject().put("entity", SPEC)));
    assertSame(notFound, ex);
  }
}
