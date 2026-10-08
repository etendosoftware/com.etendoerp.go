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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>Also covers the {@code TAB_WHERE} step: the same shape with the real payment Lines where
 * clause ({@code ... pd.finPayment.id = @FIN_Payment_ID@}) is scoped by that clause, so it is
 * publishable, gates lists on {@code parentId} and still refuses creates.</p>
 *
 * @covers com.etendoerp.go.mcp.McpParentScope
 * @covers com.etendoerp.go.mcp.McpWriteRequestSupport
 */
@DisplayName("ETP-5558 — an unresolvable parent scope still names the parent entity")
class McpParentScopeUnresolvableTest {

  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<KernelUtils> kernelUtilsMock;
  private MockedStatic<McpToolRouterSupport> routerSupportMock;
  private SFEntity child;
  private Tab linesTab;

  @BeforeEach
  void setUp() {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();

    Table paymentTable = table("tbl-payment", "FIN_Payment");
    Table lineTable = table("tbl-psd", "FIN_Payment_ScheduleDetail");

    Tab headerTab = mock(Tab.class);
    when(headerTab.getId()).thenReturn("tab-header");
    when(headerTab.getTable()).thenReturn(paymentTable);
    linesTab = mock(Tab.class);
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

  /**
   * Review WARN-2: {@code unparented} is refused as UNRESOLVABLE when the entity advertises a write
   * — and "advertises" must mean what the MCP advertises. An unparented entity whose flags are all
   * on but whose writes {@code MCP_CONFIG.verbs} hides has nothing to write, so it is UNPARENTED.
   */
  @Test
  @DisplayName("unparented with every write hidden by verbs stays UNPARENTED")
  void unparentedWithHiddenWritesIsPublishable() {
    Tab tab = child.getADTab();
    SFSpec spec = child.getETGOSFSpec();
    SFEntity scoped = mock(SFEntity.class);
    when(scoped.getId()).thenReturn("ent-unparented");
    when(scoped.getName()).thenReturn("unparentedLines");
    when(scoped.getADTab()).thenReturn(tab);
    when(scoped.getETGOSFSpec()).thenReturn(spec);
    when(scoped.isPost()).thenReturn(true);
    when(scoped.isPut()).thenReturn(true);
    when(scoped.isPatch()).thenReturn(true);
    when(scoped.isDelete()).thenReturn(true);
    when(scoped.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(
        "{\"parent\":{\"mode\":\"unparented\",\"reason\":\"r\"},"
            + "\"verbs\":{\"create\":false,\"update\":false,\"delete\":false,\"reason\":\"r\"}}");

    assertEquals(McpParentScope.Kind.UNPARENTED, McpParentScope.forEntity(scoped).getKind());
  }

  /**
   * The remedy "Set MCP_CONFIG parent.field …" is for whoever configures the entity: it stays in
   * the scope's problem ({@code configError}, the log) but must not reach the agent, who cannot
   * act on it and would only be sent looking for a tool that does not exist.
   */
  @Test
  @DisplayName("the admin remedy stays in configError but not in the agent's refusal")
  void adminRemedyIsNotShownToTheAgent() throws Exception {
    McpParentScope.Scope scope = McpParentScope.forEntity(child);
    assertTrue(scope.getProblem().contains("MCP_CONFIG"),
        "configError keeps the remedy for the administrator: " + scope.getProblem());

    McpRoutingException refusal = org.junit.jupiter.api.Assertions.assertThrows(
        McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(child, null));
    String detail = refusal.toEnvelope().getString(McpConstants.KEY_DETAIL);
    assertTrue(detail.contains("FIN_Payment"), "the reason itself still reaches the agent: " + detail);
    assertFalse(detail.contains("MCP_CONFIG"), "no admin instruction for the agent: " + detail);
  }

  private static final String PAYMENT_LINES_WHERE = "exists (select 1 from FIN_Payment_Detail pd "
      + "where pd = e.paymentDetails and pd.finPayment.id = @FIN_Payment_ID@)";

  /**
   * The payment Lines shape: no link column reaches {@code FIN_Payment}, but the tab's where
   * clause carries {@code @FIN_Payment_ID@}, which the list fills from {@code parentId}. That is a
   * real scope, so no WARN-worthy problem, no {@code configError}, and the list is gated.
   */
  @Test
  @DisplayName("a where clause carrying the parent key placeholder resolves TAB_WHERE")
  void parentPlaceholderInTabWhereResolvesTabWhere() throws Exception {
    when(linesTab.getHqlwhereclause()).thenReturn(PAYMENT_LINES_WHERE);

    McpParentScope.Scope scope = McpParentScope.forEntity(child);

    assertEquals(McpParentScope.Kind.TAB_WHERE, scope.getKind());
    assertTrue(scope.isPublishable(), "scoped by the where clause, so the entity can be served");
    assertNull(scope.getProblem(), "no parentProblem / configError for a resolved scope");
    assertNull(scope.getParentField(), "there is still no field to write a parentId into");
    assertEquals("header", scope.getParentEntity());
    assertTrue(scope.requiresParentFor(McpParentSection.VERB_LIST), "lists need the parent id");
    assertFalse(scope.requiresParentFor(McpParentSection.VERB_CREATE),
        "create is refused outright, not gated on parentId");
    assertEquals(List.of(McpParentSection.VERB_LIST), scope.requiredVerbs());

    org.codehaus.jettison.json.JSONObject described = new org.codehaus.jettison.json.JSONObject();
    McpParentScope.publishInto(described, scope);
    assertFalse(described.has("parentProblem"), described.toString());
    assertFalse(described.has(McpParentScope.KEY_CONFIG_ERROR), described.toString());
    assertTrue(described.getBoolean("isChild"));
  }

  @Test
  @DisplayName("a TAB_WHERE child still refuses creates, with or without parentId")
  void tabWhereChildRefusesCreates() throws Exception {
    when(linesTab.getHqlwhereclause()).thenReturn(PAYMENT_LINES_WHERE);

    McpRoutingException withoutParent = org.junit.jupiter.api.Assertions.assertThrows(
        McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(child, null));
    assertEquals(McpConstants.ERROR_PARENT_UNRESOLVABLE,
        withoutParent.toEnvelope().getString(McpConstants.KEY_ERROR));
    org.junit.jupiter.api.Assertions.assertThrows(McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(child, "payment-1"));
  }

  /**
   * Only the parent's key counts. A clause whose placeholders are session variables or another
   * table's key does not scope the rows to this parent, so the entity stays UNRESOLVABLE.
   */
  @Test
  @DisplayName("a where clause without the parent key placeholder stays UNRESOLVABLE")
  void unrelatedPlaceholderStaysUnresolvable() {
    when(linesTab.getHqlwhereclause()).thenReturn(
        "e.organization.id in (@#AccessibleOrgTree@) and e.invoice.id = @C_Invoice_ID@");

    assertEquals(McpParentScope.Kind.UNRESOLVABLE, McpParentScope.forEntity(child).getKind());
  }

  /**
   * A declared parent.field with a reason and no relaxed verb: the parent is required on every
   * verb, so the reason must not be published as parentOptionalReason (it would say the opposite).
   */
  @Test
  @DisplayName("a declared parent.field reason is not published as optional when nothing is")
  void declaredReasonNotPublishedAsOptionalWhenEveryVerbRequiresTheParent() throws Exception {
    Entity lineEntity = ModelProvider.getInstance().getEntityByTableId("tbl-psd");
    Property link = lineEntity.getPropertyByColumnName("FIN_Payment_Detail_ID");
    when(lineEntity.getProperty("paymentDetails", false)).thenReturn(link);
    when(child.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(
        "{\"parent\":{\"field\":\"paymentDetails\",\"reason\":\"the link is paymentDetails\"}}");

    McpParentScope.Scope scope = McpParentScope.forEntity(child);
    org.codehaus.jettison.json.JSONObject described = new org.codehaus.jettison.json.JSONObject();
    McpParentScope.publishInto(described, scope);

    assertEquals(McpParentScope.Kind.RESOLVED, scope.getKind());
    assertEquals(McpParentSection.ALL_VERBS.size(), scope.requiredVerbs().size());
    assertFalse(described.has("parentOptionalReason"), described.toString());
  }

  @Test
  @DisplayName("a reason is still published when a verb is relaxed")
  void reasonPublishedWhenAVerbIsOptional() throws Exception {
    Entity lineEntity = ModelProvider.getInstance().getEntityByTableId("tbl-psd");
    Property link = lineEntity.getPropertyByColumnName("FIN_Payment_Detail_ID");
    when(lineEntity.getProperty("paymentDetails", false)).thenReturn(link);
    when(child.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(
        "{\"parent\":{\"field\":\"paymentDetails\",\"optionalFor\":[\"list\"],"
            + "\"reason\":\"lists are global\"}}");

    org.codehaus.jettison.json.JSONObject described = new org.codehaus.jettison.json.JSONObject();
    McpParentScope.publishInto(described, McpParentScope.forEntity(child));

    assertEquals("lists are global", described.getString("parentOptionalReason"));
  }
}
