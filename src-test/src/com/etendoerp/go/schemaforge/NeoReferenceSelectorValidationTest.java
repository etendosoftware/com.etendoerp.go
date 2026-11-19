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
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

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
   * ETP-6365: when the entity's customization answers for a field whose picker is its own (a
   * document header's currency, listed by {@code currencyOptions}), its answer replaces the
   * generic selector's; when it defers ({@code Optional.empty()}), the generic selector decides.
   */
  @Test
  public void testCustomizationAnswerReplacesTheGenericSelector() throws Exception {
    Object[][] cases = {
        // name, customization answer, generic selector answer, expected refused
        { "customization offers what the generic selector refuses", Boolean.TRUE, Boolean.FALSE,
            false },
        { "customization refuses what the generic selector offers", Boolean.FALSE, Boolean.TRUE,
            true },
        { "customization defers, generic selector refuses", null, Boolean.FALSE, true },
        { "customization defers, generic selector offers", null, Boolean.TRUE, false },
    };
    com.etendoerp.go.schemaforge.data.SFEntity sfEntity =
        mock(com.etendoerp.go.schemaforge.data.SFEntity.class);
    for (Object[] c : cases) {
      String name = (String) c[0];
      java.util.Optional<Boolean> customizationAnswer =
          java.util.Optional.ofNullable((Boolean) c[1]);
      NeoHandler customization = mock(NeoHandler.class);
      when(customization.offersReference(eq(ADDRESS), eq(ADDRESS_ID), any()))
          .thenReturn(customizationAnswer);
      offers((Boolean) c[2]);

      NeoReferenceSelectorValidation.RefusedReference refused;
      try (MockedStatic<com.etendoerp.go.schemaforge.util.NeoHandlerLookup> lookup =
          mockStatic(com.etendoerp.go.schemaforge.util.NeoHandlerLookup.class)) {
        lookup.when(() -> com.etendoerp.go.schemaforge.util.NeoHandlerLookup
            .forEntityQuietly(sfEntity)).thenReturn(customization);
        refused = NeoReferenceSelectorValidation.findRefusedReference(sfEntity, tab, null, null,
            new JSONObject().put(ADDRESS, ADDRESS_ID));
      }

      assertEquals(name, Boolean.TRUE.equals(c[3]), refused != null);
      if (refused != null) {
        assertEquals(name, ADDRESS, refused.fieldName());
        assertTrue(name, refused.isVerified());
      }
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
