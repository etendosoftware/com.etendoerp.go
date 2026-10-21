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

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The {@code documentAction} contract of a goods receipt / goods shipment header (ETP-5709).
 *
 * <p>Why it is declared: {@code M_InOut.DocAction} has {@code AD_Field.IsDisplayed = 'N'} on both
 * windows (the UI shows a separate process button instead), so the MCP catalogue read it as a
 * button the AD "does not display in the tab" and listed it as not invokable, although a call
 * works. A declared contract replaces that AD button in the listing and publishes it as
 * invokable. Do not remove it to "simplify": the listing would revert.
 *
 * <p>Only {@code CO} is offered. Voiding is a product decision (the UI offers no void on a
 * completed receipt/shipment); the other document actions are not exposed either. The request
 * body stays flat ({@code {"docAction":"CO"}}), which is what the native process reads and what
 * the SPA posts ({@code useDocumentAction}). The handlers keep serving the action as before
 * (the router returns {@code null} and NEO runs the native process); REST and the SPA do not
 * validate against this contract, only the MCP precheck does.
 *
 * <p>Referenced explicitly from {@link GoodsReceiptHeaderHandler} and
 * {@link GoodsShipmentHeaderHandler}; it is not selected by entity identity.
 */
final class InOutCompletionContract {

  /** The only document action offered: complete. */
  static final String DOC_ACTION_COMPLETE = "CO";

  static final NeoActionContract CONTRACT = NeoActionContract.write("documentAction",
      "Completes this DRAFT goods document (documentStatus DR -> CO): the receipt or shipment "
          + "that createGoodsReceipt / createShipment generates (docAction CO). Completing books "
          + "the stock movement and updates the received/shipped quantity of the order lines it "
          + "comes from. Precondition: the document is a draft. Accounting posting is a separate "
          + "step reflected by the posted field, which may still read N right after completing. "
          + "Only CO is offered: completing cannot be undone from here and "
          + "voiding is not available; a completed document is corrected with a return.",
      NeoActionContract.Param.requiredOptions("docAction",
          "The only value accepted is CO (complete).", List.of(DOC_ACTION_COMPLETE))
          .withRefusalHint(DocumentAvailability.GOODS_REFUSAL_HINT))
      .withIdDescription("the id of the draft goods receipt / goods shipment to complete")
      .availableWhen(DocumentAvailability.GOODS_DOCUMENT_ACTION);

  private InOutCompletionContract() {
  }
}
