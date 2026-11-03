/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.ocr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * A scripted {@link OcrLookups} for the inbox matcher tests: answers what a test configures and
 * records what it was asked, so a test can assert both the rule applied and the lookups it did not
 * need to make. Test support, not a test.
 */
final class InboxFakeLookups implements OcrLookups {

  /** Vendors by the cleaned tax id they are asked with. */
  final Map<String, List<Party>> byTaxId = new HashMap<>();
  /** Vendors the name search returns, whatever the text. */
  List<Party> byName = new ArrayList<>();
  /** The tax id stored for each vendor id. */
  final Map<String, String> storedTaxIds = new HashMap<>();
  Address address = new Address(AddressState.PRESENT, "LOC1");
  Duplicates duplicates = new Duplicates(true, List.of());
  /** One list of candidates per line description, in line order. */
  List<List<Candidate>> products = new ArrayList<>();
  /** The tax answer for each requested rate (key: plain string of the rate, "" for no rate). */
  final Map<String, TaxAnswer> taxByRate = new HashMap<>();
  TaxAnswer defaultTaxAnswer = new TaxAnswer(true, null, List.of());
  String orgTaxId = "A28000727";

  final List<String> taxIdSearches = new ArrayList<>();
  final List<String> nameSearches = new ArrayList<>();
  final List<String> addressAsks = new ArrayList<>();
  final List<String> duplicateAsks = new ArrayList<>();
  final List<List<String>> productAsks = new ArrayList<>();
  final List<String> taxAsks = new ArrayList<>();
  final List<String> taxDates = new ArrayList<>();
  int orgTaxIdAsks;

  @Override
  public List<Party> searchVendors(String name, int limit) {
    nameSearches.add(name);
    return byName;
  }

  @Override
  public List<Party> vendorsByTaxId(String cleanTaxId, int limit) {
    taxIdSearches.add(cleanTaxId);
    return byTaxId.getOrDefault(cleanTaxId, List.of());
  }

  @Override
  public String vendorTaxId(String vendorId) {
    return storedTaxIds.getOrDefault(vendorId, "");
  }

  @Override
  public Optional<Party> vendorById(String id) {
    return Optional.empty();
  }

  @Override
  public Address vendorAddress(String vendorId) {
    addressAsks.add(vendorId);
    return address;
  }

  @Override
  public Duplicates duplicates(String vendorId, String documentNo) {
    duplicateAsks.add(vendorId + "|" + documentNo);
    return duplicates;
  }

  @Override
  public List<List<Candidate>> productCandidates(List<String> descriptions) {
    productAsks.add(descriptions);
    return products;
  }

  @Override
  public TaxAnswer defaultTax(String locationId, String invoiceDate, BigDecimal rate) {
    String key = rate == null ? "" : rate.stripTrailingZeros().toPlainString();
    taxAsks.add(locationId + "|" + key);
    taxDates.add(invoiceDate);
    return taxByRate.getOrDefault(key, defaultTaxAnswer);
  }

  @Override
  public String organizationTaxId() {
    orgTaxIdAsks++;
    return orgTaxId;
  }
}
