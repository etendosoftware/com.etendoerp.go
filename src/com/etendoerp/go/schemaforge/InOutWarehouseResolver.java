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
package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Warehouse of a goods movement created as a follow-up (ETP-5576), for a source that does not
 * designate one itself. Target-side and source-agnostic: it only needs the movement's client and
 * organization, so any {@link InOutFollowUpCreator.SourceMapper} can call it.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>the caller's {@value #INPUT_WAREHOUSE_ID} input, when present — validated, never
 *       trusted: unknown, inactive, of another client, not usable by the organization or not
 *       readable by the caller → {@link FollowUpException.Reason#INVALID_INPUT};</li>
 *   <li>the warehouse the source designates ({@code sourceWarehouse}, e.g. its order's), taken
 *       as is;</li>
 *   <li>the caller's default warehouse — {@link OBContext#getWarehouse()}, the exact value NEO
 *       exposes as {@code #M_Warehouse_ID} ({@link NeoCalloutService#buildVars}) and that the
 *       {@code @#M_Warehouse_ID@} default of the goods receipt / goods shipment {@code warehouse}
 *       field resolves to when a user creates the document by hand (token warehouse, else the
 *       user's {@code Default_M_Warehouse_ID}) — if {@link #isUsable usable};</li>
 *   <li>the only usable warehouse, when exactly one exists;</li>
 *   <li>several usable → {@link FollowUpException.Reason#WAREHOUSE_REQUIRED} carrying them as
 *       {@link FollowUpException.RequiredInput options}; none → {@code null} (the builder answers
 *       {@code MISSING_SETUP}). It never guesses between several.</li>
 * </ol>
 *
 * <p><b>Usable</b> = active, of the movement's client, its organization in the natural tree of
 * the movement's organization
 * ({@link org.openbravo.dal.security.OrganizationStructureProvider#getNaturalTree} plus
 * {@code *}) — the same rule NEO's selectors apply ({@link SelectorOrgFilter}), so it is
 * exactly what a user could pick by hand on a goods receipt / shipment — and readable by the
 * caller ({@link OBContext#getReadableOrganizations()}).
 */
final class InOutWarehouseResolver {

  /** Request-body key of the caller's warehouse choice, and the {@code input.key} asked for. */
  static final String INPUT_WAREHOUSE_ID = "warehouseId";

  private static final String ORG_STAR = "0";

  private InOutWarehouseResolver() {
  }

  /**
   * @param client the movement's client
   * @param organization the movement's organization
   * @param sourceWarehouse the warehouse the source designates, or {@code null}
   * @param inputs the caller's choices (never {@code null})
   * @return the warehouse, or {@code null} when no usable warehouse exists
   * @throws FollowUpException {@code INVALID_INPUT} for a rejected {@value #INPUT_WAREHOUSE_ID};
   *     {@code WAREHOUSE_REQUIRED} when several are usable and nothing designates one
   */
  static Warehouse resolve(Client client, Organization organization, Warehouse sourceWarehouse,
      FollowUpInputs inputs) {
    String requestedId = inputs != null ? inputs.get(INPUT_WAREHOUSE_ID) : null;
    if (requestedId != null) {
      return requested(client, organization, requestedId);
    }
    if (sourceWarehouse != null) {
      return sourceWarehouse;
    }
    Set<String> usableOrgIds = usableOrganizationIds(client, organization);
    Warehouse contextDefault = contextDefaultWarehouse();
    if (isUsable(contextDefault, client, usableOrgIds)) {
      return contextDefault;
    }
    List<Warehouse> candidates = usableWarehouses(client, usableOrgIds);
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    if (candidates.isEmpty()) {
      return null;
    }
    throw new FollowUpException(FollowUpException.Reason.WAREHOUSE_REQUIRED,
        FollowUpException.Reason.WAREHOUSE_REQUIRED.getDefaultMessage(),
        new FollowUpException.RequiredInput(INPUT_WAREHOUSE_ID, options(candidates)));
  }

  /**
   * The caller's default warehouse ({@link OBContext#getWarehouse()}), re-read from the session:
   * the context instance was loaded when the request was authenticated and may be stale or
   * detached, so its active flag is not trusted.
   */
  private static Warehouse contextDefaultWarehouse() {
    Warehouse fromContext = OBContext.getOBContext().getWarehouse();
    return fromContext != null
        ? OBDal.getInstance().get(Warehouse.class, fromContext.getId())
        : null;
  }

  /** The caller's explicit choice, validated like any other candidate. */
  private static Warehouse requested(Client client, Organization organization,
      String requestedId) {
    Warehouse warehouse = OBDal.getInstance().get(Warehouse.class, requestedId);
    if (!isUsable(warehouse, client, usableOrganizationIds(client, organization))) {
      throw new FollowUpException(FollowUpException.Reason.INVALID_INPUT,
          "The selected warehouse is not available for this document");
    }
    return warehouse;
  }

  /**
   * Organizations whose warehouses the movement may use: the natural tree of its organization
   * plus {@code *} (as {@link SelectorOrgFilter} does for the hand-made document's selector),
   * restricted to the organizations the caller can read. Computed once per resolution.
   */
  static Set<String> usableOrganizationIds(Client client, Organization organization) {
    Set<String> ids = new HashSet<>(OBContext.getOBContext()
        .getOrganizationStructureProvider(client.getId())
        .getNaturalTree(organization.getId()));
    ids.add(ORG_STAR);
    ids.retainAll(new HashSet<>(
        Arrays.asList(OBContext.getOBContext().getReadableOrganizations())));
    return ids;
  }

  /**
   * Active, of {@code client}, and its organization in {@code usableOrgIds} (natural tree and
   * readable, see {@link #usableOrganizationIds}).
   */
  static boolean isUsable(Warehouse warehouse, Client client, Set<String> usableOrgIds) {
    if (warehouse == null || !Boolean.TRUE.equals(warehouse.isActive())
        || warehouse.getClient() == null || warehouse.getOrganization() == null
        || !client.getId().equals(warehouse.getClient().getId())) {
      return false;
    }
    return usableOrgIds.contains(warehouse.getOrganization().getId());
  }

  /** Every usable warehouse, by name. */
  private static List<Warehouse> usableWarehouses(Client client, Set<String> usableOrgIds) {
    List<Warehouse> found = OBDal.getInstance().createCriteria(Warehouse.class)
        .add(Restrictions.eq(Warehouse.PROPERTY_CLIENT, client))
        .add(Restrictions.eq(Warehouse.PROPERTY_ACTIVE, true))
        .add(Restrictions.in(Warehouse.PROPERTY_ORGANIZATION + ".id", usableOrgIds))
        .addOrderBy(Warehouse.PROPERTY_NAME, true)
        .list();
    List<Warehouse> usable = new ArrayList<>(found.size());
    for (Warehouse warehouse : found) {
      if (isUsable(warehouse, client, usableOrgIds)) {
        usable.add(warehouse);
      }
    }
    return usable;
  }

  private static List<FollowUpException.RequiredInput.Option> options(List<Warehouse> warehouses) {
    List<FollowUpException.RequiredInput.Option> options = new ArrayList<>(warehouses.size());
    for (Warehouse warehouse : warehouses) {
      options.add(new FollowUpException.RequiredInput.Option(warehouse.getId(),
          warehouse.getName()));
    }
    return options;
  }
}
