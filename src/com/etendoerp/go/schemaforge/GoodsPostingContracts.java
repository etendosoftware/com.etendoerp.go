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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The {@code post} / {@code unpost} contracts of the goods documents — receipt, shipment, return to
 * vendor, customer return (ETP-5714).
 *
 * <p>Why they are declared. The SPA books and unbooks these documents with the menu actions
 * {@code post} / {@code unpost}, served by {@link com.etendoerp.go.schemaforge.handlers.DocumentPostingService}.
 * Nothing declared them, so {@code etendo_schema(view:"actions")} listed only the AD {@code Posted}
 * button — a blind toggle the SPA never uses, whose failure was core's raw cost error with no retry
 * guidance. Declaring them publishes the real actions with their states, the same way
 * {@link InvoicePostingGate#CONTRACTS} does for invoices.</p>
 *
 * <p>Declaring changes no routing: the handlers keep serving the actions through the posting
 * service, and REST and the SPA do not read the contracts. The service answers a handled 200 with
 * "0 entries removed" for any document that is not posted (ETP-5445, Internal Consumption), and
 * that stays for every table. A goods document is narrower, like an invoice: {@link #checkUnpost}
 * refuses an {@code unpost} of one that is not posted with a 422 and its reason, called explicitly
 * by the four header handlers before they delegate to the service (ETP-5714).</p>
 *
 * <p>Referenced explicitly from the four header handlers; not selected by entity identity.</p>
 */
final class GoodsPostingContracts {

  private static final Logger log = LogManager.getLogger(GoodsPostingContracts.class);

  private static final String ID_DESCRIPTION = "the id of the goods document";

  /** Stable identity sent in {@code messageKeys} when an unpost finds nothing posted (ETP-5714). */
  static final String MSG_UNPOST_NOT_POSTED = "ETGO_GoodsUnpostNotPosted";

  /** {@code post} then {@code unpost}, in this order. */
  static final Map<String, NeoActionContract> CONTRACTS;

  static {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put("post", NeoActionContract.write("post",
        "Books the document in the general ledger: creates its accounting entries and sets posted "
            + "to 'Y'. Exactly what the app's Post button does. Only for a completed document whose "
            + "posted field is not 'Y': a draft answers 422 with messageKeys "
            + "['ETGO_PostingDocumentNotProcessed'] and an already posted one 422 with "
            + "['PostedDocument']; an incomplete accounting setup or a closed period also answers 422 "
            + "with the reason. Nothing changes on a refusal. Cost is calculated by a background "
            + "process about every 30 seconds, so posting right after completing can answer 409 "
            + "costNotCalculated with retryAfterSeconds 30 (nothing was posted, posted stays 'NC'): "
            + "wait that long and repeat the same call. If it answers 422 costCalculationStalled "
            + "(retryable false), costing is stuck, not slow: do not retry, tell the user an "
            + "administrator must check the Costing Background process. Takes no parameters; "
            + "never use the AD Posted button instead.")
        .withIdDescription(ID_DESCRIPTION)
        .availableWhen(DocumentAvailability.WHEN_POSTABLE));
    contracts.put(InvoicePostingGate.ACTION_UNPOST, NeoActionContract.write(InvoicePostingGate.ACTION_UNPOST,
        "Removes the document's accounting entries (posted becomes 'N') and leaves it Completed. "
            + "Exactly what the app's Unpost button does. Only for a document whose posted field "
            + "is 'Y': one that is not posted (a draft, or a completed one not yet booked) answers "
            + "422 with messageKeys ['ETGO_GoodsUnpostNotPosted'] and changes nothing. A period "
            + "closed for unposting also answers 422. Takes no parameters.")
        .withIdDescription(ID_DESCRIPTION)
        .availableWhen(DocumentAvailability.WHEN_UNPOSTABLE));
    CONTRACTS = Collections.unmodifiableMap(contracts);
  }

  private GoodsPostingContracts() {
  }

  /**
   * Refuses an {@code unpost} of a goods document that is not posted (ETP-5714): a handled 200
   * "0 entries removed" reads as a success an agent then reports to the user. Per entity on
   * purpose — {@code DocumentPostingService} keeps that 200 for every other table (ETP-5445) —
   * so each goods header handler calls it explicitly before delegating to the service.
   *
   * @param context the request
   * @return a 422 in the flat shape the posting service answers ({@code success:false, message,
   *     messageKeys}), or {@code null} to let the service run: always {@code null} for anything
   *     but an {@code unpost} action, and when the document cannot be read
   */
  static NeoResponse checkUnpost(NeoContext context) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !InvoicePostingGate.ACTION_UNPOST.equals(context.getFieldName())
        || StringUtils.isBlank(context.getRecordId())) {
      return null;
    }
    ShipmentInOut document = InvoicePostingGate.readForGate(context, ShipmentInOut.class);
    if (document == null || DocumentAvailability.POSTED_YES.equals(document.getPosted())) {
      return null;
    }
    try {
      return InvoicePostingGate.refusal("This document is not posted, so it has no accounting "
          + "entries to remove.", MSG_UNPOST_NOT_POSTED, Collections.emptyMap());
    } catch (JSONException e) {
      log.error("Could not build the unpost refusal for goods document {}", context.getRecordId(),
          e);
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, "Unpost refused");
    }
  }
}
