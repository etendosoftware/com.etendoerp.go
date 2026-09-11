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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

/**
 * Unit tests for the {@code beforeDelete} DELETE pre-hook added to
 * {@link AbstractSmartDeactivationHandler} by ETP-5117.
 *
 * <p>The hook exists because a genuine HTTP {@code DELETE} never reaches
 * {@link AbstractSmartDeactivationHandler#smartDeactivate} — it falls straight through to NEO's
 * default hard-delete CRUD — so any cleanup that needs the record's <em>own</em> data (its
 * client/organization) has to run while the record still exists. Doing it in {@code afterHandle}
 * left only the session context to guess from, and a GO client-admin session reports organization
 * {@code '0'} (the {@code '*'} org), never the record's business organization.
 *
 * <p>The critical guarantee tested here is that the hook is <b>opt-in</b>: a subclass that does not
 * override {@code beforeDelete} keeps its previous DELETE behavior exactly — no {@code
 * smartDeactivate} call, {@code null} returned so default CRUD proceeds. That is what protects
 * {@code VerifactuConfigReadyHandler}, the third subclass, which needs no DELETE cleanup at all.
 */
public class AbstractSmartDeactivationHandlerTest {

  private static final String RECORD_ID = "record-001";

  /**
   * A subclass with NO {@code beforeDelete} override — the {@code VerifactuConfigReadyHandler}
   * shape. Records whether {@code smartDeactivate} was ever reached.
   */
  private static class PlainHandler extends AbstractSmartDeactivationHandler {
    private final List<String> smartDeactivateCalls = new ArrayList<>();

    @Override
    protected NeoResponse smartDeactivate(String recordId) {
      smartDeactivateCalls.add(recordId);
      return null;
    }
  }

  /** A subclass that DOES override {@code beforeDelete}, recording what it was handed. */
  private static class CleanupHandler extends AbstractSmartDeactivationHandler {
    private final List<String> beforeDeleteRecordIds = new ArrayList<>();
    private final List<NeoContext> beforeDeleteContexts = new ArrayList<>();
    private RuntimeException failure;

    @Override
    protected NeoResponse smartDeactivate(String recordId) {
      return null;
    }

    @Override
    protected void beforeDelete(NeoContext context, String recordId) {
      beforeDeleteRecordIds.add(recordId);
      beforeDeleteContexts.add(context);
      if (failure != null) {
        throw failure;
      }
    }
  }

  // ─── the opt-in guarantee: a subclass without the override is unaffected ─────

  /**
   * ETP-5117 must be a no-op for {@code VerifactuConfigReadyHandler} and any other subclass that
   * does not opt in: a DELETE still returns {@code null} (default CRUD proceeds) and never reaches
   * {@code smartDeactivate}, which is PUT-only.
   */
  @Test
  public void deleteIsUnchangedForASubclassThatDoesNotOverrideBeforeDelete() {
    PlainHandler handler = new PlainHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(anyBoolean())).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .build()));

      assertTrue("smartDeactivate must never run for a DELETE",
          handler.smartDeactivateCalls.isEmpty());
    }
  }

  /** The default {@code beforeDelete} body is a no-op and must not throw. */
  @Test
  public void defaultBeforeDeleteIsASilentNoOp() {
    PlainHandler handler = new PlainHandler();
    handler.beforeDelete(NeoContext.builder().httpMethod("DELETE").recordId(RECORD_ID).build(),
        RECORD_ID);
  }

  // ─── the hook itself ─────────────────────────────────────────────────────────

  /**
   * An overriding subclass gets {@code beforeDelete} invoked exactly once, with the record id from
   * the URL and the live {@link NeoContext}, wrapped in admin mode (the record may not be visible
   * to the acting role's own organization) — and {@code handle} still returns {@code null} so the
   * default CRUD performs the delete.
   */
  @Test
  public void deleteInvokesBeforeDeleteUnderAdminModeAndStillReturnsNull() {
    CleanupHandler handler = new CleanupHandler();
    NeoContext ctx = NeoContext.builder().httpMethod("DELETE").recordId(RECORD_ID).build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(anyBoolean())).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.handle(ctx));

      assertEquals(1, handler.beforeDeleteRecordIds.size());
      assertEquals(RECORD_ID, handler.beforeDeleteRecordIds.get(0));
      assertSame(ctx, handler.beforeDeleteContexts.get(0));
      obCtxMock.verify(() -> OBContext.setAdminMode(true), times(1));
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /** Lower-case {@code delete} is the same method — the dispatch is case-insensitive. */
  @Test
  public void deleteDispatchIsCaseInsensitive() {
    CleanupHandler handler = new CleanupHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(anyBoolean())).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("delete")
          .recordId(RECORD_ID)
          .build()));

      assertEquals(1, handler.beforeDeleteRecordIds.size());
    }
  }

  /** A blank or absent record id short-circuits before admin mode and before the hook. */
  @Test
  public void deleteWithBlankRecordIdSkipsTheHookEntirely() {
    CleanupHandler handler = new CleanupHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId("   ")
          .build()));
      assertNull(handler.handle(NeoContext.builder().httpMethod("DELETE").build()));

      assertTrue(handler.beforeDeleteRecordIds.isEmpty());
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  /**
   * The hook is a cleanup side effect: a failure inside it is swallowed and logged, admin mode is
   * still restored, and {@code handle} still returns {@code null} so the delete the user asked for
   * is not blocked.
   */
  @Test
  public void deleteSwallowsBeforeDeleteFailureAndStillLetsTheDeleteProceed() {
    CleanupHandler handler = new CleanupHandler();
    handler.failure = new RuntimeException("cleanup exploded");

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(anyBoolean())).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .build()));

      assertEquals(1, handler.beforeDeleteRecordIds.size());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── the DELETE branch must not disturb the PUT skeleton ─────────────────────

  /** A deactivating PUT still routes to {@code smartDeactivate} and never to {@code beforeDelete}. */
  @Test
  public void deactivatingPutStillRoutesToSmartDeactivateAndNeverToBeforeDelete() throws Exception {
    CleanupHandler handler = new CleanupHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(anyBoolean())).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build()));

      assertTrue(handler.beforeDeleteRecordIds.isEmpty());
    }
  }

  /** Every other method still short-circuits without touching either hook. */
  @Test
  public void otherMethodsTouchNeitherHook() {
    CleanupHandler handler = new CleanupHandler();
    PlainHandler plain = new PlainHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      for (String method : new String[] { "GET", "POST", "PATCH" }) {
        assertNull(handler.handle(NeoContext.builder()
            .httpMethod(method)
            .recordId(RECORD_ID)
            .build()));
        assertNull(plain.handle(NeoContext.builder()
            .httpMethod(method)
            .recordId(RECORD_ID)
            .build()));
      }

      assertTrue(handler.beforeDeleteRecordIds.isEmpty());
      assertTrue(plain.smartDeactivateCalls.isEmpty());
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  // ─── isExplicitlyDeactivating stays untouched by the DELETE branch ───────────

  @Test
  public void isExplicitlyDeactivatingStillOnlyMatchesAnExplicitFalse() throws Exception {
    assertFalse(AbstractSmartDeactivationHandler.isExplicitlyDeactivating(null));
    assertFalse(AbstractSmartDeactivationHandler.isExplicitlyDeactivating(new JSONObject()));
    assertFalse(AbstractSmartDeactivationHandler
        .isExplicitlyDeactivating(new JSONObject().put("active", true)));
    assertTrue(AbstractSmartDeactivationHandler
        .isExplicitlyDeactivating(new JSONObject().put("active", false)));
    assertTrue(AbstractSmartDeactivationHandler
        .isExplicitlyDeactivating(new JSONObject().put("active", "false")));
    assertTrue(AbstractSmartDeactivationHandler
        .isExplicitlyDeactivating(new JSONObject().put("active", "N")));
  }
}
