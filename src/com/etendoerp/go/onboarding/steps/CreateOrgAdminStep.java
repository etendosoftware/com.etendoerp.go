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
  public void execute(OnboardingContext ctx) throws OnboardingStepException {
    try {
      Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
      if (client == null) {
        throw new OBException("Client not found with ID: " + ctx.getClientId());
      }
      Organization org = OBDal.getInstance().get(Organization.class, ctx.getOrgId());
      if (org == null) {
        throw new OBException("Organization not found with ID: " + ctx.getOrgId());
      }

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
    } catch (Exception e) {
      throw new OnboardingStepException(e.getMessage(), e);
    }
  }
}
