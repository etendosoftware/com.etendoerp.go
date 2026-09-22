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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * ETP-5378 — posting delegation for {@link ReturnMaterialReceiptHeaderHandler}.
 *
 * <p>Both return windows own their {@code JAVA_QUALIFIER} slot, so the shared
 * {@code @Named("document-posting")} handler can never serve them. Before this delegation,
 * {@code post}/{@code unpost} fell through to the generic AD-button path, which looks for a button
 * column literally named {@code post}, finds none, and answers {@code 404 Action not found: post}
 * — the defect reported from the form view of both return windows.</p>
 *
 * <p>Mirrors {@link SalesInvoiceHeaderHandlerPostingTest}, the representative test for the same
 * one-line delegation on the six handlers that already had it.</p>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReturnMaterialReceiptHeaderHandlerPostingTest {

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(context)).thenReturn(sentinel);

    ReturnMaterialReceiptHeaderHandler handler = new ReturnMaterialReceiptHeaderHandler();
    handler.setPostingService(service);

    assertSame("Posting response must short-circuit the handler's own logic",
        sentinel, handler.handle(context));
  }

  @Test
  public void handleFallsThroughWhenServiceDeclinesTheAction() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    when(service.handleAction(context)).thenReturn(null);

    ReturnMaterialReceiptHeaderHandler handler = new ReturnMaterialReceiptHeaderHandler();
    handler.setPostingService(service);

    assertNull("A declined posting action must not short-circuit the handler",
        handler.handle(context));
    verify(service).handleAction(context);
  }

  @Test
  public void handleSurvivesAnUninjectedPostingService() {
    NeoContext context = mock(NeoContext.class);

    ReturnMaterialReceiptHeaderHandler handler = new ReturnMaterialReceiptHeaderHandler();

    assertNull("A null posting service must degrade to the pre-ETP-5378 behavior",
        handler.handle(context));
  }
}
