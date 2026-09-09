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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link DocumentSequenceHandler} — the invoice-series prefix rules the Spanish
 * fiscal localizations impose, applied on write instead of at invoice completion (ETP-5190).
 *
 * <p>Split in two: {@code validateSpanishPrefix} is pure and gets the rule matrix, while
 * {@link #handle(NeoContext)}'s guards are covered only up to the point where they stop needing
 * a DAL — everything past the country lookup belongs to an integration test.
 */
class DocumentSequenceHandlerTest {

  private final DocumentSequenceHandler handler = new DocumentSequenceHandler();

  @Nested
  @DisplayName("validateSpanishPrefix — the four ProcessInvoiceTbaiHook rules")
  class ValidateSpanishPrefix {

    @ParameterizedTest
    @ValueSource(strings = { "FV", "FV-", "A", "FACT-2026-", "0", "999", "A-B-C",
        "ABCDEFGHJKLMNPQRST" })
    @DisplayName("accepts uppercase letters, digits and hyphens")
    void acceptsValidPrefixes(String prefix) {
      assertNull(DocumentSequenceHandler.validateSpanishPrefix(prefix));
    }

    @Test
    @DisplayName("accepts a prefix of exactly the maximum length")
    void acceptsPrefixAtMaxLength() {
      // The rule is `length() > 20`, so 20 itself must pass — the off-by-one that would make
      // a legal 20-character series unsavable.
      String twenty = "A".repeat(DocumentSequenceHandler.MAX_PREFIX_LENGTH);
      assertEquals(20, twenty.length());
      assertNull(DocumentSequenceHandler.validateSpanishPrefix(twenty));
    }

    @Test
    @DisplayName("rejects a prefix one character over the maximum")
    void rejectsTooLongPrefix() {
      String twentyOne = "A".repeat(DocumentSequenceHandler.MAX_PREFIX_LENGTH + 1);
      assertEquals(DocumentSequenceHandler.ERR_PREFIX_TOO_LONG,
          DocumentSequenceHandler.validateSpanishPrefix(twentyOne));
    }

    @ParameterizedTest
    @ValueSource(strings = { "fv", "Fv-", "añO", "FV-á", "FV-ü" })
    @DisplayName("rejects lowercase and accented letters")
    void rejectsLowercaseOrAccented(String prefix) {
      assertEquals(DocumentSequenceHandler.ERR_PREFIX_LOWER_OR_ACCENT,
          DocumentSequenceHandler.validateSpanishPrefix(prefix));
    }

    @ParameterizedTest
    @ValueSource(strings = { "I", "O", "Y", "W", "Ñ", "FI-", "FO-", "AÑO" })
    @DisplayName("rejects the letters the localizations reserve")
    void rejectsForbiddenLetters(String prefix) {
      assertEquals(DocumentSequenceHandler.ERR_PREFIX_FORBIDDEN_LETTERS,
          DocumentSequenceHandler.validateSpanishPrefix(prefix));
    }

    @ParameterizedTest
    @ValueSource(strings = { "FV_", "FV/", "FV.", "FV ", "FV#", "FV+", "F*V", "FACTURÉ", "AÁ" })
    @DisplayName("rejects any character outside A-Z, 0-9 and the hyphen")
    void rejectsInvalidCharacters(String prefix) {
      // Note the last two: an UPPERCASE accented letter is not matched by the accent rule
      // (`[a-záéíóúüñ]` is lowercase-only) and lands here instead. Same verdict either way, but
      // a different message — and this is exactly what classic does, which is the point.
      assertEquals(DocumentSequenceHandler.ERR_PREFIX_INVALID_CHARS,
          DocumentSequenceHandler.validateSpanishPrefix(prefix));
    }

    @Test
    @DisplayName("reports the rules in the hook's own order")
    void reportsRulesInHookOrder() {
      // A prefix that breaks several rules at once must report the same one classic would, so
      // the two never contradict each other on the same value: length first, then case, then
      // the reserved letters, then the character set.
      assertEquals(DocumentSequenceHandler.ERR_PREFIX_TOO_LONG,
          DocumentSequenceHandler.validateSpanishPrefix("i_".repeat(11)));
      assertEquals(DocumentSequenceHandler.ERR_PREFIX_LOWER_OR_ACCENT,
          DocumentSequenceHandler.validateSpanishPrefix("i_"));
      assertEquals(DocumentSequenceHandler.ERR_PREFIX_FORBIDDEN_LETTERS,
          DocumentSequenceHandler.validateSpanishPrefix("I_"));
    }
  }

  @Nested
  @DisplayName("handle — what never reaches the country lookup")
  class HandleGuards {

    @Test
    @DisplayName("ignores a null context")
    void ignoresNullContext() {
      assertNull(handler.handle(null));
    }

    @Test
    @DisplayName("ignores every endpoint type but CRUD")
    void ignoresNonCrudEndpoints() throws JSONException {
      for (NeoEndpointType type : NeoEndpointType.values()) {
        if (type == NeoEndpointType.CRUD) {
          continue;
        }
        assertNull(handler.handle(context(type, "POST", body("FV-"))),
            "endpoint type " + type);
      }
    }

    @ParameterizedTest
    @ValueSource(strings = { "GET", "DELETE", "OPTIONS", "HEAD" })
    @DisplayName("ignores methods that do not write")
    void ignoresReadMethods(String method) throws JSONException {
      // A GET that happened to carry a body must not be rejected for its contents.
      assertNull(handler.handle(context(NeoEndpointType.CRUD, method, body("fv"))));
    }

    @Test
    @DisplayName("ignores a write with no body at all")
    void ignoresMissingBody() {
      assertNull(handler.handle(context(NeoEndpointType.CRUD, "POST", null)));
    }

    @Test
    @DisplayName("ignores a write that does not touch the prefix")
    void ignoresBodyWithoutPrefix() throws JSONException {
      // A PUT that only renames the sequence must not be re-validated against a prefix it is
      // not sending — otherwise an existing illegal prefix would block every later edit.
      JSONObject other = new JSONObject().put("name", "Sales invoices");
      assertNull(handler.handle(context(NeoEndpointType.CRUD, "PUT", other)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    @DisplayName("accepts clearing the prefix — the rules constrain its content, not its presence")
    void acceptsBlankPrefix(String prefix) throws JSONException {
      assertNull(handler.handle(context(NeoEndpointType.CRUD, "POST", body(prefix))));
    }

    @Test
    @DisplayName("accepts an explicit JSON null prefix")
    void acceptsJsonNullPrefix() throws JSONException {
      JSONObject cleared = new JSONObject().put(DocumentSequenceHandler.FIELD_PREFIX,
          JSONObject.NULL);
      assertNull(handler.handle(context(NeoEndpointType.CRUD, "POST", cleared)));
    }

    @Test
    @DisplayName("fails open with no OBContext to resolve an organization from")
    void failsOpenWithNoObContext() throws JSONException {
      // The context carries no OBContext, so there is no organization and therefore no country.
      // An illegal prefix must still be ACCEPTED: refusing a save because the tenant's country
      // could not be established would break the onboarding step this validation serves — and
      // would refuse every non-Spanish tenant outright.
      assertNull(handler.handle(context(NeoEndpointType.CRUD, "POST", body("fv_"))));
    }
  }

  @Nested
  @DisplayName("CDI wiring")
  class CdiWiring {

    @Test
    @DisplayName("carries @Named matching the spec's Java_Qualifier and no bean scope")
    void isNamedWithNoScope() {
      // REGRESSION GUARD (ETP-4244 precedent, documented in neo-headless-extensibility.md
      // §2.2): lookupHandler() reads @Named off handler.getClass(). Adding a normal scope such
      // as @ApplicationScoped makes Weld hand back a client proxy whose subclass does not carry
      // the (non-@Inherited) annotation, and the handler silently stops being discovered — no
      // error, the prefix rules just quietly stop applying.
      Named named = DocumentSequenceHandler.class.getAnnotation(Named.class);
      assertEquals("document-sequence", named == null ? null : named.value());
      for (java.lang.annotation.Annotation annotation
          : DocumentSequenceHandler.class.getAnnotations()) {
        String name = annotation.annotationType().getName();
        assertEquals(false, name.startsWith("javax.enterprise.context.")
            || name.startsWith("jakarta.enterprise.context."),
            "must not declare a CDI scope, found " + name);
      }
    }
  }

  private static JSONObject body(String prefix) throws JSONException {
    return new JSONObject().put(DocumentSequenceHandler.FIELD_PREFIX, prefix);
  }

  /**
   * A real {@link NeoContext} carrying only what the guards read. `NeoContext`'s constructor is
   * private, so this goes through its builder rather than a subclass. `obContext` is left unset
   * on purpose — see {@code failsOpenWithNoObContext}.
   */
  private static NeoContext context(NeoEndpointType endpointType, String httpMethod,
      JSONObject requestBody) {
    return listContext(endpointType, httpMethod, requestBody, new HashMap<>(), null);
  }

  private static NeoContext listContext(NeoEndpointType endpointType, String httpMethod,
      JSONObject requestBody, Map<String, String> queryParams, String recordId) {
    return NeoContext.builder()
        .specName("document-sequence")
        .entityName("sequence")
        .endpointType(endpointType)
        .httpMethod(httpMethod)
        .requestBody(requestBody)
        .queryParams(queryParams)
        .recordId(recordId)
        .build();
  }

  /** Compile-time guard that the rejection shape stays a 400 the frontend can map. */
  @Test
  @DisplayName("rejection messages are the exact strings backendErrors.js maps")
  void rejectionMessagesAreStable() {
    // These literals are the KEY in the frontend's BACKEND_ERROR_MAP; changing one here without
    // changing it there silently reverts the message to untranslated English.
    assertEquals("The prefix cannot be longer than 20 characters.",
        DocumentSequenceHandler.ERR_PREFIX_TOO_LONG);
    assertEquals("The prefix cannot contain lowercase or accented letters.",
        DocumentSequenceHandler.ERR_PREFIX_LOWER_OR_ACCENT);
    assertEquals("The prefix cannot contain the letters I, O, Y, W or Ñ.",
        DocumentSequenceHandler.ERR_PREFIX_FORBIDDEN_LETTERS);
    assertEquals("The prefix can only contain uppercase letters, digits and hyphens.",
        DocumentSequenceHandler.ERR_PREFIX_INVALID_CHARS);
    assertEquals(400, NeoResponse.error(400, "x").getHttpStatus());
  }

  @Nested
  @DisplayName("applyListScope — the list is narrowed to what a user came here for")
  class ListScope {

    /** The criteria the handler injected, as clauses keyed by fieldName. */
    private Map<String, JSONObject> injectedClauses(Map<String, String> params)
        throws JSONException {
      JSONArray criteria = new JSONArray(params.get("criteria"));
      Map<String, JSONObject> byField = new HashMap<>();
      for (int i = 0; i < criteria.length(); i++) {
        JSONObject clause = criteria.getJSONObject(i);
        byField.put(clause.optString("fieldName"), clause);
      }
      return byField;
    }

    @Test
    @DisplayName("narrows a list GET to the eleven sequence names, as an inSet clause")
    void narrowsListByName() throws JSONException {
      // A provisioned tenant has 242 sequences; all but these are record-ID and internal
      // counters. Injected as criteria rather than filtered out of the response so the
      // narrowed query is the one that runs — see the method's javadoc on paging.
      Map<String, String> params = new HashMap<>();
      assertNull(handler.handle(listContext(NeoEndpointType.CRUD, "GET", null, params, null)));

      JSONObject nameClause = injectedClauses(params).get("name");
      assertEquals("inSet", nameClause.getString("operator"));
      JSONArray names = nameClause.getJSONArray("value");
      assertEquals(DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES.size(), names.length());
      for (int i = 0; i < names.length(); i++) {
        assertTrue(DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES.contains(names.getString(i)),
            names.getString(i) + " is not in the allowlist");
      }
    }

    @Test
    @DisplayName("carries the exact eleven names the product asked for")
    void allowlistContentIsTheAgreedList() {
      // Verified against the instance: every one of these exists, by this exact name, in a
      // provisioned client. Renaming or dropping one is a product change, not a refactor.
      assertEquals(java.util.List.of(
          "AR Invoice", "AP Payment", "AR Receipt", "MM Shipment", "Standard Order",
          "Purchase Order", "DocumentNo_C_Invoice", "Secuencia TICKETBAI",
          "DocumentNo_M_InOut", "DocumentNo_M_Movement", "DocumentNo_A_Asset"),
          DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES);
    }

    @Test
    @DisplayName("keeps a filter the user already applied, narrowing rather than replacing it")
    void appendsToExistingCriteria() throws JSONException {
      // Top-level criteria clauses are ANDed, so appending is enough — and it must be an
      // append: replacing would silently drop the user's own list filter.
      JSONObject userClause = new JSONObject()
          .put("fieldName", "prefix").put("operator", "iContains").put("value", "FV");
      Map<String, String> params = new HashMap<>();
      params.put("criteria", new JSONArray().put(userClause).toString());

      assertNull(handler.handle(listContext(NeoEndpointType.CRUD, "GET", null, params, null)));

      Map<String, JSONObject> clauses = injectedClauses(params);
      assertEquals("FV", clauses.get("prefix").getString("value"));
      assertEquals("inSet", clauses.get("name").getString("operator"));
    }

    @Test
    @DisplayName("leaves a GET by id alone")
    void doesNotScopeGetById() {
      // These are the tenant's own records and the point is a shorter list, not access
      // control, so a deep link into a sequence the list does not show still resolves.
      Map<String, String> params = new HashMap<>();
      assertNull(handler.handle(
          listContext(NeoEndpointType.CRUD, "GET", null, params, "SOME_SEQUENCE_ID")));
      assertNull(params.get("criteria"));
    }

    @Test
    @DisplayName("does not touch the criteria on a write")
    void doesNotScopeWrites() throws JSONException {
      Map<String, String> params = new HashMap<>();
      handler.handle(listContext(NeoEndpointType.CRUD, "POST", body("FV-"), params, null));
      assertNull(params.get("criteria"));
    }

    @Test
    @DisplayName("replaces a criteria value it cannot parse instead of failing the request")
    void survivesUnparseableCriteria() throws JSONException {
      // A cluttered list is a far better failure than a 500 on a window that just loaded, and
      // NeoCrudHandler treats an unparseable criteria the same way.
      Map<String, String> params = new HashMap<>();
      params.put("criteria", "not json at all");

      assertNull(handler.handle(listContext(NeoEndpointType.CRUD, "GET", null, params, null)));
      assertEquals("inSet", injectedClauses(params).get("name").getString("operator"));
    }

    @Test
    @DisplayName("tolerates a context with no query params to write into")
    void toleratesMissingQueryParams() {
      NeoContext context = listContext(NeoEndpointType.CRUD, "GET", null, null, null);
      assertNull(handler.handle(context));
    }
  }
}
