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

package com.etendoerp.go.common;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.onboarding.OnboardingForceTestModeService;

/**
 * Resolves the effective {@code ETSG_ForceTestMode} preference value (owned by
 * {@code com.etendoerp.sif.general}, PROPERTY-shaped, bundled System default row
 * {@code AD_Preference_ID 6DCB1CD4A0414D78BB97441626B62835} at {@code VALUE='N'}) for a
 * given Client: its own active row if it has one, else the System-wide (Client=0) row,
 * else {@code false} (production/real submissions — the safe default).
 *
 * <p>This mirrors {@code com.etendoerp.verifactu.eventhandler.ForceTestModeEventHandler
 * #isForceTestModeActiveForClient}/{@code #findPreference} (and its
 * {@code org.openbravo.module.sii}/{@code com.smf.ticketbai} siblings) byte-for-byte in
 * intent: a plain, unfiltered {@link OBCriteria} query on the row's own
 * {@code AD_Client_ID}, deliberately NOT {@code Preferences.getPreferenceValue()} — that
 * standard precedence engine previously corrupted Hibernate's shared session state when
 * invoked (with an admin-mode bypass) across many different Clients in one request. No
 * admin mode is needed here either: {@code AD_Preference} has access level "All", and
 * {@code setFilterOnReadableClients(false)}/{@code setFilterOnReadableOrganization(false)}
 * already let this read cross into the System row without it.
 *
 * <p>The lookup logic is intentionally duplicated here rather than extracted into a shared
 * class in {@code com.etendoerp.verifactu}/{@code org.openbravo.module.sii}/
 * {@code com.smf.ticketbai} — this servlet-facing consumer lives in {@code com.etendoerp.go}
 * and must not require a code change in any of those three fiscal modules. It DOES reuse
 * {@link OnboardingForceTestModeService#FORCE_TEST_MODE_PROPERTY} — already declared in this
 * same module — instead of re-declaring the {@code "ETSG_ForceTestMode"} literal a third time
 * within {@code com.etendoerp.go}.
 */
public final class FiscalTestModeResolver {

  private static final String YES = "Y";
  private static final String SYSTEM_ID = "0";

  private FiscalTestModeResolver() {
  }

  /**
   * Resolves whether fiscal test mode is effectively forced for the given Client, falling
   * back to the System-wide default row when the Client has no own preference row.
   *
   * @param client the Client to resolve the effective value for. {@code null} resolves
   *               straight to the System-wide default row.
   * @return {@code true} when fiscal test mode is effectively forced for this Client.
   */
  public static boolean isForceTestModeActive(Client client) {
    Preference preference = findPreference(client);

    if (preference == null && client != null && !SYSTEM_ID.equals(client.getId())) {
      preference = findPreference(null);
    }

    return preference != null && StringUtils.equals(preference.getSearchKey(), YES);
  }

  /**
   * @param client the Client to look up a row for, or {@code null}/System (id {@code "0"})
   *               for the System-wide default row.
   */
  private static Preference findPreference(Client client) {
    OBCriteria<Preference> criteria = OBDal.getInstance().createCriteria(Preference.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Preference.PROPERTY_PROPERTY,
        OnboardingForceTestModeService.FORCE_TEST_MODE_PROPERTY));
    criteria.add(Restrictions.eq(Preference.PROPERTY_ACTIVE, true));

    if (client == null) {
      criteria.add(Restrictions.eq(Preference.PROPERTY_CLIENT + ".id", SYSTEM_ID));
    } else {
      criteria.add(Restrictions.eq(Preference.PROPERTY_CLIENT, client));
    }

    // Deterministic tie-break if a Client somehow ends up with more than one active row for
    // this Property: the most recently created one wins, instead of an arbitrary DB-order pick.
    criteria.addOrder(Order.desc(Preference.PROPERTY_CREATIONDATE));
    criteria.setMaxResults(1);

    return (Preference) criteria.uniqueResult();
  }
}
