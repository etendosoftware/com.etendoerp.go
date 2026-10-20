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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * What the invoice header customizations say about ONE invoice's actions (ETP-5714): the
 * {@code documentAction} value Reactivate is ruled out when {@link InvoiceReactivationGuard} would
 * refuse it (the invoice is fiscally accepted and already rectified by a completed rectificativa).
 *
 * <p>Out of {@link AbstractInvoiceHeaderHandler} for size, called explicitly by it. Advisory only:
 * the guard itself stays the enforcement, and a lookup that fails rules nothing out.</p>
 */
final class InvoiceDocumentAvailability {

  private static final Logger log = LogManager.getLogger(InvoiceDocumentAvailability.class);

  private static final String ACTION_DOCUMENT_ACTION = "documentAction";
  private static final String VALUE_REACTIVATE = "RE";

  private InvoiceDocumentAvailability() {
  }

  /**
   * The {@code documentAction} values that do not apply to this invoice.
   *
   * @param record the record the agent asked about
   * @return {@code action:value → reason}; empty for anything that is not a completed invoice or
   *         when nothing rules Reactivate out
   */
  static Map<String, String> unavailable(BaseOBObject record) {
    if (!(record instanceof Invoice)) {
      return Collections.emptyMap();
    }
    Invoice invoice = (Invoice) record;
    if (!DocumentAvailability.STATUS_COMPLETED.equals(invoice.getDocumentStatus())) {
      return Collections.emptyMap();
    }
    try {
      InvoiceReactivationGuard.Block block = InvoiceReactivationGuard.computeBlock(invoice.getId());
      if (block == null) {
        return Collections.emptyMap();
      }
      List<String> docNos = block.rectificativaDocNos;
      return Map.of(NeoActionAvailability.valueKey(ACTION_DOCUMENT_ACTION, VALUE_REACTIVATE),
          "This invoice cannot be reactivated: it was accepted by " + block.fiscalSystem
              + " and is already rectified by " + docNos.stream().collect(Collectors.joining(", "))
              + ". The other documentAction values still apply.");
    } catch (RuntimeException e) {
      log.debug("Could not evaluate the reactivation of invoice {}: {}", invoice.getId(),
          e.getMessage());
      return Collections.emptyMap();
    }
  }
}
