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
 *
 * <p>ETP-6158: the request-language capture ({@code captureRequestCode} / {@code requestCode} /
 * {@code restoreRequestCode}) that the Classic message remap answers in.
 *
 * @covers com.etendoerp.go.schemaforge.util.NeoLanguage
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
    // the captured request language is a ThreadLocal: never leak it into the next test
    NeoLanguage.restoreRequestCode(null);
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

  /**
   * Without the DAL, {@code setAdminMode} pushes the mode and then throws: the finally must pop
   * it anyway, or the admin stack stays dirty for the rest of the thread (ETP-6069).
   */
  @Test
  @DisplayName("resolveActive restores admin mode even when setAdminMode itself throws")
  void resolveActiveRestoresWhenSetAdminModeThrows() {
    obContext.when(() -> OBContext.setAdminMode(true)).thenThrow(new RuntimeException("no DAL"));

    assertNull(NeoLanguage.resolveActive("es_ES"));
    obContext.verify(OBContext::restorePreviousMode);
    verify(obDalInstance, never()).createCriteria(Language.class);
  }

  /** Same contract as {@link #resolveActiveRestoresWhenSetAdminModeThrows()}. */
  @Test
  @DisplayName("applyToContext restores admin mode even when setAdminMode itself throws")
  void applyToContextRestoresWhenSetAdminModeThrows() {
    obContext.when(() -> OBContext.setAdminMode(true)).thenThrow(new RuntimeException("no DAL"));

    NeoLanguage.applyToContext("es_ES"); // must not propagate
    obContext.verify(OBContext::restorePreviousMode);
    obContext.verify(OBContext::getOBContext, never());
  }

  @Test
  @DisplayName("applyToContext is a no-op for an invalid code (never touches the context language)")
  void applyToContextNoopForInvalidCode() {
    NeoLanguage.applyToContext("nope");
    obContext.verify(OBContext::getOBContext, never());
  }

  // ── request-language capture (ETP-6158) ─────────────────────────────────────────────────────

  /** A mutable OBContext language, as a Classic call that switches it mid-request leaves it. */
  private OBContext contextIn(String code) {
    OBContext ctx = mock(OBContext.class);
    switchLanguage(ctx, code);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
    return ctx;
  }

  private static void switchLanguage(OBContext ctx, String code) {
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn(code);
    when(ctx.getLanguage()).thenReturn(language);
  }

  @Test
  @DisplayName("requestCode keeps the captured language when the OBContext language changes")
  void requestCodeKeepsTheCapturedLanguage() {
    OBContext ctx = contextIn("es_ES");

    String previous = NeoLanguage.captureRequestCode();
    switchLanguage(ctx, "en_US");

    assertNull(previous);
    assertEquals("es_ES", NeoLanguage.requestCode());
    assertEquals("en_US", NeoLanguage.currentCode());
  }

  @Test
  @DisplayName("requestCode falls back to currentCode when nothing was captured")
  void requestCodeFallsBackToCurrentCode() {
    contextIn("en_US");

    assertEquals("en_US", NeoLanguage.requestCode());

    obContext.when(OBContext::getOBContext).thenReturn(null);
    assertNull(NeoLanguage.requestCode());
  }

  @Test
  @DisplayName("a restored capture does not leak into the next request on a pooled thread")
  void aRestoredCaptureDoesNotLeakIntoTheNextRequest() {
    // request 1 on this (pooled) thread
    OBContext ctx = contextIn("es_ES");
    String previous = NeoLanguage.captureRequestCode();
    NeoLanguage.restoreRequestCode(previous);

    // request 2 on the same thread, another user's language, nothing captured yet
    switchLanguage(ctx, "en_US");

    assertEquals("en_US", NeoLanguage.requestCode());
  }

  @Test
  @DisplayName("a nested capture hands back the outer one, and restore reinstates it")
  void aNestedCaptureRestoresTheOuterOne() {
    OBContext ctx = contextIn("es_ES");
    String outerPrevious = NeoLanguage.captureRequestCode();
    switchLanguage(ctx, "en_US");

    String innerPrevious = NeoLanguage.captureRequestCode();
    assertEquals("en_US", NeoLanguage.requestCode());
    NeoLanguage.restoreRequestCode(innerPrevious);

    assertEquals("es_ES", innerPrevious);
    assertEquals("es_ES", NeoLanguage.requestCode());
    NeoLanguage.restoreRequestCode(outerPrevious);
    assertEquals("en_US", NeoLanguage.requestCode());
  }

  @Test
  @DisplayName("capturing with no language (or a failing context) captures nothing")
  void capturingWithNoLanguageCapturesNothing() {
    OBContext ctx = contextIn("es_ES");
    NeoLanguage.captureRequestCode();
    obContext.when(OBContext::getOBContext).thenThrow(new IllegalStateException("no context"));

    String previous = NeoLanguage.captureRequestCode();

    assertEquals("es_ES", previous);
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
    switchLanguage(ctx, "en_US");
    assertEquals("en_US", NeoLanguage.requestCode());
  }
}
