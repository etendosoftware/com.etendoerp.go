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
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MAX_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_DUPLICATE_IN_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.TaxIdNormalizer;

/**
 * The in-inbox duplicate check (ETP-6048 D6, extracted for ETP-6050 D11 with no change of
 * behaviour): other live inbox rows with the same issuer and document number. The worker applies it
 * once after matching; the review's {@code apply} applies it again when the document number or the
 * vendor of a document changes, so both answer the very same.
 */
final class InvoiceInboxDuplicates {

  /** Most rows listed. */
  static final int MAX_RESULTS = 50;
  /** Shortest cleaned tax id worth identifying an issuer by (as the vendor match). */
  private static final int MIN_ISSUER_TAX_ID = 5;

  private InvoiceInboxDuplicates() {
  }

  /**
   * Other live inbox rows with the same issuer and document number: a warning in the document
   * (ETP-6048 D6). Best-effort for documents processed at the same time.
   *
   * @param row      the row being processed
   * @param document the {@code CURRENT_DATA} object; its {@code duplicate.inbox} and {@code notices}
   *                 are written
   * @throws JSONException when the document has no header or duplicate block
   */
  static void addInboxDuplicates(IncomingDocument row, JSONObject document)
      throws JSONException {
    JSONObject header = document.getJSONObject(K_HEADER);
    JSONArray found = find(row, header);
    header.getJSONObject(K_DUPLICATE).put(K_INBOX, found);
    if (found.length() > 0) {
      document.getJSONArray(K_NOTICES).put(N_DUPLICATE_IN_INBOX);
    }
  }

  /**
   * The live inbox rows (other than {@code row}, not discarded) with the document's issuer and
   * number.
   *
   * @param row    the row the document belongs to (excluded from the answer)
   * @param header the document's {@code header}
   * @return {@code [{id, fileName}]}, at most {@value #MAX_RESULTS}; empty when the number is blank
   *         or longer than the column, or the issuer cannot be identified
   * @throws JSONException when an object cannot be built
   */
  static JSONArray find(IncomingDocument row, JSONObject header) throws JSONException {
    return find(row, header, null);
  }

  /**
   * As {@link #find(IncomingDocument, JSONObject)}, optionally limited to some organizations.
   *
   * @param row           the row the document belongs to (excluded from the answer)
   * @param header        the document's {@code header}
   * @param organizations the organizations whose rows may be listed (the user-facing path passes
   *                      the ones the role can read, so no id or file name of another organization
   *                      leaks); {@code null} for no restriction (the worker, which acts for the
   *                      uploader and has always searched the whole client)
   * @return {@code [{id, fileName}]}, as the two-argument form
   * @throws JSONException when an object cannot be built
   */
  static JSONArray find(IncomingDocument row, JSONObject header,
      java.util.Collection<String> organizations) throws JSONException {
    String documentNo = header.optString(K_DOCUMENT_NO, "").trim();
    JSONArray found = new JSONArray();
    if (!documentNo.isEmpty() && documentNo.length() <= MAX_DOCUMENT_NO) {
      OBQuery<IncomingDocument> query = issuerQuery(header.optJSONObject(K_VENDOR),
          organizations != null);
      if (query != null) {
        if (organizations != null) {
          query.setNamedParameter("organizations", organizations);
        }
        query.setNamedParameter("client", row.getClient().getId());
        query.setNamedParameter("reference", documentNo.toUpperCase(java.util.Locale.ROOT));
        query.setNamedParameter("self", row.getId());
        query.setMaxResult(MAX_RESULTS);
        for (IncomingDocument other : query.list()) {
          found.put(new JSONObject().put(K_ID, other.getId()).put("fileName",
              other.getFileName()));
        }
      }
    }
    return found;
  }

  /**
   * The query for the rows of the same issuer, still missing the client, reference and self
   * parameters: by vendor when it is linked, else by the extracted tax id (the common case of a
   * vendor that does not exist yet). {@code null} when the issuer cannot be identified.
   */
  private static OBQuery<IncomingDocument> issuerQuery(JSONObject vendor,
      boolean byOrganizations) {
    String common = " and upper(trim(d.orderReference)) = :reference and d.status <> 'DISCARDED'"
        + " and d.id <> :self" + (byOrganizations ? " and d.organization.id in (:organizations)" : "");
    if (vendor != null && S_LINKED.equals(vendor.optString(K_STATE))) {
      OBQuery<IncomingDocument> query = OBDal.getInstance().createQuery(IncomingDocument.class,
          "as d where d.client.id = :client and d.businessPartner.id = :vendor" + common);
      query.setNamedParameter("vendor", vendor.optString(K_ID));
      return query;
    }
    JSONObject extracted = vendor == null ? null : vendor.optJSONObject(K_EXTRACTED);
    String taxId = TaxIdNormalizer.clean(extracted == null ? "" : extracted.optString(K_TAX_ID));
    if (taxId.length() < MIN_ISSUER_TAX_ID) {
      return null;
    }
    // The stored value is the raw text read from the document: compare it the way
    // PurchaseInvoiceOcrLookups.vendorsByTaxId does, cleaned and in every prefix spelling.
    OBQuery<IncomingDocument> query = OBDal.getInstance().createQuery(IncomingDocument.class,
        "as d where d.client.id = :client and upper(replace(replace(replace(replace("
            + "d.extractedTaxID, '-', ''), ' ', ''), '.', ''), '/', '')) in (:spellings)" + common);
    query.setNamedParameter("spellings", TaxIdNormalizer.variants(taxId));
    return query;
  }
}
