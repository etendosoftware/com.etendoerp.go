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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;

/**
 * Unit tests for {@link NeoLanguage} — the shared request-language helper (ETP-4304).
 * Uses Mockito static mocking of {@link OBContext} / {@link OBDal}; no DB.
 */
class NeoLanguageTest {

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private OBDal obDalInstance;

  @BeforeEach
  void setUp() {
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    obDalInstance = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(obDalInstance);
  }

  @AfterEach
  void tearDown() {
    obDal.close();
    obContext.close();
  }

  /** Stub the AD_Language criteria so {@code uniqueResult()} returns {@code result}. */
  @SuppressWarnings("unchecked")
  private void stubLanguageQuery(Language result) {
    OBCriteria<Language> criteria = mock(OBCriteria.class);
    when(obDalInstance.createCriteria(Language.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(result);
  }

  @Test
  @DisplayName("resolveActive returns the active Language for a well-formed code")
  void resolveActiveReturnsLanguage() {
    Language es = mock(Language.class);
    stubLanguageQuery(es);

    assertSame(es, NeoLanguage.resolveActive("es_ES"));
    // Lookup runs in admin mode and always restores it.
    obContext.verify(() -> OBContext.setAdminMode(true));
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  @DisplayName("resolveActive returns null for null / malformed / browser-style codes without hitting the DB")
  void resolveActiveRejectsBadCodes() {
    assertNull(NeoLanguage.resolveActive(null));
    assertNull(NeoLanguage.resolveActive("english"));
    assertNull(NeoLanguage.resolveActive("es-ES"));      // browser style, not xx_YY
    assertNull(NeoLanguage.resolveActive("ES_es"));       // wrong casing
    // Short-circuits before any admin-mode/DB work.
    obContext.verify(() -> OBContext.setAdminMode(true), never());
    verify(obDalInstance, never()).createCriteria(any(Class.class));
  }

  @Test
  @DisplayName("resolveActive returns null when the language is unknown or inactive")
  void resolveActiveReturnsNullWhenNotFound() {
    stubLanguageQuery(null);
    assertNull(NeoLanguage.resolveActive("de_DE"));
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  @DisplayName("resolveActive swallows query errors and restores admin mode")
  void resolveActiveSwallowsErrors() {
    when(obDalInstance.createCriteria(Language.class)).thenThrow(new RuntimeException("boom"));
    assertNull(NeoLanguage.resolveActive("es_ES"));
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  @DisplayName("current / currentCode expose the OBContext language")
  void currentExposesContextLanguage() {
    OBContext ctx = mock(OBContext.class);
    Language es = mock(Language.class);
    when(es.getLanguage()).thenReturn("es_ES");
    when(ctx.getLanguage()).thenReturn(es);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);

    assertSame(es, NeoLanguage.current());
    assertEquals("es_ES", NeoLanguage.currentCode());
  }

  @Test
  @DisplayName("current / currentCode return null when there is no OBContext")
  void currentNullWhenNoContext() {
    obContext.when(OBContext::getOBContext).thenReturn(null);
    assertNull(NeoLanguage.current());
    assertNull(NeoLanguage.currentCode());
  }

  @Test
  @DisplayName("withLanguage sets the language, runs the body, and restores the previous one")
  void withLanguageAppliesAndRestores() {
    OBContext ctx = mock(OBContext.class);
    Language previous = mock(Language.class);
    Language target = mock(Language.class);
    when(ctx.getLanguage()).thenReturn(previous);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);

    String result = NeoLanguage.withLanguage(target, () -> "done");

    assertEquals("done", result);
    InOrder order = inOrder(ctx);
    order.verify(ctx).setLanguage(target);
    order.verify(ctx).setLanguage(previous);
  }

  @Test
  @DisplayName("withLanguage restores the previous language even when the body throws")
  void withLanguageRestoresOnException() {
    OBContext ctx = mock(OBContext.class);
    Language previous = mock(Language.class);
    Language target = mock(Language.class);
    when(ctx.getLanguage()).thenReturn(previous);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);

    try {
      NeoLanguage.withLanguage(target, () -> {
        throw new IllegalStateException("boom");
      });
    } catch (IllegalStateException ignored) {
      // expected
    }
    verify(ctx).setLanguage(previous);
  }

  @Test
  @DisplayName("withLanguage runs the body unchanged when lang is null")
  void withLanguageNullLangIsPassThrough() {
    Supplier<String> body = () -> "value";
    assertEquals("value", NeoLanguage.withLanguage(null, body));
    // No context interaction at all.
    obContext.verify(OBContext::getOBContext, never());
  }

  @Test
  @DisplayName("applyToContext sets the resolved language on the context in admin mode")
  void applyToContextAppliesLanguage() {
    Language es = mock(Language.class);
    stubLanguageQuery(es);
    OBContext ctx = mock(OBContext.class);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);

    NeoLanguage.applyToContext("es_ES");

    verify(ctx).setLanguage(es);
    // setLanguage must run inside admin mode, which is always restored.
    obContext.verify(() -> OBContext.setAdminMode(true), atLeastOnce());
    obContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }

  @Test
  @DisplayName("applyToContext swallows errors and still restores admin mode")
  void applyToContextSwallowsErrors() {
    Language es = mock(Language.class);
    stubLanguageQuery(es);
    OBContext ctx = mock(OBContext.class);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
    doThrow(new RuntimeException("boom")).when(ctx).setLanguage(es);

    NeoLanguage.applyToContext("es_ES"); // must not propagate
    obContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }

  @Test
  @DisplayName("applyToContext is a no-op for an invalid code (never touches the context language)")
  void applyToContextNoopForInvalidCode() {
    NeoLanguage.applyToContext("nope");
    obContext.verify(OBContext::getOBContext, never());
  }
}
