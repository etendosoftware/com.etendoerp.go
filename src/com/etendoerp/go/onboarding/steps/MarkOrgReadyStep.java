package com.etendoerp.go.onboarding.steps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.reference.PInstanceProcessData;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessRunner;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;

/**
 * Finalizes the organization by:
 * 1. Executing the ORG_AS_READY process (resolved by search key "AD_Org_Ready", never hardcoded ID)
 * 2. Setting org.ready = true via OBDal (defensive fallback for DAL state)
 * 3. Setting the default language on both admin users
 *
 * Process execution uses DalConnectionProvider + VariablesSecureApp so no servlet context is needed.
 */
public class MarkOrgReadyStep implements OnboardingStep {

  private static final Logger log = LogManager.getLogger();

  /** Search key of the "Set as Ready" process in AD_PROCESS.VALUE. Never hardcode the ID. */
  private static final String ORG_READY_PROCESS_SEARCH_KEY = "AD_Org_Ready";

  @Override
  public String name() {
    return "markOrgReady";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    String orgId = ctx.getOrgId();
    String clientId = ctx.getClientId();
    String clientAdminUserId = ctx.getClientAdminUserId();
    String orgAdminUserId = ctx.getOrgAdminUserId();

    // 1. Flush pending DAL state before PL/SQL call
    OBDal.getInstance().flush();

    // 2. Execute ORG_AS_READY process via PInstanceProcessData + ProcessRunner
    executeOrgAsReadyProcess(orgId, clientId, clientAdminUserId);

    // 3. Mark org as ready in DAL (defensive — process should have done this, but keeps state consistent)
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    if (org != null && !Boolean.TRUE.equals(org.isReady())) {
      org.setReady(true);
      OBDal.getInstance().save(org);
    }

    // 4. Set default language on admin users
    if (ctx.getLanguageCode() != null) {
      Language language = resolveLanguage(ctx.getLanguageCode());
      if (language != null) {
        setDefaultLanguage(clientAdminUserId, language);
        setDefaultLanguage(orgAdminUserId, language);
      } else {
        log.warn("Language not found for code '{}' — skipping default language assignment",
            ctx.getLanguageCode());
      }
    }

    OBDal.getInstance().flush();
  }

  private void executeOrgAsReadyProcess(String orgId, String clientId, String userId)
      throws Exception {
    // Resolve process by search key — never by hardcoded ID
    Process orgReadyProcess = resolveProcess(ORG_READY_PROCESS_SEARCH_KEY);
    if (orgReadyProcess == null) {
      throw new Exception(
          "ORG_AS_READY process not found by search key '" + ORG_READY_PROCESS_SEARCH_KEY
              + "'. Ensure Etendo core reference data is loaded.");
    }
    String processId = orgReadyProcess.getId();

    DalConnectionProvider conn = new DalConnectionProvider(false);

    // VariablesSecureApp can be constructed without an HttpServletRequest
    String roleId = OBContext.getOBContext().getRole().getId();
    String language = OBContext.getOBContext().getLanguage().getLanguage();
    VariablesSecureApp vars = new VariablesSecureApp(userId, clientId, orgId, roleId, language);

    String pinstanceId = SequenceIdData.getUUID();
    PInstanceProcessData.insertPInstance(conn, pinstanceId, processId, orgId, "Y", userId,
        clientId, orgId);

    ProcessBundle bundle = ProcessBundle.pinstance(pinstanceId, vars, conn);
    new ProcessRunner(bundle).execute(conn);

    log.info("ORG_AS_READY process executed for org '{}' using process id '{}'", orgId, processId);
  }

  private Process resolveProcess(String searchKey) {
    OBCriteria<Process> criteria = OBDal.getInstance().createCriteria(Process.class);
    criteria.add(Restrictions.eq(Process.PROPERTY_SEARCHKEY, searchKey));
    criteria.setMaxResults(1);
    return (Process) criteria.uniqueResult();
  }

  private Language resolveLanguage(String languageCode) {
    OBCriteria<Language> criteria = OBDal.getInstance().createCriteria(Language.class);
    criteria.add(Restrictions.eq(Language.PROPERTY_LANGUAGE, languageCode));
    criteria.setMaxResults(1);
    return (Language) criteria.uniqueResult();
  }

  private void setDefaultLanguage(String userId, Language language) {
    if (userId == null) {
      return;
    }
    User user = OBDal.getInstance().get(User.class, userId);
    if (user != null) {
      user.setDefaultLanguage(language);
      OBDal.getInstance().save(user);
    }
  }
}
