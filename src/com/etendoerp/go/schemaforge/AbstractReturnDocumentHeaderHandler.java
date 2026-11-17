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

import java.util.Map;

import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * Shared base for the two return-document header handlers ({@link ReturnMaterialReceiptHeaderHandler}
 * and {@link ReturnToVendorShipmentHeaderHandler}) — holds the {@code postingService} injection
 * point and the {@code handle()} wiring around it (ETP-5378).
 *
 * <p>This injection-and-wiring shape was byte-identical between the two concrete handlers and kept
 * tripping SonarQube's duplicated-lines-on-new-code gate across two prior remediation attempts —
 * see {@link NeoHandlerUtils#delegateToPostingServiceOrElse}'s javadoc for the first two (moving
 * the guard clause's body out of {@code handle()}, then removing the private wrapper method around
 * it). Both attempts still left this exact field/setter/{@code handle()} shape typed out in each
 * subclass, which is what CPD kept matching. Hoisting it here removes the last copy: CDI/Weld
 * resolves {@code @Inject} fields declared anywhere in the class hierarchy, so each concrete
 * subclass still gets its own {@code postingService} instance without redeclaring the field.
 */
public abstract class AbstractReturnDocumentHeaderHandler implements NeoHandler {

  private static final Logger LOG = LogManager.getLogger(AbstractReturnDocumentHeaderHandler.class);

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    mirrorAccountingDate(context);
    // ETP-5714: an unpost of a return that is not posted is refused, not answered as a no-op 200.
    NeoResponse unpostRefusal = GoodsPostingContracts.checkUnpost(context);
    if (unpostRefusal != null) {
      return unpostRefusal;
    }
    // ETP-6179: the SIF fields createReturnInvoice would give the factura rectificativa.
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && InvoiceSifPreset.ACTION_SIF_DEFAULTS.equals(context.getFieldName())
        && "POST".equals(context.getHttpMethod())) {
      return handleSifDefaults(context);
    }
    return NeoHandlerUtils.delegateToPostingServiceOrElse(context, postingService, this::continueHandling);
  }

  /**
   * ETP-6179: answers {@code sifDefaults} for the factura rectificativa
   * {@code createReturnInvoice} would create from this return, creating nothing. Shared by both
   * return headers: the direction comes from the return's own {@code IsSOTrx}.
   */
  private static NeoResponse handleSifDefaults(NeoContext context) {
    String returnId = context.getRecordId();
    if (returnId == null || returnId.isBlank()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Record ID is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ShipmentInOut returnDoc = OBDal.getInstance().get(ShipmentInOut.class, returnId);
        if (returnDoc == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Return document not found");
        }
        return InvoiceSifPreset.previewResponse(new InvoiceSifPreset.Target(
            returnDoc.getOrganization(), returnDoc.getBusinessPartner(),
            ReturnShipmentUtils.findReturnInvoiceDocType(returnDoc),
            Boolean.TRUE.equals(returnDoc.isSalesTransaction()), null));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      LOG.error("Error previewing the SIF fields for return {}: {}", returnId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while previewing the invoice's fiscal data");
    }
  }

  /**
   * {@inheritDoc} {@code createReturnInvoice} does not apply once the return already has a factura
   * rectificativa (ETP-5714).
   */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    return ReturnDocumentAvailability.unavailable(subject, acceptsAllowDuplicate());
  }

  /**
   * Whether this header's {@code createReturnInvoice} lets {@code allowDuplicate:true} create a
   * second rectificativa (ETP-5712). Each customization answers for its own window: the customer
   * return overrides it with {@code false} (ETP-6026, one per return as on the screen).
   *
   * @return {@code true} when the flag is declared and honoured
   */
  protected boolean acceptsAllowDuplicate() {
    return true;
  }

  /**
   * Mirrors the document's single visible date field into its hidden accounting-date field
   * before the default CRUD path persists it. Each subclass supplies its own field-name
   * constants and calls the shared {@link NeoHandlerUtils#mirrorFieldValue}.
   */
  protected abstract void mirrorAccountingDate(NeoContext context);

  /**
   * Everything {@link #handle(NeoContext)} used to do after its posting-delegation guard
   * clause, now the continuation {@link NeoHandlerUtils#delegateToPostingServiceOrElse} calls
   * when posting/unpost did not apply — see that method's javadoc for why the guard clause
   * itself had to move out of the concrete classes entirely, not just its body.
   */
  protected abstract NeoResponse continueHandling(NeoContext context);
}
