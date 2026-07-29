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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.inject.Named;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.RectificativeDocTypeFlagService;

/**
 * Unit tests for the ETP-4536 rectificative-flagging wiring added to
 * {@link TbaiConfigSequenceHandler} (the TBAI chaining-sequence side effect itself is
 * pre-existing, ETP-4401). These verify that after the handler's own TBAI side effect runs, it
 * still delegates to the shared {@link RectificativeDocTypeFlagService} and returns that service's
 * response, and that non-CRUD / non-write requests short-circuit before either side effect.
 *
 * <p>Uses the package-private {@code setRectificativeService(...)} seam with a mocked service; the
 * pre-existing TBAI DB work inside {@code afterHandle} is neutralised on the write path by mocking
 * the {@code OBContext} statics and passing a context that resolves no config scope (the handler
 * logs-and-continues), matching the {@code YearAccountingHandlerTest} static-mock convention.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class TbaiConfigSequenceHandlerRectificativeTest {

  /** The class must carry {@code @Named("tbai-config-sequence-handler")}. */
  @Test
  public void carriesTbaiConfigSequenceNamedQualifier() {
    Named named = TbaiConfigSequenceHandler.class.getAnnotation(Named.class);
    assertNotNull("TbaiConfigSequenceHandler must be annotated @Named", named);
    assertEquals("tbai-config-sequence-handler", named.value());
  }

  /** The pre-hook is inert: {@code handle()} must return null. */
  @Test
  public void handleReturnsNullPreHook() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    assertNull(handler.handle(mock(NeoContext.class)));
  }

  /**
   * A non-CRUD endpoint short-circuits: neither the TBAI side effect nor the rectificative service
   * run, and null is returned.
   */
  @Test
  public void afterHandleReturnsNullAndSkipsServiceForNonCrud() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(context.getHttpMethod()).thenReturn("POST");

    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    handler.setRectificativeService(service);

    assertNull(handler.afterHandle(context));
    verify(service, never()).applyAfterConfigSave(Mockito.any());
  }

  /**
   * A CRUD GET (non-write) short-circuits before both side effects and returns null.
   */
  @Test
  public void afterHandleReturnsNullAndSkipsServiceForGet() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("GET");

    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    handler.setRectificativeService(service);

    assertNull(handler.afterHandle(context));
    verify(service, never()).applyAfterConfigSave(Mockito.any());
  }

  /**
   * On a CRUD POST, after the pre-existing TBAI chaining side effect runs (here a no-op because no
   * config scope resolves and its exceptions are swallowed), the handler must STILL delegate to the
   * rectificative service and return that service's response verbatim.
   */
  @Test
  public void afterHandlePostDelegatesToRectificativeServiceAfterTbaiSideEffect() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoResponse expected = new NeoResponse(201, null);
    when(service.applyAfterConfigSave(Mockito.any())).thenReturn(expected);

    // POST with no previous-result body -> resolveRecordId returns null -> no config scope ->
    // the TBAI side effect logs-and-returns without touching the DB. No OBDal interaction needed.
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getPreviousResult()).thenReturn(null);
    when(context.getObContext()).thenReturn(null);

    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    handler.setRectificativeService(service);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      NeoResponse actual = handler.afterHandle(context);

      verify(service).applyAfterConfigSave(context);
      assertSame("The rectificative service's response must be returned verbatim", expected, actual);
    }
  }

  /**
   * Even if the pre-existing TBAI side effect throws, its exception is swallowed and the handler
   * still delegates to the rectificative service (independent side effects). Here the TBAI branch
   * is forced to throw by making {@code getObContext().getOrganizationStructureProvider()} blow up
   * is out of reach in a pure unit test; instead we assert the delegation still happens when the
   * TBAI branch resolves no scope (its normal swallow path), which is the observable contract.
   */
  @Test
  public void afterHandlePutDelegatesToRectificativeService() {
    RectificativeDocTypeFlagService service = mock(RectificativeDocTypeFlagService.class);
    NeoResponse expected = new NeoResponse(200, null);
    when(service.applyAfterConfigSave(Mockito.any())).thenReturn(expected);

    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(context.getHttpMethod()).thenReturn("PUT");
    // PUT with a blank record id -> no scope resolves -> TBAI side effect is a no-op.
    when(context.getRecordId()).thenReturn(null);
    when(context.getObContext()).thenReturn(null);

    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    handler.setRectificativeService(service);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(true)).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      assertSame(expected, handler.afterHandle(context));
      verify(service).applyAfterConfigSave(context);
    }
  }
}
