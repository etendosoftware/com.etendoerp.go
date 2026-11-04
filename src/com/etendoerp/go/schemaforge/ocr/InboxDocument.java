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

/**
 * The shape of the {@code CURRENT_DATA} document of the invoice inbox (ETP-6048): header, lines and
 * the states of their matching, as one JSON object. These names are the contract between the worker
 * that builds it ({@link InboxMatcher}), the validator and the review panel that edits it later.
 *
 * <p>Everything read from the document is untrusted text kept as data; the document carries codes
 * (states, {@code missing}, {@code notices}), never sentences, and the UI translates them.</p>
 */
public final class InboxDocument {

  /** Version of the document layout. */
  public static final int VERSION = 1;

  public static final String K_VERSION = "version";
  public static final String K_HEADER = "header";
  public static final String K_LINES = "lines";
  public static final String K_VALIDATION = "validation";
  public static final String K_NOTICES = "notices";

  public static final String K_VENDOR = "vendor";
  public static final String K_STATE = "state";
  public static final String K_MATCHED_BY = "matchedBy";
  public static final String K_ID = "id";
  public static final String K_NAME = "name";
  public static final String K_CANDIDATES = "candidates";
  public static final String K_EXTRACTED = "extracted";
  public static final String K_ADDRESS_STATE = "addressState";
  public static final String K_LOCATION_ID = "locationId";
  public static final String K_TAX_ID = "taxId";

  public static final String K_DOCUMENT_NO = "documentNo";
  public static final String K_INVOICE_DATE = "invoiceDate";
  public static final String K_INVOICE_DATE_RAW = "invoiceDateRaw";
  public static final String K_TOTAL_AMOUNT = "totalAmount";
  public static final String K_TOTAL_SOURCE = "totalSource";
  public static final String K_DUPLICATE = "duplicate";
  public static final String K_INVOICES = "invoices";
  public static final String K_INBOX = "inbox";
  public static final String K_RECEIVER = "receiver";
  public static final String K_CHECK = "check";
  public static final String K_ORG_TAX_ID = "orgTaxId";

  public static final String K_INDEX = "index";
  public static final String K_DESCRIPTION = "description";
  public static final String K_QUANTITY = "quantity";
  public static final String K_UNIT_PRICE = "unitPrice";
  public static final String K_TAX_RATE = "taxRate";
  public static final String K_TAX_LABEL = "taxLabel";
  public static final String K_PRODUCT = "product";
  public static final String K_TAX = "tax";
  public static final String K_SIMILARITY = "similarity";
  public static final String K_RATE = "rate";

  public static final String K_VALID = "valid";
  public static final String K_MISSING = "missing";

  /** Matching state: matched with certainty to an existing record. */
  public static final String S_LINKED = "linked";
  /** Matching state: several or doubtful records, the user decides. */
  public static final String S_AMBIGUOUS = "ambiguous";
  /** Matching state: nothing matches, a record would be created. */
  public static final String S_NEW = "new";
  /** Matching state: nothing to match with (no name, no id). */
  public static final String S_NONE = "none";

  /** Tax state: a tax of the printed rate was found for the vendor's address. */
  public static final String T_RESOLVED = "resolved";
  /** Tax state: depends on a vendor address that is not known yet. */
  public static final String T_PENDING_VENDOR = "pending_vendor";
  /** Tax state: the address is known but no purchase tax has the rate. */
  public static final String T_NO_TAX_AT_RATE = "no_tax_at_rate";
  /** Tax state: the lookup could not be made. */
  public static final String T_UNKNOWN = "unknown";

  /** Duplicate state: no existing document with the same vendor and number. */
  public static final String D_NONE = "none";
  /** Duplicate state: at least one existing document. */
  public static final String D_FOUND = "found";
  /** Duplicate state: the lookup could not be made. */
  public static final String D_UNKNOWN = "unknown";

  /** Address state: the matched vendor has an address. */
  public static final String A_PRESENT = "PRESENT";
  /** Address state: the matched vendor has no address. */
  public static final String A_MISSING = "MISSING";
  /** Address state: no vendor matched, or the lookup could not be made. */
  public static final String A_UNKNOWN = "UNKNOWN";

  /** Source of the total: printed on the document. */
  public static final String TOTAL_PRINTED = "printed";
  /** Source of the total: computed from the lines. */
  public static final String TOTAL_LINES = "lines";

  /** Most characters of a document number the target column holds. */
  public static final int MAX_DOCUMENT_NO = 20;

  private InboxDocument() {
  }
}
