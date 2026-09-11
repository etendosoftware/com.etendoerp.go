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



package com.etendoerp.go.schemaforge.email.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * Unit tests for {@link EmailMessages}, the per-language copy catalog that replaces the hardcoded
 * ES/EN literals the contracts used to carry (ETP-5003).
 */
public class EmailMessagesTest {

  @Test
  public void resolvesSpanishAndEnglish() {
    assertEquals("Invitación para unirte a ACME",
        EmailMessages.get("invitation.subject", "es_ES", "ACME"));
    assertEquals("Invitation to join ACME",
        EmailMessages.get("invitation.subject", "en_US", "ACME"));
  }

  @Test
  public void fallsBackToSpanishForAnUnsupportedLanguage() {
    // The language reaching a contract comes from the AD, which ships pt_BR, fr_FR and others.
    // Answering with the raw key would put "invitation.subject" in a customer's inbox.
    assertEquals("Invitación para unirte a ACME",
        EmailMessages.get("invitation.subject", "pt_BR", "ACME"));
  }

  @Test
  public void fallsBackToSpanishWhenNoLanguageIsGiven() {
    assertEquals("Invitación para unirte a ACME",
        EmailMessages.get("invitation.subject", null, "ACME"));
    assertEquals("Invitación para unirte a ACME",
        EmailMessages.get("invitation.subject", "   ", "ACME"));
  }

  @Test
  public void doesNotLetTheHostLocaleDecideTheFallback() {
    // getBundle consults the JVM default locale before giving up; a server running under en_US
    // would then answer a Portuguese request in English instead of the product's Spanish.
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.US);
      assertEquals("Invitación para unirte a ACME",
          EmailMessages.get("invitation.subject", "pt_BR", "ACME"));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  public void returnsTheKeyRatherThanFailingOnAnUnknownMessage() {
    assertEquals("no.such.key", EmailMessages.get("no.such.key", "es_ES"));
  }

  @Test
  public void parsesEtendoLanguageCodes() {
    assertEquals(new Locale("es", "ES"), EmailMessages.toLocale("es_ES"));
    assertEquals(new Locale("en", "US"), EmailMessages.toLocale("en-US"));
    assertEquals(new Locale("es"), EmailMessages.toLocale("es"));
    assertEquals(new Locale("es", "ES"), EmailMessages.toLocale(null));
  }

  @Test
  public void statesTheValidityWindowItIsGiven() {
    // The window belongs to CompanyInvitationService, not to the copy: a literal here is how the
    // email came to promise 24 hours for a token that lives seven days.
    assertEquals("Este enlace es personal y seguro. Tiene una validez de 7 días.",
        EmailMessages.get("invitation.note.expiry", "es_ES", 7L));
    assertEquals("This link is personal and secure. It is valid for 7 days.",
        EmailMessages.get("invitation.note.expiry", "en_US", 7L));
  }

  @Test
  public void keepsBothCatalogsInSync() {
    // A key added to one language and forgotten in the other silently ships the Spanish string to
    // an English recipient. Cheaper to catch here than in an inbox.
    java.util.ResourceBundle es = java.util.ResourceBundle.getBundle(
        "com.etendoerp.go.schemaforge.email.render.messages.emails", new Locale("es", "ES"));
    java.util.ResourceBundle en = java.util.ResourceBundle.getBundle(
        "com.etendoerp.go.schemaforge.email.render.messages.emails", new Locale("en", "US"));

    assertTrue("keys missing from en_US: " + difference(es, en), difference(es, en).isEmpty());
    assertTrue("keys missing from es_ES: " + difference(en, es), difference(en, es).isEmpty());
  }

  private static java.util.Set<String> difference(java.util.ResourceBundle from,
      java.util.ResourceBundle to) {
    java.util.Set<String> missing = new java.util.TreeSet<>(from.keySet());
    missing.removeAll(to.keySet());
    return missing;
  }
}
