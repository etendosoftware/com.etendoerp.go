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

import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.cleanText;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.text;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.A_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ADDRESS_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CANDIDATES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DESCRIPTION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LOCATION_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MATCHED_BY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MATCHED_MANUAL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;


import java.util.Iterator;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * The operations of {@link InvoiceInboxApply} that choose or create the vendor of a document and
 * the product of a line (ETP-6050): {@code selectVendor}, {@code newVendor},
 * {@code selectProduct} and {@code newProduct}. Only ids are stored; every id is resolved again
 * through the lookups.
 */
final class InvoiceInboxApplyResolution {

  private static final String KEY_ID = "id";
  private static final String KEY_FIELDS = "fields";

  private InvoiceInboxApplyResolution() {
  }

  private static JSONObject vendorOf(InvoiceInboxApply.Edit edit) {
    return edit.header().optJSONObject(K_VENDOR);
  }

  static void selectVendor(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    String id = op.getString(KEY_ID);
    JSONObject vendor = vendorOf(edit);
    boolean alreadyLinked = S_LINKED.equals(vendor.optString(K_STATE)) && id.equals(
        vendor.optString(K_ID));
    if (alreadyLinked) {
      return;
    }
    Optional<Party> found = edit.lookups == null ? Optional.empty() : edit.lookups.vendorById(id);
    if (found.isEmpty()) {
      throw new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
          InvoiceInboxApply.CODE_UNKNOWN_VENDOR, InvoiceInboxMessages.UNKNOWN_VENDOR, null));
    }
    Address address = edit.lookups.vendorAddress(id);
    boolean present = address.state() == OcrLookups.AddressState.PRESENT
        && address.locationId() != null;
    vendor.put(K_STATE, S_LINKED).put(K_MATCHED_BY, MATCHED_MANUAL).put(K_ID, found.get().id())
        .put(K_NAME, found.get().name()).put(K_ADDRESS_STATE, address.state().name())
        .put(K_LOCATION_ID, present ? address.locationId() : JSONObject.NULL);
    vendor.remove("details");
    edit.vendorChanged = true;
    edit.changed(InvoiceInboxAdjustedPolicy.selection(false));
  }

  static void newVendor(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    JSONObject vendor = vendorOf(edit);
    JSONObject extracted = vendor.optJSONObject(K_EXTRACTED);
    if (extracted == null) {
      extracted = new JSONObject();
    }
    boolean wasLinked = S_LINKED.equals(vendor.optString(K_STATE));
    boolean wasNew = S_NEW.equals(vendor.optString(K_STATE));
    JSONObject fields = op.optJSONObject(KEY_FIELDS);
    boolean anyDiffers = false;
    JSONObject merged = new JSONObject(extracted.toString());
    Iterator<?> keys = fields == null ? new JSONObject().keys() : fields.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      String cleaned = cleanText(fields.get(key), key, null);
      if (!cleaned.equals(text(extracted, key))) {
        merged.put(key, cleaned);
        anyDiffers = true;
      }
    }
    if (wasNew && !anyDiffers) {
      return;
    }
    vendor.remove(K_ID);
    vendor.remove(K_NAME);
    vendor.remove(K_MATCHED_BY);
    vendor.remove("details");
    vendor.put(K_STATE, S_NEW).put(K_EXTRACTED, merged).put(K_ADDRESS_STATE, A_UNKNOWN)
        .put(K_LOCATION_ID, JSONObject.NULL);
    if (vendor.optJSONArray(K_CANDIDATES) == null) {
      vendor.put(K_CANDIDATES, new JSONArray());
    }
    edit.vendorChanged = true;
    edit.changed(InvoiceInboxAdjustedPolicy.newVendor(wasLinked, anyDiffers));
  }

  static void selectProduct(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    JSONObject line = InvoiceInboxApply.lineOf(edit, index);
    String id = op.getString(KEY_ID);
    JSONObject product = line.optJSONObject(K_PRODUCT);
    boolean alreadyLinked = product != null && S_LINKED.equals(product.optString(K_STATE))
        && id.equals(product.optString(K_ID));
    if (alreadyLinked) {
      return;
    }
    Optional<Party> found = edit.lookups == null ? Optional.empty()
        : edit.lookups.productById(id);
    if (found.isEmpty()) {
      throw new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
          InvoiceInboxApply.CODE_UNKNOWN_PRODUCT, InvoiceInboxMessages.UNKNOWN_PRODUCT,
          InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    line.put(K_PRODUCT, new JSONObject().put(K_STATE, S_LINKED).put(K_MATCHED_BY, MATCHED_MANUAL)
        .put(K_ID, found.get().id()).put(K_NAME, found.get().name())
        .put(K_CANDIDATES, candidatesOf(product)));
    edit.changed(InvoiceInboxAdjustedPolicy.selection(false));
  }

  static void newProduct(InvoiceInboxApply.Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    JSONObject line = InvoiceInboxApply.lineOf(edit, index);
    JSONObject product = line.optJSONObject(K_PRODUCT);
    boolean wasLinked = product != null && S_LINKED.equals(product.optString(K_STATE));
    boolean wasNew = product != null && S_NEW.equals(product.optString(K_STATE));
    JSONObject stored = product == null ? null : product.optJSONObject(K_NEW);
    String currentName = stored != null && stored.has(K_NAME) ? text(stored, K_NAME)
        : text(line, K_DESCRIPTION);
    String name = op.has(K_NAME) ? cleanText(op.get(K_NAME), K_NAME, index) : currentName;
    JSONObject draft = new JSONObject().put(K_NAME, name);
    resolveChoice(edit, op, stored, draft, index, true);
    resolveChoice(edit, op, stored, draft, index, false);
    boolean nameDiffers = !name.equals(currentName);
    boolean same = wasNew && stored != null && sameDraft(stored, draft);
    if (same) {
      return;
    }
    line.put(K_PRODUCT, new JSONObject().put(K_STATE, S_NEW).put(K_ID, JSONObject.NULL)
        .put(K_CANDIDATES, candidatesOf(product)).put(K_NEW, draft));
    edit.changed(InvoiceInboxAdjustedPolicy.newProduct(true, wasLinked, nameDiffers));
  }

  /**
   * Resolves the category (or the unit) of the draft: the one asked for (its id is checked), the
   * stored id when the operation does not mention it, none when {@code null}. Only the ID is
   * stored: the name is a display value that {@link InvoiceInboxReview} adds at read time in the
   * language of the request, so a stored copy can never be stale or in another language. A name
   * stored by an earlier version is ignored here and dropped on the next write.
   */
  private static void resolveChoice(InvoiceInboxApply.Edit edit, JSONObject op,
      JSONObject stored, JSONObject draft, int index, boolean category) throws JSONException {
    String idKey = category ? K_CATEGORY_ID : K_UOM_ID;
    if (!op.has(idKey)) {
      if (stored != null && !StringUtils.isBlank(text(stored, idKey))) {
        draft.put(idKey, text(stored, idKey));
      }
      return;
    }
    Object value = op.get(idKey);
    if (JSONObject.NULL.equals(value)) {
      return;
    }
    Optional<Party> found = findChoice(edit, (String) value, category);
    if (found.isEmpty()) {
      throw new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
          category ? InvoiceInboxApply.CODE_UNKNOWN_CATEGORY : InvoiceInboxApply.CODE_UNKNOWN_UOM,
          category ? InvoiceInboxMessages.UNKNOWN_CATEGORY : InvoiceInboxMessages.UNKNOWN_UOM,
          InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    draft.put(idKey, found.get().id());
  }

  /** The category (or unit) with this id for the session, empty when there is none. */
  private static Optional<Party> findChoice(InvoiceInboxApply.Edit edit, String id,
      boolean category) {
    if (edit.lookups == null) {
      return Optional.empty();
    }
    return category ? edit.lookups.productCategoryById(id) : edit.lookups.uomById(id);
  }

  private static boolean sameDraft(JSONObject stored, JSONObject draft) {
    return text(stored, K_NAME).equals(text(draft, K_NAME))
        && text(stored, K_CATEGORY_ID).equals(text(draft, K_CATEGORY_ID))
        && text(stored, K_UOM_ID).equals(text(draft, K_UOM_ID));
  }

  private static JSONArray candidatesOf(JSONObject product) throws JSONException {
    JSONArray candidates = product == null ? null : product.optJSONArray(K_CANDIDATES);
    return candidates == null ? new JSONArray() : new JSONArray(candidates.toString());
  }
}
