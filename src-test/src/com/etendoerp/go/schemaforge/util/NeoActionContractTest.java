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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoActionContract} (ETP-5468): the declaration a handler publishes for its
 * named actions — rendered by {@code neo_schema}, listed by {@code neo_discover} and used to judge
 * every {@code neo_action} call before the handler runs.
 *
 * <p>Pure: only {@link NeoActionContract#resolve} touches the DAL, and it is driven with a static
 * {@link OBDal} mock and a mocked {@link NeoHandlerLookup}.</p>
 */
@SuppressWarnings("java:S2187")
@DisplayName("NeoActionContract (ETP-5468)")
class NeoActionContractTest {

  private static final int UNPROCESSABLE = 422;

  /** A small catalog with one of every parameter shape. */
  private static Map<String, NeoActionContract> catalog() {
    Map<String, NeoActionContract> map = new LinkedHashMap<>();
    map.put("list", NeoActionContract.read("list", "Lists things.",
        NeoActionContract.Param.optional("dateFrom", NeoActionContract.TYPE_DATE, "From."),
        NeoActionContract.Param.options("kind", "Kind.", List.of("transactions", "invoices"))));
    map.put("apply", NeoActionContract.write("apply", "Applies things.",
        NeoActionContract.Param.required("lineId", NeoActionContract.TYPE_STRING, "Line."),
        NeoActionContract.Param.array("ids", NeoActionContract.TYPE_STRING, true, "Ids."),
        NeoActionContract.Param.array("groups", NeoActionContract.TYPE_OBJECT, false, "Groups."),
        NeoActionContract.Param.optional("flag", NeoActionContract.TYPE_BOOLEAN, "Flag.")));
    return map;
  }

  private static JSONObject errorOf(NeoResponse response) throws Exception {
    assertNotNull(response, "the call must be refused");
    assertEquals(UNPROCESSABLE, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(UNPROCESSABLE, error.getInt("status"));
    return error;
  }

  private static List<String> strings(JSONArray arr) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      out.add(arr.getString(i));
    }
    return out;
  }

  private static JSONObject validApplyBody() throws Exception {
    return new JSONObject().put("lineId", "L1").put("ids", new JSONArray().put("T1"));
  }

  // ── rendering ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("toJson — the neo_schema rendering")
  class Rendering {

    @Test
    @DisplayName("renders action, description, mutating flag and invokeVia")
    void rendersHeader() throws Exception {
      JSONObject json = catalog().get("apply").toJson();
      assertEquals("apply", json.getString("action"));
      assertEquals("Applies things.", json.getString("description"));
      assertTrue(json.getBoolean("mutating"));
      assertEquals("neo_action", json.getString("invokeVia"));
      assertFalse(catalog().get("list").toJson().getBoolean("mutating"));
    }

    @Test
    @DisplayName("parameters is a closed JSON Schema object with the required list in order")
    void rendersClosedObjectSchema() throws Exception {
      JSONObject schema = catalog().get("apply").toJson().getJSONObject("parameters");
      assertEquals("object", schema.getString("type"));
      assertFalse(schema.getBoolean("additionalProperties"));
      assertEquals(List.of("lineId", "ids"), strings(schema.getJSONArray("required")));
      JSONObject props = schema.getJSONObject("properties");
      assertEquals(4, props.length());
      assertEquals("string", props.getJSONObject("lineId").getString("type"));
      assertEquals("Line.", props.getJSONObject("lineId").getString("description"));
      assertEquals("boolean", props.getJSONObject("flag").getString("type"));
    }

    @Test
    @DisplayName("a date renders as string + format:date")
    void rendersDateAsFormattedString() throws Exception {
      JSONObject dateFrom = catalog().get("list").toJson().getJSONObject("parameters")
          .getJSONObject("properties").getJSONObject("dateFrom");
      assertEquals("string", dateFrom.getString("type"));
      assertEquals("date", dateFrom.getString("format"));
    }

    @Test
    @DisplayName("an array renders its item type; an options param renders its enum")
    void rendersArrayItemsAndEnum() throws Exception {
      JSONObject listProps = catalog().get("list").toJson().getJSONObject("parameters")
          .getJSONObject("properties");
      assertEquals(List.of("transactions", "invoices"),
          strings(listProps.getJSONObject("kind").getJSONArray("enum")));
      JSONObject applyProps = catalog().get("apply").toJson().getJSONObject("parameters")
          .getJSONObject("properties");
      assertEquals("array", applyProps.getJSONObject("ids").getString("type"));
      assertEquals("string",
          applyProps.getJSONObject("ids").getJSONObject("items").getString("type"));
      assertEquals("object",
          applyProps.getJSONObject("groups").getJSONObject("items").getString("type"));
      assertFalse(applyProps.getJSONObject("ids").has("enum"));
    }

    @Test
    @DisplayName("an action without parameters renders an empty required list")
    void rendersNoParams() throws Exception {
      JSONObject schema = NeoActionContract.read("preview", "Preview.").toJson()
          .getJSONObject("parameters");
      assertEquals(0, schema.getJSONArray("required").length());
      assertEquals(0, schema.getJSONObject("properties").length());
    }

    @Test
    @DisplayName("idDescription is emitted only when declared (withIdDescription)")
    void idDescriptionOnlyWhenSet() throws Exception {
      NeoActionContract plain = catalog().get("apply");
      assertNull(plain.getIdDescription());
      assertFalse(plain.toJson().has("idDescription"));

      NeoActionContract described = plain.withIdDescription("The financial account id.");
      assertEquals("The financial account id.", described.getIdDescription());
      assertEquals("The financial account id.", described.toJson().getString("idDescription"));
      assertNull(plain.getIdDescription(), "withIdDescription returns a copy");
    }

    @Test
    @DisplayName("withIdDescription keeps name, description, mutating flag and parameters")
    void withIdDescriptionPreservesContract() throws Exception {
      NeoActionContract plain = catalog().get("apply");
      NeoActionContract described = plain.withIdDescription("id");
      assertNotSame(plain, described);
      assertEquals(plain.getName(), described.getName());
      assertEquals(plain.getDescription(), described.getDescription());
      assertEquals(plain.isMutating(), described.isMutating());
      assertEquals(plain.getParams(), described.getParams());
      assertEquals(plain.toJson().getJSONObject("parameters").toString(),
          described.toJson().getJSONObject("parameters").toString());
    }

    @Test
    @DisplayName("getters expose the declaration")
    void getters() {
      NeoActionContract apply = catalog().get("apply");
      assertEquals("apply", apply.getName());
      assertTrue(apply.isMutating());
      assertEquals(4, apply.getParams().size());
      NeoActionContract.Param first = apply.getParams().get(0);
      assertEquals("lineId", first.getName());
      assertEquals(NeoActionContract.TYPE_STRING, first.getType());
      assertTrue(first.isRequired());
      assertFalse(apply.getParams().get(3).isRequired());
    }
  }

  // ── validation ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("validate — judging a call")
  class Validation {

    @Test
    @DisplayName("a call that matches the contract passes (null)")
    void validCallPasses() throws Exception {
      assertNull(NeoActionContract.validate(catalog(), "apply", validApplyBody()));
      assertNull(NeoActionContract.validate(catalog(), "list", null),
          "no parameters at all is fine when none is required");
      assertNull(NeoActionContract.validate(catalog(), "list",
          new JSONObject().put("dateFrom", "2026-01-31").put("kind", "invoices")));
    }

    @Test
    @DisplayName("an unknown action → 422 with availableActions")
    void unknownAction() throws Exception {
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "nope", null));
      assertEquals(List.of("list", "apply"), strings(error.getJSONArray("availableActions")));
      assertTrue(error.getString("message").contains("Unknown action 'nope'"));
    }

    @Test
    @DisplayName("a null action → 422 with availableActions")
    void nullAction() throws Exception {
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), null, null));
      assertEquals(2, error.getJSONArray("availableActions").length());
    }

    @Test
    @DisplayName("an undeclared parameter → 422 unknownParameters (sorted) + acceptedParameters")
    void unknownParameter() throws Exception {
      JSONObject body = validApplyBody().put("zeta", "x").put("amount", "10");
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "apply", body));
      assertEquals(List.of("amount", "zeta"), strings(error.getJSONArray("unknownParameters")));
      assertEquals(List.of("lineId", "ids", "groups", "flag"),
          strings(error.getJSONArray("acceptedParameters")));
      assertFalse(error.has("missingParameters"));
    }

    @Test
    @DisplayName("unknown parameters are reported before missing ones")
    void unknownBeforeMissing() throws Exception {
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "apply",
          new JSONObject().put("typo", "x")));
      assertTrue(error.has("unknownParameters"));
      assertFalse(error.has("missingParameters"));
    }

    @Test
    @DisplayName("absent required parameters → 422 missingParameters in declaration order")
    void missingParameters() throws Exception {
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "apply", new JSONObject()));
      assertEquals(List.of("lineId", "ids"), strings(error.getJSONArray("missingParameters")));
    }

    @Test
    @DisplayName("null, blank string and empty array count as missing")
    void blankNullAndEmptyAreMissing() throws Exception {
      JSONObject body = new JSONObject().put("lineId", "   ").put("ids", new JSONArray());
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "apply", body));
      assertEquals(List.of("lineId", "ids"), strings(error.getJSONArray("missingParameters")));

      JSONObject nulls = new JSONObject().put("lineId", JSONObject.NULL)
          .put("ids", new JSONArray().put("T1"));
      JSONObject nullError = errorOf(NeoActionContract.validate(catalog(), "apply", nulls));
      assertEquals(List.of("lineId"), strings(nullError.getJSONArray("missingParameters")));
    }

    @Test
    @DisplayName("a malformed date → 422 field + expectedType date (yyyy-MM-dd)")
    void badDate() throws Exception {
      for (Object bad : List.of("2026/01/31", "31-01-2026", "2026-1-5", 20260131)) {
        JSONObject error = errorOf(NeoActionContract.validate(catalog(), "list",
            new JSONObject().put("dateFrom", bad)));
        assertEquals("dateFrom", error.getString("field"), "value " + bad);
        assertEquals("date (yyyy-MM-dd)", error.getString("expectedType"));
      }
    }

    @Test
    @DisplayName("a value outside the enum → 422 field + allowedValues")
    void badEnum() throws Exception {
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "list",
          new JSONObject().put("kind", "payments")));
      assertEquals("kind", error.getString("field"));
      assertEquals(List.of("transactions", "invoices"),
          strings(error.getJSONArray("allowedValues")));
    }

    @Test
    @DisplayName("a non-string enum value → 422 expectedType string")
    void nonStringEnum() throws Exception {
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "list",
          new JSONObject().put("kind", 3)));
      assertEquals("string", error.getString("expectedType"));
    }

    @Test
    @DisplayName("a non-string (or blank) array item → 422 expectedType array")
    void nonStringArrayItem() throws Exception {
      for (JSONArray bad : List.of(new JSONArray().put("T1").put(7),
          new JSONArray().put("T1").put(" "), new JSONArray().put(new JSONObject()))) {
        JSONObject body = new JSONObject().put("lineId", "L1").put("ids", bad);
        JSONObject error = errorOf(NeoActionContract.validate(catalog(), "apply", body));
        assertEquals("ids", error.getString("field"), "items " + bad);
        assertEquals("array", error.getString("expectedType"));
      }
    }

    @Test
    @DisplayName("an array param given a scalar → 422")
    void arrayGivenScalar() throws Exception {
      JSONObject body = new JSONObject().put("lineId", "L1").put("ids", "T1");
      assertEquals("ids", errorOf(NeoActionContract.validate(catalog(), "apply", body))
          .getString("field"));
    }

    @Test
    @DisplayName("an object-array param rejects string items and accepts objects")
    void objectArray() throws Exception {
      JSONObject bad = validApplyBody().put("groups", new JSONArray().put("g1"));
      assertEquals("groups", errorOf(NeoActionContract.validate(catalog(), "apply", bad))
          .getString("field"));
      JSONObject ok = validApplyBody().put("groups",
          new JSONArray().put(new JSONObject().put("statementLineId", "L1")));
      assertNull(NeoActionContract.validate(catalog(), "apply", ok));
    }

    @Test
    @DisplayName("a boolean param rejects the string \"true\"")
    void booleanRejectsString() throws Exception {
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "apply",
          validApplyBody().put("flag", "true")));
      assertEquals("flag", error.getString("field"));
      assertEquals("boolean", error.getString("expectedType"));
      assertNull(NeoActionContract.validate(catalog(), "apply", validApplyBody().put("flag", true)));
    }

    @Test
    @DisplayName("a scalar string param rejects a number")
    void stringRejectsNumber() throws Exception {
      JSONObject body = new JSONObject().put("lineId", 12).put("ids", new JSONArray().put("T1"));
      JSONObject error = errorOf(NeoActionContract.validate(catalog(), "apply", body));
      assertEquals("lineId", error.getString("field"));
      assertEquals("string", error.getString("expectedType"));
    }

    @Test
    @DisplayName("an explicit null on an optional parameter is not type-checked")
    void nullOptionalIsSkipped() throws Exception {
      assertNull(NeoActionContract.validate(catalog(), "list",
          new JSONObject().put("dateFrom", JSONObject.NULL)));
    }
  }

  // ── resolve ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("resolve — which entity of a spec serves declared actions")
  class Resolve {

    private final Map<String, NeoActionContract> contracts = catalog();

    private NeoHandler declaring(Map<String, NeoActionContract> declared) {
      return new NeoHandler() {
        @Override
        public NeoResponse handle(NeoContext context) {
          return null;
        }

        @Override
        public Map<String, NeoActionContract> actionContracts() {
          return declared;
        }
      };
    }

    private SFEntity entity(String name, String qualifier) {
      SFEntity e = mock(SFEntity.class);
      when(e.getName()).thenReturn(name);
      when(e.getJavaQualifier()).thenReturn(qualifier);
      return e;
    }

    /** The restrictions {@code resolve} added to its criteria in the last {@link #resolveWith}. */
    private final List<String> addedRestrictions = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private Optional<NeoActionContract.SpecActions> resolveWith(List<SFEntity> entities,
        Map<String, NeoHandler> handlers) {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-1");
      when(spec.getName()).thenReturn("bank-reconciliation");
      OBDal dal = mock(OBDal.class);
      OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
      addedRestrictions.clear();
      when(criteria.add(any())).thenAnswer(inv -> {
        addedRestrictions.add(String.valueOf(inv.<Object>getArgument(0)));
        return criteria;
      });
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(entities);
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
        obDal.when(OBDal::getInstance).thenReturn(dal);
        lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(anyString()))
            .thenAnswer(inv -> handlers.get(inv.<String>getArgument(0)));
        return NeoActionContract.resolve(spec);
      }
    }

    @Test
    @DisplayName("a null spec resolves to empty")
    void nullSpec() {
      assertFalse(NeoActionContract.resolve(null).isPresent());
    }

    @Test
    @DisplayName("the first included entity whose handler declares actions wins")
    void firstDeclaringEntity() {
      Optional<NeoActionContract.SpecActions> out = resolveWith(
          List.of(entity("plain", "plain-q"), entity("bank-reconciliation", "rec-q")),
          Map.of("plain-q", declaring(Collections.emptyMap()), "rec-q", declaring(contracts)));
      assertTrue(out.isPresent());
      assertEquals("bank-reconciliation", out.get().getEntityName());
      assertSame(contracts, out.get().getContracts());
    }

    @Test
    @DisplayName("only ACTIVE, INCLUDED entities of the spec are considered")
    void onlyActiveIncludedEntities() {
      resolveWith(List.of(entity("bank-reconciliation", "rec-q")),
          Map.of("rec-q", declaring(contracts)));
      assertTrue(addedRestrictions.contains(
          Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true).toString()),
          "inactive entities must be filtered out: " + addedRestrictions);
      assertTrue(addedRestrictions.contains(
          Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true).toString()), addedRestrictions
          .toString());
      assertTrue(addedRestrictions.contains(
          Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", "spec-1").toString()),
          addedRestrictions.toString());
    }

    @Test
    @DisplayName("no handler, or handlers without declarations → empty")
    void noDeclaration() {
      assertFalse(resolveWith(List.of(entity("a", "missing"), entity("b", "plain-q")),
          Map.of("plain-q", declaring(Collections.emptyMap()))).isPresent());
      assertFalse(resolveWith(Collections.emptyList(), Map.of()).isPresent());
    }

    @Test
    @DisplayName("a DAL failure degrades to empty instead of propagating")
    void dalFailureIsQuiet() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-1");
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        obDal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no session"));
        assertFalse(NeoActionContract.resolve(spec).isPresent());
      }
    }
  }
}
