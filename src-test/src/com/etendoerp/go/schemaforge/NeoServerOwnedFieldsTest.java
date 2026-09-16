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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * The tenant rule on the write paths — {@link NeoServerOwnedFields} and the two REST entry points
 * that call it.
 *
 * <h2>The defect this closes</h2>
 * <p>{@code neo_create} carrying an {@code organization} belonging to another org answered
 * {@code 200 OK}, and the record was then invisible to the session that created it — a
 * {@code 404} on the very id the response had just handed back, because the row had been written
 * into the other tenant. Neither {@code AD_Client_ID} nor {@code AD_Org_ID} has an
 * {@code ETGO_SF_FIELD} row, and the MCP write gate is built entirely out of those rows, so the
 * key matched neither deny-set, cleared both gates and reached {@code jsonService.add} with the
 * caller's value intact.</p>
 *
 * <h2>Why the REST side is tested even though it was never exploitable</h2>
 * <p>It was safe by accident. The REST filter is a whitelist, so an uncurated key falls out — a
 * property of nobody curating {@code AD_Org_ID}, not a decision, and one that evaporates entirely
 * when the filter is inactive and {@code filterBody} hands the body back untouched. That is why
 * {@code stripServerOwnedFields} sits <b>outside</b> the {@code active} guard, and it is the
 * reason the inactive-filter cases below are the important ones rather than a completeness
 * exercise.</p>
 */
@DisplayName("Tenant ownership on write — client/organization come from the session")
class NeoServerOwnedFieldsTest {

  private static final String SESSION_CLIENT = "CLIENT-SESSION";
  private static final String SESSION_ORG = "ORG-SESSION";
  private static final String OTHER_ORG = "ORG-SOMEONE-ELSE";

  private MockedStatic<OBContext> obContextMock;

  /**
   * A session in one client and one organization — the shape every authenticated Etendo GO call
   * has. Installed for every test so {@code sessionValue} has something to read; the
   * no-context case gets its own installation below.
   */
  @BeforeEach
  void installSession() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(SESSION_CLIENT);
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn(SESSION_ORG);

    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getCurrentOrganization()).thenReturn(organization);

    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(context);
  }

  @AfterEach
  void removeSession() {
    obContextMock.close();
  }

  /** Replace the installed session with none at all — a background thread, or no login. */
  private void withoutSession() {
    obContextMock.close();
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(null);
  }

  private static JSONObject body(String key, Object value) throws Exception {
    return new JSONObject().put(key, value);
  }

  // ── which keys the policy owns ────────────────────────────────────────

  @Nested
  @DisplayName("the spellings a caller can reach the tenant fields by")
  class Spellings {

    @Test
    @DisplayName("the two DAL property names, and nothing else")
    void theDalPropertyNames() {
      assertTrue(NeoServerOwnedFields.isServerOwned("client"));
      assertTrue(NeoServerOwnedFields.isServerOwned("organization"));
      assertFalse(NeoServerOwnedFields.isServerOwned("businessPartner"));
      assertFalse(NeoServerOwnedFields.isServerOwned((String) null));
      assertEquals(Set.of("client", "organization"),
          NeoServerOwnedFields.SERVER_OWNED_PROPERTIES);
    }

    @Test
    @DisplayName("a DAL Property is judged by its name, and a null one owns nothing")
    void theDalProperty() {
      Property organization = mock(Property.class);
      when(organization.getName()).thenReturn("organization");
      assertTrue(NeoServerOwnedFields.isServerOwned(organization));

      Property other = mock(Property.class);
      when(other.getName()).thenReturn("documentNo");
      assertFalse(NeoServerOwnedFields.isServerOwned(other));

      assertFalse(NeoServerOwnedFields.isServerOwned((Property) null));
    }

    /**
     * The REST path matches raw keys before any DAL resolution, so every spelling has to be
     * listed here rather than derived.
     */
    @ParameterizedTest
    @ValueSource(strings = { "client", "AD_Client_ID", "ad_client_id", "AD_CLIENT_ID",
        "organization", "AD_Org_ID", "ad_org_id", "AD_ORG_ID" })
    @DisplayName("every column and property spelling is recognised, case-insensitively")
    void everySpellingIsRecognised(String key) {
      assertTrue(NeoServerOwnedFields.isServerOwnedKey(key), key + " is not recognised");
    }

    @ParameterizedTest
    @ValueSource(strings = { "documentNo", "businessPartner", "clientReference", "organizational" })
    @DisplayName("a key that merely looks similar is not claimed")
    void lookalikesAreNotClaimed(String key) {
      assertFalse(NeoServerOwnedFields.isServerOwnedKey(key), key + " must not be claimed");
    }

    @Test
    @DisplayName("a null key is not a server-owned key")
    void nullKey() {
      assertFalse(NeoServerOwnedFields.isServerOwnedKey(null));
    }

    /**
     * <b>The crossed-wires case, pinned per key.</b> A naive fallback once mapped
     * {@code ad_client_id} onto {@code organization}; the two assertions below fail
     * independently, so a future edit that crosses them again cannot be masked by the other
     * still being right. A generic "both are stripped" test passes happily while the report
     * names the wrong field, which is worse than no report — it tells the caller to fix a field
     * it never sent.
     */
    @Test
    @DisplayName("ad_client_id maps to client, never to organization")
    void clientColumnMapsToClient() throws Exception {
      JSONObject report = NeoServerOwnedFields.stripServerOwned(
          body("ad_client_id", "SOME-OTHER-CLIENT"), null);

      assertTrue(report.has("client"), "ad_client_id must be reported under 'client'");
      assertFalse(report.has("organization"),
          "ad_client_id was reported as an organization — the two are crossed");
      assertEquals(SESSION_CLIENT, report.getJSONObject("client").getString("session"));
    }

    @Test
    @DisplayName("ad_org_id maps to organization, never to client")
    void organizationColumnMapsToOrganization() throws Exception {
      JSONObject report = NeoServerOwnedFields.stripServerOwned(
          body("ad_org_id", OTHER_ORG), null);

      assertTrue(report.has("organization"), "ad_org_id must be reported under 'organization'");
      assertFalse(report.has("client"),
          "ad_org_id was reported as a client — the two are crossed");
      assertEquals(SESSION_ORG, report.getJSONObject("organization").getString("session"));
    }

    /**
     * <b>Pins what the code does, which is NOT what the class javadoc claims.</b> The javadoc of
     * {@code SERVER_OWNED_KEYS} says it lists "the {@code _identifier} variant a client echoes
     * back from a read response". The variant this system actually emits is
     * {@code organization$_identifier} — five separate {@code IDENTIFIER_SUFFIX} constants across
     * this module spell it with the {@code $}, including {@code NeoFieldFilter}'s own. The map
     * lists {@code organization_identifier} without it, so the entry matches a spelling nothing
     * produces and the real companion key is not recognised. Reported, not fixed.
     */
    @Test
    @DisplayName("the $_identifier companion this system really emits is NOT recognised today")
    void theRealIdentifierCompanionIsNotRecognised() {
      assertTrue(NeoServerOwnedFields.isServerOwnedKey("organization_identifier"),
          "the map lists the underscore spelling");
      assertFalse(NeoServerOwnedFields.isServerOwnedKey("organization$_identifier"),
          "if this starts passing the map was corrected — update this test and delete the"
              + " finding, do not weaken the assertion");
      assertFalse(NeoServerOwnedFields.isServerOwnedKey("client$_identifier"));
    }
  }

  // ── the session's own value ───────────────────────────────────────────

  @Nested
  @DisplayName("sessionValue")
  class SessionValue {

    @Test
    @DisplayName("reads the current client and organization off the context")
    void readsTheContext() {
      assertEquals(SESSION_CLIENT, NeoServerOwnedFields.sessionValue("client"));
      assertEquals(SESSION_ORG, NeoServerOwnedFields.sessionValue("organization"));
    }

    @Test
    @DisplayName("a property this policy does not own has no session value")
    void unownedPropertyHasNone() {
      assertNull(NeoServerOwnedFields.sessionValue("businessPartner"));
      assertNull(NeoServerOwnedFields.sessionValue(null));
    }

    /**
     * The case the assignment asked to be pinned rather than mocked away. With no context there
     * is nothing to compare against, so the policy must fall silent — never guess, and never
     * fail the write.
     */
    @Test
    @DisplayName("with no OBContext it answers null instead of throwing")
    void noContextIsAnswerable() {
      withoutSession();
      assertNull(NeoServerOwnedFields.sessionValue("client"));
      assertNull(NeoServerOwnedFields.sessionValue("organization"));
    }

    @Test
    @DisplayName("a context with no client or organization is not a value either")
    void anEmptyContextIsNotAValue() {
      obContextMock.close();
      OBContext context = mock(OBContext.class);
      when(context.getCurrentClient()).thenReturn(null);
      when(context.getCurrentOrganization()).thenReturn(null);
      obContextMock = mockStatic(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(context);

      assertNull(NeoServerOwnedFields.sessionValue("client"));
      assertNull(NeoServerOwnedFields.sessionValue("organization"));
    }
  }

  // ── stripping ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("stripServerOwned")
  class Stripping {

    /**
     * Per key rather than in one body: a single body asserting "none of the six survived" passes
     * while five of the six are handled and the sixth is removed by the one before it. Each
     * spelling has to be the only thing in the body it is judged on.
     */
    @ParameterizedTest
    @ValueSource(strings = { "client", "AD_Client_ID", "ad_client_id",
        "organization", "AD_Org_ID", "ad_org_id" })
    @DisplayName("every spelling is removed from the body")
    void everySpellingIsRemoved(String key) throws Exception {
      JSONObject payload = body(key, "SOMETHING-ELSE");

      NeoServerOwnedFields.stripServerOwned(payload, null);

      assertFalse(payload.has(key), key + " survived the strip and would reach the DAL");
      assertEquals(0, payload.length());
    }

    @Test
    @DisplayName("nothing else is touched")
    void otherKeysSurvive() throws Exception {
      JSONObject payload = new JSONObject()
          .put("documentNo", "SO-1")
          .put("organization", OTHER_ORG)
          .put("businessPartner", "BP-1");

      NeoServerOwnedFields.stripServerOwned(payload, null);

      assertEquals(2, payload.length());
      assertEquals("SO-1", payload.getString("documentNo"));
      assertEquals("BP-1", payload.getString("businessPartner"));
    }

    /**
     * <b>Stripping is unconditional.</b> The comparison decides only whether the caller is told,
     * never whether the value is honoured — a rule that kept the value when it "happened to
     * match" would be a rule the caller could probe, and would still be reading tenant identity
     * out of the payload.
     */
    @Test
    @DisplayName("a value equal to the session's is discarded just the same")
    void anEchoIsStrippedToo() throws Exception {
      JSONObject payload = body("organization", SESSION_ORG);

      JSONObject report = NeoServerOwnedFields.stripServerOwned(payload, null);

      assertFalse(payload.has("organization"), "the value is resolved from the session, always");
      assertEquals(0, report.length(), "nothing was taken from the caller, so it is told nothing");
    }

    @Test
    @DisplayName("a key resolved through the DAL model is stripped too")
    void theDalModelResolvesAColumnSpelling() throws Exception {
      Property organization = mock(Property.class);
      when(organization.getName()).thenReturn("organization");
      Entity dalEntity = mock(Entity.class);
      when(dalEntity.getPropertyByColumnName("AD_Org_ID", false)).thenReturn(organization);

      JSONObject payload = body("AD_Org_ID", OTHER_ORG);
      JSONObject report = NeoServerOwnedFields.stripServerOwned(payload, dalEntity);

      assertFalse(payload.has("AD_Org_ID"));
      assertTrue(report.has("organization"));
    }

    @Test
    @DisplayName("a null body is tolerated and reports nothing")
    void nullBodyIsTolerated() {
      assertEquals(0, NeoServerOwnedFields.stripServerOwned(null, null).length());
    }

    @Test
    @DisplayName("an empty body reports nothing")
    void emptyBodyReportsNothing() {
      assertEquals(0, NeoServerOwnedFields.stripServerOwned(new JSONObject(), null).length());
    }
  }

  // ── reporting ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("the report")
  class Reporting {

    @Test
    @DisplayName("a different tenant is reported with both values")
    void aDifferentTenantIsReported() throws Exception {
      JSONObject report = NeoServerOwnedFields.stripServerOwned(
          body("organization", OTHER_ORG), null);

      JSONObject entry = report.getJSONObject("organization");
      assertEquals(OTHER_ORG, entry.getString("sent"),
          "'sent' must name what the caller asked for, or it cannot tell what was ignored");
      assertEquals(SESSION_ORG, entry.getString("session"),
          "'session' must name where the record actually went");
    }

    @Test
    @DisplayName("recordIfDifferent stays silent on an echo, a null value, or no session")
    void theSilentCases() throws Exception {
      JSONObject report = new JSONObject();

      NeoServerOwnedFields.recordIfDifferent(report, "organization", SESSION_ORG);
      assertEquals(0, report.length(), "an echo took nothing from the caller");

      NeoServerOwnedFields.recordIfDifferent(report, "organization", null);
      assertEquals(0, report.length(), "there is no value to have displaced");

      withoutSession();
      NeoServerOwnedFields.recordIfDifferent(report, "organization", OTHER_ORG);
      assertEquals(0, report.length(),
          "with no session there is nothing to compare against — guessing would be worse than"
              + " silence, and the strip already happened either way");
    }

    /**
     * With no session the value is still discarded. Only the telling is suppressed — otherwise a
     * background thread would silently start honouring caller-supplied tenants.
     */
    @Test
    @DisplayName("with no session the value is still stripped, only unreported")
    void noSessionStillStrips() throws Exception {
      withoutSession();
      JSONObject payload = body("organization", OTHER_ORG);

      JSONObject report = NeoServerOwnedFields.stripServerOwned(payload, null);

      assertFalse(payload.has("organization"), "the strip must not depend on having a session");
      assertEquals(0, report.length());
    }
  }

  // ── the REST write paths ──────────────────────────────────────────────

  /**
   * {@code filterCreateRequest} and {@code filterWriteRequest} both strip before they filter, and
   * both do it outside the {@code active} guard. The inactive cases are the point: that is the
   * configuration in which {@code filterBody} returns the body untouched, so the whitelist that
   * covers these keys today is not covering them at all.
   */
  @Nested
  @DisplayName("the REST write paths strip regardless of the filter")
  class RestPaths {

    private NeoFieldFilter filter(boolean active) throws Exception {
      Constructor<NeoFieldFilter> ctor = NeoFieldFilter.class.getDeclaredConstructor(
          Set.class, Set.class, Set.class, Map.class, Map.class, boolean.class);
      ctor.setAccessible(true);
      // 'organization' deliberately IN the writable whitelist: if the tenant rule were still
      // riding on the whitelist, this fixture would let the value through. It must not.
      return ctor.newInstance(Set.of("documentNo", "organization", "client"),
          Set.of("documentNo", "organization", "client"), Collections.emptySet(),
          Collections.emptyMap(), Collections.emptyMap(), active);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    @DisplayName("filterCreateRequest drops the tenant fields, active or not")
    void createStrips(boolean active) throws Exception {
      JSONObject payload = new JSONObject()
          .put("documentNo", "SO-1")
          .put("organization", OTHER_ORG)
          .put("client", "OTHER-CLIENT");

      filter(active).filterCreateRequest(payload);

      assertFalse(payload.has("organization"),
          "active=" + active + ": an organization reaching the DAL is a cross-tenant write");
      assertFalse(payload.has("client"), "active=" + active);
      assertTrue(payload.has("documentNo"), "the rest of the body must be untouched by this rule");
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    @DisplayName("filterWriteRequest drops them too — an update relocates an existing record")
    void writeStrips(boolean active) throws Exception {
      JSONObject payload = new JSONObject()
          .put("documentNo", "SO-1")
          .put("organization", OTHER_ORG);

      filter(active).filterWriteRequest(payload);

      assertFalse(payload.has("organization"),
          "active=" + active + ": moving organization on an update is the same hole from the"
              + " other direction");
      assertTrue(payload.has("documentNo"));
    }

    /**
     * The SPA and the legacy clients wrap the record in a {@code data} node. Stripping only the
     * top level would leave the whole REST surface untouched by the rule.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    @DisplayName("a body wrapped in a data node is stripped inside the node")
    void theDataNodeIsStripped(boolean active) throws Exception {
      JSONObject data = new JSONObject()
          .put("documentNo", "SO-1")
          .put("organization", OTHER_ORG);
      JSONObject payload = new JSONObject().put("data", data);

      filter(active).filterCreateRequest(payload);

      assertFalse(payload.getJSONObject("data").has("organization"), "active=" + active);
      assertTrue(payload.getJSONObject("data").has("documentNo"));
    }

    @Test
    @DisplayName("the column spellings are dropped on the REST path as well")
    void columnSpellingsAreDropped() throws Exception {
      for (String key : List.of("AD_Org_ID", "AD_Client_ID", "ad_org_id", "ad_client_id")) {
        JSONObject payload = body(key, OTHER_ORG);
        filter(false).filterCreateRequest(payload);
        assertFalse(payload.has(key), key + " survived filterCreateRequest");
      }
    }

    @Test
    @DisplayName("a null body does not break either entry point")
    void nullBodiesAreTolerated() throws Exception {
      filter(true).filterCreateRequest(null);
      filter(false).filterWriteRequest(null);
    }
  }
}
