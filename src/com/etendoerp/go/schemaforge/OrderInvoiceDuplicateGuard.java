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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The one-draft-invoice-per-order rule of the screen, for every channel (ETP-5714): the order
 * header's {@code createPurchaseInvoice} / {@code createDraftInvoice} refuse with 409
 * {@code draftInvoiceExists} when the order already has a linked DRAFT invoice, unless the request
 * carries the JSON boolean {@code allowDuplicate:true}. The screen hides the button in that case
 * (its {@code needsInvoiceDoc} flag), and {@code etendo_schema(view:"actions")} reports the action
 * as {@code availableNow:false}; the call now agrees with both. Same pattern as
 * {@link ReturnInvoiceSupport#refuseIfAlreadyInvoiced} (ETP-5712).
 *
 * <p>Kept out of the two invoice handlers on purpose: they are far past the size Sonar accepts.
 * They call it explicitly from their order route; nothing here selects an entity. The drafts come
 * from {@link OrderDocumentAvailability#draftInvoiceNumbers}, the very query behind the advice.</p>
 */
final class OrderInvoiceDuplicateGuard {

  /** {@code error.code} of the 409 (ETP-5714). */
  static final String ERROR_DRAFT_INVOICE_EXISTS = "draftInvoiceExists";

  /** Key listing the draft invoices, in the 409 body and in a created answer. */
  static final String KEY_EXISTING_DRAFTS = "existingDraftInvoices";

  /** The one way past the 409, which the screen does not offer. */
  static final String HINT_DRAFT_INVOICE_EXISTS = "Complete or delete the draft invoice instead of "
      + "creating another one. Pass allowDuplicate:true only if a second invoice for the same "
      + "order lines is really intended. Nothing was created.";

  /** What the {@code allowDuplicate} contract parameter says. */
  static final String ALLOW_DUPLICATE_DESCRIPTION = "Only the JSON boolean true creates an invoice "
      + "for an order that already has a draft invoice; anything else (absent, false, a string, a "
      + "number) keeps the refusal. Use it only when a second invoice is really intended.";

  private OrderInvoiceDuplicateGuard() {
  }

  /**
   * The {@code allowDuplicate} parameter both order invoice contracts declare.
   *
   * @return the optional boolean parameter
   */
  static NeoActionContract.Param allowDuplicateParam() {
    return NeoActionContract.Param.optional(ReturnShipmentUtils.PARAM_ALLOW_DUPLICATE,
        NeoActionContract.TYPE_BOOLEAN, ALLOW_DUPLICATE_DESCRIPTION);
  }

  /**
   * The draft invoices that already cover the order, as document numbers.
   *
   * @param orderId the order
   * @param sales   {@code true} for a sales order
   * @return the numbers, empty when none
   */
  static List<String> draftsOf(String orderId, boolean sales) {
    return OrderDocumentAvailability.draftInvoiceNumbers(orderId, sales);
  }

  /**
   * Refuses the creation while a draft invoice exists. Strict like the return rule: a string, a
   * number or {@code null} for {@code allowDuplicate} is never read as true, it is a 422.
   *
   * @param body   the request body; {@code null} reads as empty
   * @param drafts the order's draft invoice numbers, from {@link #draftsOf}
   * @return the 409 or 422, or {@code null} when the creation may go on
   */
  static NeoResponse refuseIfDraftExists(JSONObject body, List<String> drafts) {
    if (drafts == null || drafts.isEmpty()) {
      // still strict about a malformed allowDuplicate, which is a 422 whatever the state
      return DuplicateCreationRefusal.refuseUnlessAllowed(body, null, ERROR_DRAFT_INVOICE_EXISTS,
          null, HINT_DRAFT_INVOICE_EXISTS, KEY_EXISTING_DRAFTS);
    }
    return DuplicateCreationRefusal.refuseUnlessAllowed(body, new JSONArray(drafts),
        ERROR_DRAFT_INVOICE_EXISTS,
        "This order already has a draft invoice (" + String.join(", ", drafts) + ").",
        HINT_DRAFT_INVOICE_EXISTS, KEY_EXISTING_DRAFTS);
  }

  /**
   * Same refusal, thrown: lets a caller with a long body stop the creation without a branch of
   * its own, catching {@link Refusal} next to its other typed refusals.
   *
   * @param body   the request body; {@code null} reads as empty
   * @param drafts the order's draft invoice numbers, from {@link #draftsOf}
   * @throws Refusal carrying the 409 or 422 when the creation must not go on
   */
  static void requireNoDraft(JSONObject body, List<String> drafts) {
    NeoResponse refusal = refuseIfDraftExists(body, drafts);
    if (refusal != null) {
      throw new Refusal(refusal);
    }
  }

  /** The refusal of {@link #requireNoDraft}, carrying the response to answer. */
  static final class Refusal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient NeoResponse response;

    Refusal(NeoResponse response) {
      super(ERROR_DRAFT_INVOICE_EXISTS, null, false, false);
      this.response = response;
    }

    /** @return the response to answer */
    NeoResponse getResponse() {
      return response;
    }
  }

  /**
   * Adds the drafts the order had before this call to a created answer, so an
   * {@code allowDuplicate:true} caller sees what it chose to duplicate. Nothing is added when
   * there were none.
   *
   * @param data   the {@code response.data} object of the created answer
   * @param drafts the drafts the order had before the call
   * @throws JSONException if the key cannot be written
   */
  static void listExisting(JSONObject data, List<String> drafts) throws JSONException {
    if (drafts != null && !drafts.isEmpty()) {
      data.put(KEY_EXISTING_DRAFTS, new JSONArray(drafts));
    }
  }
}
