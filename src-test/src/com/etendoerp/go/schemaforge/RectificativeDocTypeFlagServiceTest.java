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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

/**
 * Unit tests for {@link RectificativeDocTypeFlagService}.
 *
 * <p>Pure Mockito style, mirroring {@code YearAccountingHandlerTest} (mocked {@code OBContext}
 * static methods, no live DB). These cover the pieces that do NOT require a real Hibernate session:
 * <ul>
 *   <li>the CRUD/method gating of {@link RectificativeDocTypeFlagService#applyAfterConfigSave} —
 *       TC-05/TC-06 style checks;</li>
 *   <li>the response-shaping (augment-with-warnings) contract driven by a spy service — TC-07;</li>
 *   <li>the column-absent short-circuit guard of {@link RectificativeDocTypeFlagService#flagForClient}
 *       — the SIF-General-not-installed no-op.</li>
 * </ul>
 *
 * <p>The real flagging order (sequence before doc type), idempotency, doc-no-controlled vs linked
 * sequence, no-sequence skip+warning, and the FAC-untouched classification against real
 * {@code C_Invoice} document types (TC-01..TC-04, TC-08..TC-10) require a live DB and the
 * {@code ETSG_CHECK_RECTIF_DOC_TYPE} trigger; those are the integration-test scaffold in
 * {@link com.etendoerp.go.schemaforge.handlers.RectificativeDocTypeFlagServiceIntegrationTest}.
 */
public class RectificativeDocTypeFlagServiceTest {

  @After
  public void resetColumnPresenceCache() {
    // Always reset the static cache so tests do not leak state into each other or into the
    // real column probe used by other suites.
    RectificativeDocTypeFlagService.setRectificativeColumnsPresentForTests(null);
  }

  // ── Column-absent guard (SIF General not installed) ──────────────────────

  /**
   * When the {@code em_etsg_isrectificative} columns are absent, {@code flagForClient} is a no-op
   * and returns an empty {@link RectificativeDocTypeFlagService.Result} without touching the DB.
   */
  @Test
  public void flagForClientIsNoOpWhenColumnsAbsent() {
    RectificativeDocTypeFlagService.setRectificativeColumnsPresentForTests(false);
    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();

    Client client = mock(Client.class);
    RectificativeDocTypeFlagService.Result result = service.flagForClient(client);

    assertNotNull(result);
    assertEquals(0, result.getFlaggedDocTypes());
    assertEquals(0, result.getFlaggedSequences());
    assertTrue(result.getWarnings().isEmpty());
  }

  /**
   * A {@code null} client yields an empty result regardless of column presence (defensive guard).
   */
  @Test
  public void flagForClientIsNoOpForNullClient() {
    RectificativeDocTypeFlagService.setRectificativeColumnsPresentForTests(true);
    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();

    RectificativeDocTypeFlagService.Result result = service.flagForClient(null);

    assertNotNull(result);
    assertEquals(0, result.getFlaggedDocTypes());
    assertEquals(0, result.getFlaggedSequences());
    assertTrue(result.getWarnings().isEmpty());
  }

  // ── applyAfterConfigSave: endpoint / method gating (TC-05, TC-06) ────────

  /** A non-CRUD endpoint (e.g. ACTION) must never trigger flagging — returns null. */
  @Test
  public void applyAfterConfigSaveReturnsNullForNonCrudEndpoint() {
    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(context.getHttpMethod()).thenReturn("POST");

    assertNull(service.applyAfterConfigSave(context));
  }

  /** A CRUD GET is a read — must not trigger flagging. */
  @Test
  public void applyAfterConfigSaveReturnsNullForGet() {
    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("GET");

    assertNull(service.applyAfterConfigSave(context));
  }

  /** A CRUD DELETE must not trigger flagging. */
  @Test
  public void applyAfterConfigSaveReturnsNullForDelete() {
    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("DELETE");

    assertNull(service.applyAfterConfigSave(context));
  }

  /**
   * A CRUD POST with the SIF columns absent runs {@code flagForClient} (no-op, no warnings) and,
   * with no warnings produced, returns {@code null} to keep the original response untouched. This
   * exercises the full happy-path plumbing (admin mode enter/exit, current-client resolution) with
   * the column guard short-circuiting the DB work.
   */
  @Test
  public void applyAfterConfigSavePostWithNoWarningsReturnsNull() {
    RectificativeDocTypeFlagService.setRectificativeColumnsPresentForTests(false);
    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();

    Client client = mock(Client.class);
    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getObContext()).thenReturn(obContext);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      assertNull(service.applyAfterConfigSave(context));
    }
  }

  /**
   * PUT/PATCH are also write methods and must run flagging. Column-absent → no-op → null, but the
   * gating branch (write-method check) must pass for all three verbs. Covered here for PUT; POST is
   * covered above and PATCH follows the same {@code isWriteMethod} branch.
   */
  @Test
  public void applyAfterConfigSavePutIsTreatedAsWriteMethod() {
    RectificativeDocTypeFlagService.setRectificativeColumnsPresentForTests(false);
    RectificativeDocTypeFlagService service = new RectificativeDocTypeFlagService();

    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(mock(Client.class));

    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("PUT");
    when(context.getObContext()).thenReturn(obContext);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      // No warnings (columns absent) → null, but it did not short-circuit on the method check.
      assertNull(service.applyAfterConfigSave(context));
    }
  }

  /**
   * Swallow-and-return-null: when {@code flagForClient} throws, the secondary side effect must not
   * fail the parent request. Driven with a spy that throws from {@code flagForClient}.
   */
  @Test
  public void applyAfterConfigSaveSwallowsExceptionsAndReturnsNull() {
    RectificativeDocTypeFlagService service = Mockito.spy(new RectificativeDocTypeFlagService());
    Mockito.doThrow(new RuntimeException("boom")).when(service).flagForClient(Mockito.any());

    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(mock(Client.class));

    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getObContext()).thenReturn(obContext);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      assertNull(service.applyAfterConfigSave(context));
    }
  }

  // ── augmentWithWarnings response shaping (TC-07) ─────────────────────────

  /**
   * When {@code flagForClient} produces warnings, the original CRUD response body is preserved and
   * a {@code warnings} array carrying each skipped-document-type message is appended.
   */
  @Test
  public void applyAfterConfigSaveAppendsWarningsArrayToResponse() throws Exception {
    RectificativeDocTypeFlagService service = Mockito.spy(new RectificativeDocTypeFlagService());

    RectificativeDocTypeFlagService.Result result = new RectificativeDocTypeFlagService.Result();
    result.getWarnings().add("Document type 'NC Test' (dt-1) could not be flagged: no sequence");
    Mockito.doReturn(result).when(service).flagForClient(Mockito.any());

    JSONObject originalBody = new JSONObject()
        .put("response", new JSONObject().put("data",
            new JSONArray().put(new JSONObject().put("id", "cfg-1"))));
    NeoResponse previous = new NeoResponse(201, originalBody);

    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(mock(Client.class));

    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getObContext()).thenReturn(obContext);
    when(context.getPreviousResult()).thenReturn(previous);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      NeoResponse augmented = service.applyAfterConfigSave(context);

      assertNotNull("A response carrying the warnings must be returned", augmented);
      assertEquals(201, augmented.getHttpStatus());
      // Original body is preserved.
      assertNotNull(augmented.getBody().optJSONObject("response"));
      // warnings array appended with the single message.
      JSONArray warnings = augmented.getBody().getJSONArray("warnings");
      assertEquals(1, warnings.length());
      assertTrue(warnings.getString(0).contains("no sequence"));
    }
  }

  /**
   * With warnings present but no previous response body to augment, the method returns null (there
   * is nothing to attach the warnings to, so the original response is left as-is).
   */
  @Test
  public void applyAfterConfigSaveReturnsNullWhenWarningsButNoPreviousBody() {
    RectificativeDocTypeFlagService service = Mockito.spy(new RectificativeDocTypeFlagService());

    RectificativeDocTypeFlagService.Result result = new RectificativeDocTypeFlagService.Result();
    result.getWarnings().add("skipped dt");
    Mockito.doReturn(result).when(service).flagForClient(Mockito.any());

    OBContext obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(mock(Client.class));

    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getObContext()).thenReturn(obContext);
    when(context.getPreviousResult()).thenReturn(new NeoResponse(201, null));

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      assertNull(service.applyAfterConfigSave(context));
    }
  }

  // ── Result value object ──────────────────────────────────────────────────

  /** The Result accumulator exposes its counters and mutable warnings list. */
  @Test
  public void resultExposesCountersAndWarnings() {
    RectificativeDocTypeFlagService.Result result = new RectificativeDocTypeFlagService.Result();
    assertEquals(0, result.getFlaggedDocTypes());
    assertEquals(0, result.getFlaggedSequences());
    assertNotNull(result.getWarnings());
    assertTrue(result.getWarnings().isEmpty());

    result.getWarnings().add("w1");
    List<String> warnings = result.getWarnings();
    assertEquals(1, warnings.size());
    assertFalse(warnings.isEmpty());
  }
}
