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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * What the two return-document header customizations say about ONE return's actions and response
 * keys (ETP-5714): {@code createReturnInvoice} does not apply once the return already has a
 * factura rectificativa, and the keys the headers add to a GET that no spec field backs.
 *
 * <p>Shared by {@link ReturnToVendorShipmentHeaderHandler} and
 * {@link ReturnMaterialReceiptHeaderHandler}, which call it explicitly. Advisory only: it warns
 * before the call; the refusal itself (409 {@code returnInvoiceAlreadyExists}, unless
 * {@code allowDuplicate:true}) lives in {@link ReturnInvoiceSupport#refuseIfAlreadyInvoiced}.</p>
 */
final class ReturnDocumentAvailability {

  private static final Logger log = LogManager.getLogger(ReturnDocumentAvailability.class);

  /** The action that creates the factura rectificativa of a completed return. */
  static final String ACTION_CREATE_RETURN_INVOICE = "createReturnInvoice";

  /** The keys both headers add to a GET record, whatever the source document's direction. */
  static final Set<String> COMMON_ENRICHED_FIELDS = Set.of("returnInvoices", "hasReturnInvoice",
      "linesCount", "invoiceStatus", "issuerOrg");

  private ReturnDocumentAvailability() {
  }

  /**
   * The header's enriched keys: the common ones plus the two that name the source documents.
   *
   * @param sourceDocuments  key of the array of source documents ({@code sourceReceipts} / {@code sourceShipments})
   * @param sourceDocumentNo key of their joined document numbers
   * @return the union, immutable
   */
  static Set<String> enrichedFields(String sourceDocuments, String sourceDocumentNo) {
    Set<String> keys = new TreeSet<>(COMMON_ENRICHED_FIELDS);
    keys.add(sourceDocuments);
    keys.add(sourceDocumentNo);
    return Collections.unmodifiableSet(keys);
  }

  /**
   * The actions that do not apply to this return: {@code createReturnInvoice} when it already has
   * a non-voided factura rectificativa (the {@code hasReturnInvoice} rule).
   *
   * @param subject the record the agent asked about
   * @return {@code action → reason}; empty for anything that is not a completed return, when it
   *         has no rectificativa yet, or when the lookup fails
   */
  static Map<String, String> unavailable(BaseOBObject subject) {
    if (!(subject instanceof ShipmentInOut)) {
      return Collections.emptyMap();
    }
    ShipmentInOut inout = (ShipmentInOut) subject;
    if (!DocumentAvailability.STATUS_COMPLETED.equals(inout.getDocumentStatus())) {
      return Collections.emptyMap();
    }
    try {
      JSONArray previous = ReturnShipmentUtils.fetchPreviousReturnInvoices(inout.getId());
      if (previous.length() == 0) {
        return Collections.emptyMap();
      }
      StringBuilder docNos = new StringBuilder();
      for (int i = 0; i < previous.length(); i++) {
        docNos.append(i == 0 ? "" : ", ").append(previous.optJSONObject(i).optString("documentNo"));
      }
      return Map.of(ACTION_CREATE_RETURN_INVOICE, "This return already has a factura "
          + "rectificativa (" + docNos + "); calling it again is refused with 409 "
          + ReturnShipmentUtils.ERROR_RETURN_INVOICE_ALREADY_EXISTS
          + " and creates nothing, unless "
          + ReturnShipmentUtils.PARAM_ALLOW_DUPLICATE + ":true is passed.");
    } catch (OBException e) {
      log.debug("Could not read the rectificativas of return {}: {}", inout.getId(), e.getMessage());
      return Collections.emptyMap();
    }
  }
}
