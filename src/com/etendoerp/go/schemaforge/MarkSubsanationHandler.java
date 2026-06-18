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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the facturasParcialmenteAceptadas VF monitor entity.
 *
 * <p>Intercepts PUT/PATCH requests and sets {@code em_etvfac_issubsanation = 'Y'} on the
 * underlying C_Invoice so the InvoiceSendingListener picks it up for retry.
 *
 * <p>The entity is backed by the view {@code etvfac_inv_sent_status_v} (ISVIEW=Y), so the
 * standard DataSourceServlet PUT writes nothing. This handler navigates from the view PK
 * ({@code etvfac_c_invoice_verifactu_id}) to C_Invoice via a native SQL lookup and updates
 * the flag with an HQL UPDATE to avoid a compile-time dependency on the verifactu module.
 */
@ApplicationScoped
@Named("mark-subsanation-handler")
public class MarkSubsanationHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(MarkSubsanationHandler.class);

  private static final String VF_RECORD_QUERY =
      "SELECT c_invoice_id FROM etvfac_c_invoice_verifactu "
          + "WHERE etvfac_c_invoice_verifactu_id = :id";

  private static final String SET_SUBSANATION_HQL =
      "UPDATE org.openbravo.model.common.invoice.Invoice "
          + "SET etvfacIsSubsanation = true WHERE id = :id";

  @Override
  public NeoResponse handle(NeoContext context) {
    String method = context.getHttpMethod();
    if (!"PUT".equals(method) && !"PATCH".equals(method)) {
      return null;
    }

    // If body explicitly sets isSubsanation=false, do nothing
    org.codehaus.jettison.json.JSONObject body = context.getRequestBody();
    if (body != null && body.has("isSubsanation")) {
      try {
        if (!body.getBoolean("isSubsanation")) {
          return null;
        }
      } catch (org.codehaus.jettison.json.JSONException e) {
        // non-boolean value — fall through to handle it
      }
    }

    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Record ID is required");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String cInvoiceId = (String) OBDal.getInstance().getSession()
            .createNativeQuery(VF_RECORD_QUERY)
            .setParameter("id", recordId)
            .uniqueResult();

        if (cInvoiceId == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "VF record not found: " + recordId);
        }

        int updated = OBDal.getInstance().getSession()
            .createQuery(SET_SUBSANATION_HQL)
            .setParameter("id", cInvoiceId)
            .executeUpdate();

        if (updated == 0) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              "Invoice not found: " + cInvoiceId);
        }

        OBDal.getInstance().flush();
        return NeoResponse.noContent();

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error marking subsanation for VF record {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error marking subsanation: " + e.getMessage());
    }
  }
}
