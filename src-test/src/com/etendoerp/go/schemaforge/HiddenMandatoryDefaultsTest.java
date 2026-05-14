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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

/**
 * Unit tests for hidden mandatory defaults resolution in
 * {@link NeoDefaultsService}.
 *
 * <p>These tests call package-private helpers and mock the DAL dependencies.
 * They do not require a database connection.</p>
 */
public class HiddenMandatoryDefaultsTest {

  // ── resolveHiddenMandatoryDefaults — null guards ─────────────────────

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsNullTab() throws Exception {
    JSONObject defaults = new JSONObject();
    Set<String> sfFieldColumns = new HashSet<>();

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, null, null, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsNullTable() throws Exception {
    JSONObject defaults = new JSONObject();
    Tab tab = Mockito.mock(Tab.class);
    when(tab.getTable()).thenReturn(null);
    Set<String> sfFieldColumns = new HashSet<>();

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, null, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }

  // ── resolveHiddenMandatoryDefaults — column filtering ────────────────

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsInactiveColumns() throws Exception {
    JSONObject defaults = new JSONObject();
    Tab tab = Mockito.mock(Tab.class);
    Table table = Mockito.mock(Table.class);
    Column inactiveCol = Mockito.mock(Column.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getADColumnList()).thenReturn(java.util.Arrays.asList(inactiveCol));
    when(inactiveCol.isActive()).thenReturn(false);
    when(inactiveCol.isMandatory()).thenReturn(true);
    when(inactiveCol.getDBColumnName()).thenReturn("C_Inactive_ID");

    Set<String> sfFieldColumns = new HashSet<>();

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, null, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsNonMandatoryColumns() throws Exception {
    JSONObject defaults = new JSONObject();
    Tab tab = Mockito.mock(Tab.class);
    Table table = Mockito.mock(Table.class);
    Column optionalCol = Mockito.mock(Column.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getADColumnList()).thenReturn(java.util.Arrays.asList(optionalCol));
    when(optionalCol.isActive()).thenReturn(true);
    when(optionalCol.isMandatory()).thenReturn(false);
    when(optionalCol.getDBColumnName()).thenReturn("Description");

    Set<String> sfFieldColumns = new HashSet<>();

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, null, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsKeyColumns() throws Exception {
    JSONObject defaults = new JSONObject();
    Tab tab = Mockito.mock(Tab.class);
    Table table = Mockito.mock(Table.class);
    Column keyCol = Mockito.mock(Column.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getADColumnList()).thenReturn(java.util.Arrays.asList(keyCol));
    when(keyCol.isActive()).thenReturn(true);
    when(keyCol.isMandatory()).thenReturn(true);
    when(keyCol.isKeyColumn()).thenReturn(Boolean.TRUE);
    when(keyCol.getDBColumnName()).thenReturn("C_Order_ID");

    Set<String> sfFieldColumns = new HashSet<>();

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, null, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsColumnsInSFField() throws Exception {
    JSONObject defaults = new JSONObject();
    Tab tab = Mockito.mock(Tab.class);
    Table table = Mockito.mock(Table.class);
    Column col = Mockito.mock(Column.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getADColumnList()).thenReturn(java.util.Arrays.asList(col));
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(true);
    when(col.isKeyColumn()).thenReturn(Boolean.FALSE);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

    Set<String> sfFieldColumns = new HashSet<>();
    sfFieldColumns.add("C_BPARTNER_ID");

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, null, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsAuditColumns() throws Exception {
    JSONObject defaults = new JSONObject();
    Tab tab = Mockito.mock(Tab.class);
    Table table = Mockito.mock(Table.class);
    Column auditCol = Mockito.mock(Column.class);
    Entity entity = Mockito.mock(Entity.class);
    Property prop = Mockito.mock(Property.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getADColumnList()).thenReturn(java.util.Arrays.asList(auditCol));
    when(auditCol.isActive()).thenReturn(true);
    when(auditCol.isMandatory()).thenReturn(true);
    when(auditCol.isKeyColumn()).thenReturn(Boolean.FALSE);
    when(auditCol.getDBColumnName()).thenReturn("Updated");
    when(entity.getPropertyByColumnName("Updated")).thenReturn(prop);
    when(prop.isAuditInfo()).thenReturn(true);

    Set<String> sfFieldColumns = new HashSet<>();

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, entity, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }

  @Test
  public void testResolveHiddenMandatoryDefaultsSkipsAlreadyResolvedColumns() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("transactionDocument", "DOC-001");

    Tab tab = Mockito.mock(Tab.class);
    Table table = Mockito.mock(Table.class);
    Column col = Mockito.mock(Column.class);
    Entity entity = Mockito.mock(Entity.class);
    Property prop = Mockito.mock(Property.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getADColumnList()).thenReturn(java.util.Arrays.asList(col));
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(true);
    when(col.isKeyColumn()).thenReturn(Boolean.FALSE);
    when(col.getDBColumnName()).thenReturn("C_DocTypeTarget_ID");
    when(entity.getPropertyByColumnName("C_DocTypeTarget_ID")).thenReturn(prop);
    when(prop.isAuditInfo()).thenReturn(false);
    when(prop.getName()).thenReturn("transactionDocument");

    Set<String> sfFieldColumns = new HashSet<>();

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, entity, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(1, defaults.length());
    assertEquals("DOC-001", defaults.getString("transactionDocument"));
  }

  @Test
  public void testResolveHiddenMandatoryDefaultsCaseInsensitiveColumnCheck() throws Exception {
    JSONObject defaults = new JSONObject();
    Tab tab = Mockito.mock(Tab.class);
    Table table = Mockito.mock(Table.class);
    Column col = Mockito.mock(Column.class);

    when(tab.getTable()).thenReturn(table);
    when(table.getADColumnList()).thenReturn(java.util.Arrays.asList(col));
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(true);
    when(col.isKeyColumn()).thenReturn(Boolean.FALSE);
    when(col.getDBColumnName()).thenReturn("c_bpartner_id");

    Set<String> sfFieldColumns = new HashSet<>();
    sfFieldColumns.add("C_BPARTNER_ID");

    NeoDefaultsService.resolveHiddenMandatoryDefaults(
        defaults, null, tab, null, null, null, null, null, sfFieldColumns);

    assertEquals(0, defaults.length());
  }
}
