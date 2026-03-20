package com.etendoerp.go.onboarding.steps;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;

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
  public void execute(OnboardingContext ctx) throws Exception {
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
      throw new Exception("InitialClientSetup failed: " + result.getMessage()
          + "\nLog: " + clientSetup.getLog());
    }

    // 4. Extract the created client ID from session variable set by InitialClientSetup
    String clientId = vars.getSessionValue("AD_Client_ID");
    if (clientId == null || clientId.isEmpty()) {
      throw new Exception("InitialClientSetup succeeded but client ID not found in session");
    }
    ctx.setClientId(clientId);

    // 5. Find the client admin user created by InitialClientSetup (by username)
    OBCriteria<User> userCriteria = OBDal.getInstance().createCriteria(User.class);
    userCriteria.add(Restrictions.eq(User.PROPERTY_USERNAME, ctx.getAdminUser()));
    userCriteria.setMaxResults(1);
    User adminUser = (User) userCriteria.uniqueResult();
    if (adminUser != null) {
      ctx.setClientAdminUserId(adminUser.getId());
    }
  }

  private String resolveCurrencyId(String isoCode) throws Exception {
    OBCriteria<Currency> criteria = OBDal.getInstance().createCriteria(Currency.class);
    criteria.add(Restrictions.eq(Currency.PROPERTY_ISOCODE, isoCode));
    criteria.setMaxResults(1);
    Currency currency = (Currency) criteria.uniqueResult();
    if (currency == null) {
      throw new Exception("Currency not found for ISO code: " + isoCode);
    }
    return currency.getId();
  }
}
