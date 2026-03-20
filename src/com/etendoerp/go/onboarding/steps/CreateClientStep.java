package com.etendoerp.go.onboarding.steps;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.ClientInformation;
import org.openbravo.base.provider.OBProvider;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;

public class CreateClientStep implements OnboardingStep {

  @Override
  public String name() {
    return "createClient";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    Client client = OBProvider.getInstance().get(Client.class);
    client.setNewOBObject(true);
    client.setName(ctx.getClientName());
    client.setSearchKey(ctx.getClientName().toLowerCase().replaceAll("\\s+", "-"));
    OBDal.getInstance().save(client);

    ClientInformation clientInfo = OBProvider.getInstance().get(ClientInformation.class);
    clientInfo.setNewOBObject(true);
    clientInfo.setClient(client);
    OBDal.getInstance().save(clientInfo);

    ctx.setClientId(client.getId());
  }
}
