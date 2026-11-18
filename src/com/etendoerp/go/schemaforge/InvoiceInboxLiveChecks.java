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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.A_MISSING;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ADDRESS_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;

/**
 * The validation of an inbox document that needs the database (ETP-6051), run on top of the pure
 * {@link InvoiceInboxValidator}: what would stop the invoice from being created, told BEFORE the user
 * confirms, as codes of the {@code missing} list of the verdict:
 * <ul>
 *   <li>{@value #VENDOR_ADDRESS}: the vendor has no usable address. A linked vendor whose stored
 *   {@code addressState} is {@code MISSING}; a new one whose document prints no address data, or
 *   prints a country that matches none (the address is never invented, and the country never
 *   guessed).</li>
 *   <li>{@value #EXCHANGE_RATE}: the document is in a currency other than its organization's and no
 *   rate is valid on the invoice date ({@code C_Conversion_Rate}), the same lookup the invoice
 *   itself runs.</li>
 * </ul>
 * Both are total: a lookup that cannot be made adds nothing (the confirm judges them again, with the
 * database, and refuses with its own code).
 */
final class InvoiceInboxLiveChecks {

  /** Missing code: the vendor has no usable address. */
  static final String VENDOR_ADDRESS = "vendorAddress";
  /** Missing code: no exchange rate is valid on the invoice date. */
  static final String EXCHANGE_RATE = "exchangeRate";

  private static final Logger log = LogManager.getLogger(InvoiceInboxLiveChecks.class);
  private static final String COUNTRIES_SQL = "SELECT c_country_id, countrycode, name FROM"
      + " c_country WHERE isactive = 'Y' UNION ALL SELECT t.c_country_id, c.countrycode, t.name"
      + " FROM c_country_trl t JOIN c_country c ON c.c_country_id = t.c_country_id"
      + " WHERE t.isactive = 'Y' AND c.isactive = 'Y'";

  private InvoiceInboxLiveChecks() {
  }

  /**
   * The verdict of a stored document: the pure one plus the live codes.
   *
   * @param document the {@code CURRENT_DATA} object
   * @param row      the inbox row (organization, client, target type)
   * @return the verdict
   */
  static InvoiceInboxValidator.Result verdict(JSONObject document, IncomingDocument row) {
    return InvoiceInboxValidator.validate(document, extraMissing(document, row));
  }

  /**
   * The live missing codes of a document, in order: vendor address, exchange rate.
   *
   * @param document the {@code CURRENT_DATA} object
   * @param row      the inbox row
   * @return the codes, empty when nothing is missing or nothing could be asked
   */
  static List<String> extraMissing(JSONObject document, IncomingDocument row) {
    List<String> missing = new ArrayList<>();
    JSONObject header = document == null ? null : document.optJSONObject(K_HEADER);
    if (header == null) {
      return missing;
    }
    try {
      if (!vendorAddressUsable(header.optJSONObject(K_VENDOR))) {
        missing.add(VENDOR_ADDRESS);
      }
      if (rateMissing(header, row)) {
        missing.add(EXCHANGE_RATE);
      }
    } catch (RuntimeException e) {
      log.warn("Invoice inbox live checks failed: {}", e.getClass().getSimpleName());
    }
    return missing;
  }

  /** Whether the vendor of the document can carry an invoice address. */
  static boolean vendorAddressUsable(JSONObject vendor) {
    String state = vendor == null ? "" : vendor.optString(K_STATE, "");
    if (S_LINKED.equals(state)) {
      return !A_MISSING.equals(vendor.optString(K_ADDRESS_STATE, ""));
    }
    if (!S_NEW.equals(state)) {
      return true;
    }
    JSONObject extracted = vendor.optJSONObject(K_EXTRACTED);
    if (!InvoiceInboxConfirmSeeds.hasUsableAddress(extracted)) {
      return false;
    }
    String printed = extracted.optString(InvoiceInboxConfirmSeeds.K_COUNTRY, "");
    return StringUtils.isBlank(printed)
        || InvoiceInboxConfirmSeeds.countryId(printed, countries()) != null;
  }

  /** The active countries and their translated names, for {@link InvoiceInboxConfirmSeeds}. */
  @SuppressWarnings("unchecked")
  static List<InvoiceInboxConfirmSeeds.CountryRow> countries() {
    List<Object[]> rows = OBDal.getInstance().getSession().createNativeQuery(COUNTRIES_SQL)
        .list();
    List<InvoiceInboxConfirmSeeds.CountryRow> out = new ArrayList<>();
    for (Object[] row : rows) {
      out.add(new InvoiceInboxConfirmSeeds.CountryRow((String) row[0], (String) row[1],
          (String) row[2]));
    }
    return out;
  }

  private static boolean rateMissing(JSONObject header, IncomingDocument row) {
    LocalDate date = dateOf(header.optString(K_INVOICE_DATE, ""));
    String orgId = row.getOrganization() == null ? null : row.getOrganization().getId();
    String clientId = row.getClient() == null ? null : row.getClient().getId();
    String currencyId = documentCurrencyId(header, row, orgId);
    if (date == null || orgId == null || currencyId == null) {
      return false;
    }
    String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    return orgCurrencyId != null && !orgCurrencyId.equals(currencyId)
        && DocumentCurrencyRate.lookupRate(orgCurrencyId, currencyId, date, orgId, clientId)
            == null;
  }

  /** The currency stored in the header, else the one derived now; {@code null} when unknown. */
  static String documentCurrencyId(JSONObject header, IncomingDocument row, String orgId) {
    String stored = header.optString(K_CURRENCY_ID, "");
    if (!stored.isEmpty()) {
      return stored;
    }
    Optional<OcrLookups> lookups = InvoiceInboxReview.lookupsFor(row.getTargetType());
    if (lookups.isEmpty()) {
      return null;
    }
    JSONObject vendor = header.optJSONObject(K_VENDOR);
    String vendorId = vendor != null && S_LINKED.equals(vendor.optString(K_STATE, ""))
        ? vendor.optString(K_ID, null) : null;
    return lookups.get().documentCurrency(vendorId, orgId).map(OcrLookups.DocCurrency::id)
        .orElse(null);
  }

  /**
   * The currency of the document: the one stored in the header, else the one derived now (vendor
   * purchase price list, then organization, then client: what the worker, the SPA and
   * {@code PurchaseInvoiceOcrLookups#documentCurrency} agree on), so the column, the review answer and
   * the SPA banner use the same decimals. Total: {@code null} when it cannot be known.
   *
   * @param document the {@code CURRENT_DATA} object
   * @param row      the inbox row
   * @return the currency, or {@code null}
   */
  static Currency documentCurrency(JSONObject document, IncomingDocument row) {
    try {
      JSONObject header = document == null ? null : document.optJSONObject(K_HEADER);
      String orgId = row.getOrganization() == null ? null : row.getOrganization().getId();
      String id = header == null ? null : documentCurrencyId(header, row, orgId);
      return id == null ? null : OBDal.getInstance().get(Currency.class, id);
    } catch (RuntimeException e) {
      log.warn("Invoice inbox document currency lookup failed: {}", e.getClass().getSimpleName());
      return null;
    }
  }

  private static LocalDate dateOf(String iso) {
    try {
      return iso.isEmpty() ? null : LocalDate.parse(iso);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
