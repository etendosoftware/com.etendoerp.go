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
package com.etendoerp.go.roles.overlap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * DB-free unit tests for {@link HigherPrecedenceSkip} (ETP-5507): each of its 4 rules, and the
 * guards that turn the skip off. {@code OBContext} and the inheritance query are mocked; the
 * end-to-end behaviour against core's propagation is covered by {@code
 * AssignTemplateRolesPrecedenceSkipIntegrationTest}.
 */
class HigherPrecedenceSkipTest {

  private static final String TENANT = "TENANT";
  private static final String OTHER_TENANT = "OTHER";
  private static final String STAR_ORG = "0";
  private static final String HIGHER_TEMPLATE = "HIGHER";
  private static final Long NEW_SEQNO = 10L;

  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<ActiveTemplateInheritance> inheritanceStatic;
  private OBContext context;
  private OBCriteria<RoleInheritance> criteria;
  private Role dependent;
  private Role higherTemplate;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    TemplateRemovalTracker.clear();
    context = mock(OBContext.class);
    when(context.getReadableClients()).thenReturn(new String[] { "0", TENANT });
    when(context.getReadableOrganizations()).thenReturn(new String[] { STAR_ORG });
    obContextStatic = mockStatic(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(context);

    criteria = mock(OBCriteria.class);
    inheritanceStatic = mockStatic(ActiveTemplateInheritance.class);
    inheritanceStatic
        .when(() -> ActiveTemplateInheritance.crossClientCriteria(RoleInheritance.class))
        .thenReturn(criteria);

    dependent = owned(mock(Role.class), TENANT, STAR_ORG);
    higherTemplate = template(HIGHER_TEMPLATE, true);
    precedingInheritances(inheritance(higherTemplate, TENANT));
  }

  @AfterEach
  void tearDown() {
    inheritanceStatic.close();
    obContextStatic.close();
    TemplateRemovalTracker.clear();
  }

  @Test
  void keepsAVisibleRowFromAPrecedingTemplateThatIsAtLeastAsPermissive() {
    HigherPrecedenceSkip skip = HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO);

    assertTrue(skip.keepsExisting(row(TENANT, STAR_ORG), higherTemplate, true, true));
    assertTrue(skip.keepsExisting(row(TENANT, STAR_ORG), higherTemplate, true, false));
    assertTrue(skip.keepsExisting(row(TENANT, STAR_ORG), higherTemplate, false, false));
  }

  @Test
  void deletesAReadOnlyRowFacingAFullGrant() {
    HigherPrecedenceSkip skip = HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO);

    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), higherTemplate, false, true));
    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), higherTemplate, null, true));
  }

  @Test
  void deletesAManuallyGrantedRow() {
    HigherPrecedenceSkip skip = HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO);

    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), null, true, true));
  }

  @Test
  void deletesARowFromATemplateThatDoesNotPrecedeTheNewOne() {
    HigherPrecedenceSkip skip = HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO);

    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), template("LOWER", true), true, true));
  }

  @Test
  void ignoresPrecedingInheritancesThatAreNoLongerTemplatesOrAreBeingRemoved() {
    Role notATemplate = template("NOT_TEMPLATE", false);
    Role beingRemoved = template("REMOVED", true);
    TemplateRemovalTracker.markRemoved("REMOVED");
    RoleInheritance orphan = mock(RoleInheritance.class);
    precedingInheritances(inheritance(notATemplate, TENANT), inheritance(beingRemoved, TENANT),
        orphan);

    HigherPrecedenceSkip skip = HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO);

    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), notATemplate, true, true));
    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), beingRemoved, true, true));
  }

  @Test
  void deletesARowCoreCannotSee() {
    HigherPrecedenceSkip skip = HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO);

    assertFalse(skip.keepsExisting(row(OTHER_TENANT, STAR_ORG), higherTemplate, true, true),
        "row on an unreadable client");
    assertFalse(skip.keepsExisting(row(TENANT, "ORG_B"), higherTemplate, true, true),
        "row on an unreadable organization");
    assertFalse(skip.keepsExisting(owned(mock(WindowAccess.class), null, STAR_ORG),
        higherTemplate, true, true), "row without client");
    assertFalse(skip.keepsExisting(owned(mock(WindowAccess.class), TENANT, null),
        higherTemplate, true, true), "row without organization");
    assertFalse(skip.keepsExisting(mock(BaseOBObject.class), higherTemplate, true, true),
        "row with no client/organization at all");
  }

  @Test
  void deletesARowWhenCoreCannotSeeTheHigherInheritance() {
    precedingInheritances(inheritance(higherTemplate, OTHER_TENANT));

    HigherPrecedenceSkip skip = HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO);

    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), higherTemplate, true, true));
  }

  @Test
  void neverSkipsWithoutASequenceNumberARoleOrAContext() {
    assertNeverSkips(HigherPrecedenceSkip.forNewInheritance(dependent, null));
    assertNeverSkips(HigherPrecedenceSkip.forNewInheritance(null, NEW_SEQNO));
    obContextStatic.when(OBContext::getOBContext).thenReturn(null);
    assertNeverSkips(HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO));
    inheritanceStatic.verify(() -> ActiveTemplateInheritance.crossClientCriteria(any()), never());
  }

  @Test
  void neverSkipsWhenNoInheritancePrecedesTheNewOne() {
    precedingInheritances();

    assertNeverSkips(HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO));
  }

  @Test
  void neverSkipsWhenTheContextHasNoReadableSets() {
    when(context.getReadableClients()).thenReturn(null);
    when(context.getReadableOrganizations()).thenReturn(null);

    assertNeverSkips(HigherPrecedenceSkip.forNewInheritance(dependent, NEW_SEQNO));
    assertFalse(HigherPrecedenceSkip.isVisibleToCore(dependent));
  }

  @Test
  void rolesAreVisibleToCoreOnlyWhenTheirClientAndOrganizationAreReadable() {
    assertTrue(HigherPrecedenceSkip.isVisibleToCore(dependent));
    assertFalse(HigherPrecedenceSkip.isVisibleToCore(owned(mock(Role.class), OTHER_TENANT,
        STAR_ORG)));
    assertFalse(HigherPrecedenceSkip.isVisibleToCore(null));
    obContextStatic.when(OBContext::getOBContext).thenReturn(null);
    assertFalse(HigherPrecedenceSkip.isVisibleToCore(dependent));
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  /** Stubs the query result; the arguments are built first, never inside the stubbing call. */
  private void precedingInheritances(RoleInheritance... inheritances) {
    List<RoleInheritance> result = List.of(inheritances);
    when(criteria.list()).thenReturn(result);
  }

  private void assertNeverSkips(HigherPrecedenceSkip skip) {
    assertFalse(skip.keepsExisting(row(TENANT, STAR_ORG), higherTemplate, true, false));
  }

  private static Role template(String id, boolean isTemplate) {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(id);
    when(role.isTemplate()).thenReturn(isTemplate);
    return role;
  }

  private static RoleInheritance inheritance(Role template, String clientId) {
    RoleInheritance inheritance = owned(mock(RoleInheritance.class), clientId, STAR_ORG);
    when(inheritance.getInheritFrom()).thenReturn(template);
    return inheritance;
  }

  private static WindowAccess row(String clientId, String organizationId) {
    return owned(mock(WindowAccess.class), clientId, organizationId);
  }

  /** Stubs {@code getClient()}/{@code getOrganization()} on a Role, RoleInheritance or access. */
  private static <T extends BaseOBObject> T owned(T entity, String clientId,
      String organizationId) {
    Client client = clientId == null ? null : mock(Client.class);
    if (client != null) {
      when(client.getId()).thenReturn(clientId);
    }
    Organization organization = organizationId == null ? null : mock(Organization.class);
    if (organization != null) {
      when(organization.getId()).thenReturn(organizationId);
    }
    if (entity instanceof Role) {
      when(((Role) entity).getClient()).thenReturn(client);
      when(((Role) entity).getOrganization()).thenReturn(organization);
    } else if (entity instanceof RoleInheritance) {
      when(((RoleInheritance) entity).getClient()).thenReturn(client);
      when(((RoleInheritance) entity).getOrganization()).thenReturn(organization);
    } else if (entity instanceof WindowAccess) {
      when(((WindowAccess) entity).getClient()).thenReturn(client);
      when(((WindowAccess) entity).getOrganization()).thenReturn(organization);
    }
    return entity;
  }
}
