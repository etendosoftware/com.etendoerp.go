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

import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Shared DAL and execution-context scaffolding for the onboarding provisioning services.
 *
 * <p>Every onboarding service runs the same way: it captures the current {@link OBContext}, switches
 * to the target admin user/role/client/org, optionally enters admin mode, mutates the DAL, flushes,
 * then restores the previous context. Each service used to carry its own identical copy of this
 * boilerplate; centralizing it here keeps the per-service classes focused on their provisioning
 * logic and removes the duplicated block. The methods stay {@code protected} so tests can still
 * override them as seams.
 */
public abstract class OnboardingContextSupport {

  /**
   * Human-readable subject for {@code "Missing <field> for <subject>"} validation messages
   * (e.g. {@code "accounting wiring"}).
   */
  protected abstract String contextSubject();

  protected Organization resolveOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }

  protected void flushChanges() {
    OBDal.getInstance().flush();
  }

  protected OBContext captureCurrentContext() {
    return OBContext.getOBContext();
  }

  protected void applyExecutionContext(String adminUserId, String adminRoleId,
      String clientId, String orgId) {
    OBContext.setOBContext(adminUserId, adminRoleId, clientId, orgId);
  }

  protected void restoreExecutionContext(OBContext previousContext) {
    OBContext.setOBContext(previousContext);
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  protected void validateContext(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    requirePresent(clientId, "client");
    requirePresent(orgId, "organization");
    requirePresent(adminUserId, "admin user");
    requirePresent(adminRoleId, "admin role");
  }

  private void requirePresent(String value, String label) {
    if (value == null || value.isEmpty()) {
      throw new OBException("Missing " + label + " for " + contextSubject());
    }
  }
}
