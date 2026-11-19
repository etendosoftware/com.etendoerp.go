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

  private ReceiverTaxIdCheck() {
  }

  /** See {@link TaxIdNormalizer#clean}. */
  static String clean(String value) {
    return TaxIdNormalizer.clean(value);
  }

  /** See {@link TaxIdNormalizer#idsMatch}. */
  static boolean idsMatch(String a, String b) {
    return TaxIdNormalizer.idsMatch(a, b);
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
