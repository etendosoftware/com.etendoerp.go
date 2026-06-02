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
import static org.mockito.Mockito.when;

import com.etendoerp.go.schemaforge.email.EmailContract;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.businesspartner.BusinessPartner;

/**
 * Unit tests for sales document email contract wiring and recipient resolution.
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
}
