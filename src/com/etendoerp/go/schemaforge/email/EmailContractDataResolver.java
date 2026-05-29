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

package com.etendoerp.go.schemaforge.email;

import java.util.Optional;

interface EmailContractDataResolver {

  /**
   * Resolves an Etendo Go account contact by trusted account id.
   *
   * @param accountId ETGO_Account id
   * @return account contact when available
   */
  Optional<EmailContactRecord> findAccountContact(String accountId);

  /**
   * Resolves an application user contact by trusted user id.
   *
   * @param userId AD_User id
   * @return user contact when available
   */
  Optional<EmailContactRecord> findUserContact(String userId);

  /**
   * Resolves a sales invoice document and its recipient data.
   *
   * @param invoiceId C_Invoice id
   * @return invoice email data when available
   */
  Optional<EmailDocumentRecord> findSalesInvoice(String invoiceId);

  /**
   * Resolves a sales order document and its recipient data.
   *
   * @param orderId C_Order id
   * @return order email data when available
   */
  Optional<EmailDocumentRecord> findSalesOrder(String orderId);
}
