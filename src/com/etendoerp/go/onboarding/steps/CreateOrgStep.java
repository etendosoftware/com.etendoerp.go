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

package com.etendoerp.go.onboarding.steps;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;
import com.etendoerp.go.onboarding.OnboardingStepException;

/**
 * Creates a new AD_Org using Etendo's built-in InitialOrgSetup.
 * This handles all the complex setup automatically: organization record, tree nodes,
 * org info, images, admin user, and role.
 */
public class CreateOrgStep implements OnboardingStep {

  private static final String LEGAL_WITH_ACCOUNTING_ORG_TYPE_ID = "1";

  @Override
  public String name() {
    return "createOrganization";
  }

  @Override
  public void execute(OnboardingContext ctx) throws OnboardingStepException {
    try {
      // Switch OBContext to the new client so Hibernate filters include it
      OBContext.setOBContext(ctx.getClientAdminUserId(), "0", ctx.getClientId(), "0");
      OBContext.setAdminMode(false);

      // Force a fresh Hibernate session so filters pick up the new client context
      OBDal.getInstance().commitAndClose();

      // Reload client in fresh session
      Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
      if (client == null) {
        throw new OBException("Client not found with ID: " + ctx.getClientId());
      }

      // The org admin username is the main admin user with ".org" suffix
      // to avoid duplication with the client admin user
      String orgAdminUser = ctx.getAdminUser() + ".org";

      // Use InitialOrgSetup to create org + tree nodes + org info + images + user + role
      InitialOrgSetup orgSetup = new InitialOrgSetup(client);
      OBError result = orgSetup.createOrganization(
          ctx.getOrgName(),
          orgAdminUser,
        LEGAL_WITH_ACCOUNTING_ORG_TYPE_ID,
          "0",                  // parent org = root
          "",                   // no location yet
          ctx.getAdminPassword(),
          "",                   // no reference data modules
          false,                // do NOT create accounting
          null,                 // no COA file
          ctx.getCurrencyId(),  // currency ID
          false, false, false, false, false  // no BP, Product, Project, Campaign, SalesRegion
      );

      if ("Error".equals(result.getType())) {
        throw new OBException("InitialOrgSetup failed: " + result.getMessage()
            + "\nLog: " + orgSetup.getLog());
      }

      // Extract the created org ID
      String orgId = orgSetup.getOrgId();
      if (orgId == null || orgId.isEmpty()) {
        throw new OBException("InitialOrgSetup succeeded but org ID not found");
      }
      ctx.setOrgId(orgId);

      // Find the org admin user via SQL (bypasses Hibernate client filters)
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT ad_user_id FROM ad_user WHERE username = ? AND ad_client_id = ?")) {
        ps.setString(1, orgAdminUser);
        ps.setString(2, ctx.getClientId());
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            ctx.setOrgAdminUserId(rs.getString(1));
          }
        }
      }
      if (ctx.getOrgAdminUserId() == null) {
        throw new OBException("Org admin user '" + orgAdminUser + "' not found after organization creation");
      }
    } catch (Exception e) {
      throw new OnboardingStepException(e.getMessage(), e);
    }
  }
}
