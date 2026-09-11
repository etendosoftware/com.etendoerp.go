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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.util.NeoSessionVarsCache;

/**
 * ETP-5184 — regression guard for the callout-param precedence chain in
 * {@link CalloutRequestBuilder#buildRequestParams}.
 *
 * <p><b>Defect guarded.</b> {@code buildRequestParams} used to call
 * {@code fillMissingColumnDefaults} <em>before</em> {@code injectParentTabParams}. Both write the
 * same {@code inp*} keys and both skip a key that is already present, so whoever runs second
 * loses. Running the child tab's AD defaults first therefore froze every column shared by the
 * child and the header at the child's own default — and those defaults are window-context
 * references ({@code @C_BPartner_Location_ID@}, {@code @M_Warehouse_ID@}) that resolve to EMPTY
 * under NEO, which has no window context. On a sales-order line that meant {@code C_GetTax}
 * received a NULL ship-to location and fell through to "any tax flagged IsDefault": a silently
 * wrong tax rate.</p>
 *
 * <p>The fix is nothing but the order of those two calls, and nothing in the code prevents
 * swapping them back. Each test below pins one link of the chain the callouts need:</p>
 *
 * <pre>
 *   client body  &gt;  parent record  &gt;  child AD column default  &gt;  session
 * </pre>
 *
 * <p>{@link #parentRecordWinsOverChildColumnDefault()} and
 * {@link #parentWarehouseWinsOverChildColumnDefault()} are the two that fail if the order is
 * reverted.</p>
 */
public class CalloutRequestBuilderPrecedenceTest {

  private static final String CHILD_TABLE_ID = "C_ORDERLINE";
  private static final String PARENT_TABLE_ID = "C_ORDER";
  private static final String PARENT_ID = "ORDER-HEADER-001";

  private static final String COL_LOCATION = "C_BPartner_Location_ID";
  private static final String COL_WAREHOUSE = "M_Warehouse_ID";
  private static final String COL_DESCRIPTION = "Description";
  private static final String COL_CHILD_ONLY = "Line_Discount";

  private static final String INP_LOCATION = "inpcBpartnerLocationId";
  private static final String INP_WAREHOUSE = "inpmWarehouseId";
  private static final String INP_DESCRIPTION = "inpdescription";
  private static final String INP_CHILD_ONLY = "inplineDiscount";

  /** Prefix of whatever {@code Utility.getDefault} answers in these tests. */
  private static final String DEFAULT_PREFIX = "CHILD-DEFAULT-";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<ModelProvider> modelProviderMock;
  private MockedStatic<NeoDefaultsSqlHelper> neoSqlHelperMock;
  private MockedStatic<NeoSessionVarsCache> sessionVarsMock;
  private MockedStatic<Utility> utilityMock;
  private MockedConstruction<org.openbravo.service.db.DalConnectionProvider> connProviderMock;

  private OBDal dal;
  private ModelProvider modelProvider;
  private OBContext obContext;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    modelProvider = mock(ModelProvider.class);
    modelProviderMock = mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

    obContext = mock(OBContext.class);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

    neoSqlHelperMock = mockStatic(NeoDefaultsSqlHelper.class);
    neoSqlHelperMock.when(() -> NeoDefaultsSqlHelper.resolveFirstOrgForClient(anyString()))
        .thenReturn(null);

    // The session-vars snapshot is the only DB-backed part of buildCalloutVars. Stubbing it keeps
    // the default pass alive without a database — which matters: if buildCalloutVars threw, no
    // default would ever be filled and these precedence assertions would pass vacuously.
    sessionVarsMock = mockStatic(NeoSessionVarsCache.class);
    sessionVarsMock.when(() -> NeoSessionVarsCache.getOrLoad(anyString(), anyString(), anyString(),
        anyString(), anyString(), anyString())).thenReturn(Collections.emptyMap());
    // Every child AD column resolves to a recognisable, NON-empty default so that a reverted call
    // order is visible in the assertion instead of merely producing an empty string.
    utilityMock = mockStatic(Utility.class);
    utilityMock.when(() -> Utility.getDefault(any(), any(VariablesSecureApp.class), anyString(),
            any(), any(), any()))
        .thenAnswer(inv -> DEFAULT_PREFIX + inv.getArgument(2));

    connProviderMock = mockConstruction(org.openbravo.service.db.DalConnectionProvider.class);

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLIENT-001");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-001");
    User user = mock(User.class);
    when(user.getId()).thenReturn("USER-001");
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("ROLE-001");
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn("en_US");
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(obContext.getUser()).thenReturn(user);
    when(obContext.getRole()).thenReturn(role);
    when(obContext.getLanguage()).thenReturn(language);
    withSessionWarehouse("SESSION-WH");

    CalloutRequestBuilder.clearParentTabCache();
  }

  @After
  public void tearDown() {
    // Close in REVERSE open order to satisfy Mockito's scope nesting requirement.
    connProviderMock.close();
    utilityMock.close();
    sessionVarsMock.close();
    neoSqlHelperMock.close();
    obContextMock.close();
    modelProviderMock.close();
    obDalMock.close();

    NeoCalloutService.clearMetadataCache();
  }

  // ─────────────────────────────────────────────────────────────────────
  // Fixture
  // ─────────────────────────────────────────────────────────────────────

  private void withSessionWarehouse(String warehouseId) {
    if (warehouseId == null) {
      when(obContext.getWarehouse()).thenReturn(null);
      return;
    }
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn(warehouseId);
    when(obContext.getWarehouse()).thenReturn(warehouse);
  }

  private static Column column(String dbColumnName) {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn(dbColumnName);
    when(col.isActive()).thenReturn(true);
    return col;
  }

  /** DAL property name this test uses for a given DB column. */
  private static String propertyNameOf(String dbColumnName) {
    return "prop$" + dbColumnName;
  }

  private static Entity entityWithProperties(String... dbColumnNames) {
    Entity entity = mock(Entity.class);
    for (String dbColumnName : dbColumnNames) {
      Property property = mock(Property.class);
      when(property.getName()).thenReturn(propertyNameOf(dbColumnName));
      when(entity.getPropertyByColumnName(dbColumnName)).thenReturn(property);
    }
    return entity;
  }

  /**
   * Wires a level-1 child tab whose table declares {@code childColumns}, under a level-0 parent
   * tab whose table declares {@code parentColumns} and whose record answers
   * {@code parentValues[i]} for {@code parentColumns[i]}.
   *
   * @return the child tab, ready to be passed to {@code buildRequestParams}
   */
  @SuppressWarnings("unchecked")
  private Tab wireTabs(List<String> childColumns, List<String> parentColumns,
      List<?> parentValues) {
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("WIN-001");
    when(window.isSalesTransaction()).thenReturn(Boolean.TRUE);

    Tab childTab = mock(Tab.class);
    when(childTab.getId()).thenReturn("child");
    when(childTab.getSequenceNumber()).thenReturn(20L);
    when(childTab.getTabLevel()).thenReturn(1L);
    when(childTab.getWindow()).thenReturn(window);

    Tab parentTab = mock(Tab.class);
    when(parentTab.getId()).thenReturn("parent");
    when(parentTab.getSequenceNumber()).thenReturn(10L);
    when(parentTab.getTabLevel()).thenReturn(0L);
    when(parentTab.getWindow()).thenReturn(window);

    // findParentTab sorts this list in place, so it must be mutable.
    when(window.getADTabList()).thenReturn(new ArrayList<>(Arrays.asList(parentTab, childTab)));
    when(dal.get(Tab.class, "parent")).thenReturn(parentTab);

    Table childTable = mock(Table.class);
    when(childTable.getId()).thenReturn(CHILD_TABLE_ID);
    when(childTable.getDBTableName()).thenReturn("C_OrderLine");
    when(childTab.getTable()).thenReturn(childTable);

    Table parentTable = mock(Table.class);
    when(parentTable.getId()).thenReturn(PARENT_TABLE_ID);
    when(parentTable.getDBTableName()).thenReturn("C_Order");
    when(parentTab.getTable()).thenReturn(parentTable);

    // Child columns reach buildColumnLookupMaps through the Column criteria.
    List<Column> childColumnMocks = new ArrayList<>();
    for (String dbColumnName : childColumns) {
      childColumnMocks.add(column(dbColumnName));
    }
    OBCriteria<Column> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Column.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(childColumnMocks);
    Entity childEntity = entityWithProperties(childColumns.toArray(new String[0]));
    when(modelProvider.getEntityByTableId(CHILD_TABLE_ID)).thenReturn(childEntity);

    // Parent columns reach injectParentRecordFields through the parent table's column list.
    List<Column> parentColumnMocks = new ArrayList<>();
    for (String dbColumnName : parentColumns) {
      parentColumnMocks.add(column(dbColumnName));
    }
    when(parentTable.getADColumnList()).thenReturn(parentColumnMocks);

    Entity parentEntity = entityWithProperties(parentColumns.toArray(new String[0]));
    when(parentEntity.getName()).thenReturn("Order");
    when(modelProvider.getEntityByTableId(PARENT_TABLE_ID)).thenReturn(parentEntity);

    BaseOBObject parentRecord = mock(BaseOBObject.class);
    when(parentRecord.getEntity()).thenReturn(parentEntity);
    for (int i = 0; i < parentColumns.size(); i++) {
      when(parentRecord.get(propertyNameOf(parentColumns.get(i)))).thenReturn(parentValues.get(i));
    }
    when(dal.get("Order", PARENT_ID)).thenReturn(parentRecord);

    return childTab;
  }

  private static JSONObject formState(String... keyValuePairs) throws Exception {
    JSONObject formState = new JSONObject();
    formState.put("id", PARENT_ID);
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      formState.put(keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return formState;
  }

  private static Map<String, String[]> build(Tab childTab, JSONObject formState) {
    return CalloutRequestBuilder.buildRequestParams(childTab, "PROD-1", formState,
        "inpmProductId", null);
  }

  private static String param(Map<String, String[]> params, String key) {
    String[] holder = params.get(key);
    return holder != null && holder.length > 0 ? holder[0] : null;
  }

  // ─────────────────────────────────────────────────────────────────────
  // client body > parent record
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void clientBodyWinsOverParentRecordAndDefault() throws Exception {
    Tab childTab = wireTabs(
        Arrays.asList(COL_DESCRIPTION),
        Arrays.asList(COL_DESCRIPTION),
        Arrays.<Object>asList("PARENT-DESC"));

    Map<String, String[]> params = build(childTab,
        formState(propertyNameOf(COL_DESCRIPTION), "CLIENT-DESC"));

    assertEquals("CLIENT-DESC", param(params, INP_DESCRIPTION));
  }

  // ─────────────────────────────────────────────────────────────────────
  // parent record > child AD column default  ← the reverted-order guard
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void parentRecordWinsOverChildColumnDefault() throws Exception {
    // C_BPartner_Location_ID exists on BOTH the line and the header, and the client body carries
    // neither: exactly the shape that produced the wrong tax rate.
    Tab childTab = wireTabs(
        Arrays.asList(COL_LOCATION),
        Arrays.asList(COL_LOCATION),
        Arrays.<Object>asList("PARENT-LOC"));

    Map<String, String[]> params = build(childTab, formState());

    assertEquals("the parent record's ship-to location must survive the child AD default pass",
        "PARENT-LOC", param(params, INP_LOCATION));
  }

  @Test
  public void parentWarehouseWinsOverChildColumnDefault() throws Exception {
    Tab childTab = wireTabs(
        Arrays.asList(COL_WAREHOUSE),
        Arrays.asList(COL_WAREHOUSE),
        Arrays.<Object>asList("PARENT-WH"));

    Map<String, String[]> params = build(childTab, formState());

    assertEquals("PARENT-WH", param(params, INP_WAREHOUSE));
  }

  @Test
  public void parentFkValueIsInjectedAsItsRecordId() throws Exception {
    BaseOBObject warehouseRef = mock(BaseOBObject.class);
    when(warehouseRef.getId()).thenReturn("PARENT-WH-FK");

    Tab childTab = wireTabs(
        Arrays.asList(COL_WAREHOUSE),
        Arrays.asList(COL_WAREHOUSE),
        Arrays.<Object>asList(warehouseRef));

    Map<String, String[]> params = build(childTab, formState());

    assertEquals("PARENT-WH-FK", param(params, INP_WAREHOUSE));
  }

  // ─────────────────────────────────────────────────────────────────────
  // child AD column default > session / nothing
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void childColumnDefaultAppliesWhenTheParentDoesNotDeclareTheColumn() throws Exception {
    // Guards the other direction: the swap must not have turned the default pass into a no-op.
    Tab childTab = wireTabs(
        Arrays.asList(COL_LOCATION, COL_CHILD_ONLY),
        Arrays.asList(COL_LOCATION),
        Arrays.<Object>asList("PARENT-LOC"));

    Map<String, String[]> params = build(childTab, formState());

    assertEquals("PARENT-LOC", param(params, INP_LOCATION));
    assertEquals(DEFAULT_PREFIX + COL_CHILD_ONLY, param(params, INP_CHILD_ONLY));
  }

  @Test
  public void childColumnDefaultWinsOverTheSessionWarehouse() throws Exception {
    Tab childTab = wireTabs(
        Arrays.asList(COL_WAREHOUSE),
        Arrays.asList(COL_DESCRIPTION),
        Arrays.<Object>asList("PARENT-DESC"));

    Map<String, String[]> params = build(childTab, formState());

    assertEquals(DEFAULT_PREFIX + COL_WAREHOUSE, param(params, INP_WAREHOUSE));
  }

  // ─────────────────────────────────────────────────────────────────────
  // session — last resort
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void sessionWarehouseIsUsedWhenNeitherParentNorChildColumnProvidesOne() throws Exception {
    Tab childTab = wireTabs(
        Arrays.asList(COL_DESCRIPTION),
        Arrays.asList(COL_DESCRIPTION),
        Arrays.<Object>asList("PARENT-DESC"));

    Map<String, String[]> params = build(childTab, formState());

    assertEquals("SESSION-WH", param(params, INP_WAREHOUSE));
  }

  @Test
  public void noWarehouseParamAtAllWhenNothingInTheChainProvidesOne() throws Exception {
    withSessionWarehouse(null);
    Tab childTab = wireTabs(
        Arrays.asList(COL_DESCRIPTION),
        Arrays.asList(COL_DESCRIPTION),
        Arrays.<Object>asList("PARENT-DESC"));

    Map<String, String[]> params = build(childTab, formState());

    assertNull(param(params, INP_WAREHOUSE));
  }

  // ─────────────────────────────────────────────────────────────────────
  // full chain in one request
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void allFourLinksOfTheChainResolveInOneRequest() throws Exception {
    Tab childTab = wireTabs(
        Arrays.asList(COL_DESCRIPTION, COL_LOCATION, COL_CHILD_ONLY),
        Arrays.asList(COL_DESCRIPTION, COL_LOCATION),
        Arrays.<Object>asList("PARENT-DESC", "PARENT-LOC"));

    Map<String, String[]> params = build(childTab,
        formState(propertyNameOf(COL_DESCRIPTION), "CLIENT-DESC"));

    // client body
    assertEquals("CLIENT-DESC", param(params, INP_DESCRIPTION));
    // parent record
    assertEquals("PARENT-LOC", param(params, INP_LOCATION));
    // child AD column default
    assertEquals(DEFAULT_PREFIX + COL_CHILD_ONLY, param(params, INP_CHILD_ONLY));
    // session
    assertEquals("SESSION-WH", param(params, INP_WAREHOUSE));
  }
}
