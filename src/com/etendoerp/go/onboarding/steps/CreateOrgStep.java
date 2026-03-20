package com.etendoerp.go.onboarding.steps;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;

/**
 * Creates a new AD_Org using Etendo's built-in InitialOrgSetup.
 * This handles all the complex setup automatically: organization record, tree nodes,
 * org info, images, admin user, and role.
 */
public class CreateOrgStep implements OnboardingStep {

  @Override
  public String name() {
    return "createOrganization";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    // Switch OBContext to the new client so Hibernate filters include it
    OBContext.setOBContext(ctx.getClientAdminUserId(), "0", ctx.getClientId(), "0");
    OBContext.setAdminMode(false);

    // Force a fresh Hibernate session so filters pick up the new client context
    OBDal.getInstance().commitAndClose();

    // Reload client in fresh session
    Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());

    // The org admin username is the main admin user with ".org" suffix
    // to avoid duplication with the client admin user
    String orgAdminUser = ctx.getAdminUser() + ".org";

    // Use InitialOrgSetup to create org + tree nodes + org info + images + user + role
    InitialOrgSetup orgSetup = new InitialOrgSetup(client);
    OBError result = orgSetup.createOrganization(
        ctx.getOrgName(),
        orgAdminUser,
        "1",                  // org type "1" = Legal with accounting
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
      throw new Exception("InitialOrgSetup failed: " + result.getMessage()
          + "\nLog: " + orgSetup.getLog());
    }

    // Extract the created org ID
    String orgId = orgSetup.getOrgId();
    if (orgId == null || orgId.isEmpty()) {
      throw new Exception("InitialOrgSetup succeeded but org ID not found");
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
  }
}
