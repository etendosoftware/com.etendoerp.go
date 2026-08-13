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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.data.AEATSIIFacturas;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link SiiConfigDeactivateHandler} (ETP-4785).
 *
 * <p>Covers the dispatch guard clauses in {@link SiiConfigDeactivateHandler#handle} (non-PUT,
 * not deactivating, blank recordId), and all branches of
 * {@link SiiConfigDeactivateHandler#smartDeactivate}: config not found, no adoption date (→
 * delete), adoption date set with SII invoices (→ null / fall through to CRUD), and adoption date
 * set without SII invoices (→ delete).
 */
public class SiiConfigDeactivateHandlerTest {

  private static final String RECORD_ID = "sii-config-001";
  private static final String ORG_ID = "org-spain-001";

  // ─── handle(): dispatch guards ────────────────────────────────────────────────

  @Test
  public void handleReturnsNullForGetMethod() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("GET").build()));
  }

  @Test
  public void handleReturnsNullForPostMethod() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("POST").build()));
  }

  @Test
  public void handleReturnsNullForPatchMethod() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("PATCH").build()));
  }

  @Test
  public void handleReturnsNullWhenBodyHasNoActiveField() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("name", "foo"))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenBodyIsNull() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenActiveIsTrue() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", true))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenRecordIdIsBlank() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId("   ")
        .build()));
  }

  @Test
  public void handleReturnsNullWhenRecordIdIsMissing() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .build()));
  }

  // ─── handle(): isExplicitlyDeactivating edge cases ───────────────────────────

  @Test
  public void handleRecognizesStringFalseAsDeactivating() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // Config not found — simplest path confirming handle() was entered.
      when(dal.get(eq(AEATSIIConfig.class), Mockito.anyString())).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", "false"))
          .recordId(RECORD_ID)
          .build());
      // null because config not found in smartDeactivate
      assertNull(result);
    }
  }

  @Test
  public void handleRecognizesStringNAsDeactivating() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), Mockito.anyString())).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", "N"))
          .recordId(RECORD_ID)
          .build());
      assertNull(result);
    }
  }

  // ─── afterHandle(): always returns null ──────────────────────────────────────

  @Test
  public void afterHandleAlwaysReturnsNull() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.afterHandle(NeoContext.builder().httpMethod("PUT").recordId(RECORD_ID).build()));
    assertNull(handler.afterHandle(NeoContext.builder().httpMethod("POST").build()));
    assertNull(handler.afterHandle(NeoContext.builder().httpMethod("GET").build()));
  }

  // ─── smartDeactivate(): config not found ─────────────────────────────────────

  @Test
  public void smartDeactivateReturnsNullWhenConfigNotFound() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(null);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);
      assertNull(result);

      verify(dal, never()).remove(any());
      verify(dal, never()).flush();
    }
  }

  // ─── smartDeactivate(): no adoption date (→ delete) ──────────────────────────

  @Test
  public void smartDeactivateDeletesAndReturnsDeletedWhenFechaAcogidaIsNull() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      AEATSIIConfig config = mock(AEATSIIConfig.class);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      // fechaAcogidaSII is null — config never entered the fiscal system
      when(config.getFechaAcogidaSII()).thenReturn(null);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      verify(dal).remove(config);
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  // ─── smartDeactivate(): adoption date set, SII invoices exist (→ null) ───────

  @Test
  public void smartDeactivateReturnsNullWhenSiiInvoicesExist() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    Date adoptionDate = new Date();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      AEATSIIConfig config = mock(AEATSIIConfig.class);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      when(config.getFechaAcogidaSII()).thenReturn(adoptionDate);

      // hasSiiInvoicesSince → OBCriteria returns count > 0
      @SuppressWarnings("unchecked")
      OBCriteria<AEATSIIFacturas> crit = mock(OBCriteria.class);
      when(dal.createCriteria(AEATSIIFacturas.class)).thenReturn(crit);
      when(crit.createAlias(Mockito.anyString(), Mockito.anyString())).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(3L);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      // SII invoices found → fallthrough to default CRUD deactivation
      assertNull(result);
      verify(dal, never()).remove(any());
    }
  }

  // ─── smartDeactivate(): adoption date set, no SII invoices (→ delete) ────────

  @Test
  public void smartDeactivateDeletesWhenAdoptionDateSetButNoSiiInvoices() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    Date adoptionDate = new Date();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      AEATSIIConfig config = mock(AEATSIIConfig.class);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      when(config.getFechaAcogidaSII()).thenReturn(adoptionDate);

      // hasSiiInvoicesSince → OBCriteria returns count 0
      @SuppressWarnings("unchecked")
      OBCriteria<AEATSIIFacturas> crit = mock(OBCriteria.class);
      when(dal.createCriteria(AEATSIIFacturas.class)).thenReturn(crit);
      when(crit.createAlias(Mockito.anyString(), Mockito.anyString())).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(0L);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      verify(dal).remove(config);
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  // ─── handle(): 500 on unexpected exception (must NOT fall through to default CRUD) ───

  @Test
  public void handleReturns500OnUnexpectedException() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), Mockito.anyString()))
          .thenThrow(new RuntimeException("DB exploded"));

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());
      // Must return 500, NOT null — returning null would let the default CRUD deactivate
      // the record without verifying pending invoices, bypassing the business rule.
      assertNotNull(result);
      assertEquals(500, result.getHttpStatus());
      obCtxMock.verify(OBContext::restorePreviousMode, Mockito.times(1));
    }
  }
}
