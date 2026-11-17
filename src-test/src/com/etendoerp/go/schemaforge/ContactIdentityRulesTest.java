/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.ContactIdentityRules.Persisted;

/**
 * Unit tests for {@link ContactIdentityRules}, the person/company identity shape of a contact
 * (ETP-6175). They use the {@code apply(ctx, body, method, loader)} overload, so no database is
 * needed; the message text is resolved from the bundled catalog (the DAL statics are mocked so the
 * {@code AD_MESSAGE} lookups find nothing).
 *
 * @covers com.etendoerp.go.schemaforge.ContactIdentityRules
 */
class ContactIdentityRulesTest {

  private static final String REC = "REC_001";
  private static final Object[] BLANKS = { "", "   ", JSONObject.NULL };

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private Function<String, Persisted> loader;
  private NeoContext ctx;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    loader = mock(Function.class);
    ctx = mock(NeoContext.class);
    when(ctx.getRecordId()).thenReturn(REC);
  }

  @AfterEach
  void tearDown() {
    obDal.close();
    obContext.close();
  }

  private NeoResponse post(JSONObject body) throws Exception {
    return ContactIdentityRules.apply(ctx, body, "POST", loader);
  }

  private NeoResponse patch(JSONObject body, Persisted stored) throws Exception {
    when(loader.apply(REC)).thenReturn(stored);
    return ContactIdentityRules.apply(ctx, body, "PATCH", loader);
  }

  private static Persisted person(String name, String first, String last) {
    return new Persisted(true, name, first, last);
  }

  private static Persisted company(String name, String first, String last) {
    return new Persisted(false, name, first, last);
  }

  private static JSONObject json() {
    return new JSONObject();
  }

  /** Asserts the 400 refusal with the given code, message key and offending fields. */
  private static JSONObject assertRefusal(NeoResponse r, String code, String key, String... fields)
      throws Exception {
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    JSONObject error = r.getBody().getJSONObject("error");
    assertEquals(code, error.getString("code"));
    assertEquals(key, error.getJSONArray("messageKeys").getString(0));
    assertEquals(1, error.getJSONArray("messageKeys").length());
    JSONArray got = error.getJSONArray("fields");
    assertEquals(fields.length, got.length(), got.toString());
    for (int i = 0; i < fields.length; i++) {
      assertEquals(fields[i], got.getString(i));
    }
    return error;
  }

  // ── person on create ─────────────────────────────────────────────────────────────────────────

  @Test
  void aPersonCreateDerivesTheNameFromFirstAndLastName() throws Exception {
    JSONObject absent = json().put("etgoIsperson", true).put("etgoFirstname", "F")
        .put("etgoLastname", "L");
    JSONObject empty = json().put("etgoIsperson", true).put("name", "")
        .put("etgoFirstname", "F").put("etgoLastname", "L");

    assertNull(post(absent));
    assertNull(post(empty));

    assertEquals("F L", absent.getString("name"));
    assertEquals("F L", empty.getString("name"));
    verifyNoInteractions(loader);
  }

  @Test
  void aPersonCreateWithAMissingOrBlankPartListsExactlyTheMissingParts() throws Exception {
    for (Object blank : BLANKS) {
      assertRefusal(post(json().put("etgoIsperson", true).put("etgoFirstname", "F")
          .put("etgoLastname", blank)), "MISSING_REQUIRED_FIELDS",
          ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoLastname");
      assertRefusal(post(json().put("etgoIsperson", true).put("etgoFirstname", blank)
          .put("etgoLastname", "L")), "MISSING_REQUIRED_FIELDS",
          ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoFirstname");
    }
    assertRefusal(post(json().put("etgoIsperson", true).put("etgoLastname", "L")),
        "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoFirstname");
    assertRefusal(post(json().put("etgoIsperson", true).put("etgoFirstname", "F")),
        "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoLastname");
    assertRefusal(post(json().put("etgoIsperson", true)), "MISSING_REQUIRED_FIELDS",
        ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoFirstname", "etgoLastname");
  }

  @Test
  void aPersonCreateWithANameOfItsOwnIsRefusedUnlessItIsTheDerivation() throws Exception {
    JSONObject different = json().put("etgoIsperson", true).put("name", "Other")
        .put("etgoFirstname", "F").put("etgoLastname", "L");
    JSONObject error = assertRefusal(post(different), ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_PERSON_NAME_NOT_ALLOWED, "name");
    assertEquals("name", error.getString("field"));

    JSONObject same = json().put("etgoIsperson", true).put("name", "F L")
        .put("etgoFirstname", "F").put("etgoLastname", "L");
    assertNull(post(same));
    assertEquals("F L", same.getString("name"));

    JSONObject spaced = json().put("etgoIsperson", true).put("name", "  F   L ")
        .put("etgoFirstname", "F").put("etgoLastname", "L");
    assertNull(post(spaced));
    assertEquals("F L", spaced.getString("name"));
  }

  @Test
  void theTypeCountsAsPersonForABooleanOrYOrTrueText() throws Exception {
    for (Object isPerson : new Object[] { true, "Y", "true" }) {
      JSONObject body = json().put("etgoIsperson", isPerson).put("etgoFirstname", "F")
          .put("etgoLastname", "L");

      assertNull(post(body), String.valueOf(isPerson));
      assertEquals("F L", body.getString("name"), String.valueOf(isPerson));
    }
  }

  // ── company on create ────────────────────────────────────────────────────────────────────────

  @Test
  void aCompanyCreateNeedsOnlyItsName() throws Exception {
    JSONObject body = json().put("etgoIsperson", false).put("name", "Acme");

    assertNull(post(body));

    assertEquals("Acme", body.getString("name"));
    assertFalse(body.has("etgoFirstname"));
    assertFalse(body.has("etgoLastname"));
  }

  @Test
  void aCompanyCreateWithoutAnyNameIsRefused() throws Exception {
    assertRefusal(post(json().put("etgoIsperson", false)), "MISSING_REQUIRED_FIELDS",
        ContactIdentityRules.MSG_COMPANY_NAME_REQUIRED, "name");
    for (Object blank : BLANKS) {
      assertRefusal(post(json().put("etgoIsperson", false).put("name", blank)),
          "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_COMPANY_NAME_REQUIRED, "name");
    }
  }

  @Test
  void aCompanyCreateWithAFirstOrLastNameIsRefusedNamingTheField() throws Exception {
    JSONObject first = assertRefusal(
        post(json().put("etgoIsperson", false).put("name", "Acme").put("etgoFirstname", "F")),
        ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED, "etgoFirstname");
    assertEquals("etgoFirstname", first.getString("field"));
    assertTrue(first.getString("message").contains("etgoIsperson=true"), first.toString());
    assertTrue(first.getString("message").contains("is_person"), first.toString());

    JSONObject last = assertRefusal(
        post(json().put("etgoIsperson", false).put("name", "Acme").put("etgoLastname", "L")),
        ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED, "etgoLastname");
    assertEquals("etgoLastname", last.getString("field"));
  }

  @Test
  void aCompanyCreateWithEmptyFirstAndLastNamePasses() throws Exception {
    assertNull(post(json().put("etgoIsperson", false).put("name", "Acme")
        .put("etgoFirstname", "").put("etgoLastname", "")));
  }

  @Test
  void aCreateWithoutTheTypeIsACompanyEvenWhenItCarriesFirstAndLastName() throws Exception {
    assertNull(post(json().put("name", "Acme")));

    assertRefusal(post(json().put("etgoFirstname", "F").put("etgoLastname", "L")),
        ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED, "etgoFirstname");
  }

  // ── update ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void anUpdateWithoutAnyIdentityKeyIsNotJudgedAndTheRowIsNotRead() throws Exception {
    JSONObject patchBody = json().put("etgoEmail", "a@b.c");
    JSONObject putBody = json().put("description", "x");

    assertNull(ContactIdentityRules.apply(ctx, patchBody, "PATCH", loader));
    assertNull(ContactIdentityRules.apply(ctx, putBody, "PUT", loader));

    verifyNoInteractions(loader);
    assertEquals("a@b.c", patchBody.getString("etgoEmail"));
    assertEquals(1, patchBody.length());
  }

  @Test
  void aNullBodyIsLeftAlone() throws Exception {
    assertNull(ContactIdentityRules.apply(ctx, null, "POST", loader));
    verifyNoInteractions(loader);
  }

  @Test
  void persistedValuesFillInTheKeysAnUpdateOmits() throws Exception {
    JSONObject body = json().put("etgoFirstname", "C");

    assertNull(patch(body, person("A B", "A", "B")));

    assertEquals("C B", body.getString("name"));
    verify(loader).apply(REC);
  }

  @Test
  void aCompanyBecomesAPersonWithBothPartsFromTheBodyAndDerivesTheName() throws Exception {
    JSONObject body = json().put("etgoIsperson", true).put("etgoFirstname", "A")
        .put("etgoLastname", "B");

    assertNull(patch(body, company("Acme", "", "")));

    assertEquals("A B", body.getString("name"));
  }

  @Test
  void aCompanyBecomesAPersonWithPersistedPartsCounting() throws Exception {
    JSONObject body = json().put("etgoIsperson", true);

    assertNull(patch(body, company("Acme", "A", "B")));

    assertEquals("A B", body.getString("name"));
  }

  @Test
  void aCompanyBecomingAPersonNeedsBothParts() throws Exception {
    assertRefusal(patch(json().put("etgoIsperson", true).put("etgoFirstname", "A"),
        company("Acme", "", "")), "MISSING_REQUIRED_FIELDS",
        ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoLastname");
    assertRefusal(patch(json().put("etgoIsperson", true), company("Acme", "", "")),
        "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED,
        "etgoFirstname", "etgoLastname");
  }

  @Test
  void aPersonBecomingACompanyNeedsTheEffectiveName() throws Exception {
    assertRefusal(patch(json().put("etgoIsperson", false), person("", "A", "B")),
        "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_COMPANY_NAME_REQUIRED, "name");

    JSONObject body = json().put("etgoIsperson", false);
    assertNull(patch(body, person("A B", "A", "B")));
    assertFalse(body.has("name"), "the stored name stays");

    JSONObject named = json().put("etgoIsperson", false).put("name", "X");
    assertNull(patch(named, person("A B", "A", "B")));
    assertEquals("X", named.getString("name"));
  }

  @Test
  void clearingACompanysNameIsRefused() throws Exception {
    for (Object blank : BLANKS) {
      assertRefusal(patch(json().put("name", blank), company("Acme", "", "")),
          "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_COMPANY_NAME_REQUIRED, "name");
    }
  }

  @Test
  void editingAPersonsFirstNameReDerivesTheName() throws Exception {
    JSONObject body = json().put("etgoFirstname", "Anita");

    assertNull(patch(body, person("Ana Ruiz", "Ana", "Ruiz")));

    assertEquals("Anita Ruiz", body.getString("name"));
  }

  @Test
  void aLegacyPersonWithoutALastNameCanStillHaveItsFirstNameEdited() throws Exception {
    JSONObject body = json().put("etgoFirstname", "Anita");

    assertNull(patch(body, person("Ana", "Ana", "")));

    assertEquals("Anita", body.getString("name"));
  }

  @Test
  void aLegacyPersonIsNotBlockedByAnUnrelatedUpdate() throws Exception {
    JSONObject body = json().put("etgoEmail", "a@b.c");

    assertNull(ContactIdentityRules.apply(ctx, body, "PATCH", loader));

    verifyNoInteractions(loader);
  }

  @Test
  void aPersonWithBothPartsEmptyAndABlankNameGetsTheNameDropped() throws Exception {
    JSONObject body = json().put("name", "");

    assertNull(patch(body, person("Legacy", "", "")));

    assertFalse(body.has("name"));
  }

  @Test
  void aBlankOrMissingRecordIdSkipsTheRules() throws Exception {
    for (String id : new String[] { "", "  ", null }) {
      when(ctx.getRecordId()).thenReturn(id);
      JSONObject body = json().put("etgoFirstname", "Pedro");

      assertNull(ContactIdentityRules.apply(ctx, body, "PATCH", loader));

      assertFalse(body.has("name"));
    }
    verify(loader, never()).apply(anyString());
  }

  @Test
  void aMissingRowSkipsTheRules() throws Exception {
    JSONObject body = json().put("etgoFirstname", "Pedro");

    assertNull(patch(body, null));

    assertFalse(body.has("name"));
    verify(loader).apply(REC);
  }

  // ── switching to company clears the stored parts ─────────────────────────────────────────────

  @Test
  void switchingToCompanyClearsBothStoredParts() throws Exception {
    JSONObject body = json().put("etgoIsperson", false).put("name", "X");

    assertNull(patch(body, person("A B", "A", "B")));

    assertEquals("", body.getString("etgoFirstname"));
    assertEquals("", body.getString("etgoLastname"));
  }

  @Test
  void aCompanyThatStillStoresPartsGetsThemClearedOnANameEdit() throws Exception {
    JSONObject body = json().put("name", "Y");

    assertNull(patch(body, company("Z", "A", "B")));

    assertEquals("", body.getString("etgoFirstname"));
    assertEquals("", body.getString("etgoLastname"));
  }

  @Test
  void anUpdateWithoutIdentityKeysClearsNothingAndReadsNothing() throws Exception {
    JSONObject body = json().put("etgoEmail", "a@b.c");

    assertNull(ContactIdentityRules.apply(ctx, body, "PATCH", loader));

    assertFalse(body.has("etgoFirstname"));
    assertFalse(body.has("etgoLastname"));
    verifyNoInteractions(loader);
  }

  @Test
  void aBlankFirstNameOnACompanyWithAStoredLastNameClearsTheLastName() throws Exception {
    JSONObject body = json().put("etgoFirstname", "");

    assertNull(patch(body, company("Z", "", "B")));

    assertEquals("", body.getString("etgoFirstname"));
    assertEquals("", body.getString("etgoLastname"));
  }

  @Test
  void aCompanyWithNoStoredPartsGetsNoPartKeysAdded() throws Exception {
    JSONObject body = json().put("name", "Y");

    assertNull(patch(body, company("Z", "", "")));

    assertFalse(body.has("etgoFirstname"));
    assertFalse(body.has("etgoLastname"));
  }

  @Test
  void switchingToCompanyWithAFirstNameIsRefusedAndNothingIsCleared() throws Exception {
    JSONObject body = json().put("etgoIsperson", false).put("etgoFirstname", "A");

    assertRefusal(patch(body, person("A B", "A", "B")),
        ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED, "etgoFirstname");

    assertEquals("A", body.getString("etgoFirstname"));
    assertFalse(body.has("etgoLastname"));
  }

  // ── payloads the app sends when it toggles the type ──────────────────────────────────────────

  @Test
  void theAppsCompanyToPersonTogglePayloadPasses() throws Exception {
    JSONObject body = json().put("etgoIsperson", true).put("name", "")
        .put("etgoFirstname", "A").put("etgoLastname", "B");

    assertNull(patch(body, company("Acme", "", "")));

    assertEquals("A B", body.getString("name"));
  }

  @Test
  void theAppsPersonToCompanyTogglePayloadPasses() throws Exception {
    JSONObject body = json().put("etgoIsperson", false).put("name", "Acme")
        .put("etgoFirstname", "").put("etgoLastname", "");

    assertNull(patch(body, person("A B", "A", "B")));

    assertEquals("Acme", body.getString("name"));
    assertEquals("", body.getString("etgoFirstname"));
    assertEquals("", body.getString("etgoLastname"));
  }

  @Test
  void aBlankNameOnAPersonWithStoredPartsPassesAndKeepsTheDerivation() throws Exception {
    JSONObject body = json().put("name", "");

    assertNull(patch(body, person("A B", "A", "B")));

    assertEquals("A B", body.getString("name"));
  }

  // ── blank counts as absent ───────────────────────────────────────────────────────────────────

  @Test
  void aBlankTypeIsTheSameAsNoTypeOnCreateAndOnUpdate() throws Exception {
    for (Object blank : BLANKS) {
      assertNull(post(json().put("etgoIsperson", blank).put("name", "Acme")));

      JSONObject body = json().put("etgoIsperson", blank).put("etgoFirstname", "B");
      assertNull(patch(body, person("A", "A", "L")), String.valueOf(blank));
      assertEquals("B L", body.getString("name"));
    }
  }

  @Test
  void aBlankFirstOrLastNameOnACompanyIsAbsent() throws Exception {
    for (Object blank : BLANKS) {
      assertNull(post(json().put("etgoIsperson", false).put("name", "Acme")
          .put("etgoFirstname", blank)), String.valueOf(blank));
      assertNull(post(json().put("etgoIsperson", false).put("name", "Acme")
          .put("etgoLastname", blank)), String.valueOf(blank));
    }
  }

  @Test
  void aBlankNameOnAPersonIsAbsentAndABlankNameOnACompanyIsMissing() throws Exception {
    for (Object blank : BLANKS) {
      JSONObject person = json().put("etgoIsperson", true).put("name", blank)
          .put("etgoFirstname", "F").put("etgoLastname", "L");
      assertNull(post(person), String.valueOf(blank));
      assertEquals("F L", person.getString("name"));

      assertRefusal(post(json().put("etgoIsperson", false).put("name", blank)),
          "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_COMPANY_NAME_REQUIRED, "name");
    }
  }

  @Test
  void aBlankFirstOrLastNameOnAPersonIsMissing() throws Exception {
    for (Object blank : BLANKS) {
      assertRefusal(post(json().put("etgoIsperson", true).put("etgoFirstname", blank)
          .put("etgoLastname", "L")), "MISSING_REQUIRED_FIELDS",
          ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoFirstname");
    }
  }

  // ── requestedType / deriveNameForCreate ──────────────────────────────────────────────────────

  @Test
  void requestedTypeReadsBooleansYNAndTextAndTreatsBlankAsAbsent() throws Exception {
    assertEquals(Optional.of(true), ContactIdentityRules.requestedType(json().put("etgoIsperson", true)));
    assertEquals(Optional.of(false), ContactIdentityRules.requestedType(json().put("etgoIsperson", false)));
    assertEquals(Optional.of(true), ContactIdentityRules.requestedType(json().put("etgoIsperson", "Y")));
    assertEquals(Optional.of(false), ContactIdentityRules.requestedType(json().put("etgoIsperson", "N")));
    assertEquals(Optional.of(true), ContactIdentityRules.requestedType(json().put("etgoIsperson", "true")));
    assertEquals(Optional.of(false), ContactIdentityRules.requestedType(json().put("etgoIsperson", "false")));
    assertEquals(Optional.empty(), ContactIdentityRules.requestedType(json()));
    for (Object blank : BLANKS) {
      assertEquals(Optional.empty(), ContactIdentityRules.requestedType(json().put("etgoIsperson", blank)));
    }
  }

  @Test
  void deriveNameForCreateNamesAPersonAndLeavesACompanyAlone() throws Exception {
    JSONObject person = json().put("etgoIsperson", true).put("etgoFirstname", "F")
        .put("etgoLastname", "L");
    ContactIdentityRules.deriveNameForCreate(person);
    assertEquals("F L", person.getString("name"));

    JSONObject company = json().put("etgoFirstname", "F").put("etgoLastname", "L");
    ContactIdentityRules.deriveNameForCreate(company);
    assertFalse(company.has("name"));

    JSONObject named = json().put("etgoIsperson", true).put("name", "Mine")
        .put("etgoFirstname", "F");
    ContactIdentityRules.deriveNameForCreate(named);
    assertEquals("Mine", named.getString("name"));

    JSONObject noParts = json().put("etgoIsperson", true);
    ContactIdentityRules.deriveNameForCreate(noParts);
    assertFalse(noParts.has("name"));

    ContactIdentityRules.deriveNameForCreate(null);
  }

  // ── {"data": {...}} envelope ─────────────────────────────────────────────────────────────────
  // NeoFieldFilter#filterBody persists only the envelope's inner object, so the rules judge it
  // and write into it (the innermost of ContactSepaMandateDefaults#writeSections).

  private static JSONObject envelope(JSONObject data) throws Exception {
    return json().put("data", data);
  }

  @Test
  void anEnvelopeCompanyCreateWithANamePasses() throws Exception {
    JSONObject data = json().put("name", "Acme").put("customer", true);

    assertNull(post(envelope(data)));

    assertEquals("Acme", data.getString("name"));
  }

  @Test
  void anEnvelopePersonCreateDerivesTheNameInsideTheEnvelope() throws Exception {
    JSONObject data = json().put("etgoIsperson", true).put("etgoFirstname", "F")
        .put("etgoLastname", "L");
    JSONObject body = envelope(data);

    assertNull(post(body));

    assertEquals("F L", data.getString("name"));
    assertFalse(body.has("name"));
  }

  @Test
  void anEnvelopeCreateIsJudgedLikeATopLevelOne() throws Exception {
    assertRefusal(post(envelope(json().put("etgoIsperson", true).put("etgoFirstname", "F"))),
        "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, "etgoLastname");
    assertRefusal(post(envelope(json().put("name", "Acme").put("etgoLastname", "L"))),
        ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED, "etgoLastname");
    assertRefusal(post(envelope(json().put("customer", true))),
        "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_COMPANY_NAME_REQUIRED, "name");
  }

  @Test
  void anEnvelopeUpdateIsJudgedAndWrittenInsideTheEnvelope() throws Exception {
    assertRefusal(patch(envelope(json().put("name", "Other")), person("A B", "A", "B")),
        ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_PERSON_NAME_NOT_ALLOWED, "name");

    JSONObject data = json().put("etgoIsperson", false).put("name", "Acme");
    JSONObject body = envelope(data);
    assertNull(patch(body, person("A B", "A", "B")));
    assertEquals("", data.getString("etgoFirstname"));
    assertEquals("", data.getString("etgoLastname"));
    assertFalse(body.has("etgoFirstname"));
  }

  @Test
  void keysBesideAnEnvelopeAreNotJudgedBecauseTheyAreNotPersisted() throws Exception {
    JSONObject data = json().put("etgoIsperson", true).put("etgoFirstname", "F")
        .put("etgoLastname", "L");
    JSONObject body = envelope(data).put("etgoIsperson", false).put("name", "Stale");

    assertNull(post(body));

    assertEquals("F L", data.getString("name"));
    assertEquals("Stale", body.getString("name"));
  }

  @Test
  void deriveNameForCreateNamesAPersonInsideTheEnvelope() throws Exception {
    JSONObject data = json().put("etgoIsperson", true).put("etgoFirstname", "F")
        .put("etgoLastname", "L");
    JSONObject body = envelope(data);

    ContactIdentityRules.deriveNameForCreate(body);

    assertEquals("F L", data.getString("name"));
    assertFalse(body.has("name"));
  }

  // ── error shape ──────────────────────────────────────────────────────────────────────────────

  @Test
  void bothErrorShapesCarryTheMessageKeyAndTheCatalogText() throws Exception {
    JSONObject required = assertRefusal(post(json().put("etgoIsperson", true)),
        "MISSING_REQUIRED_FIELDS", ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED,
        "etgoFirstname", "etgoLastname");
    assertEquals(NeoMessageText.text(ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED),
        required.getString("message"));
    assertNotEquals(ContactIdentityRules.MSG_PERSON_NAMES_REQUIRED, required.getString("message"));
    assertEquals(400, required.getInt("status"));

    JSONObject notAllowed = assertRefusal(
        post(json().put("etgoIsperson", false).put("name", "A").put("etgoLastname", "L")),
        ContactIdentityRules.ERROR_FIELD_NOT_ALLOWED,
        ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED, "etgoLastname");
    assertEquals(NeoMessageText.text(ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED),
        notAllowed.getString("message"));
    assertNotEquals(ContactIdentityRules.MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED,
        notAllowed.getString("message"));
    assertEquals(400, notAllowed.getInt("status"));
  }
}
