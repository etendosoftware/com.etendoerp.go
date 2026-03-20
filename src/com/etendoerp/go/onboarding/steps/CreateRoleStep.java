package com.etendoerp.go.onboarding.steps;

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStep;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Creates the default role for the new client/org, grants WebService access,
 * links both admin users, and provisions window/process access for all
 * active NEO-configured specs (ETGO_SF_SPEC).
 *
 * <p>Role is created at org "0" (system level) following the SaaS module pattern.
 * RoleOrganization entries are added for both org "0" and the new org so that
 * the role can access data at both levels.
 */
public class CreateRoleStep implements OnboardingStep {

  @Override
  public String name() {
    return "createRole";
  }

  @Override
  public void execute(OnboardingContext ctx) throws Exception {
    Client client = OBDal.getInstance().get(Client.class, ctx.getClientId());
    Organization orgZero = OBDal.getInstance().get(Organization.class, "0");
    Organization org = OBDal.getInstance().get(Organization.class, ctx.getOrgId());

    // 1. Create role scoped to client, org "0"
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(client);
    role.setOrganization(orgZero);
    role.setName(ctx.getClientName() + " Default Role");
    role.setUserLevel("  O");
    role.setWebServiceEnabled(true);
    role.setManual(false);
    OBDal.getInstance().save(role);

    // 2. Grant access to org "0" and the new org
    createRoleOrganization(role, client, orgZero);
    createRoleOrganization(role, client, org);

    // 3. Link client admin user
    User clientAdmin = OBDal.getInstance().get(User.class, ctx.getClientAdminUserId());
    linkUserToRole(clientAdmin, role, client, orgZero);

    // 4. Link org admin user
    User orgAdmin = OBDal.getInstance().get(User.class, ctx.getOrgAdminUserId());
    linkUserToRole(orgAdmin, role, client, orgZero);

    // 5. Grant WindowAccess for each active window spec (specType='W')
    OBCriteria<SFSpec> windowSpecs = OBDal.getInstance().createCriteria(SFSpec.class);
    windowSpecs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    windowSpecs.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, "W"));
    List<SFSpec> windowSpecList = windowSpecs.list();
    for (SFSpec spec : windowSpecList) {
      if (spec.getADWindow() != null) {
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

    // 6. Grant ProcessAccess for each active process spec (specType='P')
    OBCriteria<SFSpec> processSpecs = OBDal.getInstance().createCriteria(SFSpec.class);
    processSpecs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    processSpecs.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, "P"));
    List<SFSpec> processSpecList = processSpecs.list();
    for (SFSpec spec : processSpecList) {
      if (spec.getProcess() != null) {
        ProcessAccess pa = OBProvider.getInstance().get(ProcessAccess.class);
        pa.setNewOBObject(true);
        pa.setClient(client);
        pa.setOrganization(orgZero);
        pa.setRole(role);
        pa.setProcess(spec.getProcess());
        OBDal.getInstance().save(pa);
      }
    }

    ctx.setRoleId(role.getId());
  }

  private void createRoleOrganization(Role role, Client client, Organization org) {
    RoleOrganization ro = OBProvider.getInstance().get(RoleOrganization.class);
    ro.setNewOBObject(true);
    ro.setClient(client);
    ro.setOrganization(org);
    ro.setRole(role);
    OBDal.getInstance().save(ro);
  }

  private void linkUserToRole(User user, Role role, Client client, Organization orgZero) {
    UserRoles ur = OBProvider.getInstance().get(UserRoles.class);
    ur.setNewOBObject(true);
    ur.setClient(client);
    ur.setOrganization(orgZero);
    ur.setUserContact(user);
    ur.setRole(role);
    ur.setRoleAdmin(true);
    OBDal.getInstance().save(ur);

    if (user.getDefaultRole() == null) {
      user.setDefaultRole(role);
      OBDal.getInstance().save(user);
    }
  }
}
