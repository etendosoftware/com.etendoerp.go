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

package com.etendoerp.go.schemaforge.handlers;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoCertificateHelper;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-5852 (M-170): the read-only {@code certificate} action of {@code organization} — whether the
 * organization has an active signing certificate (the one Verifactu, TicketBAI and SII sign with)
 * and when it expires, as the fiscal configuration shows it ({@code GET /sws/neo/certificate}).
 * Never the certificate itself or its password. The organization is the record the call names,
 * loaded with the caller's access, so an organization the role cannot read answers 404.
 */
@NeoExtension(spec = "organization", entity = "organization")
public class OrganizationCertificateAction implements NeoHandler {

  private static final Logger log = LogManager.getLogger(OrganizationCertificateAction.class);

  static final String ACTION = "certificate";

  private static final Map<String, NeoActionContract> CONTRACTS = Collections.singletonMap(ACTION,
      NeoActionContract.read(ACTION,
          "Returns the organization's signing certificate status, as its fiscal configuration "
              + "shows it: {exists, validTo (yyyy-MM-dd), daysLeft} — daysLeft is negative once it "
              + "has expired; exists:false when no active certificate is uploaded. The "
              + "certificate is uploaded and replaced in the app.")
          .withIdDescription("the organization id"));

  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return CONTRACTS;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !ACTION.equals(context.getFieldName())) {
      return null;
    }
    String orgId = context.getRecordId();
    if (orgId == null || OBDal.getInstance().get(Organization.class, orgId) == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Organization not found");
    }
    try {
      return NeoResponse.ok(status(NeoCertificateHelper.activeCertificateExpiry(orgId),
          LocalDate.now()));
    } catch (JSONException e) {
      log.error("Could not build the certificate status of {}", orgId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not read the certificate status");
    }
  }

  /** The answer for a certificate expiring on {@code expiry} ({@code null}: none), seen on {@code today}. */
  static JSONObject status(Date expiry, LocalDate today) throws JSONException {
    JSONObject body = new JSONObject();
    body.put("exists", expiry != null);
    if (expiry != null) {
      LocalDate validTo = new java.sql.Date(expiry.getTime()).toLocalDate();
      body.put("validTo", validTo.toString());
      body.put("daysLeft", ChronoUnit.DAYS.between(today, validTo));
    }
    return body;
  }
}
