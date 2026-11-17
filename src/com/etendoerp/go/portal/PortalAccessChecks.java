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

package com.etendoerp.go.portal;

import java.util.Optional;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;

import com.etendoerp.go.payment.EnvironmentAccessGuard;

/**
 * The three conditions that close the portal for a customer (ETP-6209, story item 5 and decision
 * D-1): the contact is blocked as a customer, the contact is inactive, or the issuing company's
 * plan has expired.
 *
 * <p>Applied twice: before a renewal email is sent, and on every request made with a link that is
 * still within its lifetime (D-1: a blocked customer keeps no access for the rest of the 48 hours).
 *
 * <p>The reason is returned for the log line and the KPI event only. The customer is never told
 * which of the three it was: the company's billing state and its internal blocking of the customer
 * are not the customer's business.
 */
class PortalAccessChecks {

  /** {@code errorCode} of a customer blocked by {@code C_BPartner.Customer_Blocking}. */
  static final String BLOCKED_CUSTOMER = "BLOCKED_CUSTOMER";
  /** {@code errorCode} of an inactive Business Partner. */
  static final String INACTIVE = "INACTIVE";
  /** {@code errorCode} of a tenant whose commercial access is denied. */
  static final String PLAN_EXPIRED = "PLAN_EXPIRED";

  private static final String ENTRY_POINT = "portal";
  private static final Logger log = LogManager.getLogger(PortalAccessChecks.class);

  private final Supplier<EnvironmentAccessGuard> environmentAccessGuard;

  PortalAccessChecks() {
    this(EnvironmentAccessGuard::new);
  }

  PortalAccessChecks(Supplier<EnvironmentAccessGuard> environmentAccessGuard) {
    this.environmentAccessGuard = environmentAccessGuard;
  }

  /**
   * Says whether the portal is closed for this customer, and why.
   *
   * <p>The caller runs in admin mode: {@code OBDal#get} by id then reads the Business Partner
   * whether it is active or not, which is exactly what the inactive check needs.
   *
   * @param clientId the issuing tenant
   * @param businessPartnerId the customer
   * @return the reason code, or empty when the portal is open
   */
  Optional<String> blockReason(String clientId, String businessPartnerId) {
    BusinessPartner businessPartner = OBDal.getInstance()
        .get(BusinessPartner.class, businessPartnerId);
    if (businessPartner == null || !Boolean.TRUE.equals(businessPartner.isActive())) {
      return Optional.of(INACTIVE);
    }
    // C_BPartner.Customer_Blocking, the switch the Contacts window edits. Only the master flag
    // counts: C_BPARTNER_CUSBLOCKING_CHK guarantees it implies at least one per-flow flag.
    if (Boolean.TRUE.equals(businessPartner.isCustomerBlocking())) {
      return Optional.of(BLOCKED_CUSTOMER);
    }
    // The same commercial check every tenant entry point runs, enforcement kill switch included.
    // A tenant that predates lifecycle metadata answers null and is allowed, as everywhere else.
    if (environmentAccessGuard.get().checkAsSystem(clientId, ENTRY_POINT) != null) {
      return Optional.of(PLAN_EXPIRED);
    }
    return Optional.empty();
  }

  /**
   * {@link #blockReason} that fails closed: an infrastructure error reading any of the three
   * answers "closed" rather than leaving a customer's data reachable on an unanswered question.
   */
  Optional<String> blockReasonOrClosed(String clientId, String businessPartnerId) {
    try {
      return blockReason(clientId, businessPartnerId);
    } catch (RuntimeException e) {
      log.error("Could not evaluate portal access for business partner {} of client {}",
          businessPartnerId, clientId, e);
      return Optional.of(INACTIVE);
    }
  }
}
