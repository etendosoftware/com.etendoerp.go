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
import java.sql.SQLException;
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
import com.etendoerp.go.onboarding.OnboardingStepException;
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
  public void execute(OnboardingContext ctx) throws OnboardingStepException {
    try {
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

        // 3-4. Grant WindowAccess for NEO window specs
        grantWindowAccess(conn, client, orgZero, role);

        // 5-6. Grant ProcessAccess for NEO process specs
        grantProcessAccess(conn, client, orgZero, role);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      throw new OnboardingStepException(e.getMessage(), e);
    }
  }

  private void grantWindowAccess(Connection conn, Client client,
      Organization orgZero, Role role) throws SQLException {
    Set<String> existing = queryExistingIds(conn,
        "SELECT ad_window_id FROM ad_window_access WHERE ad_role_id = ?", role.getId());

    OBCriteria<SFSpec> specs = OBDal.getInstance().createCriteria(SFSpec.class);
    specs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    specs.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, "W"));
    specs.setFilterOnReadableClients(false);
    for (SFSpec spec : specs.list()) {
      if (spec.getADWindow() != null && !existing.contains(spec.getADWindow().getId())) {
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
  }

  private void grantProcessAccess(Connection conn, Client client,
      Organization orgZero, Role role) throws SQLException {
    Set<String> existing = queryExistingIds(conn,
        "SELECT ad_process_id FROM ad_process_access WHERE ad_role_id = ?", role.getId());

    OBCriteria<SFSpec> specs = OBDal.getInstance().createCriteria(SFSpec.class);
    specs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    specs.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, "P"));
    specs.setFilterOnReadableClients(false);
    for (SFSpec spec : specs.list()) {
      if (spec.getProcess() != null && !existing.contains(spec.getProcess().getId())) {
        ProcessAccess pa = OBProvider.getInstance().get(ProcessAccess.class);
        pa.setNewOBObject(true);
        pa.setClient(client);
        pa.setOrganization(orgZero);
        pa.setRole(role);
        pa.setProcess(spec.getProcess());
        OBDal.getInstance().save(pa);
      }
    }
  }

  private Set<String> queryExistingIds(Connection conn, String sql, String roleId)
      throws SQLException {
    Set<String> ids = new HashSet<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, roleId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    }
    return ids;
  }
}
