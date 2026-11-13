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

import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

/**
 * Whether a {@code C_Order} is a sales QUOTATION, read from its target document type's
 * {@code DocSubTypeSO} ({@code 'OB'}) — the same structural signal the related-documents readers
 * use ({@code isQuotation}, ETP-6002). A quotation lives in {@code C_Order} like an order, but no
 * goods movement delivers it: a shipment made from an invoice that came straight from a quotation
 * relates to the invoice only (ETP-6002 CP-14), so it must not carry the quotation's lines.
 * Called explicitly by the customizations and mappers that need it; no generic service reads it.
 */
final class SalesQuotations {

  /** {@code C_DocType.DocSubTypeSO} of a sales quotation. */
  static final String QUOTATION_SUBTYPE = "OB";

  private SalesQuotations() {
  }

  /** {@code true} when {@code order} is a sales quotation; {@code false} for {@code null}. */
  static boolean isQuotation(Order order) {
    DocumentType docType = order == null ? null : order.getTransactionDocument();
    return docType != null && QUOTATION_SUBTYPE.equals(docType.getSOSubType());
  }

  /** {@code true} when {@code line} is a line of a sales quotation; {@code false} for {@code null}. */
  static boolean isQuotationLine(OrderLine line) {
    return line != null && isQuotation(line.getSalesOrder());
  }
}
