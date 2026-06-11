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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.enterprise.inject.Instance;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.smf.jobs.hooks.CloneRecordHook;

/**
 * Unit tests for {@link NeoCloneRecordHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Dispatch fall-through (non-ACTION, wrong fieldName, non-POST).</li>
 *   <li>Validation: blank recordId, null adTab, null entity.</li>
 *   <li>Happy path: source not found → 404.</li>
 *   <li>Happy path: DalUtil copy fallback when no hook registered → 201.</li>
 *   <li>Error handling: OBException → 400, RuntimeException → 500.</li>
 * </ul>
 */
public class NeoCloneRecordHandlerTest {

  private static final String ACTION = "cloneRecord";

  /** Inject cloneHooks via reflection since @Inject @Any blocks standard MockitoJUnit injection. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static NeoCloneRecordHandler handlerWithMockedHooks(Instance cloneHooksInstance)
      throws Exception {
    NeoCloneRecordHandler h = new NeoCloneRecordHandler();
    java.lang.reflect.Field f = NeoCloneRecordHandler.class.getDeclaredField("cloneHooks");
    f.setAccessible(true);
    f.set(h, cloneHooksInstance);
    return h;
  }

  private static NeoContext actionCtx(String recordId) {
    return NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION)
        .recordId(recordId)
        .build();
  }

  // ── dispatch guard ────────────────────────────────────────────────────────

  @Test
  public void handle_nonActionEndpoint_returnsNull() {
    assertNull(new NeoCloneRecordHandler().handle(NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .fieldName(ACTION)
        .build()));
  }

  @Test
  public void handle_wrongFieldName_returnsNull() {
    assertNull(new NeoCloneRecordHandler().handle(NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("otherAction")
        .build()));
  }

  @Test
  public void handle_getMethod_returnsNull() {
    assertNull(new NeoCloneRecordHandler().handle(NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION)
        .build()));
  }

  // ── validation ────────────────────────────────────────────────────────────

  @Test
  public void handle_blankRecordId_returns400() {
    NeoResponse r = new NeoCloneRecordHandler().handle(NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION)
        .recordId("")
        .build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void handle_nullAdTab_returns500() {
    NeoResponse r = new NeoCloneRecordHandler().handle(actionCtx("rec-1"));
    assertNotNull(r);
    assertEquals(500, r.getHttpStatus());
  }

  // ── OBContext.setAdminMode path ───────────────────────────────────────────

  @Test
  public void handle_entityNotFound_returns500() {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getDBTableName()).thenReturn("M_InOut");

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION)
        .recordId("some-id")
        .adTab(tab)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      ModelProvider mp = mock(ModelProvider.class);
      mpMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableName("M_InOut")).thenReturn(null);

      NeoResponse r = new NeoCloneRecordHandler().handle(ctx);
      assertNotNull(r);
      assertEquals(500, r.getHttpStatus());
    }
  }

  @Test
  public void handle_sourceRecordNotFound_returns404() {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getDBTableName()).thenReturn("M_InOut");

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION)
        .recordId("missing-id")
        .adTab(tab)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      Entity entity = mock(Entity.class);
      when(entity.getMappingClass()).thenReturn((Class) BaseOBObject.class);
      ModelProvider mp = mock(ModelProvider.class);
      mpMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableName("M_InOut")).thenReturn(entity);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(BaseOBObject.class, "missing-id")).thenReturn(null);

      NeoResponse r = new NeoCloneRecordHandler().handle(ctx);
      assertNotNull(r);
      assertEquals(404, r.getHttpStatus());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_dalUtilCopyFallback_returns201() throws Exception {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getTable()).thenReturn(table);
    when(table.getDBTableName()).thenReturn("M_InOut");

    // Set up empty hooks via reflection since @Inject @Any blocks @InjectMocks
    Instance<CloneRecordHook> cloneHooks = mock(Instance.class);
    Instance<CloneRecordHook> emptyHooks = mock(Instance.class);
    when(cloneHooks.select(any(ComponentProvider.Selector.class))).thenReturn(emptyHooks);
    when(emptyHooks.iterator()).thenReturn(java.util.Collections.emptyIterator());
    NeoCloneRecordHandler handler = handlerWithMockedHooks(cloneHooks);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION)
        .recordId("src-id")
        .adTab(tab)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilMock = Mockito.mockStatic(DalUtil.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      Entity entity = mock(Entity.class);
      when(entity.getMappingClass()).thenReturn((Class) BaseOBObject.class);
      when(entity.getName()).thenReturn("ShipmentInOut");
      ModelProvider mp = mock(ModelProvider.class);
      mpMock.when(ModelProvider::getInstance).thenReturn(mp);
      when(mp.getEntityByTableName("M_InOut")).thenReturn(entity);

      BaseOBObject source = mock(BaseOBObject.class);
      BaseOBObject clone = mock(BaseOBObject.class);
      when(clone.getId()).thenReturn("clone-id");

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(BaseOBObject.class, "src-id")).thenReturn(source);

      dalUtilMock.when(() -> DalUtil.copy(source, false)).thenReturn(clone);

      NeoResponse r = handler.handle(ctx);
      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      assertEquals("clone-id", r.getBody().getJSONObject("response").getJSONObject("data").getString("id"));
    }
  }

  @Test
  public void afterHandle_alwaysReturnsNull() {
    assertNull(new NeoCloneRecordHandler().afterHandle(NeoContext.builder().httpMethod("GET").build()));
  }
}
