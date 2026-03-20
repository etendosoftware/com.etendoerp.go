package com.etendoerp.go.onboarding.steps;

import org.openbravo.authentication.hashing.PasswordHash;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;

/**
 * Creates the organization-level admin user, scoped to the newly created organization.
 * This dual-admin pattern (client admin + org admin) matches the SaaS module's behavior
 * and is documented as a legacy pattern to be unified in v2.
 */
public class CreateOrgAdminStep implements OnboardingStep {

  @Override
  public String name() {
    return "createOrgAdmin";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
    Organization org = OBDal.getInstance().get(Organization.class, ctx.getOrgId());

    User user = OBProvider.getInstance().get(User.class);
    user.setNewOBObject(true);
    user.setClient(client);
    user.setOrganization(org);
    user.setUsername(ctx.getAdminUser() + ".org");
    user.setEmail(ctx.getAdminUser());
    user.setName(ctx.getOrgName() + " Admin");
    user.setPassword(PasswordHash.generateHash(ctx.getAdminPassword()));
    OBDal.getInstance().save(user);

    ctx.setOrgAdminUserId(user.getId());
  }
}
