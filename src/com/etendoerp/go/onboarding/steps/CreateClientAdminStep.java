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
 * Creates the client-level admin user, scoped to organization "0" (legacy pattern).
 * This dual-admin pattern (client admin + org admin) matches the SaaS module's behavior
 * and is documented as a legacy pattern to be unified in v2.
 */
public class CreateClientAdminStep implements OnboardingStep {

  @Override
  public String name() {
    return "createClientAdmin";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
    Organization orgZero = OBDal.getInstance().get(Organization.class, "0");

    User user = OBProvider.getInstance().get(User.class);
    user.setNewOBObject(true);
    user.setClient(client);
    user.setOrganization(orgZero);
    user.setUsername(ctx.getAdminUser());
    user.setEmail(ctx.getAdminUser());
    user.setName(ctx.getClientName() + " Admin");
    user.setPassword(PasswordHash.generateHash(ctx.getAdminPassword()));
    OBDal.getInstance().save(user);

    ctx.setClientAdminUserId(user.getId());
  }
}
