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

package com.etendoerp.go.schemaforge.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.FiscalTestModeResolver;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The write rules of the three fiscal-configuration entities: no inactive create and none in forced
 * test mode on any channel, one active config per system and organization (REST reactivation
 * included), and no {@code active} change by an agent. When either side of an {@code active}
 * change is {@link FiscalSifGuard.ActiveFlag#UNKNOWN} (no readable value in the body, no record id,
 * a record that does not exist, a system with no table) the guard lets the write through. The
 * fiscal tables are answered by a fake
 * native-query session, so the assertions are on the refusal (status and message key) and on the
 * table each rule reads.
 *
 * @covers com.etendoerp.go.schemaforge.fiscal.FiscalSifGuard
 */
class FiscalSifGuardTest {

  static final String ORG = "ORG-1";
  private static final String RECORD = "REC-1";

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<FiscalTestModeResolver> testModeStatic;
  private FakeFiscalTables tables;
  private OBContext obContext;

  @BeforeEach
  void setUp() throws Exception {
    tables = new FakeFiscalTables();
    dalStatic = mockStatic(OBDal.class);
    obContextStatic = mockStatic(OBContext.class);
    testModeStatic = mockStatic(FiscalTestModeResolver.class);
    OBDal dal = mock(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    Session session = tables.session();
    when(dal.getSession()).thenReturn(session);

    obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(obContext.getCurrentClient()).thenReturn(mock(Client.class));
    obContextStatic.when(OBContext::getOBContext).thenReturn(obContext);
  }

  @AfterEach
  void tearDown() throws Exception {
    testModeStatic.close();
    obContextStatic.close();
    dalStatic.close();
  }

  private NeoContext crud(String method, JSONObject body, boolean mcp) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .recordId("POST".equals(method) ? null : RECORD)
        .requestBody(body)
        .obContext(obContext)
        .mcpOrigin(mcp)
        .build();
  }

  static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }

  static String message(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  // ── POST ─────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "mcp={0}")
  @ValueSource(booleans = { false, true })
  void anInactiveCreateIsRefusedOnEveryChannel(boolean mcp) throws Exception {
    NeoResponse refusal = FiscalSifGuard.preCheck(
        crud("POST", new JSONObject().put("active", false), mcp), FiscalSifRules.SII);

    assertEquals(400, refusal.getHttpStatus());
    assertEquals(FiscalSifGuard.MSG_INACTIVE_CREATE, messageKey(refusal));
    assertTrue(tables.sqls.isEmpty(), "refused before any query");
  }

  @Test
  void anInactiveCreateSpelledNIsRefusedToo() throws Exception {
    NeoResponse refusal = FiscalSifGuard.preCheck(
        crud("POST", new JSONObject().put("active", "N"), false), FiscalSifRules.TBAI);

    assertEquals(FiscalSifGuard.MSG_INACTIVE_CREATE, messageKey(refusal));
  }

  @Test
  void aCreateInForcedTestModeIsRefused() throws Exception {
    testModeStatic.when(() -> FiscalTestModeResolver.isForceTestModeActive(any()))
        .thenReturn(true);

    NeoResponse refusal = FiscalSifGuard.preCheck(crud("POST", new JSONObject(), false),
        FiscalSifRules.VERIFACTU);

    assertEquals(409, refusal.getHttpStatus());
    assertEquals(FiscalSifGuard.MSG_TEST_MODE, messageKey(refusal));
  }

  @Test
  void aSecondActiveConfigOfTheSameSystemIsRefused() throws Exception {
    tables.active.put("tbai_config", "EXISTING");

    NeoResponse refusal = FiscalSifGuard.preCheck(crud("POST", new JSONObject(), false),
        FiscalSifRules.TBAI);

    assertEquals(409, refusal.getHttpStatus());
    assertEquals(FiscalSifGuard.MSG_ONE_ACTIVE, messageKey(refusal));
    assertTrue(message(refusal).contains("TicketBAI"), message(refusal));
    assertTrue(tables.sqls.get(0).contains("FROM tbai_config"));
  }

  @Test
  void anActiveConfigOfAnotherSystemDoesNotBlockTheCreate() throws Exception {
    tables.active.put("tbai_config", "EXISTING");

    assertNull(FiscalSifGuard.preCheck(crud("POST", new JSONObject(), false),
        FiscalSifRules.SII));
  }

  @Test
  void aFirstCreateProceeds() throws Exception {
    assertNull(FiscalSifGuard.preCheck(crud("POST", new JSONObject().put("active", true), true),
        FiscalSifRules.SII));
  }

  // ── PUT / PATCH ──────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "PUT", "PATCH" })
  void anAgentCannotChangeActive(String method) throws Exception {
    tables.isActive.put(RECORD, "Y");

    NeoResponse refusal = FiscalSifGuard.preCheck(
        crud(method, new JSONObject().put("active", false), true), FiscalSifRules.SII);

    assertEquals(400, refusal.getHttpStatus());
    assertEquals(FiscalSifGuard.MSG_ACTIVE_BY_AGENT, messageKey(refusal));
  }

  @Test
  void anAgentCannotReactivateEither() throws Exception {
    tables.isActive.put(RECORD, "N");

    NeoResponse refusal = FiscalSifGuard.preCheck(
        crud("PUT", new JSONObject().put("active", true), true), FiscalSifRules.VERIFACTU);

    assertEquals(FiscalSifGuard.MSG_ACTIVE_BY_AGENT, messageKey(refusal));
    assertTrue(tables.sqls.get(0).contains("FROM etvfac_verifactu_config"));
  }

  @Test
  void restCanDeactivateCambiarSif() throws Exception {
    tables.isActive.put(RECORD, "Y");

    assertNull(FiscalSifGuard.preCheck(crud("PUT", new JSONObject().put("active", false), false),
        FiscalSifRules.SII));
  }

  @Test
  void anAgentEchoingTheCurrentActiveValuePasses() throws Exception {
    tables.isActive.put(RECORD, "Y");

    assertNull(FiscalSifGuard.preCheck(
        crud("PUT", new JSONObject().put("active", true).put("taxtype", "IVA"), true),
        FiscalSifRules.SII));
  }

  @Test
  void anAgentUpdateWithoutActiveNeverReadsTheRecord() throws Exception {
    assertNull(FiscalSifGuard.preCheck(crud("PUT", new JSONObject().put("taxtype", "IVA"), true),
        FiscalSifRules.SII));
    assertTrue(tables.sqls.isEmpty());
  }

  @Test
  void restReactivationIsRefusedWhenAnotherConfigIsActive() throws Exception {
    tables.isActive.put(RECORD, "N");
    tables.orgOf.put(RECORD, ORG);
    tables.active.put("aeatsii_config", "OTHER");

    NeoResponse refusal = FiscalSifGuard.preCheck(
        crud("PUT", new JSONObject().put("active", true), false), FiscalSifRules.SII);

    assertEquals(409, refusal.getHttpStatus());
    assertEquals(FiscalSifGuard.MSG_ONE_ACTIVE, messageKey(refusal));
  }

  @Test
  void restReactivationProceedsWhenNoOtherConfigIsActive() throws Exception {
    tables.isActive.put(RECORD, "N");
    tables.orgOf.put(RECORD, ORG);
    // The only active row is the record itself, which the query excludes.
    tables.active.put("aeatsii_config", RECORD);

    assertNull(FiscalSifGuard.preCheck(crud("PUT", new JSONObject().put("active", true), false),
        FiscalSifRules.SII));
  }

  // ── UNKNOWN active flag ──────────────────────────────────────────────────

  @Test
  void anActiveChangeOnARecordThatDoesNotExistPasses() throws Exception {
    // tables.isActive has no row for RECORD: the stored flag is UNKNOWN.
    assertNull(FiscalSifGuard.preCheck(
        crud("PUT", new JSONObject().put("active", false), true), FiscalSifRules.SII));

    assertEquals(1, tables.sqls.size(), "only the isactive read, never the organization");
    assertTrue(tables.sqls.get(0).startsWith("SELECT isactive FROM aeatsii_config"));
  }

  @Test
  void anActiveChangeWithoutARecordIdNeverReadsTheTable() throws Exception {
    NeoContext noRecord = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .requestBody(new JSONObject().put("active", true))
        .obContext(obContext)
        .mcpOrigin(true)
        .build();

    assertNull(FiscalSifGuard.preCheck(noRecord, FiscalSifRules.TBAI));
    assertTrue(tables.sqls.isEmpty());
  }

  @ParameterizedTest(name = "active={0}")
  @ValueSource(strings = { "maybe", "", "1" })
  void anUnreadableRequestedActivePassesEvenForAnAgent(String value) throws Exception {
    tables.isActive.put(RECORD, "Y");

    assertNull(FiscalSifGuard.preCheck(
        crud("PUT", new JSONObject().put("active", value), true), FiscalSifRules.SII));
  }

  @Test
  void aNullRequestedActivePassesEvenForAnAgent() throws Exception {
    tables.isActive.put(RECORD, "Y");

    assertNull(FiscalSifGuard.preCheck(
        crud("PUT", new JSONObject().put("active", JSONObject.NULL), true), FiscalSifRules.SII));
  }

  @Test
  void anActiveChangeOnASystemWithoutATableIsUnknownAndPasses() throws Exception {
    assertNull(FiscalSifGuard.preCheck(
        crud("PUT", new JSONObject().put("active", false), true), "SII+TBAI"));
    assertTrue(tables.sqls.isEmpty());
  }

  @Test
  void aCreateWithAnUnreadableActiveIsNotTakenAsInactive() throws Exception {
    assertNull(FiscalSifGuard.preCheck(
        crud("POST", new JSONObject().put("active", "maybe"), false), FiscalSifRules.SII));
    // It went on to the one-active check, which reads the table.
    assertTrue(tables.sqls.get(0).contains("FROM aeatsii_config"));
  }

  @Test
  void requestedActiveIsUnknownForAJsonNullAndTrimsTheText() throws Exception {
    assertEquals(FiscalSifGuard.ActiveFlag.UNKNOWN,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", JSONObject.NULL)));
    assertEquals(FiscalSifGuard.ActiveFlag.ACTIVE,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", true)));
    assertEquals(FiscalSifGuard.ActiveFlag.ACTIVE,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", " TRUE ")));
    assertEquals(FiscalSifGuard.ActiveFlag.INACTIVE,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", " n")));
    assertEquals(FiscalSifGuard.ActiveFlag.UNKNOWN,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", 1)));
  }

  // ── other calls ──────────────────────────────────────────────────────────

  @Test
  void nonCrudCallsAreIgnored() throws Exception {
    NeoContext action = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .requestBody(new JSONObject())
        .mcpOrigin(true)
        .build();

    assertNull(FiscalSifGuard.preCheck(action, FiscalSifRules.SII));
    assertNull(FiscalSifGuard.preCheck(null, FiscalSifRules.SII));
    assertTrue(tables.sqls.isEmpty());
  }

  @Test
  void readsAndDeletesAreNotTheGuardsBusiness() throws Exception {
    assertNull(FiscalSifGuard.preCheck(crud("GET", null, true), FiscalSifRules.SII));
    assertNull(FiscalSifGuard.preCheck(crud("DELETE", null, true), FiscalSifRules.SII));
    assertTrue(tables.sqls.isEmpty());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  @Test
  void requestedActiveReadsBooleansAndTheEtendoSpelling() throws Exception {
    assertEquals(FiscalSifGuard.ActiveFlag.ACTIVE,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", "Y")));
    assertEquals(FiscalSifGuard.ActiveFlag.INACTIVE,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", "false")));
    assertEquals(FiscalSifGuard.ActiveFlag.UNKNOWN,
        FiscalSifGuard.requestedActive(new JSONObject().put("active", "maybe")));
    assertEquals(FiscalSifGuard.ActiveFlag.UNKNOWN,
        FiscalSifGuard.requestedActive(new JSONObject()));
    assertEquals(FiscalSifGuard.ActiveFlag.UNKNOWN, FiscalSifGuard.requestedActive(null));
  }

  @Test
  void activeRecordsListsOnlyTheActiveSystemsInOrder() throws Exception {
    tables.active.put("etvfac_verifactu_config", "V");
    tables.active.put("aeatsii_config", "S");

    Map<String, String> active = FiscalSifGuard.activeRecords(ORG);

    assertEquals(List.of("SII", "VERIFACTU"), new ArrayList<>(active.keySet()));
    assertEquals("S", active.get("SII"));
  }

  @Test
  void displayAndTerritoryNamesAreWhatAPersonReads() throws Exception {
    assertEquals("SII + TicketBAI", FiscalSifGuard.displayName("SII+TBAI"));
    assertEquals("VERI*FACTU", FiscalSifGuard.displayName("VERIFACTU"));
    assertEquals("Álava", FiscalSifGuard.territoryName("alava"));
    assertEquals("madrid", FiscalSifGuard.territoryName("madrid"));
    assertNull(FiscalSifGuard.territoryName(null));
  }

  @Test
  void theAgentOnboardingFlagHoldsOnlyInsideItsScope() throws Exception {
    assertFalse(FiscalSifGuard.isFiscalSystemActivation());
    assertTrue(FiscalSifGuard.asFiscalSystemActivation(FiscalSifGuard::isFiscalSystemActivation));
    assertFalse(FiscalSifGuard.isFiscalSystemActivation());
  }

  /**
   * The fiscal tables, answered by SQL shape: the active row per table (for the org), the
   * {@code isactive} and {@code ad_org_id} of a record, and the TicketBAI territory. Records every
   * SQL text so a test can assert which table a rule read.
   */
  static final class FakeFiscalTables {
    final Map<String, String> active = new HashMap<>();
    final Map<String, String> isActive = new HashMap<>();
    final Map<String, String> orgOf = new HashMap<>();
    String tbaiTerritory;
    final List<String> sqls = new ArrayList<>();

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Session session() {
      Session session = mock(Session.class);
      when(session.createNativeQuery(anyString())).thenAnswer(inv -> {
        String sql = inv.getArgument(0);
        sqls.add(sql);
        Map<String, Object> params = new HashMap<>();
        NativeQuery query = mock(NativeQuery.class);
        when(query.setParameter(anyString(), any())).thenAnswer(a -> {
          params.put(a.getArgument(0), a.getArgument(1));
          return query;
        });
        when(query.uniqueResult()).thenAnswer(a -> answer(sql, params));
        return query;
      });
      return session;
    }

    private Object answer(String sql, Map<String, Object> params) {
      String table = sql.replaceFirst("(?s).* FROM (\\w+) .*", "$1");
      if (sql.contains("isactive = 'Y'")) {
        String id = active.get(table);
        boolean match = id != null && ORG.equals(params.get("orgId"))
            && !id.equals(params.get("excluded"));
        return match ? id : null;
      }
      if (sql.startsWith("SELECT isactive")) {
        return isActive.get(params.get("id"));
      }
      if (sql.startsWith("SELECT ad_org_id")) {
        return orgOf.get(params.get("id"));
      }
      if (sql.startsWith("SELECT etsg_sif_territory")) {
        return tbaiTerritory;
      }
      throw new AssertionError("unexpected SQL: " + sql);
    }
  }

  @Test
  void tbaiTerritoryOfReadsTheTicketBaiConfig() throws Exception {
    tables.tbaiTerritory = "BIZKAIA";

    assertEquals("BIZKAIA", FiscalSifGuard.tbaiTerritoryOf("T"));
    assertTrue(tables.sqls.get(0).contains("FROM tbai_config"));
  }
}
