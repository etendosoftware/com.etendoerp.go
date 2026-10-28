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
package com.etendoerp.go.onboarding;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.handlers.ContactTypeRules;

/**
 * ETP-5791 — makes "Cliente/Proveedor" the default contact category of a new tenant, only while
 * the {@code contact-category-type} feature flag is on.
 *
 * <p><b>Why a step and not the dataset.</b> The GOClient dataset keeps "Cliente" as the default
 * category, exactly as before the ticket, so a tenant created with the flag off is identical to
 * one created before it. The switch is applied here, after the onboarding commit, on the one path
 * both a classic tenant and a tenant claimed from the pool (ETP-5389) go through: the flag is read
 * when the tenant is handed to the user, never when the pool was filled, so a tenant built with the
 * flag off and claimed with it on still gets the new default.</p>
 *
 * <p><b>How the categories are found.</b> By the GOClient record id through the import's
 * {@code AD_REF_DATA_LOADED} mapping (generic id → the tenant's id), falling back to the category's
 * {@code Value} within the tenant; never by display name. A tenant without the target category (a
 * pool tenant built from an older dataset, a renamed or deleted category) is logged and skipped:
 * it keeps the default it has.</p>
 *
 * <p><b>Idempotent.</b> Only rows whose flag actually changes are written, so a second run is a
 * no-op. Every other default category of the tenant is un-defaulted, since the contact's AD default
 * picks {@code MAX(c_bp_group_id)} among the defaults and two would make the answer arbitrary.</p>
 *
 * <p>Tenants created while the flag was off are never revisited: turning it on later leaves their
 * "Cliente" default as is, the same rule the ticket sets for existing data. No data-fix exists or
 * is needed.</p>
 *
 * <p><b>Transaction contract.</b> Neither commits nor rolls back; the caller does, as for the
 * other post-commit onboarding steps.</p>
 */
public class OnboardingDefaultContactCategoryService {

  private static final Logger log = LogManager.getLogger(OnboardingDefaultContactCategoryService.class);

  /** GOClient's "Cliente/Proveedor" category ({@code referencedata/sampledata/GOClient}). */
  static final String SAMPLE_CUSTOMER_VENDOR_CATEGORY_ID = "E075D97EFB264D028E8652E5CA057518";
  /** Its {@code Value}, the fallback key when the import mapping is missing. */
  static final String CUSTOMER_VENDOR_CATEGORY_VALUE = "Cliente/Proveedor";

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_GENERIC_ID = "genericId";
  private static final String PARAM_VALUE = "value";
  private static final String PARAM_TARGET_ID = "targetId";

  private static final String BY_IMPORT_MAPPING_SQL =
      "SELECT g.c_bp_group_id FROM ad_ref_data_loaded r"
      + " JOIN c_bp_group g ON g.c_bp_group_id = r.specific_id AND g.ad_client_id = r.ad_client_id"
      + " WHERE r.ad_client_id = :" + PARAM_CLIENT_ID + " AND r.generic_id = :" + PARAM_GENERIC_ID
      + "   AND g.isactive = 'Y'";

  private static final String BY_VALUE_SQL =
      "SELECT c_bp_group_id FROM c_bp_group WHERE ad_client_id = :" + PARAM_CLIENT_ID
      + " AND value = :" + PARAM_VALUE + " AND isactive = 'Y' ORDER BY c_bp_group_id";

  private static final String UNDEFAULT_OTHERS_SQL =
      "UPDATE c_bp_group SET isdefault = 'N', updated = now()"
      + " WHERE ad_client_id = :" + PARAM_CLIENT_ID + " AND isdefault = 'Y'"
      + "   AND c_bp_group_id <> :" + PARAM_TARGET_ID;

  private static final String DEFAULT_TARGET_SQL =
      "UPDATE c_bp_group SET isdefault = 'Y', updated = now()"
      + " WHERE c_bp_group_id = :" + PARAM_TARGET_ID + " AND isdefault <> 'Y'";

  /**
   * Applies the new default when the flag is on.
   *
   * @param clientId the tenant just committed
   * @return {@code true} when a row was written (the caller commits), {@code false} when there was
   *     nothing to do: flag off, target category missing, or already the only default
   */
  public boolean applyIfEnabled(String clientId) {
    if (clientId == null || !isFlagEnabled()) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      String targetId = resolveTargetCategory(clientId);
      if (targetId == null) {
        log.warn("ETP-5791 client {} has no '{}' contact category; its default category is left "
            + "unchanged", clientId, CUSTOMER_VENDOR_CATEGORY_VALUE);
        return false;
      }
      int undefaulted = executeUpdate(UNDEFAULT_OTHERS_SQL, clientId, targetId);
      int defaulted = executeUpdate(DEFAULT_TARGET_SQL, null, targetId);
      if (undefaulted + defaulted > 0) {
        log.info("ETP-5791 client {}: '{}' is now the default contact category ({} other "
            + "default(s) cleared)", clientId, CUSTOMER_VENDOR_CATEGORY_VALUE, undefaulted);
        return true;
      }
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Seam for tests: the flag read, the same environment-level read the contact rules use. */
  protected boolean isFlagEnabled() {
    return ContactTypeRules.isEnabled();
  }

  /** The tenant's "Cliente/Proveedor" category: by import mapping, else by value. */
  protected String resolveTargetCategory(String clientId) {
    String byMapping = firstId(OBDal.getInstance().getSession()
        .createNativeQuery(BY_IMPORT_MAPPING_SQL)
        .setParameter(PARAM_CLIENT_ID, clientId)
        .setParameter(PARAM_GENERIC_ID, SAMPLE_CUSTOMER_VENDOR_CATEGORY_ID)
        .getResultList());
    if (byMapping != null) {
      return byMapping;
    }
    return firstId(OBDal.getInstance().getSession()
        .createNativeQuery(BY_VALUE_SQL)
        .setParameter(PARAM_CLIENT_ID, clientId)
        .setParameter(PARAM_VALUE, CUSTOMER_VENDOR_CATEGORY_VALUE)
        .getResultList());
  }

  protected int executeUpdate(String sql, String clientId, String targetId) {
    var query = OBDal.getInstance().getSession().createNativeQuery(sql)
        .setParameter(PARAM_TARGET_ID, targetId);
    if (clientId != null) {
      query.setParameter(PARAM_CLIENT_ID, clientId);
    }
    return query.executeUpdate();
  }

  private static String firstId(List<?> rows) {
    if (rows == null || rows.isEmpty() || rows.get(0) == null) {
      return null;
    }
    return rows.get(0).toString();
  }
}
