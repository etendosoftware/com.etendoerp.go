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

import java.util.function.Function;
import java.util.function.Predicate;

import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;

/**
 * What the invoice header customization tells the rectificative-import delegates about its own
 * side of the trade (ETP-5876): which invoices are rectificatives, and whether a line copied from
 * an origin invoice keeps its order line.
 *
 * <p><b>Why it is handed in and not decided here.</b> "Rectificative" is each header's own
 * {@code classifyDocType} rule (the one behind {@code arInvoiceSubtype} / {@code apInvoiceSubtype},
 * which the screen's {@code getArSubtype} / {@code getApSubtype} read): the flag
 * {@code EM_Etsg_Isrectificative} or a legacy document type that differs per side. The delegates are
 * shared and name no entity, so the header that serves the action passes its rule in, through the
 * request context ({@link #ATTRIBUTE}), and the delegates never classify anything themselves.
 *
 * <p>{@code salesModalRules} says which window's modals the side mirrors, because the two sides'
 * modals differ and the server follows each one exactly (ETP-5876): the sales ones send the source
 * line's order line along ({@link #keepsOrderLink}, ETP-5381), compare the currency
 * ({@link #comparesCurrency}) and net every invoice line linked to a return line from its quantity;
 * the purchase ones do none of that and only skip a return line this invoice already holds
 * ({@link #capsReturnsByPresence}).
 */
final class RectificativeImportPolicy {

  /** The {@link NeoContext} attribute the header customization stores its policy under. */
  static final String ATTRIBUTE = "rectificativeImportPolicy";

  /** Nothing is a rectificative, purchase rules: what a caller that sets none gets. */
  static final RectificativeImportPolicy NONE = new RectificativeImportPolicy(invoice -> false,
      false);

  private final Predicate<Invoice> rectificative;
  private final boolean salesModalRules;

  RectificativeImportPolicy(Predicate<Invoice> rectificative, boolean salesModalRules) {
    this.rectificative = rectificative;
    this.salesModalRules = salesModalRules;
  }

  /** Whether {@code invoice} is a rectificative, by the header customization's own rule. */
  boolean isRectificative(Invoice invoice) {
    return invoice != null && rectificative.test(invoice);
  }

  /** Whether a line copied from an origin invoice keeps the order line of its source line. */
  boolean keepsOrderLink() {
    return salesModalRules;
  }

  /** Whether the origin invoice and the return are compared by currency (the sales modals do). */
  boolean comparesCurrency() {
    return salesModalRules;
  }

  /**
   * Whether the cap of a return line is its quantity with only the lines THIS invoice holds
   * skipped (the purchase modal), instead of netting every invoice line linked to it (sales).
   */
  boolean capsReturnsByPresence() {
    return !salesModalRules;
  }

  /**
   * The policy of an invoice header customization: "rectificative" is the screen's
   * {@code RECTIFICATIVA} subtype, this header's own {@code classifyDocType} (the rule behind
   * {@code arInvoiceSubtype} / {@code apInvoiceSubtype}) applied to the invoice's target document
   * type ({@code transactionDocument}).
   *
   * @param classifier      the header's {@code classifyDocType}
   * @param salesModalRules {@code true} when this side mirrors the sales window's modals
   */
  static RectificativeImportPolicy forHeader(Function<DocumentType, String> classifier,
      boolean salesModalRules) {
    return new RectificativeImportPolicy(invoice -> {
      DocumentType type = invoice.getTransactionDocument();
      return type != null
          && AbstractInvoiceHeaderHandler.SUBTYPE_RECTIFICATIVA.equals(classifier.apply(type));
    }, salesModalRules);
  }

  /** The policy the header stored on the request, or {@link #NONE}. */
  static RectificativeImportPolicy from(NeoContext context) {
    Object stored = context == null ? null : context.getAttribute(ATTRIBUTE);
    return stored instanceof RectificativeImportPolicy ? (RectificativeImportPolicy) stored
        : NONE;
  }
}
