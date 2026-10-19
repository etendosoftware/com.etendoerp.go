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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

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
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.service.json.JsonConstants;

/**
 * Unit tests for {@link NeoReferenceOwnership} — the check that refuses a NEO write whose request
 * references a record of another tenant (ETP-5785).
 *
 * <p>The DAL model is mocked: a written entity {@code Line} whose {@code parent} property points at
 * {@code Header}, whose {@code partner} property points at the client-enabled {@code Partner}, and
 * whose {@code currency} property points at the shared, not client-enabled {@code Currency}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoReferenceOwnership
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NeoReferenceOwnershipTest {

  private static final String OWN_CLIENT = "client-own";
  private static final String OTHER_CLIENT = "client-other";
  private static final String SYSTEM_CLIENT = "0";

  private static final String LINE = "Line";
  private static final String HEADER = "Header";
  private static final String PARTNER = "Partner";
  private static final String CURRENCY = "Currency";

  private static final String OWN_PARTNER_ID = "P-OWN";
  private static final String SYSTEM_PARTNER_ID = "P-SYS";
  private static final String FOREIGN_PARTNER_ID = "P-FOREIGN";
  private static final String MISSING_PARTNER_ID = "P-MISSING";
  private static final String OWN_HEADER_ID = "H-OWN";
  private static final String FOREIGN_HEADER_ID = "H-FOREIGN";

  private static final String PROP_PARTNER = "partner";
  private static final String PROP_PARENT = "parent";
  private static final String PROP_CURRENCY = "currency";
  private static final String PROP_QUANTITY = "quantity";

  private MockedStatic<ModelProvider> modelProviderStatic;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<KernelUtils> kernelUtilsStatic;
  private MockedStatic<OBProvider> obProviderStatic;

  private OBDal dal;
  private Tab lineTab;
  private Tab headerTab;
  private Entity partnerEntity;

  /**
   * Releases the inline mock-maker references created during the test. Without this they
   * accumulate across the module's single test JVM and push the fork past its heap cap.
   */
  @After
  public void tearDown() {
    modelProviderStatic.close();
    obDalStatic.close();
    obContextStatic.close();
    kernelUtilsStatic.close();
    obProviderStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  @Before
  public void setUp() {
    modelProviderStatic = mockStatic(ModelProvider.class);
    obDalStatic = mockStatic(OBDal.class);
    obContextStatic = mockStatic(OBContext.class);
    kernelUtilsStatic = mockStatic(KernelUtils.class);
    obProviderStatic = mockStatic(OBProvider.class);

    Entity lineEntity = entity(LINE, true);
    Entity headerEntity = entity(HEADER, true);
    partnerEntity = entity(PARTNER, true);
    Entity currencyEntity = entity(CURRENCY, false);

    Property idProperty = mock(Property.class);
    when(idProperty.isId()).thenReturn(true);
    when(idProperty.getName()).thenReturn(JsonConstants.ID);
    when(partnerEntity.getIdProperties()).thenReturn(Collections.singletonList(idProperty));
    when(headerEntity.getIdProperties()).thenReturn(Collections.singletonList(idProperty));
    when(lineEntity.getProperty(JsonConstants.ID, false)).thenReturn(idProperty);
    when(partnerEntity.getProperty(JsonConstants.ID, false)).thenReturn(idProperty);

    Property quantity = mock(Property.class);
    when(quantity.isPrimitive()).thenReturn(true);
    when(lineEntity.getProperty(PROP_QUANTITY, false)).thenReturn(quantity);
    // Built before stubbing: creating a mock inside thenReturn() is an unfinished stubbing.
    Property partner = reference(partnerEntity);
    Property parent = reference(headerEntity);
    Property currency = reference(currencyEntity);
    when(lineEntity.getProperty(PROP_PARTNER, false)).thenReturn(partner);
    when(lineEntity.getProperty(PROP_PARENT, false)).thenReturn(parent);
    when(lineEntity.getProperty(PROP_CURRENCY, false)).thenReturn(currency);

    ModelProvider modelProvider = mock(ModelProvider.class);
    modelProviderStatic.when(ModelProvider::getInstance).thenReturn(modelProvider);
    lineTab = tab("T-LINE", 1);
    headerTab = tab("T-HEADER", 0);
    when(modelProvider.getEntityByTableId("T-LINE")).thenReturn(lineEntity);
    when(modelProvider.getEntityByTableId("T-HEADER")).thenReturn(headerEntity);

    KernelUtils kernelUtils = mock(KernelUtils.class);
    kernelUtilsStatic.when(KernelUtils::getInstance).thenReturn(kernelUtils);
    when(kernelUtils.getParentTab(lineTab)).thenReturn(headerTab);

    OBProvider obProvider = mock(OBProvider.class);
    obProviderStatic.when(OBProvider::getInstance).thenReturn(obProvider);
    when(obProvider.get(anyString())).thenThrow(new IllegalStateException("no model in tests"));

    OBContext context = mock(OBContext.class);
    when(context.getReadableClients()).thenReturn(new String[] {OWN_CLIENT, SYSTEM_CLIENT});
    obContextStatic.when(OBContext::getOBContext).thenReturn(context);

    dal = mock(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    BaseOBObject ownRow = rowOf(OWN_CLIENT);
    BaseOBObject systemRow = rowOf(SYSTEM_CLIENT);
    BaseOBObject foreignRow = rowOf(OTHER_CLIENT);
    when(dal.get(PARTNER, OWN_PARTNER_ID)).thenReturn(ownRow);
    when(dal.get(PARTNER, SYSTEM_PARTNER_ID)).thenReturn(systemRow);
    when(dal.get(PARTNER, FOREIGN_PARTNER_ID)).thenReturn(foreignRow);
    when(dal.get(HEADER, OWN_HEADER_ID)).thenReturn(ownRow);
    when(dal.get(HEADER, FOREIGN_HEADER_ID)).thenReturn(foreignRow);
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  private static Entity entity(String name, boolean clientEnabled) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(name);
    when(entity.isClientEnabled()).thenReturn(clientEnabled);
    return entity;
  }

  private static Property reference(Entity target) {
    Property property = mock(Property.class);
    when(property.isPrimitive()).thenReturn(false);
    when(property.getTargetEntity()).thenReturn(target);
    return property;
  }

  private static Tab tab(String tableId, long level) {
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(tableId);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    when(tab.getTabLevel()).thenReturn(level);
    return tab;
  }

  /** Any client-enabled row; a financial account is just a convenient generated class. */
  private static BaseOBObject rowOf(String clientId) {
    FIN_FinancialAccount row = mock(FIN_FinancialAccount.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(row.getClient()).thenReturn(client);
    return row;
  }

  private static JSONObject body(String key, Object value) throws Exception {
    return new JSONObject().put(key, value);
  }

  private static JSONObject errorsOf(String refusal) throws Exception {
    JSONObject response = new JSONObject(refusal).getJSONObject(JsonConstants.RESPONSE_RESPONSE);
    assertEquals(JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR,
        response.getInt(JsonConstants.RESPONSE_STATUS));
    return response.getJSONObject(JsonConstants.RESPONSE_ERRORS);
  }

  // ── foreign keys the request sets ──────────────────────────────────────────

  /** A foreign key to a row of another client is refused with core's unknown-reference body. */
  @Test
  public void testRefusesAForeignKeyToAnotherClient() throws Exception {
    String refusal = NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, FOREIGN_PARTNER_ID), null);

    assertNotNull(refusal);
    String message = errorsOf(refusal).getString(JsonConstants.ID);
    assertTrue(message, message.contains("(key: " + FOREIGN_PARTNER_ID + "_" + PARTNER + ")"));
    assertTrue(message, message.endsWith("refered to but not present in the import set"));
  }

  /**
   * The refusal for another tenant's row is the refusal for a row that does not exist: only the id
   * the caller sent differs, so the answer cannot be used to probe ids.
   */
  @Test
  public void testAForeignRowAndAMissingRowGetTheSameAnswer() throws Exception {
    String foreign = NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, FOREIGN_PARTNER_ID), null);
    String missing = NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, MISSING_PARTNER_ID), null);

    assertNotNull(missing);
    assertEquals(missing, foreign.replace(FOREIGN_PARTNER_ID, MISSING_PARTNER_ID));
  }

  /** The caller's own rows and System rows are accepted. */
  @Test
  public void testAcceptsOwnAndSystemRows() throws Exception {
    assertNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, OWN_PARTNER_ID), null));
    assertNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, SYSTEM_PARTNER_ID), null));
  }

  /** Primitive values and references to shared (not client-enabled) data never reach the DAL. */
  @Test
  public void testSkipsPrimitivesAndSharedReferenceData() throws Exception {
    JSONObject fields = new JSONObject().put(PROP_QUANTITY, "5").put(PROP_CURRENCY, "EUR-ID")
        .put("notAProperty", FOREIGN_PARTNER_ID);

    assertNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab, fields, null));
    verify(dal, never()).get(anyString(), anyString());
  }

  /**
   * Blank, JSON-null, the "0" sentinel, an unsubstituted batch reference and placeholder literals
   * (an unresolved AD default, a sequence preview) are not looked up.
   */
  @Test
  public void testSkipsValuesThatAddressNoRow() throws Exception {
    for (String value : new String[] {"", "null", "0", BatchService.REF_PREFIX + "op1",
        "@OnHandLocatorDefault@", "<1000>"}) {
      assertNull(value, NeoReferenceOwnership.refuseUnownedReferences(lineTab,
          body(PROP_PARTNER, value), null));
    }
    verify(dal, never()).get(anyString(), anyString());
  }

  /** One lookup per distinct reference, however many keys carry it. */
  @Test
  public void testLooksUpEachDistinctReferenceOnce() throws Exception {
    JSONObject fields = new JSONObject().put(PROP_PARTNER, OWN_PARTNER_ID)
        .put(PROP_PARTNER + ".id", OWN_PARTNER_ID);

    assertNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab, fields, null));
    verify(dal, times(1)).get(PARTNER, OWN_PARTNER_ID);
  }

  /** A dotted path ({@code partner.id}) names the referenced row exactly as core reads it. */
  @Test
  public void testRefusesAForeignKeySentThroughAPath() throws Exception {
    assertNotNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER + ".id", FOREIGN_PARTNER_ID), null));
  }

  /** A reference sent as an object addresses the row of its {@code id}: core writes into it. */
  @Test
  public void testRefusesAForeignRowSentAsANestedObject() throws Exception {
    JSONObject nested = new JSONObject().put(JsonConstants.ID, FOREIGN_PARTNER_ID);

    assertNotNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, nested), null));
  }

  /** A plain {@code id} key is the written record itself, not a reference. */
  @Test
  public void testIgnoresTheWrittenRecordsOwnId() throws Exception {
    assertNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(JsonConstants.ID, FOREIGN_PARTNER_ID), null));
    verify(dal, never()).get(anyString(), anyString());
  }

  /**
   * A numeric id is still an id: core reads a nested object's id with getString, so
   * {"id": 1000000} resolves the row "1000000" and must be checked like its string form.
   */
  @Test
  public void testRefusesANumericIdOfAnotherClient() throws Exception {
    BaseOBObject foreignRow = rowOf(OTHER_CLIENT);
    when(dal.get(PARTNER, "1000000")).thenReturn(foreignRow);

    assertNotNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, new JSONObject().put(JsonConstants.ID, 1000000L)), null));
    verify(dal).get(PARTNER, "1000000");
  }

  /** A leading dot ({@code .partner.id}) does not hide the reference from the check. */
  @Test
  public void testRefusesAForeignKeySentThroughALeadingDotPath() throws Exception {
    assertNotNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body("." + PROP_PARTNER + ".id", FOREIGN_PARTNER_ID), null));
  }

  /**
   * {@code x.id} sets {@code x} in core, so a property that references its target by a non-id
   * property is looked up by that property, exactly as core resolves it.
   */
  @Test
  public void testLooksUpByTheReferencedPropertyThroughAnIdPath() throws Exception {
    Property byValue = mock(Property.class);
    when(byValue.isId()).thenReturn(false);
    when(byValue.getName()).thenReturn("searchKey");
    Property coded = reference(partnerEntity);
    when(coded.getReferencedProperty()).thenReturn(byValue);
    Entity lineEntity = ModelProvider.getInstance().getEntityByTableId("T-LINE");
    when(lineEntity.getProperty("codedPartner", false)).thenReturn(coded);
    @SuppressWarnings("unchecked")
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(dal.createQuery(PARTNER, "searchKey = :reference")).thenReturn(query);
    BaseOBObject foreignRow = rowOf(OTHER_CLIENT);
    when(query.list()).thenReturn(Collections.singletonList(foreignRow));

    assertNotNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body("codedPartner.id", "ACME"), null));
    verify(query).setNamedParameter("reference", "ACME");
    verify(dal, never()).get(PARTNER, "ACME");
  }

  /**
   * A lookup that throws (e.g. an id the key type cannot hold) fails closed, with the very
   * refusal a missing row gets — never an exception for the caller's generic handler.
   */
  @Test
  public void testALookupFailureIsRefusedLikeAMissingRow() throws Exception {
    when(dal.get(PARTNER, "P-BROKEN")).thenThrow(new IllegalArgumentException("type mismatch"));

    String broken = NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, "P-BROKEN"), null);
    String missing = NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, MISSING_PARTNER_ID), null);

    assertNotNull(broken);
    assertEquals(missing, broken.replace("P-BROKEN", MISSING_PARTNER_ID));
  }

  /** The public probe used by MCP FK-by-name resolution: own and System yes, foreign or broken no. */
  @Test
  public void testIsOwnedByCallerClient() {
    when(dal.get(PARTNER, "P-BROKEN")).thenThrow(new IllegalArgumentException("type mismatch"));

    assertTrue(NeoReferenceOwnership.isOwnedByCallerClient(PARTNER, OWN_PARTNER_ID));
    assertTrue(NeoReferenceOwnership.isOwnedByCallerClient(PARTNER, SYSTEM_PARTNER_ID));
    assertFalse(NeoReferenceOwnership.isOwnedByCallerClient(PARTNER, FOREIGN_PARTNER_ID));
    assertFalse(NeoReferenceOwnership.isOwnedByCallerClient(PARTNER, MISSING_PARTNER_ID));
    assertFalse(NeoReferenceOwnership.isOwnedByCallerClient(PARTNER, "P-BROKEN"));
  }

  // ── parentId ───────────────────────────────────────────────────────────────

  /** A parent of another client is refused, checked against the parent tab's entity. */
  @Test
  public void testRefusesAParentOfAnotherClient() throws Exception {
    String refusal = NeoReferenceOwnership.refuseUnownedReferences(lineTab, null,
        FOREIGN_HEADER_ID);

    assertNotNull(refusal);
    assertTrue(errorsOf(refusal).getString(JsonConstants.ID)
        .contains("(key: " + FOREIGN_HEADER_ID + "_" + HEADER + ")"));
  }

  /** The caller's own parent is accepted. */
  @Test
  public void testAcceptsAnOwnParent() throws Exception {
    assertNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, OWN_PARTNER_ID), OWN_HEADER_ID));
  }

  /** A header ignores parentId on every write path, so the guard does not look it up either. */
  @Test
  public void testIgnoresAParentIdOnAHeader() throws Exception {
    assertNull(NeoReferenceOwnership.refuseUnownedReferences(headerTab, null, FOREIGN_HEADER_ID));
    verify(dal, never()).get(anyString(), anyString());
  }

  // ── context ────────────────────────────────────────────────────────────────

  /** With no session on the thread there is no tenant to compare against: nothing is checked. */
  @Test
  public void testDoesNothingWithoutASession() throws Exception {
    obContextStatic.when(OBContext::getOBContext).thenReturn(null);

    assertNull(NeoReferenceOwnership.refuseUnownedReferences(lineTab,
        body(PROP_PARTNER, FOREIGN_PARTNER_ID), FOREIGN_HEADER_ID));
    verify(dal, never()).get(anyString(), anyString());
  }
}
