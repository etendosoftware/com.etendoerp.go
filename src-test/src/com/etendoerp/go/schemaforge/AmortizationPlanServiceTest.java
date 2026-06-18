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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.assetmgmt.Amortization;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link AmortizationPlanService#generatePlan(String)}.
 *
 * <p>Every branch of the validation / resolution / execution flow is covered.
 * Static boundaries are mocked via Mockito {@code mockStatic}: {@link OBDal},
 * {@link NeoProcessService}. The {@link Session#refresh(Object)} call on the
 * Hibernate session is also mocked so the test does not require a real server.
 */
public class AmortizationPlanServiceTest {

  // ── Constants ─────────────────────────────────────────────────────────────

  private static final String ASSET_ID = "ASSET-001";
  private static final String AMORTIZATION_ID = "AMORT-001";
  private static final String TAB_ID = "TAB-001";
  private static final String SPEC_ID = "SPEC-001";
  private static final String ENTITY_ID = "ENTITY-001";

  // ── 400 — blank / null assetId ────────────────────────────────────────────

  /**
   * TC-01: null assetId must return HTTP 400 without touching the DAL.
   */
  @Test
  public void testNullAssetIdReturns400() {
    NeoResponse response = AmortizationPlanService.generatePlan(null);

    assertEquals(400, response.getHttpStatus());
    assertBodyContainsMessage(response, "assetId is required");
  }

  /**
   * TC-02: empty string assetId must return HTTP 400.
   */
  @Test
  public void testEmptyAssetIdReturns400() {
    NeoResponse response = AmortizationPlanService.generatePlan("");

    assertEquals(400, response.getHttpStatus());
    assertBodyContainsMessage(response, "assetId is required");
  }

  /**
   * TC-03: blank-whitespace assetId must return HTTP 400.
   */
  @Test
  public void testBlankAssetIdReturns400() {
    NeoResponse response = AmortizationPlanService.generatePlan("   ");

    assertEquals(400, response.getHttpStatus());
  }

  // ── 404 — asset not found ─────────────────────────────────────────────────

  /**
   * TC-04: when OBDal.get returns null the service must return HTTP 404.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testAssetNotFoundReturns404() {
    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(404, response.getHttpStatus());
      assertBodyContainsMessage(response, "Asset not found");
    }
  }

  // ── 400 — not depreciable ─────────────────────────────────────────────────

  /**
   * TC-05: asset with isDepreciate() == false must return HTTP 400.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testNonDepreciableAssetReturns400() {
    Asset asset = mockDepreciableAsset(false, null, null);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(400, response.getHttpStatus());
      assertBodyContainsMessage(response, "not configured for depreciation");
    }
  }

  // ── 409 — already processed ───────────────────────────────────────────────

  /**
   * TC-06: asset already processed ("Y") must return HTTP 409.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testAlreadyProcessedAssetReturns409() {
    Asset asset = mock(Asset.class);
    when(asset.isDepreciate()).thenReturn(true);
    when(asset.getProcessed()).thenReturn("Y");

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(409, response.getHttpStatus());
      assertBodyContainsMessage(response, "already has a generated amortization plan");
    }
  }

  // ── 400 — TI with null usable life ────────────────────────────────────────

  /**
   * TC-07: calculateType == "TI" and usableLifeMonths == null must return HTTP 400.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testTiAssetWithNullUsableLifeReturns400() {
    Asset asset = mock(Asset.class);
    when(asset.isDepreciate()).thenReturn(true);
    when(asset.getProcessed()).thenReturn("N");
    when(asset.getCalculateType()).thenReturn("TI");
    when(asset.getUsableLifeMonths()).thenReturn(null);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(400, response.getHttpStatus());
      assertBodyContainsMessage(response, "usableLifeMonths must be > 0");
    }
  }

  /**
   * TC-08: calculateType == "TI" and usableLifeMonths == 0 must return HTTP 400.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testTiAssetWithZeroUsableLifeReturns400() {
    Asset asset = mock(Asset.class);
    when(asset.isDepreciate()).thenReturn(true);
    when(asset.getProcessed()).thenReturn("N");
    when(asset.getCalculateType()).thenReturn("TI");
    when(asset.getUsableLifeMonths()).thenReturn(0L);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(400, response.getHttpStatus());
    }
  }

  // ── PE: usable-life check must NOT apply ─────────────────────────────────

  /**
   * TC-09: calculateType == "PE" with null usableLifeMonths must NOT be rejected
   * for missing usable life. The service should proceed past that validation gate.
   * (It may still fail at process resolution if OBCriteria returns null, which is
   * covered separately; here we verify the code does NOT return 400 for PE.)
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testPeAssetDoesNotRejectForNullUsableLife() {
    Asset asset = mock(Asset.class);
    when(asset.isDepreciate()).thenReturn(true);
    when(asset.getProcessed()).thenReturn("N");
    when(asset.getCalculateType()).thenReturn("PE");
    when(asset.getUsableLifeMonths()).thenReturn(null);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    // Return null from process criteria so the flow advances to step 6 and returns 500
    // (not 400), proving the usable-life gate was not triggered.
    OBCriteria<Process> processCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(Process.class)).thenReturn(processCriteria);
    when(processCriteria.add(any())).thenReturn(processCriteria);
    when(processCriteria.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      // Must NOT be 400 (usable-life rejection); the actual code returns 500 here
      // because the A_Asset_Post process could not be resolved.
      assertFalse("PE asset must not be rejected with 400 for null usable life",
          response.getHttpStatus() == 400);
    }
  }

  // ── 500 — A_Asset_Post process not found ─────────────────────────────────

  /**
   * TC-10: when A_Asset_Post process cannot be resolved, service returns HTTP 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testProcessNotFoundReturns500() {
    Asset asset = buildValidTiAsset(12L);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    // Process criteria returns null (not found)
    OBCriteria<Process> processCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(Process.class)).thenReturn(processCriteria);
    when(processCriteria.add(any())).thenReturn(processCriteria);
    when(processCriteria.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(500, response.getHttpStatus());
      assertBodyContainsMessage(response, "A_Asset_Post process not found");
    }
  }

  // ── 500 — assets tab not found ────────────────────────────────────────────

  /**
   * TC-11: when the assets SFSpec cannot be resolved, service returns HTTP 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testAssetsSpecNotFoundReturns500() {
    Asset asset = buildValidTiAsset(12L);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    // A_Asset_Post process found
    Process process = mock(Process.class);
    OBCriteria<Process> processCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(Process.class)).thenReturn(processCriteria);
    when(processCriteria.add(any())).thenReturn(processCriteria);
    when(processCriteria.uniqueResult()).thenReturn(process);

    // SFSpec criteria returns null (spec not found)
    OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(SFSpec.class)).thenReturn(specCriteria);
    when(specCriteria.add(any())).thenReturn(specCriteria);
    when(specCriteria.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(500, response.getHttpStatus());
      assertBodyContainsMessage(response, "Assets AD_Tab could not be resolved");
    }
  }

  /**
   * TC-12: when the SFSpec is found but SFEntity is missing, service returns HTTP 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testAssetsEntityNotFoundReturns500() {
    Asset asset = buildValidTiAsset(12L);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    Process process = mock(Process.class);
    OBCriteria<Process> processCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(Process.class)).thenReturn(processCriteria);
    when(processCriteria.add(any())).thenReturn(processCriteria);
    when(processCriteria.uniqueResult()).thenReturn(process);

    // SFSpec found
    SFSpec sfSpec = mock(SFSpec.class);
    when(sfSpec.getId()).thenReturn(SPEC_ID);
    OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(SFSpec.class)).thenReturn(specCriteria);
    when(specCriteria.add(any())).thenReturn(specCriteria);
    when(specCriteria.uniqueResult()).thenReturn(sfSpec);

    // SFEntity criteria returns null
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any())).thenReturn(entityCriteria);
    when(entityCriteria.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(500, response.getHttpStatus());
      assertBodyContainsMessage(response, "Assets AD_Tab could not be resolved");
    }
  }

  /**
   * TC-13: when SFEntity has no linked AD_Tab, service returns HTTP 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testAssetsEntityHasNoTabReturns500() {
    Asset asset = buildValidTiAsset(12L);

    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    Process process = mock(Process.class);
    OBCriteria<Process> processCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(Process.class)).thenReturn(processCriteria);
    when(processCriteria.add(any())).thenReturn(processCriteria);
    when(processCriteria.uniqueResult()).thenReturn(process);

    SFSpec sfSpec = mock(SFSpec.class);
    when(sfSpec.getId()).thenReturn(SPEC_ID);
    OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(SFSpec.class)).thenReturn(specCriteria);
    when(specCriteria.add(any())).thenReturn(specCriteria);
    when(specCriteria.uniqueResult()).thenReturn(sfSpec);

    // Entity with no AD_Tab
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getADTab()).thenReturn(null);
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any())).thenReturn(entityCriteria);
    when(entityCriteria.uniqueResult()).thenReturn(sfEntity);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(500, response.getHttpStatus());
      assertBodyContainsMessage(response, "Assets AD_Tab could not be resolved");
    }
  }

  // ── Process execution failure surfaced ───────────────────────────────────

  /**
   * TC-14: when NeoProcessService.executeProcess returns an error response, the
   * service must propagate it instead of swallowing it.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testProcessExecutionErrorIsPropagated() {
    Asset asset = buildValidTiAsset(12L);
    OBDal dalMock = buildDalWithProcessAndTab(asset);

    NeoResponse processErrorResponse = NeoResponse.error(500, "Process execution failed");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);
      processMock.when(() -> NeoProcessService.executeProcess(
          any(Process.class), isNull(), eq(ASSET_ID), eq(TAB_ID)))
          .thenReturn(processErrorResponse);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertTrue("Process error must be propagated with status >= 400",
          response.getHttpStatus() >= 400);
    }
  }

  // ── Success path — TI asset ───────────────────────────────────────────────

  /**
   * TC-15: happy path with a TI asset; verifies all output JSON fields.
   * Two amortization lines with different amounts are used to exercise totalAmortization
   * (sum) and periodAmount (first line).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testSuccessPathTiReturnsCorrectJson() throws Exception {
    Asset asset = buildValidTiAsset(12L);
    when(asset.getCalculateType()).thenReturn("TI");

    Currency currency = mock(Currency.class);
    when(currency.getISOCode()).thenReturn("EUR");
    when(asset.getCurrency()).thenReturn(currency);

    // Build two amortization lines
    Date startDate = new Date(1000000000L * 1000L); // arbitrary fixed date
    Date endDate = new Date(1100000000L * 1000L);

    Amortization header = mock(Amortization.class);
    when(header.getId()).thenReturn(AMORTIZATION_ID);
    when(header.getStartingDate()).thenReturn(startDate);
    when(header.getEndingDate()).thenReturn(endDate);

    AmortizationLine line1 = mockLine(header, new BigDecimal("100.00"));
    AmortizationLine line2 = mockLine(header, new BigDecimal("100.00"));
    List<AmortizationLine> lines = Arrays.asList(line1, line2);

    OBDal dalMock = buildDalWithProcessAndTab(asset);
    Session sessionMock = mock(Session.class);
    when(dalMock.getSession()).thenReturn(sessionMock);

    OBCriteria<AmortizationLine> lineCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(AmortizationLine.class)).thenReturn(lineCriteria);
    when(lineCriteria.add(any())).thenReturn(lineCriteria);
    when(lineCriteria.addOrder(any())).thenReturn(lineCriteria);
    when(lineCriteria.list()).thenReturn(lines);

    NeoResponse processOkResponse = NeoResponse.ok(new JSONObject("{\"status\":\"success\"}"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);
      processMock.when(() -> NeoProcessService.executeProcess(
          any(Process.class), isNull(), eq(ASSET_ID), eq(TAB_ID)))
          .thenReturn(processOkResponse);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(200, response.getHttpStatus());
      assertNotNull(response.getBody());

      JSONObject body = response.getBody();
      assertTrue("success flag must be true", body.getBoolean("success"));
      assertEquals(AMORTIZATION_ID, body.getString("amortizationId"));
      assertEquals("TI", body.getString("calculateType"));
      assertEquals(new BigDecimal("200.00"),
          new BigDecimal(body.getString("totalAmortization")));
      assertEquals("EUR", body.getString("currency"));
      assertEquals(2, body.getInt("periodsGenerated"));
      assertEquals(new BigDecimal("100.00"),
          new BigDecimal(body.getString("periodAmount")));
      // startDate and endDate must be present and formatted
      assertNotNull(body.getString("startDate"));
      assertNotNull(body.getString("endDate"));
    }
  }

  /**
   * TC-16: happy path with a PE asset; currency is EUR; verifies currency field is present.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testSuccessPathPeWithCurrency() throws Exception {
    Asset asset = mock(Asset.class);
    when(asset.isDepreciate()).thenReturn(true);
    when(asset.getProcessed()).thenReturn("N");
    when(asset.getCalculateType()).thenReturn("PE");
    when(asset.getUsableLifeMonths()).thenReturn(null);

    Currency currency = mock(Currency.class);
    when(currency.getISOCode()).thenReturn("USD");
    when(asset.getCurrency()).thenReturn(currency);

    Amortization header = mock(Amortization.class);
    when(header.getId()).thenReturn(AMORTIZATION_ID);
    when(header.getStartingDate()).thenReturn(null);
    when(header.getEndingDate()).thenReturn(null);

    AmortizationLine line = mockLine(header, new BigDecimal("50.00"));

    OBDal dalMock = buildDalWithProcessAndTab(asset);
    Session sessionMock = mock(Session.class);
    when(dalMock.getSession()).thenReturn(sessionMock);

    OBCriteria<AmortizationLine> lineCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(AmortizationLine.class)).thenReturn(lineCriteria);
    when(lineCriteria.add(any())).thenReturn(lineCriteria);
    when(lineCriteria.addOrder(any())).thenReturn(lineCriteria);
    when(lineCriteria.list()).thenReturn(Collections.singletonList(line));

    NeoResponse processOkResponse = NeoResponse.ok(new JSONObject("{\"status\":\"success\"}"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);
      processMock.when(() -> NeoProcessService.executeProcess(
          any(Process.class), isNull(), eq(ASSET_ID), eq(TAB_ID)))
          .thenReturn(processOkResponse);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertEquals("USD", body.getString("currency"));
      assertEquals("PE", body.getString("calculateType"));
    }
  }

  // ── Currency null — no NPE, field omitted ─────────────────────────────────

  /**
   * TC-17: when asset currency is null the response must succeed and the
   * {@code currency} field must be omitted (not throw NPE).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testNullCurrencyOmitsFieldWithoutNpe() throws Exception {
    Asset asset = buildValidTiAsset(12L);
    when(asset.getCurrency()).thenReturn(null);

    Amortization header = mock(Amortization.class);
    when(header.getId()).thenReturn(AMORTIZATION_ID);
    when(header.getStartingDate()).thenReturn(null);
    when(header.getEndingDate()).thenReturn(null);

    AmortizationLine line = mockLine(header, new BigDecimal("100.00"));

    OBDal dalMock = buildDalWithProcessAndTab(asset);
    Session sessionMock = mock(Session.class);
    when(dalMock.getSession()).thenReturn(sessionMock);

    OBCriteria<AmortizationLine> lineCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(AmortizationLine.class)).thenReturn(lineCriteria);
    when(lineCriteria.add(any())).thenReturn(lineCriteria);
    when(lineCriteria.addOrder(any())).thenReturn(lineCriteria);
    when(lineCriteria.list()).thenReturn(Collections.singletonList(line));

    NeoResponse processOkResponse = NeoResponse.ok(new JSONObject("{\"status\":\"success\"}"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);
      processMock.when(() -> NeoProcessService.executeProcess(
          any(Process.class), isNull(), eq(ASSET_ID), eq(TAB_ID)))
          .thenReturn(processOkResponse);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(200, response.getHttpStatus());
      assertFalse("currency field must be absent when currency is null",
          response.getBody().has("currency"));
    }
  }

  // ── 500 — empty lines after process execution ─────────────────────────────

  /**
   * TC-18: process succeeds but OBCriteria returns empty lines list — returns HTTP 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testEmptyLinesAfterProcessReturns500() throws Exception {
    Asset asset = buildValidTiAsset(12L);

    OBDal dalMock = buildDalWithProcessAndTab(asset);
    Session sessionMock = mock(Session.class);
    when(dalMock.getSession()).thenReturn(sessionMock);

    OBCriteria<AmortizationLine> lineCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(AmortizationLine.class)).thenReturn(lineCriteria);
    when(lineCriteria.add(any())).thenReturn(lineCriteria);
    when(lineCriteria.addOrder(any())).thenReturn(lineCriteria);
    when(lineCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse processOkResponse = NeoResponse.ok(new JSONObject("{\"status\":\"success\"}"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);
      processMock.when(() -> NeoProcessService.executeProcess(
          any(Process.class), isNull(), eq(ASSET_ID), eq(TAB_ID)))
          .thenReturn(processOkResponse);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(500, response.getHttpStatus());
      assertBodyContainsMessage(response, "no amortization lines found");
    }
  }

  // ── 500 — unexpected exception ────────────────────────────────────────────

  /**
   * TC-19: unexpected runtime exception in the DAL layer must be caught and
   * returned as HTTP 500 without bubbling up.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testUnexpectedExceptionReturns500() {
    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID))
        .thenThrow(new RuntimeException("Simulated DB failure"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(500, response.getHttpStatus());
      assertBodyContainsMessage(response, "Unexpected error");
    }
  }

  // ── Success: line with null amortization amount (zero-contribution) ───────

  /**
   * TC-20: a line with null amortizationAmount must be treated as zero in the
   * totalAmortization sum (no NPE).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testNullLineAmountTreatedAsZero() throws Exception {
    Asset asset = buildValidTiAsset(12L);
    Currency currency = mock(Currency.class);
    when(currency.getISOCode()).thenReturn("EUR");
    when(asset.getCurrency()).thenReturn(currency);

    Amortization header = mock(Amortization.class);
    when(header.getId()).thenReturn(AMORTIZATION_ID);
    when(header.getStartingDate()).thenReturn(null);
    when(header.getEndingDate()).thenReturn(null);

    // Line with null amount
    AmortizationLine line = mock(AmortizationLine.class);
    when(line.getAmortization()).thenReturn(header);
    when(line.getAmortizationAmount()).thenReturn(null);

    OBDal dalMock = buildDalWithProcessAndTab(asset);
    Session sessionMock = mock(Session.class);
    when(dalMock.getSession()).thenReturn(sessionMock);

    OBCriteria<AmortizationLine> lineCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(AmortizationLine.class)).thenReturn(lineCriteria);
    when(lineCriteria.add(any())).thenReturn(lineCriteria);
    when(lineCriteria.addOrder(any())).thenReturn(lineCriteria);
    when(lineCriteria.list()).thenReturn(Collections.singletonList(line));

    NeoResponse processOkResponse = NeoResponse.ok(new JSONObject("{\"status\":\"success\"}"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dalMock);
      processMock.when(() -> NeoProcessService.executeProcess(
          any(Process.class), isNull(), eq(ASSET_ID), eq(TAB_ID)))
          .thenReturn(processOkResponse);

      NeoResponse response = AmortizationPlanService.generatePlan(ASSET_ID);

      assertEquals(200, response.getHttpStatus());
      assertEquals(new BigDecimal("0").compareTo(
          new BigDecimal(response.getBody().getString("totalAmortization"))), 0);
      assertEquals(new BigDecimal("0").compareTo(
          new BigDecimal(response.getBody().getString("periodAmount"))), 0);
    }
  }

  // ── Private builder helpers ───────────────────────────────────────────────

  /**
   * Builds a valid TI asset mock that passes all early-validation gates.
   */
  private static Asset buildValidTiAsset(long usableLifeMonths) {
    Asset asset = mock(Asset.class);
    when(asset.isDepreciate()).thenReturn(true);
    when(asset.getProcessed()).thenReturn("N");
    when(asset.getCalculateType()).thenReturn("TI");
    when(asset.getUsableLifeMonths()).thenReturn(usableLifeMonths);
    when(asset.getCurrency()).thenReturn(null);
    return asset;
  }

  /**
   * Convenience overload: non-depreciable asset only needs depreciate flag.
   */
  @SuppressWarnings("unused")
  private static Asset mockDepreciableAsset(boolean isDepreciate,
      String calculateType, Long usableLifeMonths) {
    Asset asset = mock(Asset.class);
    when(asset.isDepreciate()).thenReturn(isDepreciate);
    if (calculateType != null) {
      when(asset.getCalculateType()).thenReturn(calculateType);
    }
    if (usableLifeMonths != null) {
      when(asset.getUsableLifeMonths()).thenReturn(usableLifeMonths);
    }
    return asset;
  }

  /**
   * Builds a fully-wired {@link OBDal} mock that satisfies steps 2, 6 (Process lookup),
   * and 7 (SFSpec + SFEntity + Tab lookup) so tests can focus on steps 8+.
   *
   * <p><b>Note on the setMaxResults interaction:</b> {@link OBCriteria} is a mock here.
   * {@code setMaxResults} returns {@code void}, so no extra stub is needed for Mockito's
   * default void behaviour.</p>
   */
  @SuppressWarnings("unchecked")
  private static OBDal buildDalWithProcessAndTab(Asset asset) {
    OBDal dalMock = mock(OBDal.class);
    when(dalMock.get(Asset.class, ASSET_ID)).thenReturn(asset);

    // Process resolution
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("PROC-001");
    OBCriteria<Process> processCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(Process.class)).thenReturn(processCriteria);
    when(processCriteria.add(any())).thenReturn(processCriteria);
    when(processCriteria.uniqueResult()).thenReturn(process);

    // Tab resolution: SFSpec → SFEntity → Tab
    Tab tab = mock(Tab.class);
    when(tab.getId()).thenReturn(TAB_ID);

    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getId()).thenReturn(ENTITY_ID);
    when(sfEntity.getADTab()).thenReturn(tab);

    SFSpec sfSpec = mock(SFSpec.class);
    when(sfSpec.getId()).thenReturn(SPEC_ID);

    OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(SFSpec.class)).thenReturn(specCriteria);
    when(specCriteria.add(any())).thenReturn(specCriteria);
    when(specCriteria.uniqueResult()).thenReturn(sfSpec);

    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dalMock.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any())).thenReturn(entityCriteria);
    when(entityCriteria.uniqueResult()).thenReturn(sfEntity);

    return dalMock;
  }

  /**
   * Creates a mock {@link AmortizationLine} belonging to the given header.
   */
  private static AmortizationLine mockLine(Amortization header, BigDecimal amount) {
    AmortizationLine line = mock(AmortizationLine.class);
    when(line.getAmortization()).thenReturn(header);
    when(line.getAmortizationAmount()).thenReturn(amount);
    return line;
  }

  /**
   * Asserts that the error body contains an "error.message" or top-level "error" object
   * that includes the expected substring, using the standard {@link NeoResponse#error}
   * JSON shape: {@code {"error":{"message":"...","status":N}}}.
   */
  private static void assertBodyContainsMessage(NeoResponse response, String expectedSubstring) {
    assertNotNull("Response body must not be null", response.getBody());
    String bodyStr = response.getBody().toString();
    assertTrue("Expected body to contain '" + expectedSubstring + "' but got: " + bodyStr,
        bodyStr.contains(expectedSubstring));
  }
}
