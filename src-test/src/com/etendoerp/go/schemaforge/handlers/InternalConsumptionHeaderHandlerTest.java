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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link InternalConsumptionHeaderHandler} (ETP-5445): the {@code post}/{@code unpost}
 * actions on the Internal Consumption header are delegated to {@link DocumentPostingService};
 * every other request returns {@code null} so default CRUD handling is unchanged.
 */
@ExtendWith(MockitoExtension.class)
class InternalConsumptionHeaderHandlerTest {

  @Mock
  private DocumentPostingService mockPostingService;

  @Mock
  private NeoContext mockContext;

  private InternalConsumptionHeaderHandler handler;

  @BeforeEach
  void setUp() {
    handler = new InternalConsumptionHeaderHandler();
  }

  /** The CDI qualifier must match ETGO_SF_ENTITY.JAVA_QUALIFIER = 'internal-consumption'. */
  @Test
  void testHandlerIsNamedInternalConsumption() {
    Named named = InternalConsumptionHeaderHandler.class.getAnnotation(Named.class);
    assertNotNull(named);
    assertEquals("internal-consumption", named.value());
  }

  /** A post action is delegated to the posting service and its response returned as-is. */
  @Test
  void testHandleDelegatesPostActionToPostingService() throws Exception {
    NeoResponse sentinel = NeoResponse.ok(new JSONObject().put("success", true));
    when(mockPostingService.handleAction(mockContext)).thenReturn(sentinel);
    handler.setPostingService(mockPostingService);

    NeoResponse response = handler.handle(mockContext);

    assertSame(sentinel, response);
    verify(mockPostingService).handleAction(mockContext);
  }

  /** An unpost action is delegated the same way; a 422 failure body is propagated unchanged. */
  @Test
  void testHandleDelegatesUnpostActionToPostingService() throws Exception {
    NeoResponse sentinel = NeoResponse.error(422, new JSONObject().put("success", false));
    when(mockPostingService.handleAction(mockContext)).thenReturn(sentinel);
    handler.setPostingService(mockPostingService);

    NeoResponse response = handler.handle(mockContext);

    assertSame(sentinel, response);
    assertEquals(422, response.getHttpStatus());
    verify(mockPostingService).handleAction(mockContext);
  }

  /** When the posting service does not handle the request, the handler returns null (default CRUD). */
  @Test
  void testHandleReturnsNullWhenPostingServiceDoesNotHandleRequest() {
    when(mockPostingService.handleAction(mockContext)).thenReturn(null);
    handler.setPostingService(mockPostingService);

    assertNull(handler.handle(mockContext));
    verify(mockPostingService).handleAction(mockContext);
  }

  /**
   * With the real {@link DocumentPostingService}, an ACTION other than post/unpost falls through
   * to {@code null} without touching the DAL or the accounting engine.
   */
  @Test
  void testHandleReturnsNullForOtherActionWithRealPostingService() {
    when(mockContext.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(mockContext.getFieldName()).thenReturn("processConsumption");
    handler.setPostingService(new DocumentPostingService());

    assertNull(handler.handle(mockContext));
  }

  /** With the real {@link DocumentPostingService}, a plain CRUD request returns null. */
  @Test
  void testHandleReturnsNullForCrudRequestWithRealPostingService() {
    when(mockContext.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    handler.setPostingService(new DocumentPostingService());

    assertNull(handler.handle(mockContext));
  }

  /** Without an injected posting service (no CDI), handle() is a safe no-op. */
  @Test
  void testHandleReturnsNullWhenPostingServiceIsNotInjected() {
    assertNull(handler.handle(mockContext));
    verifyNoInteractions(mockContext);
  }

  /** afterHandle() never alters the default response. */
  @Test
  void testAfterHandleReturnsNull() {
    handler.setPostingService(mockPostingService);

    assertNull(handler.afterHandle(mockContext));
    verifyNoInteractions(mockPostingService);
  }
}
