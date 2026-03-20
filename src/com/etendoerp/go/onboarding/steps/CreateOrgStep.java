package com.etendoerp.go.onboarding.steps;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.base.provider.OBProvider;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;

public class CreateOrgStep implements OnboardingStep {

  @Override
  public String name() {
    return "createOrganization";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());

    Organization org = OBProvider.getInstance().get(Organization.class);
    org.setNewOBObject(true);
    org.setClient(client);
    org.setName(ctx.getOrgName());
    org.setSearchKey(ctx.getOrgName().toLowerCase().replaceAll("\\s+", "-"));
    OBDal.getInstance().save(org);

    OrganizationInformation orgInfo = OBProvider.getInstance().get(OrganizationInformation.class);
    orgInfo.setNewOBObject(true);
    orgInfo.setClient(client);
    orgInfo.setOrganization(org);
    OBDal.getInstance().save(orgInfo);

    ctx.setOrgId(org.getId());
  }
}
