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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.completionSuccess;
import static com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.mockCompletedInvoice;
import static com.etendoerp.go.schemaforge.ReturnInvoiceSqlTestSupport.stubReturnInvoiceQueries;

import com.etendoerp.go.schemaforge.InvoiceCompletionTestSupport.CompletionMocks;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.criterion.SimpleExpression;
import org.hibernate.query.Query;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.ReversedInvoice;
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

  /**
   * The batch size the tests that do not care about paging ask for. Deliberately equal to
   * {@link ReturnShipmentUtils#DEFAULT_RECTIFIABLE_PAGE_SIZE} so those tests keep exercising the
   * same window an unparameterised request produces; the paging tests below pass their own sizes.
   */
  private static final int PAGE = ReturnShipmentUtils.DEFAULT_RECTIFIABLE_PAGE_SIZE;

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

  // ── buildReturnInvoiceHeader — declared-defaults background resolution (ETP-4888) ──
  //
  // buildReturnInvoiceHeader builds its Invoice header directly via OBProvider (bypassing the
  // normal NEO CRUD "new record" HTTP path), so it must call
  // NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity itself to resolve any
  // contract.json-declared derivation (e.g. SII/SIF fields) — mirrors the same call already
  // added to NeoCommercialDocumentFactory#createInvoiceFromOrderHeader/
  // #createInvoiceFromReceiptHeader and CreateDraftInvoiceHandler#createInvoiceHeaderFromShipment.
  // NeoBackgroundDefaultsService itself is fully mocked out here (a no-op void call) — its own
  // resolution logic is covered by NeoBackgroundDefaultsServiceTest; this only pins the CALL and
  // its ARGUMENTS.

  @Test
  public void buildReturnInvoiceHeader_sales_appliesDeclaredDefaultsWithSalesInvoiceSpecAndShipmentIdAsParent() {
    ShipmentInOut doc = mock(ShipmentInOut.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    when(doc.getBusinessPartner()).thenReturn(bp);
    when(doc.getId()).thenReturn("shipment-sales-001");

    DocumentType docType = mock(DocumentType.class);
    Invoice sourceInvoice = mock(Invoice.class);
    Invoice createdInvoice = mock(Invoice.class);

    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<NeoBackgroundDefaultsService> defaultsMock =
            Mockito.mockStatic(NeoBackgroundDefaultsService.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Invoice.class)).thenReturn(createdInvoice);

      Invoice result = ReturnShipmentUtils.buildReturnInvoiceHeader(
          doc, docType, sourceInvoice, true);

      assertSame(createdInvoice, result);
      defaultsMock.verify(() -> NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          "sales-invoice", "header", createdInvoice, "shipment-sales-001"));
    }
  }

  @Test
  public void buildReturnInvoiceHeader_purchase_appliesDeclaredDefaultsWithPurchaseInvoiceSpecAndShipmentIdAsParent() {
    ShipmentInOut doc = mock(ShipmentInOut.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    when(doc.getBusinessPartner()).thenReturn(bp);
    when(doc.getId()).thenReturn("shipment-purchase-002");

    DocumentType docType = mock(DocumentType.class);
    Invoice sourceInvoice = mock(Invoice.class);
    Invoice createdInvoice = mock(Invoice.class);

    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<NeoBackgroundDefaultsService> defaultsMock =
            Mockito.mockStatic(NeoBackgroundDefaultsService.class)) {
      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Invoice.class)).thenReturn(createdInvoice);

      Invoice result = ReturnShipmentUtils.buildReturnInvoiceHeader(
          doc, docType, sourceInvoice, false);

      assertSame(createdInvoice, result);
      defaultsMock.verify(() -> NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(
          "purchase-invoice", "header", createdInvoice, "shipment-purchase-002"));
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────────────
  // ETP-5381 — create-and-confirm: the rectified-invoice link, the P5 guard, and the order
  // in which finalizeReturnInvoice performs them.
  // ───────────────────────────────────────────────────────────────────────────────────────

  /**
   * <b>The regression this whole ticket hinges on.</b> {@code C_INVOICE_REVERSE_TRG} rejects any
   * insert into {@code C_Invoice_Reverse} once the invoice is {@code Processed='Y'}, and
   * {@code ETSG_CHECK_RECTIF_INV_DOC} rejects completing a rectificative document type with no
   * rectified invoices attached. Complete before linking and the document can be neither
   * confirmed nor linked, ever — a permanently stuck invoice, only recoverable by hand in the DB.
   *
   * <p>Verified through the two observable side effects, in order: the {@code ReversedInvoice}
   * row reaching the DAL, then {@code ProcessInvoiceUtil.process} running.
   */
  @Test
  public void finalizeReturnInvoice_linksRectifiedInvoicesBeforeCompleting() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("ret-inv-1");
      when(dal.get(eq(Invoice.class), eq("inv-origin-1"))).thenReturn(mock(Invoice.class));
      stubNoExistingReversedInvoiceLink(dal);
      ReversedInvoice link = mock(ReversedInvoice.class);
      when(provider.get(ReversedInvoice.class)).thenReturn(link);

      CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
      when(createDraftInvoiceHandler.getSupport()).thenReturn(mock(InvoiceFromOrderSupport.class));

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        mockCompletedInvoice(dal, "ret-inv-1", "REC-1000001", "CO");

        NeoResponse response = ReturnShipmentUtils.finalizeReturnInvoice(invoice,
            Collections.<ShipmentInOutLine>emptyList(), createDraftInvoiceHandler,
            Collections.singletonList("inv-origin-1"), null);

        InOrder inOrder = Mockito.inOrder(dal, completion.processInvoiceUtil);
        inOrder.verify(dal).save(link);
        inOrder.verify(completion.processInvoiceUtil).process(
            eq("ret-inv-1"), eq("CO"), anyString(), anyString(), anyString(), any(), any());

        JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
        assertEquals("ret-inv-1", data.getString("id"));
        assertEquals("REC-1000001", data.getString("documentNo"));
        assertEquals("CO", data.getString("documentStatus"));
      }
    }
  }

  // ── linkRectifiedInvoices ─────────────────────────────────────────────────

  /**
   * An empty selection is rejected instead of silently producing an unlinked rectificative
   * invoice, which the completion trigger would refuse forever.
   */
  @Test
  public void linkRectifiedInvoices_emptyList_throwsWithoutWriting() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      try {
        ReturnShipmentUtils.linkRectifiedInvoices(mock(Invoice.class), Collections.emptyList());
        fail("An empty selection must be rejected");
      } catch (OBException e) {
        assertEquals(ReturnShipmentUtils.ERR_RECTIFIED_INVOICE_REQUIRED, e.getMessage());
      }
      verify(dal, never()).save(any());
    }
  }

  /** {@code null} is treated exactly like an empty selection. */
  @Test
  public void linkRectifiedInvoices_nullList_throwsWithoutWriting() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      try {
        ReturnShipmentUtils.linkRectifiedInvoices(mock(Invoice.class), null);
        fail("A null selection must be rejected");
      } catch (OBException e) {
        assertEquals(ReturnShipmentUtils.ERR_RECTIFIED_INVOICE_REQUIRED, e.getMessage());
      }
      verify(dal, never()).save(any());
    }
  }

  /**
   * A pair that is already linked is skipped: {@code C_Invoice_Reverse} has a uniqueness
   * constraint on (invoice, rectified invoice), so inserting the duplicate would abort the
   * transaction and roll the whole create-and-confirm step back.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void linkRectifiedInvoices_alreadyLinked_doesNotDuplicateTheRow() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      when(dal.get(eq(Invoice.class), eq("inv-origin-1"))).thenReturn(mock(Invoice.class));
      OBCriteria<ReversedInvoice> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(ReversedInvoice.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list())
          .thenReturn(Collections.singletonList(mock(ReversedInvoice.class)));

      ReturnShipmentUtils.linkRectifiedInvoices(mock(Invoice.class),
          Collections.singletonList("inv-origin-1"));

      verify(provider, never()).get(ReversedInvoice.class);
      verify(dal, never()).save(any(ReversedInvoice.class));
    }
  }

  /** An id that resolves to nothing fails loudly rather than linking to null. */
  @Test
  public void linkRectifiedInvoices_unknownInvoiceId_throws() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Invoice.class), eq("does-not-exist"))).thenReturn(null);

      try {
        ReturnShipmentUtils.linkRectifiedInvoices(mock(Invoice.class),
            Collections.singletonList("does-not-exist"));
        fail("An unresolvable invoice id must be rejected");
      } catch (OBException e) {
        assertEquals("Invoice to rectify not found: does-not-exist", e.getMessage());
      }
      verify(dal, never()).save(any(ReversedInvoice.class));
    }
  }

  // ── hasNonVoidedReturnInvoice (guard P5) ──────────────────────────────────

  @Test
  public void hasNonVoidedReturnInvoice_rowFound_returnsTrue() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, true, Collections.<String>emptyList());

      assertTrue(ReturnShipmentUtils.hasNonVoidedReturnInvoice("ret-1"));
    }
  }

  @Test
  public void hasNonVoidedReturnInvoice_noRow_returnsFalse() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, false, Collections.<String>emptyList());

      assertFalse(ReturnShipmentUtils.hasNonVoidedReturnInvoice("ret-1"));
    }
  }

  /**
   * A DB failure must PROPAGATE, not degrade to "no invoice": answering false on an unreadable
   * database would wave the duplicate through, which is the single thing this guard exists to
   * prevent.
   */
  @Test
  public void hasNonVoidedReturnInvoice_dbFailure_propagatesInsteadOfAnsweringFalse() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      try {
        ReturnShipmentUtils.hasNonVoidedReturnInvoice("ret-1");
        fail("A DB failure must not be reported as 'no invoice'");
      } catch (OBException e) {
        assertEquals("Could not verify existing invoices for this return document",
            e.getMessage());
      }
    }
  }

  // ── resolveRectifiedInvoiceIds ────────────────────────────────────────────

  /** An explicit selection is honoured verbatim; the candidate query is never consulted. */
  @Test
  public void resolveRectifiedInvoiceIds_explicitIds_areUsedAndDbIsNotQueried() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, "inv-auto");

      JSONObject body = new JSONObject().put(ReturnShipmentUtils.PARAM_ORIGIN_INVOICES,
          new JSONArray().put("inv-a").put("inv-b"));

      assertEquals(Arrays.asList("inv-a", "inv-b"),
          ReturnShipmentUtils.resolveRectifiedInvoiceIds(body, "ret-1"));
      verify(dal, never()).getConnection();
    }
  }

  /** Repeated and blank entries are dropped: each pair may only be linked once. */
  @Test
  public void resolveRectifiedInvoiceIds_deduplicatesAndSkipsBlanks() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, "inv-auto");

      JSONObject body = new JSONObject().put(ReturnShipmentUtils.PARAM_ORIGIN_INVOICES,
          new JSONArray().put("inv-a").put("inv-a").put("  ").put("inv-b"));

      assertEquals(Arrays.asList("inv-a", "inv-b"),
          ReturnShipmentUtils.resolveRectifiedInvoiceIds(body, "ret-1"));
    }
  }

  /**
   * With no explicit selection the fallback takes the FIRST AUTO-DETECTED candidate — the same
   * one {@code buildRectifiableInvoicesResponse} reports first in {@code suggestedInvoiceIds}, so
   * what the modal preselects and what the server would pick on its own can never diverge.
   */
  @Test
  public void resolveRectifiedInvoiceIds_noSelection_fallsBackToFirstCandidate() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, "inv-new", "inv-old");

      assertEquals(Collections.singletonList("inv-new"),
          ReturnShipmentUtils.resolveRectifiedInvoiceIds(null, "ret-1"));
    }
  }

  /** An empty {@code originInvoices} array behaves exactly like no key at all. */
  @Test
  public void resolveRectifiedInvoiceIds_emptyArray_fallsBackToFirstCandidate() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, "inv-new");

      JSONObject body = new JSONObject()
          .put(ReturnShipmentUtils.PARAM_ORIGIN_INVOICES, new JSONArray());

      assertEquals(Collections.singletonList("inv-new"),
          ReturnShipmentUtils.resolveRectifiedInvoiceIds(body, "ret-1"));
    }
  }

  /**
   * Nothing to rectify → throw BEFORE anything is written. The alternative (create the invoice
   * and discover at completion time that it has no rectified invoice) leaves a draft that can
   * never be confirmed.
   */
  @Test
  public void resolveRectifiedInvoiceIds_noCandidates_throws() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal);

      try {
        ReturnShipmentUtils.resolveRectifiedInvoiceIds(null, "ret-1");
        fail("A return document with no rectifiable invoice must be rejected");
      } catch (OBException e) {
        assertEquals(ReturnShipmentUtils.ERR_RECTIFIED_INVOICE_REQUIRED, e.getMessage());
      }
      verify(dal, never()).save(any());
    }
  }

  /**
   * The single worst failure this feature can produce: rectifying an invoice that has nothing to
   * do with the return. The selectable list is every confirmed invoice of the flow, so if the
   * fallback ever read from it the server would quietly pick a stranger's invoice whenever the
   * caller sent no selection. With no chain the only correct answer is to refuse.
   */
  @Test
  public void resolveRectifiedInvoiceIds_noChain_refusesInsteadOfPickingFromTheSelectableList()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      // Nothing detected from the chain, but plenty of invoices the user COULD have picked.
      stubReturnInvoiceQueries(dal, false, Collections.<String>emptyList(),
          Arrays.asList("inv-stranger-a", "inv-stranger-b"));

      try {
        ReturnShipmentUtils.resolveRectifiedInvoiceIds(new JSONObject(), "ret-1");
        fail("Without a chain the server must refuse, never pick an arbitrary invoice");
      } catch (OBException e) {
        assertEquals(ReturnShipmentUtils.ERR_RECTIFIED_INVOICE_REQUIRED, e.getMessage());
        assertFalse("The error must not name a selectable invoice — that would mean it was chosen",
            e.getMessage().contains("inv-stranger"));
      }
      verify(dal, never()).save(any());
    }
  }

  /**
   * An explicit multi-invoice selection is honoured in full: every id, in the order the caller
   * sent them, minus repeats. A return covering two invoiced shipments rectifies BOTH, and
   * dropping the tail would silently under-rectify.
   */
  @Test
  public void resolveRectifiedInvoiceIds_multipleExplicitIds_keepsAllInOrderDeduplicated()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, false, Collections.singletonList("inv-auto"),
          Arrays.asList("inv-auto", "inv-a", "inv-b", "inv-c"));

      JSONObject body = new JSONObject().put(ReturnShipmentUtils.PARAM_ORIGIN_INVOICES,
          new JSONArray().put("inv-c").put("inv-a").put("inv-c").put("inv-b").put("inv-a"));

      assertEquals("Order is the caller's, repeats are collapsed to the first occurrence",
          Arrays.asList("inv-c", "inv-a", "inv-b"),
          ReturnShipmentUtils.resolveRectifiedInvoiceIds(body, "ret-1"));
      assertFalse("An explicit selection must never be widened with the auto-detected one",
          ReturnShipmentUtils.resolveRectifiedInvoiceIds(body, "ret-1").contains("inv-auto"));
      verify(dal, never()).getConnection();
    }
  }

  // ── buildRectifiableInvoicesResponse ──────────────────────────────────────

  /**
   * The action payload carries the selectable candidates (each flagged with whether the chain
   * suggested it), every auto-detected suggestion and the already-invoiced flag. The suggestions
   * must be the same ids {@code resolveRectifiedInvoiceIds} falls back to.
   */
  @Test
  public void buildRectifiableInvoicesResponse_reportsCandidatesSuggestionAndFlag()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = stubReturnInvoiceQueries(dal, true, Arrays.asList("inv-new", "inv-old"));
      assertNull(conn.getWarnings());

      NeoResponse response =
          ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", 0, PAGE, null);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray invoices = data.getJSONArray("invoices");
      assertEquals(2, invoices.length());
      assertEquals("inv-new", invoices.getJSONObject(0).getString("id"));
      assertEquals("inv-old", invoices.getJSONObject(1).getString("id"));
      assertTrue("A chain-detected invoice must be flagged so the UI can preselect it",
          invoices.getJSONObject(0).getBoolean("suggested"));
      assertTrue(invoices.getJSONObject(1).getBoolean("suggested"));
      assertEquals(Arrays.asList("inv-new", "inv-old"), idsOf(data, "suggestedInvoiceIds"));
      assertTrue(data.getBoolean("hasReturnInvoice"));
    }
  }

  /** No candidates → an empty list and no suggestions at all, never an error. */
  @Test
  public void buildRectifiableInvoicesResponse_noCandidates_reportsNullSuggestion()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal);

      NeoResponse response =
          ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", 0, PAGE, null);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getJSONArray("invoices").length());
      assertEquals(0, data.getJSONArray("suggestedInvoiceIds").length());
      assertFalse("The removed singular key must not come back",
          data.has("suggestedInvoiceId"));
      assertFalse(data.getBoolean("hasReturnInvoice"));
    }
  }

  /**
   * ETP-5381, production case #1: a return created by hand has NO {@code Canceled_Inoutline_ID}
   * chain, so nothing is auto-detected — yet rectifying is perfectly legitimate. Before the
   * split this emptied the whole list and left the user unable to create the rectificative at
   * all, which is precisely the bug: the chain may limit the SUGGESTION, never the CHOICE.
   */
  @Test
  public void buildRectifiableInvoicesResponse_noChain_stillOffersEverySelectableInvoice()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, false, Collections.<String>emptyList(),
          Arrays.asList("inv-a", "inv-b", "inv-c"));

      NeoResponse response =
          ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", 0, PAGE, null);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray invoices = data.getJSONArray("invoices");
      assertEquals("An empty chain must not empty the selectable list", 3, invoices.length());
      for (int i = 0; i < invoices.length(); i++) {
        assertFalse("Nothing was detected, so nothing may be flagged as suggested",
            invoices.getJSONObject(i).getBoolean("suggested"));
      }
      assertEquals(0, data.getJSONArray("suggestedInvoiceIds").length());
    }
  }

  /**
   * ETP-5381, production case #2: a return covering two shipments billed on two separate invoices
   * must preselect BOTH. Reporting only the newest made the user re-find the second one by hand,
   * and silently under-rectified whoever trusted the preselection.
   */
  @Test
  public void buildRectifiableInvoicesResponse_twoDetectedInvoices_suggestsBoth()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubReturnInvoiceQueries(dal, false, Arrays.asList("inv-new", "inv-old"),
          Arrays.asList("inv-new", "inv-old", "inv-unrelated"));

      NeoResponse response =
          ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", 0, PAGE, null);

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("Both chain-detected invoices must be preselected, not just the newest",
          Arrays.asList("inv-new", "inv-old"), idsOf(data, "suggestedInvoiceIds"));

      JSONArray invoices = data.getJSONArray("invoices");
      assertEquals(3, invoices.length());
      assertTrue(invoices.getJSONObject(0).getBoolean("suggested"));
      assertTrue(invoices.getJSONObject(1).getBoolean("suggested"));
      assertFalse("An invoice outside the chain is selectable but never suggested",
          invoices.getJSONObject(2).getBoolean("suggested"));
    }
  }

  /**
   * The selectable query is scoped to the return document itself: same client and same
   * {@code IsSOTrx} flow, confirmed and active rows only, ordered and bounded. Without the
   * flow filter a sales return would offer purchase invoices — a rectification the trigger
   * would reject only after the user committed to it.
   */
  @Test
  public void fetchSelectableInvoices_isScopedToTheReturnDocumentsClientAndFlow()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      java.sql.ResultSet rs =
          ReturnInvoiceSqlTestSupport.invoiceResultSet(Arrays.asList("inv-a", "inv-b"));
      PreparedStatement ps = mock(PreparedStatement.class);
      when(ps.executeQuery()).thenReturn(rs);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      List<JSONObject> invoices =
          ReturnShipmentUtils.fetchSelectableInvoices("ret-1", 0, PAGE, null);

      assertEquals(2, invoices.size());
      assertEquals("inv-a", invoices.get(0).getString("id"));
      verify(conn).prepareStatement(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("Must stay inside the return document's flow",
          sql.contains("i.IsSOTrx = ret.IsSOTrx"));
      assertTrue("Must stay inside the return document's client",
          sql.contains("i.AD_Client_ID = ret.AD_Client_ID"));
      assertTrue("Only confirmed invoices can be rectified",
          sql.contains("i.DocStatus = 'CO'"));
      assertTrue("Inactive invoices must not be offered", sql.contains("i.IsActive = 'Y'"));
      // Document number first, invoice date second — both DESC, so the newest still floats to the
      // top and the preselection lands on the likely row. DocumentNo is a VARCHAR, so the primary
      // key of the sort is a string sort; within one partner and one numbering series (the case
      // this picker serves) that reads as newest-first. C_Invoice_ID closes the sort (ETP-5381) so
      // that paging over it is deterministic — see the dedicated tie-break test below.
      assertTrue("Ordered by document number first, invoice date second, id last, newest at the top",
          sql.contains("ORDER BY i.DocumentNo DESC, i.DateInvoiced DESC, i.C_Invoice_ID DESC"));
      assertTrue("The list must stay bounded — now by the paging window, not a fixed ceiling",
          sql.contains("LIMIT ? OFFSET ?"));
      assertFalse("The chain must not restrict the selectable list",
          sql.contains("Canceled_Inoutline_ID"));
      // The scope comes from the return document itself, so the only bound STRING is its id; the
      // paging window occupies positions 2 and 3 and is bound as an integer, never as text.
      verify(ps).setString(1, "ret-1");
      verify(ps, never()).setString(eq(2), anyString());
    }
  }

  /**
   * ETP-5381: the selectable list is restricted to the return document's own business partner.
   *
   * <p>Do NOT "simplify" this filter away. An earlier revision dropped it on the stated grounds
   * that {@code C_Invoice_Reverse} enforces same-BP "only where it applies (Verifactu orgs permit
   * cross-BP rectifications)", so filtering "would hide rows the database would have accepted".
   * That rationale is false. The trigger
   * ({@code src-db/database/model/triggers/C_INVOICE_REVERSE_TRG.xml}) is titled "Check the
   * introduced BP is the same as the Invoice" and its check
   * ({@code IF v_bpheader_id <> v_bpreversed_id THEN RAISE_APPLICATION_ERROR('@NotEqualBPartner@')})
   * is unconditional — Openbravo core, no Verifactu branch, no module gate. Offering another
   * partner's invoice therefore offers a row the database ALWAYS rejects, and the user finds out
   * only on save. Filtering removes impossible choices, never legitimate ones.
   */
  @Test
  public void fetchSelectableInvoices_isRestrictedToTheReturnDocumentsBusinessPartner()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      java.sql.ResultSet rs =
          ReturnInvoiceSqlTestSupport.invoiceResultSet(Collections.singletonList("inv-a"));
      PreparedStatement ps = mock(PreparedStatement.class);
      when(ps.executeQuery()).thenReturn(rs);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      ReturnShipmentUtils.fetchSelectableInvoices("ret-1", 0, PAGE, null);

      verify(conn).prepareStatement(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("Only the return document's own partner can be rectified — the trigger's BP "
              + "check is unconditional, so any other partner's invoice is an impossible choice",
          sql.contains("AND i.C_BPartner_ID = ret.C_BPartner_ID"));
      // The partner is read off the return document itself, not handed in by the caller: a bound
      // literal could be passed a partner the return does not belong to, which is precisely the
      // mismatch the trigger rejects.
      assertTrue("The partner must come from the joined return document",
          sql.contains("JOIN M_InOut ret ON ret.M_InOut_ID = ?"));
      assertFalse("The partner must not be a caller-supplied parameter",
          sql.contains("i.C_BPartner_ID = ?"));
      // Still a single bound parameter: the return document id. Filtering by partner adds a
      // correlation to the existing join, not a new input.
      verify(ps).setString(1, "ret-1");
      verify(ps, never()).setString(eq(2), anyString());
    }
  }

  /** The chain query is the one that walks {@code Canceled_Inoutline_ID}, and only confirmed. */
  @Test
  public void fetchAutoDetectedInvoices_walksTheCancelledLineChain() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      java.sql.ResultSet rs =
          ReturnInvoiceSqlTestSupport.invoiceResultSet(Collections.singletonList("inv-new"));
      PreparedStatement ps = mock(PreparedStatement.class);
      when(ps.executeQuery()).thenReturn(rs);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      List<JSONObject> invoices = ReturnShipmentUtils.fetchAutoDetectedInvoices("ret-1");

      assertEquals(Collections.singletonList("inv-new"),
          Collections.singletonList(invoices.get(0).getString("id")));
      verify(conn).prepareStatement(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue(sql.contains("rl.Canceled_Inoutline_ID IS NOT NULL"));
      assertTrue(sql.contains("i.DocStatus = 'CO'"));
      verify(ps).setString(1, "ret-1");
    }
  }

  /** Both candidate queries propagate DB failures with their own message, never an empty list. */
  @Test
  public void invoiceQueries_dbFailure_propagateDistinctMessages() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      try {
        ReturnShipmentUtils.fetchSelectableInvoices("ret-1", 0, PAGE, null);
        fail("An unreadable database must not look like 'no invoice to rectify'");
      } catch (OBException e) {
        assertEquals("Could not load the invoices available to rectify", e.getMessage());
      }
      try {
        ReturnShipmentUtils.fetchAutoDetectedInvoices("ret-1");
        fail("An unreadable database must not look like 'nothing detected'");
      } catch (OBException e) {
        assertEquals("Could not load the invoices detected for this return", e.getMessage());
      }
    }
  }

  // ── ETP-5381: server-side paging and search ───────────────────────────────

  /**
   * <b>The regression test that matters most in this batch.</b> The paging window must be bound as
   * an INTEGER, never as text.
   *
   * <p>This is not a style preference. PostgreSQL types a {@code setString} parameter as
   * {@code varchar} and {@code LIMIT}/{@code OFFSET} demand {@code bigint}, so binding {@code "80"}
   * there fails the entire statement before a single row is read:
   * {@code ERROR: argument of OFFSET must be type bigint, not type character varying}. Reproduced
   * against a live database — {@code setString} fails, {@code setInt} returns rows — on the exact
   * statement this method builds. Because the paging clause is unconditional, a regression here
   * breaks EVERY call to the picker, not an edge case, and surfaces as the generic
   * "Could not load the invoices available to rectify".
   *
   * <p>Asserting on the SQL text cannot catch this: the text is identical either way. A mocked
   * {@code PreparedStatement} also accepts {@code setString} on a {@code LIMIT} placeholder without
   * complaint, so every other test in this file stays green while the feature is entirely broken.
   * Only verifying the SETTER closes that gap — hence the explicit {@code never()} clauses.
   */
  @Test
  public void fetchSelectableInvoices_bindsPagingAsIntegers_becauseAStringLimitIsRejectedByPostgres()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      SelectableQuery q = captureSelectableQuery(dal, Collections.singletonList("inv-a"),
          "ret-1", 40, 25, null);

      assertTrue("The window must be bound, not inlined", q.sql.contains("LIMIT ? OFFSET ?"));
      // Position 1 is the return document id and stays a string; 2 and 3 are the window.
      verify(q.ps).setString(1, "ret-1");
      verify(q.ps).setInt(2, 25);
      verify(q.ps).setInt(3, 40);
      verify(q.ps, never()).setString(eq(2), anyString());
      verify(q.ps, never()).setString(eq(3), anyString());
      // Belt and braces: the stringified values must not reach ANY position, which also catches a
      // future refactor that reorders the parameters instead of changing the setter.
      verify(q.ps, never()).setString(anyInt(), eq("25"));
      verify(q.ps, never()).setString(anyInt(), eq("40"));
    }
  }

  /**
   * The id tie-break is what makes OFFSET/LIMIT paging correct, not merely tidy: over a set ordered
   * only by document number and date, two invoices sharing both can straddle a batch boundary in a
   * different order each time, so a row is silently repeated on one batch and skipped on the next.
   * The user never sees an error — just an invoice that is missing from the picker.
   */
  @Test
  public void fetchSelectableInvoices_ordersByIdLast_soPagingCannotRepeatOrSkipARow()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      SelectableQuery q = captureSelectableQuery(dal, Collections.singletonList("inv-a"),
          "ret-1", 0, PAGE, null);

      assertTrue("C_Invoice_ID must close the sort, or paging is non-deterministic",
          q.sql.contains("ORDER BY i.DocumentNo DESC, i.DateInvoiced DESC, i.C_Invoice_ID DESC"));
      assertTrue("A total order is only useful if it is applied before the window is cut",
          q.sql.indexOf("ORDER BY") < q.sql.indexOf("LIMIT ?"));
    }
  }

  /**
   * Search runs in SQL, matched case-insensitively against document number and partner name — the
   * two fields the picker renders as text.
   *
   * <p>Filtering client-side instead would be a correctness bug, not an optimisation: the client
   * only holds the batches it has already fetched, so it would answer "no matches" for an invoice
   * that exists further down the set and the user would conclude the invoice is not rectifiable.
   */
  @Test
  public void fetchSelectableInvoices_appliesSearchInSql_trimmedAndLowercasedOnBothFields()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      SelectableQuery q = captureSelectableQuery(dal, Collections.singletonList("inv-a"),
          "ret-1", 0, PAGE, "  AcMe  ");

      assertTrue("Both rendered fields must be searched",
          q.sql.contains("AND (LOWER(i.DocumentNo) LIKE ? OR LOWER(COALESCE(bp.Name, '')) LIKE ?)"));
      // COALESCE matters: a NULL partner name would make the whole OR NULL and drop rows whose
      // document number DOES match.
      assertTrue("A null partner name must not swallow a document-number match",
          q.sql.contains("COALESCE(bp.Name, '')"));
      // Typed as "  AcMe  ", bound as "%acme%" — trimmed and lowercased, once per field.
      verify(q.ps).setString(2, "%acme%");
      verify(q.ps).setString(3, "%acme%");
      // The window follows the search parameters, still as integers.
      verify(q.ps).setInt(4, PAGE);
      verify(q.ps).setInt(5, 0);
    }
  }

  /**
   * A blank search is no search: no clause, no bound parameters, and the window stays at positions
   * 2 and 3. Emitting {@code LIKE '%%'} instead would be harmless but emitting a clause bound to a
   * blank string while the caller believed it typed nothing would not be.
   */
  @Test
  public void fetchSelectableInvoices_blankSearch_addsNoClauseAndNoParameters() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      MockedStatic<OBDal> staticMock = dalMock;
      for (String blank : Arrays.asList(null, "", "   ", "\t\n")) {
        OBDal dal = mock(OBDal.class);
        staticMock.when(OBDal::getInstance).thenReturn(dal);

        SelectableQuery q = captureSelectableQuery(dal, Collections.singletonList("inv-a"),
            "ret-1", 0, PAGE, blank);

        assertFalse("A blank search must not produce a LIKE clause: " + describe(blank),
            q.sql.contains("LIKE ?"));
        // With no search the window sits immediately after the return document id.
        verify(q.ps).setInt(2, PAGE);
        verify(q.ps).setInt(3, 0);
        verify(q.ps, never()).setString(eq(2), anyString());
      }
    }
  }

  /**
   * {@code pageSize} is clamped to {@code [1, 500]}. A non-positive value is the dangerous end:
   * {@code LIMIT 0} returns nothing at all, so an off-by-one in a caller would empty the picker
   * with no error to explain it. The ceiling stops a hand-crafted request asking for the table.
   */
  @Test
  public void fetchSelectableInvoices_clampsPageSizeToAUsableWindow() throws Exception {
    // requested → expected LIMIT
    int[][] cases = { { 0, 1 }, { -5, 1 }, { 1, 1 }, { 80, 80 }, { 500, 500 }, { 100_000, 500 } };
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      for (int[] c : cases) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        SelectableQuery q = captureSelectableQuery(dal, Collections.singletonList("inv-a"),
            "ret-1", 0, c[0], null);

        verify(q.ps).setInt(2, c[1]);
      }
    }
  }

  /** A negative offset is floored at 0 rather than rejected: the first batch is the safe answer. */
  @Test
  public void fetchSelectableInvoices_floorsNegativeStartRowAtZero() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      SelectableQuery q = captureSelectableQuery(dal, Collections.singletonList("inv-a"),
          "ret-1", -10, PAGE, null);

      verify(q.ps).setInt(3, 0);
      verify(q.ps, never()).setInt(eq(3), eq(-10));
    }
  }

  /**
   * The chain-detected rows ride the FIRST batch only. They are prepended so a detected invoice
   * outside the window is still reachable; merging them into every batch would repeat them down
   * the list as the user scrolls.
   */
  @Test
  public void buildRectifiableInvoicesResponse_mergesDetectedRowsOnTheFirstBatchOnly()
      throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubAndCaptureSelectableStatement(dal, Collections.singletonList("inv-auto"),
          Arrays.asList("inv-a", "inv-b"));

      JSONObject first = dataOf(
          ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", 0, PAGE, null));

      assertEquals("The detected invoice is prepended so it can be reached from the picker",
          Arrays.asList("inv-auto", "inv-a", "inv-b"), idsOfInvoices(first));
      assertTrue(first.getJSONArray("invoices").getJSONObject(0).getBoolean("suggested"));
    }

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubAndCaptureSelectableStatement(dal, Collections.singletonList("inv-auto"),
          Arrays.asList("inv-a", "inv-b"));

      JSONObject later = dataOf(
          ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", PAGE, PAGE, null));

      assertEquals("A later batch carries its own rows only — re-merging would duplicate the "
              + "detected invoice further down the list",
          Arrays.asList("inv-a", "inv-b"), idsOfInvoices(later));
    }
  }

  /**
   * {@code suggestedInvoiceIds} ships on EVERY batch, including batches that carry no detected row.
   * It is small and identical each time, and making the client remember which batch defined it
   * would make the preselection depend on the order the batches happen to arrive.
   */
  @Test
  public void buildRectifiableInvoicesResponse_reportsSuggestedIdsOnEveryBatch() throws Exception {
    for (int startRow : new int[] { 0, PAGE, 5 * PAGE }) {
      try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        stubAndCaptureSelectableStatement(dal, Arrays.asList("inv-auto-1", "inv-auto-2"),
            Arrays.asList("inv-a", "inv-b"));

        JSONObject data = dataOf(
            ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", startRow, PAGE, null));

        assertEquals("Batch at offset " + startRow + " must still carry the full suggestion",
            Arrays.asList("inv-auto-1", "inv-auto-2"), idsOf(data, "suggestedInvoiceIds"));
      }
    }
  }

  /**
   * {@code hasMore} is the short-batch signal {@code useEntity} already reads: a full batch means
   * "there may be more", a short one is the end. Deriving it this way is what lets the picker page
   * without a second {@code COUNT(*)} on every scroll.
   */
  @Test
  public void buildRectifiableInvoicesResponse_reportsHasMoreFromBatchFullnessAndEchoesStartRow()
      throws Exception {
    // Two rows available; a window of exactly two is full → there may be more.
    assertTrue("A full batch must not be reported as the end of the set",
        pagedData(0, 2).getBoolean("hasMore"));
    // A window of three came back short → the set is exhausted.
    assertFalse("A short batch is the end of the set",
        pagedData(0, 3).getBoolean("hasMore"));
    // The offset is echoed so the client can tell which window it is looking at.
    assertEquals(40, pagedData(40, 2).getInt("startRow"));
    assertEquals("A floored offset must be echoed as the value actually used, not as requested",
        0, pagedData(-7, 2).getInt("startRow"));
  }

  /**
   * The request-facing overload reads the window off the request BODY — {@code startRow} and
   * {@code pageSize} — and passes it straight through to the query.
   */
  @Test
  public void buildRectifiableInvoicesResponse_readsThePagingWindowOffTheRequestBody()
      throws Exception {
    PreparedStatement first = pagingParamsFor(contextWithBody("startRow", 0, "pageSize", 80));
    verify(first).setInt(2, 80);
    verify(first).setInt(3, 0);

    PreparedStatement second = pagingParamsFor(contextWithBody("startRow", 80, "pageSize", 80));
    verify(second).setInt(2, 80);
    verify(second).setInt(3, 80);

    // A third batch of a different size is honoured too — the window is not fixed to the default.
    PreparedStatement third = pagingParamsFor(contextWithBody("startRow", 160, "pageSize", 25));
    verify(third).setInt(2, 25);
    verify(third).setInt(3, 160);
  }

  /**
   * The window must come from the BODY, not from {@code _startRow}/{@code _endRow} query
   * parameters, even though that is how the list windows page through {@code useEntity}.
   *
   * <p>An action endpoint reaches its handler via {@code NeoHookDispatcher#buildHookContext}, which
   * populates {@code requestBody} but NOT {@code queryParams} — only {@code NeoRequestRouter} fills
   * those in. A query-string implementation therefore reads null and silently serves the first
   * batch forever: no error, no log, just a picker that never pages. This test fails if anyone
   * "restores" the query-string convention.
   */
  @Test
  public void buildRectifiableInvoicesResponse_ignoresQueryStringPaging_becauseActionsNeverCarryIt()
      throws Exception {
    NeoContext queryStringOnly = NeoContext.builder()
        .queryParams(mapOf("_startRow", "80", "_endRow", "159", "search", "acme"))
        .build();

    PreparedStatement ps = pagingParamsFor(queryStringOnly);

    verify(ps).setInt(2, PAGE);
    verify(ps).setInt(3, 0);
    // And no search was applied: position 2 is the window, not a LIKE parameter.
    verify(ps, never()).setString(eq(2), anyString());
  }

  /**
   * The window survives being sent as JSON strings. The frontend builds this body from state that
   * may hold either a number or the string it was typed as, and {@code 80} vs {@code "80"} must
   * not be the difference between paging and silently serving the first batch.
   */
  @Test
  public void buildRectifiableInvoicesResponse_acceptsThePagingWindowAsJsonStrings()
      throws Exception {
    PreparedStatement ps = pagingParamsFor(contextWithBody("startRow", "80", "pageSize", "25"));
    verify(ps).setInt(2, 25);
    verify(ps).setInt(3, 80);
  }

  /** An empty body — and a null context — must still return the first default batch. */
  @Test
  public void buildRectifiableInvoicesResponse_withoutPagingParams_asksForTheFirstDefaultBatch()
      throws Exception {
    List<NeoContext> bare = Arrays.asList(null, contextWithBody(), NeoContext.builder().build());
    for (NeoContext context : bare) {
      PreparedStatement ps = pagingParamsFor(context);
      verify(ps).setInt(2, PAGE);
      verify(ps).setInt(3, 0);
    }
  }

  /**
   * A malformed paging value falls back to the default instead of failing the action. The caller is
   * asking for a page of a picker: showing the first batch is a recoverable answer, while a 400
   * would hide the whole list over a bad offset the user never typed.
   */
  @Test
  public void buildRectifiableInvoicesResponse_malformedPagingValues_fallBackInsteadOfFailing()
      throws Exception {
    // Unparseable offset → the first row, with the default window.
    PreparedStatement badStart = pagingParamsFor(contextWithBody("startRow", "abc"));
    verify(badStart).setInt(2, PAGE);
    verify(badStart).setInt(3, 0);

    // Unparseable size → a default-sized batch from the requested offset, not an empty window.
    PreparedStatement badSize =
        pagingParamsFor(contextWithBody("startRow", 40, "pageSize", "xyz"));
    verify(badSize).setInt(2, PAGE);
    verify(badSize).setInt(3, 40);

    // Blank values are absent values, not errors.
    PreparedStatement blank =
        pagingParamsFor(contextWithBody("startRow", "  ", "pageSize", ""));
    verify(blank).setInt(2, PAGE);
    verify(blank).setInt(3, 0);

    // A negative offset is floored before it ever reaches the query.
    PreparedStatement negative = pagingParamsFor(contextWithBody("startRow", -5));
    verify(negative).setInt(3, 0);

    // A non-positive size would make LIMIT return nothing, so it lands on the clamp floor of 1.
    PreparedStatement zeroSize = pagingParamsFor(contextWithBody("pageSize", 0));
    verify(zeroSize).setInt(2, 1);
  }

  /** The search term reaches SQL through the body too, not only through the direct overload. */
  @Test
  public void buildRectifiableInvoicesResponse_passesTheSearchTermFromTheBody() throws Exception {
    PreparedStatement ps = pagingParamsFor(contextWithBody("search", "Acme"));
    verify(ps).setString(2, "%acme%");
    verify(ps).setString(3, "%acme%");
    // The window follows the two search parameters.
    verify(ps).setInt(4, PAGE);
    verify(ps).setInt(5, 0);
  }

  // ── Paging test helpers ───────────────────────────────────────────────────

  /** The statement {@code fetchSelectableInvoices} was run with, plus the SQL text it carried. */
  private static final class SelectableQuery {
    private final PreparedStatement ps;
    private final String sql;

    private SelectableQuery(PreparedStatement ps, String sql) {
      this.ps = ps;
      this.sql = sql;
    }
  }

  /**
   * Runs {@code fetchSelectableInvoices} against a mocked connection and hands back both the SQL it
   * prepared and the statement it bound, so a test can assert on the text AND on the setters.
   */
  private static SelectableQuery captureSelectableQuery(OBDal dal, List<String> rows,
      String inOutId, int startRow, int pageSize, String search) throws Exception {
    java.sql.ResultSet rs = ReturnInvoiceSqlTestSupport.invoiceResultSet(rows);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    ReturnShipmentUtils.fetchSelectableInvoices(inOutId, startRow, pageSize, search);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sqlCaptor.capture());
    return new SelectableQuery(ps, sqlCaptor.getValue());
  }

  /**
   * Like {@link ReturnInvoiceSqlTestSupport#stubReturnInvoiceQueries}, but returns the statement
   * the SELECTABLE query is prepared with: the paging parameters ride on that one, and the shared
   * helper only exposes the connection. Dispatches by SQL marker for the same reason it does.
   */
  private static PreparedStatement stubAndCaptureSelectableStatement(OBDal dal,
      List<String> autoDetectedIds, List<String> selectableIds) throws Exception {
    java.sql.ResultSet autoRs = ReturnInvoiceSqlTestSupport.invoiceResultSet(autoDetectedIds);
    java.sql.ResultSet selectableRs = ReturnInvoiceSqlTestSupport.invoiceResultSet(selectableIds);

    PreparedStatement autoPs = mock(PreparedStatement.class);
    when(autoPs.executeQuery()).thenReturn(autoRs);
    PreparedStatement selectablePs = mock(PreparedStatement.class);
    when(selectablePs.executeQuery()).thenReturn(selectableRs);

    java.sql.ResultSet emptyRs = mock(java.sql.ResultSet.class);
    when(emptyRs.next()).thenReturn(false);
    PreparedStatement otherPs = mock(PreparedStatement.class);
    when(otherPs.executeQuery()).thenReturn(emptyRs);

    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("rl.Canceled_Inoutline_ID IS NOT NULL")) {
        return autoPs;
      }
      return sql.contains("i.IsSOTrx = ret.IsSOTrx") ? selectablePs : otherPs;
    });
    return selectablePs;
  }

  /** Builds the action payload for one window over a fixed two-row selectable set. */
  private static JSONObject pagedData(int startRow, int pageSize) throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubAndCaptureSelectableStatement(dal, Collections.<String>emptyList(),
          Arrays.asList("inv-a", "inv-b"));
      return dataOf(
          ReturnShipmentUtils.buildRectifiableInvoicesResponse("ret-1", startRow, pageSize, null));
    }
  }

  /** Runs the request-facing overload and returns the statement the selectable query bound. */
  private static PreparedStatement pagingParamsFor(NeoContext context) throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      PreparedStatement selectablePs = stubAndCaptureSelectableStatement(dal,
          Collections.<String>emptyList(), Collections.singletonList("inv-a"));
      ReturnShipmentUtils.buildRectifiableInvoicesResponse(context, "ret-1");
      return selectablePs;
    }
  }

  /**
   * A request context whose BODY carries the given entries, as alternating key/value arguments.
   * Values are {@code Object} so a test can send {@code 80} and {@code "80"} and prove both work.
   */
  private static NeoContext contextWithBody(Object... keyValues) throws Exception {
    JSONObject body = new JSONObject();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      body.put((String) keyValues[i], keyValues[i + 1]);
    }
    return NeoContext.builder().requestBody(body).build();
  }

  /** A string map from alternating key/value arguments. */
  private static java.util.Map<String, String> mapOf(String... keyValues) {
    java.util.Map<String, String> params = new java.util.HashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      params.put(keyValues[i], keyValues[i + 1]);
    }
    return params;
  }

  /** Unwraps the {@code response.data} object every action payload is wrapped in. */
  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  /** The ids of the {@code invoices} array, in payload order. */
  private static List<String> idsOfInvoices(JSONObject data) throws Exception {
    JSONArray arr = data.getJSONArray("invoices");
    List<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      ids.add(arr.getJSONObject(i).getString("id"));
    }
    return ids;
  }

  /** Renders a blank search value readably in an assertion message. */
  private static String describe(String blank) {
    return blank == null ? "null" : "\"" + blank.replace("\t", "\\t").replace("\n", "\\n") + "\"";
  }

  /** Reads a string array out of the action payload so order can be asserted directly. */
  private static List<String> idsOf(JSONObject data, String key) throws Exception {
    JSONArray arr = data.getJSONArray(key);
    List<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      ids.add(arr.getString(i));
    }
    return ids;
  }

  /** {@code linkRectifiedInvoices}' "is this pair already linked?" probe answers "no". */
  @SuppressWarnings("unchecked")
  private static void stubNoExistingReversedInvoiceLink(OBDal dal) {
    OBCriteria<ReversedInvoice> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(ReversedInvoice.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.<ReversedInvoice>emptyList());
  }
}
