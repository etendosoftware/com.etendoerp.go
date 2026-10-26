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

import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * Compares the tax id the extraction read as the invoice RECEIVER with the active organization's
 * (ETP-5715) — a port of the application's {@code receiverTaxIdCheck.js}, rule for rule.
 *
 * <p>No check-digit validation; leading zeros are kept; a known VAT country prefix is ignored on
 * either side (so {@code ES-B81639719} matches {@code B81639719}), while the letters of an id that
 * only look like a prefix (the X/Y/Z of an NIE, the letter of a CIF) are never stripped.</p>
 */
public final class ReceiverTaxIdCheck {

  /** Outcome of the comparison. */
  public enum Status {
    /** Same id after cleaning. */
    MATCH("match"),
    /** Both known and different. */
    MISMATCH("mismatch"),
    /** The document prints no receiver id. */
    ABSENT("absent"),
    /** The organization has no tax id to compare with. */
    NO_ORG_TAX_ID("no-org-tax-id"),
    /** The "receiver" id equals the issuer's: the two blocks were confused, nothing to conclude. */
    SAME_AS_ISSUER("same-as-issuer");

    private final String code;

    Status(String code) {
      this.code = code;
    }

    /**
     * Returns the wire code.
     *
     * @return the code this status is reported under
     */
    public String code() {
      return code;
    }
  }

  /** The status plus the two ids as written. */
  public static final class Result {
    private final Status status;
    private final String receiverTaxId;
    private final String orgTaxId;

    Result(Status status, String receiverTaxId, String orgTaxId) {
      this.status = status;
      this.receiverTaxId = receiverTaxId;
      this.orgTaxId = orgTaxId;
    }

    /** Returns the status. */
    public Status getStatus() {
      return status;
    }

    /** Returns the receiver id as read from the document (trimmed). */
    public String getReceiverTaxId() {
      return receiverTaxId;
    }

    /** Returns the organization's tax id (trimmed). */
    public String getOrgTaxId() {
      return orgTaxId;
    }
  }

  /** Leading two-letter prefixes that are real VAT country prefixes. GR is an alias of EL. */
  private static final Set<String> VAT_PREFIXES = Set.of("AT", "BE", "BG", "CY", "CZ", "DE", "DK",
      "EE", "EL", "ES", "FI", "FR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL",
      "PT", "RO", "SE", "SI", "SK", "XI", "GB");

  private ReceiverTaxIdCheck() {
  }

  /**
   * Uppercase, keeping only letters A-Z and digits; blank and placeholder values become empty.
   *
   * @param value the id as written
   * @return the cleaned id
   */
  static String clean(String value) {
    return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  /**
   * The id without a known VAT country prefix, only when something remains after it.
   *
   * @param clean an id already cleaned
   * @return the id without its prefix
   */
  static String stripVatPrefix(String clean) {
    String head = clean.length() >= 2 ? clean.substring(0, 2) : clean;
    String prefix = "GR".equals(head) ? "EL" : head;
    return clean.length() > 2 && VAT_PREFIXES.contains(prefix) ? clean.substring(2) : clean;
  }

  /**
   * Whether two ids are the same once cleaned, with or without a country prefix.
   *
   * @param a first id as written
   * @param b second id as written
   * @return {@code true} when they match
   */
  static boolean idsMatch(String a, String b) {
    String ca = clean(a);
    String cb = clean(b);
    if (ca.isEmpty() || cb.isEmpty()) {
      return false;
    }
    return ca.equals(cb) || stripVatPrefix(ca).equals(stripVatPrefix(cb));
  }

  /**
   * Compares the receiver of an extraction with the organization.
   *
   * @param extraction the (sanitized) payload: {@code receiver}, {@code issuer}, legacy {@code tax_id}
   * @param orgTaxId   the active organization's tax id, may be {@code null}
   * @return the status and both ids
   */
  public static Result check(JSONObject extraction, String orgTaxId) {
    String receiverRaw = taxIdOf(extraction, "receiver");
    String orgRaw = StringUtils.trimToEmpty(orgTaxId);
    if (clean(receiverRaw).isEmpty()) {
      return new Result(Status.ABSENT, receiverRaw, orgRaw);
    }
    String issuerRaw = taxIdOf(extraction, "issuer");
    if (issuerRaw.isEmpty() && extraction != null) {
      issuerRaw = OcrJson.str(extraction, "tax_id");
    }
    if (idsMatch(receiverRaw, issuerRaw)) {
      return new Result(Status.SAME_AS_ISSUER, receiverRaw, orgRaw);
    }
    if (clean(orgRaw).isEmpty()) {
      return new Result(Status.NO_ORG_TAX_ID, receiverRaw, orgRaw);
    }
    return new Result(idsMatch(receiverRaw, orgRaw) ? Status.MATCH : Status.MISMATCH, receiverRaw,
        orgRaw);
  }

  private static String taxIdOf(JSONObject extraction, String party) {
    JSONObject block = extraction == null ? null : extraction.optJSONObject(party);
    return OcrJson.str(block, "tax_id_raw");
  }
}
