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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.ActiveEnabled;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link NeoReferenceActiveness} — the check that refuses a NEO write which SETS a
 * reference/FK column to a value whose target row is inactive (ETP-5802).
 *
 * <p>Reproduces the external-QA finding (retest "Pasada 2", ETP-5802): {@code etendo_create} on
 * {@code product} accepted {@code taxCategory} pointing at a deactivated
 * {@code FinancialMgmtTaxCategory} row with no error. Mirrors {@link NeoReferenceOwnershipTest}'s
 * mocking style: a written entity {@code Product} whose {@code taxCategory} property points at the
 * active-tracked {@code TaxCategory}, and whose {@code uom} property points at the
 * NOT-active-tracked {@code UOM} (structural exemption).
 *
 * @covers com.etendoerp.go.schemaforge.NeoReferenceActiveness
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NeoReferenceActivenessTest {

  private static final String PRODUCT = "Product";
  private static final String TAX_CATEGORY = "TaxCategory";
  private static final String UOM = "UOM";

  private static final String PROP_TAX_CATEGORY = "taxCategory";
  private static final String PROP_UOM = "uom";

  private static final String ACTIVE_TAX_ID = "TC-ACTIVE";
  private static final String INACTIVE_TAX_ID = "TC-OLD";
  private static final String UOM_ID = "UOM-1";

  private MockedStatic<ModelProvider> modelProviderStatic;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;

  private OBDal dal;
  private Tab productTab;

  @Before
  public void setUp() {
    modelProviderStatic = mockStatic(ModelProvider.class);
    obDalStatic = mockStatic(OBDal.class);
    obContextStatic = mockStatic(OBContext.class);

    Entity productEntity = mock(Entity.class);
    Entity taxCategoryEntity = activeEnabledEntity(TAX_CATEGORY);
    Entity uomEntity = notActiveEnabledEntity(UOM);

    Property idProperty = mock(Property.class);
    when(idProperty.isId()).thenReturn(true);
    when(productEntity.getProperty("id", false)).thenReturn(idProperty);

    Property taxCategory = reference(taxCategoryEntity);
    Property uom = reference(uomEntity);
    when(productEntity.getProperty(PROP_TAX_CATEGORY, false)).thenReturn(taxCategory);
    when(productEntity.getProperty(PROP_UOM, false)).thenReturn(uom);

    ModelProvider modelProvider = mock(ModelProvider.class);
    modelProviderStatic.when(ModelProvider::getInstance).thenReturn(modelProvider);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("T-PRODUCT");
    productTab = mock(Tab.class);
    when(productTab.getTable()).thenReturn(table);
    when(modelProvider.getEntityByTableId("T-PRODUCT")).thenReturn(productEntity);

    OBContext context = mock(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(context);

    // Built before stubbing: creating a mock inside thenReturn() is an unfinished stubbing.
    BaseOBObject activeRow = rowOf(true);
    BaseOBObject inactiveRow = rowOf(false);
    BaseOBObject uomRow = mock(BaseOBObject.class);

    dal = mock(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    when(dal.get(TAX_CATEGORY, ACTIVE_TAX_ID)).thenReturn(activeRow);
    when(dal.get(TAX_CATEGORY, INACTIVE_TAX_ID)).thenReturn(inactiveRow);
    when(dal.get(UOM, UOM_ID)).thenReturn(uomRow);
  }

  @After
  public void tearDown() {
    modelProviderStatic.close();
    obDalStatic.close();
    obContextStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  /** The reproduced bug: setting a reference to an inactive row must be refused. */
  @Test
  public void testRefusesAReferenceSetToAnInactiveRow() throws Exception {
    JSONObject fields = new JSONObject().put(PROP_TAX_CATEGORY, INACTIVE_TAX_ID);

    NeoReferenceActiveness.InactiveReference found =
        NeoReferenceActiveness.findInactiveReference(productTab, fields);

    assertEquals(PROP_TAX_CATEGORY, found.fieldName());
    assertEquals(TAX_CATEGORY, found.targetEntityName());
  }

  /** The acceptance case: an active row is never refused. */
  @Test
  public void testAcceptsAReferenceSetToAnActiveRow() throws Exception {
    JSONObject fields = new JSONObject().put(PROP_TAX_CATEGORY, ACTIVE_TAX_ID);

    assertNull(NeoReferenceActiveness.findInactiveReference(productTab, fields));
  }

  /**
   * ETP-5802 CP-6 regression guard: a write that does not SET the reference field at all — e.g. an
   * update touching only an unrelated column of a record that already carries an inactive
   * reference elsewhere — must not be refused. The caller decides what is "set" by what it puts in
   * {@code requestFields}; this test pins that the check only ever inspects the keys it is handed.
   */
  @Test
  public void testDoesNotCheckAFieldTheRequestDidNotSet() throws Exception {
    JSONObject fields = new JSONObject().put("name", "Updated description only");

    assertNull(NeoReferenceActiveness.findInactiveReference(productTab, fields));
  }

  /** Structural exemption: a target table with no IsActive column is never checked. */
  @Test
  public void testSkipsATargetThatIsNotActiveTracked() throws Exception {
    JSONObject fields = new JSONObject().put(PROP_UOM, UOM_ID);

    assertNull(NeoReferenceActiveness.findInactiveReference(productTab, fields));
  }

  /** A reference that names no row is not this check's concern — NeoReferenceOwnership's is. */
  @Test
  public void testIgnoresAnUnresolvableReference() throws Exception {
    JSONObject fields = new JSONObject().put(PROP_TAX_CATEGORY, "TC-UNKNOWN");

    assertNull(NeoReferenceActiveness.findInactiveReference(productTab, fields));
  }

  @Test
  public void testIgnoresTheSentinelAndBlankValues() throws Exception {
    JSONObject fields = new JSONObject().put(PROP_TAX_CATEGORY, "0");

    assertNull(NeoReferenceActiveness.findInactiveReference(productTab, fields));
  }

  @Test
  public void testDoesNothingWithoutASession() throws Exception {
    obContextStatic.when(OBContext::getOBContext).thenReturn(null);
    JSONObject fields = new JSONObject().put(PROP_TAX_CATEGORY, INACTIVE_TAX_ID);

    assertNull(NeoReferenceActiveness.findInactiveReference(productTab, fields));
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  private static Entity activeEnabledEntity(String name) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(name);
    when(entity.isActiveEnabled()).thenReturn(true);
    return entity;
  }

  private static Entity notActiveEnabledEntity(String name) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(name);
    when(entity.isActiveEnabled()).thenReturn(false);
    return entity;
  }

  private static Property reference(Entity target) {
    Property property = mock(Property.class);
    when(property.isPrimitive()).thenReturn(false);
    when(property.getTargetEntity()).thenReturn(target);
    return property;
  }

  /** Any active-trackable row; a financial account is just a convenient generated class. */
  private static BaseOBObject rowOf(boolean active) {
    FIN_FinancialAccount row = mock(FIN_FinancialAccount.class);
    when(row.isActive()).thenReturn(active);
    return row;
  }
}
