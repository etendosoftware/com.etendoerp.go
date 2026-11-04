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
package com.etendoerp.go.terms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.openbravo.base.structure.BaseOBObject;

import com.etendoerp.go.terms.TermsAndConditionsService.Store;
import com.etendoerp.go.terms.TermsAndConditionsService.Terms;

/**
 * @covers com.etendoerp.go.terms.TermsAndConditionsService
 */
class TermsAndConditionsServiceTest {

  private static final Terms CURRENT = new Terms("2026-11", "es_ES", "Texto", new Date(1000L));

  private final Store store = mock(Store.class);
  private final TermsAndConditionsService service = new TermsAndConditionsService(store);

  @Test
  void readsTheRequestedLanguageAndItsBaseLanguage() {
    when(store.findCurrent("es_AR", "es")).thenReturn(Optional.of(CURRENT));

    assertEquals(Optional.of(CURRENT), service.findCurrent("es_AR"));
  }

  @Test
  void acceptsTheBrowserSpellingOfALanguage() {
    when(store.findCurrent("es_ES", "es")).thenReturn(Optional.of(CURRENT));

    assertEquals(Optional.of(CURRENT), service.findCurrent("es-ES"));
  }

  @Test
  void aMissingOrMalformedLanguageReadsEnglish() {
    service.findCurrent(null);
    service.findCurrent("'; drop table etgo_terms; --");

    verify(store, org.mockito.Mockito.times(2)).findCurrent("en_US", "en");
  }

  @Test
  void keepsTheVersionTheScreenShowedWhenItExists() {
    when(store.exists("2026-10")).thenReturn(true);

    assertEquals("2026-10", service.resolveAcceptedVersion(" 2026-10 ", "es_ES"));
    verify(store, never()).findCurrent(anyString(), anyString());
  }

  @Test
  void replacesAnUnknownVersionWithTheOneInForce() {
    when(store.exists("made-up")).thenReturn(false);
    when(store.findCurrent("es_ES", "es")).thenReturn(Optional.of(CURRENT));

    assertEquals("2026-11", service.resolveAcceptedVersion("made-up", "es_ES"));
  }

  @Test
  void recordsTheVersionInForceWhenTheScreenSentNone() {
    when(store.findCurrent("en_US", "en")).thenReturn(Optional.of(CURRENT));

    assertEquals("2026-11", service.resolveAcceptedVersion(null, null));
    verify(store, never()).exists(anyString());
  }

  @Test
  void neverLooksUpAVersionLongerThanItsColumn() {
    when(store.findCurrent("en_US", "en")).thenReturn(Optional.of(CURRENT));

    assertEquals("2026-11", service.resolveAcceptedVersion("v".repeat(61), null));
    verify(store, never()).exists(anyString());
  }

  @Test
  void recordsNoVersionWhenNoneIsPublished() {
    when(store.findCurrent("en_US", "en")).thenReturn(Optional.empty());

    assertNull(service.resolveAcceptedVersion(null, null));
  }

  @Test
  void aFailingStoreNeverBlocksTheSignUp() {
    when(store.exists("2026-10")).thenThrow(new IllegalStateException("db down"));

    assertNull(service.resolveAcceptedVersion("2026-10", "es_ES"));
  }

  @Test
  void stampsTheVersionAndTheDateOnTheAccount() {
    BaseOBObject account = mock(BaseOBObject.class);
    Date at = new Date();

    TermsAndConditionsService.recordAcceptance(account, "2026-11", at);

    verify(account).set(TermsAndConditionsService.PROPERTY_TERMS_VERSION, "2026-11");
    verify(account).set(TermsAndConditionsService.PROPERTY_TERMS_ACCEPTED_AT, at);
  }

  @Test
  void stampsTheMarketingConsentOnTheAccount() {
    BaseOBObject account = mock(BaseOBObject.class);

    TermsAndConditionsService.recordMarketingConsent(account, true);

    verify(account).set(TermsAndConditionsService.PROPERTY_MARKETING_CONSENT, true);
  }

  @Test
  void thePublicBodyCarriesOnlyTheTextAndItsIdentity() throws Exception {
    JSONObject json = CURRENT.toJson();

    assertEquals("2026-11", json.getString("version"));
    assertEquals("es_ES", json.getString("language"));
    assertEquals("Texto", json.getString("content"));
    assertEquals(1000L, json.getLong("publishedAt"));
    assertEquals(4, json.length());
    assertTrue(json.has("publishedAt"));
  }
}
