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
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.inject.Named;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for the ETP-4536 rectificative-flagging wiring added to
 * {@link VerifactuConfigReadyHandler} (the Verifactu adoption-date side effect itself is
 * pre-existing, ETP-4389). These verify the handler still delegates to the shared
 * {@link RectificativeDocTypeFlagService} at the end of {@code afterHandle} for write methods, and
 * short-circuits before both side effects for non-write methods.
 *
 * <p>Uses the package-private {@code setRectificativeService(...)} seam with a mocked service. On
 * the write path a POST with no previous-result body is used so {@code resolveRecordId} returns
 * null and {@code markReadyIfNeeded} (the pre-existing DB side effect) is skipped — no live DB is
 * touched, isolating the delegation contract this story added.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class VerifactuConfigReadyHandlerRectificativeTest {

  /** The class must carry {@code @Named("verifactu-config-ready-handler")}. */
  @Test
  public void carriesVerifactuConfigReadyNamedQualifier() {
    Named named = VerifactuConfigReadyHandler.class.getAnnotation(Named.class);
    assertNotNull("VerifactuConfigReadyHandler must be annotated @Named", named);
    assertEquals("verifactu-config-ready-handler", named.value());
  }

  /** The pre-hook is inert: {@code handle()} must return null. */
  @Test
  public void handleReturnsNullPreHook() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(mock(NeoContext.class)));
  }

  /**
   * A non-write method (GET) short-circuits before both the adoption-date side effect and the
   * rectificative service, returning null.
   */
  @Test
  public void afterHandleReturnsNullAndSkipsServiceForGet() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("GET");

    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    handler.setRectificativeService(service);

    assertNull(handler.afterHandle(context));
    verify(service, never()).applyAfterConfigSave(Mockito.any());
  }

  /** A DELETE likewise short-circuits before the rectificative service. */
  @Test
  public void afterHandleReturnsNullAndSkipsServiceForDelete() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("DELETE");

    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    handler.setRectificativeService(service);

    assertNull(handler.afterHandle(context));
    verify(service, never()).applyAfterConfigSave(Mockito.any());
  }

  /**
   * On a POST, after the pre-existing adoption-date side effect is skipped (no resolvable record
   * id: previous result is null), the handler must STILL delegate to the rectificative service and
   * return that service's response verbatim.
   */
  @Test
  public void afterHandlePostDelegatesToRectificativeServiceAfterAdoptionSideEffect() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoResponse expected = new NeoResponse(201, null);
    when(service.applyAfterConfigSave(Mockito.any())).thenReturn(expected);

    // POST + null previous result -> resolveRecordId returns null -> markReadyIfNeeded is skipped
    // (no OBDal interaction), then the rectificative service is always invoked.
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getPreviousResult()).thenReturn(null);

    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    handler.setRectificativeService(service);

    NeoResponse actual = handler.afterHandle(context);

    verify(service).applyAfterConfigSave(context);
    assertSame("The rectificative service's response must be returned verbatim", expected, actual);
  }

  /**
   * On a PATCH, the same delegation contract holds. PATCH resolves its id from the URL
   * ({@code getRecordId}); a null id skips the adoption side effect but the rectificative service
   * still runs.
   */
  @Test
  public void afterHandlePatchDelegatesToRectificativeService() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoResponse expected = new NeoResponse(200, null);
    when(service.applyAfterConfigSave(Mockito.any())).thenReturn(expected);

    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("PATCH");
    when(context.getRecordId()).thenReturn(null);

    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    handler.setRectificativeService(service);

    assertSame(expected, handler.afterHandle(context));
    verify(service).applyAfterConfigSave(context);
  }
}
