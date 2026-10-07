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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Unit tests for {@link InOutWarehouseResolver#resolve}: the warehouse of a follow-up goods
 * movement. The caller's {@code warehouseId} input wins but is validated; then the source's own
 * warehouse; then the caller's default (re-read, not trusted); then the only usable one. Several
 * usable without a designated one ask the caller to choose ({@code WAREHOUSE_REQUIRED}); none
 * yields {@code null}.
 *
 * <p>Fixture: client {@code CLI}, movement organization {@code ORG}, whose natural tree is
 * {@code PARENT}, {@code ORG}, {@code CHILD} and {@code HIDDEN}; the caller reads every one of
 * them except {@code HIDDEN}, plus {@code 0} and the sibling {@code SIBLING} (outside the tree).
 *
 * @covers com.etendoerp.go.schemaforge.InOutWarehouseResolver
 */
class InOutWarehouseResolverTest {

  private static final String CLIENT_ID = "CLI";
  private static final String ORG_ID = "ORG";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private OBDal dal;
  private OBContext obContext;
  private OBCriteria<Warehouse> criteria;
  private Client client;
  private Organization organization;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    obContext = mock(OBContext.class);
    criteria = mock(OBCriteria.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    client = mock(Client.class);
    organization = mock(Organization.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(organization.getId()).thenReturn(ORG_ID);
    when(obContext.getOrganizationStructureProvider(CLIENT_ID)).thenReturn(osp);
    when(osp.getNaturalTree(ORG_ID))
        .thenReturn(new HashSet<>(Arrays.asList("PARENT", ORG_ID, "CHILD", "HIDDEN")));
    when(obContext.getReadableOrganizations())
        .thenReturn(new String[] { "PARENT", ORG_ID, "CHILD", "0", "SIBLING" });
    when(dal.createCriteria(Warehouse.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.addOrderBy(Warehouse.PROPERTY_NAME, true)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    obContextStatic = mockStatic(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(obContext);
  }

  @AfterEach
  void tearDown() {
    obContextStatic.close();
    obDalStatic.close();
  }

  // ── caller's input ────────────────────────────────────────────────────────

  @Test
  void aValidInputOverridesTheSourceWarehouseAndTheContextDefault() throws Exception {
    Warehouse requested = warehouse("wh-in", "Requested", true, CLIENT_ID, ORG_ID);
    when(dal.get(Warehouse.class, "wh-in")).thenReturn(requested);
    Warehouse contextDefault = warehouse("wh-ctx", "Default", true, CLIENT_ID, ORG_ID);
    when(obContext.getWarehouse()).thenReturn(contextDefault);
    Warehouse sourceWarehouse = mock(Warehouse.class);

    Warehouse resolved = InOutWarehouseResolver.resolve(client, organization, sourceWarehouse,
        inputs("wh-in"));

    assertSame(requested, resolved);
    verify(obContext, never()).getWarehouse();
    verify(dal, never()).createCriteria(Warehouse.class);
  }

  /**
   * An input is validated like any candidate; the rejection never echoes the requested id.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "unknown id,           false, false, CLI,   ORG",
      "inactive,             true,  false, CLI,   ORG",
      "of another client,    true,  true,  OTHER, ORG",
      "sibling organization, true,  true,  CLI,   SIBLING",
      "organization not readable, true, true, CLI, HIDDEN",
  })
  void anUnacceptableInputIsInvalidInput(String label, boolean exists, boolean active,
      String clientId, String orgId) throws Exception {
    Warehouse candidate = exists ? warehouse("wh-bad-42", "Bad", active, clientId, orgId) : null;
    when(dal.get(Warehouse.class, "wh-bad-42")).thenReturn(candidate);
    Warehouse sourceWarehouse = mock(Warehouse.class);

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> InOutWarehouseResolver.resolve(client, organization, sourceWarehouse,
            inputs("wh-bad-42")));

    assertEquals(FollowUpException.Reason.INVALID_INPUT, e.getReason());
    assertNull(e.getRequiredInput());
    assertFalse(e.getMessage().contains("wh-bad-42"), e.getMessage());
  }

  // ── source warehouse ──────────────────────────────────────────────────────

  @Test
  void theSourceWarehouseIsTakenAsIsWhenTheCallerSendsNone() {
    Warehouse sourceWarehouse = mock(Warehouse.class);

    assertSame(sourceWarehouse, InOutWarehouseResolver.resolve(client, organization,
        sourceWarehouse, FollowUpInputs.none()));
    verify(obContext, never()).getWarehouse();
    verify(dal, never()).createCriteria(Warehouse.class);
  }

  // ── context default ───────────────────────────────────────────────────────

  @Test
  void aUsableContextDefaultIsReReadAndChosen() {
    Warehouse fromContext = mock(Warehouse.class);
    when(fromContext.getId()).thenReturn("wh-ctx");
    when(obContext.getWarehouse()).thenReturn(fromContext);
    Warehouse reloaded = warehouse("wh-ctx", "Default", true, CLIENT_ID, ORG_ID);
    when(dal.get(Warehouse.class, "wh-ctx")).thenReturn(reloaded);

    assertSame(reloaded, InOutWarehouseResolver.resolve(client, organization, null,
        FollowUpInputs.none()));
    verify(dal, never()).createCriteria(Warehouse.class);
  }

  /**
   * An unusable default is skipped — judged on the re-read entity, so a context instance that
   * still says "active" does not save a warehouse deactivated since — and the only usable
   * warehouse is chosen instead.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "inactive once reloaded,   false, CLI,   ORG",
      "of another client,        true,  OTHER, ORG",
      "outside the natural tree, true,  CLI,   SIBLING",
  })
  void anUnusableContextDefaultIsSkipped(String label, boolean reloadedActive, String clientId,
      String orgId) {
    Warehouse fromContext = mock(Warehouse.class);
    when(fromContext.getId()).thenReturn("wh-ctx");
    when(fromContext.isActive()).thenReturn(true);
    when(obContext.getWarehouse()).thenReturn(fromContext);
    Warehouse reloaded = warehouse("wh-ctx", "Default", reloadedActive, clientId, orgId);
    when(dal.get(Warehouse.class, "wh-ctx")).thenReturn(reloaded);
    Warehouse only = warehouse("wh-only", "Only", true, CLIENT_ID, ORG_ID);
    List<Warehouse> found = Collections.singletonList(only);
    when(criteria.list()).thenReturn(found);

    assertSame(only, InOutWarehouseResolver.resolve(client, organization, null,
        FollowUpInputs.none()));
    verify(dal).get(Warehouse.class, "wh-ctx");
  }

  // ── natural tree ──────────────────────────────────────────────────────────

  /** A warehouse of a child organization, or of {@code *}, is usable by the movement. */
  @ParameterizedTest
  @ValueSource(strings = { "CHILD", "PARENT", "0" })
  void aWarehouseOfTheNaturalTreeOrOfStarIsAcceptedAsInput(String orgId) throws Exception {
    Warehouse requested = warehouse("wh-tree", "Tree", true, CLIENT_ID, orgId);
    when(dal.get(Warehouse.class, "wh-tree")).thenReturn(requested);

    assertSame(requested, InOutWarehouseResolver.resolve(client, organization, null,
        inputs("wh-tree")));
  }

  /**
   * Exactly one usable warehouse, in organization {@code *}, and no default: it is chosen (the
   * invoice whose shipment failed because only the invoice's exact organization was searched).
   */
  @Test
  void theOnlyUsableWarehouseIsChosenEvenInOrganizationStar() {
    Warehouse star = warehouse("wh-star", "Central", true, CLIENT_ID, "0");
    List<Warehouse> found = Collections.singletonList(star);
    when(criteria.list()).thenReturn(found);

    assertSame(star, InOutWarehouseResolver.resolve(client, organization, null,
        FollowUpInputs.none()));
  }

  // ── several / none ────────────────────────────────────────────────────────

  @Test
  void severalUsableWithoutADefaultAskTheCallerToChooseByName() {
    Warehouse alpha = warehouse("wh-a", "Alpha", true, CLIENT_ID, "0");
    Warehouse beta = warehouse("wh-b", "Beta", true, CLIENT_ID, "CHILD");
    List<Warehouse> found = Arrays.asList(alpha, beta);
    when(criteria.list()).thenReturn(found);

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> InOutWarehouseResolver.resolve(client, organization, null, FollowUpInputs.none()));

    assertEquals(FollowUpException.Reason.WAREHOUSE_REQUIRED, e.getReason());
    FollowUpException.RequiredInput input = e.getRequiredInput();
    assertEquals("warehouseId", input.getKey());
    assertEquals(2, input.getOptions().size());
    assertEquals("wh-a", input.getOptions().get(0).getId());
    assertEquals("Alpha", input.getOptions().get(0).getName());
    assertEquals("wh-b", input.getOptions().get(1).getId());
    assertEquals("Beta", input.getOptions().get(1).getName());
    // The query orders by name; the options keep that order.
    verify(criteria).addOrderBy(Warehouse.PROPERTY_NAME, true);
    // The readable organizations are read once for the whole resolution.
    verify(obContext, times(1)).getReadableOrganizations();
  }

  /** No usable warehouse: {@code null}, so the movement builder answers MISSING_SETUP. */
  @Test
  void noUsableWarehouseResolvesToNull() {
    Warehouse inactive = warehouse("wh-off", "Off", false, CLIENT_ID, ORG_ID);
    List<Warehouse> found = Collections.singletonList(inactive);
    when(criteria.list()).thenReturn(found);

    assertNull(InOutWarehouseResolver.resolve(client, organization, null,
        FollowUpInputs.none()));
    verify(obContext, times(1)).getReadableOrganizations();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static FollowUpInputs inputs(String warehouseId) throws Exception {
    return FollowUpInputs.fromRequestBody(new JSONObject().put("warehouseId", warehouseId));
  }

  private static Warehouse warehouse(String id, String name, boolean active, String clientId,
      String orgId) {
    Client owner = mock(Client.class);
    when(owner.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn(id);
    when(warehouse.getName()).thenReturn(name);
    when(warehouse.isActive()).thenReturn(active);
    when(warehouse.getClient()).thenReturn(owner);
    when(warehouse.getOrganization()).thenReturn(org);
    return warehouse;
  }
}
