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

package com.etendoerp.go.schemaforge.email.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.etendoerp.go.portal.PortalLinkPolicy;
import com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecord;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecordResolver;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.businesspartner.BusinessPartner;

/**
 * Unit tests for sales document email contract wiring and recipient resolution, and for which
 * document contracts may carry a customer portal link ({@code bp-portal-link}).
 *
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesDocumentEmailContractProvider
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesDocumentEmailRecipientResolver
 * @covers com.etendoerp.go.schemaforge.email.contracts.DalOrderEmailDocumentResolver
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesInvoiceSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesOrderSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesQuotationSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.GoodsShipmentSendEmailContract
 */
public class SalesDocumentEmailContractsTest {

  @Test
  public void providerRegistersSalesDocumentContracts() {
    Collection<EmailContract> contracts = new SalesDocumentEmailContractProvider().getContracts();

    Set<String> contractNames = contracts.stream()
        .map(EmailContract::getName)
        .collect(Collectors.toSet());

    assertEquals(3, contracts.size());
    assertTrue(contractNames.contains("sales-invoice-send"));
    assertTrue(contractNames.contains("sales-order-send"));
    assertTrue(contractNames.contains("sales-quotation-send"));
    assertTrue(contracts.stream().anyMatch(SalesInvoiceSendEmailContract.class::isInstance));
    assertTrue(contracts.stream().anyMatch(SalesOrderSendEmailContract.class::isInstance));
    assertTrue(contracts.stream().anyMatch(SalesQuotationSendEmailContract.class::isInstance));
  }

  @Test
  public void orderResolverRejectsQuotationAndProposalDocumentSubtypes() {
    DalOrderEmailDocumentResolver resolver = new DalOrderEmailDocumentResolver(
        DalOrderEmailDocumentResolver.SalesOrderDocumentFamily.SALES_ORDER);

    assertTrue(resolver.acceptsDocumentSubtype(null));
    assertTrue(resolver.acceptsDocumentSubtype("SO"));
    assertTrue(resolver.acceptsDocumentSubtype("WR"));
    assertFalse(resolver.acceptsDocumentSubtype("OB"));
    assertFalse(resolver.acceptsDocumentSubtype("ON"));
  }

  @Test
  public void quotationResolverOnlyAcceptsQuotationAndProposalDocumentSubtypes() {
    DalOrderEmailDocumentResolver resolver = new DalOrderEmailDocumentResolver(
        DalOrderEmailDocumentResolver.SalesOrderDocumentFamily.SALES_QUOTATION);

    assertTrue(resolver.acceptsDocumentSubtype("OB"));
    assertTrue(resolver.acceptsDocumentSubtype("ON"));
    assertFalse(resolver.acceptsDocumentSubtype(null));
    assertFalse(resolver.acceptsDocumentSubtype("SO"));
    assertFalse(resolver.acceptsDocumentSubtype("WR"));
  }

  @Test
  public void recipientResolverUsesBusinessPartnerEmailFirst() {
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    when(businessPartner.getEtgoEmail()).thenReturn(" customer@example.com ");

    assertEquals("customer@example.com",
        SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(businessPartner));
  }

  @Test
  public void recipientResolverFallsBackToFirstActiveUserEmail() {
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    User inactiveUser = mock(User.class);
    User activeUser = mock(User.class);
    when(businessPartner.getEtgoEmail()).thenReturn(" ");
    when(inactiveUser.isActive()).thenReturn(false);
    when(inactiveUser.getEmail()).thenReturn("inactive@example.com");
    when(activeUser.isActive()).thenReturn(true);
    when(activeUser.getEmail()).thenReturn(" contact@example.com ");
    when(businessPartner.getADUserList()).thenReturn(Arrays.asList(inactiveUser, activeUser));

    assertEquals(" contact@example.com ",
        SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(businessPartner));
  }

  @Test
  public void recipientResolverRejectsMissingRecipientSources() {
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    User userWithoutEmail = mock(User.class);
    when(businessPartner.getEtgoEmail()).thenReturn(null);
    when(userWithoutEmail.isActive()).thenReturn(true);
    when(userWithoutEmail.getEmail()).thenReturn(" ");
    when(businessPartner.getADUserList()).thenReturn(Arrays.asList(userWithoutEmail));

    assertNull(SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(null));
    assertNull(SalesDocumentEmailRecipientResolver.resolveBusinessPartnerEmail(businessPartner));
  }

  // ── ETP-6209: the customer portal link, gated by bp-portal-link ─────────────

  private static final String PORTAL_DAL = "com.etendoerp.go.portal.PortalLinkDal";
  private static final EmailDocumentRecordResolver RESOLVER = recordId -> Optional.of(
      new EmailDocumentRecord("Cliente", "customer@example.test", recordId, "DOC-0001", null,
          "https://app.example.test/doc/" + recordId, "tenant-1"));

  @Test
  public void salesContractsSendTheEmailUnchangedAndMintNothingWhileTheFlagIsClosed()
      throws Exception {
    // Each sales contract next to the same contract without a portal declaration: the email
    // as it was before the portal link existed.
    List<DefaultDocumentSendEmailContract[]> pairs = Arrays.asList(
        new DefaultDocumentSendEmailContract[] { new SalesQuotationSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(SalesQuotationSendEmailContract.NAME,
                "Sales Quotation", RESOLVER) { } },
        new DefaultDocumentSendEmailContract[] { new SalesOrderSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(SalesOrderSendEmailContract.NAME, "Sales Order",
                RESOLVER) { } },
        new DefaultDocumentSendEmailContract[] { new GoodsShipmentSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(GoodsShipmentSendEmailContract.NAME,
                "Goods Shipment", RESOLVER) { } },
        new DefaultDocumentSendEmailContract[] { new SalesInvoiceSendEmailContract(RESOLVER),
            new DefaultDocumentSendEmailContract(SalesInvoiceSendEmailContract.NAME,
                DefaultDocumentSendEmailContract.CONTENT_TEMPLATE, "Sales Invoice",
                "invoice_number", true, RESOLVER) { } });

    try (MockedStatic<PortalLinkPolicy> policy = mockStatic(PortalLinkPolicy.class);
        MockedStatic<?> portalDal = mockStatic(Class.forName(PORTAL_DAL))) {
      policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(false);

      for (DefaultDocumentSendEmailContract[] pair : pairs) {
        EmailProviderRequest gated = resolve(pair[0]);
        EmailProviderRequest baseline = resolve(pair[1]);

        assertEquals(pair[0].getName(), baseline.getData().toString(),
            gated.getData().toString());
        assertNull(pair[0].getName(), gated.getSendCorrelationId());
      }
      // The gate was asked once per send — the email is unchanged because it said no.
      policy.verify(PortalLinkPolicy::isLinkEnabled, times(4));
      portalDal.verifyNoInteractions();
    }
  }

  @Test
  public void purchaseAndReturnContractsNeverAskForAPortalLinkEvenWithTheFlagOpen()
      throws Exception {
    List<DefaultDocumentSendEmailContract> contracts = Arrays.asList(
        new PurchaseOrderSendEmailContract(RESOLVER),
        new ReturnToVendorSendEmailContract(RESOLVER),
        new ReturnToVendorShipmentSendEmailContract(RESOLVER),
        new ReturnMaterialReceiptSendEmailContract(RESOLVER));

    try (MockedStatic<PortalLinkPolicy> policy = mockStatic(PortalLinkPolicy.class);
        MockedStatic<?> portalDal = mockStatic(Class.forName(PORTAL_DAL))) {
      policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(true);

      for (DefaultDocumentSendEmailContract contract : contracts) {
        EmailProviderRequest request = resolve(contract);

        assertNull(contract.getName(), request.getSendCorrelationId());
        assertFalse(contract.getName(), request.getData().getString("body").contains("/portal/"));
      }
      policy.verifyNoInteractions();
      portalDal.verifyNoInteractions();
    }
  }

  private static EmailProviderRequest resolve(DefaultDocumentSendEmailContract contract)
      throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_RECORD_ID, "doc-1");
    body.put(EmailContractCommandSupport.FIELD_LANGUAGE, "es_ES");
    EmailContractResolution resolution = contract.resolve(
        new EmailContractCommand(contract.getName(), body),
        EmailRecipientResolution.serverResolved(EmailRecipientSet.of(
            Collections.singletonList("customer@example.test"),
            Collections.singletonList("boss@example.test"))));
    assertTrue(contract.getName() + ": " + resolution.getMessage(), resolution.isReady());
    return resolution.getProviderRequest();
  }
}
