/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DataResolvingMode;
import org.openbravo.service.json.DataToJsonConverter;

/**
 * Unit tests for {@link NeoReferenceSelectorValidation} (ETP-6176): a reference a write sets is
 * accepted only when the app's selector offers it, judged against the merged record.
 *
 * <p>The written entity is an {@code Order} whose {@code partnerAddress} depends on its
 * {@code businessPartner} (the contact/order address must belong to that partner).
 *
 * @covers com.etendoerp.go.schemaforge.NeoReferenceSelectorValidation
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NeoReferenceSelectorValidationTest {

  private static final String ADDRESS = "partnerAddress";
  private static final String PARTNER = "businessPartner";
  private static final String ADDRESS_ID = "ADDR-1";
  private static final String PARTNER_ID = "BP-1";
  private static final String ADDRESS_COLUMN = "C_BPartner_Location_ID";
  private static final String ORDER_ID = "ORD-1";
  private static final String DESCRIPTION = "description";

  private MockedStatic<ModelProvider> modelProviderStatic;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<NeoSelectorService> selectorStatic;

  private Entity order;
  private Tab tab;
  private OBDal dal;

  @Before
  public void setUp() {
    modelProviderStatic = mockStatic(ModelProvider.class);
    obDalStatic = mockStatic(OBDal.class);
    obContextStatic = mockStatic(OBContext.class);
    selectorStatic = mockStatic(NeoSelectorService.class);

    order = mock(Entity.class);
    Entity location = mock(Entity.class);
    Entity partner = mock(Entity.class);
    Property address = reference(ADDRESS_COLUMN, location, ADDRESS);
    Property bp = reference("C_BPartner_ID", partner, PARTNER);
    when(order.getProperty(ADDRESS, false)).thenReturn(address);
    when(order.getProperty(PARTNER, false)).thenReturn(bp);
    when(order.getProperties()).thenReturn(java.util.Arrays.asList(bp, address));
    when(location.getName()).thenReturn("BusinessPartnerLocation");

    ModelProvider modelProvider = mock(ModelProvider.class);
    modelProviderStatic.when(ModelProvider::getInstance).thenReturn(modelProvider);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("T-ORDER");
    List<Column> columns = java.util.Arrays.asList(column(ADDRESS_COLUMN),
        column("C_BPartner_ID"));
    when(table.getADColumnList()).thenReturn(columns);
    tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    when(modelProvider.getEntityByTableId("T-ORDER")).thenReturn(order);

    obContextStatic.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
    dal = mock(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @After
  public void tearDown() {
    modelProviderStatic.close();
    obDalStatic.close();
    obContextStatic.close();
    selectorStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  /** An address the selector does not offer for the partner is refused. */
  @Test
  public void testRefusesAReferenceTheSelectorDoesNotOffer() throws Exception {
    offers(Boolean.TRUE);
    selectorStatic.when(() -> NeoSelectorService.offersValue(any(), any(Column.class), eq(ADDRESS),
        anyString(), any())).thenReturn(java.util.Optional.of(Boolean.FALSE));

    NeoReferenceSelectorValidation.RefusedReference refused = check(
        new JSONObject().put(PARTNER, PARTNER_ID).put(ADDRESS, ADDRESS_ID));

    assertEquals(ADDRESS, refused.fieldName());
    assertEquals("BusinessPartnerLocation", refused.targetEntityName());
    assertEquals(ADDRESS_ID, refused.value());
    assertTrue(refused.isVerified());
  }

  /** A reference the selector offers is accepted. */
  @Test
  public void testAcceptsAReferenceTheSelectorOffers() throws Exception {
    offers(Boolean.TRUE);

    assertNull(check(new JSONObject().put(ADDRESS, ADDRESS_ID)));
  }

  /** A selector that cannot answer refuses the write: fail closed. */
  @Test
  public void testFailsClosedWhenTheSelectorCannotAnswer() throws Exception {
    offers(null);

    NeoReferenceSelectorValidation.RefusedReference refused = check(
        new JSONObject().put(ADDRESS, ADDRESS_ID));

    assertEquals(ADDRESS, refused.fieldName());
    assertEquals(ADDRESS_ID, refused.value());
    assertFalse(refused.isVerified());
  }

  /** A selector that throws refuses the write too. */
  @Test
  public void testFailsClosedWhenTheSelectorThrows() throws Exception {
    selectorStatic.when(() -> NeoSelectorService.offersValue(any(), any(Column.class), anyString(),
        anyString(), any())).thenThrow(new IllegalStateException("boom"));

    NeoReferenceSelectorValidation.RefusedReference refused = check(
        new JSONObject().put(ADDRESS, ADDRESS_ID));

    assertEquals(ADDRESS, refused.fieldName());
    assertFalse(refused.isVerified());
  }

  /** Changing the controlling field re-validates the stored dependent reference. */
  @Test
  public void testChangingTheControllingFieldRevalidatesTheStoredDependent() throws Exception {
    BaseOBObject stored = storedOrder();
    BaseOBObject oldPartner = mock(BaseOBObject.class);
    when(oldPartner.getId()).thenReturn("BP-OLD");
    when(stored.get(PARTNER)).thenReturn(oldPartner);
    org.openbravo.model.ad.domain.Validation rule = validation(
        "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@");
    when(columnOf(ADDRESS_COLUMN).getValidation()).thenReturn(rule);
    offers(Boolean.TRUE);
    selectorStatic.when(() -> NeoSelectorService.offersValue(any(), any(Column.class), eq(ADDRESS),
        anyString(), any())).thenReturn(java.util.Optional.of(Boolean.FALSE));

    NeoReferenceSelectorValidation.RefusedReference refused = NeoReferenceSelectorValidation
        .findRefusedReference(null, tab, ORDER_ID, null, new JSONObject().put(PARTNER, PARTNER_ID));

    assertEquals(ADDRESS, refused.fieldName());
    assertEquals(ADDRESS_ID, refused.value());
  }

  /** An update that changes nothing the dependent reads leaves it alone. */
  @Test
  public void testUnrelatedChangeDoesNotRevalidateTheStoredDependent() throws Exception {
    BaseOBObject stored = storedOrder();
    when(stored.get(DESCRIPTION)).thenReturn("old");
    org.openbravo.model.ad.domain.Validation rule = validation(
        "C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@");
    when(columnOf(ADDRESS_COLUMN).getValidation()).thenReturn(rule);
    when(order.getProperty(DESCRIPTION, false)).thenReturn(mock(Property.class));
    offers(Boolean.FALSE);

    assertNull(NeoReferenceSelectorValidation.findRefusedReference(null, tab, ORDER_ID, null,
        new JSONObject().put(DESCRIPTION, "new")));
  }

  /** The selector is asked with the other values of the request as its context. */
  @Test
  public void testSelectorGetsTheMergedRecordAsContext() throws Exception {
    offers(Boolean.TRUE);

    check(new JSONObject().put(PARTNER, PARTNER_ID).put(ADDRESS, ADDRESS_ID));

    ArgumentCaptor<Map<String, String>> context = ArgumentCaptor.forClass(Map.class);
    selectorStatic.verify(() -> NeoSelectorService.offersValue(any(), any(Column.class),
        eq(ADDRESS), eq(ADDRESS_ID), context.capture()));
    assertEquals(PARTNER_ID, context.getValue().get("C_BPartner_ID"));
    assertEquals(PARTNER_ID, context.getValue().get(PARTNER));
  }

  /** A "not set" sentinel names no record, so no selector is asked. */
  @Test
  public void testIgnoresValuesThatAreNotReferences() throws Exception {
    assertNull(check(new JSONObject().put(ADDRESS, "0")));

    selectorStatic.verify(() -> NeoSelectorService.offersValue(any(), any(), anyString(),
        anyString(), any()), never());
  }

  /** An update that sets the value the record already has is not re-validated. */
  @Test
  public void testUnchangedReferenceIsNotRevalidated() throws Exception {
    storedOrder();
    offers(Boolean.FALSE);

    assertNull(NeoReferenceSelectorValidation.findRefusedReference(null, tab, ORDER_ID, null,
        new JSONObject().put(ADDRESS, ADDRESS_ID)));
  }

  /**
   * A line created with its header in the link-to-parent field and no {@code parentId} is judged
   * with the header's fields: a storage bin reads the receipt's warehouse (ETP-6381).
   */
  @Test
  public void testLineWithoutParentIdTakesTheHeaderFromItsLinkColumn() throws Exception {
    try (LineFixture line = new LineFixture()) {
      offers(Boolean.TRUE);

      assertNull(NeoReferenceSelectorValidation.findRefusedReference(null, line.tab, null, null,
          new JSONObject().put(LineFixture.LINK, LineFixture.HEADER_ID)
              .put(LineFixture.BIN, LineFixture.BIN_ID)));

      Map<String, String> context = contextFor(LineFixture.BIN);
      assertEquals(LineFixture.WAREHOUSE_ID, context.get("M_Warehouse_ID"));
      assertEquals(LineFixture.HEADER_ID, context.get("M_InOut_ID"));
    }
  }

  /** An explicit {@code parentId} still names the parent over the link value the body sets. */
  @Test
  public void testExplicitParentIdWinsOverTheLinkValue() throws Exception {
    try (LineFixture line = new LineFixture()) {
      offers(Boolean.TRUE);

      NeoReferenceSelectorValidation.findRefusedReference(null, line.tab, null,
          LineFixture.HEADER_ID, new JSONObject().put(LineFixture.LINK, "OTHER-HEADER")
              .put(LineFixture.BIN, LineFixture.BIN_ID));

      assertEquals(LineFixture.WAREHOUSE_ID, contextFor(LineFixture.BIN).get("M_Warehouse_ID"));
      Mockito.verify(dal, never()).get(LineFixture.HEADER_ENTITY, "OTHER-HEADER");
    }
  }

  /**
   * The tab's literal auxiliary inputs reach the selector: Sales Quotation's {@code isQuotation=Y}
   * is what lets the quotation document type through rule 133 (ETP-6381).
   */
  @Test
  public void testSelectorGetsTheTabLiteralAuxiliaryInputs() throws Exception {
    givenAuxiliaryInput("isQuotation", "Y");
    offers(Boolean.TRUE);

    check(new JSONObject().put(ADDRESS, ADDRESS_ID));

    Map<String, String> context = contextFor(ADDRESS);
    assertEquals("Y", context.get("isQuotation"));
    assertEquals("Y", context.get("ISQUOTATION"));
  }

  /** A value the record carries wins over an auxiliary input of the same name, in any casing. */
  @Test
  public void testRecordValueWinsOverAnAuxiliaryInput() throws Exception {
    givenAuxiliaryInput("ISSOTRX", "N");
    Property salesFlag = mock(Property.class);
    when(salesFlag.getName()).thenReturn("salesTransaction");
    when(salesFlag.getColumnName()).thenReturn("IsSOTrx");
    when(salesFlag.isBoolean()).thenReturn(true);
    when(salesFlag.isPrimitive()).thenReturn(true);
    when(order.getProperty("salesTransaction", false)).thenReturn(salesFlag);
    offers(Boolean.TRUE);

    check(new JSONObject().put("salesTransaction", true).put(ADDRESS, ADDRESS_ID));

    Map<String, String> context = contextFor(ADDRESS);
    assertEquals("Y", context.get("IsSOTrx"));
    assertNull(context.get("ISSOTRX"));
  }

  /**
   * An auxiliary input wins over the window's sales flag, as in the Classic form: Product's
   * Purchasing tab says {@code IsSOTrx=N} inside a window flagged as sales.
   */
  @Test
  public void testAuxiliaryInputWinsOverTheWindowDirection() throws Exception {
    org.openbravo.model.ad.ui.Window window = mock(org.openbravo.model.ad.ui.Window.class);
    when(window.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(tab.getWindow()).thenReturn(window);
    givenAuxiliaryInput("IsSOTrx", "N");
    offers(Boolean.TRUE);

    check(new JSONObject().put(ADDRESS, ADDRESS_ID));

    assertEquals("N", contextFor(ADDRESS).get("IsSOTrx"));
  }

  private Map<String, String> contextFor(String field) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> context = ArgumentCaptor.forClass(Map.class);
    selectorStatic.verify(() -> NeoSelectorService.offersValue(any(), any(Column.class),
        eq(field), anyString(), context.capture()));
    return context.getValue();
  }

  /** The written tab carries one active auxiliary input. */
  private void givenAuxiliaryInput(String name, String code) {
    org.openbravo.model.ad.ui.AuxiliaryInput aux = mock(
        org.openbravo.model.ad.ui.AuxiliaryInput.class);
    when(aux.getName()).thenReturn(name);
    when(aux.getValidationCode()).thenReturn(code);
    when(aux.isActive()).thenReturn(true);
    List<org.openbravo.model.ad.ui.AuxiliaryInput> auxInputs = java.util.Collections
        .singletonList(aux);
    when(tab.getADAuxiliaryInputList()).thenReturn(auxInputs);
  }

  /**
   * A goods-receipt line tab: {@code shipmentReceipt} (M_InOut_ID) links it to its header, whose
   * {@code warehouse} the {@code storageBin} (M_Locator_ID) selector reads. Header HDR-1 is in
   * warehouse WH-1.
   */
  private final class LineFixture implements AutoCloseable {
    static final String LINK = "shipmentReceipt";
    static final String BIN = "storageBin";
    static final String HEADER_ID = "HDR-1";
    static final String BIN_ID = "BIN-1";
    static final String WAREHOUSE_ID = "WH-1";
    static final String HEADER_ENTITY = "MaterialMgmtShipmentInOut";

    private final MockedStatic<KernelUtils> kernelStatic = mockStatic(KernelUtils.class);
    private final MockedStatic<OBProvider> providerStatic = mockStatic(OBProvider.class);
    final Tab tab = mock(Tab.class);

    LineFixture() throws Exception {
      Entity line = mock(Entity.class);
      Entity header = mock(Entity.class);
      Entity locator = mock(Entity.class);
      when(locator.getName()).thenReturn("Locator");
      Property link = reference("M_InOut_ID", header, LINK);
      Property bin = reference("M_Locator_ID", locator, BIN);
      when(line.getProperty(LINK, false)).thenReturn(link);
      when(line.getProperty(BIN, false)).thenReturn(bin);
      when(line.getPropertyByColumnName("M_InOut_ID")).thenReturn(link);
      when(line.getProperties()).thenReturn(java.util.Arrays.asList(link, bin));

      Property warehouse = mock(Property.class);
      when(warehouse.getName()).thenReturn("warehouse");
      when(warehouse.getColumnName()).thenReturn("M_Warehouse_ID");
      when(header.getProperty("warehouse", false)).thenReturn(warehouse);
      when(header.getName()).thenReturn(HEADER_ENTITY);

      Table lineTable = mock(Table.class);
      when(lineTable.getId()).thenReturn("T-LINE");
      Column linkColumn = column("M_InOut_ID");
      when(linkColumn.isLinkToParentColumn()).thenReturn(true);
      List<Column> lineColumns = java.util.Arrays.asList(linkColumn, column("M_Locator_ID"));
      when(lineTable.getADColumnList()).thenReturn(lineColumns);
      when(tab.getTable()).thenReturn(lineTable);
      when(tab.getTabLevel()).thenReturn(1L);

      Table headerTable = mock(Table.class);
      when(headerTable.getId()).thenReturn("T-HEADER");
      Tab headerTab = mock(Tab.class);
      when(headerTab.getTable()).thenReturn(headerTable);
      KernelUtils kernel = mock(KernelUtils.class);
      kernelStatic.when(KernelUtils::getInstance).thenReturn(kernel);
      when(kernel.getParentTab(tab)).thenReturn(headerTab);

      ModelProvider modelProvider = ModelProvider.getInstance();
      when(modelProvider.getEntityByTableId("T-LINE")).thenReturn(line);
      when(modelProvider.getEntityByTableId("T-HEADER")).thenReturn(header);

      BaseOBObject headerRecord = mock(BaseOBObject.class);
      when(headerRecord.getEntity()).thenReturn(header);
      when(dal.get(HEADER_ENTITY, HEADER_ID)).thenReturn(headerRecord);
      OBProvider provider = mock(OBProvider.class);
      providerStatic.when(OBProvider::getInstance).thenReturn(provider);
      DataToJsonConverter converter = mock(DataToJsonConverter.class);
      when(provider.get(DataToJsonConverter.class)).thenReturn(converter);
      when(converter.toJsonObject(headerRecord, DataResolvingMode.FULL))
          .thenReturn(new JSONObject().put("warehouse", WAREHOUSE_ID));
    }

    @Override
    public void close() {
      kernelStatic.close();
      providerStatic.close();
    }
  }

  /** The stored order ORD-1, holding {@code ADDRESS_ID} and {@code PARTNER_ID}. */
  private BaseOBObject storedOrder() {
    BaseOBObject stored = mock(BaseOBObject.class);
    BaseOBObject address = mock(BaseOBObject.class);
    when(address.getId()).thenReturn(ADDRESS_ID);
    BaseOBObject partner = mock(BaseOBObject.class);
    when(partner.getId()).thenReturn(PARTNER_ID);
    when(stored.get(ADDRESS)).thenReturn(address);
    when(stored.get(PARTNER)).thenReturn(partner);
    when(dal.get("Order", ORDER_ID)).thenReturn(stored);
    when(order.getName()).thenReturn("Order");
    return stored;
  }

  private Column columnOf(String dbName) {
    for (Column column : tab.getTable().getADColumnList()) {
      if (dbName.equals(column.getDBColumnName())) {
        return column;
      }
    }
    throw new IllegalArgumentException(dbName);
  }

  private static org.openbravo.model.ad.domain.Validation validation(String code) {
    org.openbravo.model.ad.domain.Validation validation = mock(
        org.openbravo.model.ad.domain.Validation.class);
    when(validation.getValidationCode()).thenReturn(code);
    return validation;
  }

  private NeoReferenceSelectorValidation.RefusedReference check(JSONObject fields) {
    return NeoReferenceSelectorValidation.findRefusedReference(null, tab, null, null, fields);
  }

  private void offers(Boolean answer) {
    selectorStatic.when(() -> NeoSelectorService.offersValue(any(), any(Column.class), anyString(),
        anyString(), any())).thenReturn(java.util.Optional.ofNullable(answer));
  }

  private static Property reference(String columnName, Entity target, String name) {
    Property property = mock(Property.class);
    when(property.getColumnName()).thenReturn(columnName);
    when(property.getName()).thenReturn(name);
    when(property.getTargetEntity()).thenReturn(target);
    when(property.isPrimitive()).thenReturn(false);
    return property;
  }

  private static Column column(String dbName) {
    Column column = mock(Column.class);
    when(column.isActive()).thenReturn(true);
    when(column.getDBColumnName()).thenReturn(dbName);
    when(column.isLinkToParentColumn()).thenReturn(false);
    return column;
  }

  @Test
  public void invalidReferenceResponseCarries422WithFieldAndValue() throws Exception {
    NeoResponse response = NeoInvalidReferenceResponse.build(
        new NeoReferenceSelectorValidation.RefusedReference(ADDRESS, "Location", ADDRESS_ID, true));

    assertEquals(422, response.getHttpStatus());
    assertEquals(NeoInvalidReferenceResponse.ERROR_CODE, response.getBody().getString("error"));
    assertEquals(ADDRESS, response.getBody().getString("field"));
    assertEquals(ADDRESS_ID, response.getBody().getString("value"));
  }

  @Test
  public void unverifiedRefusalSaysTheCheckFailed() {
    NeoReferenceSelectorValidation.RefusedReference refused =
        new NeoReferenceSelectorValidation.RefusedReference(ADDRESS, "unknown", null, false);

    assertTrue(refused.detail().contains("could not be verified"));
  }
}
