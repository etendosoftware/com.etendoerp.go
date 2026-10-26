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

package com.etendoerp.go.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * DAL-backed {@link DemoTrialNoticeSource} (ETP-5453).
 *
 * <p>Everything runs in admin mode: the job runs as System and the lifecycle markers are
 * {@code AD_Preference} rows of every tenant.</p>
 *
 * <p>The recipient is the platform account behind the environment's owner ({@code
 * EM_ETGO_Is_Owner}), found through the owner's username the same way the rest of the module
 * links an environment user to its account. The language is the environment's own
 * ({@code AD_Client.AD_Language}); when it is unset the email layer falls back to Spanish.</p>
 */
public class DalDemoTrialNoticeSource implements DemoTrialNoticeSource {

  private static final Logger log = LogManager.getLogger(DalDemoTrialNoticeSource.class);
  private static final String SYSTEM_CLIENT = "0";

  /**
   * Both markers that make a tenant a demo in the policy's eyes: a demo created by onboarding, and
   * a historical free tenant under the controlled legacy transition. Either one is cut off when
   * its trial ends, so either one is owed the warning.
   */
  private static final String CANDIDATES_HQL = "select distinct pref.client.id from "
      + Preference.ENTITY_NAME + " pref"
      + " where pref.attribute in (:attributes)"
      + " and pref.active = true"
      + " and pref.client.active = true"
      + " and pref.client.id <> :systemClient";

  private final TenantEnvironmentLifecycleService lifecycleService;
  private final Supplier<DemoTrialNoticePlanner> planner;

  /**
   * Creates a source with the instance's lifecycle and reminder thresholds. The thresholds are
   * read on every lookup, not here: the email contract registry builds this source eagerly,
   * including in tests that never reach it, and a changed threshold applies without a restart.
   */
  public DalDemoTrialNoticeSource() {
    this(new TenantEnvironmentLifecycleService(), DemoTrialNoticePlanner::fromConfiguration);
  }

  DalDemoTrialNoticeSource(TenantEnvironmentLifecycleService lifecycleService,
      Supplier<DemoTrialNoticePlanner> planner) {
    this.lifecycleService = lifecycleService;
    this.planner = planner;
  }

  @Override
  public List<String> findCandidateClientIds() {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().getSession().createQuery(CANDIDATES_HQL, String.class)
          .setParameterList("attributes", List.of(
              TenantEnvironmentLifecycleService.DEMO_TRIAL_STARTED_ATTRIBUTE,
              TenantEnvironmentLifecycleService.LEGACY_TRANSITION_STARTED_ATTRIBUTE))
          .setParameter("systemClient", SYSTEM_CLIENT)
          .getResultList();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public Optional<DemoTrialNoticeRecord> findOwedNotice(String clientId, Instant now) {
    if (StringUtils.isBlank(clientId) || SYSTEM_CLIENT.equals(clientId)) {
      return Optional.empty();
    }
    OBContext.setAdminMode(true);
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null || !Boolean.TRUE.equals(client.isActive())) {
        return Optional.empty();
      }
      Optional<DemoTrialNotice> notice = planner.get().plan(lifecycleService.resolve(clientId), now,
          lifecycleService.configuration());
      if (!notice.isPresent()) {
        return Optional.empty();
      }
      Optional<Account> account = findOwnerAccount(clientId);
      if (!account.isPresent()) {
        log.warn("Demo trial notice {} owed by client {} has no deliverable owner account",
            notice.get().milestone(), clientId);
        return Optional.empty();
      }
      String language = client.getLanguage() == null ? null
          : StringUtils.trimToNull(client.getLanguage().getLanguage());
      return Optional.of(new DemoTrialNoticeRecord(clientId, client.getName(), language,
          account.get().getName(), account.get().getEmail(), notice.get()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Optional<Account> findOwnerAccount(String clientId) {
    String ownerUserId = OwnerSupport.findOwnerUserId(clientId);
    if (ownerUserId == null) {
      return Optional.empty();
    }
    User owner = OBDal.getInstance().get(User.class, ownerUserId);
    if (owner == null || !Boolean.TRUE.equals(owner.isActive())) {
      return Optional.empty();
    }
    return GoAccountResolver.findAccountByUsername(owner.getUsername());
  }
}
