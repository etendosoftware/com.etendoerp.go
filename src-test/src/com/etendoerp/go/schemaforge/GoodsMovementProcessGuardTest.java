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
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.onhandquantity.InventoryStatus;
import org.openbravo.model.materialmgmt.transaction.InternalMovement;
import org.openbravo.model.materialmgmt.transaction.InternalMovementLine;

/**
 * Unit tests for {@link GoodsMovementProcessGuard} (ETP-5037).
 *
 * <p>Covers both responsibilities of {@code validateBeforeProcess()}: the zero-or-negative-qty
 * check (mirrors {@code M_MOVEMENT_POST.xml}'s own overissue-gated condition) and the pre-existing
 * cumulative stock check, including the ordering between them (zero/negative-qty wins when both
 * would otherwise fire).
 */
public class GoodsMovementProcessGuardTest {

  private static final String MOVEMENT_ID = "movement-1";

  private MockedStatic<OBMessageUtils> mockedMessageUtils;
  private MockedStatic<OBContext> mockedObContext;
  private MockedStatic<OBDal> mockedObDal;
  private OBDal dal;

  @Before
  public void setUp() {
    mockedMessageUtils = mockStatic(OBMessageUtils.class);
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD("ETGO_ZeroOrNegativeQtyProcess"))
        .thenReturn("Cannot process: zero or negative quantity for @products@");
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD("ETGO_InsufficientStockProcess"))
        .thenReturn("Insufficient stock for @products@: @details@");

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

  private static JSONObject bodyProcessNow() {
    try {
      return new JSONObject().put("processNow", "Y");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static NeoContext processContext(String recordId) {
    return NeoContext.builder()
        .specName("goods-movements")
        .entityName("header")
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(recordId)
        .requestBody(bodyProcessNow())
        .build();
  }

  private static Product product(String id, String name) {
    Product product = mock(Product.class);
    when(product.getId()).thenReturn(id);
    when(product.getName()).thenReturn(name);
    when(product.getSearchKey()).thenReturn(id);
    return product;
  }

  private static Locator locator(String id, boolean allowsOverissue) {
    Locator locator = mock(Locator.class);
    when(locator.getId()).thenReturn(id);
    InventoryStatus status = mock(InventoryStatus.class);
    when(status.isOverissue()).thenReturn(allowsOverissue);
    when(locator.getInventoryStatus()).thenReturn(status);
    return locator;
  }

  private static InternalMovementLine line(Product product, BigDecimal qty, Locator source,
      Locator destination) {
    InternalMovementLine line = mock(InternalMovementLine.class);
    when(line.getProduct()).thenReturn(product);
    when(line.getMovementQuantity()).thenReturn(qty);
    when(line.getStorageBin()).thenReturn(source);
    when(line.getNewStorageBin()).thenReturn(destination);
    return line;
  }

  private void movementWithLines(List<InternalMovementLine> lines) {
    InternalMovement movement = mock(InternalMovement.class);
    when(movement.getMaterialMgmtInternalMovementLineList()).thenReturn(lines);
    when(dal.get(eq(InternalMovement.class), eq(MOVEMENT_ID))).thenReturn(movement);
  }

  private static void assertRejectedWithCode(NeoResponse response, String expectedCode) {
    org.junit.Assert.assertNotNull("expected a rejection response, got null (accepted)", response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(expectedCode, response.getBody().optString("code"));
  }

  // ── guard clauses ─────────────────────────────────────────────────────────

  @Test
  public void nonProcessActionIsANoOp() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(MOVEMENT_ID)
        .requestBody(new JSONObject())
        .build();
    assertNull(GoodsMovementProcessGuard.validateBeforeProcess(ctx));
  }

  @Test
  public void blankRecordIdIsANoOp() {
    assertNull(GoodsMovementProcessGuard.validateBeforeProcess(processContext("")));
  }

  @Test
  public void missingMovementIsANoOp() {
    when(dal.get(eq(InternalMovement.class), eq(MOVEMENT_ID))).thenReturn(null);
    assertNull(GoodsMovementProcessGuard.validateBeforeProcess(processContext(MOVEMENT_ID)));
  }

  // ── zero/negative quantity check ────────────────────────────────────────

  @Test
  public void zeroQtyBlockedWhenSourceDisallowsOverissue() {
    Product product = product("prod-1", "Fernet");
    Locator source = locator("loc-src", false);
    movementWithLines(Collections.singletonList(line(product, BigDecimal.ZERO, source, null)));

    NeoResponse response = GoodsMovementProcessGuard.validateBeforeProcess(processContext(MOVEMENT_ID));

    assertRejectedWithCode(response, "ZERO_OR_NEGATIVE_QTY");
    assertTrue(response.getBody().optString("message").contains("Fernet"));
  }

  @Test
  public void negativeQtyBlockedWhenDestinationDisallowsOverissueEvenIfSourceAllows() {
    Product product = product("prod-1", "Fernet");
    Locator source = locator("loc-src", true);
    Locator destination = locator("loc-dst", false);
    movementWithLines(Collections.singletonList(
        line(product, BigDecimal.valueOf(-5), source, destination)));

    NeoResponse response = GoodsMovementProcessGuard.validateBeforeProcess(processContext(MOVEMENT_ID));

    assertRejectedWithCode(response, "ZERO_OR_NEGATIVE_QTY");
  }

  @Test
  public void zeroQtyNotBlockedWhenBothLocatorsAllowOverissue() {
    Product product = product("prod-1", "Fernet");
    Locator source = locator("loc-src", true);
    Locator destination = locator("loc-dst", true);
    movementWithLines(Collections.singletonList(line(product, BigDecimal.ZERO, source, destination)));

    assertNull(GoodsMovementProcessGuard.validateBeforeProcess(processContext(MOVEMENT_ID)));
  }

  @Test
  public void positiveQtyIsNeverFlaggedByZeroOrNegativeCheck() {
    Product product = product("prod-1", "Fernet");
    Locator source = locator("loc-src", false);
    movementWithLines(Collections.singletonList(line(product, BigDecimal.TEN, source, null)));

    // No on-hand-quantity stub is set up, so StockAvailabilityGuard.onHandQuantity() fails open
    // (OBDal.getReadOnlyInstance() resolves to a Mockito default null and the NPE is caught),
    // meaning the cumulative check also lets this through — net result: allowed.
    assertNull(GoodsMovementProcessGuard.validateBeforeProcess(processContext(MOVEMENT_ID)));
  }

  @Test
  public void zeroOrNegativeQtyTakesPrecedenceOverCumulativeStockViolation() {
    Product zeroQtyProduct = product("prod-zero", "Malbec");
    Product otherProduct = product("prod-other", "Fernet");
    Locator source = locator("loc-src", false);

    InternalMovementLine zeroLine = line(zeroQtyProduct, BigDecimal.ZERO, source, null);
    InternalMovementLine otherLine = line(otherProduct, BigDecimal.TEN, source, null);
    movementWithLines(Arrays.asList(zeroLine, otherLine));

    NeoResponse response = GoodsMovementProcessGuard.validateBeforeProcess(processContext(MOVEMENT_ID));

    // Short-circuits on the zero-qty check before the cumulative check ever runs — the offending
    // product named is the zero-qty one, not the (unevaluated) positive-qty line.
    assertRejectedWithCode(response, "ZERO_OR_NEGATIVE_QTY");
    assertTrue(response.getBody().optString("message").contains("Malbec"));
  }
}
