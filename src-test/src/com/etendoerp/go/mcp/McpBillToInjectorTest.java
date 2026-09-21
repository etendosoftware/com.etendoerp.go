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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;

/**
 * ETP-5335 — unit tests for {@link McpBillToInjector}, the MCP-only compensation that derives
 * {@code C_Order.BillTo_ID} from the business partner because no other layer can.
 *
 * <p>Every guard in the injector is a decision to ABSTAIN rather than guess: a plausible-looking
 * wrong invoicing address is worse than an absent one. The whole body also sits inside a
 * {@code catch (Exception)}, so "the body was left untouched" is never on its own evidence that
 * the intended guard fired — each abstain test therefore also asserts how far into the DAL the
 * injector got (no {@code OBDal} contact at all, or no criteria query).</p>
 */
@DisplayName("ETP-5335 — McpBillToInjector derives the bill-to or abstains")
class McpBillToInjectorTest {

  private static final String BILL_TO = "invoiceAddress";
  private static final String PARTNER = "businessPartner";
  private static final String SHIP_TO = "partnerAddress";

  private static final String COLUMN_BILL_TO = "BillTo_ID";
  private static final String COLUMN_PARTNER = "C_BPartner_ID";
  private static final String COLUMN_SHIP_TO = "C_BPartner_Location_ID";

  private static final String PARTNER_ID = "BP-001";
  private static final String OTHER_PARTNER_ID = "BP-999";
  private static final String SHIP_TO_ID = "LOC-SHIP";
  private static final String PARTNER_BILL_TO_ID = "LOC-BILL";
  private static final String REF_PLACEHOLDER = "$ref:op-1";

  private MockedStatic<OBDal> obDalMock;
  private OBDal dal;
  private OBCriteria<Location> criteria;
  private Logger log;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Location.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.addOrderBy(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(criteria);
    when(criteria.setMaxResults(org.mockito.ArgumentMatchers.anyInt())).thenReturn(criteria);

    log = mock(Logger.class);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  // ─────────────────────────────────────────────────────────────────────
  // Fixture
  // ─────────────────────────────────────────────────────────────────────

  /** An entity exposing the three columns the injector reads. */
  private static Entity orderEntity() {
    return entityWith(property(BILL_TO), property(PARTNER), property(SHIP_TO));
  }

  /**
   * A tab whose AD column {@code BillTo_ID} is mandatory — {@code C_Order}.
   *
   * <p>The mandatory flag is stubbed on the AD {@link Column}, NOT on the DAL {@link Property},
   * and that is the point of these fixtures rather than an implementation detail. {@code
   * ModelProvider} overwrites the property's flag with the physical NOT NULL of the database, and
   * {@code BillTo_ID} is mandatory in AD while nullable in Postgres — so the property answers
   * {@code false} on the very column this class exists for. A gate built on {@code
   * Property#isMandatory} never opens, which is exactly the defect a live probe caught after this
   * suite passed green: the earlier fixtures stubbed the property flag to {@code true} and so
   * asserted the assumption instead of testing it.
   */
  private static Tab orderTab() {
    return tabWith(adColumn(COLUMN_BILL_TO, true));
  }

  /** A tab that carries {@code BillTo_ID} but does not require it — {@code C_Project}. */
  private static Tab tabWithOptionalBillTo() {
    return tabWith(adColumn(COLUMN_BILL_TO, false));
  }

  /** A tab with no bill-to column at all. */
  private static Tab tabWithoutBillTo() {
    return tabWith();
  }

  private static Tab tabWith(Column... columns) {
    Table table = mock(Table.class);
    when(table.getADColumnList()).thenReturn(new ArrayList<>(List.of(columns)));
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    return tab;
  }

  private static Column adColumn(String dbColumnName, boolean mandatory) {
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    when(column.isActive()).thenReturn(true);
    when(column.isMandatory()).thenReturn(mandatory);
    return column;
  }

  private static Entity entityWith(Property billTo, Property partner, Property shipTo) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn("Order");
    when(entity.getPropertyByColumnName(COLUMN_BILL_TO, false)).thenReturn(billTo);
    when(entity.getPropertyByColumnName(COLUMN_PARTNER, false)).thenReturn(partner);
    when(entity.getPropertyByColumnName(COLUMN_SHIP_TO, false)).thenReturn(shipTo);
    return entity;
  }

  private static Property property(String name) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(name);
    return property;
  }

  /** The body a create carries once the agent has chosen a partner but no bill-to. */
  private static JSONObject body(Object... keyValuePairs) {
    try {
      JSONObject body = new JSONObject();
      for (int i = 0; i < keyValuePairs.length; i += 2) {
        body.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
      }
      return body;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A location {@code OBDal.get} will return for {@code id}. */
  private Location location(String id, boolean active, boolean invoiceTo, String partnerId) {
    Location location = mock(Location.class);
    when(location.getId()).thenReturn(id);
    when(location.isActive()).thenReturn(active);
    when(location.isInvoiceToAddress()).thenReturn(invoiceTo);
    if (partnerId != null) {
      BusinessPartner partner = mock(BusinessPartner.class);
      when(partner.getId()).thenReturn(partnerId);
      when(location.getBusinessPartner()).thenReturn(partner);
    }
    when(dal.get(Location.class, id)).thenReturn(location);
    return location;
  }

  /** What {@code findBillToLocation} finds for the partner — {@code null} means "nothing". */
  private void partnerBillToLocation(String locationId) {
    if (locationId == null) {
      when(criteria.uniqueResult()).thenReturn(null);
      return;
    }
    Location found = mock(Location.class);
    when(found.getId()).thenReturn(locationId);
    when(criteria.uniqueResult()).thenReturn(found);
  }

  private void assertUntouched(JSONObject body) {
    assertFalse(body.has(BILL_TO), "The injector must leave the body untouched when it abstains");
  }

  private void assertNoCriteriaQuery() {
    verify(dal, never()).createCriteria(Location.class);
  }

  // ─────────────────────────────────────────────────────────────────────
  // Applicability — the entity decides whether this document has a bill-to
  // ─────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("applicability")
  class Applicability {

    @Test
    @DisplayName("abstains when the entity has no BillTo_ID column at all")
    void noBillToColumn() {
      JSONObject body = body(PARTNER, PARTNER_ID);

      McpBillToInjector.injectIfMissing(body, tabWithoutBillTo(),
          entityWith(null, property(PARTNER), property(SHIP_TO)), log);

      assertUntouched(body);
      verify(dal, never()).get(eq(Location.class), anyString());
      assertNoCriteriaQuery();
    }

    @Test
    @DisplayName("abstains when BillTo_ID exists but is optional — C_Project, not C_Order")
    void billToColumnNotMandatory() {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, true, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, tabWithOptionalBillTo(),
          entityWith(property(BILL_TO), property(PARTNER), property(SHIP_TO)), log);

      assertUntouched(body);
      verify(dal, never()).get(eq(Location.class), anyString());
      assertNoCriteriaQuery();
    }

    @Test
    @DisplayName("abstains on a null entity without throwing")
    void nullEntity() {
      JSONObject body = body(PARTNER, PARTNER_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), null, log);

      assertUntouched(body);
      assertNoCriteriaQuery();
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // The body already answered the question
  // ─────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("an explicit bill-to is never overwritten")
  class ExplicitValue {

    @Test
    @DisplayName("keeps the value the caller supplied")
    void keepsSuppliedValue() throws Exception {
      JSONObject body = body(BILL_TO, "LOC-CHOSEN", PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, true, PARTNER_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals("LOC-CHOSEN", body.getString(BILL_TO));
      assertNoCriteriaQuery();
    }

    @ParameterizedTest(name = "a {0} bill-to is not a value — derivation still runs")
    @ValueSource(strings = { "", "   " })
    @DisplayName("blank placeholders do not count as a supplied value")
    void blankIsNotAValue(String blank) throws Exception {
      JSONObject body = body(BILL_TO, blank, PARTNER, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(PARTNER_BILL_TO_ID, body.getString(BILL_TO));
    }

    @Test
    @DisplayName("an explicit JSON null does not count as a supplied value")
    void jsonNullIsNotAValue() throws Exception {
      JSONObject body = body(BILL_TO, JSONObject.NULL, PARTNER, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(PARTNER_BILL_TO_ID, body.getString(BILL_TO));
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // Without a resolved partner there is nothing to derive from
  // ─────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("the business partner gates the whole derivation")
  class PartnerGate {

    @Test
    @DisplayName("abstains when the body carries no business partner")
    void noPartner() {
      JSONObject body = body(SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, true, PARTNER_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertUntouched(body);
      assertNoCriteriaQuery();
      verify(dal, never()).get(eq(Location.class), anyString());
    }

    @Test
    @DisplayName("abstains when the entity does not even map C_BPartner_ID")
    void noPartnerProperty() {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(),
          entityWith(property(BILL_TO), null, property(SHIP_TO)), log);

      assertUntouched(body);
      assertNoCriteriaQuery();
    }

    @Test
    @DisplayName("abstains when the partner is still a $ref placeholder inside a batch")
    void partnerStillUnresolvedReference() {
      JSONObject body = body(PARTNER, REF_PLACEHOLDER, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, true, PARTNER_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertUntouched(body);
      assertNoCriteriaQuery();
      verify(dal, never()).get(eq(Location.class), anyString());
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // Resolution order
  // ─────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("resolution order, most specific first")
  class Resolution {

    @Test
    @DisplayName("1) reuses the ship-to when it is the partner's own active bill-to address")
    void shipToIsAlsoABillToAddress() throws Exception {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, true, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(SHIP_TO_ID, body.getString(BILL_TO),
          "A ship-to that is itself a bill-to address wins over any other location");
      assertNoCriteriaQuery();
    }

    @Test
    @DisplayName("2) falls back to the partner's own bill-to when the ship-to is not one")
    void shipToNotFlaggedAsBillTo() throws Exception {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, false, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(PARTNER_BILL_TO_ID, body.getString(BILL_TO));
    }

    @Test
    @DisplayName("2) an inactive ship-to is not reused even when flagged as a bill-to")
    void inactiveShipTo() throws Exception {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, false, true, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(PARTNER_BILL_TO_ID, body.getString(BILL_TO));
    }

    @Test
    @DisplayName("2) a ship-to belonging to another partner is not reused")
    void shipToOfAnotherPartner() throws Exception {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, true, OTHER_PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(PARTNER_BILL_TO_ID, body.getString(BILL_TO));
    }

    @Test
    @DisplayName("2) a ship-to id that no longer resolves is not reused")
    void shipToNotFound() throws Exception {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      when(dal.get(Location.class, SHIP_TO_ID)).thenReturn(null);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(PARTNER_BILL_TO_ID, body.getString(BILL_TO));
    }

    @Test
    @DisplayName("2) derives from the partner alone when the document has no ship-to yet")
    void noShipToAtAll() throws Exception {
      JSONObject body = body(PARTNER, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(PARTNER_BILL_TO_ID, body.getString(BILL_TO));
      verify(dal, never()).get(eq(Location.class), anyString());
    }

    @Test
    @DisplayName("3) falls back to the ship-to when the partner has no bill-to location")
    void shipToAsLastResort() throws Exception {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      location(SHIP_TO_ID, true, false, PARTNER_ID);
      partnerBillToLocation(null);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertEquals(SHIP_TO_ID, body.getString(BILL_TO),
          "Core itself treats an empty bill-to as the ship-to (SL_Order_Product)");
    }

    @Test
    @DisplayName("4) abstains when neither the partner nor the document offers a location")
    void nothingResolves() {
      JSONObject body = body(PARTNER, PARTNER_ID);
      partnerBillToLocation(null);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertUntouched(body);
    }

    @Test
    @DisplayName("4) a $ref ship-to is no fallback either — it is not an id yet")
    void refShipToIsNotAFallback() {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, REF_PLACEHOLDER);
      partnerBillToLocation(null);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertUntouched(body);
      verify(dal, never()).get(eq(Location.class), anyString());
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // The query the fallback issues, and the failure mode of the whole pass
  // ─────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("the partner-location query and failure handling")
  class QueryAndFailure {

    @Test
    @DisplayName("asks for the partner's active bill-to locations, oldest first, one row")
    void queryShape() {
      JSONObject body = body(PARTNER, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      ArgumentCaptor<Criterion> criterions = ArgumentCaptor.forClass(Criterion.class);
      verify(criteria, org.mockito.Mockito.atLeastOnce()).add(criterions.capture());
      List<String> rendered = new ArrayList<>();
      for (Criterion criterion : criterions.getAllValues()) {
        rendered.add(criterion.toString());
      }
      assertTrue(rendered.contains("businessPartner.id=" + PARTNER_ID),
          "The query must be scoped to the business partner, got " + rendered);
      assertTrue(rendered.contains("invoiceToAddress=true"),
          "Only bill-to addresses qualify, got " + rendered);
      assertTrue(rendered.contains("active=true"),
          "An inactive location must never become the invoicing address, got " + rendered);
      verify(criteria).addOrderBy(Location.PROPERTY_CREATIONDATE, true);
      verify(criteria).setMaxResults(1);
    }

    @Test
    @DisplayName("runs in the caller's own DAL scope — never in admin mode")
    void noAdminMode() {
      JSONObject body = body(PARTNER, PARTNER_ID);
      partnerBillToLocation(PARTNER_BILL_TO_ID);

      try (MockedStatic<org.openbravo.dal.core.OBContext> context =
          mockStatic(org.openbravo.dal.core.OBContext.class)) {
        McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);
        context.verify(() -> org.openbravo.dal.core.OBContext.setAdminMode(), never());
        context.verify(() -> org.openbravo.dal.core.OBContext.setAdminMode(
            org.mockito.ArgumentMatchers.anyBoolean()), never());
      }
    }

    @Test
    @DisplayName("swallows a DAL failure and leaves the body untouched")
    void dalFailureIsSwallowed() {
      JSONObject body = body(PARTNER, PARTNER_ID, SHIP_TO, SHIP_TO_ID);
      when(dal.get(Location.class, SHIP_TO_ID))
          .thenThrow(new IllegalStateException("no session"));

      McpBillToInjector.injectIfMissing(body, orderTab(), orderEntity(), log);

      assertUntouched(body);
    }
  }
}
