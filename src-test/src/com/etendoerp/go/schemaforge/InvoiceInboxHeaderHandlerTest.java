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
 * The feature-flag gate and the {@code discard} dispatch of the invoice inbox header customization.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxHeaderHandler
 */
class InvoiceInboxHeaderHandlerTest {

  private MockedStatic<InvoiceInboxFlag> flag;
  private MockedStatic<InvoiceInboxDiscard> discard;
  private MockedStatic<OBMessageUtils> messages;
  private final InvoiceInboxHeaderHandler handler = new InvoiceInboxHeaderHandler();

  @BeforeEach
  void openStaticMocks() {
    flag = mockStatic(InvoiceInboxFlag.class);
    discard = mockStatic(InvoiceInboxDiscard.class);
    // Unstubbed messageBD answers null: the English default is used.
    messages = mockStatic(OBMessageUtils.class);
  }

  @AfterEach
  void closeStaticMocks() {
    messages.close();
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

    assertNull(handler.handle(ctx("POST", NeoEndpointType.ACTION, "ID-1", "confirm")));
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

  // ---------------------------------------------------------------- contract

  @Test
  void itServesActionsAndHidesDiscardFromAgents() {
    assertTrue(handler.servesActions());
    assertEquals(Set.of("discard"), handler.agentExcludedActions());
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
