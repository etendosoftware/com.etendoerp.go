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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.RejectReason;

/**
 * Unit tests for {@link CreateRejectReasonHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Dispatch fall-through (non-ACTION endpoint, wrong fieldName, non-POST method).</li>
 *   <li>Validation (null body, missing/blank/whitespace name).</li>
 *   <li>Happy path (persist with current client/org, active=true, searchKey=name; return 201 echo).</li>
 *   <li>Length truncation at 60 characters.</li>
 *   <li>Optional description forwarded only when non-blank.</li>
 * </ul>
 */
public class CreateRejectReasonHandlerTest {

  private static final String SPEC_SALES_QUOTATION = "sales-quotation";
  private static final String ENTITY_HEADER = "header";
  private static final String ACTION_CREATE = "createRejectReason";

  // ── handle() — dispatch ────────────────────────────────────────────────────

  /**
   * Non-ACTION endpoints fall through unchanged so the handler doesn't
   * accidentally hijack CRUD requests on the quotation entity.
   */
  @Test
  public void testNonActionEndpointReturnsNull() {
    assertNull(new CreateRejectReasonHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .fieldName(ACTION_CREATE).build()));
  }

  /**
   * Actions with a {@code fieldName} other than {@code createRejectReason}
   * are not for us — they belong to other handlers in the dispatch chain.
   */
  @Test
  public void testActionWithUnknownFieldReturnsNull() {
    assertNull(new CreateRejectReasonHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction").build()));
  }

  /**
   * The endpoint is POST-only. GET / PUT / DELETE on the same fieldName fall
   * through so a future read handler could share the action name.
   */
  @Test
  public void testNonPostMethodReturnsNull() {
    assertNull(new CreateRejectReasonHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE).build()));
  }

  // ── name validation ──────────────────────────────────────────────────────

  /**
   * A {@code null} request body must produce HTTP 400 with a message
   * mentioning "Name" — the React modal blocks empty submissions, so a
   * missing body always indicates misuse.
   */
  @Test
  public void testNullBodyReturns400() throws JSONException {
    NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("Name"));
  }

  /**
   * Empty JSON body ({@code {}}) is rejected: the {@code name} field is
   * mandatory because {@code C_Reject_Reason.Name} is NOT NULL.
   */
  @Test
  public void testMissingNameReturns400() throws JSONException {
    NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE)
        .requestBody(new JSONObject()).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  /**
   * An empty-string name is treated the same as missing — Etendo's check
   * constraint would reject it at the DB level, so we fail fast at the
   * boundary.
   */
  @Test
  public void testBlankNameReturns400() throws JSONException {
    NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE)
        .requestBody(new JSONObject().put("name", "")).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  /**
   * Whitespace-only names are stripped to empty by the trim, so they must
   * also be rejected before the persist step.
   */
  @Test
  public void testWhitespaceNameReturns400() throws JSONException {
    NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
        .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE)
        .requestBody(new JSONObject().put("name", "   ")).build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  // ── happy path ────────────────────────────────────────────────────────────

  /**
   * End-to-end persist path. Verifies every field the handler is responsible
   * for: client and organization come from the current OBContext, the row is
   * saved {@code active = true}, both {@code name} and {@code searchKey} are
   * populated, the (absent) description is NOT touched, and the saved row is
   * flushed before returning. The 201 response carries the new id and name
   * so the React typeahead can preselect without an extra round-trip.
   */
  @Test
  public void testHappyPathPersistsAndReturns201() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext obCtx = mock(OBContext.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(obCtx.getCurrentClient()).thenReturn(client);
      when(obCtx.getCurrentOrganization()).thenReturn(org);
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      RejectReason newReason = mock(RejectReason.class);
      when(provider.get(RejectReason.class)).thenReturn(newReason);
      when(newReason.getId()).thenReturn("rr-new");
      when(newReason.getName()).thenReturn("Price too high");

      NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE).recordId("q-host")
          .requestBody(new JSONObject().put("name", "Price too high")).build());

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());

      verify(newReason).setClient(client);
      verify(newReason).setOrganization(org);
      verify(newReason).setActive(true);
      verify(newReason).setName("Price too high");
      verify(newReason).setSearchKey("Price too high");
      verify(newReason, never()).setDescription(any());
      verify(dal).save(newReason);
      verify(dal).flush();

      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("rr-new", data.getString("id"));
      assertEquals("Price too high", data.getString("name"));
    }
  }

  /**
   * Surrounding whitespace on the {@code name} input is silently trimmed so
   * the persisted value is always clean. Both {@code name} and
   * {@code searchKey} get the trimmed string.
   */
  @Test
  public void testHappyPathTrimsName() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      when(obCtx.getCurrentOrganization()).thenReturn(mock(Organization.class));
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      RejectReason newReason = mock(RejectReason.class);
      when(provider.get(RejectReason.class)).thenReturn(newReason);
      when(newReason.getId()).thenReturn("rr-trim");
      when(newReason.getName()).thenReturn("Trimmed reason");

      NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .requestBody(new JSONObject().put("name", "  Trimmed reason  ")).build());

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(newReason).setName("Trimmed reason");
      verify(newReason).setSearchKey("Trimmed reason");
    }
  }

  /**
   * {@code C_Reject_Reason.Name} is a {@code VARCHAR2(60)}. The handler must
   * truncate longer strings instead of failing the insert with a DB error.
   * Uses {@link ArgumentCaptor} to verify the actual value passed to
   * {@code setName} is exactly 60 characters.
   */
  @Test
  public void testHappyPathTruncatesAt60Chars() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      when(obCtx.getCurrentOrganization()).thenReturn(mock(Organization.class));
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      RejectReason newReason = mock(RejectReason.class);
      when(provider.get(RejectReason.class)).thenReturn(newReason);
      when(newReason.getId()).thenReturn("rr-long");
      String original = "A".repeat(80);
      String expected = "A".repeat(60);
      when(newReason.getName()).thenReturn(expected);

      NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .requestBody(new JSONObject().put("name", original)).build());

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      verify(newReason).setName(nameCaptor.capture());
      assertEquals(60, nameCaptor.getValue().length());
      assertEquals(expected, nameCaptor.getValue());
    }
  }

  /**
   * When the body carries a non-blank {@code description}, it must be set on
   * the new row. This covers the optional copy/paste from the kebab modal
   * (today the React UI doesn't expose a description input, but the
   * endpoint contract supports it).
   */
  @Test
  public void testHappyPathForwardsDescriptionWhenPresent() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      when(obCtx.getCurrentOrganization()).thenReturn(mock(Organization.class));
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      RejectReason newReason = mock(RejectReason.class);
      when(provider.get(RejectReason.class)).thenReturn(newReason);
      when(newReason.getId()).thenReturn("rr-desc");
      when(newReason.getName()).thenReturn("With desc");

      NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .requestBody(new JSONObject()
              .put("name", "With desc")
              .put("description", "Customer rejected the offer")).build());

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(newReason).setDescription("Customer rejected the offer");
    }
  }

  /**
   * A whitespace-only {@code description} must NOT call {@code setDescription}
   * — leaving the column null is preferred over storing meaningless space.
   */
  @Test
  public void testHappyPathSkipsBlankDescription() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      when(obCtx.getCurrentOrganization()).thenReturn(mock(Organization.class));
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      RejectReason newReason = mock(RejectReason.class);
      when(provider.get(RejectReason.class)).thenReturn(newReason);
      when(newReason.getId()).thenReturn("rr-no-desc");
      when(newReason.getName()).thenReturn("No desc");

      NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .requestBody(new JSONObject()
              .put("name", "No desc")
              .put("description", "   ")).build());

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(newReason, never()).setDescription(any());
    }
  }

  // ── error envelope ────────────────────────────────────────────────────────

  /**
   * Unexpected runtime exceptions inside the persistence layer must be
   * surfaced as HTTP 500 with a generic message — the client must never see
   * the underlying stack-trace. Validation failures stay at 400 (those go
   * through {@link OBException}); only truly unexpected faults bubble up
   * as 500.
   */
  @Test
  public void testRuntimeExceptionReturns500() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      OBContext obCtx = mock(OBContext.class);
      when(obCtx.getCurrentClient()).thenReturn(mock(Client.class));
      when(obCtx.getCurrentOrganization()).thenReturn(mock(Organization.class));
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      RejectReason newReason = mock(RejectReason.class);
      when(provider.get(RejectReason.class)).thenReturn(newReason);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Mockito.doThrow(new RuntimeException("DB exploded")).when(dal).flush();

      NeoResponse r = new CreateRejectReasonHandler().handle(NeoContext.builder()
          .specName(SPEC_SALES_QUOTATION).entityName(ENTITY_HEADER)
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName(ACTION_CREATE)
          .requestBody(new JSONObject().put("name", "Boom")).build());
      assertNotNull(r);
      assertEquals(500, r.getHttpStatus());
    }
  }
}
