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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link NotPostedDocumentsHandler}.
 *
 * <p>All tests drive the ACTION dispatch path or annotation contract. CRUD paths (which require
 * {@link com.etendoerp.bulk.posting.datasource.NoPostedDocumentDS} and live OBDal) are integration
 * tests and are excluded here. The {@code setPostingService(...)} package-private seam allows
 * injection of a mock {@link DocumentPostingService} so post / bulk-post paths can be exercised
 * without a database.</p>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NotPostedDocumentsHandlerTest {

  @Test
  public void carriesNotPostedDocumentsNamedQualifier() {
    Named named = NotPostedDocumentsHandler.class.getAnnotation(Named.class);
    assertNotNull("NotPostedDocumentsHandler must be annotated @Named", named);
    assertEquals("not-posted-documents", named.value());
  }

  @Test
  public void handleReturnsNullForUnknownEndpointType() {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleActionReturnsNullForUnknownAction() {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("unknown-action");

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleSinglePostReturns200OnSuccess() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    when(service.post("318", "REC-1"))
        .thenReturn(new DocumentPostingService.PostResult(true, "posted"));

    JSONObject body = new JSONObject();
    body.put("tableId", "318");
    body.put("recordId", "REC-1");

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("post");
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(200, resp.getHttpStatus());
  }

  @Test
  public void handleSinglePostReturns422OnFailure() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    when(service.post("318", "REC-1"))
        .thenReturn(new DocumentPostingService.PostResult(false, "Posting failed"));

    JSONObject body = new JSONObject();
    body.put("tableId", "318");
    body.put("recordId", "REC-1");

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("post");
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(422, resp.getHttpStatus());
  }

  @Test
  public void handleBulkPostAggregatesResults() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    when(service.post("318", "REC-1"))
        .thenReturn(new DocumentPostingService.PostResult(true, "ok"));
    when(service.post("319", "REC-2"))
        .thenReturn(new DocumentPostingService.PostResult(false, "err"));

    JSONObject row1 = new JSONObject();
    row1.put("tableId", "318");
    row1.put("recordId", "REC-1");
    JSONObject row2 = new JSONObject();
    row2.put("tableId", "319");
    row2.put("recordId", "REC-2");
    JSONArray rows = new JSONArray();
    rows.put(row1);
    rows.put(row2);
    JSONObject body = new JSONObject();
    body.put("rows", rows);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("bulk-post");
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(200, resp.getHttpStatus());
    assertEquals(1, resp.getBody().getInt("ok"));
    assertEquals(2, resp.getBody().getInt("total"));
  }
}
