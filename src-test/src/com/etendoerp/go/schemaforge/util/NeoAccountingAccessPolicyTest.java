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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for the accounting-entity gate (ETP-5648): the structural criterion (child tab
 * whose table has {@code C_AcctSchema_ID}) and who may see such an entity.
 *
 * @covers com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoAccountingAccessPolicyTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<NeoAccessHelper> accessMock;
  private Session session;
  private NativeQuery<Object> acctColumnQuery;
  private NativeQuery<Object> roleFlagQuery;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    OBDal obDal = mock(OBDal.class);
    session = mock(Session.class);
    acctColumnQuery = mock(NativeQuery.class);
    roleFlagQuery = mock(NativeQuery.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenAnswer(inv ->
        inv.getArgument(0, String.class).contains("ad_column") ? acctColumnQuery : roleFlagQuery);
    accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.isAdminOrClientAdmin(org.mockito.ArgumentMatchers.any()))
        .thenCallRealMethod();
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    accessMock.close();
  }

  /** A fresh tab id per entity: the structural verdict is cached per AD_Tab_ID. */
  private SFEntity entity(long tabLevel, boolean tableHasAcctSchema) {
    Tab tab = mock(Tab.class);
    when(tab.getId()).thenReturn(UUID.randomUUID().toString());
    when(tab.getTabLevel()).thenReturn(tabLevel);
    when(tab.getTable()).thenReturn(mock(Table.class));
    when(acctColumnQuery.getResultList())
        .thenReturn(tableHasAcctSchema ? List.of(1) : Collections.emptyList());
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(tab);
    return entity;
  }

  private Role role(String id, boolean clientAdmin, String flag) {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(id);
    when(role.isClientAdmin()).thenReturn(clientAdmin);
    when(roleFlagQuery.getResultList())
        .thenReturn(flag == null ? Collections.emptyList() : List.of(flag.charAt(0)));
    return role;
  }

  @Test
  void childTabWithAcctSchemaColumnIsAccounting() {
    assertTrue(NeoAccountingAccessPolicy.isAccountingEntity(entity(1, true)));
  }

  @Test
  void headerTabWithAcctSchemaColumnIsNotAccounting() {
    assertFalse(NeoAccountingAccessPolicy.isAccountingEntity(entity(0, true)));
  }

  @Test
  void childTabWithoutAcctSchemaColumnIsNotAccounting() {
    assertFalse(NeoAccountingAccessPolicy.isAccountingEntity(entity(1, false)));
  }

  @Test
  void entityWithoutTabIsNotAccounting() {
    assertFalse(NeoAccountingAccessPolicy.isAccountingEntity(mock(SFEntity.class)));
    assertFalse(NeoAccountingAccessPolicy.isAccountingEntity(null));
  }

  @Test
  void nullRoleCannotSeeAccounting() {
    assertFalse(NeoAccountingAccessPolicy.canSeeAccounting(null));
  }

  @Test
  void adminAndClientAdminBypassTheFlag() {
    assertTrue(NeoAccountingAccessPolicy.canSeeAccounting(role("0", false, "N")));
    assertTrue(NeoAccountingAccessPolicy.canSeeAccounting(role("R1", true, "N")));
  }

  @Test
  void roleFlagDecidesForOrdinaryRoles() {
    assertTrue(NeoAccountingAccessPolicy.canSeeAccounting(role("R1", false, "Y")));
    assertFalse(NeoAccountingAccessPolicy.canSeeAccounting(role("R1", false, "N")));
    assertFalse(NeoAccountingAccessPolicy.canSeeAccounting(role("R1", false, null)));
  }

  @Test
  void accountingEntityAllowedOnlyWithCapability() {
    SFEntity acct = entity(1, true);
    Role sales = role("R1", false, "N");
    accessMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(sales);
    assertFalse(NeoAccountingAccessPolicy.isEntityAllowed(acct));

    Role finance = role("R2", false, "Y");
    accessMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(finance);
    assertTrue(NeoAccountingAccessPolicy.isEntityAllowed(acct));
  }

  @Test
  void nonAccountingEntityNeverReadsTheRoleFlag() {
    assertTrue(NeoAccountingAccessPolicy.isEntityAllowed(entity(1, false)));
    accessMock.verify(NeoAccessHelper::resolveCurrentRole, never());
  }

  @Test
  @SuppressWarnings("unchecked")
  void entityNameLookupFailsClosedOnAnyAccountingMatch() {
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(OBDal.getInstance().createCriteria(SFEntity.class)).thenReturn(criteria);
    SFEntity acct = entity(1, true);
    when(criteria.list()).thenReturn(List.of(acct));
    Role sales = role("R1", false, "N");
    accessMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(sales);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");

    // Mixed case on purpose: the REST handlers resolve entity names case-insensitively.
    assertFalse(NeoAccountingAccessPolicy.isEntityNameAllowed(spec, "ACCOUNTING"));
    verify(criteria).setFilterOnActive(false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void unknownEntityNameIsLeftToTheHandler() {
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(OBDal.getInstance().createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");

    assertTrue(NeoAccountingAccessPolicy.isEntityNameAllowed(spec, "nope"));
    assertTrue(NeoAccountingAccessPolicy.isEntityNameAllowed(null, "x"));
  }
}
