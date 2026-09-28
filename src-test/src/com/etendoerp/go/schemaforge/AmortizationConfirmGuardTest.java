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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.assetmgmt.Amortization;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;

/**
 * Unit tests for {@link AmortizationConfirmGuard} (ETP-5414).
 *
 * <p>Covers both responsibilities: the confirm-only lines validation (whitelisted away from
 * reactivate, since that direction has no lines precondition in {@code a_amortization_process})
 * and the unconditional PATCH/PUT block on {@code processed}, mirroring
 * {@link GoodsMovementProcessGuardTest}'s structure.
 */
public class AmortizationConfirmGuardTest {

  private static final String RECORD_ID = "amortization-1";

  private MockedStatic<OBMessageUtils> mockedMessageUtils;
  private MockedStatic<OBContext> mockedObContext;
  private MockedStatic<OBDal> mockedObDal;
  private OBDal dal;

  @Before
  public void setUp() {
    mockedMessageUtils = mockStatic(OBMessageUtils.class);
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD("ETGO_AmortizationNoLines"))
        .thenReturn("Has no amortization lines");
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD("ETGO_AmortizationLineMissingPercentage"))
        .thenReturn("There are lines with a missing amortization percentage");
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD("ETGO_AmortizationLineInvalidAmount"))
        .thenReturn("There are lines with a zero or negative amount");
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD("ETGO_AmortizationProcessedDirectUpdateBlocked"))
        .thenReturn("Processed cannot be updated directly");

    mockedObContext = mockStatic(OBContext.class);
    mockedObContext.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
    mockedObContext.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

    dal = mock(OBDal.class);
    mockedObDal = mockStatic(OBDal.class);
    mockedObDal.when(OBDal::getInstance).thenReturn(dal);
  }

  @After
  public void clearMocks() {
    mockedObDal.close();
    mockedObContext.close();
    mockedMessageUtils.close();
    Mockito.framework().clearInlineMocks();
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private static NeoContext actionContext(String fieldName, String recordId) {
    return NeoContext.builder()
        .specName("amortization")
        .entityName("header")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(fieldName)
        .recordId(recordId)
        .build();
  }

  private static NeoContext patchContext(String method, JSONObject body) {
    return NeoContext.builder()
        .specName("amortization")
        .entityName("header")
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .recordId(RECORD_ID)
        .requestBody(body)
        .build();
  }

  private static JSONObject bodyWith(String key, String value) {
    try {
      return new JSONObject().put(key, value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static AmortizationLine line(BigDecimal percentage, BigDecimal amount) {
    AmortizationLine line = mock(AmortizationLine.class);
    when(line.getAmortizationPercentage()).thenReturn(percentage);
    when(line.getAmortizationAmount()).thenReturn(amount);
    return line;
  }

  private void amortization(String processed, List<AmortizationLine> lines) {
    Amortization amortization = mock(Amortization.class);
    when(amortization.getProcessed()).thenReturn(processed);
    when(amortization.getFinancialMgmtAmortizationLineList()).thenReturn(lines);
    when(dal.get(eq(Amortization.class), eq(RECORD_ID))).thenReturn(amortization);
  }

  // ─── confirm-branch lines validation ────────────────────────────────────

  @Test
  public void testConfirmWithValidLinesPasses() {
    amortization("N", Arrays.asList(
        line(new BigDecimal("10"), new BigDecimal("100")),
        line(new BigDecimal("20"), new BigDecimal("200"))));
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID)));
  }

  @Test
  public void testConfirmWithNoLinesIsRejected() {
    amortization("N", Collections.emptyList());
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID));
    assertRejected(response, "ETGO_AmortizationNoLines", "Has no amortization lines");
  }

  @Test
  public void testConfirmWithNullLinesIsRejected() {
    amortization("N", null);
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID));
    assertRejected(response, "ETGO_AmortizationNoLines", "Has no amortization lines");
  }

  @Test
  public void testConfirmWithMissingPercentageIsRejected() {
    amortization("N", Arrays.asList(
        line(new BigDecimal("10"), new BigDecimal("100")),
        line(null, new BigDecimal("200"))));
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID));
    assertRejected(response, "ETGO_AmortizationLineMissingPercentage",
        "There are lines with a missing amortization percentage");
  }

  @Test
  public void testConfirmWithZeroAmountIsRejected() {
    amortization("N", Collections.singletonList(line(new BigDecimal("10"), BigDecimal.ZERO)));
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID));
    assertRejected(response, "ETGO_AmortizationLineInvalidAmount",
        "There are lines with a zero or negative amount");
  }

  @Test
  public void testConfirmWithNegativeAmountIsRejected() {
    amortization("N", Collections.singletonList(line(new BigDecimal("10"), new BigDecimal("-5"))));
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID));
    assertRejected(response, "ETGO_AmortizationLineInvalidAmount",
        "There are lines with a zero or negative amount");
  }

  @Test
  public void testConfirmWithNullAmountIsRejected() {
    amortization("N", Collections.singletonList(line(new BigDecimal("10"), null)));
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID));
    assertRejected(response, "ETGO_AmortizationLineInvalidAmount",
        "There are lines with a zero or negative amount");
  }

  @Test
  public void testMissingPercentageWinsOverInvalidAmount() {
    // Both problems present on the same document — percentage is checked first (matches the
    // client-side validateConfirmEligibility's own check order).
    amortization("N", Collections.singletonList(line(null, BigDecimal.ZERO)));
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID));
    assertRejected(response, "ETGO_AmortizationLineMissingPercentage",
        "There are lines with a missing amortization percentage");
  }

  // ─── reactivate branch — no lines validation at all ─────────────────────

  @Test
  public void testReactivateIsNeverValidatedEvenWithNoLines() {
    // Processed='Y' → this call is a REACTIVATE, not a confirm. No lines precondition applies.
    amortization("Y", Collections.emptyList());
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID)));
  }

  @Test
  public void testReactivateIsNeverValidatedWithInvalidLines() {
    amortization("Y", Collections.singletonList(line(null, BigDecimal.ZERO)));
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID)));
  }

  // ─── endpoint/field guards ───────────────────────────────────────────────

  @Test
  public void testNonActionEndpointIsIgnored() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.DEFAULTS)
        .fieldName("Processed").recordId(RECORD_ID).build();
    assertNull(AmortizationConfirmGuard.validateBeforeAction(ctx));
  }

  @Test
  public void testActionEndpointWithDifferentFieldNameIsIgnored() {
    amortization("N", Collections.emptyList());
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("post", RECORD_ID)));
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("unpost", RECORD_ID)));
  }

  @Test
  public void testBlankRecordIdIsIgnored() {
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", "")));
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", null)));
  }

  @Test
  public void testRecordNotFoundIsIgnored() {
    when(dal.get(eq(Amortization.class), eq(RECORD_ID))).thenReturn(null);
    assertNull(AmortizationConfirmGuard.validateBeforeAction(actionContext("Processed", RECORD_ID)));
  }

  // ─── PATCH/PUT direct-write block ────────────────────────────────────────

  @Test
  public void testPatchWithProcessedFieldIsRejected() {
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(
        patchContext("PATCH", bodyWith("processed", "Y")));
    assertRejected(response, "ETGO_AmortizationProcessedDirectUpdateBlocked",
        "Processed cannot be updated directly");
  }

  @Test
  public void testPutWithProcessedFieldIsRejected() {
    NeoResponse response = AmortizationConfirmGuard.validateBeforeAction(
        patchContext("PUT", bodyWith("processed", "N")));
    assertRejected(response, "ETGO_AmortizationProcessedDirectUpdateBlocked",
        "Processed cannot be updated directly");
  }

  @Test
  public void testPatchRejectionShortCircuitsBeforeReadingTheRecord() {
    // The block is unconditional — it must not even look at OBDal to decide, since the whole
    // point is that the classic process (and its own record-state read) never runs on this path.
    AmortizationConfirmGuard.validateBeforeAction(patchContext("PATCH", bodyWith("processed", "Y")));
    verify(dal, never()).get(eq(Amortization.class), eq(RECORD_ID));
  }

  @Test
  public void testPatchWithoutProcessedFieldPasses() {
    assertNull(AmortizationConfirmGuard.validateBeforeAction(
        patchContext("PATCH", bodyWith("description", "hello"))));
  }

  @Test
  public void testPatchWithNullBodyPasses() {
    assertNull(AmortizationConfirmGuard.validateBeforeAction(patchContext("PATCH", null)));
  }

  @Test
  public void testNonPatchPutCrudMethodsAreIgnored() {
    // GET/POST/DELETE on the CRUD endpoint are unrelated to a field write — never blocked here.
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST").recordId(RECORD_ID).requestBody(bodyWith("processed", "Y")).build();
    assertNull(AmortizationConfirmGuard.validateBeforeAction(ctx));
  }

  // ─── assertion helper ────────────────────────────────────────────────────

  private static void assertRejected(NeoResponse response, String expectedCode, String expectedMessage) {
    assertTrue("Expected a rejection NeoResponse", response != null);
    assertEquals(400, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(expectedCode, body.optString("code", null));
    assertEquals(expectedMessage, body.optString("message", null));
    assertEquals("error", body.optString("status", null));
  }
}
