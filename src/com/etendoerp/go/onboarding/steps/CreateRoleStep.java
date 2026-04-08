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

package com.etendoerp.go.onboarding.steps;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Enables WebService on existing roles (created by InitialClientSetup/InitialOrgSetup)
 * and grants window/process access for all active NEO-configured specs.
 */
public class CreateRoleStep implements OnboardingStep {

  @Override
  public String name() {
    return "createRole";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    OBContext.setAdminMode(true);
    try {
      Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
      if (client == null) {
        throw new OBException("Client not found with ID: " + ctx.getClientId());
      }
      Organization orgZero = OBDal.getInstance().get(Organization.class, "0");

      // 1. Enable WebService on all roles for this client
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE ad_role SET iswebserviceenabled = 'Y' WHERE ad_client_id = ?")) {
        ps.setString(1, ctx.getClientId());
        ps.executeUpdate();
      }

      // 2. Get the first role (admin role created by InitialClientSetup)
      String roleId = null;
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT ad_role_id FROM ad_role WHERE ad_client_id = ? ORDER BY created LIMIT 1")) {
        ps.setString(1, ctx.getClientId());
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            roleId = rs.getString(1);
          }
        }
      }
      if (roleId == null) {
        throw new OBException("No role found for client " + ctx.getClientId());
      }
      ctx.setRoleId(roleId);

      Role role = OBDal.getInstance().get(Role.class, roleId);
      if (role == null) {
        throw new OBException("Role not found with ID: " + roleId);
      }

      // 3. Get existing window access for this role (to avoid duplicates)
      Set<String> existingWindowIds = new HashSet<>();
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT ad_window_id FROM ad_window_access WHERE ad_role_id = ?")) {
        ps.setString(1, roleId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            existingWindowIds.add(rs.getString(1));
          }
        }
      }

      // 4. Grant WindowAccess for NEO window specs not already granted
      OBCriteria<SFSpec> windowSpecs = OBDal.getInstance().createCriteria(SFSpec.class);
      windowSpecs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
      windowSpecs.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, "W"));
      windowSpecs.setFilterOnReadableClients(false);
      List<SFSpec> windowSpecList = windowSpecs.list();
      for (SFSpec spec : windowSpecList) {
        if (spec.getADWindow() != null && !existingWindowIds.contains(spec.getADWindow().getId())) {
          WindowAccess wa = OBProvider.getInstance().get(WindowAccess.class);
          wa.setNewOBObject(true);
          wa.setClient(client);
          wa.setOrganization(orgZero);
          wa.setRole(role);
          wa.setWindow(spec.getADWindow());
          wa.setEditableField(true);
          OBDal.getInstance().save(wa);
        }
      }

      // 5. Get existing process access (to avoid duplicates)
      Set<String> existingProcessIds = new HashSet<>();
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT ad_process_id FROM ad_process_access WHERE ad_role_id = ?")) {
        ps.setString(1, roleId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            existingProcessIds.add(rs.getString(1));
          }
        }
      }

      // 6. Grant ProcessAccess for NEO process specs not already granted
      OBCriteria<SFSpec> processSpecs = OBDal.getInstance().createCriteria(SFSpec.class);
      processSpecs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
      processSpecs.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, "P"));
      processSpecs.setFilterOnReadableClients(false);
      List<SFSpec> processSpecList = processSpecs.list();
      for (SFSpec spec : processSpecList) {
        if (spec.getProcess() != null && !existingProcessIds.contains(spec.getProcess().getId())) {
          ProcessAccess pa = OBProvider.getInstance().get(ProcessAccess.class);
          pa.setNewOBObject(true);
          pa.setClient(client);
          pa.setOrganization(orgZero);
          pa.setRole(role);
          pa.setProcess(spec.getProcess());
          OBDal.getInstance().save(pa);
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
