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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link DocumentSequenceHandler} — the invoice-series prefix rules the Spanish
 * fiscal localizations impose, applied on write instead of at invoice completion (ETP-5190).
 *
 * <p>Split in two: {@code validateSpanishPrefix} is pure and gets the rule matrix, while
 * {@link #handle(NeoContext)}'s guards are covered only up to the point where they stop needing
 * a DAL — everything past the country lookup belongs to an integration test. The spec's scope
 * (ETP-5837) is covered with the DAL and the country lookup mocked: {@code readPredicates} for
 * the list, and the by-id refusal in {@code handle} for reads and writes of a single row.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.DocumentSequenceHandler
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
    @DisplayName("is bound by @NeoExtension, with no @Named and no bean scope")
    void isBoundByAnnotationWithNoScope() {
      // ETP-5837 migrated this pair off the legacy Java_Qualifier/@Named binding. A normal scope
      // such as @ApplicationScoped would still hide the class behind a Weld client proxy, so it
      // stays a @Dependent bean.
      NeoExtension binding = DocumentSequenceHandler.class.getAnnotation(NeoExtension.class);
      assertNotNull(binding);
      assertEquals("document-sequence", binding.spec());
      assertEquals("sequence", binding.entity());
      assertNull(DocumentSequenceHandler.class.getAnnotation(Named.class));
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

  private static final String OWN_CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String OTHER_CLIENT_ID = "23C59575B9CF467C9620760EB255B389";

  /** A CRUD context carrying a session, as every real channel builds it. */
  private static NeoContext sessionContext(String httpMethod, JSONObject requestBody,
      String recordId, OBContext obContext) {
    return NeoContext.builder()
        .specName("document-sequence")
        .entityName("sequence")
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(httpMethod)
        .requestBody(requestBody)
        .queryParams(new HashMap<>())
        .recordId(recordId)
        .obContext(obContext)
        .build();
  }

  /** A session whose current client has the given id; {@code null} means no current client. */
  private static OBContext sessionOf(String clientId) {
    OBContext obContext = mock(OBContext.class);
    if (clientId != null) {
      Client client = clientWithId(clientId);
      when(obContext.getCurrentClient()).thenReturn(client);
    }
    return obContext;
  }

  private static Client clientWithId(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    return client;
  }

  private static Sequence sequenceOf(String name, String clientId) {
    Sequence sequence = mock(Sequence.class);
    when(sequence.getName()).thenReturn(name);
    Client client = clientWithId(clientId);
    when(sequence.getClient()).thenReturn(client);
    return sequence;
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
  @DisplayName("list scope — readPredicates, honoured by REST, batch and MCP list reads alike")
  class ListScope {

    @Test
    @DisplayName("declares a name predicate over exactly the seven visible sequences")
    void readPredicatesScopeByName() {
      // REPRO (ETP-5837): the scope used to be a `criteria` param injected from handle(), which
      // MCP etendo_list never reads — an agent listing this spec got every sequence of the
      // tenant. readPredicates is ANDed into the list query of every channel.
      List<String> predicates = handler.readPredicates(
          listContext(NeoEndpointType.CRUD, "GET", null, new HashMap<>(), null));
      String names = predicates.get(0);
      assertTrue(names.startsWith("e.name in ("), names);
      for (String name : DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES) {
        assertTrue(names.contains("'" + name + "'"), name + " missing from " + names);
      }
      assertFalse(names.contains("DocumentNo_"), names);
      // No OBContext: no client to scope by, so the name predicate is the only one.
      assertEquals(1, predicates.size(), predicates.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = { "4028E6C72959682B01295A070852010D", "1000000" })
    @DisplayName("scopes to the session client by its exact id, UUID or legacy numeric")
    void readPredicatesScopeToTheSessionClient(String clientId) {
      List<String> predicates = handler.readPredicates(
          sessionContext("GET", null, null, sessionOf(clientId)));

      assertEquals(2, predicates.size(), predicates.toString());
      assertTrue(predicates.get(0).startsWith("e.name in ("), predicates.get(0));
      assertEquals("e.client.id = '" + clientId + "'", predicates.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc", "' or '1'='1", "4028E6C72959682B01295A070852010G",
        "4028E6C72959682B01295A070852010D' --" })
    @DisplayName("fails closed with 1 = 0 when the session client id is not an AD id")
    void readPredicatesFailClosedOnMalformedClientId(String clientId) {
      // Dropping the clause would widen the scope to every readable client; splicing the value
      // would put session text into the HQL. Neither: the list is empty.
      List<String> predicates = handler.readPredicates(
          sessionContext("GET", null, null, sessionOf(clientId)));

      assertEquals(2, predicates.size(), predicates.toString());
      assertTrue(predicates.get(0).startsWith("e.name in ("), predicates.get(0));
      assertEquals("1 = 0", predicates.get(1));
      assertFalse(predicates.stream().anyMatch(p -> p.contains("e.client.id")),
          predicates.toString());
    }

    @Test
    @DisplayName("declares only the name predicate when the session has no current client")
    void readPredicatesWithoutSessionClientScopeByNameOnly() {
      List<String> predicates = handler.readPredicates(
          sessionContext("GET", null, null, sessionOf(null)));

      assertEquals(1, predicates.size(), predicates.toString());
      assertTrue(predicates.get(0).startsWith("e.name in ("), predicates.get(0));
    }

    @Test
    @DisplayName("carries the exact seven names the product asked for")
    void allowlistContentIsTheAgreedList() {
      // Verified against the instance: every one of these exists, by this exact name, in a
      // provisioned client. Renaming or dropping one is a product change, not a refactor.
      // ETP-5285 cut this from seven to five: AP Payment, AR Receipt, MM Shipment and
      // Secuencia TICKETBAI are not document series a tenant configures on this screen.
      // ETP-5364 added the sixth, "AP Invoice" (FC), together with the dataset pair that
      // creates that sequence and points the AP Invoice doctype at it.
      // ETP-5837 added the seventh, "Quotation" (PR), right after the sales order.
      assertEquals(java.util.List.of(
          "Purchase Order", "Standard Order", "Quotation", "AR Invoice",
          "Factura Rectificativa (Ventas)", "AP Invoice",
          "Factura Rectificativa (Compras)"),
          DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES);
    }

    @Test
    @DisplayName("holds no sequence ETP-5285 removed from the product's document series")
    void allowlistExcludesTheNamesEtp5285Dropped() {
      // REGRESSION GUARD. These four were visible before ETP-5285 and were dropped on purpose:
      // they are not document series. Re-adding one is a product change, so it must break here
      // first rather than quietly reappear in the list through an unrelated edit.
      for (String dropped : java.util.List.of(
          "AP Payment", "AR Receipt", "MM Shipment", "Secuencia TICKETBAI")) {
        assertFalse(DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES.contains(dropped),
            dropped + " is no longer a document series this window configures");
      }
    }

    @Test
    @DisplayName("holds the purchase-invoice series ETP-5364 gave a sequence to")
    void allowlistIncludesPurchaseInvoiceSeries() {
      // ETP-5285 asked for six series and could only ship five: "Factura de compra" (FC) had
      // no AD_Sequence to expose, because stock AP Invoice carries IsDocNoControlled='N' and
      // no sequence, so its number came from the supplier rather than from a series. ETP-5364
      // took that product decision — GOClient/AD_SEQUENCE.xml now ships an "AP Invoice"
      // sequence with prefix FC and GOClient/C_DOCTYPE.xml points the doctype at it with
      // IsDocNoControlled='Y'. This name is only useful WITH that dataset pair: dropping
      // either half turns the row into a prefix that governs nothing.
      assertTrue(DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES.contains("AP Invoice"),
          "AP Invoice is numbered by its own FC series since ETP-5364");
    }

    @Test
    @DisplayName("holds no DocumentNo_* fallback counter, which would be unsafe to edit")
    void allowlistExcludesTableLevelFallbacks() {
      // REGRESSION GUARD. A DocumentNo_* row is a table-level FALLBACK counter, shared by
      // every doctype that has no sequence of its own, so a prefix set here would configure a
      // series that is not authoritative for any single document — that alone disqualifies it.
      // Editing one is also unsafe on any tenant provisioned before ETP-5364, whose dataset
      // import re-inserted 96 of these names on top of the 97 InitialClientSetup already creates
      // (9888 surplus rows across 103 of 125 clients when measured). ad_sequence_doc
      // increments every row matching the name and reads one back with a non-STRICT SELECT
      // INTO, so the pair advances together and numbering works — until someone edits ONE of
      // them here, at which point the rows diverge and the value actually used becomes
      // non-deterministic. The import filter fixes new tenants; the existing ones are not.
      for (String name : DocumentSequenceHandler.VISIBLE_SEQUENCE_NAMES) {
        assertFalse(name.startsWith("DocumentNo_"),
            name + " is a duplicated table-level counter and must not be editable");
      }
    }

    @Test
    @DisplayName("a list GET reaches the default CRUD untouched")
    void listGetIsNotShortCircuited() {
      NeoContext context = listContext(NeoEndpointType.CRUD, "GET", null, null, null);
      assertNull(handler.handle(context));
    }
  }

  @Nested
  @DisplayName("by-id scope — a hidden sequence is not a record of this spec")
  class ByIdScope {

    @Test
    @DisplayName("refuses an update of a sequence outside the allowlist with a 404")
    void refusesUpdateOfHiddenSequence() throws JSONException {
      // REPRO (ETP-5837): an MCP agent could set the prefix of DocumentNo_C_Order, the fallback
      // counter shared by every doctype without a sequence of its own.
      Sequence hidden = mock(Sequence.class);
      when(hidden.getName()).thenReturn("DocumentNo_C_Order");
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(Sequence.class, "HIDDEN_ID")).thenReturn(hidden);

      NeoResponse response;
      try (MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> oc = Mockito.mockStatic(OBContext.class)) {
        od.when(OBDal::getInstance).thenReturn(obDal);
        response = handler.handle(listContext(NeoEndpointType.CRUD, "PUT", body("XX"),
            new HashMap<>(), "HIDDEN_ID"));
      }

      assertNotNull(response, "a hidden sequence must be refused, not updated");
      assertEquals(404, response.getHttpStatus());
    }

    /**
     * method, the sequence's name ({@code null}: the id does not exist), the sequence's client,
     * and whether the handler itself answers 404. Every other row returns {@code null}, handing
     * over to the default CRUD — which, for another client's row, answers the same generic
     * not-found as for a missing id.
     */
    static Stream<Arguments> byIdMatrix() {
      return Stream.of(
          Arguments.of("GET", "AR Invoice", OTHER_CLIENT_ID, false),
          Arguments.of("GET", "DocumentNo_C_Order", OTHER_CLIENT_ID, false),
          Arguments.of("PUT", "DocumentNo_C_Order", OTHER_CLIENT_ID, false),
          Arguments.of("GET", "DocumentNo_C_Order", OWN_CLIENT_ID, true),
          Arguments.of("PATCH", "DocumentNo_C_Order", OWN_CLIENT_ID, true),
          Arguments.of("DELETE", "DocumentNo_C_Order", OWN_CLIENT_ID, true),
          Arguments.of("GET", null, OWN_CLIENT_ID, false),
          Arguments.of("GET", "AR Invoice", OWN_CLIENT_ID, false));
    }

    @ParameterizedTest(name = "{0} {1} of client {2} -> own 404: {3}")
    @MethodSource("byIdMatrix")
    @DisplayName("answers 404 only for an own-client sequence outside the allowlist")
    void byIdScopeMatrix(String method, String sequenceName, String sequenceClientId,
        boolean expect404) {
      Sequence sequence = sequenceName == null ? null
          : sequenceOf(sequenceName, sequenceClientId);
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(Sequence.class, "SEQ_ID")).thenReturn(sequence);
      OBContext session = sessionOf(OWN_CLIENT_ID);

      NeoResponse response;
      try (MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> oc = Mockito.mockStatic(OBContext.class)) {
        od.when(OBDal::getInstance).thenReturn(obDal);
        response = handler.handle(sessionContext(method, null, "SEQ_ID", session));
        // Proves every row reached the lookup: a null below is the verdict, not a short-circuit.
        verify(obDal).get(Sequence.class, "SEQ_ID");
        oc.verify(OBContext::restorePreviousMode);
      }

      if (expect404) {
        assertNotNull(response, "an own hidden sequence must be refused");
        assertEquals(404, response.getHttpStatus());
        assertEquals(DocumentSequenceHandler.ERR_SEQUENCE_NOT_FOUND,
            response.getBody().optJSONObject("error").optString("message"));
      } else {
        assertNull(response, "must hand over to the default CRUD");
      }
    }

    static Stream<Arguments> ownVisiblePutPrefixes() {
      return Stream.of(
          Arguments.of("fv_", DocumentSequenceHandler.ERR_PREFIX_LOWER_OR_ACCENT),
          Arguments.of("FV-", null));
    }

    @ParameterizedTest(name = "prefix \"{0}\"")
    @MethodSource("ownVisiblePutPrefixes")
    @DisplayName("a PUT by id of an own visible sequence still runs the prefix rules")
    void putByIdOfOwnVisibleSequenceStillValidatesThePrefix(String prefix, String expectedError)
        throws JSONException {
      // The real UI save: PUT with the record id. The by-id check must let it through to the
      // prefix rules — an invalid prefix is a 400 with the rule's message, never the 404.
      Sequence visible = sequenceOf("AR Invoice", OWN_CLIENT_ID);
      OBDal obDal = mock(OBDal.class);
      when(obDal.get(Sequence.class, "SEQ_ID")).thenReturn(visible);
      Organization org = mock(Organization.class);
      OBContext session = sessionOf(OWN_CLIENT_ID);
      when(session.getCurrentOrganization()).thenReturn(org);

      NeoResponse response;
      try (MockedStatic<OBDal> od = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> oc = Mockito.mockStatic(OBContext.class);
          MockedStatic<OrganizationCountrySupport> country =
              Mockito.mockStatic(OrganizationCountrySupport.class)) {
        od.when(OBDal::getInstance).thenReturn(obDal);
        country.when(() -> OrganizationCountrySupport.isSpain(org)).thenReturn(true);
        response = handler.handle(sessionContext("PUT", body(prefix), "SEQ_ID", session));
        country.verify(() -> OrganizationCountrySupport.isSpain(org));
      }

      if (expectedError == null) {
        assertNull(response, "a valid prefix continues to the default CRUD");
      } else {
        assertNotNull(response);
        assertEquals(400, response.getHttpStatus());
        assertEquals(expectedError,
            response.getBody().optJSONObject("error").optString("message"));
      }
    }
  }
}
