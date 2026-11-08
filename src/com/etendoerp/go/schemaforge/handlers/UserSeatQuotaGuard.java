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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.handlers;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.NeoQuotaWriteGate;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.usage.quota.QuotaEvaluation;
import com.etendoerp.go.usage.quota.SeatQuotaGuard;

/**
 * ETP-5051 — seat-quota guards of {@link UserRoleAssignmentHandler}, kept apart so the handler
 * stays small. Stateless; every method returns the NEO write path's 403 {@code QUOTA_EXCEEDED}
 * on a refusal, its 500 {@code QUOTA_CHECK_FAILED} when the check itself failed (fail closed), or
 * {@code null} to continue.
 */
final class UserSeatQuotaGuard {

  private static final Logger log = LogManager.getLogger(UserSeatQuotaGuard.class);

  private static final String FIELD_ACTIVE_FLAG = "active";

  private UserSeatQuotaGuard() {
  }

  /**
   * Refuses a new seat when the tenant already uses every seat of its plan (see
   * {@link SeatQuotaGuard}). Fails closed.
   *
   * @param client the tenant the user is created in, may be null (then nothing is checked)
   * @return a 403/500 response, or {@code null} to continue
   */
  static NeoResponse rejectSeatOverQuota(Client client) {
    if (client == null) {
      return null;
    }
    try {
      Optional<QuotaEvaluation> refusal = SeatQuotaGuard.refusalForNewSeat(client.getId());
      return refusal.map(NeoQuotaWriteGate::refusalResponse).orElse(null);
    } catch (Exception e) {
      log.error("UserSeatQuotaGuard.rejectSeatOverQuota error for client {}: {}",
          client.getId(), e.getMessage(), e);
      return NeoQuotaWriteGate.checkFailedResponse();
    }
  }

  /**
   * Reactivating an inactive user that is a seat once active takes that seat back, so it is
   * checked as a new one (see {@link SeatQuotaGuard#refusalForReactivation}); any other
   * reactivation passes. Fails closed, loading the target included, as the create path does.
   *
   * @param requestBody the {@code PUT}/{@code PATCH} body
   * @param userId      the target {@code AD_User_ID}
   * @return a 403/500 response, or {@code null} to continue
   */
  static NeoResponse rejectReactivationOverQuota(JSONObject requestBody, String userId) {
    if (!isExplicitlyActivating(requestBody)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      User target = OBDal.getInstance().get(User.class, userId);
      if (target == null || Boolean.TRUE.equals(target.isActive())
          || target.getClient() == null) {
        return null;
      }
      Optional<QuotaEvaluation> refusal = SeatQuotaGuard.refusalForReactivation(
          target.getClient().getId(), userId);
      return refusal.map(NeoQuotaWriteGate::refusalResponse).orElse(null);
    } catch (Exception e) {
      log.error("UserSeatQuotaGuard.rejectReactivationOverQuota error for user {}: {}",
          userId, e.getMessage(), e);
      return NeoQuotaWriteGate.checkFailedResponse();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static boolean isExplicitlyActivating(JSONObject body) {
    if (body == null || !body.has(FIELD_ACTIVE_FLAG)) {
      return false;
    }
    Object value = body.opt(FIELD_ACTIVE_FLAG);
    return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value))
        || "Y".equalsIgnoreCase(String.valueOf(value));
  }
}
