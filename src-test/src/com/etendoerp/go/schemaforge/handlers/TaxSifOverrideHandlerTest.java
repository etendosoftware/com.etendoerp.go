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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link TaxSifOverrideHandler} (ETP-5122).
 *
 * <p>Covers the write-side redirect (PUT/PATCH on {@code tax} strips the 8 SIF value fields
 * out of the request body and upserts them into {@code etsg_tax_sif_config} instead of letting
 * them reach {@code c_tax}) and the read-side overlay ({@code afterHandle} rewrites the 8
 * properties on a GET response — single record or list — with the D5 effective value).
 *
 * <p>{@code OBDal}/Hibernate {@code Session}/{@code NativeQuery} are mocked; no real DB access.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaxSifOverrideHandlerTest {

  private static final String TAX_ID = "TAX_001";
  private static final String CURRENT_ORG_ID = "ORG_CHILD_001";
  private static final String LEGAL_ENTITY_ORG_ID = "ORG_LE_001";
  private static final String CLIENT_ID = "CLIENT_001";
  private static final String USER_ID = "USER_001";

  private TaxSifOverrideHandler handler;

  @Mock private OBDal mockOBDal;
  @Mock private Session mockSession;
  @Mock private OBContext obContext;
  @Mock private Organization organization;
  @Mock private Client client;
  @Mock private User user;

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;

  @BeforeEach
  void setUp() {
    handler = new TaxSifOverrideHandler();

    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(mockOBDal);
    lenient().when(mockOBDal.getSession()).thenReturn(mockSession);

    obContextStatic = mockStatic(OBContext.class);

    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getUser()).thenReturn(user);
    when(organization.getId()).thenReturn(CURRENT_ORG_ID);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(user.getId()).thenReturn(USER_ID);

    // ad_get_org_le_bu(...) lookup — used by both the write-side upsert and the
    // read-side effective-value query.
    NativeQuery<Object> leQuery = mock(NativeQuery.class);
    lenient().when(mockSession.createNativeQuery(
        argThat(sql -> sql != null && sql.contains("ad_get_org_le_bu"))))
        .thenReturn((NativeQuery) leQuery);
    lenient().when(leQuery.setParameter(anyString(), any())).thenReturn(leQuery);
    lenient().when(leQuery.uniqueResult()).thenReturn(LEGAL_ENTITY_ORG_ID);

    // Default effective-values stub (empty result): buildOverrideOnlyResponse now queries
    // effective values for EVERY SIF-only PATCH, not just GET. Tests that care about the
    // overlaid content override this with their own stubEffectiveValuesQuery(...) call.
    stubEffectiveValuesQuery();
  }

  @AfterEach
  void tearDown() {
    obDalStatic.close();
    obContextStatic.close();
  }

  private NeoContext.Builder patchContext(JSONObject body) {
    return NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .recordId(TAX_ID).requestBody(body).obContext(obContext);
  }

  private NativeQuery<?> stubUpsertQuery() {
    NativeQuery<?> upsertQuery = mock(NativeQuery.class);
    lenient().when(mockSession.createNativeQuery(
        argThat(sql -> sql != null && sql.startsWith("INSERT INTO etsg_tax_sif_config"))))
        .thenReturn((NativeQuery) upsertQuery);
    lenient().when(((NativeQuery) upsertQuery).setParameter(anyString(), any()))
        .thenReturn(upsertQuery);
    lenient().when(((NativeQuery) upsertQuery).setParameter(anyString(), isNull()))
        .thenReturn(upsertQuery);
    return upsertQuery;
  }

  // ── handle() — PATCH with SIF-only fields ───────────────────────────────────

  /**
   * A PATCH body containing ONLY SIF fields must be entirely redirected: the upsert into
   * {@code etsg_tax_sif_config} must run, the fields must be stripped from the body so the
   * default CRUD never sees them (i.e. {@code c_tax} is never written), and the handler must
   * short-circuit the default CRUD by returning its own override-only response.
   */
  @Test
  void handlePatchWithOnlySifFieldsUpsertsOverrideAndSkipsDefaultCrud() throws Exception {
    NativeQuery<?> upsertQuery = stubUpsertQuery();
    // The write just landed, so the read-back effective-value query must see it: the same
    // (c_tax NULL, override "09") precedence queryEffectiveValues/afterHandle always applies.
    stubEffectiveValuesQuery(row(TAX_ID, "09", null, null, null, null, null, null, null));
    JSONObject body = new JSONObject().put("etvfacVatRegime", "09");

    NeoResponse result = handler.handle(patchContext(body).build());

    verify(mockSession).createNativeQuery(
        argThat(sql -> sql.startsWith("INSERT INTO etsg_tax_sif_config")
            && sql.contains("em_etvfac_vat_regime")));
    verify((NativeQuery) upsertQuery).setParameter("em_etvfac_vat_regime", "09");
    verify((NativeQuery) upsertQuery).executeUpdate();

    // The field was stripped — c_tax's default CRUD never receives it.
    assertFalse(body.has("etvfacVatRegime"));

    // Short-circuits: does NOT return null (null would mean "let default CRUD handle it").
    assertTrue(result != null);
    JSONObject row = result.getBody().getJSONObject(JsonConstants.RESPONSE_RESPONSE)
        .getJSONArray(JsonConstants.RESPONSE_DATA).getJSONObject(0);
    assertEquals(TAX_ID, row.getString("id"));
    assertEquals("09", row.getString("etvfacVatRegime"));
  }

  /**
   * ETP-5122 follow-up: a PATCH that only touches ONE of the 8 SIF fields (the common case —
   * a user edits a single dropdown in the header form and saves) must still answer with the
   * EFFECTIVE value of ALL 8 SIF columns, not just the one submitted in this request. Before
   * this fix, {@code buildOverrideOnlyResponse} echoed back only the fields present in {@code
   * appliedValues}, so the frontend's post-save merge (which overlays {@code response.data[0]}
   * onto the in-memory record) left the other 7 SIF fields untouched — correct for THOSE 7
   * (unchanged), but meant a caller relying on this response alone (rather than a follow-up
   * GET) never saw the effective values it did not itself just write.
   *
   * <p>Response shape must also match {@code response.data} as a one-element {@link JSONArray}
   * — the same shape the default CRUD's PATCH response uses and every frontend save path
   * already unwraps via {@code data?.response?.data?.[0]}.
   */
  @Test
  void handlePatchWithSingleSifFieldReturnsEffectiveValueOfAllEightFields() throws Exception {
    stubUpsertQuery();
    // Effective values after the write: tbaiClaveregimeniva = "04" (just written), the other
    // 7 columns carry whatever c_tax/override already had — here, a pre-existing override on
    // etvfacIGICRegime ("03") plus everything else blank, to prove the overlay is NOT limited
    // to the single field this request touched.
    stubEffectiveValuesQuery(row(TAX_ID, null, null, "03", null, null, "04", null, null));
    JSONObject body = new JSONObject().put("tbaiClaveregimeniva", "04");

    NeoResponse result = handler.handle(patchContext(body).build());

    assertTrue(result != null);
    JSONObject responseObj = result.getBody().getJSONObject(JsonConstants.RESPONSE_RESPONSE);
    // Same envelope shape as the default CRUD's single-record PATCH response: `data` is an
    // ARRAY with exactly one element (SmartClient DataSource protocol), never a bare object.
    JSONArray dataArray = responseObj.getJSONArray(JsonConstants.RESPONSE_DATA);
    assertEquals(1, dataArray.length());
    JSONObject row = dataArray.getJSONObject(0);

    assertEquals(TAX_ID, row.getString("id"));
    // The field actually submitted in this request.
    assertEquals("04", row.getString("tbaiClaveregimeniva"));
    // The OTHER 7 SIF fields must also be present with their effective value — not silently
    // omitted because this particular request didn't touch them.
    assertEquals("03", row.getString("etvfacIGICRegime"));
    assertTrue(row.isNull("etvfacVatRegime"));
    assertTrue(row.isNull("etvfacIPSIRegime"));
    assertTrue(row.isNull("etvfacExemptionCause"));
    assertTrue(row.isNull("etvfacCauseNotTaxable"));
    assertTrue(row.isNull("tbaiNonsubjectcause"));
    assertTrue(row.isNull("tBAICausaDeExencion"));
  }

  /**
   * Admin mode must be set before the upsert and restored afterwards, regardless of the
   * caller's own role/org access on {@code etsg_tax_sif_config}.
   */
  @Test
  void handlePatchWithSifFieldsSetsAndRestoresAdminMode() throws Exception {
    stubUpsertQuery();
    JSONObject body = new JSONObject().put("etvfacVatRegime", "09");

    handler.handle(patchContext(body).build());

    obContextStatic.verify(() -> OBContext.setAdminMode(true));
    obContextStatic.verify(OBContext::restorePreviousMode);
  }

  // ── handle() — PATCH with mixed fields ──────────────────────────────────────

  /**
   * A PATCH body mixing a normal {@code c_tax} field with a SIF field must strip only the SIF
   * field (which is upserted into the override table) and let the remaining body — still
   * carrying the normal field — flow through to the default CRUD by returning {@code null}.
   */
  @Test
  void handlePatchWithMixedFieldsStripsOnlySifFieldAndFallsThroughForTheRest() throws Exception {
    NativeQuery<?> upsertQuery = stubUpsertQuery();
    JSONObject body = new JSONObject()
        .put("name", "IVA 21% Ventas")
        .put("etvfacExemptionCause", "E2");

    NeoResponse result = handler.handle(patchContext(body).build());

    verify((NativeQuery) upsertQuery).setParameter("em_etvfac_exemption_cause", "E2");
    assertFalse(body.has("etvfacExemptionCause"));
    assertTrue(body.has("name"));
    assertEquals("IVA 21% Ventas", body.getString("name"));

    // null tells the caller "let the default CRUD process the (now SIF-free) remaining body".
    assertNull(result);
  }

  // ── handle() — PATCH clearing a SIF field ───────────────────────────────────

  /**
   * A PATCH that clears a SIF field by sending an empty string must upsert a {@code null} for
   * that column (blank is trimmed to {@code null} before being bound), not the literal empty
   * string — otherwise {@code TaxSifConfigResolver}'s own blank-trimming on read would never
   * even need to run, but an empty string stored raw would still count as "present" for other
   * SQL that does not trim.
   */
  @Test
  void handlePatchClearingSifFieldWithEmptyStringUpsertsNull() throws Exception {
    NativeQuery<?> upsertQuery = stubUpsertQuery();
    JSONObject body = new JSONObject().put("etvfacVatRegime", "");

    handler.handle(patchContext(body).build());

    verify((NativeQuery) upsertQuery).setParameter(eq("em_etvfac_vat_regime"), isNull());
  }

  /**
   * A PATCH that clears a SIF field by sending JSON {@code null} must also upsert a {@code
   * null} for that column.
   */
  @Test
  void handlePatchClearingSifFieldWithJsonNullUpsertsNull() throws Exception {
    NativeQuery<?> upsertQuery = stubUpsertQuery();
    JSONObject body = new JSONObject().put("etvfacVatRegime", JSONObject.NULL);

    handler.handle(patchContext(body).build());

    verify((NativeQuery) upsertQuery).setParameter(eq("em_etvfac_vat_regime"), isNull());
  }

  // ── handle() — no SIF fields at all ─────────────────────────────────────────

  /**
   * A PATCH body with no SIF fields must never touch the override table and must fall through
   * (return {@code null}) so the default CRUD processes the body unchanged.
   */
  @Test
  void handlePatchWithNoSifFieldsNeverTouchesOverrideTableAndFallsThrough() throws Exception {
    JSONObject body = new JSONObject().put("name", "IVA 21% Ventas");

    NeoResponse result = handler.handle(patchContext(body).build());

    assertNull(result);
    verify(mockSession, never()).createNativeQuery(
        argThat(sql -> sql != null && sql.startsWith("INSERT INTO etsg_tax_sif_config")));
  }

  // ── handle() — legal entity organization cannot be resolved ────────────────

  /**
   * When {@code AD_GET_ORG_LE_BU} returns no legal entity organization (null/blank), the
   * write MUST fail fast with a clear error instead of silently falling back to the raw
   * current organization. Every read path filters strictly by the legal entity org, so a
   * row saved under the raw org would be orphaned — invisible to all readers. No row may be
   * persisted into {@code etsg_tax_sif_config} in this case.
   */
  @Test
  void handlePatchFailsFastWhenLegalEntityOrgCannotBeResolved() throws Exception {
    NativeQuery<Object> leQuery = mock(NativeQuery.class);
    lenient().when(mockSession.createNativeQuery(
        argThat(sql -> sql != null && sql.contains("ad_get_org_le_bu"))))
        .thenReturn((NativeQuery) leQuery);
    lenient().when(leQuery.setParameter(anyString(), any())).thenReturn(leQuery);
    lenient().when(leQuery.uniqueResult()).thenReturn(null);

    JSONObject body = new JSONObject().put("etvfacVatRegime", "09");

    NeoResponse result = handler.handle(patchContext(body).build());

    assertTrue(result != null);
    assertEquals(500, result.getHttpStatus());
    String message = result.getBody().getJSONObject("error").getString("message");
    assertTrue(message.contains("legal entity"),
        "Expected error message to explain the legal entity resolution failure, was: " + message);

    // No row must ever be persisted into etsg_tax_sif_config for an unresolved legal entity.
    verify(mockSession, never()).createNativeQuery(
        argThat(sql -> sql != null && sql.startsWith("INSERT INTO etsg_tax_sif_config")));
  }

  // ── afterHandle() — GET single record ───────────────────────────────────────

  /**
   * A GET-by-id response must have its SIF properties overwritten with the effective value
   * resolved from {@code queryEffectiveValues} (D5 precedence — COALESCE against the override).
   */
  @Test
  void afterHandleGetSingleRecordOverlaysEffectiveValue() throws Exception {
    NativeQuery<Object> effectiveQuery = stubEffectiveValuesQuery(
        row("TAX_001", "09", null, null, null, null, null, null, null));

    JSONObject data = singleRowResponse("TAX_001");
    NeoContext ctx = NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .obContext(obContext)
        .previousResult(new NeoResponse(200, data))
        .build();

    NeoResponse result = handler.afterHandle(ctx);

    assertNull(result); // mutates in place, keeps using the previous result
    JSONObject row = data.getJSONObject(JsonConstants.RESPONSE_RESPONSE)
        .getJSONArray(JsonConstants.RESPONSE_DATA).getJSONObject(0);
    assertEquals("09", row.getString("etvfacVatRegime"));
    verify(effectiveQuery).setParameterList("taxIds", List.of("TAX_001"));
  }

  // ── afterHandle() — GET list ─────────────────────────────────────────────────

  /**
   * A GET-list response must overlay the effective value on EVERY row, matched by id.
   */
  @Test
  void afterHandleGetListOverlaysEffectiveValueOnEveryRow() throws Exception {
    stubEffectiveValuesQuery(
        row("TAX_001", "09", null, null, null, null, null, null, null),
        row("TAX_002", null, "E2", null, null, null, null, null, null));

    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", "TAX_001"))
        .put(new JSONObject().put("id", "TAX_002"));
    JSONObject inner = new JSONObject()
        .put(JsonConstants.RESPONSE_STATUS, 0)
        .put(JsonConstants.RESPONSE_DATA, rows);
    JSONObject data = new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, inner);

    NeoContext ctx = NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .obContext(obContext)
        .previousResult(new NeoResponse(200, data))
        .build();

    handler.afterHandle(ctx);

    assertEquals("09", rows.getJSONObject(0).getString("etvfacVatRegime"));
    assertEquals("E2", rows.getJSONObject(1).getString("etvfacExemptionCause"));
  }

  // ── afterHandle() — PATCH / PUT write responses ─────────────────────────────

  /**
   * ETP-5122 real-world regression: the user picks a value in a SIF dropdown AND the save
   * payload also carries a plain {@code c_tax} field, so {@link TaxSifOverrideHandler#handle}
   * strips the SIF field, upserts it, and returns {@code null} to let the default CRUD write
   * the rest. That default response echoes back {@code c_tax}'s OWN SIF columns, which are
   * blank by design (the value only ever lives in {@code etsg_tax_sif_config}) — and the
   * frontend merges {@code response.data[0]} onto its in-memory record, blanking the dropdown
   * the user just filled in. {@code afterHandle} must therefore overlay the effective values on
   * write responses too, not only on GET.
   */
  @Test
  void afterHandlePatchOverlaysEffectiveValueOnDefaultCrudResponse() throws Exception {
    stubEffectiveValuesQuery(row(TAX_ID, null, null, null, null, null, "04", null, null));

    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("id", TAX_ID)
        .put("name", "Entregas IVA 21%")
        // What the default CRUD echoes back: c_tax's own (always blank) SIF column.
        .put("tbaiClaveregimeniva", JSONObject.NULL));
    JSONObject inner = new JSONObject()
        .put(JsonConstants.RESPONSE_STATUS, 0)
        .put(JsonConstants.RESPONSE_DATA, rows);
    JSONObject data = new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, inner);

    NeoContext ctx = NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .recordId(TAX_ID).obContext(obContext)
        .previousResult(new NeoResponse(200, data))
        .build();

    assertNull(handler.afterHandle(ctx));
    assertEquals("04", rows.getJSONObject(0).getString("tbaiClaveregimeniva"));
    // Untouched non-SIF fields must survive the overlay.
    assertEquals("Entregas IVA 21%", rows.getJSONObject(0).getString("name"));
  }

  /** PUT is covered by the same overlay as PATCH. */
  @Test
  void afterHandlePutOverlaysEffectiveValue() throws Exception {
    stubEffectiveValuesQuery(row(TAX_ID, "09", null, null, null, null, null, null, null));

    JSONObject data = singleRowResponse(TAX_ID);
    NeoContext ctx = NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .recordId(TAX_ID).obContext(obContext)
        .previousResult(new NeoResponse(200, data))
        .build();

    handler.afterHandle(ctx);

    assertEquals("09", data.getJSONObject(JsonConstants.RESPONSE_RESPONSE)
        .getJSONArray(JsonConstants.RESPONSE_DATA).getJSONObject(0)
        .getString("etvfacVatRegime"));
  }

  /**
   * A SIF field with NO effective value (neither {@code c_tax} nor the override carries one)
   * must be overlaid as an EXPLICIT JSON null, not silently omitted — otherwise a user who
   * CLEARS a dropdown gets a response that says nothing about that field, and the frontend's
   * merge keeps showing the stale pre-clear value.
   */
  @Test
  void afterHandleOverlaysExplicitNullWhenNoEffectiveValue() throws Exception {
    stubEffectiveValuesQuery(row(TAX_ID, null, null, null, null, null, null, null, null));

    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("id", TAX_ID)
        .put("tbaiClaveregimeniva", "04"));
    JSONObject inner = new JSONObject()
        .put(JsonConstants.RESPONSE_STATUS, 0)
        .put(JsonConstants.RESPONSE_DATA, rows);
    JSONObject data = new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, inner);

    NeoContext ctx = NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .obContext(obContext)
        .previousResult(new NeoResponse(200, data))
        .build();

    handler.afterHandle(ctx);

    JSONObject overlaid = rows.getJSONObject(0);
    assertTrue(overlaid.has("tbaiClaveregimeniva"));
    assertTrue(overlaid.isNull("tbaiClaveregimeniva"));
  }

  // ── Effective-values SQL shape ───────────────────────────────────────────────

  /**
   * ETP-5122 root cause, as a regression test: the effective-value SELECT builds one {@code
   * COALESCE(...)} per SIF column, and Hibernate's native-query auto-discovery derives each
   * result alias FROM THE EXPRESSION. Eight unaliased {@code COALESCE(...)} expressions all
   * collapse to the alias {@code coalesce}, and the query blows up at runtime with {@code
   * NonUniqueDiscoveredSqlAliasException: Encountered a duplicated sql alias [coalesce]} —
   * which {@code afterHandle}'s catch-all swallows, leaving every SIF dropdown blank in the UI
   * while the stored override is perfectly correct. Each computed column must therefore carry
   * an explicit, unique {@code AS <alias>}.
   */
  @Test
  void effectiveValuesSqlAliasesEveryComputedColumnUniquely() throws Exception {
    stubEffectiveValuesQuery(row(TAX_ID, "09", null, null, null, null, null, null, null));

    NeoContext ctx = NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .obContext(obContext)
        .previousResult(new NeoResponse(200, singleRowResponse(TAX_ID)))
        .build();
    handler.afterHandle(ctx);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockSession, org.mockito.Mockito.atLeastOnce())
        .createNativeQuery(sqlCaptor.capture());
    String effectiveSql = sqlCaptor.getAllValues().stream()
        .filter(sql -> sql != null && sql.startsWith("SELECT t.c_tax_id"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("effective-values SELECT was never built"));

    String selectClause = effectiveSql.substring(0, effectiveSql.indexOf(" FROM c_tax t"));
    long coalesceCount = countOccurrences(selectClause, "COALESCE(");
    assertEquals(8, coalesceCount, "expected one COALESCE per SIF column");

    Set<String> aliases = new HashSet<>();
    for (String column : Arrays.asList("em_etvfac_vat_regime", "em_etvfac_igic_regime",
        "em_etvfac_ipsi_regime", "em_etvfac_exemption_cause", "em_etvfac_cause_not_taxable",
        "em_tbai_claveregimeniva", "em_tbai_nonsubjectcause", "em_tbai_exemptioncause")) {
      assertTrue(selectClause.contains(") AS " + column),
          "computed column " + column + " must be explicitly aliased, SQL was: " + selectClause);
      assertTrue(aliases.add(column), "duplicated alias " + column);
    }
    // The alias count must match the COALESCE count: no expression left auto-aliased.
    assertEquals(coalesceCount, countOccurrences(selectClause, ") AS "));
  }

  private static long countOccurrences(String haystack, String needle) {
    long count = 0;
    int idx = haystack.indexOf(needle);
    while (idx >= 0) {
      count++;
      idx = haystack.indexOf(needle, idx + needle.length());
    }
    return count;
  }

  /**
   * A non-{@code tax} entity must never invoke {@code afterHandle}'s enrichment logic, whatever
   * the HTTP method.
   */
  @Test
  void afterHandleSkipsNonTaxEntity() throws Exception {
    JSONObject data = singleRowResponse("TAX_001");
    NeoContext ctx = NeoContext.builder()
        .specName("product").entityName("product")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .obContext(obContext)
        .previousResult(new NeoResponse(200, data))
        .build();

    assertNull(handler.afterHandle(ctx));
    verify(mockSession, never()).createNativeQuery(anyString());
  }

  /**
   * DELETE (and POST — a create has no {@code c_tax_id} to key the override on yet) must not
   * trigger the overlay: the write methods {@code afterHandle} covers are exactly PUT and
   * PATCH, alongside GET.
   */
  @Test
  void afterHandleSkipsDeleteMethod() throws Exception {
    JSONObject data = singleRowResponse(TAX_ID);
    NeoContext ctx = NeoContext.builder()
        .specName("tax").entityName("tax")
        .httpMethod("DELETE").endpointType(NeoEndpointType.CRUD)
        .recordId(TAX_ID).obContext(obContext)
        .previousResult(new NeoResponse(200, data))
        .build();

    assertNull(handler.afterHandle(ctx));
    verify(mockSession, never()).createNativeQuery(anyString());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private JSONObject singleRowResponse(String taxId) throws Exception {
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", taxId));
    JSONObject inner = new JSONObject()
        .put(JsonConstants.RESPONSE_STATUS, 0)
        .put(JsonConstants.RESPONSE_DATA, rows);
    return new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, inner);
  }

  /** Builds a raw {@code Object[]} row matching queryEffectiveValues' column order. */
  private static Object[] row(String taxId, String vatRegime, String exemptionCause,
      String igicRegime, String ipsiRegime, String causeNotTaxable, String claveRegimenIva,
      String nonSubjectCause, String tbaiExemptionCause) {
    // Column order mirrors SIF_FIELD_TO_COLUMN's LinkedHashMap insertion order:
    // etvfacVatRegime, etvfacIGICRegime, etvfacIPSIRegime, etvfacExemptionCause,
    // etvfacCauseNotTaxable, tbaiClaveregimeniva, tbaiNonsubjectcause, tBAICausaDeExencion.
    return new Object[] { taxId, vatRegime, igicRegime, ipsiRegime, exemptionCause,
        causeNotTaxable, claveRegimenIva, nonSubjectCause, tbaiExemptionCause };
  }

  @SuppressWarnings("unchecked")
  private NativeQuery<Object> stubEffectiveValuesQuery(Object[]... rows) {
    NativeQuery<Object> query = mock(NativeQuery.class);
    lenient().when(mockSession.createNativeQuery(
        argThat(sql -> sql != null && sql.startsWith("SELECT t.c_tax_id"))))
        .thenReturn((NativeQuery) query);
    lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    lenient().when(query.setParameterList(anyString(), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(query);
    lenient().when(query.list()).thenReturn((List) Arrays.asList(rows));
    return query;
  }
}
