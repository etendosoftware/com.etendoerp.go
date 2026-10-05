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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * ETP-5558 Step 2 — the {@code verbs} section of {@code MCP_CONFIG}: an MCP-only way to hide a
 * write verb that {@code ETGO_SF_ENTITY} still enables for REST and the SPA.
 *
 * <p><b>Why it exists.</b> The payment windows create and edit payments only through the invoice
 * actions ({@code registerPayment} and siblings), yet every payment entity has every method flag on,
 * so MCP advertised and executed a hand-built payment route no UI offers and nothing validates — the
 * route BUG-1 corrupted data through. The flags cannot be turned off, because REST and the SPA read
 * them too. The section hides the verb for agents only, and the refusal points at the action that
 * does the job instead.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects the outer class.
@SuppressWarnings("java:S2187")
@DisplayName("ETP-5558 — MCP_CONFIG.verbs hides MCP write verbs, never REST")
class McpVerbsSectionTest {

  private static final String SPEC_NAME = "payment-in";
  private static final String ENTITY_NAME = "finPayment";
  private static int seq;

  @BeforeEach
  void setUp() {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
  }

  @AfterEach
  void tearDown() {
    McpConfigCache.invalidateAll();
  }

  private static JSONObject body(String json) throws Exception {
    return new JSONObject(json);
  }

  /** A header entity with every method flag on and the given MCP_CONFIG. */
  private static SFEntity entity(String mcpConfig) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-" + (++seq));
    when(spec.getName()).thenReturn(SPEC_NAME);
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(0L);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("ent-" + (++seq));
    when(entity.getName()).thenReturn(ENTITY_NAME);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(entity.getADTab()).thenReturn(tab);
    when(entity.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(mcpConfig);
    when(entity.isGet()).thenReturn(true);
    when(entity.isPost()).thenReturn(true);
    when(entity.isPut()).thenReturn(true);
    when(entity.isPatch()).thenReturn(true);
    when(entity.isDelete()).thenReturn(true);
    return entity;
  }

  // ── the section contract ──────────────────────────────────────────────

  @Nested
  @DisplayName("validation (§4.12.6 section contract)")
  class Validation {

    @Test
    @DisplayName("a verb set to false with a reason is valid")
    void validBody() throws Exception {
      assertTrue(McpVerbsSection.validate(
          body("{\"create\":false,\"reason\":\"created through registerPayment\"}")).isEmpty());
    }

    @Test
    @DisplayName("reason is mandatory — every hidden verb must be auditable")
    void reasonIsMandatory() throws Exception {
      List<String> problems = McpVerbsSection.validate(body("{\"create\":false}"));
      assertEquals(1, problems.size(), problems.toString());
      assertTrue(problems.get(0).contains("reason"));
      assertFalse(McpVerbsSection.validate(body("{\"create\":false,\"reason\":\"  \"}")).isEmpty());
    }

    @Test
    @DisplayName("a verb given as a string is refused, not read as true or false")
    void verbsAreBooleans() throws Exception {
      List<String> problems = McpVerbsSection.validate(
          body("{\"create\":\"false\",\"reason\":\"r\"}"));
      assertFalse(problems.isEmpty());
      assertTrue(problems.get(0).contains("create"));
    }

    @Test
    @DisplayName("a body with no verb key is refused — remove the section instead")
    void needsAVerb() throws Exception {
      assertFalse(McpVerbsSection.validate(body("{\"reason\":\"r\"}")).isEmpty());
    }

    @Test
    @DisplayName("instead, when present, must not be blank")
    void insteadNotBlank() throws Exception {
      assertFalse(McpVerbsSection.validate(
          body("{\"create\":false,\"reason\":\"r\",\"instead\":\"\"}")).isEmpty());
    }

    @Test
    @DisplayName("is registered, entity-level REPLACE, and an unknown key is an error")
    void unknownKeyIsAnError() {
      McpConfigSection declaration = McpVerbsSection.declaration();
      assertEquals("verbs", declaration.getName());
      assertEquals(McpConfigSection.Merge.REPLACE, declaration.getMerge());
      McpEntityConfig.Resolved resolved = McpEntityConfig.forEntity(
          entity("{\"verbs\":{\"create\":false,\"reason\":\"r\",\"craete\":false}}"));
      assertFalse(resolved.isUsable(), "a typo must not read as unconfigured");
    }
  }

  // ── the policy every MCP surface reads ────────────────────────────────

  @Nested
  @DisplayName("McpMethodPolicy — what the MCP advertises and executes")
  class Policy {

    @Test
    @DisplayName("create:false hides POST only; GET, PUT, PATCH and DELETE stay")
    void hidesCreateOnly() {
      SFEntity e = entity("{\"verbs\":{\"create\":false,\"reason\":\"r\"}}");

      assertFalse(McpMethodPolicy.isMethodEnabled(e, "POST"));
      assertEquals(List.of("GET", "PUT", "PATCH", "DELETE"), McpMethodPolicy.enabledMethods(e));
      assertFalse(McpMethodPolicy.isReadOnly(e));
    }

    @Test
    @DisplayName("update:false hides both PUT and PATCH; every write hidden makes it read-only")
    void hidesEveryWrite() {
      SFEntity e = entity(
          "{\"verbs\":{\"create\":false,\"update\":false,\"delete\":false,\"reason\":\"r\"}}");

      assertEquals(List.of("GET"), McpMethodPolicy.enabledMethods(e));
      assertTrue(McpMethodPolicy.isReadOnly(e));
      assertTrue(McpToolRouterSupport.isReadOnlySpec(List.of(e)),
          "a spec whose every write is hidden is advertised read-only");
    }

    @Test
    @DisplayName("true never widens: an ETGO_SF_ENTITY flag that is off stays off")
    void neverWidens() {
      SFEntity e = entity("{\"verbs\":{\"create\":true,\"reason\":\"r\"}}");
      when(e.isPost()).thenReturn(false);

      assertFalse(McpMethodPolicy.isMethodEnabled(e, "POST"));
    }

    @Test
    @DisplayName("no MCP_CONFIG: exactly the ETGO_SF_ENTITY flags")
    void unconfiguredIsUnchanged() {
      assertEquals(List.of("GET", "POST", "PUT", "PATCH", "DELETE"),
          McpMethodPolicy.enabledMethods(entity(null)));
    }

    @Test
    @DisplayName("an unusable MCP_CONFIG hides every write (fail closed) and keeps reads")
    void unusableConfigFailsClosed() {
      SFEntity e = entity("{\"verbs\":{\"create\":false}}");

      assertEquals(List.of("GET"), McpMethodPolicy.enabledMethods(e),
          "a restriction that failed validation must not switch itself off");
    }
  }

  // ── the refusal ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("requireMethodEnabled — the refusal the write verbs return")
  class Refusal {

    @Test
    @DisplayName("a hidden verb is a 405 method_not_allowed naming the reason and the way instead")
    void hiddenVerbRefusal() throws Exception {
      SFEntity e = entity("{\"verbs\":{\"create\":false,\"reason\":\"Payments are created from "
          + "the invoice\",\"instead\":\"etendo_action(spec:'sales-invoice', entity:'header', "
          + "action:'registerPayment')\"}}");

      McpRoutingException refusal = assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.requireMethodEnabled(e.getETGOSFSpec(), e, "POST"));
      JSONObject envelope = refusal.toEnvelope();
      assertEquals(405, envelope.getInt(McpConstants.KEY_STATUS));
      assertEquals("method_not_allowed", envelope.getString(McpConstants.KEY_ERROR));
      String detail = envelope.getString(McpConstants.KEY_DETAIL);
      assertTrue(detail.contains(ENTITY_NAME) && detail.contains(SPEC_NAME), detail);
      assertTrue(detail.contains("Payments are created from the invoice"), detail);
      assertTrue(detail.contains("Nothing was written"), detail);
      assertTrue(envelope.getString(McpConstants.KEY_HINT).contains("registerPayment"),
          envelope.toString());
    }

    @Test
    @DisplayName("without 'instead', the hint points at this entity's actions, callable as is")
    void fallbackHintNamesTheActions() throws Exception {
      SFEntity e = entity("{\"verbs\":{\"delete\":false,\"reason\":\"r\"}}");

      McpRoutingException refusal = assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.requireMethodEnabled(e.getETGOSFSpec(), e, "DELETE"));
      String hint = refusal.toEnvelope().getString(McpConstants.KEY_HINT);
      assertTrue(hint.contains("etendo_schema(spec:'" + SPEC_NAME + "', entity:'" + ENTITY_NAME
          + "', view:'actions')"), hint);
    }

    /**
     * Review WARN-3: a method whose flag is off keeps its historical refusal, but its "Enabled
     * methods" list must be the MCP's — listing a verb the section hides would send the agent
     * straight into a second refusal.
     */
    @Test
    @DisplayName("a flag-off refusal never lists a hidden verb among the enabled methods")
    void flagOffRefusalListsOnlyMcpMethods() throws Exception {
      SFEntity e = entity("{\"verbs\":{\"create\":false,\"reason\":\"r\"}}");
      when(e.isDelete()).thenReturn(false);

      McpRoutingException refusal = assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.requireMethodEnabled(e.getETGOSFSpec(), e, "DELETE"));
      String detail = refusal.toEnvelope().getString(McpConstants.KEY_DETAIL);
      assertTrue(detail.contains("Enabled methods: GET, PUT, PATCH."), detail);
      assertFalse(detail.contains("POST"), "POST is hidden from the MCP: " + detail);
    }

    @Test
    @DisplayName("every verb hidden plus a flag off reads as read-only, like the shared wording")
    void flagOffOnAnMcpReadOnlyEntity() throws Exception {
      SFEntity e = entity("{\"verbs\":{\"create\":false,\"update\":false,\"reason\":\"r\"}}");
      when(e.isDelete()).thenReturn(false);

      McpRoutingException refusal = assertThrows(McpRoutingException.class,
          () -> McpToolRouterSupport.requireMethodEnabled(e.getETGOSFSpec(), e, "DELETE"));
      String detail = refusal.toEnvelope().getString(McpConstants.KEY_DETAIL);
      assertTrue(detail.contains("Enabled methods: GET."), detail);
      assertTrue(detail.contains("read-only by configuration"), detail);
    }

    @Test
    @DisplayName("a verb that is not hidden passes")
    void visibleVerbPasses() {
      SFEntity e = entity("{\"verbs\":{\"create\":false,\"reason\":\"r\"}}");
      assertDoesNotThrow(
          () -> McpToolRouterSupport.requireMethodEnabled(e.getETGOSFSpec(), e, "PUT"));
    }
  }

  // ── a fail-closed entity says so ──────────────────────────────────────

  /**
   * Review WARN-1: failing closed on a header entity used to be silent. The scope never reads the
   * configuration of a header (it is NOT_CHILD before that), so {@code configError} only ever
   * appeared on children, and a header whose writes had vanished looked merely read-only.
   */
  @Nested
  @DisplayName("configError on any entity whose MCP_CONFIG is unusable")
  class ConfigError {

    @Test
    @DisplayName("etendo_discover reports configError on a header entity with an unusable config")
    void headerReportsConfigError() throws Exception {
      SFEntity e = entity("{\"verbs\":{\"create\":false}}");

      JSONObject item = McpSupportInternals.buildDiscoverEntity(e);

      assertTrue(item.has("configError"), item.toString());
      assertTrue(item.getString("configError").contains("reason"), item.toString());
      assertTrue(item.getBoolean("readOnly"), "its writes are hidden, and now it says why");
    }

    @Test
    @DisplayName("a usable config adds no configError")
    void usableConfigAddsNothing() throws Exception {
      JSONObject item = McpSupportInternals.buildDiscoverEntity(
          entity("{\"verbs\":{\"create\":false,\"reason\":\"r\"}}"));
      assertFalse(item.has("configError"), item.toString());
    }
  }

  // ── every MCP surface consults the policy ─────────────────────────────

  /**
   * The verbs can only be hidden consistently if every surface asks the same question; one that
   * kept reading the raw flags would advertise or execute what the others refuse. The router
   * methods need a live DAL, so the call sites are pinned by source.
   */
  @Nested
  @DisplayName("call sites")
  class CallSites {

    private String router() {
      return McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java");
    }

    @Test
    @DisplayName("etendo_batch applies the method gate per operation, before the curation gates")
    void batchApplyTheGate() {
      String body = McpSourceScanner.methodBody(router(), "preprocessBatchOperation");
      Matcher gate = Pattern.compile("requireMethodEnabled\\s*\\(\\s*spec\\s*,\\s*sfEntity\\s*,"
          + "\\s*HTTP_METHOD_POST\\s*\\)").matcher(body);
      assertTrue(gate.find(), "preprocessBatchOperation must call "
          + "McpToolRouterSupport.requireMethodEnabled(spec, sfEntity, HTTP_METHOD_POST)");
      Matcher writeGates = Pattern.compile("applyWriteGatesToDalBody\\s*\\(").matcher(body);
      assertTrue(writeGates.find());
      assertTrue(gate.start() < writeGates.start());
    }

    @Test
    @DisplayName("etendo_schema derives its methods from the policy and gates view:create")
    void schemaUsesThePolicy() {
      String body = McpSourceScanner.methodBody(router(), "handleSchema");
      assertFalse(Pattern.compile("sfEntity\\s*\\.\\s*is(Post|Put|Delete)\\s*\\(").matcher(body)
          .find(), "handleSchema must not read the raw ETGO_SF_ENTITY write flags");
      assertTrue(Pattern.compile("McpMethodPolicy\\s*\\.\\s*isMethodEnabled\\s*\\(").matcher(body)
          .find(), "handleSchema must build 'methods' through McpMethodPolicy");
      assertTrue(Pattern.compile("requireVerbNotHidden\\s*\\(").matcher(body).find(),
          "view:\"create\" must refuse a hidden create");
    }

    @Test
    @DisplayName("no MCP class reads NeoMethodPolicy except through McpMethodPolicy")
    void onlyThePolicyReadsTheFlags() {
      for (String file : List.of("McpToolRouterSupport.java", "McpSupportInternals.java",
          "ToolRegistry.java", "McpResourceProvider.java", "McpToolRouter.java")) {
        String src = McpSourceScanner.stripComments(
            McpSourceScanner.read("com/etendoerp/go/mcp/" + file));
        assertFalse(Pattern.compile("NeoMethodPolicy\\s*(\\.|::)\\s*(isMethodEnabled|"
            + "enabledMethods|isReadOnly|hasMutableMethod|buildMcpNotEnabledMessage)").matcher(src)
            .find(),
            file + " reads the raw flags; route it through McpMethodPolicy so hidden verbs agree");
      }
    }

    /**
     * Review WARN-2: the entity's own getters are the other way to read the raw flags, and
     * {@code McpParentScope.advertisedWrites} was doing exactly that. Every MCP class except the
     * policy itself is scanned.
     */
    @Test
    @DisplayName("no MCP class reads the write flags — getters or NeoMethodPolicy — but the policy")
    void noRawFlagGetters() throws Exception {
      java.nio.file.Path dir = java.nio.file.Paths.get(McpSourceScanner.srcRootForTests())
          .resolve("com/etendoerp/go/mcp");
      Pattern raw = Pattern.compile("\\.\\s*is(Post|Put|Patch|Delete)\\s*\\(\\s*\\)"
          + "|NeoMethodPolicy\\s*(\\.|::)\\s*(isMethodEnabled|enabledMethods|isReadOnly|"
          + "hasMutableMethod|buildMcpNotEnabledMessage)");
      List<String> offenders = new java.util.ArrayList<>();
      try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
        for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) {
          String name = f.getFileName().toString();
          if (!name.endsWith(".java") || "McpMethodPolicy.java".equals(name)) {
            continue;
          }
          String src = McpSourceScanner.stripComments(
              McpSourceScanner.read("com/etendoerp/go/mcp/" + name));
          if (raw.matcher(src).find()) {
            offenders.add(name);
          }
        }
      }
      assertTrue(offenders.isEmpty(), "raw ETGO_SF_ENTITY write-flag reads in " + offenders
          + "; ask McpMethodPolicy so a verb MCP_CONFIG.verbs hides is hidden there too");
    }
  }
}
