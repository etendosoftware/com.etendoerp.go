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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link InOutTargetBuilder} — the reusable "→ draft goods movement" target of
 * the follow-up documents (ETP-5576). Holds the header-inheritance assertion formerly in
 * {@code NeoCommercialDocumentFactoryTest} (for the removed {@code createShipmentFromInvoiceHeader})
 * and the ETP-4863 storage-bin anchoring and MISSING_SETUP cases of the deleted
 * {@code CreateInvoiceShipmentHandlerTest}.
 *
 * @covers com.etendoerp.go.schemaforge.InOutTargetBuilder
 */
class InOutTargetBuilderTest {

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBProvider> providerStatic;
  private MockedStatic<NeoCommercialDocumentFactory> factoryStatic;
  private MockedStatic<NeoHandlerUtils> utilsStatic;
  private OBDal dal;
  private OBProvider provider;
  private InOutTargetBuilder.LineLinker linker;

  private final Client client = mock(Client.class);
  private final Organization org = mock(Organization.class);
  private final BusinessPartner bp = mock(BusinessPartner.class);
  private final Location address = mock(Location.class);
  private final Warehouse warehouse = mock(Warehouse.class);
  private final Currency currency = mock(Currency.class);
  private final Order order = mock(Order.class);
  private final DocumentType docType = mock(DocumentType.class);
  private final Locator locator = mock(Locator.class);

  @BeforeEach
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    providerStatic = mockStatic(OBProvider.class);
    factoryStatic = mockStatic(NeoCommercialDocumentFactory.class);
    utilsStatic = mockStatic(NeoHandlerUtils.class);
    dal = mock(OBDal.class);
    provider = mock(OBProvider.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    providerStatic.when(OBProvider::getInstance).thenReturn(provider);
    linker = mock(InOutTargetBuilder.LineLinker.class);
  }

  @AfterEach
  void tearDown() {
    utilsStatic.close();
    factoryStatic.close();
    providerStatic.close();
    obDalStatic.close();
  }

  // ── preconditions ─────────────────────────────────────────────────────────

  @ParameterizedTest(name = "missing {0}")
  @ValueSource(strings = { "warehouse", "docType", "locator" })
  void missingSetupIsRejectedBeforeAnythingIsPersisted(String missing) {
    factoryStatic.when(() -> NeoCommercialDocumentFactory.findInOutDocType(any(), anyString(),
        anyBoolean())).thenReturn("docType".equals(missing) ? null : docType);
    factoryStatic.when(() -> NeoCommercialDocumentFactory.findDefaultLocator(any()))
        .thenReturn("locator".equals(missing) ? null : locator);
    InOutTargetBuilder.Header header = header("warehouse".equals(missing) ? null : warehouse);
    List<InOutTargetBuilder.Line> lines = Collections.singletonList(line("il-1", true));

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> InOutTargetBuilder.build(InOutTargetBuilder.Direction.SALES, header, lines, linker));

    assertEquals(FollowUpException.Reason.MISSING_SETUP, e.getReason());
    providerStatic.verifyNoInteractions();
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
    verify(linker, never()).link(any(), any());
  }

  @Test
  void nonStockableLinesNeedNoStorageBinAndGetNone() {
    factoryStatic.when(() -> NeoCommercialDocumentFactory.findInOutDocType(any(), anyString(),
        anyBoolean())).thenReturn(docType);
    ShipmentInOut inout = newInOut();
    ShipmentInOutLine created = newLines(1).get(0);

    InOutTargetBuilder.build(InOutTargetBuilder.Direction.SALES, header(warehouse),
        Collections.singletonList(line("il-svc", false)), linker);

    factoryStatic.verify(() -> NeoCommercialDocumentFactory.findDefaultLocator(any()), never());
    utilsStatic.verifyNoInteractions();
    verify(created).setStorageBin(null);
    verify(created).setShipmentReceipt(inout);
  }

  // ── header ────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @CsvSource({ "SALES,MMS,true,C-", "PURCHASE,MMR,false,V+" })
  void headerIsADraftInTheDirectionInheritingTheSourceHeader(String direction, String docBaseType,
      boolean salesTransaction, String movementType) {
    factoryStatic.when(() -> NeoCommercialDocumentFactory.findInOutDocType(client, docBaseType,
        salesTransaction)).thenReturn(docType);
    ShipmentInOut inout = newInOut();
    newLines(1);

    ShipmentInOut result = InOutTargetBuilder.build(
        InOutTargetBuilder.Direction.valueOf(direction), header(warehouse),
        Collections.singletonList(line("il-1", false)), linker);

    assertSame(inout, result);
    verify(inout).setClient(client);
    verify(inout).setOrganization(org);
    verify(inout).setBusinessPartner(bp);
    verify(inout).setPartnerAddress(address);
    verify(inout).setWarehouse(warehouse);
    verify(inout).setSalesOrder(order);
    // ETP-4028: the mandatory EM_Etgo_Currency_ID comes from the source document.
    verify(inout).setEtgoCurrency(currency);
    verify(inout).setDocumentType(docType);
    verify(inout).setSalesTransaction(salesTransaction);
    verify(inout).setMovementType(movementType);
    verify(inout).setDocumentStatus("DR");
    verify(inout).setProcessed(false);
    factoryStatic.verify(() -> NeoCommercialDocumentFactory.ensureInOutDocumentNo(eq(inout), any()));
  }

  // ── lines and linking ─────────────────────────────────────────────────────

  @Test
  void stockableLinesGetTheBinAnchoredToTheMovementWarehouseAndEveryLineIsLinkedAfterFlush() {
    factoryStatic.when(() -> NeoCommercialDocumentFactory.findInOutDocType(any(), anyString(),
        anyBoolean())).thenReturn(docType);
    factoryStatic.when(() -> NeoCommercialDocumentFactory.findDefaultLocator(warehouse))
        .thenReturn(locator);
    Locator anchored = mock(Locator.class);
    utilsStatic.when(() -> NeoHandlerUtils.anchorLocatorToWarehouse(eq(locator), eq(warehouse),
        any())).thenReturn(anchored);
    ShipmentInOut inout = newInOut();
    when(inout.getWarehouse()).thenReturn(warehouse);
    List<ShipmentInOutLine> created = newLines(2);
    InOutTargetBuilder.Line stocked = line("il-1", true);
    InOutTargetBuilder.Line service = line("il-2", false);

    InOutTargetBuilder.build(InOutTargetBuilder.Direction.SALES, header(warehouse),
        Arrays.asList(stocked, service), linker);

    verify(created.get(0)).setStorageBin(anchored);
    verify(created.get(0)).setLineNo(10L);
    verify(created.get(0)).setMovementQuantity(new BigDecimal("3"));
    verify(created.get(1)).setStorageBin(null);
    verify(created.get(1)).setLineNo(20L);
    InOrder sequence = inOrder(dal, linker);
    sequence.verify(dal).save(created.get(1));
    sequence.verify(dal).flush();
    sequence.verify(linker).link(stocked, created.get(0));
    sequence.verify(linker).link(service, created.get(1));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private InOutTargetBuilder.Header header(Warehouse headerWarehouse) {
    return new InOutTargetBuilder.Header(client, org, bp, address, headerWarehouse, currency,
        order);
  }

  private static InOutTargetBuilder.Line line(String sourceLineId, boolean stockable) {
    return new InOutTargetBuilder.Line(sourceLineId, mock(Product.class), null, null,
        new BigDecimal("3"), null, null, stockable);
  }

  private ShipmentInOut newInOut() {
    ShipmentInOut inout = mock(ShipmentInOut.class);
    when(inout.getClient()).thenReturn(client);
    when(inout.getOrganization()).thenReturn(org);
    when(provider.get(ShipmentInOut.class)).thenReturn(inout);
    return inout;
  }

  private List<ShipmentInOutLine> newLines(int count) {
    ShipmentInOutLine first = mock(ShipmentInOutLine.class);
    if (count == 1) {
      when(provider.get(ShipmentInOutLine.class)).thenReturn(first);
      return Collections.singletonList(first);
    }
    ShipmentInOutLine second = mock(ShipmentInOutLine.class);
    when(provider.get(ShipmentInOutLine.class)).thenReturn(first, second);
    return Arrays.asList(first, second);
  }
}
