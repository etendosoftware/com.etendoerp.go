/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.base.structure.OrganizationEnabled;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Tenant-ownership guard for entities addressed by an id that arrives in a request (ETP-4950).
 *
 * <p><b>Why this exists.</b> {@code OBDal.getInstance().get(Type.class, id)} resolves an id straight
 * to a row: unlike {@link org.openbravo.dal.service.OBCriteria} and
 * {@link org.openbravo.dal.service.OBQuery}, it adds <b>no</b> readable-client / readable-organization
 * predicate. Every NEO action in the reconciliation, movements, bank-statement and cash-close
 * surfaces takes its ids from a query parameter or a JSON body and resolves them with that bare
 * {@code get}, and all of them run inside {@code OBContext.setAdminMode(true)}. The result was that
 * passing another tenant's id read — and in the mutation paths, wrote — that tenant's data.
 *
 * <p><b>How to use it.</b> Replace a bare {@code OBDal.get} on a request-supplied id with
 * {@link #loadOwned(Class, String)}. A row belonging to another tenant comes back as {@code null}, so
 * the caller's existing "not found" branch answers 404/400 — deliberately indistinguishable from a
 * genuinely missing row, which is what keeps an id from being probed for existence.
 *
 * <p>Visibility is decided against {@link OBContext#getReadableClients()} /
 * {@link OBContext#getReadableOrganizations()} — the very sets the DAL would have applied — so this
 * guard and a DAL-backed query always agree on what the caller may see. Both are derived from the
 * role at login and are <b>not</b> widened by admin mode, which only suppresses the entity-access
 * check.
 *
 * <p>The NEO write paths use the client-only variant, {@link #loadOwnedByClient(String, String)},
 * for every reference a request sets (ETP-5785, see {@link NeoReferenceOwnership}).
 */
final class TenantOwnership {

  private TenantOwnership() {
  }

  /**
   * Loads an entity by id, but only when it belongs to the current tenant.
   *
   * @param entityClass the DAL entity class
   * @param id          the id, typically straight from a query parameter or a request body
   * @param <T>         the entity type
   * @return the entity, or {@code null} when the id is blank, unknown, or owned by another tenant
   */
  static <T extends BaseOBObject> T loadOwned(Class<T> entityClass, String id) {
    if (StringUtils.isBlank(id)) {
      return null;
    }
    T entity = OBDal.getInstance().get(entityClass, id);
    return isVisibleToCurrentTenant(entity) ? entity : null;
  }

  /**
   * True when {@code entity} is readable by the current client and organization.
   *
   * <p>An entity that is not client-enabled (a system/reference table) is always visible: those rows
   * are shared by design and carry no tenant of their own.
   */
  static boolean isVisibleToCurrentTenant(BaseOBObject entity) {
    if (entity == null) {
      return false;
    }
    OBContext context = OBContext.getOBContext();
    if (context == null) {
      // No session to check against (background process): leave the decision to the caller's own
      // scoping rather than silently reporting the row as missing.
      return true;
    }
    return hasReadableClient(entity, context) && hasReadableOrganization(entity, context);
  }

  /**
   * Loads a row by entity name and id, but only when it belongs to a client the caller may read:
   * its own, or System ({@code 0}). Unlike {@link #loadOwned(Class, String)} the organization is
   * NOT judged (ETP-5785).
   *
   * <p>For callers that know the entity only from the DAL model — a foreign key resolved
   * structurally from a request, a parent tab — rather than as a class. The client-only scope is
   * deliberate: a reference to a row of another organization of the SAME tenant is an
   * organization-access question with rules of its own (the natural tree, the referenced-org check
   * of {@code OBInterceptor}), not a tenant leak.
   *
   * @param entityName the DAL entity name
   * @param id         the id, typically straight from a request body
   * @return the row, or {@code null} when the id is blank, unknown, or owned by another tenant
   */
  static BaseOBObject loadOwnedByClient(String entityName, String id) {
    if (StringUtils.isBlank(entityName) || StringUtils.isBlank(id)) {
      return null;
    }
    BaseOBObject row = OBDal.getInstance().get(entityName, id);
    return belongsToReadableClient(row) ? row : null;
  }

  /**
   * Typed form of {@link #loadOwnedByClient(String, String)}, for a caller that knows the class.
   *
   * @param entityClass the DAL entity class
   * @param id          the id, typically straight from a request
   * @param <T>         the entity type
   * @return the row, or {@code null} when the id is blank, unknown, or owned by another tenant
   */
  static <T extends BaseOBObject> T loadOwnedByClient(Class<T> entityClass, String id) {
    if (StringUtils.isBlank(id)) {
      return null;
    }
    T row = OBDal.getInstance().get(entityClass, id);
    return belongsToReadableClient(row) ? row : null;
  }

  /**
   * True when {@code entity} belongs to a client the caller may read — its own or System — with
   * no opinion on its organization. See {@link #loadOwnedByClient(String, String)} for why.
   *
   * <p>A {@link Client} row is judged by its own id: it IS the tenant. Any other row that is not
   * client-enabled is shared reference data and always passes, exactly as in
   * {@link #isVisibleToCurrentTenant(BaseOBObject)}.
   */
  static boolean belongsToReadableClient(BaseOBObject entity) {
    if (entity == null) {
      return false;
    }
    OBContext context = OBContext.getOBContext();
    if (context == null) {
      // Same fail-open contract as isVisibleToCurrentTenant: no session, no tenant to compare.
      return true;
    }
    if (entity instanceof Client) {
      return contains(context.getReadableClients(), ((Client) entity).getId());
    }
    return hasReadableClient(entity, context);
  }

  /**
   * True when rows of {@code entity} belong to a tenant: the entity is client-enabled, or it is the
   * client entity itself. Anything else is shared by every tenant, so ownership cannot be violated
   * through it and there is nothing to look up.
   *
   * @param entity a DAL model entity; {@code null} is not tenant-scoped
   */
  static boolean isTenantScoped(Entity entity) {
    return entity != null
        && (entity.isClientEnabled() || Client.ENTITY_NAME.equals(entity.getName()));
  }

  /**
   * Whether an existing row belongs to the current client and a writable organization.
   * Readable System rows are legitimate references, but are not writable by another client.
   * No session, unknown ownership or missing write scope fails closed.
   */
  static boolean isWritableByCurrentTenant(BaseOBObject row) {
    OBContext context = OBContext.getOBContext();
    if (row == null || context == null || context.getCurrentClient() == null) {
      return false;
    }
    String currentClient = context.getCurrentClient().getId();
    if (currentClient == null) {
      return false;
    }
    if (row instanceof Client && !currentClient.equals(row.getId())) {
      return false;
    }
    if (row instanceof ClientEnabled) {
      Client client = ((ClientEnabled) row).getClient();
      if (client == null || !currentClient.equals(client.getId())) {
        return false;
      }
    } else if (!(row instanceof Client) && !"0".equals(currentClient)) {
      // Tables without a client are shared platform data, not tenant-owned writable rows.
      return false;
    }
    Organization organization = row instanceof Organization
        ? (Organization) row
        : row instanceof OrganizationEnabled ? ((OrganizationEnabled) row).getOrganization() : null;
    return organization == null || (context.getWritableOrganizations() != null
        && context.getWritableOrganizations().contains(organization.getId()));
  }

  private static boolean hasReadableClient(BaseOBObject entity, OBContext context) {
    if (!(entity instanceof ClientEnabled)) {
      return true;
    }
    Client client = ((ClientEnabled) entity).getClient();
    return client != null && contains(context.getReadableClients(), client.getId());
  }

  private static boolean hasReadableOrganization(BaseOBObject entity, OBContext context) {
    if (!(entity instanceof OrganizationEnabled)) {
      return true;
    }
    Organization organization = ((OrganizationEnabled) entity).getOrganization();
    // A null organization cannot betray the tenant on its own — the client check above already did
    // the isolating work — so it is not treated as a failure.
    return organization == null
        || contains(context.getReadableOrganizations(), organization.getId());
  }

  /** Membership test that fails <b>closed</b> on a missing set rather than throwing. */
  private static boolean contains(String[] ids, String id) {
    return ids != null && id != null && Arrays.asList(ids).contains(id);
  }
}
