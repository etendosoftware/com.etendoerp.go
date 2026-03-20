package com.etendoerp.go.onboarding.steps;

import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.User;
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

    // Find the org admin user created by InitialOrgSetup (by username)
    OBCriteria<User> userCriteria = OBDal.getInstance().createCriteria(User.class);
    userCriteria.add(Restrictions.eq(User.PROPERTY_USERNAME, orgAdminUser));
    userCriteria.setMaxResults(1);
    User orgAdmin = (User) userCriteria.uniqueResult();
    if (orgAdmin != null) {
      ctx.setOrgAdminUserId(orgAdmin.getId());
    }
  }
}
