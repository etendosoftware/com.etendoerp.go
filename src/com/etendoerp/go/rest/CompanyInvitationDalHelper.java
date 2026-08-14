/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * *************************************************************************
 */

package com.etendoerp.go.rest;

import java.util.List;
import java.util.Locale;

import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Invitation;

/** DAO boundary for company invitation persistence and tenant filtering. */
final class CompanyInvitationDalHelper {

  private CompanyInvitationDalHelper() {
  }

  static Invitation findInvitationByTokenHash(String tokenHash) {
    OBQuery<Invitation> query = OBDal.getInstance().createQuery(Invitation.class,
        "as i where i.tokenHash = :tokenHash and i.active = true");
    query.setNamedParameter("tokenHash", tokenHash);
    disableTenantFilters(query);
    return query.uniqueResult();
  }

  static List<Invitation> findInvitationsForEmail(String email) {
    OBQuery<Invitation> query = OBDal.getInstance().createQuery(Invitation.class,
        "as i where lower(i.email) = :email and i.active = true order by i.creationDate desc");
    query.setNamedParameter("email", email.toLowerCase(Locale.ROOT));
    disableTenantFilters(query);
    return query.list();
  }

  static Invitation findOpenInvitation(String clientId, String email) {
    OBQuery<Invitation> query = OBDal.getInstance().createQuery(Invitation.class,
        "as i where i.client.id = :clientId and lower(i.email) = :email "
            + "and i.active = true and i.status in ('PENDING', 'SENT')");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("email", email.toLowerCase(Locale.ROOT));
    disableTenantFilters(query);
    query.setMaxResult(1);
    List<Invitation> list = query.list();
    return list.isEmpty() ? null : list.get(0);
  }

  static User findUserForClientEmail(Client client, String email) {
    OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
        "as u where u.client = :client and (lower(u.email) = :email "
            + "or lower(u.username) = :email) and u.active = true");
    query.setNamedParameter("client", client);
    query.setNamedParameter("email", email.toLowerCase(Locale.ROOT));
    disableTenantFilters(query);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  static boolean hasActiveRoleForOrganization(User user, Organization organization) {
    OBQuery<UserRoles> query = OBDal.getInstance().createQuery(UserRoles.class,
        "as ur where ur.userContact = :user and ur.organization = :organization "
            + "and ur.active = true and ur.role.active = true");
    query.setNamedParameter("user", user);
    query.setNamedParameter("organization", organization);
    disableTenantFilters(query);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  private static void disableTenantFilters(OBQuery<?> query) {
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
  }
}
