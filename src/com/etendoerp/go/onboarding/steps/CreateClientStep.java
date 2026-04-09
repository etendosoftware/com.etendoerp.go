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

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;
import com.etendoerp.go.onboarding.OnboardingStepException;

/**
 * Creates a new AD_Client using Etendo's built-in InitialClientSetup.
 * This handles all the complex setup automatically: client record, 18+ AD_Tree records,
 * AD_ClientInfo, images, admin role, and admin user.
 */
public class CreateClientStep implements OnboardingStep {

  @Override
  public String name() {
    return "createClient";
  }

  @Override
  public void execute(OnboardingContext ctx) throws OnboardingStepException {
    try {
      // 1. Resolve currency ID from ISO code
      String currencyId = resolveCurrencyId(ctx.getCurrencyCode());
      ctx.setCurrencyId(currencyId);

      // 2. Build VariablesSecureApp without HTTP request
      // userId=100 (System), clientId=0, orgId=0
      VariablesSecureApp vars = new VariablesSecureApp("100", "0", "0");

      // 3. Use InitialClientSetup to create client + trees + clientInfo + images + role + user
      InitialClientSetup clientSetup = new InitialClientSetup();
      OBError result = clientSetup.createClient(
          vars,
          currencyId,
          ctx.getClientName(),
          ctx.getAdminUser(),
          ctx.getAdminPassword(),
          "",      // no reference data modules
          "",      // no account text
          "",      // no calendar text
          false,   // do NOT create accounting
          null,    // no COA file
          false, false, false, false, false  // no BP, Product, Project, Campaign, SalesRegion
      );

      if ("Error".equals(result.getType())) {
        throw new OBException("InitialClientSetup failed: " + result.getMessage()
            + "\nLog: " + clientSetup.getLog());
      }

      // 4. Extract the created client ID from session variable set by InitialClientSetup
      String clientId = StringUtils.trimToNull(vars.getSessionValue("AD_Client_ID"));
      if (clientId == null) {
        throw new OBException("InitialClientSetup succeeded but client ID not found in session");
      }
      ctx.setClientId(clientId);

      // 5. Find the client admin user bypassing readable client filters via DAL
      try {
        OBContext.setAdminMode(true);
        Client client = OBDal.getInstance().get(Client.class, clientId);
        if (client == null) {
          throw new OBException("Client not found with ID: " + clientId);
        }
        OBCriteria<User> userCriteria = OBDal.getInstance().createCriteria(User.class);
        userCriteria.add(Restrictions.eq(User.PROPERTY_USERNAME, ctx.getAdminUser()));
        userCriteria.add(Restrictions.eq(User.PROPERTY_CLIENT, client));
        userCriteria.setFilterOnReadableClients(false);
        User user = (User) userCriteria.uniqueResult();
        if (user == null) {
          throw new OBException(
              "Admin user '" + ctx.getAdminUser() + "' not found after client creation");
        }
        ctx.setClientAdminUserId(user.getId());
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      throw new OnboardingStepException(e.getMessage(), e);
    }
  }

  private String resolveCurrencyId(String isoCode) throws Exception {
    OBCriteria<Currency> criteria = OBDal.getInstance().createCriteria(Currency.class);
    criteria.add(Restrictions.eq(Currency.PROPERTY_ISOCODE, isoCode));
    criteria.setMaxResults(1);
    Currency currency = (Currency) criteria.uniqueResult();
    if (currency == null) {
      throw new OBException("Currency not found for ISO code: " + isoCode);
    }
    return currency.getId();
  }
}
