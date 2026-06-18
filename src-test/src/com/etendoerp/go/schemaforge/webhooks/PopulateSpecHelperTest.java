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

package com.etendoerp.go.schemaforge.webhooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.PopulateSpecHelper;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link PopulateSpecHelper}.
 * Covers Window-type and Process-type spec population, system column
 * exclusion, includeAllMethods flag, deleteExistingChildren,
 * null/missing references, and flush behavior.
 */
public class PopulateSpecHelperTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBProvider> obProviderMock;
  private OBDal mockDal;
  private OBContext mockContext;
  private OBProvider mockProvider;

  private SFSpec mockSpec;
  private Window mockWindow;
  private Module mockModule;
  private Client mockClient;
  private Organization mockOrg;
  private User mockUser;

  @Before
  public void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    obProviderMock = mockStatic(OBProvider.class);

    mockDal = mock(OBDal.class);
    mockContext = mock(OBContext.class);
    mockProvider = mock(OBProvider.class);
    mockUser = mock(User.class);

    obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);
    obProviderMock.when(OBProvider::getInstance).thenReturn(mockProvider);
    when(mockContext.getUser()).thenReturn(mockUser);

    mockSpec = mock(SFSpec.class);
    mockWindow = mock(Window.class);
    mockModule = mock(Module.class);
    mockClient = mock(Client.class);
    mockOrg = mock(Organization.class);

    when(mockSpec.getADModule()).thenReturn(mockModule);
    when(mockSpec.getClient()).thenReturn(mockClient);
    when(mockSpec.getOrganization()).thenReturn(mockOrg);
    when(mockSpec.getSpecType()).thenReturn("W");
    when(mockSpec.getADWindow()).thenReturn(mockWindow);
    when(mockWindow.getId()).thenReturn("window-1");
  }

  @After
  public void tearDown() {
    obProviderMock.close();
    obContextMock.close();
    obDalMock.close();
  }

  // ---- spec not found ────────────────────────────────────────────────

  @Test(expected = IllegalArgumentException.class)
  public void testPopulateThrowsWhenSpecNotFound() {
    when(mockDal.get(SFSpec.class, "missing-id")).thenReturn(null);

    PopulateSpecHelper.populate("missing-id", true);
  }

  @Test
  public void testPopulateThrowsWithDescriptiveMessage() {
    when(mockDal.get(SFSpec.class, "missing-id")).thenReturn(null);

    try {
      PopulateSpecHelper.populate("missing-id", true);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertEquals("Spec not found: missing-id", e.getMessage());
    }
  }

  // ---- Window spec: no linked window ─────────────────────────────────

  @Test(expected = IllegalArgumentException.class)
  public void testPopulateWindowThrowsWhenNoLinkedWindow() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    when(mockSpec.getADWindow()).thenReturn(null);
    stubDeleteExistingChildrenCriteria();

    PopulateSpecHelper.populate("spec-1", true);
  }

  // ---- Window spec: empty tabs ───────────────────────────────────────

  @Test
  public void testPopulateWindowWithNoTabs() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();
    stubTabCriteria(Collections.emptyList());

    int[] result = PopulateSpecHelper.populate("spec-1", true);

    assertArrayEquals(new int[]{0, 0}, result);
    verify(mockDal, times(2)).flush(); // deleteExisting + final
  }

  // ---- Window spec: single tab with columns ──────────────────────────

  @Test
  public void testPopulateWindowSingleTabWithColumns() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Order", 10L);
    stubTabCriteria(Collections.singletonList(tab));

    Column col1 = createMockColumn("C_Order_ID");
    Column col2 = createMockColumn("DocumentNo");
    stubColumnCriteria(tab, List.of(col1, col2));

    SFEntity mockEntity = mock(SFEntity.class);
    SFField mockField1 = mock(SFField.class);
    SFField mockField2 = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(mockField1, mockField2);

    int[] result = PopulateSpecHelper.populate("spec-1", false);

    assertArrayEquals(new int[]{1, 2}, result);
    verify(mockDal).save(mockEntity);
    verify(mockDal).save(mockField1);
    verify(mockDal).save(mockField2);
  }

  // ---- Window spec: excludeSystemColumns filters audit columns ───────

  @Test
  public void testPopulateWindowExcludesSystemColumns() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Order", 10L);
    stubTabCriteria(Collections.singletonList(tab));

    Column colRegular = createMockColumn("DocumentNo");
    Column colCreated = createMockColumn("Created");
    Column colUpdated = createMockColumn("Updated");
    Column colCreatedBy = createMockColumn("CreatedBy");
    Column colUpdatedBy = createMockColumn("UpdatedBy");
    Column colIsActive = createMockColumn("IsActive");
    Column colAdClient = createMockColumn("AD_Client_ID");
    Column colAdOrg = createMockColumn("AD_Org_ID");
    stubColumnCriteria(tab, List.of(colRegular, colCreated, colUpdated,
        colCreatedBy, colUpdatedBy, colIsActive, colAdClient, colAdOrg));

    SFEntity mockEntity = mock(SFEntity.class);
    SFField mockField = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(mockField);

    int[] result = PopulateSpecHelper.populate("spec-1", true);

    // Only DocumentNo should be included; 7 system columns excluded
    assertArrayEquals(new int[]{1, 1}, result);
  }

  @Test
  public void testPopulateWindowIncludesSystemColumnsWhenFlagIsFalse() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Order", 10L);
    stubTabCriteria(Collections.singletonList(tab));

    Column colRegular = createMockColumn("DocumentNo");
    Column colCreated = createMockColumn("Created");
    stubColumnCriteria(tab, List.of(colRegular, colCreated));

    SFEntity mockEntity = mock(SFEntity.class);
    SFField mockField1 = mock(SFField.class);
    SFField mockField2 = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(mockField1, mockField2);

    int[] result = PopulateSpecHelper.populate("spec-1", false);

    // Both columns included when excludeSystemColumns is false
    assertArrayEquals(new int[]{1, 2}, result);
  }

  // ---- Window spec: includeAllMethods flag ───────────────────────────

  @Test
  public void testPopulateWindowIncludeAllMethodsSetsFlags() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Order", 10L);
    stubTabCriteria(Collections.singletonList(tab));
    stubColumnCriteria(tab, Collections.emptyList());

    SFEntity mockEntity = mock(SFEntity.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);

    PopulateSpecHelper.populate("spec-1", true, true);

    verify(mockEntity).setGet(true);
    verify(mockEntity).setGetByID(true);
    verify(mockEntity).setPost(true);
    verify(mockEntity).setPut(true);
    verify(mockEntity).setPatch(true);
    verify(mockEntity).setDelete(true);
  }

  @Test
  public void testPopulateWindowExcludeAllMethodsSetsFlags() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Order", 10L);
    stubTabCriteria(Collections.singletonList(tab));
    stubColumnCriteria(tab, Collections.emptyList());

    SFEntity mockEntity = mock(SFEntity.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);

    PopulateSpecHelper.populate("spec-1", true, false);

    verify(mockEntity).setGet(false);
    verify(mockEntity).setGetByID(false);
    verify(mockEntity).setPost(false);
    verify(mockEntity).setPut(false);
    verify(mockEntity).setPatch(false);
    verify(mockEntity).setDelete(false);
  }

  // ---- Window spec: multiple tabs ────────────────────────────────────

  @Test
  public void testPopulateWindowMultipleTabs() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab1 = createMockTab("tab-1", "Header", 10L);
    Tab tab2 = createMockTab("tab-2", "Lines", 20L);
    stubTabCriteria(List.of(tab1, tab2));

    // Chain column criteria returns so tab1 gets its columns and tab2 gets its own
    List<Column> tab1Cols = List.of(createMockColumn("Col1"));
    List<Column> tab2Cols = List.of(createMockColumn("Col2"), createMockColumn("Col3"));
    stubColumnCriteriaChained(tab1Cols, tab2Cols);

    SFEntity entity1 = mock(SFEntity.class);
    SFEntity entity2 = mock(SFEntity.class);
    SFField field1 = mock(SFField.class);
    SFField field2 = mock(SFField.class);
    SFField field3 = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(entity1, entity2);
    when(mockProvider.get(SFField.class)).thenReturn(field1, field2, field3);

    int[] result = PopulateSpecHelper.populate("spec-1", false);

    assertArrayEquals(new int[]{2, 3}, result);
  }

  // ---- Window spec: flush every 10 entities ──────────────────────────

  @Test
  public void testPopulateWindowFlushesEveryTenEntities() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    // Create 11 tabs to trigger the modulo-10 flush
    List<Tab> tabs = new ArrayList<>();
    for (int i = 1; i <= 11; i++) {
      Tab tab = createMockTab("tab-" + i, "Tab" + i, (long) i * 10);
      tabs.add(tab);
      stubColumnCriteria(tab, Collections.emptyList());
    }
    stubTabCriteria(tabs);

    when(mockProvider.get(SFEntity.class)).thenAnswer(inv -> mock(SFEntity.class));

    PopulateSpecHelper.populate("spec-1", true);

    // deleteExisting flush + modulo-10 flush at entity 10 + final flush = 3
    verify(mockDal, times(3)).flush();
  }

  // ---- Window spec: two-arg populate delegates to three-arg ──────────

  @Test
  public void testPopulateTwoArgDelegatesToThreeArg() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();
    stubTabCriteria(Collections.emptyList());

    int[] result = PopulateSpecHelper.populate("spec-1", true);

    assertArrayEquals(new int[]{0, 0}, result);
  }

  // ---- Process spec: basic populate ──────────────────────────────────

  @Test
  public void testPopulateProcessBasic() {
    Process mockProcess = mock(Process.class);
    when(mockProcess.getName()).thenReturn("Test Process");
    when(mockProcess.getADProcessParameterList()).thenReturn(Collections.emptyList());

    when(mockSpec.getSpecType()).thenReturn("P");
    when(mockSpec.getProcess()).thenReturn(mockProcess);
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    SFEntity mockEntity = mock(SFEntity.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);

    int[] result = PopulateSpecHelper.populate("spec-1", true);

    assertArrayEquals(new int[]{1, 0}, result);
    // Process entities are POST-only
    verify(mockEntity).setPost(true);
    verify(mockEntity).setGet(false);
    verify(mockEntity).setGetByID(false);
    verify(mockEntity).setPut(false);
    verify(mockEntity).setPatch(false);
    verify(mockEntity).setDelete(false);
  }

  // ---- Process spec: no linked process ───────────────────────────────

  @Test(expected = IllegalArgumentException.class)
  public void testPopulateProcessThrowsWhenNoLinkedProcess() {
    when(mockSpec.getSpecType()).thenReturn("P");
    when(mockSpec.getProcess()).thenReturn(null);
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    PopulateSpecHelper.populate("spec-1", true);
  }

  // ---- Process spec: with parameters ─────────────────────────────────

  @Test
  public void testPopulateProcessWithParameters() {
    Process mockProcess = mock(Process.class);
    when(mockProcess.getName()).thenReturn("Test Process");

    ProcessParameter param1 = mock(ProcessParameter.class);
    when(param1.isActive()).thenReturn(true);
    when(param1.getSequenceNumber()).thenReturn(10L);
    when(param1.getDBColumnName()).thenReturn("Param1");
    when(param1.getDefaultValue()).thenReturn("default1");

    ProcessParameter param2 = mock(ProcessParameter.class);
    when(param2.isActive()).thenReturn(true);
    when(param2.getSequenceNumber()).thenReturn(20L);
    when(param2.getDBColumnName()).thenReturn("Param2");
    when(param2.getDefaultValue()).thenReturn(null);

    when(mockProcess.getADProcessParameterList()).thenReturn(List.of(param1, param2));

    when(mockSpec.getSpecType()).thenReturn("P");
    when(mockSpec.getProcess()).thenReturn(mockProcess);
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    SFEntity mockEntity = mock(SFEntity.class);
    SFField mockField1 = mock(SFField.class);
    SFField mockField2 = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(mockField1, mockField2);

    int[] result = PopulateSpecHelper.populate("spec-1", true);

    assertArrayEquals(new int[]{1, 2}, result);
    verify(mockField1).setJavaQualifier("Param1");
    verify(mockField1).setDefaultValue("default1");
    verify(mockField2).setJavaQualifier("Param2");
    verify(mockField2, never()).setDefaultValue(any());
  }

  // ---- Process spec: skips inactive parameters ───────────────────────

  @Test
  public void testPopulateProcessSkipsInactiveParameters() {
    Process mockProcess = mock(Process.class);
    when(mockProcess.getName()).thenReturn("Test Process");

    ProcessParameter activeParam = mock(ProcessParameter.class);
    when(activeParam.isActive()).thenReturn(true);
    when(activeParam.getSequenceNumber()).thenReturn(10L);
    when(activeParam.getDBColumnName()).thenReturn("Active");
    when(activeParam.getDefaultValue()).thenReturn(null);

    ProcessParameter inactiveParam = mock(ProcessParameter.class);
    when(inactiveParam.isActive()).thenReturn(false);

    when(mockProcess.getADProcessParameterList()).thenReturn(List.of(activeParam, inactiveParam));

    when(mockSpec.getSpecType()).thenReturn("P");
    when(mockSpec.getProcess()).thenReturn(mockProcess);
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    SFEntity mockEntity = mock(SFEntity.class);
    SFField mockField = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(mockField);

    int[] result = PopulateSpecHelper.populate("spec-1", true);

    assertArrayEquals(new int[]{1, 1}, result);
  }

  // ---- Process spec: includeAllMethods is ignored (always POST-only) ─

  @Test
  public void testPopulateProcessIgnoresIncludeAllMethods() {
    Process mockProcess = mock(Process.class);
    when(mockProcess.getName()).thenReturn("Test Process");
    when(mockProcess.getADProcessParameterList()).thenReturn(Collections.emptyList());

    when(mockSpec.getSpecType()).thenReturn("P");
    when(mockSpec.getProcess()).thenReturn(mockProcess);
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    SFEntity mockEntity = mock(SFEntity.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);

    // Even with includeAllMethods=true, process specs remain POST-only
    PopulateSpecHelper.populate("spec-1", true, true);

    verify(mockEntity).setPost(true);
    verify(mockEntity).setGet(false);
    verify(mockEntity).setPut(false);
    verify(mockEntity).setDelete(false);
  }

  // ---- deleteExistingChildren removes entities and fields ────────────

  @Test
  public void testPopulateDeletesExistingChildrenBeforeCreating() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);

    // Existing entities with child fields
    SFEntity existingEntity = mock(SFEntity.class);
    when(existingEntity.getId()).thenReturn("existing-entity-1");
    SFField existingField = mock(SFField.class);

    @SuppressWarnings("unchecked")
    OBCriteria<SFEntity> entityDeleteCrit = mock(OBCriteria.class);
    when(entityDeleteCrit.list()).thenReturn(List.of(existingEntity));

    @SuppressWarnings("unchecked")
    OBCriteria<SFField> fieldDeleteCrit = mock(OBCriteria.class);
    when(fieldDeleteCrit.list()).thenReturn(List.of(existingField));

    // New tab criteria returns empty
    @SuppressWarnings("unchecked")
    OBCriteria<Tab> tabCrit = mock(OBCriteria.class);
    when(tabCrit.list()).thenReturn(Collections.emptyList());

    when(mockDal.createCriteria(SFEntity.class)).thenReturn(entityDeleteCrit);
    when(mockDal.createCriteria(SFField.class)).thenReturn(fieldDeleteCrit);
    when(mockDal.createCriteria(Tab.class)).thenReturn(tabCrit);

    PopulateSpecHelper.populate("spec-1", true);

    verify(mockDal).remove(existingField);
    verify(mockDal).remove(existingEntity);
  }

  // ---- Window spec: entity audit fields are set ──────────────────────

  @Test
  public void testPopulateWindowSetsAuditFieldsOnEntity() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Order", 10L);
    stubTabCriteria(Collections.singletonList(tab));
    stubColumnCriteria(tab, Collections.emptyList());

    SFEntity mockEntity = mock(SFEntity.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);

    PopulateSpecHelper.populate("spec-1", true);

    verify(mockEntity).setCreatedBy(mockUser);
    verify(mockEntity).setUpdatedBy(mockUser);
    verify(mockEntity).setCreationDate(any());
    verify(mockEntity).setUpdated(any());
  }

  // ---- Window spec: field properties are correctly set ───────────────

  @Test
  public void testPopulateWindowSetsFieldProperties() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Order", 10L);
    stubTabCriteria(Collections.singletonList(tab));

    Column col = createMockColumn("DocumentNo");
    stubColumnCriteria(tab, Collections.singletonList(col));

    SFEntity mockEntity = mock(SFEntity.class);
    SFField mockField = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(mockField);

    PopulateSpecHelper.populate("spec-1", true);

    verify(mockField).setETGOSFEntity(mockEntity);
    verify(mockField).setADColumn(col);
    verify(mockField).setADModule(mockModule);
    verify(mockField).setClient(mockClient);
    verify(mockField).setOrganization(mockOrg);
    verify(mockField).setActive(true);
    verify(mockField).setIncluded(true);
    verify(mockField).setReadOnly(false);
    verify(mockField).setSeqNo(10L);
  }

  // ---- Window spec: entity properties are correctly set ──────────────

  @Test
  public void testPopulateWindowSetsEntityProperties() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Sales Order", 30L);
    stubTabCriteria(Collections.singletonList(tab));
    stubColumnCriteria(tab, Collections.emptyList());

    SFEntity mockEntity = mock(SFEntity.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);

    PopulateSpecHelper.populate("spec-1", true);

    verify(mockEntity).setName("Sales Order");
    verify(mockEntity).setETGOSFSpec(mockSpec);
    verify(mockEntity).setADTab(tab);
    verify(mockEntity).setADModule(mockModule);
    verify(mockEntity).setClient(mockClient);
    verify(mockEntity).setOrganization(mockOrg);
    verify(mockEntity).setActive(true);
    verify(mockEntity).setIncluded(true);
    verify(mockEntity).setSeqNo(30L);
  }

  // ---- System column case-insensitivity ──────────────────────────────

  @Test
  public void testSystemColumnMatchIsCaseInsensitive() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Tab", 10L);
    stubTabCriteria(Collections.singletonList(tab));

    // Lowercase system column names should still be excluded
    Column colCreated = createMockColumn("created");
    Column colRegular = createMockColumn("MyField");
    stubColumnCriteria(tab, List.of(colCreated, colRegular));

    SFEntity mockEntity = mock(SFEntity.class);
    SFField mockField = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(mockField);

    int[] result = PopulateSpecHelper.populate("spec-1", true);

    // Only MyField should be included
    assertArrayEquals(new int[]{1, 1}, result);
  }

  // ---- Window spec: field seqNo increments by 10 ─────────────────────

  @Test
  public void testPopulateWindowFieldSeqNoIncrementsBy10() {
    when(mockDal.get(SFSpec.class, "spec-1")).thenReturn(mockSpec);
    stubDeleteExistingChildrenCriteria();

    Tab tab = createMockTab("tab-1", "Tab", 10L);
    stubTabCriteria(Collections.singletonList(tab));

    Column col1 = createMockColumn("Col1");
    Column col2 = createMockColumn("Col2");
    Column col3 = createMockColumn("Col3");
    stubColumnCriteria(tab, List.of(col1, col2, col3));

    SFEntity mockEntity = mock(SFEntity.class);
    SFField field1 = mock(SFField.class);
    SFField field2 = mock(SFField.class);
    SFField field3 = mock(SFField.class);
    when(mockProvider.get(SFEntity.class)).thenReturn(mockEntity);
    when(mockProvider.get(SFField.class)).thenReturn(field1, field2, field3);

    PopulateSpecHelper.populate("spec-1", false);

    verify(field1).setSeqNo(10L);
    verify(field2).setSeqNo(20L);
    verify(field3).setSeqNo(30L);
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private Tab createMockTab(String id, String name, Long seqNo) {
    Tab tab = mock(Tab.class);
    Table table = mock(Table.class);
    when(tab.getId()).thenReturn(id);
    when(tab.getName()).thenReturn(name);
    when(tab.getSequenceNumber()).thenReturn(seqNo);
    when(tab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("table-" + id);
    return tab;
  }

  private Column createMockColumn(String dbColumnName) {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn(dbColumnName);
    return col;
  }

  @SuppressWarnings("unchecked")
  private void stubDeleteExistingChildrenCriteria() {
    OBCriteria<SFEntity> entityDeleteCrit = mock(OBCriteria.class);
    when(entityDeleteCrit.list()).thenReturn(Collections.emptyList());

    OBCriteria<SFField> fieldDeleteCrit = mock(OBCriteria.class);
    when(fieldDeleteCrit.list()).thenReturn(Collections.emptyList());

    when(mockDal.createCriteria(SFEntity.class)).thenReturn(entityDeleteCrit);
    when(mockDal.createCriteria(SFField.class)).thenReturn(fieldDeleteCrit);
  }

  @SuppressWarnings("unchecked")
  private void stubTabCriteria(List<Tab> tabs) {
    OBCriteria<Tab> tabCrit = mock(OBCriteria.class);
    when(tabCrit.list()).thenReturn(tabs);
    when(mockDal.createCriteria(Tab.class)).thenReturn(tabCrit);
  }

  @SuppressWarnings("unchecked")
  private void stubColumnCriteria(Tab tab, List<Column> columns) {
    OBCriteria<Column> colCrit = mock(OBCriteria.class);
    when(colCrit.list()).thenReturn(columns);
    when(mockDal.createCriteria(Column.class)).thenReturn(colCrit);
  }

  @SuppressWarnings("unchecked")
  private void stubColumnCriteriaChained(List<Column>... columnLists) {
    OBCriteria<Column>[] crits = new OBCriteria[columnLists.length];
    for (int i = 0; i < columnLists.length; i++) {
      crits[i] = mock(OBCriteria.class);
      when(crits[i].list()).thenReturn(columnLists[i]);
    }
    if (crits.length == 1) {
      when(mockDal.createCriteria(Column.class)).thenReturn(crits[0]);
    } else if (crits.length > 1) {
      OBCriteria<Column>[] rest = java.util.Arrays.copyOfRange(crits, 1, crits.length);
      when(mockDal.createCriteria(Column.class)).thenReturn(crits[0], rest);
    }
  }
}
