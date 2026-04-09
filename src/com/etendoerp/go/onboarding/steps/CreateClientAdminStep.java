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

import org.openbravo.authentication.hashing.PasswordHash;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;
import com.etendoerp.go.onboarding.OnboardingStepException;

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
  public void execute(OnboardingContext ctx) throws OnboardingStepException {
    try {
      Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
      Organization orgZero = OBDal.getInstance().get(Organization.class, "0");
      if (client == null) {
        throw new OBException("Client not found with ID: " + ctx.getClientId());
      }
      if (orgZero == null) {
        throw new OBException("Organization not found with ID: 0");
      }

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
    } catch (OnboardingStepException e) {
      throw e;
    } catch (Exception e) {
      throw new OnboardingStepException(e.getMessage(), e);
    }
  }
}
