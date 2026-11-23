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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

/**
 * Unit tests for {@link PurchaseInvoiceSiiDeferredVat}: the {@code siiDeferredVat} action of the
 * purchase-invoice header, which tells the SPA whether to offer the compensatory import-tax line.
 * {@code deferredVat} is true only for an F5/LC purchase invoice sent to SII by an IVA
 * organization and classified as deferred VAT by the SII module.
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceSiiDeferredVat
 */
public class PurchaseInvoiceSiiDeferredVatTest {

  private static final String INVOICE_ID = "pinv-dua";
  private static final String IVA = "IVA";

  private MockedStatic<OBDal> obDal;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<SIIUtils> siiUtils;

  private OBDal dal;
  private Invoice invoice;
  private AEATSIIConfig config;
  private Location partnerAddress;

  @Before
  public void setUp() {
    obDal = mockStatic(OBDal.class);
    obContext = mockStatic(OBContext.class);
    siiUtils = mockStatic(SIIUtils.class);
    obContext.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
    obContext.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

    dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);

    // A deferred-VAT F5 import, sent to SII by an IVA organization: every guard passes.
    invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(invoice.getId()).thenReturn(INVOICE_ID);
    when(invoice.isSalesTransaction()).thenReturn(false);
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn("F5");
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.getAccountingDate()).thenReturn(new Date());
    partnerAddress = mock(Location.class);
    when(invoice.getPartnerAddress()).thenReturn(partnerAddress);
    when(partnerAddress.getLocationAddress())
        .thenReturn(mock(org.openbravo.model.common.geography.Location.class));

    config = mock(AEATSIIConfig.class);
    when(config.isAcogidaAlSII()).thenReturn(true);
    when(config.getFechaAcogidaSII()).thenReturn(new Date());
    when(config.getTaxtype()).thenReturn(IVA);
    siiUtils.when(() -> SIIUtils.getSiiConfigFromOrg(org)).thenReturn(config);
    siiUtils.when(SIIUtils::getIvaTaxType).thenReturn(IVA);
    siiUtils.when(() -> SIIUtils.notSendingByDateCause(config, invoice)).thenReturn(false);
    siiUtils.when(() -> SIIUtils.isDeferredVATInvoice(invoice)).thenReturn(true);
  }

  @After
  public void tearDown() {
    siiUtils.close();
    obContext.close();
    obDal.close();
  }

  private static NeoContext actionCtx(String method, String recordId) {
    return NeoContext.builder()
        .specName("purchase-invoice").entityName("header")
        .httpMethod(method).endpointType(NeoEndpointType.ACTION)
        .fieldName(PurchaseInvoiceSiiDeferredVat.ACTION_NAME).recordId(recordId).build();
  }

  private static boolean deferredVat(NeoResponse response) throws Exception {
    assertEquals(200, response.getHttpStatus());
    return response.getBody().getBoolean(PurchaseInvoiceSiiDeferredVat.KEY_DEFERRED_VAT);
  }

  private boolean answer() throws Exception {
    return deferredVat(PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(actionCtx("GET",
        INVOICE_ID)));
  }

  @Test
  public void anotherActionIsNotServed() {
    NeoContext other = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION).fieldName("siiSend")
        .recordId(INVOICE_ID).build();

    assertNull(PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(other));
  }

  @Test
  public void anUnknownInvoiceIs404() {
    NeoResponse response = PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(
        actionCtx("GET", "missing"));

    assertEquals(404, response.getHttpStatus());
  }

  @Test
  public void aPostIs405() {
    NeoResponse response = PurchaseInvoiceSiiDeferredVat.handleIfDeferredVat(
        actionCtx("POST", INVOICE_ID));

    assertEquals(405, response.getHttpStatus());
  }

  @Test
  public void aDeferredVatF5ImportIsTrue() throws Exception {
    assertTrue(answer());
  }

  @Test
  public void aDeferredVatLcImportIsTrue() throws Exception {
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn("LC");

    assertTrue(answer());
  }

  @Test
  public void aNonImportInvoiceTypeIsFalse() throws Exception {
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn("F1");

    assertFalse(answer());
  }

  @Test
  public void aNonIvaOrganizationIsFalse() throws Exception {
    when(config.getTaxtype()).thenReturn("IGIC");

    assertFalse(answer());
  }

  @Test
  public void anOrganizationNotInSiiIsFalse() throws Exception {
    when(config.isAcogidaAlSII()).thenReturn(false);

    assertFalse(answer());
  }

  @Test
  public void anInvoiceBeforeTheSiiAdoptionDateIsFalse() throws Exception {
    siiUtils.when(() -> SIIUtils.notSendingByDateCause(config, invoice)).thenReturn(true);

    assertFalse(answer());
  }

  @Test
  public void aPartnerAddressWithoutLocationIsFalseWithoutAskingTheSiiModule() throws Exception {
    when(partnerAddress.getLocationAddress()).thenReturn(null);

    assertFalse(answer());
    siiUtils.verify(() -> SIIUtils.isDeferredVATInvoice(any()), org.mockito.Mockito.never());
  }

  @Test
  public void anInvoiceTheSiiModuleDoesNotClassifyAsDeferredIsFalse() throws Exception {
    siiUtils.when(() -> SIIUtils.isDeferredVATInvoice(invoice)).thenReturn(false);

    assertFalse(answer());
  }
}
