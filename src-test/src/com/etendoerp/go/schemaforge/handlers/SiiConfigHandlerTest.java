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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.inject.Named;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.RectificativeDocTypeFlagService;

/**
 * Unit tests for {@link SiiConfigHandler}.
 *
 * <p>The handler exists solely to run the shared {@link RectificativeDocTypeFlagService} after an
 * SII configuration is saved (ETP-4536). These tests drive the package-private
 * {@code setRectificativeService(...)} seam with a mocked service to verify the delegation contract
 * without a live DB, and verify the {@code @Named} qualifier via reflection.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SiiConfigHandlerTest {

  /**
   * The class must carry {@code @Named("sii-config-rectificative-handler")} so
   * {@code lookupHandler()} can match it against the {@code ETGO_SF_ENTITY.Java_Qualifier} value.
   */
  @Test
  public void carriesSiiConfigRectificativeNamedQualifier() {
    Named named = SiiConfigHandler.class.getAnnotation(Named.class);
    assertNotNull("SiiConfigHandler must be annotated @Named", named);
    assertEquals("sii-config-rectificative-handler", named.value());
  }

  /** The pre-hook is inert: {@code handle()} must return null so default CRUD continues. */
  @Test
  public void handleReturnsNullPreHook() {
    SiiConfigHandler handler = new SiiConfigHandler();
    NeoContext context = mock(NeoContext.class);

    assertNull(handler.handle(context));
  }

  /**
   * {@code afterHandle()} must delegate straight to the rectificative service and return whatever
   * it produces — here, a warnings-carrying response.
   */
  @Test
  public void afterHandleDelegatesToRectificativeService() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoContext context = mock(NeoContext.class);
    NeoResponse expected = new NeoResponse(201, null);
    when(service.applyAfterConfigSave(context)).thenReturn(expected);

    SiiConfigHandler handler = new SiiConfigHandler();
    handler.setRectificativeService(service);

    NeoResponse actual = handler.afterHandle(context);

    verify(service).applyAfterConfigSave(context);
    assertSame("afterHandle must return the service's response verbatim", expected, actual);
  }

  /**
   * When the service returns null (no warnings / not a write), the handler propagates null so the
   * original CRUD response is kept untouched.
   */
  @Test
  public void afterHandlePropagatesNullFromService() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoContext context = mock(NeoContext.class);
    when(service.applyAfterConfigSave(context)).thenReturn(null);

    SiiConfigHandler handler = new SiiConfigHandler();
    handler.setRectificativeService(service);

    assertNull(handler.afterHandle(context));
    verify(service).applyAfterConfigSave(context);
  }
}
