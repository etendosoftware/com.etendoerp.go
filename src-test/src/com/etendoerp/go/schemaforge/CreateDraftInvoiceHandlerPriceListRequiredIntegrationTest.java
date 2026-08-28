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
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.Date;

import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.test.base.OBBaseTest;
import org.openbravo.test.base.TestConstants;

/**
 * Real-DB regression test for ETP-4942: completing a Goods Shipment with no linked Sales Order,
 * where the Business Partner also has no default Price List, must fail fast with a clean
 * validation error instead of reaching the native {@code UpdatePricesAndAmounts} pricing hook
 * with a {@code null Invoice.getPriceList()} (the original bug — an unguarded NPE surfaced to the
 * caller as an HTTP 500).
 *
 * <p>Unlike {@link CreateDraftInvoiceHandlerNegativeQuantityIntegrationTest} and the fully-mocked
 * {@code CreateDraftInvoiceHandlerTest#testCreateFromShipmentsNoPriceListResolvedThrows} (which
 * exercises {@link CreateDraftInvoiceHandler#ensurePriceListResolved} via a hand-built {@code
 * Invoice} mock whose {@code getPriceList()} is stubbed directly), this test proves the guard
 * actually fires when the REAL header-derivation path — {@link
 * CreateDraftInvoiceHandler#createInvoiceHeaderFromShipment} reading a REAL {@link
 * BusinessPartner#getPriceList()} through a real Hibernate session — leaves the invoice's price
 * list null on its own. Nothing here is mocked: the shipment, its Business Partner, and the
 * session are all real, and the public entry point ({@link
 * CreateDraftInvoiceHandler#createFromShipments}) is invoked by shipment ID exactly as the HTTP
 * handler does, exercising {@link CreateDraftInvoiceHandler#loadAndValidateShipments} too.
 *
 * <p>The guard ({@code ensurePriceListResolved}) runs BEFORE {@code OBDal.getInstance().save
 * (invoice)}/{@code flush()} and before {@link CreateDraftInvoiceHandler#addShipmentLinesToInvoice}
 * (the only step in this flow that needs a live Weld/CDI container, via {@code
 * CreateInvoiceLinesFromProcess}) — so this test needs no CDI container and the shipment needs no
 * lines at all: the exception is thrown while the invoice header is still a transient, unsaved
 * object.
 *
 * <p>The Business Partner used ({@code A6750F0D15334FB890C254369AC750A8}, same real F&B demo data
 * as the sibling negative-quantity test) does have a default Price List in the seed data — its
 * Payment Terms and Payment Method are real and untouched, but the Price List reference is
 * nulled out in-memory on the Hibernate-managed instance for the duration of this test only. That
 * mutation is never flushed on this path (the guard throws first) and is discarded by {@code
 * rollbackAndClose()} in {@code tearDown} either way, so the shared demo data is never actually
 * altered.
 */
public class CreateDraftInvoiceHandlerPriceListRequiredIntegrationTest extends OBBaseTest {

  // Real F&B Group demo data, same client/org/BP as CreateDraftInvoiceHandlerNegativeQuantityIntegrationTest.
  private static final String BPARTNER_ID = "A6750F0D15334FB890C254369AC750A8"; // BP: Alimentos y Supermercados, S.A
  private static final String WAREHOUSE_ID = "B2D40D8A5D644DD89E329DC297309055"; // Warehouse: España Región Norte

  private static final String EXPECTED_MESSAGE =
      "No Price List could be resolved for this invoice: select a tariff or configure "
          + "a default Price List for the Business Partner";

  @Before
  public void setUp() {
    // This class is listed in `isolatedDalTests` (modules/com.etendoerp.go/build.gradle), so it
    // runs in its own JVM with a pristine OBContext admin-mode stack. No defensive stack reset
    // belongs here: see docs/test-jvm-isolation.md.
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void createFromShipmentsThrowsAndPersistsNothingWhenNoPriceListResolves() {
    BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, BPARTNER_ID);

    // Sanity check: this BP must carry real Payment Terms/Method in the seed data, so the
    // "Business Partner is missing mandatory Payment Terms or Payment Method" guard (a
    // different, earlier check in createInvoiceHeaderFromShipment) does NOT fire instead of
    // the one under test here.
    assertNotNull("sanity check: BP must have Payment Terms in seed data", bp.getPaymentTerms());
    assertNotNull("sanity check: BP must have a Payment Method in seed data", bp.getPaymentMethod());
    assertNotNull("sanity check: BP must have a default Price List in seed data (about to be "
        + "nulled out for this test)", bp.getPriceList());

    // Keep the currency the real Price List carried, purely so the shipment (which must carry
    // a currency at the DB level) gets a valid one — then null the Price List itself, simulating
    // "Business Partner has no default tariff" without touching Payment Terms/Method.
    Currency currency = bp.getPriceList().getCurrency();
    bp.setPriceList(null);

    ShipmentInOut shipment = createUnsavedShipmentWithNoLinkedOrder(bp, currency);
    OBDal.getInstance().save(shipment);
    OBDal.getInstance().flush();

    long invoiceCountBefore = countInvoicesFor(bp);

    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();
    try {
      // The exact public entry point the HTTP handler calls, by shipment ID — exercises
      // loadAndValidateShipments too, not just the header-derivation internals.
      handler.createFromShipments(Collections.singletonList(shipment.getId()), Collections.emptyMap(), null);
      fail("Expected an OBException when no Price List could be resolved for the invoice");
    } catch (OBException e) {
      assertEquals("ensurePriceListResolved must surface the exact ETP-4942 validation message",
          EXPECTED_MESSAGE, e.getMessage());
    }

    // The guard fails BEFORE OBDal.save(invoice)/flush(), so no half-built draft invoice should
    // ever have reached the database for this Business Partner.
    long invoiceCountAfter = countInvoicesFor(bp);
    assertEquals("no invoice must be persisted when the price-list guard rejects the request",
        invoiceCountBefore, invoiceCountAfter);
  }

  /**
   * Builds and persists (but never completes/invoices) a real, header-only Goods Shipment with
   * no linked Sales Order — {@code C_Order_ID} is left null, which is the branch of {@code
   * createFromShipments} that reaches {@code ensurePriceListResolved} (a single shipment WITH a
   * linked order instead delegates to {@code createFromOrder} before that guard ever runs). No
   * shipment lines are added: the guard throws before {@code addShipmentLinesToInvoice} — the
   * only step in this flow needing a live Weld/CDI container — is ever reached.
   */
  private ShipmentInOut createUnsavedShipmentWithNoLinkedOrder(BusinessPartner bp, Currency currency) {
    Client client = OBContext.getOBContext().getCurrentClient();
    Organization org = OBContext.getOBContext().getCurrentOrganization();
    Warehouse warehouse = OBDal.getInstance().get(Warehouse.class, WAREHOUSE_ID);
    Location partnerAddress = bp.getBusinessPartnerLocationList().get(0);
    DocumentType docType = NeoCommercialDocumentFactory.findShipmentDocType(client);
    assertNotNull("sanity check: an active MMS (Goods Shipment) DocumentType must exist for the "
        + "F&B Group client", docType);

    ShipmentInOut shipment = OBProvider.getInstance().get(ShipmentInOut.class);
    shipment.setClient(client);
    shipment.setOrganization(org);
    shipment.setBusinessPartner(bp);
    shipment.setPartnerAddress(partnerAddress);
    shipment.setWarehouse(warehouse);
    Date now = new Date();
    shipment.setMovementDate(now);
    shipment.setAccountingDate(now);
    shipment.setDocumentType(docType);
    shipment.setDocumentNo("GS-ETP4942-" + System.currentTimeMillis());
    shipment.setSalesTransaction(true);
    // No setSalesOrder() call — deliberately no linked Sales Order.
    shipment.setProcessed(false);
    shipment.setDocumentStatus("DR");
    shipment.setMovementType("C+");
    shipment.setEtgoCurrency(currency);
    return shipment;
  }

  /** Counts real, persisted {@link Invoice} rows for the given Business Partner. */
  private long countInvoicesFor(BusinessPartner bp) {
    Number count = (Number) OBDal.getInstance().createCriteria(Invoice.class)
        .add(Restrictions.eq(Invoice.PROPERTY_BUSINESSPARTNER, bp))
        .setProjection(Projections.rowCount())
        .uniqueResult();
    return count.longValue();
  }
}
