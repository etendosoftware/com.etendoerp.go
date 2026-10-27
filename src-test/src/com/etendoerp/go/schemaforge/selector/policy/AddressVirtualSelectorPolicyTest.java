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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link AddressVirtualSelectorPolicy} (ETP-5368).
 *
 * <p>{@code locationColumns()} is the only method that touches the DAL. Every case whose entity is
 * NOT an address wrapper stops before reaching it — that is the point, and a guard that stopped
 * working would let one of those reach OBDal and fail outright, which is exactly the regression
 * signal we want. The cases that ARE about a wrapper have to get past the guard by definition, so
 * they stub that one query through {@link #stubLocationColumns}; they must never be rewritten to
 * avoid it, because what they assert only means anything once the column really resolved.</p>
 *
 * <p>Also the ETP-5806 {@code structured-address-for-sepa} flag-OFF branch of
 * {@link AddressVirtualSelectorPolicy#resolveVirtualColumns}, with
 * {@link GoFeatureFlags#isEnabled} stubbed to {@code false} directly, independent of the local
 * {@code Openbravo.properties} toggle: the 3 structured SEPA columns are excluded.
 *
 * @covers com.etendoerp.go.schemaforge.selector.policy.AddressVirtualSelectorPolicy
 */
public class AddressVirtualSelectorPolicyTest {

  private static final String LOCATION_TABLE = "C_Location";
  private static final String WRAPPER_TABLE = "C_BPartner_Location";

  private static SFEntity entityOnTable(String tableName) {
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(tableName);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(tab);
    return entity;
  }

  /** A {@code C_Location} AD_Column as {@code locationColumns()} reads one. */
  private static Column locationColumn(String dbColumnName) {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    return column;
  }

  /**
   * Stands in for the single query {@code locationColumns()} runs, plus the DAL entity it uses to
   * key each column under its property name as well as its DB name.
   *
   * <p>Only the wrapper cases need this: the policy reaches the DAL exactly when the wrapper guard
   * lets it through, so stubbing the query is how those cases assert on a column that really
   * resolved rather than on one that was never found.</p>
   *
   * @param dal     an open static mock of {@link OBDal}
   * @param models  an open static mock of {@link ModelProvider}
   * @param columns the rows the query returns, keyed DB column name to DAL property name; a
   *                {@code null} property name means the column has no property alias
   * @return the mocked columns, in iteration order, so a caller can assert identity
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Column> stubLocationColumns(MockedStatic<OBDal> dal,
      MockedStatic<ModelProvider> models, Map<String, String> columns) {
    Map<String, Column> built = new LinkedHashMap<>();
    List<Column> rows = new ArrayList<>();
    Entity locationEntity = mock(Entity.class);
    for (Map.Entry<String, String> entry : columns.entrySet()) {
      Column column = locationColumn(entry.getKey());
      built.put(entry.getKey(), column);
      rows.add(column);
      Property property = null;
      if (entry.getValue() != null) {
        property = mock(Property.class);
        when(property.getName()).thenReturn(entry.getValue());
      }
      when(locationEntity.getPropertyByColumnName(entry.getKey(), false)).thenReturn(property);
    }

    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(criteria.createAlias(anyString(), anyString())).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.list()).thenReturn(rows);
    OBDal obDal = mock(OBDal.class);
    when(obDal.createCriteria(Column.class)).thenReturn(criteria);
    dal.when(OBDal::getInstance).thenReturn(obDal);

    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName(LOCATION_TABLE)).thenReturn(locationEntity);
    models.when(ModelProvider::getInstance).thenReturn(modelProvider);
    return built;
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

  /**
   * ETP-5806 — a structural third path: an entity whose own table carries an OPTIONAL
   * {@code locationAddress} FK to {@code C_Location} (e.g. {@code AD_OrgInfo}) is a wrapper too,
   * even though it is neither {@code C_BPartner_Location} nor named {@code locationAddress}.
   */
  @Test
  public void entityWithOptionalLocationPointerIsAnAddressWrapper() {
    Property property = mock(Property.class);
    when(property.isPrimitive()).thenReturn(false);
    Entity locationTargetEntity = mock(Entity.class);
    when(locationTargetEntity.getName()).thenReturn(org.openbravo.model.common.geography.Location.ENTITY_NAME);
    when(property.getTargetEntity()).thenReturn(locationTargetEntity);
    when(property.isMandatory()).thenReturn(false);
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getProperty("locationAddress", false)).thenReturn(property);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("AD_OrgInfo")).thenReturn(dalEntity);

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      assertTrue(AddressVirtualSelectorPolicy.isAddressWrapper(entityOnTable("AD_OrgInfo")));
    }
  }

  /**
   * ETP-5806 — the near-collision this ticket flagged rather than silently resolved:
   * {@code M_Warehouse} declares the exact same {@code locationAddress} property name and target
   * table as the optional-pointer case above, but its FK is NOT NULL (the warehouse cannot exist
   * without a location). It is the owner of a mandatory address, already served by its own
   * tab-less {@code warehouse/location} entity — not a thin wrapper — so it must NOT be reported
   * as an address wrapper.
   */
  @Test
  public void mandatoryLocationPointerIsNotAnAddressWrapper() {
    Property property = mock(Property.class);
    when(property.isPrimitive()).thenReturn(false);
    Entity locationTargetEntity = mock(Entity.class);
    when(locationTargetEntity.getName()).thenReturn(org.openbravo.model.common.geography.Location.ENTITY_NAME);
    when(property.getTargetEntity()).thenReturn(locationTargetEntity);
    when(property.isMandatory()).thenReturn(true);
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getProperty("locationAddress", false)).thenReturn(property);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("M_Warehouse")).thenReturn(dalEntity);

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      assertFalse(AddressVirtualSelectorPolicy.isAddressWrapper(entityOnTable("M_Warehouse")));
    }
  }

  @Test
  public void entityWithNoLocationPointerPropertyIsNotAnAddressWrapper() {
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getProperty("locationAddress", false)).thenReturn(null);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("C_Order")).thenReturn(dalEntity);

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      assertFalse(AddressVirtualSelectorPolicy.isAddressWrapper(entityOnTable("C_Order")));
    }
  }

  // ── resolveVirtualSelectorColumn ──────────────────────────────────────

  @Test
  public void nonForeignKeyVirtualColumnHasNoSelector() {
    // Address1 and RegionName ARE virtual columns of the wrapper — they resolve, which is why the
    // query is stubbed rather than left to answer nothing — but they are free text: asking for a
    // selector over them is a caller error and must keep answering as one. SELECTOR_COLUMNS is
    // narrower than VIRTUAL_COLUMNS on purpose, and widening it would answer here instead of
    // refusing.
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("Address1", "addressLine1");
    columns.put("RegionName", "regionName");

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
         MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      stubLocationColumns(dal, models, columns);

      assertNull(AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
          entityOnTable(WRAPPER_TABLE), "Address1"));
      assertNull(AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
          entityOnTable(WRAPPER_TABLE), "RegionName"));
      // Also refused under the property spelling, which is the name etendo_schema publishes.
      assertNull(AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
          entityOnTable(WRAPPER_TABLE), "addressLine1"));
    }
  }

  @Test
  public void foreignKeyVirtualColumnResolvesUnderEitherSpelling() {
    // The counterweight to the test above: the refusal there has to be about SELECTOR_COLUMNS, not
    // about nothing ever resolving. C_Region_ID does resolve, and under both names the two front
    // doors use — the SPA's selector URL names the DB column, etendo_schema publishes the property.
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("C_Region_ID", "region");
    columns.put("RegionName", "regionName");

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
         MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      Map<String, Column> built = stubLocationColumns(dal, models, columns);
      Column region = built.get("C_Region_ID");

      assertSame(region, AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
          entityOnTable(WRAPPER_TABLE), "C_Region_ID"));
      assertSame(region, AddressVirtualSelectorPolicy.resolveVirtualSelectorColumn(
          entityOnTable(WRAPPER_TABLE), "region"));
    }
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

  /**
   * ETP-5806 — the 3 structured SEPA columns are declared in {@code VIRTUAL_COLUMNS}, so a
   * wrapper's schema walk resolves them exactly like the pre-existing address columns.
   */
  @Test
  public void wrapperExposesTheStructuredAddressColumns() {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("EM_OBSEPA_StrtNm", "oBSEPAStreetName");
    columns.put("EM_OBSEPA_BldgNb", "oBSEPABuildingNumber");
    columns.put("EM_OBSEPA_BldgNm", "oBSEPABuildingName");

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
         MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class);
         MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      stubLocationColumns(dal, models, columns);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

      List<Column> resolved = AddressVirtualSelectorPolicy.resolveVirtualColumns(
          entityOnTable(WRAPPER_TABLE));

      assertEquals(3, resolved.size());
    }
  }

  /**
   * ETP-5806 — with {@code structured-address-for-sepa} off, a wrapper's schema walk must exclude
   * the 3 structured SEPA columns entirely, the same "omit, don't null" contract as the REST
   * handlers' GET response. Other, pre-existing virtual columns the wrapper declares are
   * unaffected by the flag.
   */
  @Test
  public void wrapperExcludesTheStructuredAddressColumnsWhenTheFlagIsOff() {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("EM_OBSEPA_StrtNm", "oBSEPAStreetName");
    columns.put("EM_OBSEPA_BldgNb", "oBSEPABuildingNumber");
    columns.put("EM_OBSEPA_BldgNm", "oBSEPABuildingName");
    columns.put("Address1", "addressLine1");

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
         MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class);
         MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      stubLocationColumns(dal, models, columns);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

      List<Column> resolved = AddressVirtualSelectorPolicy.resolveVirtualColumns(
          entityOnTable(WRAPPER_TABLE));

      assertEquals(1, resolved.size());
      assertEquals("Address1", resolved.get(0).getDBColumnName());
    }
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
    // This runs on every etendo_schema call, for every entity — an unmapped table must answer empty
    // rather than NPE its way out of the whole schema response.
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("C_BPartner_Location")).thenReturn(null);

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      assertTrue(AddressVirtualSelectorPolicy.serverResolvedFieldNames(
          entityOnTable("C_BPartner_Location")).isEmpty());
    }
  }

  // ── publishedNameOverride ─────────────────────────────────────────────

  /**
   * ETP-5806 naming unification — the 3 structured SEPA columns must publish the names every
   * handler agrees on in its own JSON, not the raw Hibernate property Openbravo's codegen
   * produced for them.
   */
  @Test
  public void structuredAddressColumnsPublishTheirUnifiedFieldNames() {
    assertEquals("streetName", AddressVirtualSelectorPolicy.publishedNameOverride("EM_OBSEPA_StrtNm"));
    assertEquals("buildingNumber", AddressVirtualSelectorPolicy.publishedNameOverride("EM_OBSEPA_BldgNb"));
    assertEquals("buildingName", AddressVirtualSelectorPolicy.publishedNameOverride("EM_OBSEPA_BldgNm"));
  }

  @Test
  public void publishedNameOverrideIsCaseInsensitiveOnTheDbColumnName() {
    assertEquals("streetName", AddressVirtualSelectorPolicy.publishedNameOverride("em_obsepa_strtnm"));
  }

  @Test
  public void unmappedColumnHasNoPublishedNameOverride() {
    assertNull(AddressVirtualSelectorPolicy.publishedNameOverride("C_Region_ID"));
  }

  @Test
  public void nullColumnNameHasNoPublishedNameOverride() {
    assertNull(AddressVirtualSelectorPolicy.publishedNameOverride(null));
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
