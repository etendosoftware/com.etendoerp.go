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
import java.util.function.Function;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.portal.PortalContactShare;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The contacts {@code portalLink} action (ETP-5850): a customer's portal link, handed to the user
 * so they can share it themselves (Holded's {@code get_contact_portal_link} has the same purpose).
 * The link is the one a document email carries; {@link PortalContactShare} mints it and states the
 * rules (the {@code bp-portal-link} flag, the customer checks, an entry document and a contact
 * email are required, who asked is recorded, it is not counted as a send).
 *
 * <p>A write: it creates a link that opens the customer's sales documents to whoever holds it, so
 * the role needs write access to the Contacts window, the tier of every mutating action.</p>
 */
final class ContactPortalLinkAction {

  static final String ACTION = "portalLink";

  private static final String ERROR = "error";
  private static final String MESSAGE = "message";

  static final Map<String, NeoActionContract> CONTRACTS = Map.of(ACTION,
      NeoActionContract.write(ACTION,
          "Gets a link to this customer's portal, where they see their quotations, orders, "
              + "shipments and invoices and accept or reject quotations, for the user to send "
              + "it themselves (a document email already carries one). Returns url, expiresAt "
              + "(the link stops working then; opened later, a new one is emailed to "
              + "renewalEmail), entryDocument (the most recent document, where the portal "
              + "opens) and renewalEmail. Each call creates a new link; anyone holding it sees "
              + "the customer's documents, so give it only to the user who asked. Refused when "
              + "the customer portal is not enabled for this account, the customer is blocked "
              + "or inactive, has no contact email, or has no document the portal shows.")
          .withIdDescription("the id of the contact (a customer)"));

  private ContactPortalLinkAction() {
  }

  /**
   * Serve the action.
   *
   * @param context the request; only an ACTION context naming {@link #ACTION} is served
   * @param share   mints the link for a contact id
   * @return the answer, or {@code null} when {@code context} is not this action
   */
  static NeoResponse handle(NeoContext context,
      Function<String, PortalContactShare.Outcome> share) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !ACTION.equals(context.getFieldName())) {
      return null;
    }
    if (!"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    if (!AgentActionSupport.hasAccess(context, true)) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Access denied to spec for current role");
    }
    String contactId = StringUtils.trimToNull(context.getRecordId());
    if (contactId == null) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
          "id is required: the id of the contact (a customer)");
    }
    try {
      return answer(share.apply(contactId));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the portal link answer", e);
    }
  }

  private static NeoResponse answer(PortalContactShare.Outcome outcome) throws JSONException {
    switch (outcome.getState()) {
      case SHARED:
        return NeoResponse.ok(new JSONObject()
            .put("url", outcome.getUrl())
            .put("expiresAt", outcome.getExpiresAt().toString())
            .put("entryDocument", new JSONObject()
                .put("spec", outcome.getDocumentSpec())
                .put("id", outcome.getDocumentId())
                .put("documentNo", outcome.getDocumentNo()))
            .put("renewalEmail", outcome.getRecipient()));
      case DISABLED:
        return refusal(HttpServletResponse.SC_FORBIDDEN, "feature_disabled",
            "The customer portal is not enabled for this account.");
      case NOT_FOUND:
        return refusal(HttpServletResponse.SC_NOT_FOUND, "not_found", "Contact not found.");
      case UNAVAILABLE:
        return refusal(HttpServletResponse.SC_CONFLICT, "portal_unavailable",
            "The portal is closed for this customer: it is blocked or inactive, or the "
                + "company's plan expired.");
      case NO_EMAIL:
        return refusal(HttpServletResponse.SC_CONFLICT, "contact_without_email",
            "The contact has no email. Add one first: an expired link is renewed by email.");
      case NO_DOCUMENTS:
        return refusal(HttpServletResponse.SC_CONFLICT, "no_portal_documents",
            "The customer has no completed quotation, order, shipment or invoice: their portal "
                + "would be empty.");
      default:
        return refusal(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "portal_not_configured",
            "This environment has no app address to build portal links on.");
    }
  }

  private static NeoResponse refusal(int status, String code, String message)
      throws JSONException {
    return NeoResponse.error(status, new JSONObject().put(ERROR, code).put(MESSAGE, message));
  }
}
