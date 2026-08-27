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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.selector.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.domain.ReferencedTable;
import org.openbravo.userinterface.selector.Selector;
import org.openbravo.userinterface.selector.SelectorField;

import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * Unit tests for {@link SelectorDescriptorResolver}.
 *
 * <p>All Etendo static singletons ({@link OBDal}, {@link ModelProvider}) are mocked
 * with {@link MockedStatic} in {@code setUp}/{@code tearDown} to guarantee test isolation
 * even when the full suite runs in a single JVM fork.</p>
 */
public class SelectorDescriptorResolverTest {

  // ── static singleton mocks ────────────────────────────────────────────────

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelProviderMock;

  private OBDal dal;
  private ModelProvider modelProvider;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    modelProvider = mock(ModelProvider.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
  }

  @After
  public void tearDown() {
    // Close in reverse creation order to avoid state leaks into the next test
    modelProviderMock.close();
    obDalMock.close();
  }

  // =========================================================================
  // resolveSearchableFragment — pure static method, no mocks needed
  // =========================================================================

  @Test
  public void resolveSearchableFragment_nonBlankProperty_returnsProperty() {
    String result = SelectorDescriptorResolver.resolveSearchableFragment("name", "bp.name");
    assertEquals("name", result);
  }

  @Test
  public void resolveSearchableFragment_blankPropertySafeClause_returnsClause() {
    String result = SelectorDescriptorResolver.resolveSearchableFragment("", "bp.name");
    assertEquals("bp.name", result);
  }

  @Test
  public void resolveSearchableFragment_blankPropertyDottedClause_returnsClause() {
    // dotted paths are still matched by SAFE_HQL_PATH because dots are included
    String result = SelectorDescriptorResolver.resolveSearchableFragment(null, "  entity.property  ");
    assertEquals("entity.property", result);
  }

  @Test
  public void resolveSearchableFragment_blankPropertyBlankClause_returnsNull() {
    assertNull(SelectorDescriptorResolver.resolveSearchableFragment(null, "   "));
  }

  @Test
  public void resolveSearchableFragment_blankPropertyNullClause_returnsNull() {
    assertNull(SelectorDescriptorResolver.resolveSearchableFragment("", null));
  }

  @Test
  public void resolveSearchableFragment_blankPropertyUnsafeClause_returnsNull() {
    // SQL injection attempt — unsafe chars must not pass SAFE_HQL_PATH
    String result = SelectorDescriptorResolver.resolveSearchableFragment("", "1=1 OR name");
    assertNull(result);
  }

  @Test
  public void resolveSearchableFragment_blankPropertySimpleAlphanumeric_returnsFragment() {
    String result = SelectorDescriptorResolver.resolveSearchableFragment(null, "searchKey");
    assertEquals("searchKey", result);
  }

  // =========================================================================
  // findIdentifierProperty — uses mocked Entity
  // =========================================================================

  @Test
  public void findIdentifierProperty_primitiveIdentifierProperty_returnsIt() {
    Property primitiveProp = mock(Property.class);
    when(primitiveProp.isPrimitive()).thenReturn(true);
    when(primitiveProp.getName()).thenReturn("searchKey");

    Entity entity = mock(Entity.class);
    when(entity.getIdentifierProperties()).thenReturn(Collections.singletonList(primitiveProp));

    assertEquals("searchKey", SelectorDescriptorResolver.findIdentifierProperty(entity));
  }

  @Test
  public void findIdentifierProperty_nonPrimitiveFirst_thenPrimitive_returnsPrimitive() {
    Property nonPrimitiveProp = mock(Property.class);
    when(nonPrimitiveProp.isPrimitive()).thenReturn(false);
    when(nonPrimitiveProp.getName()).thenReturn("businessPartner");

    Property primitiveProp = mock(Property.class);
    when(primitiveProp.isPrimitive()).thenReturn(true);
    when(primitiveProp.getName()).thenReturn("name");

    Entity entity = mock(Entity.class);
    when(entity.getIdentifierProperties()).thenReturn(Arrays.asList(nonPrimitiveProp, primitiveProp));

    assertEquals("name", SelectorDescriptorResolver.findIdentifierProperty(entity));
  }

  @Test
  public void findIdentifierProperty_noIdentifierProps_hasName_returnsName() {
    Entity entity = mock(Entity.class);
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(entity.hasProperty("searchKey")).thenReturn(false);

    assertEquals("name", SelectorDescriptorResolver.findIdentifierProperty(entity));
  }

  @Test
  public void findIdentifierProperty_noIdentifierProps_noName_hasSearchKey_returnsSearchKey() {
    Entity entity = mock(Entity.class);
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(false);
    when(entity.hasProperty("searchKey")).thenReturn(true);

    assertEquals("searchKey", SelectorDescriptorResolver.findIdentifierProperty(entity));
  }

  @Test
  public void findIdentifierProperty_noIdentifierProps_noName_noSearchKey_returnsId() {
    Entity entity = mock(Entity.class);
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(false);
    when(entity.hasProperty("searchKey")).thenReturn(false);

    assertEquals("id", SelectorDescriptorResolver.findIdentifierProperty(entity));
  }

  @Test
  public void findIdentifierProperty_onlyNonPrimitiveIdentifiers_noName_noSearchKey_returnsId() {
    Property nonPrimitive = mock(Property.class);
    when(nonPrimitive.isPrimitive()).thenReturn(false);
    when(nonPrimitive.getName()).thenReturn("bpRelation");

    Entity entity = mock(Entity.class);
    when(entity.getIdentifierProperties()).thenReturn(Collections.singletonList(nonPrimitive));
    when(entity.hasProperty("name")).thenReturn(false);
    when(entity.hasProperty("searchKey")).thenReturn(false);

    assertEquals("id", SelectorDescriptorResolver.findIdentifierProperty(entity));
  }

  // =========================================================================
  // hasObuiselSelector — delegates to findObuiselSelector via OBDal criteria
  // =========================================================================

  @SuppressWarnings("unchecked")
  private OBCriteria<Selector> selectorCriteria(Selector result) {
    OBCriteria<Selector> crit = mock(OBCriteria.class);
    when(crit.add(any())).thenReturn(crit);
    when(crit.setMaxResults(1)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(result);
    return crit;
  }

  @Test
  public void hasObuiselSelector_selectorFoundViaRefSearchKey_returnsTrue() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("REF-SK-001");

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);

    Selector sel = mock(Selector.class);
    OBCriteria<Selector> crit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(crit);

    assertTrue(SelectorDescriptorResolver.hasObuiselSelector(column));
  }

  @Test
  public void hasObuiselSelector_noRefSearchKey_selectorViaBaseRef_returnsTrue() {
    Reference baseRef = mock(Reference.class);
    // Not one of the three built-in ref types (TABLE/TABLEDIR/SEARCH)
    when(baseRef.getId()).thenReturn("CUSTOM-REF-99");

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(null);
    when(column.getReference()).thenReturn(baseRef);

    Selector sel = mock(Selector.class);
    OBCriteria<Selector> crit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(crit);

    assertTrue(SelectorDescriptorResolver.hasObuiselSelector(column));
  }

  @Test
  public void hasObuiselSelector_baseRefIsTableDir_returnsFalse() {
    Reference baseRef = mock(Reference.class);
    when(baseRef.getId()).thenReturn(NeoSelectorService.REF_TABLEDIR);

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(null);
    when(column.getReference()).thenReturn(baseRef);

    // No criteria interaction expected for the base ref path — return false
    assertFalse(SelectorDescriptorResolver.hasObuiselSelector(column));
  }

  @Test
  public void hasObuiselSelector_baseRefIsTable_returnsFalse() {
    Reference baseRef = mock(Reference.class);
    when(baseRef.getId()).thenReturn(NeoSelectorService.REF_TABLE);

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(null);
    when(column.getReference()).thenReturn(baseRef);

    assertFalse(SelectorDescriptorResolver.hasObuiselSelector(column));
  }

  @Test
  public void hasObuiselSelector_baseRefIsSearch_returnsFalse() {
    Reference baseRef = mock(Reference.class);
    when(baseRef.getId()).thenReturn(NeoSelectorService.REF_SEARCH);

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(null);
    when(column.getReference()).thenReturn(baseRef);

    assertFalse(SelectorDescriptorResolver.hasObuiselSelector(column));
  }

  @Test
  public void hasObuiselSelector_noSelectorFound_returnsFalse() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("REF-NOTHING");

    Reference baseRef = mock(Reference.class);
    when(baseRef.getId()).thenReturn("BASE-REF-OTHER");

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getReference()).thenReturn(baseRef);

    // Both criteria calls return null
    OBCriteria<Selector> crit = selectorCriteria(null);
    when(dal.createCriteria(Selector.class)).thenReturn(crit);

    assertFalse(SelectorDescriptorResolver.hasObuiselSelector(column));
  }

  @Test
  public void hasObuiselSelector_findSelectorThrowsException_returnsFalse() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("REF-BOOM");

    Reference baseRef = mock(Reference.class);
    when(baseRef.getId()).thenReturn("BASE-OTHER");

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getReference()).thenReturn(baseRef);

    // Make OBDal throw so findSelectorByReference enters the catch block
    when(dal.createCriteria(Selector.class)).thenThrow(new RuntimeException("DB unavailable"));

    assertFalse(SelectorDescriptorResolver.hasObuiselSelector(column));
  }

  // =========================================================================
  // resolveTarget — TableDir path (baseRefId = "19")
  // =========================================================================

  @Test
  public void resolveTarget_tableDirRef_columnEndsWithId_entityFound_returnsMeta() {
    Column column = columnWithNoObuiselSelector("C_BPartner_ID");
    when(column.getDBColumnName()).thenReturn("C_BPartner_ID");

    Entity entity = entityWithName("BusinessPartner");
    when(modelProvider.getEntityByTableName("C_BPartner")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLEDIR);

    assertNotNull(meta);
    assertEquals("BusinessPartner", meta.entityName);
  }

  @Test
  public void resolveTarget_tableDirRef_columnDoesNotEndWithId_returnsNull() {
    Column column = columnWithNoObuiselSelector("C_BPartner_Name");
    when(column.getDBColumnName()).thenReturn("C_BPartner_Name");

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLEDIR);

    assertNull(meta);
  }

  @Test
  public void resolveTarget_tableDirRef_entityNotFound_returnsNull() {
    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getDBColumnName()).thenReturn("M_Product_ID");
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(null);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLEDIR);

    assertNull(meta);
  }

  @Test
  public void resolveTarget_tableDirRef_modelProviderThrows_returnsNull() {
    Column column = columnWithNoObuiselSelector("AD_Org_ID");
    when(column.getDBColumnName()).thenReturn("AD_Org_ID");
    when(modelProvider.getEntityByTableName("AD_Org")).thenThrow(new RuntimeException("model error"));

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLEDIR);

    assertNull(meta);
  }

  // =========================================================================
  // resolveTarget — resolveRefTable path (non-TableDir, non-OBUISEL)
  // =========================================================================

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_refTableFound_displayColumnPresent_returnsMeta() {
    String referenceId = "REF-TABLE-001";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_BPartner_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_BPartner_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_BPartner");

    Column displayCol = mock(Column.class);
    when(displayCol.getDBColumnName()).thenReturn("Name");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(displayCol);
    when(refTable.getHqlwhereclause()).thenReturn(null);

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);

    Property nameProp = mock(Property.class);
    when(nameProp.getName()).thenReturn("name");

    Entity entity = entityWithName("BusinessPartner");
    when(entity.getPropertyByColumnName("Name")).thenReturn(nameProp);
    when(modelProvider.getEntityByTableName("C_BPartner")).thenReturn(entity);

    // No OBUISEL selector found (criteria returns null)
    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("BusinessPartner", meta.entityName);
    assertEquals("name", meta.displayProperty);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_refTableFound_noDisplayColumn_usesIdentifier() {
    String referenceId = "REF-TABLE-002";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("AD_User_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("AD_User_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("AD_User");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn(null);

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);

    // Entity has "name" as identifier
    Property nameProp = mock(Property.class);
    when(nameProp.isPrimitive()).thenReturn(true);
    when(nameProp.getName()).thenReturn("name");

    Entity entity = entityWithName("ADUser");
    when(entity.getIdentifierProperties()).thenReturn(Collections.singletonList(nameProp));
    when(modelProvider.getEntityByTableName("AD_User")).thenReturn(entity);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("name", meta.displayProperty);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_noRefSearchKey_returnsNull() {
    Column column = columnWithNoObuiselSelector("AD_User_ID");
    when(column.getReferenceSearchKey()).thenReturn(null);
    when(column.getDBColumnName()).thenReturn("AD_User_ID");

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    // resolveRefTable logs a warning and returns null; no _ID fallback for REF_TABLE
    assertNull(meta);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_noRefTableRecord_columnEndsWithId_fallsBackToTableDir() {
    String referenceId = "REF-TABLE-003";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("M_Warehouse_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Warehouse_ID");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(null); // no AD_Ref_Table record

    OBCriteria<Selector> selCrit = selectorCriteria(null);

    // Return the right criteria based on class
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Warehouse");
    when(modelProvider.getEntityByTableName("M_Warehouse")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    // Falls back to TableDir resolution (column ends with _ID)
    assertNotNull(meta);
    assertEquals("Warehouse", meta.entityName);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_noRefTableRecord_columnNotEndingWithId_returnsNull() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("REF-004");

    Column column = columnWithNoObuiselSelector("IsActive");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("IsActive");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(null);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNull(meta);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_refTableEntityNotFound_returnsNull() {
    String referenceId = "REF-TABLE-005";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_Tax_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_Tax_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_Tax");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn(null);

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    // Entity not found
    when(modelProvider.getEntityByTableName("C_Tax")).thenReturn(null);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNull(meta);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_withHqlWhereClause_propagatesWhereClause() {
    String referenceId = "REF-TABLE-006";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_Currency_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_Currency_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_Currency");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn("  e.active = true  ");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Currency");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(modelProvider.getEntityByTableName("C_Currency")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("e.active = true", meta.whereClause);
  }

  // =========================================================================
  // resolveTarget — resolveRefTable: AD_Ref_Table.SQLWhereClause fallback (ETP-4975)
  //
  // Classic Etendo stores the C_Tax_ID selector filter for C_OrderLine/C_InvoiceLine as a
  // classic SQL clause ("C_Tax.Parent_Tax_ID IS NULL") in SQLWhereClause, with Hqlwhereclause
  // left empty. Before the fix, resolveRefTable only read Hqlwhereclause, so this filter was
  // silently dropped and GO's manual tax selector showed compound taxes' "child" breakdown
  // rows that Classic hides.
  // =========================================================================

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_sqlWhereClauseOnly_translatesToHql() {
    String referenceId = "REF-TABLE-SQL-001";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_Currency_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_Currency_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_Currency");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn(null);
    when(refTable.getSQLWhereClause()).thenReturn("C_Currency.IsActive = 'Y'");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Property activeProp = mock(Property.class);
    when(activeProp.isPrimitive()).thenReturn(true);
    when(activeProp.getName()).thenReturn("active");

    Entity entity = entityWithName("Currency");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(entity.getTableName()).thenReturn("C_Currency");
    when(entity.getPropertyByColumnName("IsActive")).thenReturn(activeProp);
    when(modelProvider.getEntityByTableName("C_Currency")).thenReturn(entity);
    // convertSqlToHql resolves the entity a second time by DAL name (ModelProvider#getEntity)
    when(modelProvider.getEntity("Currency")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("e.active = 'Y'", meta.whereClause);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_hqlWhereClausePresent_ignoresSqlWhereClause() {
    String referenceId = "REF-TABLE-SQL-002";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_Currency_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_Currency_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_Currency");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    // Both populated — Hqlwhereclause must win, SQLWhereClause must never be consulted
    when(refTable.getHqlwhereclause()).thenReturn("e.active = true");
    when(refTable.getSQLWhereClause()).thenReturn("C_Currency.IsActive = 'Y'");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Currency");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(modelProvider.getEntityByTableName("C_Currency")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("e.active = true", meta.whereClause);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_noWhereClauseAtAll_whereClauseIsNull() {
    String referenceId = "REF-TABLE-SQL-003";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_Currency_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_Currency_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_Currency");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn(null);
    when(refTable.getSQLWhereClause()).thenReturn(null);

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Currency");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(modelProvider.getEntityByTableName("C_Currency")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertNull(meta.whereClause);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_taxParentTaxIdSqlClause_translatesToParentTaxRateId() {
    // Real-world case: C_Tax_ID reference on C_OrderLine/C_InvoiceLine. AD_Ref_Table stores
    // "C_Tax.Parent_Tax_ID IS NULL" in SQLWhereClause (hides compound-tax breakdown children,
    // e.g. the "(+10%)"/"(+1.4%)" rows under "Entregas IVA+RE 10+1.4%") with Hqlwhereclause
    // empty. Must translate to "e.parentTaxRate.id IS NULL".
    String referenceId = "REF-TABLE-TAX-158";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_Tax_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_Tax_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_Tax");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn(null);
    when(refTable.getSQLWhereClause()).thenReturn("C_Tax.Parent_Tax_ID IS NULL");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    // Parent_Tax_ID is a self-referencing FK on C_Tax -> DAL property "parentTaxRate"
    Entity parentTaxTargetEntity = mock(Entity.class);
    Property parentTaxRateProp = mock(Property.class);
    when(parentTaxRateProp.isPrimitive()).thenReturn(false);
    when(parentTaxRateProp.getTargetEntity()).thenReturn(parentTaxTargetEntity);
    when(parentTaxRateProp.getName()).thenReturn("parentTaxRate");

    Entity entity = entityWithName("FinancialMgmtTaxRate");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(entity.getTableName()).thenReturn("C_Tax");
    when(entity.getPropertyByColumnName("Parent_Tax_ID")).thenReturn(parentTaxRateProp);
    when(modelProvider.getEntityByTableName("C_Tax")).thenReturn(entity);
    when(modelProvider.getEntity("FinancialMgmtTaxRate")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("FinancialMgmtTaxRate", meta.entityName);
    assertEquals("e.parentTaxRate.id IS NULL", meta.whereClause);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_sqlWhereClauseWithNestedSubquery_dropsSubqueryClause() {
    // ETP-4975 (fixed) — SqlToHqlTranslator#convertSqlToHql now applies the same NESTED_SUBQUERY
    // guard SelectorValidationResolver#resolveValidationClause already applies to AD_Validation
    // clauses (both share SqlToHqlTranslator.NESTED_SUBQUERY): a top-level AND-segment containing
    // "(SELECT ..." is dropped instead of being translated into invalid HQL. Only the surviving
    // "C_Tax.Parent_Tax_ID IS NULL" segment is translated; the C_TaxCategory subquery segment is
    // discarded whole, so its raw SQL table name ("C_TaxCategory") never leaks into the emitted
    // HQL and Hibernate never sees a QuerySyntaxException for it.
    String referenceId = "REF-TABLE-TAX-SUBQ-159";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_Tax_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_Tax_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_Tax");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn(null);
    when(refTable.getSQLWhereClause()).thenReturn(
        "C_Tax.Parent_Tax_ID IS NULL AND C_Tax.C_TaxCategory_ID IN "
            + "(SELECT tc.C_TaxCategory_ID FROM C_TaxCategory tc WHERE tc.IsSummary='N')");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity parentTaxTargetEntity = mock(Entity.class);
    Property parentTaxRateProp = mock(Property.class);
    when(parentTaxRateProp.isPrimitive()).thenReturn(false);
    when(parentTaxRateProp.getTargetEntity()).thenReturn(parentTaxTargetEntity);
    when(parentTaxRateProp.getName()).thenReturn("parentTaxRate");

    Entity entity = entityWithName("FinancialMgmtTaxRate");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(entity.getTableName()).thenReturn("C_Tax");
    when(entity.getPropertyByColumnName("Parent_Tax_ID")).thenReturn(parentTaxRateProp);
    // C_TaxCategory_ID is never queried: the whole segment containing it is dropped by the guard
    // before column translation runs, so it is deliberately left unstubbed.
    when(modelProvider.getEntityByTableName("C_Tax")).thenReturn(entity);
    when(modelProvider.getEntity("FinancialMgmtTaxRate")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    // Only the surviving segment is translated — exactly as if the subquery segment had never
    // been configured. No trace of the dropped segment (translated or raw) remains.
    assertEquals("e.parentTaxRate.id IS NULL", meta.whereClause);
    assertFalse("nested subquery's raw SQL table name must never leak into HQL output",
        meta.whereClause.contains("C_TaxCategory"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_warehouseCorrelatedSubqueryWhereClause_dropsSubqueryClause() {
    // Real-world regression (ETP-4975) — the AD_Ref_Table for M_Warehouse_ID carries this exact
    // classic SQLWhereClause: a correlated subquery checking the warehouse's organization is
    // active. Before the guard, resolveRefTableWhereClause -> convertSqlToHql translated the
    // outer M_Warehouse.AD_Client_ID reference but left "ad_org"/"ad.isactive"/"ad.ad_org_id"
    // completely untranslated inside the emitted "HQL". Hibernate then threw
    // "QuerySyntaxException: ad_org is not mapped" the first time this selector was queried —
    // reproduced live by 2 E2E integration tests hitting this exact selector. With the guard, the
    // whole subquery segment is dropped and only the client-scoping segment survives, translated
    // correctly.
    String referenceId = "REF-TABLE-WAREHOUSE-160";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("M_Warehouse_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Warehouse_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("M_Warehouse");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(null);
    when(refTable.getHqlwhereclause()).thenReturn(null);
    when(refTable.getSQLWhereClause()).thenReturn(
        "M_Warehouse.AD_Client_ID=@#AD_Client_ID@ AND (select ad.isactive from ad_org ad "
            + "where ad.ad_org_id = M_Warehouse.AD_Org_ID) = 'Y'");

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    // AD_Client_ID is a FK column -> "client" DAL property on every AD entity.
    Entity clientTargetEntity = mock(Entity.class);
    Property clientProp = mock(Property.class);
    when(clientProp.isPrimitive()).thenReturn(false);
    when(clientProp.getTargetEntity()).thenReturn(clientTargetEntity);
    when(clientProp.getName()).thenReturn("client");

    Entity entity = entityWithName("Warehouse");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(entity.getTableName()).thenReturn("M_Warehouse");
    when(entity.getPropertyByColumnName("AD_Client_ID")).thenReturn(clientProp);
    // ad_org / ad.isactive / ad.ad_org_id are never queried against this entity: the whole
    // subquery segment referencing them is dropped by the guard before column translation runs.
    when(modelProvider.getEntityByTableName("M_Warehouse")).thenReturn(entity);
    when(modelProvider.getEntity("Warehouse")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    // Only the client-scoping segment survives, correctly translated; the subquery segment (and
    // its raw SQL table/column names) is gone entirely — not merely left untranslated.
    assertEquals("e.client.id=@#AD_Client_ID@", meta.whereClause);
    assertFalse("correlated subquery's raw SQL table name must never leak into HQL output",
        meta.whereClause.contains("ad_org"));
    assertFalse("correlated subquery's raw SQL column ref must never leak into HQL output",
        meta.whereClause.contains("ad.isactive"));
  }

  // =========================================================================
  // resolveTarget — OBUISEL path (selector found first)
  // =========================================================================

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_fullResolution_returnsRichMeta() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-REF");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Product");

    SelectorField displayField = mock(SelectorField.class);
    when(displayField.getProperty()).thenReturn("name");

    SelectorField valueField = mock(SelectorField.class);
    when(valueField.getProperty()).thenReturn("id");

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getHQL()).thenReturn(null);
    when(sel.getEntityAlias()).thenReturn("p");
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Product selector");
    when(sel.getDisplayfield()).thenReturn(displayField);
    when(sel.getValuefield()).thenReturn(valueField);
    when(sel.getHQLWhereClause()).thenReturn(null);
    when(sel.getOBUISELSelectorFieldList()).thenReturn(Collections.emptyList());

    // Criteria returns the selector
    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Property nameProp = mock(Property.class);
    when(nameProp.isPrimitive()).thenReturn(true);
    when(nameProp.getName()).thenReturn("name");

    Entity entity = entityWithName("Product");
    when(entity.getIdentifierProperties()).thenReturn(Collections.singletonList(nameProp));
    when(entity.hasProperty(anyString())).thenAnswer(inv -> "name".equals(inv.getArgument(0)));
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertTrue(meta.isRich);
    assertEquals("Product", meta.entityName);
    assertEquals("name", meta.displayProperty);
    assertEquals("id", meta.valueProperty);
    assertEquals("p", meta.entityAlias);
    assertFalse(meta.isCustomQuery);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_customQuery_setsCustomHql() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-CUSTOM-REF");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Product");

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(true);
    when(sel.getHQL()).thenReturn("SELECT p FROM Product p WHERE p.active = true");
    when(sel.getEntityAlias()).thenReturn("p");
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Product custom selector");
    when(sel.getDisplayfield()).thenReturn(null);
    when(sel.getValuefield()).thenReturn(null);
    when(sel.getHQLWhereClause()).thenReturn(null);
    when(sel.getOBUISELSelectorFieldList()).thenReturn(Collections.emptyList());

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Product");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(entity.hasProperty("searchKey")).thenReturn(false);
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertTrue(meta.isCustomQuery);
    assertEquals("SELECT p FROM Product p WHERE p.active = true", meta.customHql);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_nullTable_returnsNull() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-NULL-TABLE");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getTable()).thenReturn(null);
    when(sel.getName()).thenReturn("No-table selector");
    when(sel.getEntityAlias()).thenReturn("e");

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNull(meta);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_entityNotFound_returnsNull() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-NO-ENTITY");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("UnknownTable");

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Unknown entity selector");
    when(sel.getEntityAlias()).thenReturn("e");

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);
    when(modelProvider.getEntityByTableName("UnknownTable")).thenReturn(null);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNull(meta);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_defaultEntityAlias_usesE() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-ALIAS");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Product");

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getHQL()).thenReturn(null);
    when(sel.getEntityAlias()).thenReturn("  "); // blank → should use default "e"
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Product selector blank alias");
    when(sel.getDisplayfield()).thenReturn(null);
    when(sel.getValuefield()).thenReturn(null);
    when(sel.getHQLWhereClause()).thenReturn(null);
    when(sel.getOBUISELSelectorFieldList()).thenReturn(Collections.emptyList());

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Product");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty("name")).thenReturn(true);
    when(entity.hasProperty(anyString())).thenAnswer(inv -> "name".equals(inv.getArgument(0)));
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("e", meta.entityAlias);
  }

  // =========================================================================
  // resolveTarget — OBUISEL with SelectorField classification
  // =========================================================================

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_withGridAndSearchAndAuxFields_classifiesCorrectly() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-FIELDS-REF");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Product");

    // Grid field — active, showInGrid=true, searchInSuggestion=true
    SelectorField gridSearchField = mock(SelectorField.class);
    when(gridSearchField.isActive()).thenReturn(true);
    when(gridSearchField.isOutfield()).thenReturn(false);
    when(gridSearchField.getProperty()).thenReturn("name");
    when(gridSearchField.isShowingrid()).thenReturn(true);
    when(gridSearchField.isSearchinsuggestionbox()).thenReturn(true);
    when(gridSearchField.getClauseLeftPart()).thenReturn(null);
    when(gridSearchField.getSortno()).thenReturn(10L);
    when(gridSearchField.getName()).thenReturn("Name");

    // Aux field — outfield=true, suffix=_LOC
    SelectorField auxField = mock(SelectorField.class);
    when(auxField.isActive()).thenReturn(true);
    when(auxField.isOutfield()).thenReturn(true);
    when(auxField.getSuffix()).thenReturn("_LOC");
    when(auxField.getDisplayColumnAlias()).thenReturn("LOCATION_ALIAS");
    when(auxField.getName()).thenReturn("Location");
    when(auxField.getProperty()).thenReturn("locatorName");
    when(auxField.isShowingrid()).thenReturn(false);
    when(auxField.isSearchinsuggestionbox()).thenReturn(false);
    when(auxField.getClauseLeftPart()).thenReturn(null);
    when(auxField.getSortno()).thenReturn(20L);

    // Inactive field — should be skipped
    SelectorField inactiveField = mock(SelectorField.class);
    when(inactiveField.isActive()).thenReturn(false);

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getHQL()).thenReturn(null);
    when(sel.getEntityAlias()).thenReturn("p");
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Product fields selector");
    when(sel.getDisplayfield()).thenReturn(null);
    when(sel.getValuefield()).thenReturn(null);
    when(sel.getHQLWhereClause()).thenReturn("p.active = true");
    when(sel.getOBUISELSelectorFieldList()).thenReturn(
        Arrays.asList(gridSearchField, auxField, inactiveField));

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Product");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty(anyString())).thenAnswer(inv -> {
      String p = inv.getArgument(0);
      return "name".equals(p) || "searchKey".equals(p);
    });
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals(1, meta.gridFields.size());
    assertEquals("name", meta.gridFields.get(0).propertyKey);
    assertEquals("Name", meta.gridFields.get(0).label);
    assertEquals(1, meta.auxFields.size());
    assertEquals("_LOC", meta.auxFields.get(0).suffix);
    assertEquals("location_alias", meta.auxFields.get(0).hqlAlias);
    // "name" added both by grid-search classification and ensureSearchableFallback → no duplicates
    assertTrue(meta.searchableProperties.contains("name"));
    assertEquals("p.active = true", meta.whereClause);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_identifierSuffixFieldSkippedFromSearchable() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-IDENT-REF");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Product");

    // Field with _identifier suffix — must not be added to searchableProps
    SelectorField identField = mock(SelectorField.class);
    when(identField.isActive()).thenReturn(true);
    when(identField.isOutfield()).thenReturn(false);
    when(identField.getProperty()).thenReturn("businessPartner_identifier");
    when(identField.isShowingrid()).thenReturn(false);
    when(identField.isSearchinsuggestionbox()).thenReturn(true);
    when(identField.getClauseLeftPart()).thenReturn(null);
    when(identField.getSortno()).thenReturn(1L);
    when(identField.getName()).thenReturn("BP Identifier");

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getHQL()).thenReturn(null);
    when(sel.getEntityAlias()).thenReturn("e");
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Product ident selector");
    when(sel.getDisplayfield()).thenReturn(null);
    when(sel.getValuefield()).thenReturn(null);
    when(sel.getHQLWhereClause()).thenReturn(null);
    when(sel.getOBUISELSelectorFieldList()).thenReturn(Collections.singletonList(identField));

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Product");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty(anyString())).thenReturn(false);
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    // _identifier suffix field must never appear in searchableProperties
    for (String prop : meta.searchableProperties) {
      assertFalse("_identifier fields must be excluded from searchableProps",
          prop.endsWith("_identifier"));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_gridFieldsSortedBySortNo() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-SORT-REF");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Product");

    SelectorField fieldB = selectorGridField("description", "Description", 20L);
    SelectorField fieldA = selectorGridField("name", "Name", 10L);
    SelectorField fieldC = selectorGridField("searchKey", "Search Key", 5L);

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getHQL()).thenReturn(null);
    when(sel.getEntityAlias()).thenReturn("e");
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Product sort selector");
    when(sel.getDisplayfield()).thenReturn(null);
    when(sel.getValuefield()).thenReturn(null);
    when(sel.getHQLWhereClause()).thenReturn(null);
    when(sel.getOBUISELSelectorFieldList()).thenReturn(Arrays.asList(fieldB, fieldA, fieldC));

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Product");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty(anyString())).thenReturn(false);
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals(3, meta.gridFields.size());
    assertEquals(5L, meta.gridFields.get(0).sortNo);   // searchKey
    assertEquals(10L, meta.gridFields.get(1).sortNo);  // name
    assertEquals(20L, meta.gridFields.get(2).sortNo);  // description
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_nullSortNo_treatedAsZero() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-NULL-SORT");

    Column column = columnWithNoObuiselSelector("M_Product_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Product_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Product");

    SelectorField field = selectorGridField("name", "Name", null); // null sortNo

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getHQL()).thenReturn(null);
    when(sel.getEntityAlias()).thenReturn("e");
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Product null sort selector");
    when(sel.getDisplayfield()).thenReturn(null);
    when(sel.getValuefield()).thenReturn(null);
    when(sel.getHQLWhereClause()).thenReturn(null);
    when(sel.getOBUISELSelectorFieldList()).thenReturn(Collections.singletonList(field));

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Product");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty(anyString())).thenReturn(false);
    when(modelProvider.getEntityByTableName("M_Product")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals(1, meta.gridFields.size());
    assertEquals(0L, meta.gridFields.get(0).sortNo);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_obuiselSelector_auxFieldWithNullAlias_lowercasedToNull() {
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn("OBUISEL-AUX-NULL-ALIAS");

    Column column = columnWithNoObuiselSelector("M_Locator_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("M_Locator_ID");

    Table selectorTable = mock(Table.class);
    when(selectorTable.getDBTableName()).thenReturn("M_Locator");

    SelectorField auxField = mock(SelectorField.class);
    when(auxField.isActive()).thenReturn(true);
    when(auxField.isOutfield()).thenReturn(true);
    when(auxField.getSuffix()).thenReturn("_WH");
    when(auxField.getDisplayColumnAlias()).thenReturn(null); // null alias
    when(auxField.getName()).thenReturn("Warehouse");
    when(auxField.getProperty()).thenReturn("warehouse.name");
    when(auxField.isShowingrid()).thenReturn(false);
    when(auxField.isSearchinsuggestionbox()).thenReturn(false);
    when(auxField.getClauseLeftPart()).thenReturn(null);
    when(auxField.getSortno()).thenReturn(1L);

    Selector sel = mock(Selector.class);
    when(sel.isCustomQuery()).thenReturn(false);
    when(sel.getHQL()).thenReturn(null);
    when(sel.getEntityAlias()).thenReturn("e");
    when(sel.getTable()).thenReturn(selectorTable);
    when(sel.getName()).thenReturn("Locator selector");
    when(sel.getDisplayfield()).thenReturn(null);
    when(sel.getValuefield()).thenReturn(null);
    when(sel.getHQLWhereClause()).thenReturn(null);
    when(sel.getOBUISELSelectorFieldList()).thenReturn(Collections.singletonList(auxField));

    OBCriteria<Selector> selCrit = selectorCriteria(sel);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("Locator");
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty(anyString())).thenReturn(false);
    when(modelProvider.getEntityByTableName("M_Locator")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals(1, meta.auxFields.size());
    assertNull(meta.auxFields.get(0).hqlAlias);
  }

  // =========================================================================
  // resolveDisplayColumnProperty — via resolveRefTable with display column
  // =========================================================================

  @SuppressWarnings("unchecked")
  @Test
  public void resolveTarget_tableRef_displayColumnPropertyNotFound_fallsBackToName() {
    String referenceId = "REF-DISPLAY-001";
    Reference refSearchKey = mock(Reference.class);
    when(refSearchKey.getId()).thenReturn(referenceId);

    Column column = columnWithNoObuiselSelector("C_BPartner_ID");
    when(column.getReferenceSearchKey()).thenReturn(refSearchKey);
    when(column.getDBColumnName()).thenReturn("C_BPartner_ID");

    Table targetTable = mock(Table.class);
    when(targetTable.getDBTableName()).thenReturn("C_BPartner");

    Column displayCol = mock(Column.class);
    when(displayCol.getDBColumnName()).thenReturn("NonExistentColumn");

    ReferencedTable refTable = mock(ReferencedTable.class);
    when(refTable.getTable()).thenReturn(targetTable);
    when(refTable.getDisplayedColumn()).thenReturn(displayCol);
    when(refTable.getHqlwhereclause()).thenReturn(null);

    OBCriteria<ReferencedTable> refTableCrit = mock(OBCriteria.class);
    when(refTableCrit.add(any())).thenReturn(refTableCrit);
    when(refTableCrit.setMaxResults(1)).thenReturn(refTableCrit);
    when(refTableCrit.uniqueResult()).thenReturn(refTable);

    OBCriteria<Selector> selCrit = selectorCriteria(null);
    when(dal.createCriteria(ReferencedTable.class)).thenReturn(refTableCrit);
    when(dal.createCriteria(Selector.class)).thenReturn(selCrit);

    Entity entity = entityWithName("BusinessPartner");
    when(entity.getPropertyByColumnName("NonExistentColumn")).thenReturn(null); // not found
    when(modelProvider.getEntityByTableName("C_BPartner")).thenReturn(entity);

    SelectorMeta meta = SelectorDescriptorResolver.resolveTarget(column, NeoSelectorService.REF_TABLE);

    assertNotNull(meta);
    assertEquals("name", meta.displayProperty); // fallback
  }

  // =========================================================================
  // Private helpers
  // =========================================================================

  /**
   * Create a column mock configured so that findObuiselSelector returns null
   * (no OBUISEL selector is found) for the given DB column name.
   * The column has no referenceSearchKey and uses the TABLE ref type so
   * findSelectorByReference is not called for the base-ref path.
   */
  private Column columnWithNoObuiselSelector(String dbColumnName) {
    Reference baseRef = mock(Reference.class);
    when(baseRef.getId()).thenReturn(NeoSelectorService.REF_TABLE);

    Column column = mock(Column.class);
    when(column.getReferenceSearchKey()).thenReturn(null);
    when(column.getReference()).thenReturn(baseRef);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    return column;
  }

  private Entity entityWithName(String entityName) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(entityName);
    when(entity.getIdentifierProperties()).thenReturn(Collections.emptyList());
    when(entity.hasProperty(anyString())).thenReturn(false);
    when(entity.getPropertyByColumnName(anyString())).thenReturn(null);
    return entity;
  }

  private SelectorField selectorGridField(String property, String name, Long sortNo) {
    SelectorField sf = mock(SelectorField.class);
    when(sf.isActive()).thenReturn(true);
    when(sf.isOutfield()).thenReturn(false);
    when(sf.getProperty()).thenReturn(property);
    when(sf.isShowingrid()).thenReturn(true);
    when(sf.isSearchinsuggestionbox()).thenReturn(false);
    when(sf.getClauseLeftPart()).thenReturn(null);
    when(sf.getSortno()).thenReturn(sortNo);
    when(sf.getName()).thenReturn(name);
    return sf;
  }
}
