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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.plm.Product;

import com.etendoerp.go.schemaforge.DefaultTaxResolver.Determination;
import com.etendoerp.go.schemaforge.DefaultTaxResolver.Result;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.TaxIdNormalizer;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoTrl;

/**
 * The lookups of the purchase-invoice OCR review (ETP-5715): what the application's review steps ask
 * the server when a supplier invoice is scanned, answered by the same code.
 *
 * <ul>
 *   <li><b>vendor and address</b> — the invoice header's own selectors ({@code C_BPartner_ID},
 *       {@code C_BPartner_Location_ID}), called with the context the form gives them;</li>
 *   <li><b>duplicates</b> — the invoices of the vendor with the same supplier number
 *       ({@code orderReference}), case-insensitively, not voided;</li>
 *   <li><b>products</b> — the similarity search the application's {@code /simsearch} runs, 3 candidates
 *       from 50 % up;</li>
 *   <li><b>taxes</b> — {@link PurchaseInvoiceLineHandler#resolveOwned}, the rule behind the lines'
 *       {@code defaultTax} action.</li>
 * </ul>
 *
 * <p>This class is the purchase-invoice customization of the review: what names purchase invoices
 * lives here and nowhere in the shared review ({@code ocr} package), which reaches it through the
 * {@code resolver} name of the OCR configuration ({@link OcrLookupsRegistry}). Every method is total:
 * a lookup that fails is logged (class name only, never document text) and answers "nothing found" or
 * "unknown".</p>
 */
public final class PurchaseInvoiceOcrLookups implements OcrLookups {

  /** The name the OCR configuration uses to bind a document type to these lookups. */
  public static final String RESOLVER = "purchase-invoice";

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceOcrLookups.class);

  private static final String SPEC = "purchase-invoice";
  private static final String HEADER = "header";
  private static final String COLUMN_VENDOR = "C_BPartner_ID";
  private static final String COLUMN_ADDRESS = "C_BPartner_Location_ID";
  private static final int DUPLICATE_LIMIT = 10;
  private static final int PRODUCT_CANDIDATES = 3;
  private static final String PRODUCT_MIN_SIMILARITY = "50";
  private static final String KEY_ID = "id";
  private static final String KEY_NAME = "name";
  private static final String KEY_DATA = "data";
  private static final String VOIDED = "VO";

  /** What the invoice form hands the vendor selectors. */
  private static Map<String, String> vendorContext() {
    return new HashMap<>(Map.of("isSOTrx", "N", "isVendor", "Y"));
  }

  @Override
  public List<Party> searchVendors(String name, int limit) {
    List<Party> parties = new ArrayList<>();
    for (JSONObject item : selectorItems(COLUMN_VENDOR, name, limit, vendorContext())) {
      parties.add(new Party(item.optString(KEY_ID), labelOf(item)));
    }
    return parties;
  }

  @Override
  public List<Party> vendorsByTaxId(String cleanTaxId, int limit) {
    Set<String> spellings = TaxIdNormalizer.variants(cleanTaxId);
    if (spellings.isEmpty()) {
      return List.of();
    }
    try {
      OBQuery<BusinessPartner> query = OBDal.getInstance().createQuery(BusinessPartner.class,
          "as bp where bp.vendor = true and bp.active = true and bp.client.id = :clientId"
              + " and upper(replace(replace(replace(replace(bp.taxID, '-', ''), ' ', ''),"
              + " '.', ''), '/', '')) in (:spellings) order by bp.name");
      query.setNamedParameter("clientId", OBContext.getOBContext().getCurrentClient().getId());
      query.setNamedParameter("spellings", spellings);
      query.setMaxResult(limit);
      List<Party> parties = new ArrayList<>();
      for (BusinessPartner partner : query.list()) {
        if (TenantOwnership.isVisibleToCurrentTenant(partner)) {
          parties.add(new Party(partner.getId(), partner.getName()));
        }
      }
      return parties;
    } catch (RuntimeException e) {
      log.warn("OCR vendor lookup by tax id failed: {}", e.getClass().getSimpleName());
      return List.of();
    }
  }

  @Override
  public String vendorTaxId(String vendorId) {
    try {
      BusinessPartner partner = TenantOwnership.loadOwned(BusinessPartner.class, vendorId);
      return partner == null ? "" : StringUtils.trimToEmpty(partner.getTaxID());
    } catch (RuntimeException e) {
      log.warn("OCR vendor tax id read failed: {}", e.getClass().getSimpleName());
      return "";
    }
  }

  @Override
  public Optional<Party> vendorById(String id) {
    try {
      BusinessPartner partner = StringUtils.isBlank(id) ? null
          : OBDal.getInstance().get(BusinessPartner.class, id);
      if (partner == null || !Boolean.TRUE.equals(partner.isVendor())
          || !Boolean.TRUE.equals(partner.isActive())
          || !TenantOwnership.isVisibleToCurrentTenant(partner)) {
        return Optional.empty();
      }
      return Optional.of(new Party(partner.getId(), partner.getName()));
    } catch (RuntimeException e) {
      log.warn("OCR vendor lookup by id failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public Optional<Party> productById(String id) {
    try {
      Product product = StringUtils.isBlank(id) ? null : OBDal.getInstance().get(Product.class, id);
      if (product == null || !Boolean.TRUE.equals(product.isActive())
          || !TenantOwnership.isVisibleToCurrentTenant(product)) {
        return Optional.empty();
      }
      return Optional.of(new Party(product.getId(), product.getName()));
    } catch (RuntimeException e) {
      log.warn("OCR product lookup by id failed: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public Map<String, VendorDetails> vendorDetails(Collection<String> vendorIds) {
    Collection<String> ids = distinctIds(vendorIds);
    Map<String, VendorDetails> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    try {
      OBQuery<BusinessPartner> query = OBDal.getInstance().createQuery(BusinessPartner.class,
          "as bp where bp.id in (:ids)");
      query.setNamedParameter("ids", ids);
      List<BusinessPartner> visible = new ArrayList<>();
      for (BusinessPartner partner : query.list()) {
        if (TenantOwnership.isVisibleToCurrentTenant(partner)) {
          visible.add(partner);
        }
      }
      Map<String, String> cities = firstCities(visible);
      for (BusinessPartner partner : visible) {
        out.put(partner.getId(), new VendorDetails(StringUtils.defaultString(partner.getName()),
            StringUtils.trimToEmpty(partner.getTaxID()), cities.getOrDefault(partner.getId(), "")));
      }
    } catch (RuntimeException e) {
      log.warn("OCR vendor details lookup failed: {}", e.getClass().getSimpleName());
    }
    return out;
  }

  /**
   * The city of each partner's first (oldest) active address, one query for all of them. A failure
   * only costs the cities: the caller keeps name and tax id.
   */
  private static Map<String, String> firstCities(List<BusinessPartner> partners) {
    Map<String, String> cities = new HashMap<>();
    if (partners.isEmpty()) {
      return cities;
    }
    try {
      List<String> ids = new ArrayList<>();
      for (BusinessPartner partner : partners) {
        ids.add(partner.getId());
      }
      OBQuery<Location> query = OBDal.getInstance().createQuery(Location.class,
          "as l where l.businessPartner.id in (:ids) and l.active = true"
              + " order by l.creationDate, l.id");
      query.setNamedParameter("ids", ids);
      for (Location location : query.list()) {
        String partnerId = location.getBusinessPartner().getId();
        if (!cities.containsKey(partnerId)) {
          cities.put(partnerId, location.getLocationAddress() == null ? ""
              : StringUtils.trimToEmpty(location.getLocationAddress().getCityName()));
        }
      }
    } catch (RuntimeException e) {
      log.warn("OCR vendor city lookup failed: {}", e.getClass().getSimpleName());
    }
    return cities;
  }

  @Override
  public Map<String, ProductDetails> productDetails(Collection<String> productIds) {
    Collection<String> ids = distinctIds(productIds);
    Map<String, ProductDetails> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    try {
      OBQuery<Product> query = OBDal.getInstance().createQuery(Product.class,
          "as p where p.id in (:ids)");
      query.setNamedParameter("ids", ids);
      List<Product> visible = new ArrayList<>();
      for (Product product : query.list()) {
        if (TenantOwnership.isVisibleToCurrentTenant(product)) {
          visible.add(product);
        }
      }
      // Names follow the session language like the selectors do (NeoTrl, the *_Trl rows), and fall
      // back to the base name. BusinessPartner has no translation table.
      String language = NeoLanguage.currentCode();
      Map<String, String> productNames = NeoTrl.translatedNames("Product", ids, language);
      Map<String, String> categoryNames = NeoTrl.translatedNames("ProductCategory",
          idsOf(visible, true), language);
      Map<String, String> uomNames = NeoTrl.translatedNames("UOM", idsOf(visible, false),
          language);
      for (Product product : visible) {
        String category = product.getProductCategory() == null ? ""
            : categoryNames.getOrDefault(product.getProductCategory().getId(),
                StringUtils.defaultString(product.getProductCategory().getName()));
        String uom = product.getUOM() == null ? ""
            : uomNames.getOrDefault(product.getUOM().getId(),
                StringUtils.defaultString(product.getUOM().getName()));
        out.put(product.getId(), new ProductDetails(productNames.getOrDefault(product.getId(),
            StringUtils.defaultString(product.getName())),
            StringUtils.defaultString(product.getSearchKey()), category, uom));
      }
    } catch (RuntimeException e) {
      log.warn("OCR product details lookup failed: {}", e.getClass().getSimpleName());
    }
    return out;
  }

  /** The category ids (or unit ids) of the products, once each. */
  private static Collection<String> idsOf(List<Product> products, boolean categories) {
    Set<String> out = new LinkedHashSet<>();
    for (Product product : products) {
      if (categories && product.getProductCategory() != null) {
        out.add(product.getProductCategory().getId());
      } else if (!categories && product.getUOM() != null) {
        out.add(product.getUOM().getId());
      }
    }
    return out;
  }

  /** Non-blank ids, once each, in first-seen order. */
  private static Collection<String> distinctIds(Collection<String> ids) {
    Set<String> out = new LinkedHashSet<>();
    if (ids != null) {
      for (String id : ids) {
        if (!StringUtils.isBlank(id)) {
          out.add(id);
        }
      }
    }
    return out;
  }

  @Override
  public Address vendorAddress(String vendorId) {
    Map<String, String> context = vendorContext();
    context.put(COLUMN_VENDOR, vendorId);
    NeoResponse response = selector(COLUMN_ADDRESS, null, 1, context);
    if (response == null) {
      return new Address(AddressState.UNKNOWN, null);
    }
    JSONArray items = response.getBody() == null ? null : response.getBody().optJSONArray("items");
    if (items == null) {
      return new Address(AddressState.UNKNOWN, null);
    }
    JSONObject first = items.optJSONObject(0);
    return first == null || StringUtils.isBlank(first.optString(KEY_ID, ""))
        ? new Address(AddressState.MISSING, null)
        : new Address(AddressState.PRESENT, first.optString(KEY_ID));
  }

  @Override
  public Duplicates duplicates(String vendorId, String documentNo) {
    String wanted = StringUtils.trimToEmpty(documentNo);
    if (vendorId == null || wanted.isEmpty()) {
      return new Duplicates(false, List.of());
    }
    try {
      OBQuery<Invoice> query = OBDal.getInstance().createQuery(Invoice.class,
          "as i where i.salesTransaction = false and i.businessPartner.id = :vendorId"
              + " and upper(trim(i.orderReference)) = :reference"
              + " and i.documentStatus <> :voided");
      query.setNamedParameter("vendorId", vendorId);
      query.setNamedParameter("reference", wanted.toUpperCase(Locale.ROOT));
      query.setNamedParameter("voided", VOIDED);
      query.setMaxResult(DUPLICATE_LIMIT);
      List<Duplicate> found = new ArrayList<>();
      for (Invoice invoice : query.list()) {
        found.add(new Duplicate(invoice.getId(), invoice.getDocumentNo()));
      }
      return new Duplicates(true, found);
    } catch (RuntimeException e) {
      log.warn("OCR duplicate lookup failed: {}", e.getClass().getSimpleName());
      return new Duplicates(false, List.of());
    }
  }

  @Override
  public List<List<Candidate>> productCandidates(List<String> descriptions) {
    List<List<Candidate>> out = new ArrayList<>();
    for (String description : descriptions) {
      out.add(similarProducts(description));
    }
    return out;
  }

  private static List<Candidate> similarProducts(String description) {
    List<Candidate> candidates = new ArrayList<>();
    try {
      JSONObject found = NeoSimSearchEndpoint.searchTerm("Product", description,
          PRODUCT_CANDIDATES, PRODUCT_MIN_SIMILARITY);
      JSONArray rows = rowsOf(found);
      for (int i = 0; rows != null && i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        if (StringUtils.isBlank(row.optString(KEY_ID, ""))) {
          continue;
        }
        candidates.add(new Candidate(row.optString(KEY_ID), row.optString(KEY_NAME,
            row.optString("_identifier", row.optString(KEY_ID))),
            row.has("similarity_percent") ? row.optString("similarity_percent").replaceAll("%\\s*$",
                "") : null));
      }
    } catch (JSONException | ClassNotFoundException | RuntimeException e) {
      log.warn("OCR product similarity search failed: {}", e.getClass().getSimpleName());
    }
    return candidates;
  }

  /** The rows of a similarity answer, which carries them under {@code data} or {@code response.data}. */
  private static JSONArray rowsOf(JSONObject found) {
    if (found == null) {
      return null;
    }
    JSONArray data = found.optJSONArray(KEY_DATA);
    if (data != null) {
      return data;
    }
    JSONObject response = found.optJSONObject("response");
    return response == null ? null : response.optJSONArray(KEY_DATA);
  }

  @Override
  public TaxAnswer defaultTax(String locationId, String invoiceDate, BigDecimal rate) {
    try {
      Result result = PurchaseInvoiceLineHandler.resolveOwned(locationId, null, dateOf(invoiceDate),
          rate);
      if (result == null) {
        return new TaxAnswer(false, null, List.of());
      }
      List<Tax> candidates = new ArrayList<>();
      for (Determination candidate : result.candidates()) {
        candidates.add(tax(candidate));
      }
      return new TaxAnswer(true, result.chosen() == null ? null : tax(result.chosen()), candidates);
    } catch (RuntimeException e) {
      log.warn("OCR default tax lookup failed: {}", e.getClass().getSimpleName());
      return new TaxAnswer(false, null, List.of());
    }
  }

  private static Tax tax(Determination determination) {
    return new Tax(determination.taxId(), determination.taxName(), determination.rate());
  }

  /** Start of day in the server timezone, as {@code defaultTax} reads {@code invoiceDate}; today when blank. */
  private static Date dateOf(String isoDate) {
    if (StringUtils.isBlank(isoDate)) {
      return new Date();
    }
    try {
      return Date.from(LocalDate.parse(isoDate.trim()).atStartOfDay(ZoneId.systemDefault())
          .toInstant());
    } catch (DateTimeParseException e) {
      return new Date();
    }
  }

  @Override
  public String organizationTaxId() {
    try {
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      OrganizationInformation info = OBDal.getInstance().get(OrganizationInformation.class,
          orgId);
      return info == null ? null : info.getTaxID();
    } catch (RuntimeException e) {
      log.warn("OCR organization tax id lookup failed: {}", e.getClass().getSimpleName());
      return null;
    }
  }

  private static List<JSONObject> selectorItems(String column, String search, int limit,
      Map<String, String> context) {
    List<JSONObject> out = new ArrayList<>();
    NeoResponse response = selector(column, search, limit, context);
    JSONArray items = response == null || response.getBody() == null ? null
        : response.getBody().optJSONArray("items");
    for (int i = 0; items != null && i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null && !StringUtils.isBlank(item.optString(KEY_ID, ""))) {
        out.add(item);
      }
    }
    return out;
  }

  /**
   * The invoice header's own selector, or {@code null} when it cannot be asked or answers a failure.
   * Every failure is logged with its kind only (spec missing, HTTP status, exception class): never
   * document text, names or ids.
   */
  private static NeoResponse selector(String column, String search, int limit,
      Map<String, String> context) {
    try {
      SFSpec spec = NeoServletSupport.findSpec(SPEC);
      if (spec == null) {
        log.warn("OCR selector {} not asked: spec {} not found", column, SPEC);
        return null;
      }
      NeoResponse response = NeoSelectorService.querySelector(spec.getId(), HEADER, column, search,
          limit, 0, context);
      if (response.getHttpStatus() >= 400) {
        log.warn("OCR selector {} answered HTTP {}", column, response.getHttpStatus());
        return null;
      }
      return response;
    } catch (RuntimeException e) {
      log.warn("OCR selector {} lookup failed: {}", column, e.getClass().getSimpleName());
      return null;
    }
  }

  private static String labelOf(JSONObject item) {
    for (String key : new String[] { KEY_NAME, "label", "_identifier" }) {
      String value = item.optString(key, "");
      if (!value.isEmpty()) {
        return value;
      }
    }
    return item.optString(KEY_ID);
  }
}
