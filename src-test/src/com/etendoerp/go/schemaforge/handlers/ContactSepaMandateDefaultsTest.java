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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.SepaRemittancesFlag;

/**
 * Unit tests for {@link ContactSepaMandateDefaults}, the customer's SEPA mandate rules on a contact
 * write: flag off strips both keys; flag on defaults a blank mandate identifier to the tax ID and a
 * missing signature date to the creation date (today on create), a value the caller sends wins, a
 * stored value is left alone, and a non-customer or an instance without OBSEPAD is skipped.
 *
 * <p>The rules run through the package-private {@code apply(body, enabled, create, recordId,
 * today)} so the flag and the date are explicit; {@link ModelProvider} answers whether the OBSEPAD
 * properties exist and {@link OBDal#getConnection} the persisted contact row. That {@code apply}
 * overload is the reason this class lives in the {@code handlers} package rather than in
 * {@code BusinessPartnerHandlerTest}, which pins only that the handler calls the rules.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ContactSepaMandateDefaults
 */
class ContactSepaMandateDefaultsTest {

  private static final String MANDATE_ID = ContactSepaMandateDefaults.FIELD_MANDATE_ID;
  private static final String MANDATE_DATE = ContactSepaMandateDefaults.FIELD_MANDATE_DATE;
  private static final String COLUMN_ID = "EM_Obsepad_Mandate_Identifier";
  private static final String COLUMN_DATE = "EM_Obsepad_Mandate_Date";
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
  private static final String RECORD_ID = "BP-1";

  private MockedStatic<ModelProvider> modelStatic;
  private MockedStatic<OBDal> dalStatic;
  private Entity bpEntity;
  private ModelProvider model;
  private Connection connection;
  private ResultSet row;

  @BeforeEach
  void setUp() throws Exception {
    bpEntity = mock(Entity.class);
    when(bpEntity.hasProperty(MANDATE_ID)).thenReturn(true);
    when(bpEntity.hasProperty(MANDATE_DATE)).thenReturn(true);
    model = mock(ModelProvider.class);
    when(model.getEntity("BusinessPartner", false)).thenReturn(bpEntity);

    connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenReturn(connection);

    modelStatic = mockStatic(ModelProvider.class);
    modelStatic.when(ModelProvider::getInstance).thenReturn(model);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
    modelStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- flag off

  @Test
  void flagOffStripsBothSpellingsAtTopLevelAndInsideData() throws Exception {
    JSONObject data = new JSONObject().put(MANDATE_ID, "M-1").put(COLUMN_DATE, "2026-01-01")
        .put("name", "Acme");
    JSONObject body = new JSONObject().put(COLUMN_ID, "M-2").put(MANDATE_DATE, "2026-02-02")
        .put("em_obsepad_mandate_identifier", "M-3").put("data", data).put("taxID", "B1");

    ContactSepaMandateDefaults.apply(body, false, true, null, TODAY);

    assertFalse(body.has(COLUMN_ID));
    assertFalse(body.has(MANDATE_DATE));
    assertFalse(body.has("em_obsepad_mandate_identifier"), "case-insensitive spelling too");
    assertFalse(data.has(MANDATE_ID));
    assertFalse(data.has(COLUMN_DATE));
    assertEquals("Acme", data.getString("name"), "other keys are untouched");
    assertEquals("B1", body.getString("taxID"));
    verify(model, never()).getEntity("BusinessPartner", false);
  }

  // ---------------------------------------------------------------- create

  @Test
  void aCreateWithoutTheCustomerKeyIsACustomerAndGetsTheTaxIdAndToday() throws Exception {
    JSONObject body = new JSONObject().put("name", "Acme").put("taxID", "B12345678");

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertEquals("B12345678", body.getString(MANDATE_ID));
    assertEquals("2026-11-05", body.getString(MANDATE_DATE));
    verify(connection, never()).prepareStatement(anyString());
  }

  @Test
  void aCreateAsAnExplicitCustomerGetsTheSameDefaults() throws Exception {
    JSONObject body = new JSONObject().put("customer", "Y").put("taxID", "B12345678");

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertEquals("B12345678", body.getString(MANDATE_ID));
    assertEquals("2026-11-05", body.getString(MANDATE_DATE));
  }

  @Test
  void aCreateWithoutATaxIdGetsOnlyTheDate() throws Exception {
    JSONObject body = new JSONObject().put("customer", true);

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertFalse(body.has(MANDATE_ID));
    assertEquals("2026-11-05", body.getString(MANDATE_DATE));
  }

  @Test
  void aCreateOfANonCustomerIsSkipped() throws Exception {
    JSONObject body = new JSONObject().put("customer", false).put("vendor", true)
        .put("taxID", "B12345678");

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertFalse(body.has(MANDATE_ID));
    assertFalse(body.has(MANDATE_DATE));
  }

  /**
   * A non-customer create drops the mandate keys: the form sends the DEFAULTS-prefilled date
   * whatever type the user then picks, and a vendor-only contact gets no mandate.
   */
  @Test
  void aCreateOfANonCustomerDropsTheMandateKeys() throws Exception {
    JSONObject data = new JSONObject().put(MANDATE_DATE, TODAY.toString());
    JSONObject body = new JSONObject().put("customer", false).put("vendor", true)
        .put(MANDATE_ID, "M-1").put("data", data);

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertFalse(body.has(MANDATE_ID));
    assertFalse(data.has(MANDATE_DATE));
  }

  /** An update of a non-customer keeps what it carries: only a create drops the keys. */
  @Test
  void anUpdateOfANonCustomerKeepsTheMandateKeys() throws Exception {
    persisted("N", "B12345678", null, null, "2025-03-01");
    JSONObject body = new JSONObject().put(MANDATE_ID, "M-1");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertEquals("M-1", body.getString(MANDATE_ID));
  }

  /** DEFAULTS prefill today's signature date with the flag on, never the identifier. */
  @Test
  void theDefaultsPrefillTheSignatureDate() throws Exception {
    JSONObject defaults = new JSONObject().put("name", "");

    assertTrue(ContactSepaMandateDefaults.applyDefaults(defaults, true, TODAY));

    assertEquals(TODAY.toString(), defaults.getString(MANDATE_DATE));
    assertFalse(defaults.has(MANDATE_ID));
  }

  /** Flag off, an already defaulted date, or no OBSEPAD columns: the defaults are untouched. */
  @Test
  void theDefaultsAreLeftAloneOtherwise() throws Exception {
    JSONObject off = new JSONObject();
    assertFalse(ContactSepaMandateDefaults.applyDefaults(off, false, TODAY));
    assertFalse(off.has(MANDATE_DATE));

    JSONObject set = new JSONObject().put(MANDATE_DATE, "2020-01-01");
    assertFalse(ContactSepaMandateDefaults.applyDefaults(set, true, TODAY));
    assertEquals("2020-01-01", set.getString(MANDATE_DATE));

    assertFalse(ContactSepaMandateDefaults.applyDefaults(null, true, TODAY));
    when(bpEntity.hasProperty(MANDATE_DATE)).thenReturn(false);
    JSONObject noColumns = new JSONObject();
    assertFalse(ContactSepaMandateDefaults.applyDefaults(noColumns, true, TODAY));
    assertFalse(noColumns.has(MANDATE_DATE));
  }

  @Test
  void aValueTheCallerSendsWins() throws Exception {
    JSONObject body = new JSONObject().put("taxID", "B12345678").put(MANDATE_ID, "MANDATE-7")
        .put(COLUMN_DATE, "2025-12-01");

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertEquals("MANDATE-7", body.getString(MANDATE_ID));
    assertEquals("2025-12-01", body.getString(COLUMN_DATE));
    assertFalse(body.has(MANDATE_DATE), "a date sent under the column name is kept as sent");
  }

  @Test
  void anExplicitBlankGetsTheDefaultUnderTheDalKey() throws Exception {
    JSONObject body = new JSONObject().put("taxID", "B12345678").put(COLUMN_ID, "  ")
        .put(MANDATE_DATE, JSONObject.NULL);

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertEquals("B12345678", body.getString(MANDATE_ID));
    assertFalse(body.has(COLUMN_ID), "the blank spelling cannot win over the default");
    assertEquals("2026-11-05", body.getString(MANDATE_DATE));
  }

  @Test
  void aTaxIdLongerThan35CharactersIsTruncated() throws Exception {
    String longTaxId = "X".repeat(40);
    JSONObject body = new JSONObject().put("taxID", longTaxId);

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertEquals("X".repeat(ContactSepaMandateDefaults.MANDATE_ID_MAX_LENGTH),
        body.getString(MANDATE_ID));
    assertEquals(35, body.getString(MANDATE_ID).length());
  }

  @Test
  void defaultsAreWrittenIntoTheDataEnvelopeAndAnOuterBlankIsDropped() throws Exception {
    JSONObject data = new JSONObject().put("taxID", "B12345678");
    JSONObject body = new JSONObject().put(MANDATE_ID, "").put("data", data);

    ContactSepaMandateDefaults.apply(body, true, true, null, TODAY);

    assertEquals("B12345678", data.getString(MANDATE_ID));
    assertEquals("2026-11-05", data.getString(MANDATE_DATE));
    assertFalse(body.has(MANDATE_ID));
    assertFalse(body.has(MANDATE_DATE));
  }

  // ---------------------------------------------------------------- update

  /**
   * Any save of a customer fills each mandate field empty in the database and not sent: an
   * unrelated update of a contact created before the feature gets the stored tax ID and its
   * creation date.
   */
  @Test
  void anUnrelatedUpdateOfACustomerFillsAnEmptyMandate() throws Exception {
    persisted("Y", "B12345678", null, null, "2025-03-01");
    JSONObject body = new JSONObject().put("name", "Acme renamed");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertEquals("B12345678", body.getString(MANDATE_ID));
    assertEquals("2025-03-01", body.getString(MANDATE_DATE), "the creation date, not today");
  }

  /** The identifier takes the CURRENT tax ID: the one the update sends over the stored one. */
  @Test
  void anUpdateFillsTheIdentifierWithTheTaxIdItSends() throws Exception {
    persisted("Y", "OLD-TAX", " ", null, "2025-03-01");
    JSONObject body = new JSONObject().put("taxID", "NEW-TAX");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertEquals("NEW-TAX", body.getString(MANDATE_ID));
    assertEquals("2025-03-01", body.getString(MANDATE_DATE));
  }

  /** Without a tax ID only the date is filled; a stored date is kept. */
  @Test
  void anUpdateWithoutATaxIdFillsOnlyAnEmptyDate() throws Exception {
    persisted("Y", null, null, null, "2025-03-01");
    JSONObject withoutDate = new JSONObject().put("name", "Acme");
    ContactSepaMandateDefaults.apply(withoutDate, true, false, RECORD_ID, TODAY);
    assertFalse(withoutDate.has(MANDATE_ID));
    assertEquals("2025-03-01", withoutDate.getString(MANDATE_DATE));

    persisted("Y", "B12345678", null, "2025-04-10", "2025-03-01");
    JSONObject withDate = new JSONObject().put("name", "Acme");
    ContactSepaMandateDefaults.apply(withDate, true, false, RECORD_ID, TODAY);
    assertEquals("B12345678", withDate.getString(MANDATE_ID));
    assertFalse(withDate.has(MANDATE_DATE), "a stored date is never overwritten");
  }

  @Test
  void aStoredMandateIsLeftAlone() throws Exception {
    persisted("Y", "B12345678", "MANDATE-OLD", "2024-06-30", "2024-01-01");
    JSONObject body = new JSONObject().put("taxID", "B99999999");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertFalse(body.has(MANDATE_ID));
    assertFalse(body.has(MANDATE_DATE));
  }

  /**
   * An explicit clear (the key sent empty) is respected for that save, even with the field empty
   * in the database; the other field, not sent, is still filled. No marker remembers the clear, so
   * a later save that does not send the key fills it again (see the class javadoc).
   */
  @Test
  void anExplicitClearStaysClearedInThatSave() throws Exception {
    persisted("Y", "B12345678", null, null, "2024-01-01");
    JSONObject body = new JSONObject().put(MANDATE_ID, "");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertEquals("", body.getString(MANDATE_ID));
    assertEquals("2024-01-01", body.getString(MANDATE_DATE));

    persisted("Y", "B12345678", "MANDATE-OLD", "2024-06-30", "2024-01-01");
    JSONObject clearing = new JSONObject().put(MANDATE_ID, JSONObject.NULL).put(MANDATE_DATE, "");
    ContactSepaMandateDefaults.apply(clearing, true, false, RECORD_ID, TODAY);
    assertTrue(clearing.isNull(MANDATE_ID));
    assertEquals("", clearing.getString(MANDATE_DATE));
  }

  @Test
  void anUpdateOfAStoredNonCustomerIsSkipped() throws Exception {
    persisted("N", "B12345678", null, null, "2025-03-01");
    JSONObject body = new JSONObject().put("name", "Supplier");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertFalse(body.has(MANDATE_ID));
    assertFalse(body.has(MANDATE_DATE));
  }

  @Test
  void anUpdateThatMakesTheContactACustomerGetsTheDefaults() throws Exception {
    persisted("N", "B12345678", null, null, "2025-03-01");
    JSONObject body = new JSONObject().put("customer", true);

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertEquals("B12345678", body.getString(MANDATE_ID));
    assertEquals("2025-03-01", body.getString(MANDATE_DATE));
  }

  /** Becoming a customer keeps a stored identifier and fills only the empty date. */
  @Test
  void becomingACustomerKeepsAStoredMandateId() throws Exception {
    persisted("N", "B12345678", "MANDATE-OLD", null, "2025-03-01");
    JSONObject body = new JSONObject().put("customer", true);

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertFalse(body.has(MANDATE_ID));
    assertEquals("2025-03-01", body.getString(MANDATE_DATE));
  }

  @Test
  void anUpdateOfAMissingRecordIsLeftToTheGenericCrud() throws Exception {
    when(row.next()).thenReturn(false);
    JSONObject body = new JSONObject().put("taxID", "B12345678");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);
    ContactSepaMandateDefaults.apply(body, true, false, " ", TODAY);

    assertFalse(body.has(MANDATE_ID));
    assertFalse(body.has(MANDATE_DATE));
  }

  // ---------------------------------------------------------------- OBSEPAD not installed

  @Test
  void withoutTheObsepadPropertiesTheRulesAreANoOp() throws Exception {
    when(bpEntity.hasProperty(MANDATE_DATE)).thenReturn(false);
    JSONObject body = new JSONObject().put("taxID", "B12345678").put(MANDATE_ID, "");

    ContactSepaMandateDefaults.apply(body, true, false, RECORD_ID, TODAY);

    assertEquals("", body.getString(MANDATE_ID), "left exactly as sent");
    assertFalse(body.has(MANDATE_DATE));
    verify(connection, never()).prepareStatement(anyString());

    when(model.getEntity("BusinessPartner", false)).thenReturn(null);
    assertFalse(ContactSepaMandateDefaults.mandateColumnsInstalled());
  }

  // ---------------------------------------------------------------- public entry point

  @Test
  void theRequestEntryPointIgnoresAnythingButACrudWrite() throws Exception {
    JSONObject body = new JSONObject().put(MANDATE_ID, "M-1");
    NeoContext read = context("GET", NeoEndpointType.CRUD, body);
    NeoContext selector = context("POST", NeoEndpointType.SELECTOR, body);

    try (MockedStatic<SepaRemittancesFlag> flag = mockStatic(SepaRemittancesFlag.class)) {
      ContactSepaMandateDefaults.apply(read, body);
      ContactSepaMandateDefaults.apply(selector, body);
      ContactSepaMandateDefaults.apply(context("POST", NeoEndpointType.CRUD, null), null);

      flag.verifyNoInteractions();
    }
    assertEquals("M-1", body.getString(MANDATE_ID));
  }

  @Test
  void theRequestEntryPointEvaluatesTheFlagAndAppliesTheRules() throws Exception {
    JSONObject stripped = new JSONObject().put(MANDATE_ID, "M-1").put("taxID", "B1");
    JSONObject defaulted = new JSONObject().put("taxID", "B12345678");

    try (MockedStatic<SepaRemittancesFlag> flag = mockStatic(SepaRemittancesFlag.class)) {
      flag.when(SepaRemittancesFlag::isEnabled).thenReturn(false);
      ContactSepaMandateDefaults.apply(context("PATCH", NeoEndpointType.CRUD, stripped), stripped);

      flag.when(SepaRemittancesFlag::isEnabled).thenReturn(true);
      ContactSepaMandateDefaults.apply(context("POST", NeoEndpointType.CRUD, defaulted),
          defaulted);
    }

    assertFalse(stripped.has(MANDATE_ID));
    assertEquals("B12345678", defaulted.getString(MANDATE_ID));
    assertEquals(LocalDate.now().toString(), defaulted.getString(MANDATE_DATE));
  }

  @Test
  void writeSectionsListsTheEnvelopeAfterTheBody() throws Exception {
    JSONObject data = new JSONObject();
    JSONObject body = new JSONObject().put("data", data);

    assertEquals(2, ContactSepaMandateDefaults.writeSections(body).size());
    assertTrue(ContactSepaMandateDefaults.writeSections(body).get(1) == data);
    assertEquals(1, ContactSepaMandateDefaults.writeSections(new JSONObject()).size());
  }

  // ---------------------------------------------------------------- helpers

  /** The persisted contact the update reads: iscustomer, taxid, mandate id, date, created. */
  private void persisted(String customer, String taxId, String mandateId, String mandateDate,
      String created) throws Exception {
    when(row.next()).thenReturn(true);
    when(row.getString(1)).thenReturn(customer);
    when(row.getString(2)).thenReturn(taxId);
    when(row.getString(3)).thenReturn(mandateId);
    when(row.getString(4)).thenReturn(mandateDate);
    when(row.getString(5)).thenReturn(created);
  }

  private static NeoContext context(String method, NeoEndpointType endpoint, JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getEndpointType()).thenReturn(endpoint);
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn(RECORD_ID);
    return ctx;
  }
}
