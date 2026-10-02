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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * ETP-5558 — the record an action runs on must belong to the caller's tenant.
 *
 * <p>The DAL is mocked at its edges (the model lookup, {@code OBDal.get} and the session's readable
 * clients and organizations); {@link TenantOwnership} runs for real, so the client/organization rule
 * is the production one.</p>
 */
@DisplayName("ETP-5558 — NeoActionRecordGuard")
class NeoActionRecordGuardTest {

  private static final String OWN_CLIENT = "client-own";
  private static final String OTHER_CLIENT = "client-other";
  private static final String OWN_ORG = "org-own";
  private static final String OTHER_ORG = "org-other";
  private static final String TABLE_ID = "D1A97202E832470285C9B1EB026D54E2";
  private static final String RECORD = "pay-1";

  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> contextMock;
  private MockedStatic<ModelProvider> modelMock;
  private OBDal dal;
  private ModelProvider model;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    OBContext context = mock(OBContext.class);
    when(context.getReadableClients()).thenReturn(new String[] { OWN_CLIENT });
    when(context.getReadableOrganizations()).thenReturn(new String[] { OWN_ORG });
    contextMock = mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
    model = mock(ModelProvider.class);
    modelMock = mockStatic(ModelProvider.class);
    modelMock.when(ModelProvider::getInstance).thenReturn(model);
  }

  @AfterEach
  void tearDown() {
    modelMock.close();
    contextMock.close();
    dalMock.close();
  }

  /** An entity whose AD tab sits on {@code tableId}, mapped to the DAL entity {@code dalName}. */
  private SFEntity entityOn(String tableId, String dalName) {
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(tableId);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn("finPayment");
    when(entity.getADTab()).thenReturn(tab);
    if (dalName != null) {
      Entity dalEntity = mock(Entity.class);
      when(dalEntity.getName()).thenReturn(dalName);
      when(model.getEntityByTableId(tableId)).thenReturn(dalEntity);
    }
    return entity;
  }

  private SFEntity paymentEntity() {
    return entityOn(TABLE_ID, FIN_Payment.ENTITY_NAME);
  }

  private FIN_Payment payment(String clientId, String orgId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getClient()).thenReturn(client);
    when(payment.getOrganization()).thenReturn(org);
    when(dal.get(FIN_Payment.ENTITY_NAME, RECORD)).thenReturn(payment);
    return payment;
  }

  private static void assertRecordNotFound(NeoResponse refusal) throws Exception {
    assertEquals(404, refusal.getHttpStatus());
    assertEquals(NeoActionRecordGuard.MSG_RECORD_NOT_FOUND,
        refusal.getBody().getJSONObject("error").getString("message"));
  }

  @Test
  @DisplayName("another client's row of the tab's table is a 404 Record not found")
  void otherClientsRowIsRefused() throws Exception {
    payment(OTHER_CLIENT, OWN_ORG);

    assertRecordNotFound(NeoActionRecordGuard.refusalFor(paymentEntity(), RECORD));
    assertTrue(NeoActionRecordGuard.isForeignRecord(paymentEntity(), RECORD));
  }

  @Test
  @DisplayName("a row in an organization outside the readable ones is a 404")
  void unreadableOrganizationIsRefused() throws Exception {
    payment(OWN_CLIENT, OTHER_ORG);

    assertRecordNotFound(NeoActionRecordGuard.refusalFor(paymentEntity(), RECORD));
  }

  @Test
  @DisplayName("a same-tenant row passes")
  void ownRowPasses() {
    payment(OWN_CLIENT, OWN_ORG);

    assertNull(NeoActionRecordGuard.refusalFor(paymentEntity(), RECORD));
  }

  @Test
  @DisplayName("a blank id, or no entity, passes without a lookup")
  void blankIdPasses() {
    payment(OTHER_CLIENT, OTHER_ORG);

    assertNull(NeoActionRecordGuard.refusalFor(paymentEntity(), ""));
    assertNull(NeoActionRecordGuard.refusalFor(paymentEntity(), null));
    assertNull(NeoActionRecordGuard.refusalFor(null, RECORD));
    verify(dal, never()).get(anyString(), anyString());
  }

  @Test
  @DisplayName("an entity with no tab, or a tab with no table, passes")
  void noTabPasses() {
    payment(OTHER_CLIENT, OTHER_ORG);
    SFEntity tabless = mock(SFEntity.class);
    SFEntity tableless = mock(SFEntity.class);
    when(tableless.getADTab()).thenReturn(mock(Tab.class));

    assertNull(NeoActionRecordGuard.refusalFor(tabless, RECORD));
    assertNull(NeoActionRecordGuard.refusalFor(tableless, RECORD));
  }

  @Test
  @DisplayName("a tab table with no DAL entity passes")
  void noDalEntityPasses() {
    payment(OTHER_CLIENT, OTHER_ORG);

    assertNull(NeoActionRecordGuard.refusalFor(entityOn("unmapped-table", null), RECORD));
  }

  @Test
  @DisplayName("an id that is not a row of that table passes: the handler answers for it")
  void idOfAnotherTablePasses() {
    assertNull(NeoActionRecordGuard.refusalFor(paymentEntity(), "fin-account-id"));
  }

  @Test
  @DisplayName("a lookup that throws is refused like an unknown id (fails closed), admin mode restored")
  void throwingLookupIsRefused() throws Exception {
    when(dal.get(FIN_Payment.ENTITY_NAME, RECORD))
        .thenThrow(new IllegalArgumentException("not a key of this table"));

    assertRecordNotFound(NeoActionRecordGuard.refusalFor(paymentEntity(), RECORD));
    contextMock.verify(() -> OBContext.setAdminMode(true));
    contextMock.verify(OBContext::restorePreviousMode);
  }

  @Test
  @DisplayName("an ownership decision that throws on the row is refused too, admin mode restored")
  void throwingOwnershipDecisionIsRefused() throws Exception {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getClient()).thenThrow(new IllegalStateException("lazy proxy detached"));
    when(dal.get(FIN_Payment.ENTITY_NAME, RECORD)).thenReturn(payment);

    assertRecordNotFound(NeoActionRecordGuard.refusalFor(paymentEntity(), RECORD));
    contextMock.verify(OBContext::restorePreviousMode);
  }

  @Test
  @DisplayName("the refusal for a failed lookup cannot be told apart from an unknown id's")
  void failedLookupLeaksNothing() throws Exception {
    payment(OTHER_CLIENT, OWN_ORG);
    NeoResponse foreign = NeoActionRecordGuard.refusalFor(paymentEntity(), RECORD);
    when(dal.get(FIN_Payment.ENTITY_NAME, RECORD)).thenThrow(new IllegalArgumentException(
        "could not load FIN_Payment pay-1 of client secret-client"));
    NeoResponse failed = NeoActionRecordGuard.refusalFor(paymentEntity(), RECORD);

    assertEquals(foreign.getHttpStatus(), failed.getHttpStatus());
    assertEquals(foreign.getBody().toString(), failed.getBody().toString());
    assertFalse(failed.getBody().toString().contains("secret"), failed.getBody().toString());
  }

  @Test
  @DisplayName("a table that is not organization-enabled (AD_Org) checks the client only")
  void organizationTableChecksClientOnly() throws Exception {
    Client own = mock(Client.class);
    when(own.getId()).thenReturn(OWN_CLIENT);
    // An organization row of the own client that is not in the readable orgs: not an
    // OrganizationEnabled row, so only the client decides.
    Organization ownClientsOrg = mock(Organization.class);
    when(ownClientsOrg.getClient()).thenReturn(own);
    when(dal.get(Organization.ENTITY_NAME, OTHER_ORG)).thenReturn(ownClientsOrg);
    SFEntity orgs = entityOn("155", Organization.ENTITY_NAME);

    assertNull(NeoActionRecordGuard.refusalFor(orgs, OTHER_ORG));

    Client other = mock(Client.class);
    when(other.getId()).thenReturn(OTHER_CLIENT);
    Organization foreignOrg = mock(Organization.class);
    when(foreignOrg.getClient()).thenReturn(other);
    when(dal.get(Organization.ENTITY_NAME, "org-of-other-client")).thenReturn(foreignOrg);

    assertRecordNotFound(NeoActionRecordGuard.refusalFor(orgs, "org-of-other-client"));
  }

  @Test
  @DisplayName("loadOwned hands out only the caller's own rows")
  void loadOwnedFiltersByTenant() {
    FIN_Payment own = payment(OWN_CLIENT, OWN_ORG);
    when(dal.get(FIN_Payment.class, RECORD)).thenReturn(own);
    assertEquals(own, NeoActionRecordGuard.loadOwned(FIN_Payment.class, RECORD));

    FIN_Payment foreign = payment(OTHER_CLIENT, OWN_ORG);
    when(dal.get(FIN_Payment.class, RECORD)).thenReturn(foreign);
    assertNull(NeoActionRecordGuard.loadOwned(FIN_Payment.class, RECORD));
    assertFalse(NeoActionRecordGuard.isForeignRecord(paymentEntity(), ""));
  }
}
