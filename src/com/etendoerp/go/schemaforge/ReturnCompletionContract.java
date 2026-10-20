package com.etendoerp.go.schemaforge;

import java.util.List;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The {@code documentAction} contract of a RETURN header (ETP-5712): the return to a vendor and the
 * return of a customer's goods.
 *
 * <p>Why it is not {@link InOutCompletionContract}: that one describes a goods receipt / shipment
 * and is shared with them, so it cannot carry return-specific text. Completing a return books the
 * stock movement only; the factura rectificativa (the corrective invoice that refunds it) is a
 * SEPARATE step, {@code createReturnInvoice}. An agent that confirmed the return and stopped there
 * believed the refund was done (the UI offers the invoice as a second button, and the contract said
 * nothing). The text names that second call and the one that helps to choose its origin invoices.
 *
 * <p>Same shape as the shared contract: only {@code CO} is offered, the body stays flat
 * ({@code {"docAction":"CO"}}), and the router keeps serving the action as before. Referenced
 * explicitly by the two return header handlers; it is not selected by entity identity.
 */
final class ReturnCompletionContract {

  static final NeoActionContract CONTRACT = NeoActionContract.write("documentAction",
      "Completes this DRAFT return document (documentStatus DR -> CO), once its lines are in: "
          + "books the stock movement of the returned goods. Confirming does NOT create the "
          + "factura rectificativa (the corrective invoice that refunds the return): that is a "
          + "SEPARATE call, createReturnInvoice, to make AFTER this one. Call rectifiableInvoices "
          + "first to see which invoices the return can rectify and whether it already has a "
          + "factura rectificativa (hasReturnInvoice). Precondition: the document is a draft. "
          + "Accounting posting is a separate step reflected by the posted "
          + "field, which may still read N right after completing. Only CO is offered: "
          + "completing cannot be undone from here and voiding is not available.",
      NeoActionContract.Param.requiredOptions("docAction",
          "The only value accepted is CO (complete).",
          List.of(InOutCompletionContract.DOC_ACTION_COMPLETE)))
      .withIdDescription("the id of the draft return to complete");

  private ReturnCompletionContract() {
  }
}
