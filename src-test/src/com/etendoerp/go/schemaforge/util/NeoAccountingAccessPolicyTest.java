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

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.codehaus.jettison.json.JSONObject;
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
import org.openbravo.client.application.Process;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for the accounting gate: accounting entities (ETP-5648) — the structural criterion
 * (child tab whose table has {@code C_AcctSchema_ID}) and who may see such an entity — and the
 * posting actions (ETP-5684): post/unpost and every button that runs the shared Post process.
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

  // ETP-5684 — posting actions follow the same capability.

  private static Column button(String processId) {
    Column column = mock(Column.class);
    if (processId != null) {
      Process process = mock(Process.class);
      when(process.getId()).thenReturn(processId);
      when(column.getOBUIAPPProcess()).thenReturn(process);
    }
    return column;
  }

  @Test
  void postAndUnpostArePostingActionsWithoutLookingAtTheEntity() {
    assertTrue(NeoAccountingAccessPolicy.isPostingAction(null, "post"));
    assertTrue(NeoAccountingAccessPolicy.isPostingAction(null, "UNPOST"));
    assertFalse(NeoAccountingAccessPolicy.isPostingAction(null, "reactivate"));
    assertFalse(NeoAccountingAccessPolicy.isPostingAction(null, " "));
  }

  @Test
  void aButtonIsPostingByItsProcessNotItsName() {
    // EM_Etblkp_Bulkposting: the Post process declared on the column.
    assertTrue(NeoAccountingAccessPolicy.isPostingButton(
        button(NeoAccessHelper.DEFAULT_POST_PROCESS_ID)));
    // Posted: no process on the column, resolved through the shared fallback.
    Column posted = button(null);
    Process post = mock(Process.class);
    when(post.getId()).thenReturn(NeoAccessHelper.DEFAULT_POST_PROCESS_ID);
    accessMock.when(() -> NeoAccessHelper.resolveFallbackObuiappProcess(posted)).thenReturn(post);
    assertTrue(NeoAccountingAccessPolicy.isPostingButton(posted));

    assertFalse(NeoAccountingAccessPolicy.isPostingButton(button("OTHER")));
    assertFalse(NeoAccountingAccessPolicy.isPostingButton(button(null)));
    assertFalse(NeoAccountingAccessPolicy.isPostingButton(null));
  }

  @Test
  void anAdButtonActionIsResolvedOnTheEntity() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("E1");
    Column bulk = button(NeoAccessHelper.DEFAULT_POST_PROCESS_ID);
    try (MockedStatic<NeoButtonActionHelper> buttons = mockStatic(NeoButtonActionHelper.class)) {
      buttons.when(() -> NeoButtonActionHelper.findButtonColumn("E1", "EM_Etblkp_Bulkposting"))
          .thenReturn(bulk);
      assertTrue(NeoAccountingAccessPolicy.isPostingAction(entity, "EM_Etblkp_Bulkposting"));
      assertFalse(NeoAccountingAccessPolicy.isPostingAction(entity, "DocAction"));
    }
  }

  @Test
  void postingIsRefusedWith403WithoutTheCapability() throws Exception {
    Role sales = role("R1", false, "N");
    accessMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(sales);

    NeoResponse refusal = NeoAccountingAccessPolicy.refusalForPostingAction(null, "unpost");

    assertEquals(403, refusal.getHttpStatus());
    JSONObject body = refusal.getBody();
    assertFalse(body.getBoolean("success"));
    assertEquals(NeoAccountingAccessPolicy.KEY_ACCOUNTING_ACCESS_DENIED,
        body.getJSONArray("messageKeys").getString(0));
  }

  @Test
  void postingRunsWithTheCapability() {
    Role finance = role("R2", false, "Y");
    accessMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(finance);
    assertNull(NeoAccountingAccessPolicy.refusalForPostingAction(null, "post"));
  }

  @Test
  void aNonPostingActionNeverReadsTheRoleFlag() {
    assertNull(NeoAccountingAccessPolicy.refusalForPostingAction(null, "reactivate"));
    accessMock.verify(NeoAccessHelper::resolveCurrentRole, never());
  }
}
