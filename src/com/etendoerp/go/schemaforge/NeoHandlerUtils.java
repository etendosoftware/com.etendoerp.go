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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.CSResponse;
import org.openbravo.erpCommon.utility.DocumentNoData;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Shared helpers for {@link NeoHandler} implementations.
 */
final class NeoHandlerUtils {

  private static final String FIELD_STORAGE_BIN = "storageBin";
  private static final String PARAM_PARENT_ID = "parentId";
  // Matches an unresolved raw AD default literal such as "@OnHandLocatorDefault@". See
  // injectDefaultLocatorIfMissing's javadoc for why this must be treated as absent.
  private static final Pattern UNRESOLVED_TOKEN = Pattern.compile("^@[^@]+@$");

  private NeoHandlerUtils() {}

  /**
   * Extracts {@code response.data} from a GET response context.
   *
   * Returns null when: the request is not GET, the previous result or its body is absent,
   * the response wrapper is missing, or the data array is null/empty.
   * When non-null is returned, {@code context.getPreviousResult().getBody()} is guaranteed non-null.
   */
  static JSONArray extractGetDataArray(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    JSONObject responseWrapper = prev.getBody().optJSONObject("response");
    if (responseWrapper == null) {
      return null;
    }
    JSONArray dataArr = responseWrapper.optJSONArray("data");
    return (dataArr == null || dataArr.length() == 0) ? null : dataArr;
  }

  /** Unpacked callout-response fields, as extracted by {@link #extractCalloutFields}. */
  record CalloutFields(JSONObject body, JSONObject updates, JSONObject requestBody,
      String triggerField, JSONObject formState) {}

  /**
   * Unpacks the fields both order and invoice {@code afterCallout} hooks need: the previous
   * callout response body, its {@code updates} map, the request body, the field that triggered
   * the callout, and the {@code formState}. Shared because both hierarchies (orders, invoices)
   * apply the same currency-callout policy (block callout-driven currency changes, warn on
   * missing exchange rate) on top of this same shape.
   *
   * @return {@code null} when there's no previous result/body to work with
   */
  static CalloutFields extractCalloutFields(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject body = previous.getBody();
    JSONObject updates = body.optJSONObject("updates");
    JSONObject requestBody = context.getRequestBody();
    String triggerField = requestBody != null ? requestBody.optString("field", "") : "";
    JSONObject formState = requestBody != null ? requestBody.optJSONObject("formState") : null;
    return new CalloutFields(body, updates, requestBody, triggerField, formState);
  }

  /**
   * Fetches the next document number for an M_InOut (or similar) record.
   *
   * <p>Tries the doctype-based sequence first, then falls back to the table-level sequence.
   * Returns {@code null} when no sequence resolves.
   *
   * @param docTypeId   document type ID, or empty/null to skip doctype lookup
   * @param clientId    client ID for the sequence namespace
   * @param tableDbName DB table name used as key {@code "DocumentNo_<tableDbName>"}
   * @param log         caller's logger for debug messages
   */
  static String fetchDocumentNo(String docTypeId, String clientId, String tableDbName, Logger log) {
    DalConnectionProvider conn = new DalConnectionProvider(false);
    if (docTypeId != null && !docTypeId.isEmpty()) {
      try {
        CSResponse cs = DocumentNoData.nextDocType(conn, docTypeId, clientId, "Y");
        if (cs != null && "1".equals(cs.exito) && StringUtils.isNotBlank(cs.razon)) {
          return cs.razon;
        }
      } catch (Exception ex) {
        log.debug("nextDocType failed for doctype {}: {}", docTypeId, ex.getMessage());
      }
    }
    try {
      CSResponse cs = DocumentNoData.nextDoc(conn, "DocumentNo_" + tableDbName, clientId, "Y");
      if (cs != null && StringUtils.isNotBlank(cs.razon)) {
        return cs.razon;
      }
    } catch (Exception ex) {
      log.debug("nextDoc fallback failed for table {}: {}", tableDbName, ex.getMessage());
    }
    return null;
  }

  /**
   * Rounds a double to 2 decimal places, half-up. Shared by the order/invoice pending
   * total-discount GET-response adjustment ({@code applyTotalDiscountToRecord} in both
   * {@code AbstractOrderHeaderHandler} and {@code AbstractInvoiceHeaderHandler}).
   */
  static double roundHalfUp(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  /**
   * Collects non-blank {@code id} values from a JSON array of records.
   */
  static List<String> collectIds(JSONArray dataArr) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * Extracts the {@code id} of a just-created record from a POST/create response, read via
   * {@code context.getPreviousResult()}.
   *
   * <p>For {@code add()} (POST/create) requests, Etendo's {@code DefaultJsonDataService} always
   * serializes {@code response.data} as a {@code JSONArray} containing exactly one element — the
   * created record — never a plain {@code JSONObject}. Calling {@code optJSONObject("data")} on
   * that response returns {@code null} silently (org.codehaus.jettison.json.JSONObject#optJSONObject
   * returns {@code null} whenever the value at the key is not itself a {@code JSONObject}, with no
   * exception raised), so callers that need the created record's ID after a POST must read
   * {@code data} as an array and pull the first element, which is what this method does.
   *
   * @param context the current NeoContext; only {@code getPreviousResult()} is used
   * @return the created record's {@code id}, or {@code null} if it could not be resolved
   */
  static String extractCreatedIdFromPreviousResult(NeoContext context) {
    try {
      NeoResponse prev = context.getPreviousResult();
      if (prev == null || prev.getBody() == null) {
        return null;
      }
      JSONObject response = prev.getBody().optJSONObject("response");
      if (response == null) {
        return null;
      }
      JSONArray data = response.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return null;
      }
      return data.getJSONObject(0).optString("id", null);
    } catch (Exception e) {
      log.debug("Could not extract created record ID from POST response: {}", e.getMessage());
      return null;
    }
  }

  private static final Logger log = LogManager.getLogger(NeoHandlerUtils.class);

  /**
   * Injects the correct return document type into the request body of a new-record POST,
   * so the callout cascade cannot overwrite it with an unrelated default.
   *
   * @param context          the NeoContext for the create request
   * @param docBaseType      e.g. "MMR" for vendor returns, "MMS" for customer returns
   * @param isSalesTransaction false for purchases, true for sales
   */
  static void injectReturnDocType(NeoContext context, String docBaseType,
      boolean isSalesTransaction) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null) return;
      OBContext.setAdminMode(true);
      try {
        String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
        DocumentType docType = findReturnDocType(orgId, docBaseType, isSalesTransaction);
        if (docType != null) {
          body.put("documentType", docType.getId());
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.debug("Could not inject return document type ({}): {}", docBaseType, e.getMessage());
    }
  }

  private static DocumentType findReturnDocType(String orgId, String docBaseType,
      boolean isSalesTransaction) {
    List<DocumentType> candidates = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT,
            OBContext.getOBContext().getCurrentClient()))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, docBaseType))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, isSalesTransaction))
        .add(Restrictions.eq(DocumentType.PROPERTY_RETURN, true))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .list();
    for (DocumentType dt : candidates) {
      if (orgId.equals(dt.getOrganization().getId())) return dt;
    }
    for (DocumentType dt : candidates) {
      if ("0".equals(dt.getOrganization().getId())) return dt;
    }
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  /**
   * Mirrors {@code sourceField}'s value from {@code body} into {@code targetField},
   * unconditionally overwriting any existing value already there.
   *
   * <p>ETP-4531 (unified date): several postable documents expose a single visible date field
   * to the user while the underlying data model still carries two columns (e.g. document date
   * + accounting date). Whatever value is saved for the visible field must also become the
   * hidden field's value — this is the single source of truth going forward, not a
   * fill-if-absent default like {@link #injectReturnDocType}. Table/window-agnostic: no field
   * names are hardcoded here, callers supply them.
   *
   * @param body        the request body to mutate in place; may be {@code null}
   * @param sourceField the field whose value is copied; no-op if absent from {@code body}
   * @param targetField the field overwritten with {@code sourceField}'s value
   */
  static void mirrorFieldValue(JSONObject body, String sourceField, String targetField) {
    if (body == null || !body.has(sourceField)) {
      return;
    }
    try {
      body.put(targetField, body.opt(sourceField));
    } catch (Exception e) {
      log.debug("Could not mirror '{}' into '{}': {}", sourceField, targetField, e.getMessage());
    }
  }

  /**
   * True when {@code method} is one of the CRUD methods that create or persist a change to a
   * record ({@code POST}, {@code PUT}, {@code PATCH}) — as opposed to {@code GET}/{@code DELETE},
   * which read or remove but never write field values.
   *
   * <p>ETP-4531 root cause: every {@code mirrorAccountingDate()} call site originally hardcoded
   * {@code "POST".equals(method) || "PUT".equals(method)} inline, once per header handler
   * (invoices, orders, receipts, shipments). The live React app's {@code useEntity.js} save flow
   * always sends {@code PATCH} for edits to an EXISTING record (see {@code getMethod(isNew)}) —
   * a full {@code PUT} is never issued by the UI — so the mirror silently never fired on any
   * update, only on create. Centralizing the method check here means it only needs to be
   * correct in one place; the four call sites in {@code AbstractInvoiceHeaderHandler},
   * {@code AbstractOrderHeaderHandler}, {@code GoodsReceiptHeaderHandler} and
   * {@code GoodsShipmentHeaderHandler} all now delegate to this method instead of repeating
   * (and potentially re-diverging on) the method list.
   *
   * @param method the HTTP method from {@code NeoContext#getHttpMethod()}
   * @return {@code true} for POST, PUT, or PATCH
   */
  static boolean isWriteMethod(String method) {
    return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
  }

  /**
   * Mirrors {@code sourceField} into {@code targetField} on a CRUD write request — the shared
   * body behind each header handler's {@code mirrorAccountingDate} (ETP-4531). Extracted out of
   * {@code AbstractInvoiceHeaderHandler} to keep that class under the Sonar method-count limit
   * (S1448); {@code AbstractOrderHeaderHandler} keeps its own copy.
   *
   * @param context     the current NeoContext
   * @param sourceField the visible date field whose value is copied
   * @param targetField the hidden field overwritten with {@code sourceField}'s value
   */
  static void mirrorAccountingDate(NeoContext context, String sourceField, String targetField) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType()) && isWriteMethod(context.getHttpMethod())) {
      mirrorFieldValue(context.getRequestBody(), sourceField, targetField);
    }
  }

  /**
   * Sets {@code storageBin} to the header {@code M_InOut}'s own warehouse default locator when
   * a line-create request did not already supply a REAL one. Shared by every
   * {@code M_InOutLine}-based create flow — Goods Receipt, Goods Shipment, and Return to Vendor
   * Shipment — so all three anchor a new line's locator to the DOCUMENT'S warehouse
   * ({@code M_InOut.M_Warehouse_ID}), never to the "first locator" found or to the product's
   * on-hand locator (ETP-4863).
   *
   * <p>Originally added receipt-only (ETP-4671): the raw AD default
   * ({@code @OnHandLocatorDefault@}) resolves to "the locator where this product already has
   * stock", which is the right idea for a shipment (pick from what's in stock) but leaves
   * {@code M_Locator_ID} {@code NULL} for a receipt of a brand-new, unstocked product — and
   * {@code M_INOUT_POST} then rejects the document with {@code InoutLineWithoutLocator}. Goods
   * Shipment and Return to Vendor Shipment never got this safeguard, so they hit the mirror-image
   * bug: that same raw default correctly filters by header warehouse ONLY when the tab declares
   * the matching {@code AD_AuxiliaryInput}; when it doesn't (or the re-resolution fails), it falls
   * back to a value cached in the HTTP session from the last window/document touched in that
   * session — a stale, unrelated warehouse. Confirming a shipment/return on the PRINCIPAL
   * warehouse could silently create stock transactions in whatever warehouse happened to be
   * cached, e.g. a "secondary" one.
   *
   * <p>Treats a genuinely absent value, an unresolved {@code @Token@}-shaped literal, AND an
   * explicit-but-wrong-warehouse (or non-existent) locator all as cases requiring correction.
   * This is an UNCONDITIONAL guarantee, confirmed in scope by the product owner (ETP-4863
   * BUG-1) — every line's locator must belong to the header's own warehouse, full stop; it is
   * not a fill-if-absent default that stops validating once any non-blank value shows up. An
   * explicit {@code storageBin} that already belongs to the header's warehouse IS left alone,
   * though — the guarantee is about the warehouse, not about collapsing every line onto the
   * warehouse's single "default" locator. That is also why {@link InventoryLineHandler}'s
   * "always overwrite {@code storageBin} on every POST" pattern (Physical Inventory, a sibling
   * window) was deliberately NOT copied verbatim here: doing so would silently discard a
   * deliberate, valid picking-bin choice — from the user or an "Import from..." flow — whenever
   * that bin is already inside the correct warehouse.
   *
   * @param body the create request body to mutate in place; no-op if {@code null}
   * @param log  the caller's logger, used for debug/warn messages
   */
  static void injectDefaultLocatorIfMissing(JSONObject body, Logger log) throws Exception {
    if (body == null) {
      return;
    }
    String parentId = body.optString(PARAM_PARENT_ID, "");
    if (parentId.isEmpty()) {
      return;
    }
    ShipmentInOut header = OBDal.getInstance().get(ShipmentInOut.class, parentId);
    if (header == null || header.getWarehouse() == null) {
      return;
    }
    String headerWarehouseId = header.getWarehouse().getId();

    String existing = body.optString(FIELD_STORAGE_BIN, null);
    boolean hasRealValue = StringUtils.isNotBlank(existing)
        && !UNRESOLVED_TOKEN.matcher(existing).matches();
    if (hasRealValue && belongsToWarehouse(existing, headerWarehouseId, log)) {
      // Already anchored to the header's own warehouse — a deliberate bin choice, not a gap.
      return;
    }

    String locatorId = resolveDefaultLocatorForWarehouse(headerWarehouseId, log);
    if (locatorId == null) {
      return;
    }
    if (hasRealValue) {
      log.debug("Correcting storageBin={} (wrong/invalid warehouse) to {} for header "
          + "warehouse={}", existing, locatorId, headerWarehouseId);
    } else {
      log.debug("Defaulted storageBin={} to header warehouse={}", locatorId, headerWarehouseId);
    }
    body.put(FIELD_STORAGE_BIN, locatorId);
  }

  /**
   * True when {@code locatorId} resolves to a real, active {@code M_Locator} whose own
   * warehouse matches {@code warehouseId}. Used by {@link #injectDefaultLocatorIfMissing} to
   * decide whether an explicit {@code storageBin} already satisfies the header-warehouse
   * guarantee (ETP-4863 BUG-1) — a locator that doesn't exist, or belongs to a different
   * warehouse, is treated the same as a missing value and gets corrected.
   */
  static boolean belongsToWarehouse(String locatorId, String warehouseId, Logger log) {
    try {
      Locator locator = OBDal.getInstance().get(Locator.class, locatorId);
      return locator != null && locator.getWarehouse() != null
          && warehouseId.equals(locator.getWarehouse().getId());
    } catch (Exception e) {
      log.debug("Could not resolve warehouse for locator {}: {}", locatorId, e.getMessage());
      return false;
    }
  }

  /**
   * Returns the id of the default active {@code M_Locator} for a warehouse, or {@code null} when
   * none is configured. Thin id-only wrapper over {@link #findDefaultLocatorForWarehouse}, kept
   * because the CRUD path ({@link #injectDefaultLocatorIfMissing}) writes the id straight into a
   * JSON request body and never needs the entity.
   */
  static String resolveDefaultLocatorForWarehouse(String warehouseId, Logger log) {
    Locator locator = findDefaultLocatorForWarehouse(warehouseId, log);
    return locator != null ? locator.getId() : null;
  }

  /**
   * Returns the default active {@code M_Locator} entity for a warehouse, or {@code null} when
   * none is configured. Mirrors {@code InventoryLineHandler}'s locator lookup.
   */
  @SuppressWarnings("unchecked")
  static Locator findDefaultLocatorForWarehouse(String warehouseId, Logger log) {
    try {
      return (Locator) OBDal.getInstance().createCriteria(Locator.class)
          .add(Restrictions.eq(Locator.PROPERTY_WAREHOUSE + ".id", warehouseId))
          .add(Restrictions.eq(Locator.PROPERTY_DEFAULT, true))
          .add(Restrictions.eq(Locator.PROPERTY_ACTIVE, true))
          .addOrder(Order.asc(Locator.PROPERTY_SEARCHKEY))
          .setMaxResults(1)
          .uniqueResult();
    } catch (Exception e) {
      log.debug("Could not resolve default locator for warehouse {}: {}", warehouseId,
          e.getMessage());
      return null;
    }
  }

  /**
   * Entity-level counterpart of {@link #injectDefaultLocatorIfMissing}, for the DAL write paths
   * that never reach a {@code NeoHandler} (ETP-4863).
   *
   * <p>{@link #injectDefaultLocatorIfMissing} can only guard requests that arrive as a JSON body
   * on {@code POST /neo/.../lines}. Several flows build {@code M_InOutLine} records directly with
   * {@code OBProvider} + {@code OBDal} — importing the lines of a source document into a return,
   * or projecting an order's lines into a shipment/receipt — and those bypass the hook entirely.
   * They used to copy the SOURCE document's bin verbatim, so a return whose header sits in
   * warehouse A, built from a document whose lines sit in warehouse B, produced stock
   * transactions in B. The bin, not the header, is what {@code M_INOUT_POST} follows.
   *
   * <p>The rule is the same UNCONDITIONAL guarantee the CRUD path applies, confirmed in scope by
   * the product owner: a line's locator must ALWAYS belong to the header document's warehouse.
   * A candidate that already belongs there is a deliberate, valid bin choice and is returned
   * untouched — the guarantee is about the warehouse, not about collapsing every line onto the
   * warehouse's single default locator.
   *
   * @param candidate       the bin the caller would otherwise have used (source line's bin, the
   *                        order's warehouse default, …); may be {@code null}
   * @param headerWarehouse the warehouse of the {@code M_InOut} the line belongs to; when
   *                        {@code null} there is nothing to anchor to and {@code candidate} is
   *                        returned unchanged
   * @param log             the caller's logger
   * @return {@code candidate} when it already belongs to {@code headerWarehouse}; otherwise that
   *         warehouse's default locator, or {@code null} when the warehouse has none configured
   *         (a data-setup error the caller must surface rather than paper over with a bin from
   *         the wrong warehouse)
   */
  static Locator anchorLocatorToWarehouse(Locator candidate, Warehouse headerWarehouse,
      Logger log) {
    if (headerWarehouse == null || headerWarehouse.getId() == null) {
      return candidate;
    }
    String headerWarehouseId = headerWarehouse.getId();
    if (candidate != null && candidate.getWarehouse() != null
        && headerWarehouseId.equals(candidate.getWarehouse().getId())) {
      return candidate;
    }
    Locator anchored = findDefaultLocatorForWarehouse(headerWarehouseId, log);
    if (anchored == null) {
      log.warn("No default locator configured for warehouse {}; storage bin left unanchored",
          headerWarehouseId);
    } else if (candidate != null) {
      log.debug("Re-anchored storage bin {} to {} (header warehouse {})", candidate.getId(),
          anchored.getId(), headerWarehouseId);
    }
    return anchored;
  }
}
