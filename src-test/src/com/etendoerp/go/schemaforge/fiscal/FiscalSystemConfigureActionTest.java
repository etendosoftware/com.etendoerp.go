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

import static com.etendoerp.go.schemaforge.fiscal.FiscalSifGuardTest.message;
import static com.etendoerp.go.schemaforge.fiscal.FiscalSifGuardTest.messageKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.FiscalTestModeResolver;
import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * The {@code configureFiscalSystem} action: its refusals (status and message key), the records it
 * sends through the batch seam per system and territory, "Añadir SII" on an existing TicketBAI
 * (with or without a territory, and refused on a TicketBAI with no known territory), the
 * certificate warning, and how a batch failure is reported. The per-system window check: a role
 * without write access to the window of a system it would create gets 403
 * {@code ETGO_FiscalSifNoWindowAccess} naming that system, in the request language, before
 * anything reaches the batch (SII + TicketBAI needs both windows). Also the {@code fiscalSystemCatalog}
 * read action: its methods, its organization check and its content per territory, which must be
 * {@link FiscalSifRules} as data, and GET only. The {@code *} organization is refused (400
 * {@code ETGO_FiscalSifOrgStar}) before any statement; an activation that passes the request-level
 * checks locks the organization's {@code AD_OrgInfo} row first, before reading its active configs.
 *
 * @covers com.etendoerp.go.schemaforge.fiscal.FiscalSystemConfigureAction
 */
class FiscalSystemConfigureActionTest {

  private static final String ORG = FiscalSifGuardTest.ORG;
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<FiscalTestModeResolver> testModeStatic;
  private FiscalSifGuardTest.FakeFiscalTables tables;
  private OBContext obContext;

  /** What the batch seam received, and whether it ran as the agent onboarding. */
  private final List<JSONArray> batches = new ArrayList<>();
  private final List<Boolean> onboardingFlags = new ArrayList<>();
  private Function<JSONArray, JSONObject> batchAnswer;
  private Date certificate;

  @BeforeEach
  void setUp() {
    tables = new FiscalSifGuardTest.FakeFiscalTables();
    dalStatic = mockStatic(OBDal.class);
    obContextStatic = mockStatic(OBContext.class);
    testModeStatic = mockStatic(FiscalTestModeResolver.class);
    OBDal dal = mock(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    Session session = tables.session();
    when(dal.getSession()).thenReturn(session);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG);
    when(dal.get(Organization.class, ORG)).thenReturn(org);
    when(dal.get(Organization.class, "ORG-2")).thenReturn(mock(Organization.class));

    obContext = mock(OBContext.class);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    when(obContext.getCurrentClient()).thenReturn(mock(Client.class));
    when(obContext.getReadableOrganizations()).thenReturn(new String[] { "0", ORG, "ORG-2" });
    obContextStatic.when(OBContext::getOBContext).thenReturn(obContext);

    batchAnswer = FiscalSystemConfigureActionTest::committed;
    certificate = new Date();
  }

  @AfterEach
  void tearDown() {
    testModeStatic.close();
    obContextStatic.close();
    dalStatic.close();
  }

  private FiscalSystemConfigureAction action() {
    return new FiscalSystemConfigureAction(ops -> {
      batches.add(ops);
      onboardingFlags.add(FiscalSifGuard.isFiscalSystemActivation());
      return batchAnswer.apply(ops);
    }, () -> TODAY, orgId -> certificate);
  }

  /** The action wired through the 4-argument constructor, with the window-write predicate. */
  private FiscalSystemConfigureAction action(Predicate<String> canCreate) {
    return new FiscalSystemConfigureAction(ops -> {
      batches.add(ops);
      return batchAnswer.apply(ops);
    }, () -> TODAY, orgId -> certificate, canCreate);
  }

  private NeoResponse run(String orgId, JSONObject params) {
    return run(action(), orgId, params);
  }

  private NeoResponse run(FiscalSystemConfigureAction action, String orgId, JSONObject params) {
    return action.handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(FiscalSystemConfigureAction.ACTION)
        .httpMethod("POST")
        .recordId(orgId)
        .requestBody(params)
        .obContext(obContext)
        .mcpOrigin(true)
        .build());
  }

  private NeoResponse run(String system, String territory) throws Exception {
    return run(ORG, new JSONObject().put("system", system).put("territory", territory));
  }

  /** A committed batch outcome with one created record per operation. */
  private static JSONObject committed(JSONArray ops) {
    try {
      JSONArray created = new JSONArray();
      for (int i = 0; i < ops.length(); i++) {
        String id = ops.getJSONObject(i).getString("id");
        created.put(new JSONObject().put("id", id).put("recordId", "NEW-" + id));
      }
      return new JSONObject().put(BatchService.FIELD_COMMITTED, true).put("operations", created);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JSONObject operation(int index) throws Exception {
    return batches.get(0).getJSONObject(index);
  }

  // ── refusals ─────────────────────────────────────────────────────────────

  @Test
  void anOrganizationOtherThanTheSessionsIsRefused() throws Exception {
    NeoResponse response = run("ORG-2",
        new JSONObject().put("system", "SII").put("territory", "navarra"));

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_ORG_MISMATCH, messageKey(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void theStarOrganizationIsRefusedBeforeAnyLockOrRead() throws Exception {
    when(OBDal.getInstance().get(Organization.class, "0")).thenReturn(mock(Organization.class));

    NeoResponse response = run("0",
        new JSONObject().put("system", "SII").put("territory", "navarra"));

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_ORG_STAR, messageKey(response));
    assertTrue(tables.sqls.isEmpty(), "no lock and no read: " + tables.sqls);
    assertTrue(batches.isEmpty());
  }

  /** The lock is the first statement, on the requested organization, before the active reads. */
  @Test
  void anActivationLocksTheOrganizationBeforeReadingItsConfigs() throws Exception {
    NeoResponse response = run("SII", "navarra");

    assertEquals(200, response.getHttpStatus(), "no AD_OrgInfo row is tolerated");
    assertFalse(tables.sqls.isEmpty());
    String first = tables.sqls.get(0);
    assertTrue(first.contains("FROM ad_orginfo") && first.contains("FOR UPDATE"), first);
    assertEquals(ORG, tables.sqlParams.get(0).get("orgId"));
    assertTrue(tables.sqls.size() > 1, "activeRecords runs after the lock: " + tables.sqls);
    for (String later : tables.sqls.subList(1, tables.sqls.size())) {
      assertFalse(later.contains("FOR UPDATE"), "locked once: " + later);
    }
    assertEquals(1, batches.size());
  }

  @Test
  void aRefusalFoundUnderTheLockStillComesAfterIt() throws Exception {
    tables.active.put("etvfac_verifactu_config", "V");

    NeoResponse response = run("SII", "baleares");

    assertEquals(409, response.getHttpStatus());
    assertTrue(tables.sqls.get(0).contains("FROM ad_orginfo"), tables.sqls.toString());
    assertTrue(batches.isEmpty());
  }

  @Test
  void aRequestLevelRefusalTakesNoLock() throws Exception {
    NeoResponse mismatch = run("ORG-2",
        new JSONObject().put("system", "SII").put("territory", "navarra"));
    testModeStatic.when(() -> FiscalTestModeResolver.isForceTestModeActive(any()))
        .thenReturn(true);
    NeoResponse testMode = run("SII", "navarra");

    assertEquals(FiscalSystemConfigureAction.MSG_ORG_MISMATCH, messageKey(mismatch));
    assertEquals("ETGO_FiscalSifTestMode", messageKey(testMode));
    assertTrue(tables.sqls.isEmpty(), "no lock and no read: " + tables.sqls);
    assertTrue(batches.isEmpty());
  }

  @Test
  void forcedTestModeIsRefused() throws Exception {
    testModeStatic.when(() -> FiscalTestModeResolver.isForceTestModeActive(any()))
        .thenReturn(true);

    NeoResponse response = run("SII", "navarra");

    assertEquals(409, response.getHttpStatus());
    assertEquals("ETGO_FiscalSifTestMode", messageKey(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void aTerritoryThatDoesNotAllowTheSystemIsRefusedByName() throws Exception {
    NeoResponse response = run("SII", "ceuta");

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_NOT_ALLOWED, messageKey(response));
    String text = message(response);
    assertTrue(text.contains("Ceuta / Melilla (IPSI)"), text);
    assertTrue(text.contains("VERI*FACTU"), "says what is allowed there: " + text);
    assertTrue(batches.isEmpty());
  }

  @Test
  void siiInABasqueTerritoryWithoutTicketBaiIsNotAllowed() throws Exception {
    NeoResponse response = run("SII", "alava");

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_NOT_ALLOWED, messageKey(response));
  }

  @Test
  void anOrganizationWithAnActiveSifIsRefused() throws Exception {
    tables.active.put("etvfac_verifactu_config", "V");

    NeoResponse response = run("SII", "baleares");

    assertEquals(409, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_ALREADY_CONFIGURED, messageKey(response));
    assertTrue(message(response).contains("VERI*FACTU"), message(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void aMissingTerritoryIsRefusedOutsideAddingSii() throws Exception {
    NeoResponse response = run(ORG, new JSONObject().put("system", "SII"));

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_TERRITORY_REQUIRED, messageKey(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void noParametersAtAllIsRefused() {
    NeoResponse response = run(ORG, (JSONObject) null);

    assertEquals(422, response.getHttpStatus());
    assertTrue(batches.isEmpty());
  }

  @Test
  void anUnknownTerritoryIsRefused() throws Exception {
    NeoResponse response = run("SII", "madrid");

    assertEquals(422, response.getHttpStatus());
    assertTrue(batches.isEmpty());
  }

  @Test
  void anOrganizationTheRoleCannotReadIsNotFound() throws Exception {
    NeoResponse response = run("ORG-X",
        new JSONObject().put("system", "SII").put("territory", "navarra"));

    assertEquals(404, response.getHttpStatus());
  }

  @Test
  void onlyPostRuns() {
    NeoResponse response = action().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(FiscalSystemConfigureAction.ACTION)
        .httpMethod("GET")
        .recordId(ORG)
        .build());

    assertEquals(405, response.getHttpStatus());
  }

  @Test
  void anotherActionOrSurfaceIsNotServed() {
    assertNull(action().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).fieldName("certificate").httpMethod("POST")
        .build()));
    assertNull(action().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD).fieldName(FiscalSystemConfigureAction.ACTION)
        .httpMethod("POST").build()));
  }

  // ── what is created ──────────────────────────────────────────────────────

  @Test
  void verifactuInCanariasCreatesOneRecordWithTaxType03() throws Exception {
    NeoResponse response = run("VERIFACTU", "canarias");

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, batches.get(0).length());
    JSONObject op = operation(0);
    assertEquals("verifactu-config", op.getString("spec"));
    assertEquals("cabeceraDeConfiguraciónVerifactu", op.getString("entity"));
    assertEquals("03", op.getJSONObject("body").getString("tAXType"));

    JSONObject body = response.getBody();
    assertEquals("VERIFACTU", body.getString("system"));
    assertEquals("canarias", body.getString("territory"));
    JSONObject created = body.getJSONArray("created").getJSONObject(0);
    assertEquals("VERIFACTU", created.getString("system"));
    assertEquals("verifactu-config", created.getString("spec"));
    assertEquals("NEW-VERIFACTU", created.getString("id"));
  }

  @Test
  void siiByAnAgentIsAcogidoFromTheStartAndRunsAsTheAgentOnboarding() throws Exception {
    NeoResponse response = run("SII", "navarra");

    assertEquals(200, response.getHttpStatus());
    JSONObject op = operation(0);
    assertEquals("sii-config", op.getString("spec"));
    assertEquals("siiConfiguration", op.getString("entity"));
    JSONObject sii = op.getJSONObject("body");
    assertEquals("Y", sii.getString("acogidaAlSII"));
    assertEquals("Y", sii.getString("navarra"));
    assertEquals("IVA", sii.getString("taxtype"));
    assertEquals("2026-11-03", sii.getString("fechaAcogidaSII"));
    assertEquals(List.of(Boolean.TRUE), onboardingFlags);
    assertFalse(FiscalSifGuard.isFiscalSystemActivation(), "the flag never outlives the batch");
  }

  @Test
  void siiPlusTbaiCreatesSiiThenTicketBaiInOneBatch() throws Exception {
    NeoResponse response = run("SII+TBAI", "bizkaia");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, batches.get(0).length());
    assertEquals("SII", operation(0).getString("id"));
    assertEquals("Y", operation(0).getJSONObject("body").getString("acogidaAlSII"));
    assertEquals("TBAI", operation(1).getString("id"));
    assertEquals("tbai-config", operation(1).getString("spec"));
    assertEquals("header", operation(1).getString("entity"));
    assertEquals("BIZKAIA", operation(1).getJSONObject("body").getString("etsgSifTerritory"));
    assertEquals("SII+TBAI", response.getBody().getString("system"));
    assertEquals(2, response.getBody().getJSONArray("created").length());
  }

  @Test
  void addingSiiToTicketBaiInItsTerritoryAnswersSiiPlusTbai() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "GIPUZKOA";

    NeoResponse response = run("SII", "gipuzkoa");

    assertEquals(200, response.getHttpStatus());
    assertEquals("SII+TBAI", response.getBody().getString("system"));
    assertEquals(1, batches.get(0).length());
    JSONObject sii = operation(0).getJSONObject("body");
    assertEquals("SII", operation(0).getString("id"));
    assertEquals("Y", sii.getString("guipuzcoa"));
    assertEquals("Y", sii.getString("acogidaAlSII"));
  }

  @Test
  void addingSiiToTicketBaiOfAnotherTerritoryIsRefused() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "ARABA";

    NeoResponse response = run("SII", "bizkaia");

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_TERRITORY_MISMATCH, messageKey(response));
    assertTrue(message(response).contains("Álava"), message(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void addingSiiWithoutATerritoryTakesTheTicketBaiOne() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "GIPUZKOA";

    NeoResponse response = run(ORG, new JSONObject().put("system", "SII"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("SII+TBAI", response.getBody().getString("system"));
    assertEquals("gipuzkoa", response.getBody().getString("territory"));
    assertEquals(1, batches.get(0).length());
    assertEquals("SII", operation(0).getString("id"));
    JSONObject sii = operation(0).getJSONObject("body");
    assertEquals("Y", sii.getString("guipuzcoa"));
    assertEquals("IVA", sii.getString("taxtype"));
    assertEquals("Y", sii.getString("acogidaAlSII"));
    assertEquals(List.of(Boolean.TRUE), onboardingFlags);
  }

  @Test
  void addingSiiWithoutATerritoryToBizkaiaCarriesNoRegionalAgency() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "BIZKAIA";

    NeoResponse response = run(ORG, new JSONObject().put("system", "SII"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("bizkaia", response.getBody().getString("territory"));
    JSONObject sii = operation(0).getJSONObject("body");
    assertFalse(sii.has("guipuzcoa"));
    assertFalse(sii.has("navarra"));
  }

  @Test
  void addingSiiToTicketBaiWithANonBasqueTerritoryIsNotAddingSii() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "BIZKAIA";

    NeoResponse response = run("SII", "navarra");

    // Not "Añadir SII": a first activation, refused because a SIF is already active.
    assertEquals(409, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_ALREADY_CONFIGURED, messageKey(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void addingSiiToATicketBaiWithoutTerritoryIsAConflict() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = null;

    NeoResponse withoutTerritory = run(ORG, new JSONObject().put("system", "SII"));
    NeoResponse withTerritory = run("SII", "bizkaia");

    for (NeoResponse response : List.of(withoutTerritory, withTerritory)) {
      assertEquals(409, response.getHttpStatus());
      assertEquals(FiscalSystemConfigureAction.MSG_TBAI_NO_TERRITORY, messageKey(response));
    }
    assertTrue(batches.isEmpty());
  }

  @Test
  void addingSiiToATicketBaiWithAnUnknownTerritoryIsAConflict() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "MADRID";

    NeoResponse response = run(ORG, new JSONObject().put("system", "SII"));

    assertEquals(409, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_TBAI_NO_TERRITORY, messageKey(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void addingSiiWithTheTicketBaiTerritoryNamesItOnMismatch() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "GIPUZKOA";

    NeoResponse response = run("SII", "alava");

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_TERRITORY_MISMATCH, messageKey(response));
    assertTrue(message(response).contains("Guip\u00fazcoa"), message(response));
    assertTrue(batches.isEmpty());
  }

  @Test
  void aMissingTerritoryIsRequiredForEverySystemButAddingSii() throws Exception {
    for (String system : List.of("TBAI", "SII+TBAI", "VERIFACTU")) {
      NeoResponse response = run(ORG, new JSONObject().put("system", system));

      assertEquals(400, response.getHttpStatus(), system);
      assertEquals(FiscalSystemConfigureAction.MSG_TERRITORY_REQUIRED, messageKey(response),
          system);
    }
    assertTrue(batches.isEmpty());
  }

  @Test
  void aMissingTerritoryIsRequiredWhenTheActiveSifIsNotTicketBaiAlone() throws Exception {
    tables.active.put("etvfac_verifactu_config", "V");
    NeoResponse onVerifactu = run(ORG, new JSONObject().put("system", "SII"));

    tables.active.clear();
    tables.active.put("tbai_config", "T");
    tables.active.put("aeatsii_config", "S");
    tables.tbaiTerritory = "BIZKAIA";
    NeoResponse onSiiPlusTbai = run(ORG, new JSONObject().put("system", "SII"));

    for (NeoResponse response : List.of(onVerifactu, onSiiPlusTbai)) {
      assertEquals(400, response.getHttpStatus());
      assertEquals(FiscalSystemConfigureAction.MSG_TERRITORY_REQUIRED, messageKey(response));
    }
    assertTrue(batches.isEmpty());
  }

  // ── per-system window access ─────────────────────────────────────────────

  /** A role that may write only the windows of {@code writable}; records every spec asked. */
  private static Predicate<String> writableOnly(List<String> asked, String... writable) {
    List<String> allowed = List.of(writable);
    return spec -> {
      asked.add(spec);
      return allowed.contains(spec);
    };
  }

  private NeoResponse runWith(Predicate<String> canCreate, String system, String territory)
      throws Exception {
    return run(action(canCreate), ORG,
        new JSONObject().put("system", system).put("territory", territory));
  }

  @ParameterizedTest(name = "{0} in {1} needs {2}")
  @CsvSource({
      "SII, navarra, sii-config, SII",
      "TBAI, bizkaia, tbai-config, TicketBAI",
      "VERIFACTU, ceuta, verifactu-config, VERI*FACTU" })
  void aSystemWhoseWindowTheRoleCannotWriteIsForbidden(String system, String territory,
      String spec, String displayName) throws Exception {
    List<String> asked = new ArrayList<>();

    NeoResponse response = runWith(writableOnly(asked), system, territory);

    assertEquals(403, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_NO_WINDOW_ACCESS, messageKey(response));
    assertTrue(message(response).contains(displayName), message(response));
    assertEquals(List.of(spec), asked, "the predicate is asked for the system's own window");
    assertTrue(batches.isEmpty(), "nothing is sent to the batch");
  }

  @ParameterizedTest(name = "{0} in {1}")
  @CsvSource({ "SII, navarra, sii-config", "TBAI, bizkaia, tbai-config",
      "VERIFACTU, ceuta, verifactu-config" })
  void aSystemWhoseWindowTheRoleCanWriteIsActivated(String system, String territory,
      String spec) throws Exception {
    List<String> asked = new ArrayList<>();

    NeoResponse response = runWith(writableOnly(asked, spec), system, territory);

    assertEquals(200, response.getHttpStatus());
    assertEquals(List.of(spec), asked);
    assertEquals(1, batches.size());
  }

  @Test
  void siiPlusTbaiWithWriteAccessOnlyToTicketBaiIsRefusedNamingSii() throws Exception {
    List<String> asked = new ArrayList<>();

    NeoResponse response = runWith(writableOnly(asked, "tbai-config"), "SII+TBAI", "bizkaia");

    assertEquals(403, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_NO_WINDOW_ACCESS, messageKey(response));
    assertTrue(message(response).contains("SII"), message(response));
    assertFalse(message(response).contains("TicketBAI"), message(response));
    assertTrue(batches.isEmpty(), "no half activation: TicketBAI is not created alone");
  }

  @Test
  void siiPlusTbaiWithWriteAccessOnlyToSiiIsRefusedNamingTicketBai() throws Exception {
    List<String> asked = new ArrayList<>();

    NeoResponse response = runWith(writableOnly(asked, "sii-config"), "SII+TBAI", "gipuzkoa");

    assertEquals(403, response.getHttpStatus());
    assertTrue(message(response).contains("TicketBAI"), message(response));
    assertEquals(List.of("sii-config", "tbai-config"), asked,
        "every window is checked before anything is written");
    assertTrue(batches.isEmpty());
  }

  @Test
  void siiPlusTbaiWithWriteAccessToBothWindowsIsActivated() throws Exception {
    List<String> asked = new ArrayList<>();

    NeoResponse response = runWith(writableOnly(asked, "sii-config", "tbai-config"), "SII+TBAI",
        "alava");

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, batches.get(0).length());
  }

  @Test
  void addingSiiToTicketBaiChecksOnlyTheSiiWindow() throws Exception {
    tables.active.put("tbai_config", "T");
    tables.tbaiTerritory = "GIPUZKOA";
    List<String> asked = new ArrayList<>();

    NeoResponse response = runWith(writableOnly(asked, "tbai-config"), "SII", "gipuzkoa");

    assertEquals(403, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_NO_WINDOW_ACCESS, messageKey(response));
    assertEquals(List.of("sii-config"), asked);
    assertTrue(batches.isEmpty());
  }

  @Test
  void theWindowRefusalIsInSpanishAndNamesTheSystem() throws Exception {
    try (MockedStatic<NeoLanguage> language = mockStatic(NeoLanguage.class)) {
      language.when(NeoLanguage::currentCode).thenReturn("es_ES");

      NeoResponse response = runWith(writableOnly(new ArrayList<>(), "sii-config"), "SII+TBAI",
          "bizkaia");

      assertEquals(403, response.getHttpStatus());
      assertEquals("Tu rol no tiene permiso para configurar TicketBAI: no puede modificar su "
          + "ventana.", message(response));
      assertTrue(batches.isEmpty());
    }
  }

  @Test
  void theWindowCheckRunsAfterTheActivationRules() throws Exception {
    List<String> asked = new ArrayList<>();

    NeoResponse response = runWith(writableOnly(asked), "SII", "ceuta");

    assertEquals(400, response.getHttpStatus());
    assertEquals(FiscalSystemConfigureAction.MSG_NOT_ALLOWED, messageKey(response));
    assertTrue(asked.isEmpty(), "an invalid request is refused for what it is, not for access");
  }

  // ── certificate ──────────────────────────────────────────────────────────

  @Test
  void withACertificateThereIsNoWarning() throws Exception {
    NeoResponse response = run("VERIFACTU", "ceuta");

    JSONObject body = response.getBody();
    assertTrue(body.getJSONObject("certificate").getBoolean("exists"));
    assertFalse(body.has("messages"));
  }

  @Test
  void withoutACertificateTheAnswerWarnsToUploadIt() throws Exception {
    certificate = null;

    NeoResponse response = run("VERIFACTU", "ceuta");

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertFalse(body.getJSONObject("certificate").getBoolean("exists"));
    JSONObject warning = body.getJSONArray("messages").getJSONObject(0);
    assertEquals("warning", warning.getString("type"));
    assertTrue(warning.getString("text").contains("certificate"), warning.getString("text"));
  }

  // ── batch failure ────────────────────────────────────────────────────────

  @Test
  void aBatchRefusalKeepsItsStatusMessageAndKeys() throws Exception {
    batchAnswer = ops -> json("{\"committed\":false,\"error\":{\"status\":409,"
        + "\"message\":\"already there\",\"messageKeys\":[\"ETGO_FiscalSifOneActive\"]}}");

    NeoResponse response = run("VERIFACTU", "baleares");

    assertEquals(409, response.getHttpStatus());
    assertEquals("already there", message(response));
    assertEquals("ETGO_FiscalSifOneActive", messageKey(response));
  }

  @Test
  void aBatchRefusalNestedInTheOperationDetailKeepsItsKeys() throws Exception {
    batchAnswer = ops -> json("{\"committed\":false,\"error\":{\"status\":400,"
        + "\"message\":\"op failed\",\"detail\":{\"error\":{\"messageKeys\":[\"K1\"]}}}}");

    NeoResponse response = run("VERIFACTU", "baleares");

    assertEquals(400, response.getHttpStatus());
    assertEquals("K1", messageKey(response));
  }

  @Test
  void aBatchWithNoAnswerIsAServerError() throws Exception {
    batchAnswer = ops -> null;

    NeoResponse response = run("VERIFACTU", "baleares");

    assertEquals(500, response.getHttpStatus());
    assertFalse(response.getBody().getJSONObject("error").has("messageKeys"));
  }

  @Test
  void anUncommittedBatchWithoutErrorDetailIsABadRequest() throws Exception {
    batchAnswer = ops -> json("{\"committed\":false,\"error\":{}}");

    NeoResponse response = run("VERIFACTU", "baleares");

    assertEquals(400, response.getHttpStatus());
    assertEquals("Could not configure the fiscal system.", message(response));
  }

  // ── contract ─────────────────────────────────────────────────────────────

  @Test
  void theActionIsDeclaredWithItsName() {
    assertTrue(FiscalSystemConfigureAction.actionContracts()
        .containsKey(FiscalSystemConfigureAction.ACTION));
    assertTrue(FiscalSystemConfigureAction.serves("configureFiscalSystem"));
    assertFalse(FiscalSystemConfigureAction.serves("certificate"));
  }

  // ── fiscalSystemCatalog ─────────────────────────────────────────────────

  private NeoResponse catalog(String method, String orgId) {
    return action().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(FiscalSystemConfigureAction.CATALOG_ACTION)
        .httpMethod(method)
        .recordId(orgId)
        .obContext(obContext)
        .build());
  }

  private static JSONObject territory(JSONObject catalog, String id) throws Exception {
    JSONArray territories = catalog.getJSONArray("territories");
    for (int i = 0; i < territories.length(); i++) {
      if (id.equals(territories.getJSONObject(i).getString("territory"))) {
        return territories.getJSONObject(i);
      }
    }
    throw new AssertionError("no territory " + id + " in " + catalog);
  }

  @Test
  void theCatalogAnswersGetOnly() throws Exception {
    NeoResponse get = catalog("GET", ORG);

    assertEquals(200, get.getHttpStatus());
    assertEquals(405, catalog("post", ORG).getHttpStatus(), "read only, as its contract (GET)");
    assertTrue(batches.isEmpty(), "a read never runs the batch");
  }

  @Test
  void theCatalogRefusesAnyOtherMethod() {
    for (String method : List.of("POST", "post", "PUT", "PATCH", "DELETE")) {
      assertEquals(405, catalog(method, ORG).getHttpStatus(), method);
    }
    assertEquals(405, catalog(null, ORG).getHttpStatus());
    assertEquals(200, catalog("get", ORG).getHttpStatus(), "GET in any case");
    assertTrue(batches.isEmpty(), "a refused read never runs the batch");
  }

  @Test
  void theCatalogOfAnUnreadableOrganizationIsNotFound() {
    assertEquals(404, catalog("GET", "ORG-X").getHttpStatus());
    assertEquals(404, catalog("GET", null).getHttpStatus());
  }

  @Test
  void theCatalogOfAnotherReadableOrganizationIsTheSame() throws Exception {
    assertEquals(catalog("GET", ORG).getBody().toString(),
        catalog("GET", "ORG-2").getBody().toString());
  }

  @Test
  void theCatalogIsTheRulesAsData() throws Exception {
    JSONObject body = catalog("GET", ORG).getBody();

    JSONArray systems = body.getJSONArray("systems");
    assertEquals(FiscalSifRules.SYSTEMS.size(), systems.length());
    for (int i = 0; i < systems.length(); i++) {
      assertEquals(FiscalSifRules.SYSTEMS.get(i), systems.getString(i));
    }
    JSONArray territories = body.getJSONArray("territories");
    assertEquals(FiscalSifRules.TERRITORIES.size(), territories.length());
    for (int i = 0; i < territories.length(); i++) {
      JSONObject t = territories.getJSONObject(i);
      String id = t.getString("territory");
      assertEquals(FiscalSifRules.TERRITORIES.get(i), id, "presentation order");
      List<String> allowed = new ArrayList<>();
      JSONArray tSystems = t.getJSONArray("systems");
      for (int j = 0; j < tSystems.length(); j++) {
        allowed.add(tSystems.getString(j));
      }
      assertEquals(FiscalSifRules.allowedSystems(id), allowed, id);
      boolean siiApplies = allowed.contains("SII") || allowed.contains("SII+TBAI");
      assertEquals(!siiApplies, t.isNull("sii"), id + ": sii only where SII applies");
      String tbai = FiscalSifRules.tbaiTerritory(id);
      assertEquals(tbai == null, t.isNull("tbaiTerritory"), id);
      if (tbai != null) {
        assertEquals(tbai, t.getString("tbaiTerritory"), id);
      }
      assertEquals(!allowed.contains("VERIFACTU"), t.isNull("verifactuTaxType"), id);
    }
  }

  @Test
  void theCatalogCarriesTheSiiFlagsOfEachTerritory() throws Exception {
    JSONObject body = catalog("GET", ORG).getBody();

    JSONObject navarra = territory(body, "navarra").getJSONObject("sii");
    assertTrue(navarra.getBoolean("navarra"));
    assertFalse(navarra.getBoolean("guipuzcoa"));
    assertEquals("IVA", navarra.getString("taxtype"));

    JSONObject gipuzkoa = territory(body, "gipuzkoa").getJSONObject("sii");
    assertFalse(gipuzkoa.getBoolean("navarra"));
    assertTrue(gipuzkoa.getBoolean("guipuzcoa"));
    assertEquals("IVA", gipuzkoa.getString("taxtype"));

    JSONObject alava = territory(body, "alava").getJSONObject("sii");
    assertFalse(alava.getBoolean("navarra"));
    assertFalse(alava.getBoolean("guipuzcoa"));

    assertEquals("IGIC", territory(body, "canarias").getJSONObject("sii").getString("taxtype"));
    assertEquals("IVA", territory(body, "baleares").getJSONObject("sii").getString("taxtype"));
    assertTrue(territory(body, "ceuta").isNull("sii"), "SII cannot represent an IPSI taxpayer");
  }

  @Test
  void theCatalogCarriesTheTicketBaiTerritoryAndTheVerifactuTaxType() throws Exception {
    JSONObject body = catalog("GET", ORG).getBody();

    assertEquals("ARABA", territory(body, "alava").getString("tbaiTerritory"));
    assertEquals("BIZKAIA", territory(body, "bizkaia").getString("tbaiTerritory"));
    assertEquals("GIPUZKOA", territory(body, "gipuzkoa").getString("tbaiTerritory"));
    assertTrue(territory(body, "navarra").isNull("tbaiTerritory"));

    assertEquals("01", territory(body, "baleares").getString("verifactuTaxType"));
    assertEquals("03", territory(body, "canarias").getString("verifactuTaxType"));
    assertEquals("02", territory(body, "ceuta").getString("verifactuTaxType"));
    assertTrue(territory(body, "navarra").isNull("verifactuTaxType"));
    assertTrue(territory(body, "bizkaia").isNull("verifactuTaxType"));
  }

  @Test
  void theCatalogIsDeclaredAsARead() {
    assertTrue(FiscalSystemConfigureAction.serves(FiscalSystemConfigureAction.CATALOG_ACTION));
    assertTrue(FiscalSystemConfigureAction.actionContracts()
        .containsKey(FiscalSystemConfigureAction.CATALOG_ACTION));
  }

  private static JSONObject json(String text) {
    try {
      return new JSONObject(text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
