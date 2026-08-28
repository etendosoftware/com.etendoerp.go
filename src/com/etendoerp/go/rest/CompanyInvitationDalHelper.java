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
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Invitation;

/** DAO boundary for company invitation persistence and tenant filtering. */
final class CompanyInvitationDalHelper {

  private static final String EMAIL_PARAMETER = "email";

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
    query.setNamedParameter(EMAIL_PARAMETER, email.toLowerCase(Locale.ROOT));
    disableTenantFilters(query);
    return query.list();
  }

  static Invitation findOpenInvitation(String clientId, String email) {
    OBQuery<Invitation> query = OBDal.getInstance().createQuery(Invitation.class,
        "as i where i.client.id = :clientId and lower(i.email) = :email "
            + "and i.active = true and i.status in ('PENDING', 'SENT')");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter(EMAIL_PARAMETER, email.toLowerCase(Locale.ROOT));
    disableTenantFilters(query);
    query.setMaxResult(1);
    List<Invitation> list = query.list();
    return list.isEmpty() ? null : list.get(0);
  }

  /**
   * Returns the most recently created invitation for {@code clientId}/{@code email}, regardless
   * of status, or {@code null} if none exists (ETP-4830). Unlike {@link #findOpenInvitation},
   * this is not restricted to {@code PENDING}/{@code SENT} — it backs an {@code invitationStatus}
   * read used to render a UI badge, which must also reflect a terminal state
   * ({@code ACCEPTED}/{@code EXPIRED}/{@code REVOKED}/{@code DELIVERY_FAILED}).
   */
  static Invitation findLatestInvitation(String clientId, String email) {
    OBQuery<Invitation> query = OBDal.getInstance().createQuery(Invitation.class,
        "as i where i.client.id = :clientId and lower(i.email) = :email "
            + "and i.active = true order by i.creationDate desc");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter(EMAIL_PARAMETER, email.toLowerCase(Locale.ROOT));
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
    query.setNamedParameter(EMAIL_PARAMETER, email.toLowerCase(Locale.ROOT));
    disableTenantFilters(query);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * Whether {@code user} has an active role that can actually operate in {@code organization}
   * (ETP-4999 fix). {@code AD_User_Roles.AD_Org_ID} is NOT the right column to check here — core
   * only ever allows that table to hold instances at the root/wildcard organization ({@code '0'};
   * confirmed live: any attempt to persist a different value throws {@code "Entity ADUserRoles
   * may only have instances with organization *"}), so a user's role-ASSIGNMENT row can never
   * reflect which org they operate in. Per-organization access is instead granted at the ROLE
   * level via {@code AD_Role_OrgAccess} ({@link RoleOrganization}) — this checks whether {@code
   * user} has an active {@code AD_User_Roles} row for a role that itself has an active {@code
   * RoleOrganization} grant for {@code organization}. The old exact-match-on-{@code
   * ur.organization} version of this method could never return {@code true} for any non-root
   * organization, for ANY tenant, since that column is always {@code '0'} — this was a
   * pre-existing, always-latent bug, not something introduced by the fix.
   */
  static boolean hasActiveRoleForOrganization(User user, Organization organization) {
    OBQuery<UserRoles> query = OBDal.getInstance().createQuery(UserRoles.class,
        "as ur where ur.userContact = :user and ur.active = true and ur.role.active = true "
            + "and ur.role.id in (select ro.role.id from ADRoleOrganization as ro "
            + "where ro.organization = :organization and ro.active = true)");
    query.setNamedParameter("user", user);
    query.setNamedParameter("organization", organization);
    disableTenantFilters(query);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  /**
   * Resolves the platform account's OWN administrative identity — the single active {@code
   * AD_User} whose {@code username} exactly equals {@code accountEmail} — used by {@code
   * CompanyInvitationService#resolveInviter} to determine which client an explicit {@code
   * POST /sws/go/company-invitations} call (authenticated by a bare platform session token,
   * which carries no client of its own) should act on behalf of (ETP-4999 fix, Mystery #1).
   *
   * <p>{@code username} is the authoritative identity anchor across clients: onboarding never
   * sets {@code AD_User.email} (see {@code EtendoGoJwtDalHelper#findAccountForEnvironmentUser}'s
   * Javadoc), and {@code EtendoGoJwtSupport#buildClientUsername} guarantees the account's very
   * FIRST/home environment gets the bare {@code accountEmail} as its username — every later
   * environment (a "resume" onboarding, or being invited as a teammate elsewhere) gets a
   * {@code <accountEmail>+<clientSlug>} suffix instead, to avoid colliding with it. An exact
   * {@code username} match is therefore unambiguous by construction: at most one active,
   * non-root-client {@code AD_User} can ever hold that bare value.
   *
   * <p>The previous implementation (inline in {@code CompanyInvitationService#resolveInviter})
   * matched on {@code email OR username} in a single unordered query capped at one result — once
   * the same account also owns a teammate {@code AD_User} in a DIFFERENT client (created via
   * {@code POST /sws/neo/user/user}, which sets {@code email} explicitly), that query could
   * non-deterministically resolve to either client, misrouting the invitation. Matching on
   * {@code username} first removes that ambiguity for the common case; the {@code email} fallback
   * below only applies when the account has no home identity at all (e.g. its very first-ever
   * {@code AD_User} was created by someone else inviting it before the account ever onboarded its
   * own company) and is at least made deterministic via {@code creationDate} ordering.
   */
  static User findInviterHomeUser(String accountEmail) {
    String email = accountEmail.toLowerCase(Locale.ROOT);
    OBQuery<User> byUsername = OBDal.getInstance().createQuery(User.class,
        "as u where lower(u.username) = :email and u.client.id <> '0' and u.active = true "
            + "order by u.creationDate asc");
    byUsername.setNamedParameter(EMAIL_PARAMETER, email);
    disableTenantFilters(byUsername);
    byUsername.setMaxResult(1);
    User user = byUsername.uniqueResult();
    if (user != null) {
      return user;
    }
    OBQuery<User> byEmail = OBDal.getInstance().createQuery(User.class,
        "as u where lower(u.email) = :email and u.client.id <> '0' and u.active = true "
            + "order by u.creationDate asc");
    byEmail.setNamedParameter(EMAIL_PARAMETER, email);
    disableTenantFilters(byEmail);
    byEmail.setMaxResult(1);
    return byEmail.uniqueResult();
  }

  private static void disableTenantFilters(OBQuery<?> query) {
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
  }
}
