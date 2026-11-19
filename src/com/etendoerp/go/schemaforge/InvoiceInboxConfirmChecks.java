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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;

/**
 * What {@code confirm} judges about the document BEFORE it writes anything (ETP-6051), always on the
 * stored {@code CURRENT_DATA} and the data of now, never on what the browser showed:
 * <ol>
 *   <li>the verdict of {@link InvoiceInboxLiveChecks}: an invalid document is refused with 422
 *   {@code not_valid} and its missing codes, and the row moves as the status policy says; a document
 *   whose only gaps are the live ones is refused with their own code,
 *   {@code vendor_address_missing} or {@code missing_exchange_rate}, and the row is left as it is
 *   (the user fixes the data outside the document: the rate or the vendor);</li>
 *   <li>a purchase invoice of the same vendor with the same number, not voided, which blocks with
 *   422 {@code duplicate_invoice} and the invoice it clashes with;</li>
 *   <li>the address of a linked vendor, asked again: it may have changed since the document was
 *   read.</li>
 * </ol>
 */
final class InvoiceInboxConfirmChecks {

  private InvoiceInboxConfirmChecks() {
  }

  /**
   * Judges the document.
   *
   * @param row      the locked row
   * @param document the stored document
   * @param lookups  the lookups of the document type, may be empty
   * @return the refusal to answer, {@code null} when the document can be confirmed
   * @throws JSONException when an answer cannot be built
   */
  static NeoResponse preflight(IncomingDocument row, JSONObject document,
      Optional<OcrLookups> lookups) throws JSONException {
    InvoiceInboxValidator.Result verdict = InvoiceInboxLiveChecks.verdict(document, row);
    if (!verdict.isValid()) {
      return refuseInvalid(row, verdict, document);
    }
    JSONObject header = document.optJSONObject(K_HEADER);
    JSONObject vendor = header.optJSONObject(K_VENDOR);
    if (lookups.isEmpty()) {
      return null;
    }
    NeoResponse stale = references(document, header, vendor, lookups.get());
    if (stale != null) {
      return stale;
    }
    if (priceListIncludesTax(vendor)) {
      return InvoiceInboxEditGate.refuse(422, InvoiceInboxConfirm.CODE_PRICE_LIST_TAX_INCLUDED,
          InvoiceInboxMessages.PRICE_LIST_TAX_INCLUDED, null);
    }
    NeoResponse duplicate = duplicate(header, vendor, lookups.get());
    return duplicate != null ? duplicate : linkedAddress(vendor, lookups.get());
  }

  /**
   * The ids the stored document points at, resolved again NOW through the tenant-scoped lookups (as
   * {@code apply} does when they are chosen): a vendor, product, category, unit or tax that was
   * deactivated, or never was this tenant's, since the review is refused. A vendor that no longer
   * resolves is the missing-address refusal (the row is left as it is); the others are 422
   * {@code not_valid} naming what is stale, also without moving the row.
   */
  private static NeoResponse references(JSONObject document, JSONObject header, JSONObject vendor,
      OcrLookups lookups) throws JSONException {
    if (vendor != null && S_LINKED.equals(vendor.optString(K_STATE, ""))
        && lookups.vendorById(vendor.optString(K_ID)).isEmpty()) {
      return vendorAddressMissing();
    }
    List<String> stale = new ArrayList<>();
    String date = header.optString(K_INVOICE_DATE, null);
    JSONArray lines = document.optJSONArray(K_LINES);
    for (int i = 0; lines != null && i < lines.length(); i++) {
      staleLine(lines.getJSONObject(i), i, date, lookups, stale);
    }
    if (stale.isEmpty()) {
      return null;
    }
    JSONObject details = new JSONObject().put("missing", new JSONArray(stale));
    return InvoiceInboxEditGate.refuse(422, InvoiceInboxMarkReviewed.CODE_NOT_VALID,
        InvoiceInboxMessages.NOT_VALID, details, "missing", StringUtils.join(stale, ", "));
  }

  private static void staleLine(JSONObject line, int position, String date, OcrLookups lookups,
      List<String> stale) {
    String id = "line[" + line.optInt(K_INDEX, position) + "].";
    JSONObject product = line.optJSONObject(K_PRODUCT);
    if (product != null && S_LINKED.equals(product.optString(K_STATE, ""))
        && lookups.productById(product.optString(K_ID)).isEmpty()) {
      stale.add(id + "product");
    }
    JSONObject draft = product == null ? null : product.optJSONObject(K_NEW);
    if (draft != null && (lookups.productCategoryById(draft.optString(K_CATEGORY_ID, "")).isEmpty()
        || lookups.uomById(draft.optString(K_UOM_ID, "")).isEmpty())) {
      stale.add(id + "product");
    }
    JSONObject tax = line.optJSONObject(K_TAX);
    String taxId = tax == null ? "" : tax.optString(K_ID, "");
    if (!taxId.isEmpty() && lookups.taxById(taxId, date).isEmpty()) {
      stale.add(id + "tax");
    }
  }

  /**
   * Whether the price list that will price the invoice includes tax (ETP-6051). The invoice takes the
   * purchase price list of its vendor, else the default purchase price list; a NEW vendor has none yet
   * and cannot be tax-included. The inbox sends the NET price, so on such a list the engine would total
   * the draft at zero: the confirm refuses up front, before writing anything and without touching the
   * row (the fix is in the vendor's price list, not in the document). GO itself discards
   * {@code priceIncludesTax}; only Classic or imported data has such lists.
   *
   * <p>Known limit: for a linked vendor with no purchase list this takes the first default purchase
   * list ({@code setMaxResults(1)}, no ordering), which may differ from the list the NEO create
   * defaults assign when several default lists exist. The totals check is the backstop.</p>
   */
  private static boolean priceListIncludesTax(JSONObject vendor) {
    if (vendor == null || !S_LINKED.equals(vendor.optString(K_STATE, ""))) {
      return false;
    }
    BusinessPartner partner = OBDal.getInstance().get(BusinessPartner.class,
        vendor.optString(K_ID));
    PriceList list = partner == null ? null : partner.getPurchasePricelist();
    if (list == null) {
      OBCriteria<PriceList> criteria = OBDal.getInstance().createCriteria(PriceList.class);
      criteria.add(Restrictions.eq(PriceList.PROPERTY_SALESPRICELIST, false));
      criteria.add(Restrictions.eq(PriceList.PROPERTY_DEFAULT, true));
      criteria.setMaxResults(1);
      list = (PriceList) criteria.uniqueResult();
    }
    return list != null && Boolean.TRUE.equals(list.isPriceIncludesTax());
  }

  private static NeoResponse refuseInvalid(IncomingDocument row,
      InvoiceInboxValidator.Result verdict, JSONObject document) throws JSONException {
    List<String> missing = verdict.getMissing();
    boolean onlyLive = InvoiceInboxValidator.MISSING_FIELDS.equals(verdict.getErrorCode())
        && missing.stream().allMatch(code -> InvoiceInboxLiveChecks.VENDOR_ADDRESS.equals(code)
            || InvoiceInboxLiveChecks.EXCHANGE_RATE.equals(code));
    if (!onlyLive) {
      return InvoiceInboxMarkReviewed.notValid(row, verdict);
    }
    return missing.contains(InvoiceInboxLiveChecks.VENDOR_ADDRESS) ? vendorAddressMissing()
        : missingRate(row, document);
  }

  static NeoResponse vendorAddressMissing() {
    return InvoiceInboxEditGate.refuse(422, InvoiceInboxConfirm.CODE_VENDOR_ADDRESS_MISSING,
        InvoiceInboxMessages.VENDOR_ADDRESS_MISSING, null);
  }

  private static NeoResponse missingRate(IncomingDocument row, JSONObject document) {
    JSONObject header = document.optJSONObject(K_HEADER);
    String orgId = row.getOrganization().getId();
    String from = isoOf(OBCurrencyUtils.getOrgCurrency(orgId));
    String to = isoOf(InvoiceInboxLiveChecks.documentCurrencyId(header, row, orgId));
    return InvoiceInboxEditGate.refuse(422, InvoiceInboxConfirm.CODE_MISSING_EXCHANGE_RATE,
        InvoiceInboxMessages.MISSING_EXCHANGE_RATE, null, "from", from, "to", to);
  }

  private static String isoOf(String currencyId) {
    Currency currency = currencyId == null ? null
        : OBDal.getInstance().get(Currency.class, currencyId);
    return currency == null ? "" : currency.getISOCode();
  }

  private static NeoResponse duplicate(JSONObject header, JSONObject vendor, OcrLookups lookups)
      throws JSONException {
    String documentNo = header.optString(K_DOCUMENT_NO, "").trim();
    if (vendor == null || !S_LINKED.equals(vendor.optString(K_STATE, ""))
        || documentNo.isEmpty()) {
      return null;
    }
    OcrLookups.Duplicates found = lookups.duplicates(vendor.optString(K_ID), documentNo);
    if (!found.known() || found.documents().isEmpty()) {
      return null;
    }
    OcrLookups.Duplicate first = found.documents().get(0);
    String number = StringUtils.defaultString(first.documentNo());
    JSONObject details = InvoiceInboxEditGate.details("invoice",
        new JSONObject().put("id", first.id()).put("documentNo", number));
    return InvoiceInboxEditGate.refuse(422, InvoiceInboxConfirm.CODE_DUPLICATE_INVOICE,
        InvoiceInboxMessages.DUPLICATE_INVOICE, details, "documentNo", number);
  }

  private static NeoResponse linkedAddress(JSONObject vendor, OcrLookups lookups) {
    if (vendor == null || !S_LINKED.equals(vendor.optString(K_STATE, ""))) {
      return null;
    }
    OcrLookups.Address address = lookups.vendorAddress(vendor.optString(K_ID));
    return address.state() == OcrLookups.AddressState.MISSING ? vendorAddressMissing() : null;
  }
}
