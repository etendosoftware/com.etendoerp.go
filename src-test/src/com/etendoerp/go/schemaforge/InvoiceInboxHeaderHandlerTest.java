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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Modifier;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * The feature-flag gate and the {@code discard}, {@code review}, {@code apply}, {@code mark-reviewed} and {@code confirm} dispatch of the invoice inbox header customization.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxHeaderHandler
 */
class InvoiceInboxHeaderHandlerTest {

  private MockedStatic<InvoiceInboxFlag> flag;
  private MockedStatic<InvoiceInboxDiscard> discard;
  private MockedStatic<InvoiceInboxReview> review;
  private MockedStatic<InvoiceInboxApply> apply;
  private MockedStatic<InvoiceInboxMarkReviewed> markReviewed;
  private MockedStatic<InvoiceInboxConfirm> confirm;
  private MockedStatic<OBMessageUtils> messages;
  private final InvoiceInboxHeaderHandler handler = new InvoiceInboxHeaderHandler();

  @BeforeEach
  void openStaticMocks() {
    flag = mockStatic(InvoiceInboxFlag.class);
    discard = mockStatic(InvoiceInboxDiscard.class);
    review = mockStatic(InvoiceInboxReview.class);
    apply = mockStatic(InvoiceInboxApply.class);
    markReviewed = mockStatic(InvoiceInboxMarkReviewed.class);
    confirm = mockStatic(InvoiceInboxConfirm.class);
    // Unstubbed messageBD answers null: the English default is used.
    messages = mockStatic(OBMessageUtils.class);
  }

  @AfterEach
  void closeStaticMocks() {
    messages.close();
    confirm.close();
    markReviewed.close();
    apply.close();
    review.close();
    discard.close();
    flag.close();
  }

  private static NeoContext ctx(String method, NeoEndpointType type, String id, String field) {
    return NeoContext.builder().specName("invoice-inbox").entityName("header")
        .httpMethod(method).endpointType(type).recordId(id).fieldName(field).build();
  }

  private void flag(boolean on) {
    flag.when(InvoiceInboxFlag::isEnabled).thenReturn(on);
  }

  private static void assertDisabled(NeoResponse response) throws Exception {
    NeoResponse expected = InvoiceInboxUploadEndpoint.featureDisabled();
    assertNotNull(response);
    assertEquals(403, response.getHttpStatus());
    assertEquals("feature_disabled", response.getBody().getJSONObject("error").getString("code"));
    assertEquals(expected.getBody().toString(), response.getBody().toString());
  }

  // ---------------------------------------------------------------- flag off

  @Test
  void flagOffRefusesTheListRead() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("GET", NeoEndpointType.CRUD, null, null)));
  }

  @Test
  void flagOffRefusesTheReadById() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("GET", NeoEndpointType.CRUD, "ID-1", null)));
  }

  @Test
  void flagOffRefusesTheCrudWrites() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("POST", NeoEndpointType.CRUD, null, null)));
    assertDisabled(handler.handle(ctx("PUT", NeoEndpointType.CRUD, "ID-1", null)));
    assertDisabled(handler.handle(ctx("DELETE", NeoEndpointType.CRUD, "ID-1", null)));
  }

  @Test
  void flagOffRefusesTheDiscardActionAndNeverRunsIt() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "discard")));

    discard.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- flag on

  @Test
  void flagOnLetsReadAndCrudContinueToTheDefaultHandling() {
    flag(true);

    assertNull(handler.handle(ctx("GET", NeoEndpointType.CRUD, null, null)));
    assertNull(handler.handle(ctx("GET", NeoEndpointType.CRUD, "ID-1", null)));
    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, null, null)));
    discard.verifyNoInteractions();
  }

  @Test
  void flagOnLetsAnotherActionContinue() {
    flag(true);

    assertNull(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "something-else")));
    assertNull(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", null)));
    discard.verifyNoInteractions();
  }

  @Test
  void flagOnPostDiscardDelegatesWithTheRecordIdAndReturnsItsAnswer() {
    flag(true);
    NeoResponse answer = NeoResponse.error(409, "x");
    discard.when(() -> InvoiceInboxDiscard.discard("ID-1")).thenReturn(answer);

    NeoResponse response = handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "discard"));

    assertSame(answer, response);
    discard.verify(() -> InvoiceInboxDiscard.discard("ID-1"));
  }

  @Test
  void aGetOnDiscardIs405AndDoesNotDiscard() {
    flag(true);

    NeoResponse response = handler.handle(ctx("GET", NeoEndpointType.ACTION, "ID-1", "discard"));

    assertEquals(405, response.getHttpStatus());
    discard.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- review

  @Test
  void flagOffRefusesTheReviewBeforeAnythingElseAndNeverRunsIt() throws Exception {
    flag(false);

    assertDisabled(handler.handle(ctx("GET", NeoEndpointType.ACTION, "ID-1", "review")));
    assertDisabled(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "review")));

    review.verify(() -> InvoiceInboxReview.review(org.mockito.ArgumentMatchers.anyString()),
        never());
  }

  @Test
  void flagOnGetReviewDelegatesWithTheRecordIdAndReturnsItsAnswer() {
    flag(true);
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    review.when(() -> InvoiceInboxReview.review("ID-1")).thenReturn(answer);

    NeoResponse response = handler.handle(ctx("GET", NeoEndpointType.ACTION, "ID-1", "review"));

    assertSame(answer, response);
    review.verify(() -> InvoiceInboxReview.review("ID-1"));
    discard.verifyNoInteractions();
  }

  @Test
  void aPostOnReviewIs405AndNeitherReviewsNorDiscards() {
    flag(true);

    NeoResponse response = handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "review"));

    assertEquals(405, response.getHttpStatus());
    review.verify(() -> InvoiceInboxReview.review(org.mockito.ArgumentMatchers.anyString()),
        never());
    discard.verifyNoInteractions();
  }

  @Test
  void aPostOnDiscardNeverTouchesTheReview() {
    flag(true);
    discard.when(() -> InvoiceInboxDiscard.discard("ID-1")).thenReturn(NeoResponse.error(409, "x"));

    handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "discard"));

    review.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- apply and mark-reviewed (ETP-6050)

  private static NeoContext post(String field, org.codehaus.jettison.json.JSONObject body) {
    return NeoContext.builder().specName("invoice-inbox").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).recordId("ID-1")
        .fieldName(field).requestBody(body).build();
  }

  @Test
  void flagOffRefusesApplyAndMarkReviewedBeforeAnythingElse() throws Exception {
    flag(false);

    assertDisabled(handler.handle(post("apply", new org.codehaus.jettison.json.JSONObject())));
    assertDisabled(handler.handle(post("mark-reviewed",
        new org.codehaus.jettison.json.JSONObject())));

    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
  }

  @Test
  void aPostOnApplyDelegatesWithTheRecordIdAndTheBodyAndReturnsItsAnswer() throws Exception {
    flag(true);
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("updated", "T");
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    apply.when(() -> InvoiceInboxApply.apply("ID-1", body)).thenReturn(answer);

    assertSame(answer, handler.handle(post("apply", body)));

    apply.verify(() -> InvoiceInboxApply.apply("ID-1", body));
    markReviewed.verifyNoInteractions();
    discard.verifyNoInteractions();
    review.verifyNoInteractions();
  }

  @Test
  void aPostOnMarkReviewedDelegatesWithTheRecordIdAndTheBodyAndReturnsItsAnswer()
      throws Exception {
    flag(true);
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("updated", "T");
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    markReviewed.when(() -> InvoiceInboxMarkReviewed.markReviewed("ID-1", body))
        .thenReturn(answer);

    assertSame(answer, handler.handle(post("mark-reviewed", body)));

    markReviewed.verify(() -> InvoiceInboxMarkReviewed.markReviewed("ID-1", body));
    apply.verifyNoInteractions();
    discard.verifyNoInteractions();
    review.verifyNoInteractions();
  }

  @Test
  void applyAndMarkReviewedAreRefusedWithA405ForEveryVerbButPost() {
    flag(true);
    for (String method : new String[] { "GET", "PUT", "PATCH", "DELETE" }) {
      for (String action : new String[] { "apply", "mark-reviewed" }) {
        NeoResponse response = handler.handle(
            ctx(method, NeoEndpointType.ACTION, "ID-1", action));

        assertEquals(405, response.getHttpStatus(), method + " " + action);
      }
    }

    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
  }

  @Test
  void flagOnApplyAndMarkReviewedOnACrudEndpointContinueToTheDefaultHandling() {
    flag(true);

    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, "ID-1", "apply")));
    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, "ID-1", "mark-reviewed")));
    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- confirm

  @Test
  void flagOffRefusesConfirmAndNeverRunsIt() throws Exception {
    flag(false);

    assertDisabled(handler.handle(post("confirm", new org.codehaus.jettison.json.JSONObject())));

    confirm.verifyNoInteractions();
  }

  @Test
  void aPostOnConfirmDelegatesWithTheRecordIdAndTheBodyAndReturnsItsAnswer() throws Exception {
    flag(true);
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("updated", "T");
    NeoResponse answer = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    confirm.when(() -> InvoiceInboxConfirm.confirm("ID-1", body)).thenReturn(answer);

    assertSame(answer, handler.handle(post("confirm", body)));

    confirm.verify(() -> InvoiceInboxConfirm.confirm("ID-1", body));
    apply.verifyNoInteractions();
    markReviewed.verifyNoInteractions();
    discard.verifyNoInteractions();
    review.verifyNoInteractions();
  }

  @Test
  void confirmIsRefusedWithA405ForEveryVerbButPost() {
    flag(true);
    for (String method : new String[] { "GET", "PUT", "PATCH", "DELETE" }) {
      NeoResponse response = handler.handle(ctx(method, NeoEndpointType.ACTION, "ID-1", "confirm"));

      assertEquals(405, response.getHttpStatus(), method);
    }

    confirm.verifyNoInteractions();
  }

  @Test
  void confirmOnACrudEndpointContinuesToTheDefaultHandling() {
    flag(true);

    assertNull(handler.handle(ctx("POST", NeoEndpointType.CRUD, "ID-1", "confirm")));
    confirm.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- contract

  @Test
  void itServesActionsAndHidesTheInboxActionsFromAgents() {
    assertTrue(handler.servesActions());
    assertEquals(Set.of("discard", "review", "apply", "mark-reviewed", "confirm"),
        handler.agentExcludedActions());
    assertEquals(5, handler.agentExcludedActions().size());
  }

  @Test
  void itIsBoundByAnnotationAndNotByNamedOrANormalScope() {
    Class<InvoiceInboxHeaderHandler> type = InvoiceInboxHeaderHandler.class;
    NeoExtension binding = type.getAnnotation(NeoExtension.class);

    assertNotNull(binding);
    assertEquals("invoice-inbox", binding.spec());
    assertEquals("header", binding.entity());
    assertNull(type.getAnnotation(Named.class));
    assertNull(type.getAnnotation(ApplicationScoped.class));
    assertTrue(Modifier.isPublic(type.getModifiers()));
  }
}
