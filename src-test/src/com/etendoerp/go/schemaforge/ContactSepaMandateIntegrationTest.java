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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.businesspartner.BusinessPartner;

/**
 * The customer's SEPA mandate is STORED on the contact, through the real NEO REST dispatch of the
 * contacts {@code businessPartner} entity: the identifier takes the tax ID and the signature date
 * the creation date when the write leaves them empty, behind {@code sepa-remittances}. Only on
 * create, or on the update that makes the contact a customer or gives it its first tax ID; never a
 * backfill of an untouched contact, never a change of an identifier already set.
 *
 * <p>A DB test on purpose: the defaults are written into the request body before the generic
 * CRUD, so only a persisted row proves they survive the field filter and the insert.
 *
 * <p>Also across channels and shapes: MCP {@code etendo_update}, a {@code /sws/neo/batch} create
 * (batch operations are creates only), a {@code data} envelope, a type inherited from the
 * contact category, and the new-contact DEFAULTS.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ContactSepaMandateDefaults
 * @covers com.etendoerp.go.schemaforge.BusinessPartnerHandler
 */
public class ContactSepaMandateIntegrationTest extends WeldBaseTest {

  private static final String SPEC = "contacts";
  private static final String ENTITY = "businessPartner";
  private static final String NIF_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";
  private static final String IDENTIFIER_SEQUENCE = "BusinessPartner-EM_Etgo_Identifier";
  /** {@code C_BPartner}. */
  private static final String BPARTNER_TABLE_ID = "291";
  /** {@code C_BPartner.EM_Etgo_Identifier}. */
  private static final String IDENTIFIER_COLUMN_ID = "294937FFC81749289BD9BB28E400D4B2";

  private MockedStatic<SepaRemittancesFlag> flag;
  /** The body of the last create, as the SPA receives it (it primes the form from it). */
  private JSONObject lastCreated;

  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    OBContext.setAdminMode();
    BatchService.beginCallerOwnedTransaction();
    flag = Mockito.mockStatic(SepaRemittancesFlag.class, Mockito.CALLS_REAL_METHODS);
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(true);
    ensureContactIdentifierSequence();
  }

  /**
   * An Etendo GO tenant numbers its contacts with the sequence the onboarding creates
   * ({@code BusinessPartner-EM_Etgo_Identifier}); the sample client of the test context has none,
   * so one is added for it here, rolled back with the rest.
   */
  private static void ensureContactIdentifierSequence() {
    Client client = OBContext.getOBContext().getCurrentClient();
    Long existing = OBDal.getInstance().getSession().createQuery(
        "select count(s.id) from ADSequence as s where s.client.id = :client"
            + " and s.column.id = :column",
        Long.class)
        .setParameter("client", client.getId())
        .setParameter("column", IDENTIFIER_COLUMN_ID)
        .uniqueResult();
    if (existing != null && existing > 0) {
      return;
    }
    Sequence sequence = OBProvider.getInstance().get(Sequence.class);
    sequence.setClient(client);
    // Etendo's transactional sequences are found by client, organization and column.
    sequence.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    sequence.setTable(OBDal.getInstance().getProxy(Table.class, BPARTNER_TABLE_ID));
    sequence.setColumn(OBDal.getInstance().getProxy(Column.class, IDENTIFIER_COLUMN_ID));
    sequence.setMask("#######");
    sequence.setName(IDENTIFIER_SEQUENCE);
    sequence.setAutoNumbering(true);
    sequence.setIncrementBy(1L);
    sequence.setStartingNo(1000000L);
    sequence.setNextAssignedNumber(1000000L);
    OBDal.getInstance().save(sequence);
    OBDal.getInstance().flush();
  }

  @After
  public void rollBack() {
    try {
      flag.close();
      BatchService.endCallerOwnedTransaction();
      OBDal.getInstance().rollbackAndClose();
    } finally {
      while (OBContext.getOBContext() != null
          && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  /** A new customer with a tax ID stores the tax ID as mandate and today as signature date. */
  @Test
  public void aNewCustomerStoresItsTaxIdAndTodayAsItsMandate() throws Exception {
    String nif = randomNif();

    BusinessPartner bp = create(new JSONObject().put("taxID", nif));

    assertEquals(nif, bp.getObsepadMandateIdentifier());
    assertEquals(LocalDate.now(), day(bp.getObsepadMandateDate()));
    // The SPA does not re-read after a create: the POST response must carry the stored values.
    assertEquals(lastCreated.toString(), nif,
        NeoRestTestClient.findKey(lastCreated, "obsepadMandateIdentifier"));
    assertNotNull(lastCreated.toString(),
        NeoRestTestClient.findKey(lastCreated, "obsepadMandateDate"));
  }

  /** What the caller sends always wins over the defaults. */
  @Test
  public void aMandateTheCallerSendsIsStoredAsSent() throws Exception {
    BusinessPartner bp = create(new JSONObject().put("taxID", randomNif())
        .put("obsepadMandateIdentifier", "MANDATE-001").put("obsepadMandateDate", "2026-01-15"));

    assertEquals("MANDATE-001", bp.getObsepadMandateIdentifier());
    assertEquals(LocalDate.of(2026, 1, 15), day(bp.getObsepadMandateDate()));
  }

  /** A customer created without a tax ID gets the date only; its first tax ID then fills the ID. */
  @Test
  public void aCustomersFirstTaxIdFillsTheEmptyMandateId() throws Exception {
    BusinessPartner bp = create(new JSONObject());
    assertNull(bp.getObsepadMandateIdentifier());
    assertEquals(LocalDate.now(), day(bp.getObsepadMandateDate()));

    String nif = randomNif();
    patch(bp, new JSONObject().put("taxID", nif));

    assertEquals(nif, bp.getObsepadMandateIdentifier());

    // The identifier is now a mandate reference: a later CIF change does not move it.
    patch(bp, new JSONObject().put("taxID", randomNif()));
    assertEquals(nif, storedMandateId(bp));
  }

  /** A later change of the tax ID never changes a mandate identifier already set. */
  @Test
  public void changingTheTaxIdKeepsTheMandateId() throws Exception {
    String nif = randomNif();
    BusinessPartner bp = create(new JSONObject().put("taxID", nif));

    patch(bp, new JSONObject().put("taxID", randomNif()));

    assertEquals(nif, bp.getObsepadMandateIdentifier());
  }

  /**
   * A vendor-only contact gets no mandate, even with the date the form's DEFAULTS prefilled;
   * becoming a customer fills it.
   */
  @Test
  public void aVendorGetsNoMandateUntilItBecomesACustomer() throws Exception {
    String nif = randomNif();
    BusinessPartner bp = create(new JSONObject().put("taxID", nif).put("customer", false)
        .put("vendor", true).put("obsepadMandateDate", LocalDate.now().toString()));
    assertNull(bp.getObsepadMandateIdentifier());
    assertNull(bp.getObsepadMandateDate());

    // Saves of the vendor, a new CIF included, never fill it.
    nif = randomNif();
    patch(bp, new JSONObject().put("name", bp.getName() + " renamed").put("taxID", nif));
    assertNull("a non-customer update never fills", storedMandateId(bp));
    assertNull(bp.getObsepadMandateDate());

    patch(bp, new JSONObject().put("customer", true));

    assertEquals(nif, storedMandateId(bp));
    assertEquals(day(bp.getCreationDate()).toString(), storedMandateDate(bp));
  }

  /**
   * An existing customer whose mandate is empty (created before the feature, or with the flag
   * off) gets it filled by its next save, even an unrelated one: its tax ID and creation date.
   */
  @Test
  public void anUnrelatedSaveFillsAnEmptyMandate() throws Exception {
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(false);
    String nif = randomNif();
    BusinessPartner bp = create(new JSONObject().put("taxID", nif));
    assertNull("flag off: nothing stored", bp.getObsepadMandateIdentifier());
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(true);

    patch(bp, new JSONObject().put("name", bp.getName() + " renamed"));

    assertEquals(nif, bp.getObsepadMandateIdentifier());
    assertEquals(day(bp.getCreationDate()), day(bp.getObsepadMandateDate()));
  }

  /**
   * An explicit clear is respected for that save; with no marker column, the next save that does
   * not send the key fills it again.
   */
  @Test
  public void anExplicitClearStaysClearedInThatSave() throws Exception {
    String nif = randomNif();
    BusinessPartner bp = create(new JSONObject().put("taxID", nif));

    patch(bp, new JSONObject().put("obsepadMandateIdentifier", JSONObject.NULL));
    assertNull("cleared in that save", storedMandateId(bp));

    patch(bp, new JSONObject().put("name", bp.getName() + " renamed"));
    assertEquals("the next save refills it", nif, storedMandateId(bp));
  }

  /** With the flag off no save fills an empty mandate, not even one that gives the first CIF. */
  @Test
  public void withTheFlagOffASaveNeverFills() throws Exception {
    BusinessPartner bp = create(new JSONObject());
    // A customer with only the creation date: clear it so both fields are empty.
    bp.setObsepadMandateDate(null);
    OBDal.getInstance().flush();
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(false);

    patch(bp, new JSONObject().put("taxID", randomNif()));
    patch(bp, new JSONObject().put("name", bp.getName() + " renamed"));

    assertNull(storedMandateId(bp));
    assertNull(bp.getObsepadMandateDate());
  }

  /** A create sent in a {@code data} envelope (as some clients do) gets the same mandate. */
  @Test
  public void aCreateInADataEnvelopeStoresTheMandate() throws Exception {
    String nif = randomNif();
    // searchKey sent: the handler writes its placeholder only at the top level of the body, so an
    // envelope create without one is refused as missing it, mandate or not.
    JSONObject data = new JSONObject().put("name", "SF mandate IT " + System.nanoTime())
        .put("searchKey", "SFM" + System.nanoTime()).put("customer", true).put("taxID", nif);

    BusinessPartner bp = createRaw(new JSONObject().put("data", data));

    assertEquals(nif, bp.getObsepadMandateIdentifier());
    assertEquals(LocalDate.now(), day(bp.getObsepadMandateDate()));
  }

  /**
   * A create that does not send the type takes it from its category (contact-category-type rules):
   * a customer category's contact gets the mandate, a vendor-only category's contact does not —
   * the mandate rule runs on the inherited type.
   */
  @Test
  public void aTypeInheritedFromTheCategoryDecidesTheMandate() throws Exception {
    String customerCategory = categoryWithType(true, false);
    String vendorCategory = categoryWithType(false, true);
    try (MockedStatic<com.etendoerp.go.schemaforge.handlers.ContactTypeRules> typeRules =
        Mockito.mockStatic(com.etendoerp.go.schemaforge.handlers.ContactTypeRules.class,
            Mockito.CALLS_REAL_METHODS)) {
      typeRules.when(com.etendoerp.go.schemaforge.handlers.ContactTypeRules::isEnabled)
          .thenReturn(true);
      String nif = randomNif();
      BusinessPartner customer = createRaw(new JSONObject()
          .put("name", "SF mandate IT " + System.nanoTime()).put("taxID", nif)
          .put("businessPartnerCategory", customerCategory));
      BusinessPartner vendor = createRaw(new JSONObject()
          .put("name", "SF mandate IT " + System.nanoTime()).put("taxID", randomNif())
          .put("businessPartnerCategory", vendorCategory));

      assertTrue("inherited customer", Boolean.TRUE.equals(customer.isCustomer()));
      assertEquals(nif, customer.getObsepadMandateIdentifier());
      assertEquals(LocalDate.now(), day(customer.getObsepadMandateDate()));
      assertTrue("inherited vendor only", !Boolean.TRUE.equals(vendor.isCustomer()));
      assertNull(vendor.getObsepadMandateIdentifier());
      assertNull(vendor.getObsepadMandateDate());
    }
  }

  /** The new-contact form shows the signature date the create will store, only with the flag on. */
  @Test
  public void theDefaultsCarryTheSignatureDateOnlyWithTheFlagOn() throws Exception {
    NeoResponse on = NeoRestTestClient.defaults(SPEC, ENTITY);
    assertEquals(String.valueOf(on.getBody()), 200, on.getHttpStatus());
    assertEquals(on.getBody().toString(), LocalDate.now().toString(),
        String.valueOf(NeoRestTestClient.findKey(on.getBody(), "obsepadMandateDate")));

    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(false);
    NeoResponse off = NeoRestTestClient.defaults(SPEC, ENTITY);
    assertEquals(200, off.getHttpStatus());
    assertNull(off.getBody().toString(),
        NeoRestTestClient.findKey(off.getBody(), "obsepadMandateDate"));
  }

  /** An agent's MCP {@code etendo_update}, even an unrelated one, fills an empty mandate. */
  @Test
  public void anMcpUpdateFillsAnEmptyMandate() throws Exception {
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(false);
    String nif = randomNif();
    BusinessPartner bp = create(new JSONObject().put("taxID", nif));
    assertNull(bp.getObsepadMandateIdentifier());
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(true);

    JSONObject result = com.etendoerp.go.mcp.McpToolRouterTestAccess.handleUpdate(SPEC,
        new JSONObject().put("entity", ENTITY).put("id", bp.getId())
            .put("fields", new JSONObject().put("name", bp.getName() + " renamed"))
            .put("updated", NeoRestTestClient.readUpdated(SPEC, ENTITY, bp.getId())));

    assertTrue(result.toString(), !result.optBoolean("isError", false));
    assertEquals(nif, storedMandateId(bp));
    OBDal.getInstance().refresh(bp);
    assertEquals(day(bp.getCreationDate()), day(bp.getObsepadMandateDate()));
  }

  /**
   * {@code /sws/neo/batch} only creates ({@code BatchService} has no update operation), so the
   * create path is what a batch can exercise: a customer created by a batch operation gets the
   * mandate. The second operation reads the first one's row and then fails on purpose, so the
   * batch rolls back and commits nothing.
   */
  @Test
  public void aCustomerCreatedByABatchGetsTheMandate() throws Exception {
    String nif = randomNif();
    String name = "SF mandate IT " + System.nanoTime();
    String[] seen = new String[2];
    org.codehaus.jettison.json.JSONArray operations = new org.codehaus.jettison.json.JSONArray()
        .put(new JSONObject().put("id", "bp").put("spec", SPEC).put("entity", ENTITY)
            .put("body", new JSONObject().put("name", name).put("customer", true)
                .put("taxID", nif)))
        .put(new JSONObject().put("id", "probe").put("spec", SPEC).put("entity", ENTITY)
            .put("body", new JSONObject().put("name", name + " probe")));

    JSONObject result = BatchService.forBatchOnly().executeBatch(operations, op -> {
      if (!"probe".equals(op.opId())) {
        return null;
      }
      Object[] row = (Object[]) OBDal.getInstance().getSession().createNativeQuery(
          "select em_obsepad_mandate_identifier, to_char(em_obsepad_mandate_date, 'YYYY-MM-DD')"
              + " from c_bpartner where name = :name")
          .setParameter("name", name).uniqueResult();
      seen[0] = row != null && row[0] != null ? row[0].toString() : null;
      seen[1] = row != null && row[1] != null ? row[1].toString() : null;
      return new JSONObject().put(BatchService.FIELD_COMMITTED, false)
          .put("failedAt", new JSONObject().put("id", "probe").put("index", 1));
    });

    assertEquals(result.toString(), false, result.getBoolean(BatchService.FIELD_COMMITTED));
    assertEquals("the batch create stored the CIF as mandate", nif, seen[0]);
    assertEquals(LocalDate.now().toString(), seen[1]);
  }

  /** The signature date as the database holds it, {@code yyyy-MM-dd}. */
  private static String storedMandateDate(BusinessPartner bp) {
    OBDal.getInstance().flush();
    Object value = OBDal.getInstance().getSession().createNativeQuery(
        "select to_char(em_obsepad_mandate_date, 'YYYY-MM-DD') from c_bpartner"
            + " where c_bpartner_id = :id")
        .setParameter("id", bp.getId()).uniqueResult();
    return value != null ? value.toString() : null;
  }

  /**
   * The identifier as the database holds it. Read with SQL: after three writes in one session the
   * DAL instance can still hold the value the clear left in it.
   */
  private static String storedMandateId(BusinessPartner bp) {
    OBDal.getInstance().flush();
    Object value = OBDal.getInstance().getSession().createNativeQuery(
        "select em_obsepad_mandate_identifier from c_bpartner where c_bpartner_id = :id")
        .setParameter("id", bp.getId()).uniqueResult();
    return value != null ? value.toString() : null;
  }

  // ---------------------------------------------------------------- helpers

  private BusinessPartner create(JSONObject body) throws Exception {
    String name = "SF mandate IT " + System.nanoTime();
    body.put("name", name);
    if (!body.has("customer")) {
      body.put("customer", true);
    }
    NeoResponse created = NeoRestTestClient.send(SPEC, ENTITY, "POST", null, body);
    assertTrue(String.valueOf(created.getBody()), created.getHttpStatus() < 300);
    lastCreated = created.getBody();
    OBDal.getInstance().flush();
    Object id = NeoRestTestClient.findKey(created.getBody(), "id");
    assertNotNull("the create answers the id: " + created.getBody(), id);
    BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, id.toString());
    assertNotNull(bp);
    OBDal.getInstance().refresh(bp);
    return bp;
  }

  /** A create of exactly {@code body}, nothing added. */
  private BusinessPartner createRaw(JSONObject body) throws Exception {
    NeoResponse created = NeoRestTestClient.send(SPEC, ENTITY, "POST", null, body);
    assertTrue(String.valueOf(created.getBody()), created.getHttpStatus() < 300);
    OBDal.getInstance().flush();
    Object id = NeoRestTestClient.findKey(created.getBody(), "id");
    assertNotNull("the create answers the id: " + created.getBody(), id);
    BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, id.toString());
    assertNotNull(bp);
    OBDal.getInstance().refresh(bp);
    return bp;
  }

  /** A contact category of the sample client set, in this transaction, to the given type. */
  private static String categoryWithType(boolean customer, boolean vendor) {
    String id = (String) OBDal.getInstance().getSession().createNativeQuery(
        "select c_bp_group_id from c_bp_group where ad_client_id = :client and isactive = 'Y'"
            + " and c_bp_group_id not in (:taken) order by name limit 1")
        .setParameter("client", OBContext.getOBContext().getCurrentClient().getId())
        .setParameterList("taken", takenCategories.isEmpty() ? java.util.List.of("-")
            : takenCategories)
        .uniqueResult();
    assertNotNull("the sample client has a contact category", id);
    takenCategories.add(id);
    OBDal.getInstance().getSession().createNativeQuery(
        "update c_bp_group set em_etgo_iscustomer = :customer, em_etgo_isvendor = :vendor"
            + " where c_bp_group_id = :id")
        .setParameter("customer", customer ? "Y" : "N")
        .setParameter("vendor", vendor ? "Y" : "N")
        .setParameter("id", id)
        .executeUpdate();
    return id;
  }

  private static final java.util.List<String> takenCategories = new java.util.ArrayList<>();

  private void patch(BusinessPartner bp, JSONObject changes) throws Exception {
    NeoResponse updated = NeoRestTestClient.patch(SPEC, ENTITY, bp.getId(), changes);
    assertTrue(String.valueOf(updated.getBody()), updated.getHttpStatus() < 300);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(bp);
  }

  /** A valid Spanish NIF (8 digits + control letter), random so it does not collide. */
  private static String randomNif() {
    int number = ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
    return number + String.valueOf(NIF_LETTERS.charAt(number % 23));
  }

  private static LocalDate day(Date date) {
    if (date == null) {
      return null;
    }
    if (date instanceof java.sql.Date) {
      return ((java.sql.Date) date).toLocalDate();
    }
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
  }
}
