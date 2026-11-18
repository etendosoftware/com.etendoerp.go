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

import java.util.List;

import org.hibernate.Session;
import org.openbravo.dal.service.OBDal;

/**
 * The two small lookups the confirmation of an inbox document needs from the database (ETP-6051),
 * read in native SQL so they follow no entity of the model.
 */
final class InvoiceInboxConfirmLookups {

  /** Name of the contact category a contact created from a purchase document starts in. */
  static final String PURCHASE_CATEGORY_NAME = "proveedor";

  private static final String SYSTEM_CLIENT = "0";

  private InvoiceInboxConfirmLookups() {
  }

  /**
   * The contact category of a purchase document, as the SPA's {@code resolveContactCategorySeed}: the
   * one active category called {@code Proveedor}. Scoped to the client of the document: the tenant's
   * own categories first and the System's (client 0) only when the tenant has none. Never a guess:
   * none or several in the scope answer {@code null}.
   *
   * @param clientId the client of the inbox row
   * @return the {@code C_BP_Group_ID}, or {@code null}
   */
  static String purchaseContactCategory(String clientId) {
    List<Object> own = categories(clientId);
    if (own.isEmpty() && !SYSTEM_CLIENT.equals(clientId)) {
      own = categories(SYSTEM_CLIENT);
    }
    return own.size() == 1 ? (String) own.get(0) : null;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> categories(String clientId) {
    return session().createNativeQuery("SELECT c_bp_group_id FROM c_bp_group WHERE"
        + " isactive = 'Y' AND lower(trim(name)) = :name AND ad_client_id = :client")
        .setParameter("name", PURCHASE_CATEGORY_NAME).setParameter("client", clientId).list();
  }

  /**
   * The tax category of a tax: the one a product created for a line takes (ETP-5585).
   *
   * @param taxId the {@code C_Tax_ID}, may be {@code null}
   * @return the {@code C_TaxCategory_ID}, or {@code null} to take the default of the product
   */
  static String taxCategoryOf(String taxId) {
    if (taxId == null) {
      return null;
    }
    return (String) session().createNativeQuery(
        "SELECT c_taxcategory_id FROM c_tax WHERE c_tax_id = :id").setParameter("id", taxId)
        .uniqueResult();
  }

  private static Session session() {
    return OBDal.getInstance().getSession();
  }
}
