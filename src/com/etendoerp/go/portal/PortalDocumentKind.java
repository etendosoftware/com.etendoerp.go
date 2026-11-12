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

package com.etendoerp.go.portal;

import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * The sales documents a portal link can point at (ETP-6209).
 *
 * <p><b>This is the one place the portal names document tables.</b> The shared document-send
 * contract never does: each sales contract declares its kind
 * ({@code DefaultDocumentSendEmailContract#portalDocumentKind()}), and the base only asks "did this
 * contract declare one". That keeps the "structure yes, identity no" rule of the shared send path
 * while the portal, its own bounded context, knows what each kind is.
 *
 * <p>A credit note is an {@link #INVOICE}: it is sent from the sales-invoice window through
 * {@code sales-invoice-send}, and whether a given invoice is a credit note is read from its
 * document type when it is displayed, not stored on the link.
 */
public enum PortalDocumentKind {

  QUOTATION("C_Order", "sales-quotation", id -> ref(OBDal.getInstance().get(Order.class, id))),
  ORDER("C_Order", "sales-order", id -> ref(OBDal.getInstance().get(Order.class, id))),
  SHIPMENT("M_InOut", "goods-shipment",
      id -> ref(OBDal.getInstance().get(ShipmentInOut.class, id))),
  INVOICE("C_Invoice", "sales-invoice", id -> ref(OBDal.getInstance().get(Invoice.class, id)));

  private static final String COPY_KEY_CREDIT_NOTE = "CREDIT_NOTE";
  private static final String CATEGORY_CREDIT_MEMO = "ARC";
  private static final String CATEGORY_RETURN_MATERIAL = "ARI_RM";
  private static final String PROPERTY_RECTIFICATIVE = "etsgIsRectificative";

  private final String tableName;
  private final String specName;
  private final Function<String, Optional<Owner>> loader;

  PortalDocumentKind(String tableName, String specName,
      Function<String, Optional<Owner>> loader) {
    this.tableName = tableName;
    this.specName = specName;
    this.loader = loader;
  }

  /**
   * @return the {@code AD_Table.TableName} of the document's header
   */
  public String getTableName() {
    return tableName;
  }

  /**
   * @return the NEO spec of the window the document belongs to, used to file the renewal email in
   *     the document's own send history
   */
  public String getSpecName() {
    return specName;
  }

  /**
   * Parses the value stored in {@code etgo_portal_link.document_kind}.
   *
   * @param code the stored code
   * @return the kind, or empty for a blank or unknown code
   */
  public static Optional<PortalDocumentKind> fromCode(String code) {
    String normalized = StringUtils.trimToNull(code);
    if (normalized == null) {
      return Optional.empty();
    }
    for (PortalDocumentKind kind : values()) {
      if (kind.name().equals(normalized)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }

  /**
   * Reads who a document belongs to. The caller runs in admin mode: a portal request has no
   * session to filter against.
   *
   * @param recordId the document id
   * @return its tenant, organization and Business Partner, or empty when the record or any of the
   *     three is missing
   */
  Optional<Owner> resolveOwner(String recordId) {
    String normalized = StringUtils.trimToNull(recordId);
    return normalized == null ? Optional.empty() : loader.apply(normalized);
  }

  /**
   * Reads a document's number, for copy that names it (the renewal email). The caller runs in
   * admin mode.
   *
   * @param recordId the document id
   * @return the document number, or empty
   */
  public Optional<String> documentNumber(String recordId) {
    return resolveOwner(recordId).map(Owner::getDocumentNo)
        .map(StringUtils::trimToNull);
  }

  /**
   * The key the copy of an email names this document with — the kind's own name, or
   * {@code CREDIT_NOTE} for an invoice whose document type is a rectificative one. The caller runs
   * in admin mode.
   *
   * @param recordId the document id
   * @return {@code QUOTATION}, {@code ORDER}, {@code SHIPMENT}, {@code INVOICE} or
   *     {@code CREDIT_NOTE}
   */
  public String copyKey(String recordId) {
    if (this != INVOICE || StringUtils.isBlank(recordId)) {
      return name();
    }
    Invoice invoice = OBDal.getInstance().get(Invoice.class, recordId);
    DocumentType documentType = invoice == null ? null : invoice.getTransactionDocument();
    return documentType != null && isRectificative(documentType) ? COPY_KEY_CREDIT_NOTE : name();
  }

  /**
   * The same rule as the sales-invoice window's "Facturas rectificativas" subset
   * ({@code docs/generated-custom-windows/sales-invoice.md}): a legacy {@code ARC} or
   * {@code ARI_RM} category, or the "Factura Rectificativa" type, which shares {@code ARI} and is
   * told apart only by the localization module's flag — read only when that module is installed.
   */
  private static boolean isRectificative(DocumentType documentType) {
    String category = documentType.getDocumentCategory();
    if (CATEGORY_CREDIT_MEMO.equals(category) || CATEGORY_RETURN_MATERIAL.equals(category)) {
      return true;
    }
    return documentType.getEntity().hasProperty(PROPERTY_RECTIFICATIVE)
        && Boolean.TRUE.equals(documentType.get(PROPERTY_RECTIFICATIVE));
  }

  private static Optional<Owner> ref(Order order) {
    return order == null ? Optional.empty()
        : Owner.of(order.getClient(), order.getOrganization(), order.getBusinessPartner(),
            order.getDocumentNo());
  }

  private static Optional<Owner> ref(ShipmentInOut shipment) {
    return shipment == null ? Optional.empty()
        : Owner.of(shipment.getClient(), shipment.getOrganization(),
            shipment.getBusinessPartner(), shipment.getDocumentNo());
  }

  private static Optional<Owner> ref(Invoice invoice) {
    return invoice == null ? Optional.empty()
        : Owner.of(invoice.getClient(), invoice.getOrganization(), invoice.getBusinessPartner(),
            invoice.getDocumentNo());
  }

  /** The tenant, organization and Business Partner a document belongs to. */
  static final class Owner {
    private final Client client;
    private final Organization organization;
    private final BusinessPartner businessPartner;
    private final String documentNo;

    private Owner(Client client, Organization organization, BusinessPartner businessPartner,
        String documentNo) {
      this.client = client;
      this.organization = organization;
      this.businessPartner = businessPartner;
      this.documentNo = documentNo;
    }

    static Optional<Owner> of(Client client, Organization organization,
        BusinessPartner businessPartner, String documentNo) {
      return client == null || organization == null || businessPartner == null
          ? Optional.empty()
          : Optional.of(new Owner(client, organization, businessPartner, documentNo));
    }

    String getDocumentNo() {
      return documentNo;
    }

    Client getClient() {
      return client;
    }

    Organization getOrganization() {
      return organization;
    }

    BusinessPartner getBusinessPartner() {
      return businessPartner;
    }
  }
}
