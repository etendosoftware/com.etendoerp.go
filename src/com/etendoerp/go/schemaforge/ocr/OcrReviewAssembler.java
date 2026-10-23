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

import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * Turns a sanitized extraction into the answer the agent works from (ETP-5715): what was detected,
 * what is blocking, and every decision the user still has to take — the same decisions the
 * application's review steps put in front of a person who uploads a supplier invoice.
 *
 * <p>The answer is built as a supervised hand-off, not as an instruction to create: the status says
 * {@code needs_decisions} while any decision blocks creating (a vendor not yet accepted, an address
 * missing, a line without a certain product), the document text lives only under {@code documentData}
 * (marked {@code untrusted}) and never in a message, and a {@code supervision} block restates the
 * rules. A receiver tax id that is not the organization's blocks everything else: nothing is looked
 * up for a document that is not addressed to the company.</p>
 */
public final class OcrReviewAssembler {

  private static final String KEY_CODE = "code";
  private static final String KEY_STATUS = "status";
  private static final String KEY_BLOCKING = "blocking";
  private static final String KEY_DOCUMENT_DATA = "documentData";
  private static final String KEY_UNTRUSTED = "untrusted";
  private static final String KEY_INVOICE_DATE = "invoice_date";
  private static final String KEY_ISSUER = "issuer";
  private static final String KEY_RECEIVER = "receiver";
  private static final String KEY_TAX_ID_LABEL = "taxIdLabel";
  private static final String KEY_TAX_ID_TYPE = "taxIdType";

  private final OcrDocType type;
  private final OcrLookups lookups;

  /**
   * Creates an assembler.
   *
   * @param type    the document type being reviewed
   * @param lookups the lookups of that type
   */
  public OcrReviewAssembler(OcrDocType type, OcrLookups lookups) {
    this.type = type;
    this.lookups = lookups;
  }

  /**
   * Builds the review.
   *
   * @param stagedDocumentId the staged document, echoed back
   * @param extraction       the sanitized extraction
   * @param chosenVendor     the vendor the user already chose, or {@code null}
   * @return the answer body
   * @throws JSONException when a body cannot be built
   */
  public JSONObject assemble(String stagedDocumentId, JSONObject extraction, Party chosenVendor)
      throws JSONException {
    JSONObject answer = new JSONObject().put("spec", type.getSpec())
        .put("stagedDocumentId", stagedDocumentId);
    List<JSONObject> notices = new ArrayList<>();
    ReceiverTaxIdCheck.Result receiver = checkReceiver(extraction);
    if (receiver.getStatus() == ReceiverTaxIdCheck.Status.MISMATCH) {
      return blocked(answer, extraction);
    }
    addReceiverNotice(receiver, notices);

    OcrVendorReview vendor = new OcrVendorReview(lookups);
    vendor.review(extraction, chosenVendor);
    String locationId = locationOf(vendor);
    OcrLineReview lines = reviewLines(extraction, locationId, chosenVendor != null);
    notices.addAll(vendor.notices());
    notices.addAll(lines.notices());

    List<JSONObject> decisions = new ArrayList<>(vendor.decisions());
    decisions.addAll(headerToggles(extraction));
    decisions.addAll(lines.decisions());
    JSONObject header = OcrBatchTemplate.header(type.getSpec(),
        chosenVendor == null ? null : chosenVendor.id(),
        chosenVendor == null ? null : locationId);
    return finish(answer, new JSONArray(decisions), notices,
        OcrBatchTemplate.wrap(header, lines.operations()))
        .put(KEY_DOCUMENT_DATA, documentData(extraction, lines.documentLines()));
  }

  /** The address the document would take: the effective vendor's, only when it has one. */
  private static String locationOf(OcrVendorReview vendor) {
    Address address = vendor.address();
    return vendor.effectiveVendor() != null && address.state() == AddressState.PRESENT
        ? address.locationId() : null;
  }

  private OcrLineReview reviewLines(JSONObject extraction, String locationId,
      boolean vendorChosen) throws JSONException {
    String date = OcrJson.str(extraction, KEY_INVOICE_DATE);
    OcrLineReview lines = new OcrLineReview(lookups, type.getSpec());
    JSONArray rawLines = extraction.optJSONArray(OcrDocType.LINES_KEY);
    lines.review(rawLines == null ? new JSONArray() : rawLines, locationId,
        OcrJson.filled(date) ? OcrJson.toIsoDate(date) : null, vendorChosen);
    return lines;
  }

  private ReceiverTaxIdCheck.Result checkReceiver(JSONObject extraction) {
    String receiverId = OcrJson.str(extraction.optJSONObject(KEY_RECEIVER), "tax_id_raw");
    String org = ReceiverTaxIdCheck.clean(receiverId).isEmpty() ? null
        : lookups.organizationTaxId();
    return ReceiverTaxIdCheck.check(extraction, org);
  }

  private static void addReceiverNotice(ReceiverTaxIdCheck.Result receiver,
      List<JSONObject> notices) throws JSONException {
    if (receiver.getStatus() == ReceiverTaxIdCheck.Status.ABSENT) {
      notices.add(OcrJson.notice("receiver_tax_id_absent", "The document prints no tax id for "
          + "the receiver, so it could not be checked that it is addressed to this company. "
          + "Tell the user."));
    } else if (receiver.getStatus() == ReceiverTaxIdCheck.Status.NO_ORG_TAX_ID) {
      notices.add(OcrJson.notice("org_tax_id_missing", "This company has no tax id configured, so "
          + "the receiver of the document could not be checked. Tell the user."));
    }
  }

  private JSONObject blocked(JSONObject answer, JSONObject extraction) throws JSONException {
    JSONObject data = new JSONObject().put(KEY_UNTRUSTED, true)
        .put(KEY_ISSUER, party(extraction.optJSONObject(KEY_ISSUER)))
        .put(KEY_RECEIVER, party(extraction.optJSONObject(KEY_RECEIVER)));
    return answer.put(KEY_STATUS, "blocked").put("readyToCreate", false)
        .put(KEY_BLOCKING, new JSONArray().put(new JSONObject()
            .put(KEY_CODE, "receiver_tax_id_mismatch")
            .put("message", "The tax id printed for the receiver is not this company's: the "
                + "document is not addressed to it. Nothing was looked up and nothing can be "
                + "created from it. Both ids are in documentData for the user to compare.")))
        .put(KEY_DOCUMENT_DATA, data).put("supervision", OcrSupervision.rules());
  }

  private static JSONObject finish(JSONObject answer, JSONArray decisions,
      List<JSONObject> notices, JSONObject template) throws JSONException {
    boolean blocking = false;
    for (int i = 0; i < decisions.length(); i++) {
      blocking = blocking || decisions.getJSONObject(i).optBoolean(KEY_BLOCKING, false);
    }
    return answer.put(KEY_STATUS, blocking ? "needs_decisions" : "ready_to_create")
        .put("readyToCreate", !blocking).put(KEY_BLOCKING, new JSONArray())
        .put("pendingDecisions", decisions).put("notices", new JSONArray(notices))
        .put("batchTemplate", template).put("supervision", OcrSupervision.rules());
  }

  private static List<JSONObject> headerToggles(JSONObject extraction) throws JSONException {
    List<JSONObject> toggles = new ArrayList<>();
    String documentNo = OcrJson.str(extraction, "document_no");
    if (OcrJson.filled(documentNo)) {
      toggles.add(toggle("apply_documentNo", "orderReference", documentNo, documentNo));
    }
    String date = OcrJson.str(extraction, KEY_INVOICE_DATE);
    if (OcrJson.filled(date)) {
      toggles.add(toggle("apply_invoiceDate", "invoiceDate", date, OcrJson.toIsoDate(date)));
    }
    return toggles;
  }

  private static JSONObject toggle(String id, String field, String value, String sendValue)
      throws JSONException {
    return new JSONObject().put("id", id).put("type", "header_toggle").put(KEY_BLOCKING, false)
        .put("field", field).put("value", value).put("sendValue", sendValue).put("default", true)
        .put("valueUntrusted", true)
        .put("explanation", "Ask whether to apply the value read from the document to the invoice's "
            + field + ". It is applied only if the user says yes.");
  }

  private static JSONObject party(JSONObject block) throws JSONException {
    return new JSONObject().put("name", OcrJson.str(block, "name"))
        .put("taxId", OcrJson.str(block, "tax_id_raw"))
        .put(KEY_TAX_ID_LABEL, OcrJson.str(block, "tax_id_label"))
        .put(KEY_TAX_ID_TYPE, OcrJson.str(block, "tax_id_type"));
  }

  private static JSONObject documentData(JSONObject extraction, JSONArray lines)
      throws JSONException {
    JSONObject vendor = new JSONObject().put("name", OcrJson.str(extraction, "vendor_name"))
        .put("taxId", OcrJson.str(extraction, "tax_id"))
        .put("address", OcrJson.str(extraction, "vendor_address"))
        .put("postalCode", OcrJson.str(extraction, "vendor_postal_code"))
        .put("city", OcrJson.str(extraction, "vendor_city"))
        .put("country", OcrJson.str(extraction, "vendor_country"))
        .put("email", OcrJson.str(extraction, "vendor_email"))
        .put("phone", OcrJson.str(extraction, "vendor_phone"));
    String date = OcrJson.str(extraction, KEY_INVOICE_DATE);
    return new JSONObject().put(KEY_UNTRUSTED, true)
        .put(KEY_ISSUER, party(extraction.optJSONObject(KEY_ISSUER)))
        .put(KEY_RECEIVER, party(extraction.optJSONObject(KEY_RECEIVER)))
        .put("vendor", vendor)
        .put("documentNo", OcrJson.str(extraction, "document_no"))
        .put("invoiceDate", date)
        .put("invoiceDateIso", OcrJson.filled(date) ? OcrJson.toIsoDate(date) : "")
        .put("lines", lines);
  }
}
