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

package com.etendoerp.go.schemaforge.ocr;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the review of an extracted document asks the server (ETP-5715): the vendor, its address, the
 * documents already created from the same supplier number, the products and the taxes. One
 * implementation per document type, bound by the {@code resolver} name of its {@link OcrDocType}.
 *
 * <p>Every method is total: a failed lookup answers "nothing found" or the explicit
 * {@link AddressState#UNKNOWN} / unknown flags, never an exception, because the review must still
 * answer. Nothing here creates or changes anything.</p>
 */
public interface OcrLookups {

  /** A business partner as the selector lists it. */
  final class Party {
    private final String id;
    private final String name;

    /**
     * Describes a partner.
     *
     * @param id   the partner id
     * @param name the name the selector shows
     */
    public Party(String id, String name) {
      this.id = id;
      this.name = name;
    }

    /**
     * Returns the partner id.
     *
     * @return the partner id
     */
    public String id() {
      return id;
    }

    /**
     * Returns the partner name.
     *
     * @return the partner name
     */
    public String name() {
      return name;
    }
  }

  /** What the review panel shows of a vendor next to its name (ETP-6050). */
  final class VendorDetails {
    private final String name;
    private final String taxId;
    private final String city;

    /**
     * Describes a vendor for display.
     *
     * @param name  the partner name
     * @param taxId the tax id as stored, may be empty
     * @param city  the city of its first address, empty when it has none
     */
    public VendorDetails(String name, String taxId, String city) {
      this.name = name;
      this.taxId = taxId;
      this.city = city;
    }

    /**
     * Returns the name.
     *
     * @return the partner name
     */
    public String name() {
      return name;
    }

    /**
     * Returns the tax id.
     *
     * @return the tax id as stored, may be empty
     */
    public String taxId() {
      return taxId;
    }

    /**
     * Returns the city.
     *
     * @return the city of its first address, empty when it has none
     */
    public String city() {
      return city;
    }
  }

  /** What the review panel shows of a product next to its name (ETP-6050). */
  final class ProductDetails {
    private final String name;
    private final String searchKey;
    private final String category;
    private final String uom;

    /**
     * Describes a product for display.
     *
     * @param name      the product name
     * @param searchKey the product search key (its code)
     * @param category  the product category name, empty when none
     * @param uom       the unit of measure name, empty when none
     */
    public ProductDetails(String name, String searchKey, String category, String uom) {
      this.name = name;
      this.searchKey = searchKey;
      this.category = category;
      this.uom = uom;
    }

    /**
     * Returns the name.
     *
     * @return the product name
     */
    public String name() {
      return name;
    }

    /**
     * Returns the search key.
     *
     * @return the product code
     */
    public String searchKey() {
      return searchKey;
    }

    /**
     * Returns the category.
     *
     * @return the category name, empty when none
     */
    public String category() {
      return category;
    }

    /**
     * Returns the unit of measure.
     *
     * @return the unit name, empty when none
     */
    public String uom() {
      return uom;
    }
  }

  /** The currency a document is expressed in, with the decimals its amounts are rounded to. */
  final class DocCurrency {
    private final String id;
    private final String isoCode;
    private final String symbol;
    private final int scale;

    /**
     * Describes a currency.
     *
     * @param id      the currency id
     * @param isoCode the ISO code
     * @param symbol  the symbol, may be {@code null}
     * @param scale   the standard precision ({@code C_Currency.StdPrecision})
     */
    public DocCurrency(String id, String isoCode, String symbol, int scale) {
      this.id = id;
      this.isoCode = isoCode;
      this.symbol = symbol;
      this.scale = scale;
    }

    /**
     * Returns the currency id.
     *
     * @return the currency id
     */
    public String id() {
      return id;
    }

    /**
     * Returns the ISO code.
     *
     * @return the ISO code
     */
    public String isoCode() {
      return isoCode;
    }

    /**
     * Returns the symbol, may be {@code null}.
     *
     * @return the symbol, may be {@code null}
     */
    public String symbol() {
      return symbol;
    }

    /**
     * Returns the decimals of the amounts in this currency.
     *
     * @return the decimals of the amounts in this currency
     */
    public int scale() {
      return scale;
    }
  }

  /** Whether a partner has an address, told apart from "could not ask". */
  enum AddressState { PRESENT, MISSING, UNKNOWN }

  /** A partner's first address. */
  final class Address {
    private final AddressState state;
    private final String locationId;

    /**
     * Describes the address lookup.
     *
     * @param state      whether an address exists
     * @param locationId the first address, {@code null} unless {@code PRESENT}
     */
    public Address(AddressState state, String locationId) {
      this.state = state;
      this.locationId = locationId;
    }

    /**
     * Returns the address state.
     *
     * @return {@code PRESENT}, {@code MISSING}, or {@code UNKNOWN} when it could not be asked
     */
    public AddressState state() {
      return state;
    }

    /**
     * Returns the location id.
     *
     * @return the first address' location id, {@code null} unless {@code PRESENT}
     */
    public String locationId() {
      return locationId;
    }
  }

  /** An existing document carrying the same supplier number. */
  final class Duplicate {
    private final String id;
    private final String documentNo;

    /**
     * Describes an existing document.
     *
     * @param id         the document id
     * @param documentNo its own document number, may be {@code null}
     */
    public Duplicate(String id, String documentNo) {
      this.id = id;
      this.documentNo = documentNo;
    }

    /**
     * Returns the document id.
     *
     * @return the id of the existing document
     */
    public String id() {
      return id;
    }

    /**
     * Returns the document number.
     *
     * @return the document's own number, may be {@code null}
     */
    public String documentNo() {
      return documentNo;
    }
  }

  /** The documents found with the same supplier number; {@code known} is false when the lookup failed. */
  final class Duplicates {
    private final boolean known;
    private final List<Duplicate> documents;

    /**
     * Describes the duplicate lookup.
     *
     * @param known     whether the lookup could be made
     * @param documents the documents found
     */
    public Duplicates(boolean known, List<Duplicate> documents) {
      this.known = known;
      this.documents = documents;
    }

    /**
     * Returns whether the lookup could be made.
     *
     * @return {@code false} when the lookup failed and the result is unknown
     */
    public boolean known() {
      return known;
    }

    /**
     * Returns the documents found.
     *
     * @return the existing documents with the same supplier number
     */
    public List<Duplicate> documents() {
      return documents;
    }
  }

  /**
   * A product the vendor's completed purchase invoice lines with the same description were booked
   * to (ETP-6364), with how many lines.
   */
  final class HistoryProduct {
    private final String id;
    private final String name;
    private final int count;

    /**
     * Describes a product of the vendor's history.
     *
     * @param id    the product id
     * @param name  the product name
     * @param count the number of lines booked to it
     */
    public HistoryProduct(String id, String name, int count) {
      this.id = id;
      this.name = name;
      this.count = count;
    }

    /**
     * Returns the product id.
     *
     * @return the product id
     */
    public String id() {
      return id;
    }

    /**
     * Returns the product name.
     *
     * @return the product name
     */
    public String name() {
      return name;
    }

    /**
     * Returns how many lines were booked to the product.
     *
     * @return the number of lines
     */
    public int count() {
      return count;
    }
  }

  /** A product the similarity search proposes for a line. */
  final class Candidate {
    private final String id;
    private final String name;
    private final String similarity;
    private final String textSimilarity;
    private final String matchType;

    /**
     * Describes a candidate from a search that reports only the score.
     *
     * @param id         the product id
     * @param name       the product name
     * @param similarity the similarity as the search reports it, may be {@code null}
     */
    public Candidate(String id, String name, String similarity) {
      this(id, name, similarity, null, null);
    }

    /**
     * Describes a candidate.
     *
     * @param id             the product id
     * @param name           the product name
     * @param similarity     the score as the search reports it, may be {@code null}
     * @param textSimilarity the whole-string similarity, may be {@code null}
     * @param matchType      "exact", "normalized" or "fuzzy" (ETP-5895), may be {@code null}
     */
    public Candidate(String id, String name, String similarity, String textSimilarity,
        String matchType) {
      this.id = id;
      this.name = name;
      this.similarity = similarity;
      this.textSimilarity = textSimilarity;
      this.matchType = matchType;
    }

    /**
     * Returns the product id.
     *
     * @return the product id
     */
    public String id() {
      return id;
    }

    /**
     * Returns the product name.
     *
     * @return the product name
     */
    public String name() {
      return name;
    }

    /**
     * Returns the similarity.
     *
     * @return the similarity as the search reports it, may be {@code null}
     */
    public String similarity() {
      return similarity;
    }

    /**
     * Returns the whole-string similarity.
     *
     * @return the whole-string similarity, {@code null} when the search did not report it
     */
    public String textSimilarity() {
      return textSimilarity;
    }

    /**
     * Returns how the product's identifier relates to the description.
     *
     * @return "exact", "normalized" or "fuzzy", {@code null} when the search did not report it
     */
    public String matchType() {
      return matchType;
    }
  }

  /** A tax the server proposes. */
  final class Tax {
    private final String id;
    private final String name;
    private final BigDecimal rate;

    /**
     * Describes a tax.
     *
     * @param id   the tax id
     * @param name the tax name
     * @param rate the rate in percent, may be {@code null}
     */
    public Tax(String id, String name, BigDecimal rate) {
      this.id = id;
      this.name = name;
      this.rate = rate;
    }

    /**
     * Returns the tax id.
     *
     * @return the tax id
     */
    public String id() {
      return id;
    }

    /**
     * Returns the tax name.
     *
     * @return the tax name
     */
    public String name() {
      return name;
    }

    /**
     * Returns the tax rate.
     *
     * @return the rate in percent, may be {@code null}
     */
    public BigDecimal rate() {
      return rate;
    }
  }

  /** The default tax of a rate: the tax chosen (or none), the alternatives, and whether it could be asked. */
  final class TaxAnswer {
    private final boolean known;
    private final Tax chosen;
    private final List<Tax> candidates;

    /**
     * Describes the tax lookup.
     *
     * @param known      whether the lookup could be made
     * @param chosen     the tax to default to, {@code null} when no tax has that rate
     * @param candidates other taxes of the same rate
     */
    public TaxAnswer(boolean known, Tax chosen, List<Tax> candidates) {
      this.known = known;
      this.chosen = chosen;
      this.candidates = candidates;
    }

    /**
     * Returns whether the lookup could be made.
     *
     * @return {@code false} when the lookup failed and the answer is unknown
     */
    public boolean known() {
      return known;
    }

    /**
     * Returns the chosen tax.
     *
     * @return the tax to default to, {@code null} when no tax has the rate
     */
    public Tax chosen() {
      return chosen;
    }

    /**
     * Returns the alternative taxes.
     *
     * @return the other taxes of the same rate
     */
    public List<Tax> candidates() {
      return candidates;
    }
  }

  /**
   * Vendors whose name contains the text, through the document's own vendor selector.
   *
   * @param name  the text to search
   * @param limit the page size
   * @return the rows, empty when none or the search failed
   */
  List<Party> searchVendors(String name, int limit);

  /**
   * Vendors whose tax id is the given one, with or without a VAT country prefix (ETP-6048).
   *
   * <p>Default: no tax id search, so an implementation that does not support it never matches.</p>
   *
   * @param cleanTaxId the id as {@link TaxIdNormalizer#clean} leaves it
   * @param limit      the most rows to return
   * @return the vendors, empty when none or the search failed
   */
  default List<Party> vendorsByTaxId(String cleanTaxId, int limit) {
    return List.of();
  }

  /**
   * The tax id stored for a vendor (ETP-6048), to tell two vendors of the same name apart.
   *
   * <p>Default: unknown, which reads as "no tax id on file".</p>
   *
   * @param vendorId the partner id
   * @return the id as stored, empty when it has none or cannot be read
   */
  default String vendorTaxId(String vendorId) {
    return "";
  }

  /**
   * A product the session can use, by id (ETP-6050).
   *
   * <p>Default: nothing is found, so an implementation that does not support it never resolves.</p>
   *
   * @param id the product id
   * @return the product, empty when it does not exist, is inactive or is not the tenant's
   */
  default Optional<Party> productById(String id) {
    return Optional.empty();
  }

  /**
   * A product category the session can use, by id (ETP-6050), to create a new product with it. The
   * name is the one of the session language.
   *
   * <p>Default: nothing is found, so an implementation that does not support it never resolves.</p>
   *
   * @param id the category id
   * @return the category, empty when it does not exist, is inactive or is not the tenant's
   */
  default Optional<Party> productCategoryById(String id) {
    return Optional.empty();
  }

  /**
   * A tax a line of the document can be given, by id (ETP-6050): the very rule of the document
   * line's tax selector (active, valid from the document date, for the document's side or both,
   * the tenant's or the system's).
   *
   * <p>Default: nothing is found, so an implementation that does not support it never resolves.</p>
   *
   * @param id          the tax id
   * @param invoiceDate the document date as {@code yyyy-MM-dd}, or {@code null} for today
   * @return the tax with its rate, empty when it does not exist or does not pass the rule
   */
  default Optional<Tax> taxById(String id, String invoiceDate) {
    return Optional.empty();
  }

  /**
   * The currency a document of this vendor is expressed in (ETP-6050): the vendor's purchase price
   * list currency, else the organization's, else the client's (what the purchase invoice header
   * ends up with). {@code null} vendor: the organization's.
   *
   * <p>Default: unknown, so the amounts are rounded to two decimals.</p>
   *
   * @param vendorId the linked vendor, may be {@code null}
   * @return the currency, empty when it cannot be established
   */
  default Optional<DocCurrency> documentCurrency(String vendorId) {
    return Optional.empty();
  }

  /**
   * As {@link #documentCurrency(String)}, with the organization of the document: the fallback after
   * the vendor's price list is THAT organization's currency (the one the invoice created from the
   * row will have), not the current one of the session.
   *
   * <p>Default: the one-argument answer.</p>
   *
   * @param vendorId       the linked vendor, may be {@code null}
   * @param organizationId the organization of the inbox row, {@code null} for the session's
   * @return the currency, empty when it cannot be established
   */
  default Optional<DocCurrency> documentCurrency(String vendorId, String organizationId) {
    return documentCurrency(vendorId);
  }

  /**
   * A currency by id, with its scale (ETP-6050).
   *
   * <p>Default: unknown.</p>
   *
   * @param currencyId the currency id
   * @return the currency, empty when it does not exist or cannot be read
   */
  default Optional<DocCurrency> currencyById(String currencyId) {
    return Optional.empty();
  }

  /**
   * The rounding mode of the tax of each id (ETP-6050): {@code D} (Document based amount by rate)
   * or {@code L} (Line based amount by rate), as {@code C_Tax.DocTaxAmount}.
   *
   * <p>Default: nothing known, which reads as {@code D}.</p>
   *
   * @param taxIds the tax ids
   * @return the mode by tax id, empty when none or the lookup failed
   */
  default Map<String, String> taxDocAmountModes(Collection<String> taxIds) {
    return Map.of();
  }

  /**
   * The names, in the session language, of the taxes with these ids (ETP-6050), for display. No
   * validity rule: a tax chosen yesterday keeps its name.
   *
   * <p>Default: nothing known. Ids that do not resolve for the session are absent from the map.</p>
   *
   * @param taxIds the tax ids
   * @return the name by tax id, empty when none or the lookup failed
   */
  default Map<String, String> taxNames(Collection<String> taxIds) {
    return Map.of();
  }

  /**
   * The unit of measure a line printed, resolved to one of the tenant's (ETP-6051): by symbol, EDI
   * code or name, ignoring case and diacritics, with a small alias table. Used only to pre-fill the
   * unit of a product that does not exist yet.
   *
   * @param printed the unit as printed on the line, may be {@code null}
   * @return the unit, empty when nothing matches or more than one unit does (never a guess)
   */
  default Optional<Party> uomByPrintedUnit(String printed) {
    return Optional.empty();
  }

  /**
   * The tenant's default unit of measure ({@code IsDefault}, the tenant's own first, then System's),
   * the one the product window preselects (ETP-6051).
   *
   * @return the unit, empty when none is flagged
   */
  default Optional<Party> defaultUom() {
    return Optional.empty();
  }

  /**
   * The tenant's default product category (ETP-6051): the one the product's category column
   * defaults to, the client's {@code IsDefault} non-summary category of the organization's own tree.
   *
   * @param organizationId the organization of the document (its own tree decides), {@code null} for
   *                       the session's current one
   * @return the category, empty when the tenant has none
   */
  default Optional<Party> defaultProductCategory(String organizationId) {
    return Optional.empty();
  }

  /**
   * A unit of measure the session can use, by id (ETP-6050), to create a new product with it. The
   * name is the one of the session language.
   *
   * <p>Default: nothing is found, so an implementation that does not support it never resolves.</p>
   *
   * @param id the unit id
   * @return the unit, empty when it does not exist, is inactive or is not the tenant's
   */
  default Optional<Party> uomById(String id) {
    return Optional.empty();
  }

  /**
   * Name, tax id and city of several vendors, in one query (ETP-6050), for the review panel.
   *
   * <p>Default: nothing known. Ids that do not resolve for the session are absent from the map.</p>
   *
   * @param vendorIds the partner ids
   * @return the details by partner id, empty when none or the lookup failed
   */
  default Map<String, VendorDetails> vendorDetails(Collection<String> vendorIds) {
    return Map.of();
  }

  /**
   * Name, search key, category and unit of several products, in one query (ETP-6050), for the
   * review panel.
   *
   * <p>Default: nothing known. Ids that do not resolve for the session are absent from the map.</p>
   *
   * @param productIds the product ids
   * @return the details by product id, empty when none or the lookup failed
   */
  default Map<String, ProductDetails> productDetails(Collection<String> productIds) {
    return Map.of();
  }

  /**
   * A vendor the session can use, by id.
   *
   * @param id the partner id
   * @return the partner, empty when it does not exist, is not a vendor or is not the tenant's
   */
  Optional<Party> vendorById(String id);

  /**
   * The vendor's first usable address, the one the document would take.
   *
   * @param vendorId the partner id
   * @return the state and the address
   */
  Address vendorAddress(String vendorId);

  /**
   * Documents of the vendor that already carry this supplier number (not voided).
   *
   * @param vendorId   the partner id
   * @param documentNo the supplier document number
   * @return the documents, with whether the lookup could be made
   */
  Duplicates duplicates(String vendorId, String documentNo);

  /**
   * Similar products for each description, best first.
   *
   * @param descriptions one per line, blank allowed
   * @return one list per description (empty when nothing is similar), same order
   */
  List<List<Candidate>> productCandidates(List<String> descriptions);

  /**
   * The products this vendor's completed purchase invoice lines with the same description (after
   * normalization) were booked to (ETP-6364), most frequent first.
   *
   * <p>Default: no history, one empty list per description.</p>
   *
   * @param vendorId     the resolved vendor, {@code null} when there is none yet (no query)
   * @param descriptions one per line, blank allowed (never matched)
   * @return one list per description, same order, empty when nothing or the lookup failed
   */
  default List<List<HistoryProduct>> productHistory(String vendorId, List<String> descriptions) {
    return Collections.nCopies(descriptions.size(), List.of());
  }

  /**
   * The tax a line should default to for a printed rate.
   *
   * @param locationId  the vendor's address
   * @param invoiceDate the date as {@code yyyy-MM-dd}, or {@code null} for today
   * @param rate        the printed percentage
   * @return the answer
   */
  TaxAnswer defaultTax(String locationId, String invoiceDate, BigDecimal rate);

  /**
   * The active organization's tax id.
   *
   * @return the id, {@code null} when it has none or cannot be read
   */
  String organizationTaxId();
}
