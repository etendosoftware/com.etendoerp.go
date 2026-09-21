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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Shared plumbing for building ONE invoice from MULTIPLE source shipments/receipts
 * ({@link ShipmentInOut}, i.e. {@code M_InOut} — the same entity for goods shipments
 * and goods receipts; direction is {@code issotrx}, not the type). Both {@link
 * CreateDraftInvoiceHandler} (goods-shipment, sales) and {@link CreatePurchaseInvoiceHandler}
 * (goods-receipt, purchase) invoice N selected documents into a single header, and this class
 * is what is now shared between them.
 *
 * <p><b>Why this exists separately from {@link NeoInvoiceSupport}:</b> that class is a
 * single-concern SQL helper (pending-quantity computation). This one is generic
 * multi-document plumbing — id parsing, cross-document validation, and per-line quantity
 * resolution — with no SQL of its own. Mixing the two would muddy both.
 *
 * <p>Every user-facing message is a caller-supplied parameter, not a literal here, so the
 * sales handler's existing wording stays byte-identical after delegating to these methods —
 * see each method's callers.
 */
final class MultiDocumentInvoiceSupport {

  private MultiDocumentInvoiceSupport() {
  }

  /**
   * Extracts a list of document IDs from the request body's {@code arrayKey} JSON array,
   * falling back to a single {@code fallbackRecordId} when the array is absent, empty, or
   * malformed. This is what lets a single-record caller (which sends no array at all) and a
   * bulk caller (which sends the array) share one endpoint: an empty result here always means
   * "invoice exactly the record the URL already names".
   *
   * @param body
   *     optional request body (may be {@code null})
   * @param arrayKey
   *     the JSON key holding the array of ids (e.g. {@code shipmentIds}, {@code receiptIds})
   * @param fallbackRecordId
   *     id used when {@code arrayKey} is absent, empty, or unparseable
   * @param log
   *     the caller's logger, so a parse failure is attributed to the right class
   * @return non-empty list of document IDs to invoice
   */
  static List<String> parseDocumentIds(JSONObject body, String arrayKey, String fallbackRecordId, Logger log) {
    List<String> ids = new ArrayList<>();
    if (body != null && body.has(arrayKey)) {
      try {
        JSONArray arr = body.getJSONArray(arrayKey);
        for (int i = 0; i < arr.length(); i++) {
          ids.add(arr.getString(i));
        }
      } catch (Exception e) {
        log.warn("Failed to parse {}: {}", arrayKey, e.getMessage());
      }
    }
    if (ids.isEmpty()) {
      ids.add(fallbackRecordId);
    }
    return ids;
  }

  /**
   * Loads the {@link ShipmentInOut} records for the given IDs and validates that all belong to
   * the same Business Partner — the one cross-document invariant a combined invoice cannot
   * relax, since {@code C_Invoice.C_BPartner_ID} is a single column.
   *
   * <p>Hardened against a null Business Partner on either side of the comparison (an id-only
   * equality check via {@link Objects#equals}), unlike a direct {@code .getId()} call, which
   * would NPE on a document with no Business Partner set.
   *
   * @param ids
   *     list of {@code M_InOut_ID} values to load
   * @param notFoundPrefix
   *     message prefix when an id does not resolve to a record (the id itself is appended)
   * @param emptyMessage
   *     message when {@code ids} is empty
   * @param mismatchMessage
   *     message when the documents do not all share one Business Partner
   * @return validated list of documents in the same order as the input IDs
   * @throws OBException
   *     if any ID is not found, the list is empty, or the documents span multiple
   *     Business Partners
   */
  static List<ShipmentInOut> loadAndValidateSameBusinessPartner(List<String> ids, String notFoundPrefix,
      String emptyMessage, String mismatchMessage) {
    List<ShipmentInOut> docs = new ArrayList<>();
    for (String id : ids) {
      ShipmentInOut s = OBDal.getInstance().get(ShipmentInOut.class, id);
      if (s == null) {
        throw new OBException(notFoundPrefix + id);
      }
      docs.add(s);
    }
    if (docs.isEmpty()) {
      throw new OBException(emptyMessage);
    }
    String firstBpId = businessPartnerId(docs.get(0));
    for (ShipmentInOut s : docs) {
      if (!Objects.equals(businessPartnerId(s), firstBpId)) {
        throw new OBException(mismatchMessage);
      }
    }
    return docs;
  }

  private static String businessPartnerId(ShipmentInOut doc) {
    BusinessPartner bp = doc.getBusinessPartner();
    return bp != null ? bp.getId() : null;
  }

  /**
   * Determines the quantity to invoice for a single shipment/receipt line, capped at the
   * uninvoiced (pending) quantity so that already-invoiced lines are skipped. Returns {@code
   * null} when the line is inactive, excluded by overrides, has zero/null movement quantity, or
   * is already fully invoiced. When overrides are present the result is capped at
   * min(|override|, |pendingQty|), with the line's own sign reapplied to the result.
   *
   * <p><b>ETP-4567:</b> movement quantity can be NEGATIVE (a return-style line). {@code
   * pendingQtyMap} is itself magnitude-only — its underlying query {@code ABS()}s both movement
   * and invoiced quantities at the SQL level, so that a negative return-style line and a
   * positive shipment/receipt line are tracked on the same sign-agnostic "remaining magnitude"
   * scale. This method therefore does all its pending-quantity arithmetic in magnitude
   * (absolute-value) space and reapplies the line's own sign only at the very end — comparing
   * or clamping a negative {@code movementQty} directly against that magnitude-only map (or a
   * {@code max(ZERO)} clamp) would silently destroy the sign or drop the line outright.
   *
   * @param line
   *     the shipment/receipt line to evaluate
   * @param hasOverrides
   *     whether {@code lineOverrides} is non-empty
   * @param lineOverrides
   *     caller-supplied quantity caps per line
   * @param pendingQtyMap
   *     pre-computed uninvoiced qty MAGNITUDE per {@code M_InOutLine_ID}; a line absent from
   *     the map is treated as fully pending (falls back to its own movement quantity)
   * @return quantity to invoice (sign matches the line), or {@code null} to skip this line
   */
  static BigDecimal resolveInOutLineQty(ShipmentInOutLine line, boolean hasOverrides,
      Map<String, BigDecimal> lineOverrides, Map<String, BigDecimal> pendingQtyMap) {
    if (!line.isActive() || (hasOverrides && !lineOverrides.containsKey(line.getId()))) {
      return null;
    }
    BigDecimal movementQty = line.getMovementQuantity();
    if (movementQty == null || movementQty.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    int sign = movementQty.signum();
    BigDecimal movementQtyAbs = movementQty.abs();
    BigDecimal pendingQtyAbs = pendingQtyMap.getOrDefault(line.getId(), movementQtyAbs).min(movementQtyAbs);
    if (pendingQtyAbs.compareTo(BigDecimal.ZERO) <= 0) {
      return null; // already fully invoiced
    }
    BigDecimal qtyAbs = hasOverrides
        ? lineOverrides.get(line.getId()).abs().min(pendingQtyAbs)
        : pendingQtyAbs;
    if (qtyAbs.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return sign < 0 ? qtyAbs.negate() : qtyAbs;
  }

  /**
   * Builds the {@code [{id, orderedQuantity}, ...]} selection for every invoiceable line across
   * {@code docs}, via {@link #resolveInOutLineQty}. Used by a NEW multi-document path that has
   * no pre-existing overridable per-line seam to preserve (unlike {@link
   * CreateDraftInvoiceHandler#addShipmentLinesToInvoice}, which keeps its own loop so its
   * subclass test doubles keep intercepting {@code resolveShipmentLineQty} virtually).
   *
   * @param docs
   *     the source shipments/receipts, already validated
   * @param lineOverrides
   *     map of {@code M_InOutLine_ID → quantity} caps; empty means use full pending quantity
   * @param pendingQtyMap
   *     pre-computed uninvoiced qty MAGNITUDE per {@code M_InOutLine_ID}, merged across all
   *     of {@code docs} (line ids are globally unique, so a flat merge is safe)
   * @param log
   *     the caller's logger, so a malformed entry is attributed to the right class
   * @return JSON array of {@code {id, orderedQuantity}} entries; empty when nothing is pending
   */
  static JSONArray buildInOutLineSelection(List<ShipmentInOut> docs, Map<String, BigDecimal> lineOverrides,
      Map<String, BigDecimal> pendingQtyMap, Logger log) {
    boolean hasOverrides = !lineOverrides.isEmpty();
    JSONArray selectedLines = new JSONArray();
    for (ShipmentInOut doc : docs) {
      for (ShipmentInOutLine line : doc.getMaterialMgmtShipmentInOutLineList()) {
        BigDecimal qty = resolveInOutLineQty(line, hasOverrides, lineOverrides, pendingQtyMap);
        if (qty == null) {
          continue;
        }
        try {
          JSONObject entry = new JSONObject();
          entry.put("id", line.getId());
          entry.put("orderedQuantity", qty.toPlainString());
          selectedLines.put(entry);
        } catch (Exception e) {
          log.warn("Failed to build invoice line entry for line {}: {}", line.getId(), e.getMessage());
        }
      }
    }
    return selectedLines;
  }
}
