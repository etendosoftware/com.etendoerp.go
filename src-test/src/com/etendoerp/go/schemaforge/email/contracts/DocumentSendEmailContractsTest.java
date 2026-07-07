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
import static org.junit.Assert.assertTrue;

import com.etendoerp.go.schemaforge.email.EmailContract;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

/**
 * Unit tests for purchase and shipment document email contract wiring and
 * purchase family accept logic.
 */
public class DocumentSendEmailContractsTest {

  @Test
  public void providerRegistersPurchaseDocumentContracts() {
    Collection<EmailContract> contracts = new PurchaseDocumentEmailContractProvider().getContracts();

    Set<String> contractNames = contracts.stream()
        .map(EmailContract::getName)
        .collect(Collectors.toSet());

    assertEquals(2, contracts.size());
    assertTrue(contractNames.contains("purchase-order-send"));
    assertTrue(contractNames.contains("return-to-vendor-send"));
    assertTrue(contracts.stream().anyMatch(PurchaseOrderSendEmailContract.class::isInstance));
    assertTrue(contracts.stream().anyMatch(ReturnToVendorSendEmailContract.class::isInstance));
  }

  @Test
  public void providerRegistersShipmentDocumentContracts() {
    Collection<EmailContract> contracts = new ShipmentDocumentEmailContractProvider().getContracts();

    Set<String> contractNames = contracts.stream()
        .map(EmailContract::getName)
        .collect(Collectors.toSet());

    assertEquals(1, contracts.size());
    assertTrue(contractNames.contains("goods-shipment-send"));
    assertTrue(contracts.stream().anyMatch(GoodsShipmentSendEmailContract.class::isInstance));
  }

  @Test
  public void purchaseOrderResolverAcceptsOnlyNonReturnDocuments() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    assertTrue(resolver.acceptsReturnDocument(false));
    assertFalse(resolver.acceptsReturnDocument(true));
  }

  @Test
  public void purchaseReturnResolverAcceptsOnlyReturnDocuments() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_RETURN);

    assertTrue(resolver.acceptsReturnDocument(true));
    assertFalse(resolver.acceptsReturnDocument(false));
  }
}
