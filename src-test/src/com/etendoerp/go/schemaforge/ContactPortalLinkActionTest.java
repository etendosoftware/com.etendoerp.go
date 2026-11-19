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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.portal.PortalContactShare;

/**
 * The contacts {@code portalLink} action (ETP-5850): what reaches the share service and how each
 * outcome is answered. The share's own rules are covered by {@code PortalContactShareTest}.
 *
 * @covers com.etendoerp.go.schemaforge.ContactPortalLinkAction
 */
class ContactPortalLinkActionTest {

  private static final String CONTACT_ID = "BP1";

  private static NeoContext action(String method, String action, String recordId) {
    return NeoContext.builder().specName("contacts").entityName("businessPartner")
        .httpMethod(method).endpointType(NeoEndpointType.ACTION).fieldName(action)
        .recordId(recordId).build();
  }

  private static PortalContactShare.Outcome outcome(PortalContactShare.State state) {
    PortalContactShare.Outcome outcome = mock(PortalContactShare.Outcome.class);
    when(outcome.getState()).thenReturn(state);
    return outcome;
  }

  private static NeoResponse run(NeoContext context, PortalContactShare.Outcome outcome) {
    try (MockedStatic<AgentActionSupport> access = mockStatic(AgentActionSupport.class)) {
      access.when(() -> AgentActionSupport.hasAccess(any(), eq(true))).thenReturn(true);
      return ContactPortalLinkAction.handle(context, id -> outcome);
    }
  }

  @Test
  @DisplayName("is declared as a write action, so it needs write access to Contacts")
  void declaredAsWrite() {
    assertTrue(ContactPortalLinkAction.CONTRACTS.get("portalLink").isMutating());
  }

  @Test
  @DisplayName("leaves a CRUD request and any other action to the handler")
  void ignoresOtherRequests() {
    Function<String, PortalContactShare.Outcome> share = id -> {
      throw new AssertionError("the share must not run");
    };
    assertNull(ContactPortalLinkAction.handle(NeoContext.builder().specName("contacts")
        .entityName("businessPartner").httpMethod("POST").build(), share));
    assertNull(ContactPortalLinkAction.handle(action("POST", "somethingElse", CONTACT_ID), share));
  }

  @Test
  @DisplayName("refuses GET with 405 and a role without write access with 403, minting nothing")
  void refusesWrongMethodAndNoAccess() {
    AtomicReference<String> asked = new AtomicReference<>();
    Function<String, PortalContactShare.Outcome> share = id -> {
      asked.set(id);
      return outcome(PortalContactShare.State.SHARED);
    };
    assertEquals(405, ContactPortalLinkAction.handle(action("GET", "portalLink", CONTACT_ID),
        share).getHttpStatus());
    try (MockedStatic<AgentActionSupport> access = mockStatic(AgentActionSupport.class)) {
      access.when(() -> AgentActionSupport.hasAccess(any(), eq(true))).thenReturn(false);
      assertEquals(403, ContactPortalLinkAction.handle(action("POST", "portalLink", CONTACT_ID),
          share).getHttpStatus());
    }
    assertNull(asked.get());
  }

  @Test
  @DisplayName("answers the link, its expiry, the entry document and the renewal email")
  void answersTheSharedLink() throws Exception {
    PortalContactShare.Outcome shared = outcome(PortalContactShare.State.SHARED);
    when(shared.getUrl()).thenReturn("https://app.example/portal/tok");
    when(shared.getExpiresAt()).thenReturn(Instant.parse("2026-11-20T10:00:00Z"));
    when(shared.getDocumentSpec()).thenReturn("sales-invoice");
    when(shared.getDocumentId()).thenReturn("INV1");
    when(shared.getDocumentNo()).thenReturn("FV-1");
    when(shared.getRecipient()).thenReturn("compras@cliente.test");

    NeoResponse response = run(action("POST", "portalLink", CONTACT_ID), shared);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals("https://app.example/portal/tok", body.getString("url"));
    assertEquals("2026-11-20T10:00:00Z", body.getString("expiresAt"));
    assertEquals("FV-1", body.getJSONObject("entryDocument").getString("documentNo"));
    assertEquals("compras@cliente.test", body.getString("renewalEmail"));
  }

  @Test
  @DisplayName("names why no link was minted, with the status of each refusal")
  void answersEachRefusal() throws Exception {
    assertRefusal(PortalContactShare.State.DISABLED, 403, "feature_disabled");
    assertRefusal(PortalContactShare.State.NOT_FOUND, 404, "not_found");
    assertRefusal(PortalContactShare.State.UNAVAILABLE, 409, "portal_unavailable");
    assertRefusal(PortalContactShare.State.NO_EMAIL, 409, "contact_without_email");
    assertRefusal(PortalContactShare.State.NO_DOCUMENTS, 409, "no_portal_documents");
    assertRefusal(PortalContactShare.State.NOT_CONFIGURED, 503, "portal_not_configured");
  }

  private static void assertRefusal(PortalContactShare.State state, int status, String code)
      throws Exception {
    NeoResponse response = run(action("POST", "portalLink", CONTACT_ID), outcome(state));
    assertEquals(status, response.getHttpStatus(), state.name());
    assertEquals(code, response.getBody().getString("error"), state.name());
  }
}
