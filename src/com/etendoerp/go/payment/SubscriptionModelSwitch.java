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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Preference;

/**
 * The one place that answers "is the subscription model on in this environment?".
 *
 * <p><b>What it switches.</b> When ON, a new paid purchase opens an {@code ETGO_SUBSCRIPTION} row
 * (ETP-5046) and retires the tenant's {@code ETGO_TenantPlan} preference. When OFF, the purchase is
 * recorded exactly as before ETP-5046: the {@code ETGO_TenantPlan} preference is written and no row
 * is opened, so the lifecycle webhooks keep projecting that tenant's billing state into the
 * {@code ETGO_SubscriptionStatus} / {@code ETGO_SubscriptionDueAt} preferences. Only the
 * <em>creation</em> of rows is switched. A tenant that already has a row is decided by that row
 * whatever the switch says, so turning the switch OFF again only stops new rows — in practice the
 * switch is one-way.
 *
 * <p><b>Why a switch.</b> Each environment moves to the subscription model at one moment, chosen by
 * its operator, right after running the R37 backfill data-fix for its existing tenants. Until then
 * the whole fleet — new customers included — stays on the preferences, so an environment never runs
 * half its paying tenants on rows and half on preferences without someone having decided it.
 *
 * <p><b>The row it reads.</b> A <em>list</em> preference: the key {@value #PREFERENCE_PROPERTY} is
 * registered as an {@code AD_Ref_List} value of the {@code Property Configuration} reference, so
 * Openbravo stores it in {@code AD_Preference.Property} (with {@code PropertyList = 'Y'}) and the
 * Classic Preference window offers it in its drop-down. The row must be System level:
 * {@code AD_CLIENT_ID = '0'}, active, with no visibility restriction — no client other than
 * {@code '0'}, no organization other than {@code '0'}, no role, no user, no window. A row scoped to
 * anything narrower is not an environment-wide decision and is ignored. The value is ON only when it
 * is exactly {@value #ON}; an absent row, any other value, or a read that fails all mean OFF — the
 * pre-ETP-5046 behaviour, which is the safe direction.
 *
 * <p><b>Read on every call, never cached.</b> It is consulted once per paid purchase, which is rare,
 * so flipping the switch takes effect on the next purchase without a restart.
 *
 * <p><b>Not module data.</b> The module ships the list value only, never a preference row: a row
 * shipped as module sourcedata would be reset to its shipped value by every
 * {@code update.database}, silently turning an environment's decision back. The operator creates
 * the row at runtime, with no module assigned.
 *
 * <p>Read with a plain criteria on the row's own {@code AD_CLIENT_ID}, the same shape as
 * {@code FiscalTestModeResolver}, rather than {@code Preferences.getPreferenceValue}: that engine
 * also returns rows owned by the caller's client and resolves organization trees through the
 * current {@link OBContext}, neither of which an environment-wide switch should depend on.
 */
public class SubscriptionModelSwitch {

  private static final Logger log = LogManager.getLogger(SubscriptionModelSwitch.class);

  /** {@code AD_Ref_List} value registered against the {@code Property Configuration} reference. */
  public static final String PREFERENCE_PROPERTY = "ETGO_SubscriptionModel";

  /** The only value that turns the subscription model on. */
  public static final String ON = "Y";

  private static final String SYSTEM_ID = "0";

  /**
   * Whether a new paid purchase must open an {@code ETGO_SUBSCRIPTION} row in this environment.
   *
   * <p>Never throws: a failed read is logged and answered OFF, which keeps the purchase on the
   * preference path that every environment ran before the switch existed.
   *
   * @return {@code true} only when the System-level {@value #PREFERENCE_PROPERTY} preference is
   *     {@value #ON}
   */
  public boolean isOn() {
    try {
      Preference preference = findSystemPreference();
      return preference != null && ON.equals(StringUtils.trim(preference.getSearchKey()));
    } catch (RuntimeException e) {
      log.error("Could not read the {} preference; treating the subscription model as OFF, so "
          + "new purchases are recorded with the ETGO_TenantPlan preference", PREFERENCE_PROPERTY,
          e);
      return false;
    }
  }

  /**
   * Finds the System-level, unrestricted row. The most recently created one wins if an operator
   * left more than one, so a second row added to flip the switch takes effect.
   */
  private static Preference findSystemPreference() {
    // AD_Preference is readable by everyone, but the paid onboarding calls this from the new
    // tenant's context, whose role is not guaranteed to have the entity in its readable set.
    OBContext.setAdminMode(true);
    try {
      OBCriteria<Preference> criteria = OBDal.getInstance().createCriteria(Preference.class);
      criteria.setFilterOnReadableClients(false);
      criteria.setFilterOnReadableOrganization(false);
      criteria.add(Restrictions.eq(Preference.PROPERTY_PROPERTYLIST, true));
      criteria.add(Restrictions.eq(Preference.PROPERTY_PROPERTY, PREFERENCE_PROPERTY));
      criteria.add(Restrictions.eq(Preference.PROPERTY_ACTIVE, true));
      criteria.add(Restrictions.eq(Preference.PROPERTY_CLIENT + ".id", SYSTEM_ID));
      criteria.add(Restrictions.or(Restrictions.isNull(Preference.PROPERTY_VISIBLEATCLIENT),
          Restrictions.eq(Preference.PROPERTY_VISIBLEATCLIENT + ".id", SYSTEM_ID)));
      criteria.add(Restrictions.or(Restrictions.isNull(Preference.PROPERTY_VISIBLEATORGANIZATION),
          Restrictions.eq(Preference.PROPERTY_VISIBLEATORGANIZATION + ".id", SYSTEM_ID)));
      criteria.add(Restrictions.isNull(Preference.PROPERTY_VISIBLEATROLE));
      criteria.add(Restrictions.isNull(Preference.PROPERTY_USERCONTACT));
      criteria.add(Restrictions.isNull(Preference.PROPERTY_WINDOW));
      criteria.addOrder(Order.desc(Preference.PROPERTY_CREATIONDATE));
      criteria.setMaxResults(1);
      return (Preference) criteria.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
