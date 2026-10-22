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
package com.etendoerp.go.signup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.etendoerp.go.rest.SignupAccountService;

/**
 * Validation and output of the signup API's {@code POST /accounts} body: what the caller gets back
 * for each mistake is the public contract.
 *
 * @covers com.etendoerp.go.signup.SignupRequest
 */
class SignupRequestTest {

  private static JSONObject validBody() throws Exception {
    return new JSONObject()
        .put("name", "Ana García")
        .put("email", "  Ana@Empresa.ES ")
        .put("companyName", "Empresa SL")
        .put("countryCode", "es");
  }

  private static SignupException refusal(JSONObject body) {
    return assertThrows(SignupException.class, () -> SignupRequest.parse(body));
  }

  @Test
  void aValidBodyIsNormalizedAndTakesItsDefaultsFromTheCountry() throws Exception {
    SignupRequest request = SignupRequest.parse(validBody());

    assertEquals("ana@empresa.es", request.email());
    assertEquals("Empresa SL", request.companyName());
    assertTrue(request.sendEmails());
    JSONObject draft = new JSONObject(request.toOnboardingDraft());
    JSONObject form = draft.getJSONObject("form");
    assertEquals(2, draft.getInt("step"));
    assertEquals("ES", form.getString("countryCode"));
    assertEquals("EUR", form.getString("currency"));
    assertEquals("es_ES", form.getString("language"));
    assertEquals("Ana García", form.getString("fullName"));
    assertEquals("Empresa SL", form.getString("clientName"));
    assertFalse(form.getBoolean("includeSampleData"));
  }

  @Test
  void theAccountCarriesTheChannelTheMailChoiceTheTermsAndTheSource() throws Exception {
    JSONObject body = validBody().put("sendEmails", false).put("termsAccepted", true)
        .put("language", "en_US").put("taxId", "B12345674").put("includeSampleData", true)
        .put("source", new JSONObject().put("utm_source", "crm"));

    SignupAccountService.NewAccount account = SignupRequest.parse(body).toNewAccount("crm");

    assertEquals("ana@empresa.es", account.email());
    assertEquals("Ana García", account.name());
    assertEquals("crm", account.channel());
    assertFalse(account.sendEmails());
    assertTrue(account.termsAccepted());
    assertEquals("{\"utm_source\":\"crm\"}", account.source());
    JSONObject form = new JSONObject(account.onboardingDraft()).getJSONObject("form");
    assertEquals("en_US", form.getString("language"));
    assertEquals("B12345674", form.getString("fiscalIdValue"));
    assertTrue(form.getBoolean("includeSampleData"));
  }

  @Test
  void noSourceIsStoredAsNothing() throws Exception {
    assertNull(SignupRequest.parse(validBody()).toNewAccount("web").source());
  }

  @ParameterizedTest
  @ValueSource(strings = { "name", "email", "companyName", "countryCode" })
  void eachRequiredFieldMissingIsReportedWithItsName(String field) throws Exception {
    JSONObject body = validBody();
    body.remove(field);

    SignupException e = refusal(body);

    assertEquals(400, e.status());
    assertEquals("REQUIRED_FIELD", e.code());
    assertEquals(field, e.field());
  }

  @Test
  void aJsonNullIsMissingNotTheTextNull() throws Exception {
    SignupException e = refusal(validBody().put("name", JSONObject.NULL));

    assertEquals("REQUIRED_FIELD", e.code());
    assertEquals("name", e.field());
  }

  @Test
  void aMalformedEmailIsRefused() throws Exception {
    SignupException e = refusal(validBody().put("email", "not-an-email"));

    assertEquals("INVALID_EMAIL_FORMAT", e.code());
    assertEquals("email", e.field());
  }

  @Test
  void anOverLongCompanyNameReportsItsLimit() throws Exception {
    SignupException e = refusal(validBody().put("companyName", "x".repeat(41)));

    assertEquals("FIELD_TOO_LONG", e.code());
    assertEquals("companyName", e.field());
    assertEquals(Integer.valueOf(40), e.max());
  }

  @Test
  void anOverLongSourceReportsItsLimit() throws Exception {
    SignupException e = refusal(validBody().put("source",
        new JSONObject().put("referrer", "x".repeat(2000))));

    assertEquals("FIELD_TOO_LONG", e.code());
    assertEquals("source", e.field());
  }

  @Test
  void aCountryOutsideTheOptionsIsRefused() throws Exception {
    assertEquals("UNSUPPORTED_COUNTRY", refusal(validBody().put("countryCode", "AR")).code());
  }

  @Test
  void aCurrencyOutsideTheOptionsIsRefused() throws Exception {
    assertEquals("UNSUPPORTED_CURRENCY", refusal(validBody().put("currency", "USD")).code());
  }

  @Test
  void aLanguageOutsideTheOptionsIsRefused() throws Exception {
    assertEquals("UNSUPPORTED_LANGUAGE", refusal(validBody().put("language", "fr_FR")).code());
  }

  @Test
  void aTaxIdOfNoSpanishShapeIsAFormatError() throws Exception {
    SignupException e = refusal(validBody().put("taxId", "123"));

    assertEquals("INVALID_TAX_ID_FORMAT", e.code());
    assertEquals("taxId", e.field());
  }

  @Test
  void aTaxIdWithAWrongControlDigitIsACheckDigitError() throws Exception {
    assertEquals("INVALID_TAX_ID_CHECK_DIGIT",
        refusal(validBody().put("taxId", "B12345679")).code());
  }

  @Test
  void aRefusalForAWholeRequestNamesNoField() {
    SignupException e = SignupRequest.badRequest("INVALID_REQUEST", null, "bad");

    assertNull(e.field());
    assertNull(e.max());
  }
}
