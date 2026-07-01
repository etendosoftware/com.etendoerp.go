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

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * Representative test for the shared posting delegation added to every document header handler.
 *
 * <p>The same one-line "delegate to {@link DocumentPostingService#handleAction} first, short-circuit
 * on a non-null result" edit was applied to all six header handlers (Sales/Purchase Invoice, GL
 * Journal, Amortization, Goods Receipt, Goods Shipment). This test exercises
 * {@link SalesInvoiceHeaderHandler} as the representative case: a mocked service returning a sentinel
 * {@link NeoResponse} for an ACTION context must be returned unchanged by {@code handle()}, proving
 * the posting branch runs before the handler's own logic.</p>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SalesInvoiceHeaderHandlerPostingTest {

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(context)).thenReturn(sentinel);

    SalesInvoiceHeaderHandler handler = new SalesInvoiceHeaderHandler();
    handler.setPostingService(service);

    assertSame("Posting response must short-circuit the handler's own logic",
        sentinel, handler.handle(context));
  }
}
