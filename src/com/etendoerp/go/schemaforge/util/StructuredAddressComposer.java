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
package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Composes {@code C_Location.Address1}/{@code Address2} from the structured SEPA address fields
 * (ETP-5806): Street Name, Building Number and Building Name.
 *
 * <p><b>This ports {@code LocationSEPA.java::fillAddressLines} EXACTLY</b> (Classic,
 * {@code org.openbravo.sepa.iso20022}, branch {@code epic/ETP-3504}, lines 146-180) — not the
 * simplified "Line1=Street+Number / Line2=BuildingName" rule described in the ticket text or the
 * design mockup. The acceptance criterion "an address created in Etendo Go looks the same in
 * Etendo Classic, and vice versa" requires real parity: the simplified rule would diverge from
 * Classic whenever the building name fits on the same 60-character line as street+number.
 *
 * <p>Used by both {@code ContactsLocationAddressHandler} and {@code WarehouseLocationHandler} so
 * the two entities that wrap a {@code C_Location} compute the exact same lines, on every channel
 * (REST and MCP reach the same handler code, so there is nothing channel-specific to port twice).
 */
public final class StructuredAddressComposer {

  /** Length of {@code C_Location.Address1}/{@code Address2} — NOT the 70 of EM_OBSEPA_StrtNm. */
  private static final int ADDRESS_LINE_MAX_LENGTH = 60;

  private StructuredAddressComposer() {
  }

  /** The two computed address lines. Either may be {@code null} when empty. */
  public static final class ComposedAddress {
    private final String address1;
    private final String address2;

    private ComposedAddress(String address1, String address2) {
      this.address1 = address1;
      this.address2 = address2;
    }

    public String getAddress1() {
      return address1;
    }

    public String getAddress2() {
      return address2;
    }
  }

  /**
   * Composes Address1/Address2 from the structured fields, replicating
   * {@code LocationSEPA.fillAddressLines} line for line.
   *
   * <p>Separators: {@code ", "} between street and number; {@code " - "} (space-dash-space)
   * between street+number and building name in Line 1. {@code append} only inserts a separator
   * when both sides are non-blank.
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber Building Number ({@code EM_OBSEPA_BldgNb}), may be {@code null}/blank
   * @param buildingName   Building Name ({@code EM_OBSEPA_BldgNm}), may be {@code null}/blank
   * @return the composed lines; both {@code null} when all three inputs are blank
   */
  public static ComposedAddress composeAddressLines(String streetName, String buildingNumber,
      String buildingName) {
    String street = StringUtils.trimToEmpty(streetName);
    String number = StringUtils.trimToEmpty(buildingNumber);
    String name = StringUtils.trimToEmpty(buildingName);

    String streetAndNumber = append(street, ", ", number);
    String wholeAddress = append(streetAndNumber, " - ", name);

    if (wholeAddress.length() <= ADDRESS_LINE_MAX_LENGTH) {
      // Everything fits on one line.
      return new ComposedAddress(emptyToNull(wholeAddress), null);
    }
    if (streetAndNumber.length() <= ADDRESS_LINE_MAX_LENGTH) {
      return new ComposedAddress(emptyToNull(streetAndNumber),
          emptyToNull(StringUtils.left(name, ADDRESS_LINE_MAX_LENGTH)));
    }
    // Only a street longer than the line itself gets truncated here.
    return new ComposedAddress(
        emptyToNull(StringUtils.left(street, ADDRESS_LINE_MAX_LENGTH)),
        emptyToNull(StringUtils.left(append(number, " - ", name), ADDRESS_LINE_MAX_LENGTH)));
  }

  /**
   * The structured-address mandatory fields (ETP-5806 product decision: Street, Number, City and
   * Country are mandatory in Etendo Go, stricter than Classic's optional SEPA fields) that are
   * missing from the given values.
   *
   * @return the missing field names (payload keys), in a stable order; empty when none are missing
   */
  public static List<String> missingMandatoryFields(String streetName, String buildingNumber,
      String cityName, String countryId) {
    List<String> missing = new ArrayList<>();
    if (StringUtils.isBlank(streetName)) {
      missing.add("streetName");
    }
    if (StringUtils.isBlank(buildingNumber)) {
      missing.add("buildingNumber");
    }
    if (StringUtils.isBlank(cityName)) {
      missing.add("cityName");
    }
    if (StringUtils.isBlank(countryId)) {
      missing.add("country");
    }
    return missing;
  }

  private static String append(String base, String separator, String part) {
    if (base.isEmpty()) {
      return part;
    }
    if (part.isEmpty()) {
      return base;
    }
    return base + separator + part;
  }

  private static String emptyToNull(String value) {
    return (value == null || value.isEmpty()) ? null : value;
  }
}
