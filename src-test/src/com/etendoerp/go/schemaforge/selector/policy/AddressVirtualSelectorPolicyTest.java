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

package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link AddressVirtualSelectorPolicy} (ETP-5368).
 *
 * <p>Every case here stops before {@code locationColumns()}, which is the only method that touches
 * the DAL — that is the point. A guard that stopped working would let one of these reach OBDal and
 * fail outright, which is exactly the regression signal we want.</p>
 */
public class AddressVirtualSelectorPolicyTest {

  private static SFEntity entityOnTable(String tableName) {
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(tableName);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(tab);
    return entity;
  }

  // ── isAddressWrapper ──────────────────────────────────────────────────

  @Test
  public void bpartnerLocationTableIsAnAddressWrapper() {
    assertTrue(AddressVirtualSelectorPolicy.isAddressWrapper(
        entityOnTable("C_BPartner_Location")));
  }

  @Test
  public void locationAddressEntityNameIsAnAddressWrapper() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(null);
    when(entity.getName()).thenReturn("locationAddress");

    assertTrue(AddressVirtualSelectorPolicy.isAddressWrapper(entity));
  }

  @Test
  public void unrelatedEntityIsNotAnAddressWrapper() {
    assertFalse(AddressVirtualSelectorPolicy.isAddressWrapper(entityOnTable("C_Order")));
  }

  @Test
  public void nullEntityIsNotAnAddressWrapper() {
    assertFalse(AddressVirtualSelectorPolicy.isAddressWrapper(null));
  }

  // ── resolveVirtualSelectorColumn ──────────────────────────────────────

  @Test
  public void nonForeignKeyVirtualColumnHasNoSelector() {
    // Address1 is a virtual column of the wrapper, but free text: asking for a selector over it is
    // a caller error and must keep answering as one. SELECTOR_COLUMNS is narrower than
    // VIRTUAL_COLUMNS on purpose, and widening it would answer here instead of refusing.
    assertNull(AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
        entityOnTable("C_BPartner_Location"), "Address1"));
    assertNull(AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
        entityOnTable("C_BPartner_Location"), "RegionName"));
  }

  @Test
  public void blankColumnNameHasNoSelector() {
    assertNull(AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
        entityOnTable("C_BPartner_Location"), "   "));
  }

  @Test
  public void unrelatedEntityResolvesNoSelectorColumn() {
    assertNull(AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
        entityOnTable("C_Order"), "C_Region_ID"));
  }

  // ── resolveVirtualColumns ─────────────────────────────────────────────

  @Test
  public void unrelatedEntityExposesNoVirtualColumns() {
    // Empty, never null: callers append the result unconditionally.
    assertEquals(0, AddressVirtualSelectorPolicy.resolveVirtualColumns(entityOnTable("C_Order"))
        .size());
  }

  @Test
  public void nullEntityExposesNoVirtualColumns() {
    assertEquals(0, AddressVirtualSelectorPolicy.resolveVirtualColumns(null).size());
  }

  // ── serverResolvedFieldNames ──────────────────────────────────────────

  @Test
  public void wrapperReportsItsOwnLocationFieldAsServerResolved() {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn("locationAddress");
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getPropertyByColumnName("C_Location_ID", false)).thenReturn(property);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("C_BPartner_Location")).thenReturn(dalEntity);

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      Set<String> names = AddressVirtualSelectorPolicy.serverResolvedFieldNames(
          entityOnTable("C_BPartner_Location"));

      // The published FIELD name, not the column: the create view matches it against the name a
      // descriptor carries, so returning C_Location_ID here would union in something that matches
      // nothing and the field would stay in `required` with nobody the wiser.
      assertEquals(Set.of("locationAddress"), names);
    }
  }

  @Test
  public void unrelatedEntityReportsNoServerResolvedField() {
    assertTrue(AddressVirtualSelectorPolicy.serverResolvedFieldNames(entityOnTable("C_Order"))
        .isEmpty());
  }

  @Test
  public void nullEntityReportsNoServerResolvedField() {
    assertTrue(AddressVirtualSelectorPolicy.serverResolvedFieldNames(null).isEmpty());
  }

  @Test
  public void wrapperWithoutTabReportsNoServerResolvedField() {
    // Recognised by name, so the wrapper check passes and the tab lookup is what must not throw.
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(null);
    when(entity.getName()).thenReturn("locationAddress");

    assertTrue(AddressVirtualSelectorPolicy.serverResolvedFieldNames(entity).isEmpty());
  }

  @Test
  public void wrapperWithoutTableReportsNoServerResolvedField() {
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(null);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(tab);
    when(entity.getName()).thenReturn("locationAddress");

    assertTrue(AddressVirtualSelectorPolicy.serverResolvedFieldNames(entity).isEmpty());
  }

  @Test
  public void unmappedTableReportsNoServerResolvedField() {
    // This runs on every neo_schema call, for every entity — an unmapped table must answer empty
    // rather than NPE its way out of the whole schema response.
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("C_BPartner_Location")).thenReturn(null);

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      assertTrue(AddressVirtualSelectorPolicy.serverResolvedFieldNames(
          entityOnTable("C_BPartner_Location")).isEmpty());
    }
  }

  @Test
  public void missingPropertyReportsNoServerResolvedField() {
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getPropertyByColumnName("C_Location_ID", false)).thenReturn(null);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("C_BPartner_Location")).thenReturn(dalEntity);

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      assertTrue(AddressVirtualSelectorPolicy.serverResolvedFieldNames(
          entityOnTable("C_BPartner_Location")).isEmpty());
    }
  }
}
