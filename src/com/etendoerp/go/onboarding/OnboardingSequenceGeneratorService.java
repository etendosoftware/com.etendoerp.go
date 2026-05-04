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

import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.sequences.SequencesGenerator;

/**
 * Generates transactional sequences for an onboarding organization using the same business action
 * exposed by the classic Create Sequences process.
 */
public class OnboardingSequenceGeneratorService {

  /**
   * Runs the sequence generator for the onboarding organization and its child tree.
   *
   * @param clientId
   *     client that owns the organization
   * @param orgId
   *     root organization that receives generated sequences
   * @param adminUserId
   *     administrator user used to execute the process
   * @param adminRoleId
   *     administrator role used to execute the process
   * @return number of generated sequence combinations
   * @throws Exception
   *     when the underlying sequence generator cannot complete
   */
  public int generateSequences(String clientId, String orgId, String adminUserId, String adminRoleId)
      throws Exception {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    Object previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      flushChanges();
      Client client = resolveClient(clientId);
      if (client == null) {
        throw new OBException("Client not found for onboarding sequence generation: " + clientId);
      }
      Set<String> organizations = resolveOrganizations(orgId);
      JSONObject parameters = new JSONObject();
      parameters.put("ad_org_id", orgId);

      enterAdminMode();
      try {
        SequencesGenerator generator = createSequencesGenerator();
        return generateSequenceCombination(generator, client, organizations, parameters);
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected Object captureCurrentContext() {
    return OBContext.getOBContext();
  }

  protected void applyExecutionContext(String adminUserId, String adminRoleId, String clientId,
      String orgId) {
    OBContext.setOBContext(adminUserId, adminRoleId, clientId, orgId);
  }

  protected void restoreExecutionContext(Object previousContext) {
    OBContext.setOBContext((OBContext) previousContext);
  }

  protected void flushChanges() {
    OBDal.getInstance().flush();
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  protected Client resolveClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  protected Set<String> resolveOrganizations(String orgId) {
    return new OrganizationStructureProvider().getChildTree(orgId, true);
  }

  protected SequencesGenerator createSequencesGenerator() {
    return new SequencesGenerator();
  }

  protected int generateSequenceCombination(SequencesGenerator generator, Client client,
      Set<String> organizations, JSONObject parameters) throws Exception {
    return generator.generateSequenceCombination(client, organizations, parameters);
  }

  private void validateContext(String clientId, String orgId, String adminUserId, String adminRoleId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException("Missing client for onboarding sequence generation");
    }
    if (orgId == null || orgId.isEmpty()) {
      throw new OBException("Missing organization for onboarding sequence generation");
    }
    if (adminUserId == null || adminUserId.isEmpty()) {
      throw new OBException("Missing admin user for onboarding sequence generation");
    }
    if (adminRoleId == null || adminRoleId.isEmpty()) {
      throw new OBException("Missing admin role for onboarding sequence generation");
    }
  }
}
