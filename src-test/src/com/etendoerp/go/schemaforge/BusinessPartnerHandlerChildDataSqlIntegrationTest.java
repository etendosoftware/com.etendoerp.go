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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * Schema guardrail for the two child-data statements
 * {@link BusinessPartnerHandler#attachChildData} runs on a contacts list GET carrying
 * {@code includeChildData=1} (ETP-4997): {@link BusinessPartnerHandler#PRIMARY_LOCATIONS_SQL} and
 * {@link BusinessPartnerHandler#PRIMARY_CONTACTS_SQL}.
 *
 * <p><b>Why a DB-backed test and not a unit test.</b> {@code attachChildData} is deliberately
 * non-fatal — "a failed enrichment must cost the user empty columns, never their export" — so it
 * catches every {@code SQLException} and returns {@code null}, leaving the default CRUD result
 * untouched. That makes a malformed statement completely invisible: the export still streams, the
 * headers still match the import template, and the only symptom is that the ten contact-person and
 * address columns come out blank, which is also what a partner with no contact and no address
 * legitimately looks like. There is no error for a mock-based test to observe, and mocking the
 * JDBC layer would assert the SQL against the test's own idea of the schema rather than the real
 * one.
 *
 * <p>That is exactly how {@code loc.postcode} shipped. {@code C_Location}'s postal-code column is
 * {@code postal}; {@code postcode} does not exist, so every execution threw, was swallowed, and
 * the exported "Dirección" columns were silently empty for every partner. Executing both
 * statements against a live schema turns that class of mistake into a failing test.
 *
 * <p><b>What is asserted.</b> For each statement, independently of how much data the environment
 * holds:
 * <ol>
 *   <li>it <em>executes</em> — Postgres parses the statement on execute, so a non-existent column,
 *       table or function fails here;</li>
 *   <li>its result set exposes {@code c_bpartner_id} plus every column name
 *       {@code queryChildData()} reads by name (the {@code LOCATION_COLUMNS} /
 *       {@code CONTACT_COLUMNS} arrays the handler itself uses, so the test cannot drift from the
 *       code it guards). A statement that parses but was edited to project a differently-named
 *       column would pass (1) and fail here;</li>
 *   <li>every row it returns is readable through {@code ResultSet.getString(name)} with those
 *       exact names — the call {@code queryChildData()} makes.</li>
 * </ol>
 *
 * <p>The parameter is bound the same way the handler binds it ({@code = ANY(?)} over a
 * {@code varchar} array), because that binding is itself schema-sensitive: {@code C_BPartner_ID} is
 * {@code VARCHAR}, and an array of the wrong element type is rejected by the server with
 * "operator does not exist: character varying = ...".
 *
 * <p>Assertions about which contact or address is "primary" are NOT made here: those are ordering
 * decisions, not schema facts, and asserting them would require seeding rows this test
 * deliberately does not create. The schema checks only read; the one exception is the derivation
 * of {@code street} / {@code buildingNumber}, which lives in the SQL and is checked by overwriting
 * an existing partner's addresses inside the test transaction and rolling it back.
 *
 * @covers com.etendoerp.go.schemaforge.BusinessPartnerHandler
 */
public class BusinessPartnerHandlerChildDataSqlIntegrationTest extends OBBaseTest {

  /** Business partners to bind, and the ceiling on how many rows either statement may return. */
  private static final int SAMPLE_SIZE = 50;

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testPrimaryLocationsSqlMatchesTheLiveSchema() throws Exception {
    assertStatementMatchesSchema(BusinessPartnerHandler.PRIMARY_LOCATIONS_SQL,
        BusinessPartnerHandler.LOCATION_COLUMNS, "PRIMARY_LOCATIONS_SQL");
  }

  @Test
  public void testPrimaryContactsSqlMatchesTheLiveSchema() throws Exception {
    assertStatementMatchesSchema(BusinessPartnerHandler.PRIMARY_CONTACTS_SQL,
        BusinessPartnerHandler.CONTACT_COLUMNS, "PRIMARY_CONTACTS_SQL");
  }

  /**
   * The two key arrays must stay positionally paired with their column arrays: {@code
   * queryChildData()} walks {@code columns[i] -> keys[i]}, so a column added to one array and not
   * the other would silently drop the last field (or throw an out-of-bounds on a longer keys
   * array). Cheap to assert and it costs no DB round trip.
   */
  @Test
  public void testColumnAndKeyArraysArePaired() {
    assertTrue("LOCATION_COLUMNS/LOCATION_KEYS must have the same length",
        BusinessPartnerHandler.LOCATION_COLUMNS.length == BusinessPartnerHandler.LOCATION_KEYS.length);
    assertTrue("CONTACT_COLUMNS/CONTACT_KEYS must have the same length",
        BusinessPartnerHandler.CONTACT_COLUMNS.length == BusinessPartnerHandler.CONTACT_KEYS.length);
  }

  /**
   * The {@code street} / {@code buildingNumber} keys are derived in SQL from the structured
   * {@code EM_OBSEPA_StrtNm} / {@code EM_OBSEPA_BldgNb} columns, so only a real execution can tell
   * whether the {@code COALESCE(NULLIF(TRIM(...)))} / {@code CASE} expressions do what the export
   * needs. Driven end to end through {@code afterHandle()} — the real statement plus the real
   * {@code queryChildData()} mapping — so "a NULL or blank value leaves the key out" is asserted on
   * what the export actually receives.
   *
   * <p>Each case overwrites every active address of one existing partner (so whichever one ranks
   * first carries the case's values) inside the test transaction, which {@link #rollbackChanges}
   * discards. Nothing is committed.
   */
  @Test
  public void testStreetAndBuildingNumberAreDerivedFromTheStructuredAddress() throws Exception {
    // label, address1, em_obsepa_strtnm, em_obsepa_bldgnb, expected street,
    // expected buildingNumber (null = the key must be absent)
    String[][] cases = {
        { "structured address", "Calle Mayor, 12", "Calle Mayor", "12", "Calle Mayor", "12" },
        { "legacy address", "Av. Industria 45", null, null, "Av. Industria 45", null },
        { "empty street with a number", "Av. Industria 45", "", "7", "Av. Industria 45", null },
        { "whitespace street with a number", "Av. Industria 45", "   ", "7", "Av. Industria 45", null },
        { "street without a number", "Calle Mayor", "Calle Mayor", null, "Calle Mayor", null },
        { "street with a blank number", "Calle Mayor", "Calle Mayor", "  ", "Calle Mayor", null },
    };
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Connection conn = OBDal.getInstance().getConnection();
      String bpId = partnerWithAnAddress(conn);
      for (String[] c : cases) {
        String label = c[0];
        overwritePartnerAddresses(conn, bpId, c[1], c[2], c[3]);

        JSONObject child = childDataFor(bpId, label);

        assertEquals(label + ": address", c[1], child.optString("address", null));
        assertEquals(label + ": street", c[4], child.optString("street", null));
        if (c[5] == null) {
          assertFalse(label + ": buildingNumber must be absent, was '"
              + child.opt("buildingNumber") + "'", child.has("buildingNumber"));
        } else {
          assertEquals(label + ": buildingNumber", c[5], child.optString("buildingNumber", null));
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Any partner with an active address; the test fails loudly when the environment has none. */
  private static String partnerWithAnAddress(Connection conn) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT c_bpartner_id FROM c_bpartner_location WHERE isactive = 'Y' LIMIT 1");
        ResultSet rs = ps.executeQuery()) {
      assertTrue("no active C_BPartner_Location to derive a street from", rs.next());
      return rs.getString(1);
    }
  }

  /** Sets the three address columns on every active address of {@code bpId} (uncommitted). */
  private static void overwritePartnerAddresses(Connection conn, String bpId, String address1,
      String street, String buildingNumber) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE c_location SET address1 = ?, em_obsepa_strtnm = ?, em_obsepa_bldgnb = ?"
            + " WHERE c_location_id IN (SELECT c_location_id FROM c_bpartner_location"
            + "  WHERE c_bpartner_id = ? AND isactive = 'Y')")) {
      ps.setString(1, address1);
      ps.setString(2, street);
      ps.setString(3, buildingNumber);
      ps.setString(4, bpId);
      assertTrue("no address of " + bpId + " was updated", ps.executeUpdate() > 0);
    }
  }

  /** Runs the list-GET enrichment for one partner and returns its {@code etgoChildData}. */
  private static JSONObject childDataFor(String bpId, String label) throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject()
        .put("data", new JSONArray().put(new JSONObject().put("id", bpId))));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .queryParams(Map.of("includeChildData", "1"))
        .previousResult(NeoResponse.ok(body))
        .build();

    NeoResponse result = new BusinessPartnerHandler().afterHandle(ctx);

    // attachChildData() swallows a failing statement and declines; surface that as the failure.
    assertNotNull(label + ": etgoChildData was not attached — the enrichment failed", result);
    return result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
        .getJSONObject("etgoChildData");
  }

  /**
   * Executes {@code sql} exactly as the handler does and asserts its result set exposes
   * {@code c_bpartner_id} plus every name in {@code expectedColumns}, and that each returned row
   * is readable through those names.
   */
  private void assertStatementMatchesSchema(String sql, String[] expectedColumns, String label)
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Connection conn = OBDal.getInstance().getConnection();
      List<String> ids = sampleBusinessPartnerIds(conn);
      assertTrue("no C_BPartner rows to bind — cannot verify " + label
          + " against the live schema", !ids.isEmpty());

      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setArray(1, conn.createArrayOf("varchar", ids.toArray()));
        try (ResultSet rs = ps.executeQuery()) {
          Set<String> actual = columnNames(rs.getMetaData());
          assertColumnPresent(actual, "c_bpartner_id", label);
          for (String column : expectedColumns) {
            assertColumnPresent(actual, column, label);
          }
          int rows = 0;
          while (rs.next()) {
            assertNotNull(label + " returned a row without a c_bpartner_id",
                rs.getString("c_bpartner_id"));
            for (String column : expectedColumns) {
              // A null value is legitimate (a partner with no city); the point is that reading by
              // this name does not throw, which is the exact call queryChildData() makes.
              rs.getString(column);
            }
            rows++;
          }
          assertTrue(label + " returned " + rows + " rows for " + ids.size()
              + " partners — the row_number() ... WHERE rn = 1 filter must keep at most one per"
              + " partner", rows <= ids.size());
        }
      }
    } catch (SQLException e) {
      // Surfaced as a failure with the driver's own message, which names the offending column:
      // swallowing it here would reproduce the very silence this test exists to break.
      fail(label + " does not match the live schema: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static void assertColumnPresent(Set<String> actual, String column, String label) {
    assertTrue(label + " no longer exposes a '" + column + "' column (result set has "
        + actual + ") — queryChildData() reads it by name", actual.contains(column));
  }

  /** Result-set column labels, lower-cased so the assertion does not depend on driver casing. */
  private static Set<String> columnNames(ResultSetMetaData meta) throws SQLException {
    Set<String> names = new LinkedHashSet<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      names.add(meta.getColumnLabel(i).toLowerCase());
    }
    return names;
  }

  /**
   * A handful of real business-partner ids. Read straight off {@code C_BPartner} rather than
   * seeded: the statements are being checked against the schema, and any id makes them parse and
   * execute. Partners that happen to have a location or a contact make the row-reading half of
   * the assertion do real work, which is why the sample is not limited to one.
   */
  private static List<String> sampleBusinessPartnerIds(Connection conn) throws SQLException {
    List<String> ids = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT c_bpartner_id FROM c_bpartner WHERE isactive = 'Y' LIMIT " + SAMPLE_SIZE);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        ids.add(rs.getString(1));
      }
    }
    return ids;
  }
}
