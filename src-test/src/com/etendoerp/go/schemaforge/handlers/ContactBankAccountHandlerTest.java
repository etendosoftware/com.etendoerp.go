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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoExtension;

/**
 * Unit tests for {@link ContactBankAccountHandler}, the customization of a contact's bank accounts
 * (ETP-6247): the IBAN a write body carries — at the top level, inside its {@code data}
 * envelope, or both; under any casing of the key, each kept as sent — is stored without whitespace
 * (non-breaking spaces included) and upper-cased, so the SEPA file check and the generator see the
 * IBAN the bank expects. Only CRUD writes (POST, PUT, PATCH) are touched; the handler never
 * short-circuits.
 *
 * <p>A class of its own: {@code ContactBankAccountHandler} is a new production class;
 * {@code ContactHandlerTest} covers the contact header's handler, another entity.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ContactBankAccountHandler
 */
public class ContactBankAccountHandlerTest {

  private static final String IBAN = "iBAN";
  private static final String TYPED = " es91 2100\t0418\u00a04502\u20030005 1332\n";
  private static final String NORMALIZED = "ES9121000418450200051332";

  private final ContactBankAccountHandler handler = new ContactBankAccountHandler();

  @Test
  public void bindingIsTheContactsBankAccountAnnotationOnly() {
    NeoExtension binding = ContactBankAccountHandler.class.getAnnotation(NeoExtension.class);

    assertNotNull(binding);
    assertEquals("contacts", binding.spec());
    assertEquals("bankAccount", binding.entity());
    assertNull(ContactBankAccountHandler.class.getAnnotation(Named.class));
  }

  // ---------------------------------------------------------------- normalize

  @Test
  public void normalizeStripsEveryKindOfWhitespaceAndUpperCases() {
    assertEquals(NORMALIZED, ContactBankAccountHandler.normalize(TYPED));
    assertEquals(NORMALIZED, ContactBankAccountHandler.normalize(NORMALIZED));
    assertEquals("", ContactBankAccountHandler.normalize(" \u00a0 "));
  }

  /** Upper-casing does not depend on the server locale (a Turkish «i» would become «İ»). */
  @Test
  public void normalizeUpperCasesWithoutTheServerLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr", "TR"));
      assertEquals("IT60X0542811101000000123456",
          ContactBankAccountHandler.normalize("it60x0542811101000000123456"));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  public void normalizeIbanRewritesOnlyAStringIban() throws Exception {
    JSONObject body = new JSONObject().put(IBAN, TYPED).put("bankFormat", "IBAN");
    ContactBankAccountHandler.normalizeIban(body);
    assertEquals(NORMALIZED, body.getString(IBAN));
    assertEquals("other keys are left alone", "IBAN", body.getString("bankFormat"));

    JSONObject explicitNull = new JSONObject().put(IBAN, JSONObject.NULL);
    ContactBankAccountHandler.normalizeIban(explicitNull);
    assertTrue("an explicit null clears, untouched", explicitNull.isNull(IBAN));

    JSONObject number = new JSONObject().put(IBAN, 12345);
    ContactBankAccountHandler.normalizeIban(number);
    assertEquals(12345, number.get(IBAN));

    JSONObject absent = new JSONObject().put("accountNo", "1234 5678");
    ContactBankAccountHandler.normalizeIban(absent);
    assertFalse("no iBAN key is added", absent.has(IBAN));
    assertEquals("1234 5678", absent.getString("accountNo"));

    ContactBankAccountHandler.normalizeIban(null);
  }

  /** The {@code {"data": {...}}} envelope a write may arrive in is normalized too. */
  @Test
  public void normalizeIbanRewritesTheIbanInsideTheDataEnvelope() throws Exception {
    JSONObject data = new JSONObject().put(IBAN, TYPED).put("bankFormat", "IBAN");
    JSONObject body = new JSONObject().put("data", data);

    ContactBankAccountHandler.normalizeIban(body);

    assertSame("rewritten in place", data, body.getJSONObject("data"));
    assertEquals(NORMALIZED, data.getString(IBAN));
    assertEquals("IBAN", data.getString("bankFormat"));
    assertFalse("nothing is lifted to the top level", body.has(IBAN));
  }

  /** Any casing of the key is the IBAN, and each keeps its own spelling. */
  @Test
  public void everyCasingOfTheKeyIsNormalizedUnderItsOwnSpelling() throws Exception {
    for (String key : new String[] { "Iban", "IBAN", "iban", "iBAN" }) {
      JSONObject body = new JSONObject().put(key, TYPED);

      ContactBankAccountHandler.normalizeIban(body);

      assertEquals(key, NORMALIZED, body.getString(key));
      assertEquals(key + ": no other spelling is added", 1, body.length());
    }
  }

  /** A key that only contains «iban» is another field, left alone. */
  @Test
  public void aKeyThatOnlyContainsIbanIsNotTheIban() throws Exception {
    JSONObject body = new JSONObject().put("ibanCountry", " es ").put("generateIban", " y ");

    ContactBankAccountHandler.normalizeIban(body);

    assertEquals(" es ", body.getString("ibanCountry"));
    assertEquals(" y ", body.getString("generateIban"));
  }

  /** The top level and the envelope both carry one, in different spellings: both are normalized. */
  @Test
  public void anIbanInTheBodyAndAnotherInDataAreBothNormalized() throws Exception {
    JSONObject data = new JSONObject().put("iban", " de89 3704 0044 0532 0130 00 ");
    JSONObject body = new JSONObject().put("IBAN", TYPED).put("data", data);

    ContactBankAccountHandler.normalizeIban(body);

    assertEquals(NORMALIZED, body.getString("IBAN"));
    assertEquals("DE89370400440532013000", data.getString("iban"));
    assertFalse(body.has(IBAN));
    assertFalse(data.has(IBAN));
  }

  /** A {@code data} that is not an object is not an envelope: the top level is still normalized. */
  @Test
  public void aDataThatIsNotAnObjectLeavesTheTopLevelNormalized() throws Exception {
    JSONObject body = new JSONObject().put(IBAN, TYPED).put("data", "free text");

    ContactBankAccountHandler.normalizeIban(body);

    assertEquals(NORMALIZED, body.getString(IBAN));
    assertEquals("free text", body.getString("data"));
  }

  // ---------------------------------------------------------------- handle

  @Test
  public void everyCrudWriteNormalizesTheIbanAndContinues() throws Exception {
    for (String method : new String[] { "POST", "PUT", "PATCH" }) {
      JSONObject body = new JSONObject().put(IBAN, TYPED);

      assertNull(method, handler.handle(context(method, NeoEndpointType.CRUD, body)));
      assertEquals(method, NORMALIZED, body.getString(IBAN));
    }
  }

  @Test
  public void aCrudWriteNormalizesTheEnvelopeAndTheBody() throws Exception {
    JSONObject data = new JSONObject().put("Iban", TYPED);
    JSONObject body = new JSONObject().put("data", data);

    assertNull(handler.handle(context("PATCH", NeoEndpointType.CRUD, body)));
    assertEquals(NORMALIZED, data.getString("Iban"));
  }

  @Test
  public void aReadLeavesTheEnvelopeAlone() throws Exception {
    JSONObject data = new JSONObject().put(IBAN, TYPED);

    assertNull(handler.handle(context("GET", NeoEndpointType.CRUD,
        new JSONObject().put("data", data))));
    assertEquals(TYPED, data.getString(IBAN));
  }

  @Test
  public void aWriteWithoutEndpointTypeIsACrudWrite() throws Exception {
    JSONObject body = new JSONObject().put(IBAN, TYPED);

    assertNull(handler.handle(context("PATCH", null, body)));
    assertEquals(NORMALIZED, body.getString(IBAN));
  }

  @Test
  public void readsDeletesAndOtherEndpointsAreUntouched() throws Exception {
    for (String method : new String[] { "GET", "DELETE" }) {
      JSONObject body = new JSONObject().put(IBAN, TYPED);
      assertNull(handler.handle(context(method, NeoEndpointType.CRUD, body)));
      assertEquals(method, TYPED, body.getString(IBAN));
    }
    for (NeoEndpointType type : new NeoEndpointType[] { NeoEndpointType.DEFAULTS,
        NeoEndpointType.CALLOUT, NeoEndpointType.SELECTOR, NeoEndpointType.ACTION }) {
      JSONObject body = new JSONObject().put(IBAN, TYPED);
      assertNull(handler.handle(context("POST", type, body)));
      assertEquals(type.name(), TYPED, body.getString(IBAN));
    }
  }

  @Test
  public void aWriteWithoutBodyContinues() {
    assertNull(handler.handle(context("POST", NeoEndpointType.CRUD, null)));
  }

  /** The body is rewritten in place: what CRUD then reads is the request body itself. */
  @Test
  public void theRequestBodyItselfIsRewritten() throws Exception {
    JSONObject body = new JSONObject().put(IBAN, TYPED);
    NeoContext context = context("POST", NeoEndpointType.CRUD, body);

    handler.handle(context);

    assertSame(body, context.getRequestBody());
    assertEquals(NORMALIZED, context.getRequestBody().getString(IBAN));
  }

  private static NeoContext context(String method, NeoEndpointType type, JSONObject body) {
    return NeoContext.builder()
        .specName("contacts")
        .entityName("bankAccount")
        .httpMethod(method)
        .endpointType(type)
        .requestBody(body)
        .build();
  }
}
