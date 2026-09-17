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

package com.etendoerp.go.usage;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;
import org.openbravo.model.ad.domain.Preference;

/**
 * The settling window, read from an {@code AD_Preference}.
 *
 * <p>A day inside the window is recomputed on every run; once the window has elapsed the day
 * is final and is never recomputed, so a reported value is never revised downward.
 *
 * <p><b>This is a LIST preference.</b> The key is registered as an {@code AD_Ref_List} value
 * of the {@code Property Configuration} reference, so Openbravo stores it in
 * {@code AD_Preference.Property} and every read must pass {@code isListProperty = true}. The
 * flag and the registration must not drift apart: a list key read with {@code false} looks in
 * {@code AD_Preference.Attribute}, finds nothing, and silently returns the default — the
 * window would appear to be 5 everywhere with nothing reporting it. {@code TenantPlanService}
 * documents the same trap from the other side, where the key is deliberately NOT a list.
 *
 * <p>Because it is a preference it can be set per tenant, which is why
 * {@link #getSettlingWindowDays(String)} takes a client: the settled flag on each usage row
 * must honour that tenant's own window. The scheduled run, in contrast, must iterate
 * {@link #getMaxSettlingWindowDays()} days — see that method for why.
 */
public final class UsageSettings {

  private static final Logger log = LogManager.getLogger(UsageSettings.class);

  /** AD_Ref_List value registered against the {@code Property Configuration} reference. */
  public static final String PREFERENCE_PROPERTY = "ETGO_UsageSettlingWindowDays";

  /** Applied when no preference is set anywhere. */
  public static final int DEFAULT_SETTLING_WINDOW_DAYS = 5;

  private static final String SYSTEM_CLIENT = "0";
  private static final String ORG_ZERO = "0";

  private UsageSettings() {
  }

  /**
   * The window that applies to one tenant, falling back to the system-level preference and
   * then to the default.
   *
   * @param clientId the tenant whose usage rows are being flagged settled
   * @return the settling window in days, falling back to the default when unset or unreadable
   */
  public static int getSettlingWindowDays(String clientId) {
    try {
      String raw = Preferences.getPreferenceValue(PREFERENCE_PROPERTY, true,
          StringUtils.isBlank(clientId) ? SYSTEM_CLIENT : clientId, ORG_ZERO, null, null, null);
      return parse(raw, clientId);
    } catch (PropertyNotFoundException e) {
      return DEFAULT_SETTLING_WINDOW_DAYS;
    } catch (PropertyException e) {
      log.warn("Could not read {} for client {}: {}", PREFERENCE_PROPERTY, clientId,
          e.getMessage());
      return DEFAULT_SETTLING_WINDOW_DAYS;
    }
  }

  /** The system-level window, used when no tenant is in play. */
  public static int getSettlingWindowDays() {
    return getSettlingWindowDays(SYSTEM_CLIENT);
  }

  /**
   * The largest window configured anywhere, which is how many days the scheduled run must
   * iterate.
   *
   * <p>The run counts every tenant in one grouped query per day, so it cannot use a
   * per-tenant range. If it iterated only the system window while some tenant were configured
   * longer, that tenant's older days would be flagged unsettled — correctly, by its own
   * window — yet never recomputed again, freezing a value that was still supposed to change.
   * Taking the maximum costs extra days for everyone and is the safe direction: a day that is
   * already final for a tenant is sealed rather than recomputed, because
   * {@link UsageDayRange#isFinal} is evaluated per tenant when the row is written.
   */
  public static int getMaxSettlingWindowDays() {
    int max = getSettlingWindowDays();
    for (Preference preference : configuredPreferences()) {
      String clientId =
          preference.getVisibleAtClient() != null ? preference.getVisibleAtClient().getId() : null;
      int value = parse(preference.getSearchKey(), clientId);
      if (value > max) {
        max = value;
      }
    }
    return max;
  }

  private static List<Preference> configuredPreferences() {
    OBCriteria<Preference> criteria = OBDal.getInstance().createCriteria(Preference.class);
    criteria.add(Restrictions.eq(Preference.PROPERTY_PROPERTY, PREFERENCE_PROPERTY));
    criteria.add(Restrictions.eq(Preference.PROPERTY_ACTIVE, true));
    // The key is registered in AD_Ref_List, so only list-style rows are ours. Without this an
    // attribute-style row carrying the same text would be read as a window value, which is the
    // one place the list/attribute distinction this class documents could otherwise leak.
    criteria.add(Restrictions.eq(Preference.PROPERTY_PROPERTYLIST, true));
    // System-level code reading preferences that belong to other tenants: the readable-client
    // and readable-organisation filters must be off or the scan sees only its own client and
    // the maximum is wrong.
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.list();
  }

  /** Parses defensively: a job that refuses to start is worse than the documented default. */
  private static int parse(String raw, String clientId) {
    if (StringUtils.isBlank(raw)) {
      return DEFAULT_SETTLING_WINDOW_DAYS;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed < 0) {
        log.warn("{} is negative ({}) for client {}; using {}", PREFERENCE_PROPERTY, parsed,
            clientId, DEFAULT_SETTLING_WINDOW_DAYS);
        return DEFAULT_SETTLING_WINDOW_DAYS;
      }
      return parsed;
    } catch (NumberFormatException e) {
      log.warn("{} is not a number ('{}') for client {}; using {}", PREFERENCE_PROPERTY, raw,
          clientId, DEFAULT_SETTLING_WINDOW_DAYS);
      return DEFAULT_SETTLING_WINDOW_DAYS;
    }
  }
}
