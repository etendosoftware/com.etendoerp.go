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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * ETP-5558 — an {@code UNRESOLVABLE} scope still names its parent entity.
 *
 * <p>The {@code parent_unresolvable} refusal points the agent at the parent's actions, and it can
 * only do that as a direct call if the scope knows which entity the parent is. The parent tab is
 * resolvable even when the link column is not — {@code payment-out/lines} hangs from the
 * {@code header} tab, it just has no column pointing at it — so the name is looked up the same way a
 * resolved scope looks it up. Built through the real resolution, with the payment-out shape: the
 * only parent-link column targets {@code FIN_Payment_Detail}, the parent tab is on
 * {@code FIN_Payment}.</p>
 */
@DisplayName("ETP-5558 — an unresolvable parent scope still names the parent entity")
class McpParentScopeUnresolvableTest {

  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<KernelUtils> kernelUtilsMock;
  private MockedStatic<McpToolRouterSupport> routerSupportMock;
  private SFEntity child;

  @BeforeEach
  void setUp() {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();

    Table paymentTable = table("tbl-payment", "FIN_Payment");
    Table lineTable = table("tbl-psd", "FIN_Payment_ScheduleDetail");

    Tab headerTab = mock(Tab.class);
    when(headerTab.getId()).thenReturn("tab-header");
    when(headerTab.getTable()).thenReturn(paymentTable);
    Tab linesTab = mock(Tab.class);
    when(linesTab.getId()).thenReturn("tab-lines");
    when(linesTab.getName()).thenReturn("Lines");
    when(linesTab.getTabLevel()).thenReturn(1L);
    when(linesTab.getTable()).thenReturn(lineTable);

    Column link = mock(Column.class);
    when(link.isLinkToParentColumn()).thenReturn(true);
    when(link.isActive()).thenReturn(true);
    when(link.getDBColumnName()).thenReturn("FIN_Payment_Detail_ID");
    when(lineTable.getADColumnList()).thenReturn(List.of(link));

    Entity paymentEntity = entity("FIN_Payment");
    Entity detailEntity = entity("FIN_Payment_Detail");
    Entity lineEntity = entity("FIN_Payment_ScheduleDetail");
    Property paymentDetails = mock(Property.class);
    when(paymentDetails.getName()).thenReturn("paymentDetails");
    when(paymentDetails.getTargetEntity()).thenReturn(detailEntity);
    when(lineEntity.getPropertyByColumnName("FIN_Payment_Detail_ID")).thenReturn(paymentDetails);

    ModelProvider modelProvider = mock(ModelProvider.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
    when(modelProvider.getEntityByTableId("tbl-payment")).thenReturn(paymentEntity);
    when(modelProvider.getEntityByTableId("tbl-psd")).thenReturn(lineEntity);

    KernelUtils kernelUtils = mock(KernelUtils.class);
    kernelUtilsMock = mockStatic(KernelUtils.class);
    kernelUtilsMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
    when(kernelUtils.getParentTab(linesTab)).thenReturn(headerTab);

    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-payment-out");
    when(spec.getName()).thenReturn("payment-out");
    SFEntity header = mock(SFEntity.class);
    when(header.getName()).thenReturn("header");
    when(header.getADTab()).thenReturn(headerTab);
    child = mock(SFEntity.class);
    when(child.getId()).thenReturn("ent-lines");
    when(child.getName()).thenReturn("lines");
    when(child.getADTab()).thenReturn(linesTab);
    when(child.getETGOSFSpec()).thenReturn(spec);

    routerSupportMock = mockStatic(McpToolRouterSupport.class, Answers.CALLS_REAL_METHODS);
    routerSupportMock.when(() -> McpToolRouterSupport.listIncludedEntities("spec-payment-out"))
        .thenReturn(List.of(header, child));
  }

  @AfterEach
  void tearDown() {
    routerSupportMock.close();
    kernelUtilsMock.close();
    modelProviderMock.close();
    McpConfigCache.invalidateAll();
  }

  private static Table table(String id, String name) {
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(id);
    when(table.getDBTableName()).thenReturn(name);
    return table;
  }

  private static Entity entity(String name) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(name);
    when(entity.getProperty(anyString(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(null);
    return entity;
  }

  @Test
  @DisplayName("payment-out/lines resolves UNRESOLVABLE, with no parent field but a parent entity")
  void unresolvableCarriesParentEntity() {
    McpParentScope.Scope scope = McpParentScope.forEntity(child);

    assertEquals(McpParentScope.Kind.UNRESOLVABLE, scope.getKind());
    assertNull(scope.getParentField(), "there is still no field to write a parentId into");
    assertEquals("header", scope.getParentEntity(),
        "the refusal needs the parent entity to point the agent at a direct call");
    assertTrue(scope.getProblem().contains("FIN_Payment"), scope.getProblem());
  }
}
