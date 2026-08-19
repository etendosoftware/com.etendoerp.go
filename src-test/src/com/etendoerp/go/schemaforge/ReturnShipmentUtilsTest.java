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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.criterion.SimpleExpression;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReturnShipmentUtils#resolvePriceFromSourceInvoiceLine} and
 * {@link ReturnShipmentUtils#findReturnDocTypeForOrg} (ETP-4737).
 *
 * <p>Covers:
 * <ul>
 *   <li>null shipmentLineId → returns fallback without touching OBDal.</li>
 *   <li>Session returns a row with price &gt; 0 → returns that price.</li>
 *   <li>Session returns a row with null price → returns fallback.</li>
 *   <li>Session returns an empty list → returns fallback (logs warn).</li>
 *   <li>Session.createQuery throws → returns fallback (logs warn, no rethrow).</li>
 *   <li>{@code findReturnDocTypeForOrg} — requireRectificative gating on column presence, org
 *       match, org-'0' fallback, and first-candidate fallback.</li>
 * </ul>
 */
public class ReturnShipmentUtilsTest {

  private static final BigDecimal FALLBACK = new BigDecimal("9.99");

  // ── Case 1: null shipmentLineId → fallback, OBDal never called ──────────────

  @Test
  public void resolvePriceFromSourceInvoiceLine_nullId_returnsFallbackWithoutCallingOBDal() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          null, "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
      // OBDal.getInstance() must not be touched when shipmentLineId is null
      verify(dal, never()).getSession();
    }
  }

  // ── Case 2: session returns a row with price > 0 → returns that price ───────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithPositivePrice_returnsThatPrice()
      throws Exception {
    BigDecimal expectedPrice = new BigDecimal("42.50");

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(expectedPrice));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-001", "unitPrice", FALLBACK);

      assertEquals(expectedPrice, result);
    }
  }

  // ── Case 3: session returns a row with null price → fallback ────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithNullPrice_returnsFallback()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      // The list has one element but it is null
      when(query.list()).thenReturn(Collections.singletonList(null));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-002", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Case 4: session returns empty list → fallback (log warn) ────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_emptyList_returnsFallback() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.<BigDecimal>emptyList());

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-003", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Case 5: session throws → fallback (log warn, no rethrow) ────────────────

  @Test
  public void resolvePriceFromSourceInvoiceLine_sessionThrows_returnsFallbackWithoutRethrow() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(BigDecimal.class)))
          .thenThrow(new RuntimeException("HibernateException: session closed"));

      // Must not throw — exception is swallowed and fallback is returned
      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-004", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── Edge: row with price = 0 → treated as "no price" → fallback ─────────────

  @Test
  @SuppressWarnings("unchecked")
  public void resolvePriceFromSourceInvoiceLine_rowWithZeroPrice_returnsFallback()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Query<BigDecimal> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(BigDecimal.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(BigDecimal.ZERO));

      BigDecimal result = ReturnShipmentUtils.resolvePriceFromSourceInvoiceLine(
          "line-005", "unitPrice", FALLBACK);

      assertEquals(FALLBACK, result);
    }
  }

  // ── findReturnDocTypeForOrg (ETP-4737) ───────────────────────────────────────

  private static OBCriteria<DocumentType> mockCriteria(OBDal dal, List<DocumentType> result) {
    @SuppressWarnings("unchecked")
    OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
    when(criteria.list()).thenReturn(result);
    return criteria;
  }

  private static DocumentType docTypeWithOrg(String orgId) {
    DocumentType dt = mock(DocumentType.class);
    Organization org = mock(Organization.class);
    when(dt.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn(orgId);
    return dt;
  }

  /**
   * {@code requireRectificative=false} must never add the
   * {@code EM_Etsg_Isrectificative} restriction, regardless of column presence.
   */
  @Test
  public void findReturnDocTypeForOrg_requireRectificativeFalse_doesNotAddRectificativeRestriction() {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBCriteria<DocumentType> criteria = mockCriteria(dal, Collections.emptyList());

      ReturnShipmentUtils.findReturnDocTypeForOrg("org-1", "APC", false, false, false);

      verify(criteria, never()).add(argThat(ReturnShipmentUtilsTest::isRectificativeRestriction));
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
  }

  /**
   * {@code requireRectificative=true} + column present adds the restriction with value TRUE —
   * this is how the new unified "Factura Rectificativa" doc type is distinguished from a legacy
   * doc type sharing the same {@code docCategory}.
   */
  @Test
  public void findReturnDocTypeForOrg_requireRectificativeTrueColumnPresent_addsRestriction() {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBCriteria<DocumentType> criteria = mockCriteria(dal, Collections.emptyList());

      ReturnShipmentUtils.findReturnDocTypeForOrg("org-1", "ARC", true, false, true);

      verify(criteria, atLeastOnce()).add(argThat(ReturnShipmentUtilsTest::isRectificativeRestriction));
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
  }

  /**
   * {@code requireRectificative=true} but the column is absent (SIF General not installed)
   * degrades gracefully — the restriction is silently skipped instead of blowing up the lookup.
   */
  @Test
  public void findReturnDocTypeForOrg_requireRectificativeTrueColumnAbsent_skipsRestriction() {
    RectificativeSupport.setColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBCriteria<DocumentType> criteria = mockCriteria(dal, Collections.emptyList());

      ReturnShipmentUtils.findReturnDocTypeForOrg("org-1", "APC", false, false, true);

      verify(criteria, never()).add(argThat(ReturnShipmentUtilsTest::isRectificativeRestriction));
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
  }

  private static boolean isRectificativeRestriction(Object criterion) {
    if (!(criterion instanceof SimpleExpression)) {
      return false;
    }
    SimpleExpression expr = (SimpleExpression) criterion;
    return DocumentType.PROPERTY_ETSGISRECTIFICATIVE.equals(expr.getPropertyName())
        && Boolean.TRUE.equals(expr.getValue());
  }

  /** A candidate matching the receipt/shipment's own org is returned directly. */
  @Test
  public void findReturnDocTypeForOrg_matchesOwnOrg_returnsIt() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType match = docTypeWithOrg("org-1");
      mockCriteria(dal, Collections.singletonList(match));

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, false);

      assertSame(match, result);
    }
  }

  /** No candidate matches the org, but one has org {@code '0'} — org-'0' fallback wins. */
  @Test
  public void findReturnDocTypeForOrg_noOwnOrgMatch_fallsBackToOrgZero() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType other = docTypeWithOrg("org-99");
      DocumentType zero = docTypeWithOrg("0");
      mockCriteria(dal, java.util.Arrays.asList(other, zero));

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, false);

      assertSame(zero, result);
    }
  }

  /** No org or org-'0' match — falls back to the first candidate. */
  @Test
  public void findReturnDocTypeForOrg_noMatchAtAll_fallsBackToFirstCandidate() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      DocumentType first = docTypeWithOrg("org-99");
      mockCriteria(dal, Collections.singletonList(first));

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, false);

      assertSame(first, result);
    }
  }

  /** No candidates at all — returns {@code null}. */
  @Test
  public void findReturnDocTypeForOrg_noCandidates_returnsNull() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      mockCriteria(dal, Collections.emptyList());

      DocumentType result = ReturnShipmentUtils.findReturnDocTypeForOrg(
          "org-1", "APC", false, false, true);

      assertNull(result);
    }
  }

  // ── addReturnInvoiceLines (ETP-4737) — Sales-vs-Purchase sign asymmetry ──────
  //
  // Sales-side return receipts (RFC Receipt) store movementQuantity POSITIVE; Purchase-side
  // return shipments (RTV Shipment) already store it NEGATIVE. The rectificative invoice line
  // must ALWAYS come out negative regardless of the source's own sign convention. The old
  // `.negate()` was correct for Sales but flipped Purchase-side quantities back to positive
  // (confirmed live on REC-1000007) — fixed to `.abs().negate()`.

  /**
   * Builds the minimal mock graph for {@code addReturnInvoiceLines} / {@code buildAndSaveInvoiceLine}
   * with every optional branch short-circuited: {@code invoice.getPriceList()} is null and
   * {@code retLine.getCanceledInoutLine()} is null so neither price-resolution branch fires, the
   * built {@link InvoiceLine} already carries a non-zero unit price and a non-null tax so
   * {@code resolveApplicableTax}'s live {@code Tax.get()} lookup is never reached. Captures the
   * {@code qty} argument passed to {@code createShipmentInvoiceLine} and asserts it against
   * {@code expectedQty}.
   */
  private void assertAddReturnInvoiceLinesProducesQty(BigDecimal movementQty, BigDecimal expectedQty) {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getPriceList()).thenReturn(null);

      ShipmentInOutLine retLine = mock(ShipmentInOutLine.class);
      when(retLine.getMovementQuantity()).thenReturn(movementQty);
      Product product = mock(Product.class);
      when(retLine.getProduct()).thenReturn(product);
      when(retLine.getCanceledInoutLine()).thenReturn(null);

      CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getUnitPrice()).thenReturn(new BigDecimal("10.00"));
      TaxRate tax = mock(TaxRate.class);
      when(il.getTax()).thenReturn(tax);

      ArgumentCaptor<BigDecimal> qtyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
      when(createDraftInvoiceHandler.createShipmentInvoiceLine(
          eq(invoice), eq(retLine), qtyCaptor.capture(), anyLong()))
          .thenReturn(il);

      ReturnShipmentUtils.addReturnInvoiceLines(
          invoice, Collections.singletonList(retLine), createDraftInvoiceHandler);

      assertEquals(0, expectedQty.compareTo(qtyCaptor.getValue()));
      verify(il).setGoodsShipmentLine(retLine);
      verify(dal).save(il);
    }
  }

  /** Sales-side: source movementQuantity is POSITIVE → invoice line qty must come out negative. */
  @Test
  public void addReturnInvoiceLines_salesPositiveMovementQty_producesNegativeInvoicedQty() {
    assertAddReturnInvoiceLinesProducesQty(new BigDecimal("5"), new BigDecimal("-5"));
  }

  /**
   * Purchase-side regression: source movementQuantity is already NEGATIVE (RTV Shipment
   * convention) → invoice line qty must STILL come out negative. Before the fix
   * ({@code .negate()} instead of {@code .abs().negate()}), this flipped back to positive.
   */
  @Test
  public void addReturnInvoiceLines_purchaseNegativeMovementQty_stillProducesNegativeInvoicedQty() {
    assertAddReturnInvoiceLinesProducesQty(new BigDecimal("-5"), new BigDecimal("-5"));
  }

  /** {@code null} movementQuantity is treated as zero and the line is skipped entirely. */
  @Test
  public void addReturnInvoiceLines_nullMovementQty_lineSkipped() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOutLine retLine = mock(ShipmentInOutLine.class);
      when(retLine.getMovementQuantity()).thenReturn(null);
      Product product = mock(Product.class);
      when(retLine.getProduct()).thenReturn(product);

      Invoice invoice = mock(Invoice.class);
      CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);

      ReturnShipmentUtils.addReturnInvoiceLines(
          invoice, Collections.singletonList(retLine), createDraftInvoiceHandler);

      verify(createDraftInvoiceHandler, never())
          .createShipmentInvoiceLine(any(), any(), any(), anyLong());
      verify(dal, never()).save(any(InvoiceLine.class));
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────────────
  // ETP-4863 — the DAL "import from source document" path
  //
  // The CRUD path (POST /neo/.../lines) is already guarded by
  // NeoHandlerUtils.injectDefaultLocatorIfMissing. Return documents never go through it:
  // their lines are imported from a source shipment/receipt and persisted DIRECTLY by DAL,
  // so the locator has to be anchored to the RETURN document's own header warehouse here.
  // Real failure: return header on warehouse PRINCIPAL, source document's lines on
  // SECONDARY → every stock transaction landed in SECONDARY.
  // ───────────────────────────────────────────────────────────────────────────────────────

  private static final String WH_PRINCIPAL = LocatorTestSupport.WH_PRINCIPAL;
  private static final String WH_SECONDARY = LocatorTestSupport.WH_SECONDARY;
  private static final String LOC_PRINCIPAL_DEFAULT = LocatorTestSupport.LOC_PRINCIPAL_DEFAULT;

  private static void stubDefaultLocatorLookup(OBDal dal, Locator result) {
    LocatorTestSupport.stubDefaultLocatorLookup(dal, result);
  }

  private static Warehouse mockWarehouse(String id) {
    return LocatorTestSupport.mockWarehouse(id);
  }

  private static Locator mockLocator(String id, Warehouse warehouse) {
    return LocatorTestSupport.mockLocator(id, warehouse);
  }

  /**
   * {@code buildAndSaveReturnLine} copied the SOURCE line's storage bin verbatim. When the
   * source document lives in another warehouse than the return header, the new line ends up
   * pointing at a bin of that other warehouse — and the stock transactions follow the bin,
   * not the header. The bin must be replaced by the header warehouse's default locator.
   */
  @Test
  public void buildAndSaveReturnLine_sourceBinFromAnotherWarehouse_anchorsToHeaderWarehouse() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator sourceBin = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(sourceBin);

      ShipmentInOutLine retLine = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(retLine);

      Locator headerDefaultBin = mockLocator(LOC_PRINCIPAL_DEFAULT, headerWarehouse);
      stubDefaultLocatorLookup(dal, headerDefaultBin);

      ReturnShipmentUtils.buildAndSaveReturnLine(doc, sourceLine, 10L, BigDecimal.ONE);

      verify(retLine, never()).setStorageBin(sourceBin);
      verify(retLine).setStorageBin(headerDefaultBin);
    }
  }

  /**
   * A source bin that ALREADY belongs to the return header's warehouse is a legitimate,
   * deliberate bin choice and must survive untouched — the guarantee is about the warehouse,
   * not about collapsing every line onto the warehouse's single default locator (same rule
   * {@code NeoHandlerUtils.injectDefaultLocatorIfMissing} applies on the CRUD path).
   */
  @Test
  public void buildAndSaveReturnLine_sourceBinInHeaderWarehouse_isKept() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator sourceBin = mockLocator("loc-principal-specific", headerWarehouse);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(sourceBin);

      ShipmentInOutLine retLine = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(retLine);

      ReturnShipmentUtils.buildAndSaveReturnLine(doc, sourceLine, 10L, BigDecimal.ONE);

      verify(retLine).setStorageBin(sourceBin);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }

  /**
   * {@code assignBinsToLines} had the precedence INVERTED: it preferred the source document's
   * bin over the line's own, so it could OVERWRITE an already-correct bin with one from the
   * source document's warehouse. Both lines below assert the corrected rule:
   * <ul>
   *   <li>a line whose own bin is already in the header warehouse is left alone;</li>
   *   <li>a line with no bin falls back to the header warehouse's default, NOT to the
   *       source document's bin.</li>
   * </ul>
   */
  @Test
  public void assignBinsToLines_headerWarehouseWinsOverSourceDocumentBin() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator binInSecondary = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      Locator binInPrincipal = mockLocator("loc-principal-A", headerWarehouse);
      Locator headerDefaultBin = mockLocator(LOC_PRINCIPAL_DEFAULT, headerWarehouse);

      ShipmentInOutLine origLineA = mock(ShipmentInOutLine.class);
      when(origLineA.getStorageBin()).thenReturn(binInSecondary);
      ShipmentInOutLine origLineB = mock(ShipmentInOutLine.class);
      when(origLineB.getStorageBin()).thenReturn(binInSecondary);

      ShipmentInOutLine lineWithValidOwnBin = mock(ShipmentInOutLine.class);
      when(lineWithValidOwnBin.getStorageBin()).thenReturn(binInPrincipal);
      when(lineWithValidOwnBin.getCanceledInoutLine()).thenReturn(origLineA);

      ShipmentInOutLine lineWithoutBin = mock(ShipmentInOutLine.class);
      when(lineWithoutBin.getStorageBin()).thenReturn(null);
      when(lineWithoutBin.getCanceledInoutLine()).thenReturn(origLineB);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Arrays.asList(lineWithValidOwnBin, lineWithoutBin));

      stubDefaultLocatorLookup(dal, headerDefaultBin);

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(lineWithValidOwnBin, never()).setStorageBin(any());
      verify(lineWithoutBin, never()).setStorageBin(binInSecondary);
      verify(lineWithoutBin).setStorageBin(headerDefaultBin);
    }
  }

  /**
   * PRODUCTION REPRODUCTION (RFC Receipt 1000057/1000059/1000061/1000063). The user did NOT
   * import lines from the source document — she added each line BY HAND in the window, so the
   * line POST went through the NeoHandler and {@code injectDefaultLocatorIfMissing} already set
   * the header warehouse's bin correctly ({@code AG-0-0-0} of "Almacen GO"). Then the HEADER-level
   * {@code fillMissingStorageBins} → {@code assignBinsToLines} ran, saw a non-null
   * {@code canceledInoutLine} (the line still references the source receipt's line even when typed
   * by hand) and, because the precedence was INVERTED, overwrote that correct bin with the source
   * document's {@code AS-0-0-0} of "Almacén Secundario". The whole stock movement then landed in
   * the wrong warehouse.
   *
   * <p>The line's own, already-correct bin must win over the source document's, and
   * {@code setStorageBin} must not be called at all.
   */
  @Test
  public void assignBinsToLines_manuallyTypedLineWithCorrectBin_isNotOverwrittenBySourceDocument() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      // AG-0-0-0 — set by the line NeoHandler when the user typed the line by hand.
      Locator binSetByLineHandler = mockLocator("loc-AG-0-0-0", headerWarehouse);
      // AS-0-0-0 — the source receipt's bin, in the secondary warehouse.
      Locator sourceDocumentBin = mockLocator("loc-AS-0-0-0", mockWarehouse(WH_SECONDARY));

      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(sourceDocumentBin);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getStorageBin()).thenReturn(binSetByLineHandler);
      when(line.getCanceledInoutLine()).thenReturn(sourceLine);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(line, never()).setStorageBin(sourceDocumentBin);
      verify(line, never()).setStorageBin(any());
      verify(dal, never()).save(line);
      // Nothing to correct → no default-locator lookup should be issued either.
      verify(dal, never()).createCriteria(Locator.class);
    }
  }

  /**
   * Cascade step 4 at this call site: the header warehouse has NO active locator, so the foreign
   * bin cannot be anchored. It must be written as {@code null} rather than left in place —
   * skipping the write would keep the line pointing at the wrong warehouse, which is the exact
   * silent corruption this method exists to prevent. Uniform with
   * {@code createReturnLineShell} and {@code createAndLinkLine}.
   */
  @Test
  public void assignBinsToLines_headerWarehouseHasNoLocator_clearsForeignBinInsteadOfKeepingIt() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator binInSecondary = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getStorageBin()).thenReturn(binInSecondary);
      when(line.getCanceledInoutLine()).thenReturn(null);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      // No default-flagged bin AND no active bin at all → cascade bottoms out at null.
      LocatorTestSupport.stubLocatorCascade(dal, null);

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(line).setStorageBin(null);
      verify(dal).save(line);
    }
  }

  /**
   * Cascade step 3 at this call site: the header warehouse has bins but none flagged default.
   * The foreign bin must still be replaced, by the lowest-searchKey active bin.
   */
  @Test
  public void assignBinsToLines_headerWarehouseHasNoDefaultBin_usesAnyActiveBin() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator binInSecondary = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      Locator anyActiveBin = mockLocator("loc-principal-any", headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getStorageBin()).thenReturn(binInSecondary);
      when(line.getCanceledInoutLine()).thenReturn(null);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      LocatorTestSupport.stubLocatorCascade(dal, anyActiveBin);

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(line).setStorageBin(anyActiveBin);
      verify(line, never()).setStorageBin(binInSecondary);
    }
  }

  /**
   * W1 — the warehouse's fallback bin depends only on the header, so it must be resolved ONCE per
   * document no matter how many lines need correcting. Hibernate's L1 cache does not deduplicate
   * criteria queries, so a per-line lookup would issue N queries on a document with N bad lines.
   */
  @Test
  public void assignBinsToLines_resolvesWarehouseFallbackBinOncePerDocument() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator binInSecondary = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      Locator headerDefaultBin = mockLocator(LOC_PRINCIPAL_DEFAULT, headerWarehouse);

      ShipmentInOutLine lineA = mock(ShipmentInOutLine.class);
      when(lineA.getStorageBin()).thenReturn(binInSecondary);
      when(lineA.getCanceledInoutLine()).thenReturn(null);
      ShipmentInOutLine lineB = mock(ShipmentInOutLine.class);
      when(lineB.getStorageBin()).thenReturn(binInSecondary);
      when(lineB.getCanceledInoutLine()).thenReturn(null);
      ShipmentInOutLine lineC = mock(ShipmentInOutLine.class);
      when(lineC.getStorageBin()).thenReturn(null);
      when(lineC.getCanceledInoutLine()).thenReturn(null);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Arrays.asList(lineA, lineB, lineC));

      stubDefaultLocatorLookup(dal, headerDefaultBin);

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(lineA).setStorageBin(headerDefaultBin);
      verify(lineB).setStorageBin(headerDefaultBin);
      verify(lineC).setStorageBin(headerDefaultBin);
      // Three lines corrected, but the warehouse lookup runs exactly once.
      verify(dal, Mockito.times(1)).createCriteria(Locator.class);
    }
  }

  /**
   * A line whose own bin belongs to a DIFFERENT warehouse than the header must be corrected,
   * even though it is non-null — the pre-existing "only fill when both are null" guard let
   * exactly this case through.
   */
  @Test
  public void assignBinsToLines_ownBinFromAnotherWarehouse_isCorrected() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator binInSecondary = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      Locator headerDefaultBin = mockLocator(LOC_PRINCIPAL_DEFAULT, headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getStorageBin()).thenReturn(binInSecondary);
      when(line.getCanceledInoutLine()).thenReturn(null);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Collections.singletonList(line));

      stubDefaultLocatorLookup(dal, headerDefaultBin);

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(line).setStorageBin(headerDefaultBin);
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────────────
  // ETP-4863 QA round — additional edge cases not exercised by DEV/REVIEW
  // ───────────────────────────────────────────────────────────────────────────────────────

  /**
   * QA edge case: a line's own bin is wrong (another warehouse), but its {@code
   * canceledInoutLine} happens to carry a bin that IS in the header warehouse — just not the
   * warehouse's flagged-default one. {@code resolveCandidateBin} only falls back to the source
   * line when the line's OWN bin is {@code null}; when it is non-null (even if wrong), the
   * source line's bin is never even read. The line is therefore corrected to the warehouse's
   * anchor bin, NOT to the source line's (coincidentally valid) bin — the source's
   * {@code getStorageBin()} must not be consulted at all in this branch.
   *
   * <p>This is a real behavior fork from the pre-fix code, which prioritized {@code
   * canceledInoutLine}'s bin unconditionally: the old code would have read the source's bin
   * first, found it valid for the header warehouse, and kept that SPECIFIC locator — passing
   * this same assertion by coincidence. This test pins the NEW precedence at the query level
   * (own bin wins outright when present, correct or not) rather than only at the value level.
   */
  @Test
  public void assignBinsToLines_ownBinWrongButSourceBinAlreadyCorrect_neverConsultsSourceBin() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator ownWrongBin = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      // Source's bin IS in the header warehouse, but is a specific bin, not the flagged default.
      Locator sourceCorrectButNonDefaultBin = mockLocator("loc-principal-specific", headerWarehouse);
      Locator headerDefaultBin = mockLocator(LOC_PRINCIPAL_DEFAULT, headerWarehouse);

      ShipmentInOutLine sourceLine = mock(ShipmentInOutLine.class);
      when(sourceLine.getStorageBin()).thenReturn(sourceCorrectButNonDefaultBin);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getStorageBin()).thenReturn(ownWrongBin);
      when(line.getCanceledInoutLine()).thenReturn(sourceLine);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.singletonList(line));

      stubDefaultLocatorLookup(dal, headerDefaultBin);

      ReturnShipmentUtils.assignBinsToLines(doc);

      // Own bin is present (even though wrong) → candidate resolution never reads the source.
      verify(sourceLine, never()).getStorageBin();
      verify(line).setStorageBin(headerDefaultBin);
      verify(line, never()).setStorageBin(sourceCorrectButNonDefaultBin);
    }
  }

  /**
   * QA edge case: reentrancy/idempotency. Calling {@code assignBinsToLines} a second time on a
   * document whose line was ALREADY corrected on the first pass (simulating a retry of the
   * document-completion flow) must be a no-op — no second {@code setStorageBin} / {@code save}
   * call — because the line's own bin now already belongs to the header warehouse.
   */
  @Test
  public void assignBinsToLines_calledTwice_secondPassIsNoOp() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Locator binInSecondary = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      Locator headerDefaultBin = mockLocator(LOC_PRINCIPAL_DEFAULT, headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getStorageBin()).thenReturn(binInSecondary);
      when(line.getCanceledInoutLine()).thenReturn(null);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.singletonList(line));

      stubDefaultLocatorLookup(dal, headerDefaultBin);

      // First pass: line gets corrected.
      ReturnShipmentUtils.assignBinsToLines(doc);
      verify(line).setStorageBin(headerDefaultBin);
      verify(dal, Mockito.times(1)).save(line);

      // Simulate the persisted state: the line now reports the corrected bin.
      when(line.getStorageBin()).thenReturn(headerDefaultBin);

      // Second pass (e.g. a retried "complete document" action) must not touch the line again.
      ReturnShipmentUtils.assignBinsToLines(doc);
      verify(line, Mockito.times(1)).setStorageBin(headerDefaultBin);
      verify(dal, Mockito.times(1)).save(line);
    }
  }

  /**
   * QA edge case: a document with lines imported from MULTIPLE different source documents,
   * each in a DIFFERENT (and different-from-each-other) foreign warehouse, all sharing the same
   * return header. The per-document warehouse-fallback memoization must still resolve to the
   * SAME header-warehouse anchor bin for every line and must still issue the lookup exactly
   * once, regardless of how many distinct source warehouses are involved.
   */
  @Test
  public void assignBinsToLines_linesFromDifferentSourceWarehouses_allAnchorToSameHeaderBinWithOneLookup() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Warehouse headerWarehouse = mockWarehouse(WH_PRINCIPAL);
      Warehouse thirdWarehouse = mockWarehouse("wh-tertiary");
      Locator binInSecondary = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      Locator binInTertiary = mockLocator("loc-tertiary-A", thirdWarehouse);
      Locator headerDefaultBin = mockLocator(LOC_PRINCIPAL_DEFAULT, headerWarehouse);

      // lineA imported from a source document in the SECONDARY warehouse.
      ShipmentInOutLine origLineA = mock(ShipmentInOutLine.class);
      when(origLineA.getStorageBin()).thenReturn(binInSecondary);
      ShipmentInOutLine lineA = mock(ShipmentInOutLine.class);
      when(lineA.getStorageBin()).thenReturn(null);
      when(lineA.getCanceledInoutLine()).thenReturn(origLineA);

      // lineB imported from a DIFFERENT source document, in the TERTIARY warehouse.
      ShipmentInOutLine origLineB = mock(ShipmentInOutLine.class);
      when(origLineB.getStorageBin()).thenReturn(binInTertiary);
      ShipmentInOutLine lineB = mock(ShipmentInOutLine.class);
      when(lineB.getStorageBin()).thenReturn(null);
      when(lineB.getCanceledInoutLine()).thenReturn(origLineB);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(headerWarehouse);
      when(doc.getMaterialMgmtShipmentInOutLineList())
          .thenReturn(Arrays.asList(lineA, lineB));

      stubDefaultLocatorLookup(dal, headerDefaultBin);

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(lineA).setStorageBin(headerDefaultBin);
      verify(lineB).setStorageBin(headerDefaultBin);
      verify(lineA, never()).setStorageBin(binInSecondary);
      verify(lineB, never()).setStorageBin(binInTertiary);
      // Two different foreign warehouses among the source lines, still exactly one lookup.
      verify(dal, Mockito.times(1)).createCriteria(Locator.class);
    }
  }

  /**
   * QA edge case: the return header itself has NO warehouse at all (e.g. a legacy/malformed
   * document). {@code assignBinsToLines}' own guard ({@code headerWarehouse == null}) takes the
   * "keep candidate" branch directly — a line with no own bin falls back to whatever the source
   * document's bin was, completely unanchored, because there is nothing to anchor against. This
   * pins the deliberate pass-through (matches {@code NeoHandlerUtils.anchorLocatorToWarehouse}'s
   * own null-warehouse guard) rather than an accidental null-pointer-safe default.
   */
  @Test
  public void assignBinsToLines_headerHasNoWarehouseAtAll_fallsBackToSourceBinUnanchored() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Locator sourceBin = mockLocator("loc-secondary-A", mockWarehouse(WH_SECONDARY));
      ShipmentInOutLine origLine = mock(ShipmentInOutLine.class);
      when(origLine.getStorageBin()).thenReturn(sourceBin);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getStorageBin()).thenReturn(null);
      when(line.getCanceledInoutLine()).thenReturn(origLine);

      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getWarehouse()).thenReturn(null);
      when(doc.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.singletonList(line));

      ReturnShipmentUtils.assignBinsToLines(doc);

      verify(line).setStorageBin(sourceBin);
      // No warehouse to resolve an anchor bin for → no M_Locator lookup at all.
      verify(dal, never()).createCriteria(Locator.class);
    }
  }
}
