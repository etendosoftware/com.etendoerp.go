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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.inject.Named;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;

/**
 * Unit tests for {@link DocumentActionHandler}.
 *
 * <p>The handler is a thin CDI entry point that delegates to {@link DocumentPostingService}. These
 * tests drive the package-private {@code setPostingService(...)} seam with a mocked service so the
 * delegation contract can be verified without a live database, and verify the {@code @Named}
 * qualifier via reflection.</p>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DocumentActionHandlerTest {

  /**
   * A CRUD-endpoint context is not a posting action: the service returns {@code null} and the
   * handler must propagate that {@code null} so default CRUD continues.
   */
  @Test
  public void handleReturnsNullForCrudEndpoint() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(service.handleAction(context)).thenReturn(null);

    DocumentActionHandler handler = new DocumentActionHandler();
    handler.setPostingService(service);

    org.junit.Assert.assertNull("CRUD endpoint must fall through to default handling",
        handler.handle(context));
  }

  /**
   * The class must carry {@code @Named("document-posting")} so {@code lookupHandler()} can match it
   * against the {@code ETGO_SF_ENTITY.Java_Qualifier} value.
   */
  @Test
  public void carriesDocumentPostingNamedQualifier() {
    Named named = DocumentActionHandler.class.getAnnotation(Named.class);
    org.junit.Assert.assertNotNull("DocumentActionHandler must be annotated @Named", named);
    assertEquals("document-posting", named.value());
  }
}
