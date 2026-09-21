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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * IMP-44 — {@code neo_schema}'s {@code view} is a decision the caller states, not one it inherits.
 *
 * <p>Omitting it used to land on the full field dump: 39.5 kB on {@code sales-order/header}
 * against 5.4 kB for {@code view:"create"}, with the advice to use the cheaper projection
 * delivered as a hint at the bottom of the response the caller had already paid for. That advice
 * has also been in the tool's own description since 2026-08-06 and three independent blind agents
 * still took the full route first, which is why the lever is the argument rather than more
 * prose.</p>
 *
 * <p>The same refusal covers an unrecognised value, and that half is the sharper one:
 * {@code view:"summary"} is a real view on {@code neo_list}/{@code neo_get}, so an agent
 * generalising across the tools would ask for the smallest projection and silently be handed the
 * largest response in the surface — no error, no warning, and a plausible-looking answer.</p>
 */
@SuppressWarnings("java:S2187") // test methods live in the @Nested classes below
@DisplayName("IMP-44 — neo_schema requires an explicit view")
class McpSchemaViewRequiredTest {

  /** The three projections, and the only three. */
  private static final List<String> VIEWS =
      List.of(McpSchemaCreateView.VIEW_CREATE, McpActionsView.VIEW_ACTIONS,
          McpSchemaCreateView.VIEW_FULL);

  @Nested
  @DisplayName("the refusal")
  class Refusal {

    @Test
    @DisplayName("an absent view answers 422 view_required")
    void absentViewIsRefused() throws Exception {
      for (String supplied : new String[] { null, "", "   " }) {
        JSONObject envelope = McpRoutingException.schemaViewRequired(supplied).toEnvelope();
        assertEquals(422, envelope.getInt(McpConstants.KEY_STATUS));
        assertEquals(McpConstants.ERROR_VIEW_REQUIRED,
            envelope.getString(McpConstants.KEY_ERROR));
        assertTrue(envelope.getString(McpConstants.KEY_DETAIL).contains("requires"),
            "an absent argument must read as missing, not as wrong");
      }
    }

    /**
     * The case that used to be silent. {@code "summary"} is a legitimate view elsewhere in the
     * surface, so it is exactly the value a generalising agent sends — and falling through to the
     * full dump answered a request for less with the biggest response in the tool.
     */
    @Test
    @DisplayName("an unrecognised view — summary, the real one from neo_list — is refused, not "
        + "ignored")
    void unknownViewIsRefused() throws Exception {
      JSONObject envelope = McpRoutingException.schemaViewRequired("summary").toEnvelope();

      assertEquals(422, envelope.getInt(McpConstants.KEY_STATUS));
      assertEquals(McpConstants.ERROR_VIEW_REQUIRED, envelope.getString(McpConstants.KEY_ERROR));
      assertTrue(envelope.getString(McpConstants.KEY_DETAIL).contains("summary"),
          "the caller must see which value was rejected, or it cannot tell this apart from the"
              + " missing-argument case");
    }

    @Test
    @DisplayName("the refusal names the argument and lists the three views that work")
    void theRefusalIsSelfCorrecting() throws Exception {
      JSONObject envelope = McpRoutingException.schemaViewRequired(null).toEnvelope();

      assertEquals(McpActionsView.PARAM_VIEW, envelope.getString(McpConstants.PARAM_FIELD));
      List<String> available = new ArrayList<>();
      for (int i = 0; i < envelope.getJSONArray(McpConstants.KEY_AVAILABLE).length(); i++) {
        available.add(envelope.getJSONArray(McpConstants.KEY_AVAILABLE).getString(i));
      }
      assertEquals(VIEWS.size(), available.size());
      assertTrue(available.containsAll(VIEWS), "available was " + available);

      String hint = envelope.getString(McpConstants.KEY_HINT);
      for (String view : VIEWS) {
        assertTrue(hint.contains(view), "the hint must say what each view is for: " + view);
      }
    }

    /**
     * A refusal that does not distinguish {@code view_required} from an ordinary validation error
     * sends the agent looking at its {@code fields} instead of adding one named argument.
     */
    @Test
    @DisplayName("view_required is its own code, not the generic validation one")
    void viewRequiredHasItsOwnCode() throws Exception {
      assertEquals("view_required", McpConstants.ERROR_VIEW_REQUIRED);
      assertFalse(McpConstants.ERROR_VALIDATION.equals(McpConstants.ERROR_VIEW_REQUIRED));
    }
  }

  @Nested
  @DisplayName("the three views dispatch, and nothing else does")
  class Dispatch {

    @Test
    @DisplayName("create/actions/full are each recognised, case-insensitively")
    void everyViewIsRecognised() {
      assertTrue(McpSchemaCreateView.isCreateView("create"));
      assertTrue(McpSchemaCreateView.isCreateView("Create"));
      assertTrue(McpActionsView.isActionsView("actions"));
      assertTrue(McpSchemaCreateView.isFullView("full"));
      assertTrue(McpSchemaCreateView.isFullView("FULL"));
    }

    /**
     * The predicate that replaced the fall-through. Before IMP-44 the full dump was reached by
     * <em>not</em> matching the other two, so every value on this list reached it.
     */
    @Test
    @DisplayName("no other value reaches the full dump — least of all summary or a blank")
    void nothingElseReachesTheFullDump() {
      for (String rejected : List.of("summary", "minimal", "grouped", "", "   ", "fields")) {
        assertFalse(McpSchemaCreateView.isFullView(rejected), rejected + " reaches the full dump");
        assertFalse(McpSchemaCreateView.isCreateView(rejected));
        assertFalse(McpActionsView.isActionsView(rejected));
      }
      assertFalse(McpSchemaCreateView.isFullView(null),
          "a null view is the omission IMP-44 exists to refuse; it must not reach the dump");
      assertTrue(McpFieldProjection.isSummaryView("summary"),
          "summary is a real view on neo_list/neo_get — that is precisely why an agent sends it"
              + " here, and why falling through to the full dump was the worst possible answer");
    }

    /**
     * The call site. {@code handleSchema} needs an {@code OBContext}, a live DAL and an
     * {@code AD_Tab}, so the guard cannot be asserted behaviourally — and a guard that is simply
     * deleted leaves every unit test of the views passing, which is the whole reason this package
     * reads source for call sites ({@code McpWriteVerbCoercionCallSiteTest} is the precedent).
     */
    @Test
    @DisplayName("handleSchema refuses before it builds the full response")
    void handleSchemaGuardsTheFullDump() {
      String body = McpSourceScanner.methodBody(
          McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleSchema");

      assertTrue(body.contains("isActionsView"), "view:\"actions\" no longer dispatches");
      assertTrue(body.contains("isCreateView"), "view:\"create\" no longer dispatches");
      assertTrue(body.contains("schemaViewRequired"),
          "handleSchema no longer refuses an absent or unrecognised view, so omitting the"
              + " argument silently buys the 39.5 kB full dump again");

      int guard = body.indexOf("schemaViewRequired");
      int projection = body.indexOf("parseFields");
      assertTrue(projection < 0 || guard < projection,
          "the refusal must come before the full dump is projected — after it, the caller has"
              + " already paid for the response the refusal exists to avoid");
    }
  }

  @Nested
  @DisplayName("tools/list declares it")
  class ToolDeclaration {

    private Map<String, Object> schemaToolInputSchema() throws Exception {
      Method build = ToolRegistry.class.getDeclaredMethod("buildSchemaTool", List.class);
      build.setAccessible(true);
      McpToolDefinition definition =
          (McpToolDefinition) build.invoke(new ToolRegistry(), List.of("sales-order"));
      return definition.getInputSchema();
    }

    /**
     * The declaration is what makes the refusal unnecessary: an MCP client that validates
     * {@code required} never sends the call at all. Leaving {@code view} optional in the schema
     * while refusing it at runtime would turn IMP-44 into a round trip the agent pays for.
     */
    @Test
    @DisplayName("required is [spec, entity, view]")
    void viewIsRequired() throws Exception {
      Object required = schemaToolInputSchema().get("required");
      assertTrue(required instanceof List, "required was " + required);
      List<?> names = (List<?>) required;
      assertEquals(List.of("spec", McpConstants.PARAM_ENTITY, McpActionsView.PARAM_VIEW), names,
          "a client that validates the schema must reject a view-less call before it is sent");
    }

    @Test
    @DisplayName("the view enum is exactly create/actions/full")
    void theEnumListsTheThreeViews() throws Exception {
      Object props = schemaToolInputSchema().get(McpConstants.KEY_PROPERTIES);
      assertTrue(props instanceof Map);
      Object viewProp = ((Map<?, ?>) props).get(McpActionsView.PARAM_VIEW);
      assertTrue(viewProp instanceof Map, "neo_schema declares no view property");
      Object values = ((Map<?, ?>) viewProp).get("enum");
      assertTrue(values instanceof List, "view was declared without an enum: " + values);
      List<?> enumValues = (List<?>) values;
      assertEquals(VIEWS.size(), enumValues.size(), "enum was " + enumValues);
      assertTrue(enumValues.containsAll(VIEWS), "enum was " + enumValues);
    }

    /**
     * {@code declaredArgumentNames} is what the IMP-40 unknown-argument guard reads, so a
     * {@code view} missing from it would make the tool's own argument unknown to the router.
     */
    @Test
    @DisplayName("the unknown-argument guard knows about view")
    void theArgumentGuardKnowsView() {
      assertTrue(ToolRegistry.declaredArgumentNames("neo_schema")
          .orElseThrow(() -> new AssertionError("neo_schema is not guarded at all"))
          .contains(McpActionsView.PARAM_VIEW));
    }

    /**
     * The description is the only thing a blind agent reads before choosing, and the measured
     * failure was agents defaulting to the dump. It has to say the argument is required and say
     * which value is the cheap one.
     */
    @Test
    @DisplayName("the description says view is required and points at create for writes")
    void theDescriptionCarriesTheAdvice() throws Exception {
      Method build = ToolRegistry.class.getDeclaredMethod("buildSchemaTool", List.class);
      build.setAccessible(true);
      McpToolDefinition definition =
          (McpToolDefinition) build.invoke(new ToolRegistry(), List.of("sales-order"));
      String description = definition.getDescription().toLowerCase(Locale.ROOT);

      assertTrue(description.contains("required"), "the description must say view is required");
      assertTrue(description.contains("view:\"create\""),
          "and must name the projection to prefer before a write");
    }
  }

  /**
   * {@code fields:[…]} still narrows, and it narrows <b>under {@code full} only</b> — the other
   * two views already define their own projection. The declared description has to say so, or an
   * agent will keep sending a whitelist with {@code view:"create"} and reading the absence of its
   * names as the entity not having them.
   */
  @Nested
  @DisplayName("fields:[…] under the full view")
  class FieldWhitelist {

    @Test
    @DisplayName("a whitelist narrows the full dump and reports the names it did not match")
    void parseFieldsStillNarrows() throws Exception {
      JSONArray declared = new JSONArray();
      declared.put(new JSONObject().put("name", "businessPartner"));
      declared.put(new JSONObject().put("name", "documentNo"));

      Set<String> requested =
          McpFieldProjection.parseFields(new JSONArray(List.of("businessPartner", "nosuch")));
      assertEquals(Set.of("businessPartner", "nosuch"), requested);

      JSONArray narrowed = McpSchemaCreateView.applyFieldWhitelist(declared, requested);
      assertEquals(1, narrowed.length(), "the whitelist must still narrow under view:\"full\"");
      assertEquals("businessPartner", narrowed.getJSONObject(0).getString("name"));

      JSONArray unknown = McpSchemaCreateView.unknownFields(declared, requested);
      assertEquals(1, unknown.length(), "an unmatched name must be echoed, never dropped");
      assertEquals("nosuch", unknown.getString(0));
    }

    @Test
    @DisplayName("the declared description scopes fields to view:\"full\"")
    void theDescriptionScopesFieldsToFull() throws Exception {
      Method build = ToolRegistry.class.getDeclaredMethod("buildSchemaTool", List.class);
      build.setAccessible(true);
      McpToolDefinition definition =
          (McpToolDefinition) build.invoke(new ToolRegistry(), List.of("sales-order"));
      Object props = definition.getInputSchema().get(McpConstants.KEY_PROPERTIES);
      Object fieldsProp = ((Map<?, ?>) props).get(McpSchemaCreateView.PARAM_FIELDS);
      String description =
          String.valueOf(((Map<?, ?>) fieldsProp).get(McpConstants.KEY_DESCRIPTION));

      assertTrue(description.contains(McpSchemaCreateView.VIEW_FULL),
          "fields applies to the full view only; saying nothing leaves an agent to discover it by"
              + " getting back a projection its whitelist never touched");
      assertTrue(description.contains("unknownFields"),
          "and an unmatched name must still be reported rather than dropped");
    }
  }
}
