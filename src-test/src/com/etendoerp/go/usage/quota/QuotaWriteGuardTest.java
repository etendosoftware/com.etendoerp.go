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

package com.etendoerp.go.usage.quota;

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.NOW;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.OTHER_CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.quota;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.tenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoQuotaWriteGate;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.usage.UsageQueryComposer;
import com.etendoerp.go.usage.quota.QuotaTestFixtures.MutableClock;

/**
 * The NEO write check: which created rows can be refused, and when.
 *
 * <p>The request's client's entry in the {@link QuotaClientCache} decides which entities are
 * checked at all, the matcher whether a created row is the kind of row the resource counts, and the
 * evaluator whether the tenant is already at its {@code block} quota. The
 * {@link CatalogAddedResource} block wires the real cache and evaluator together on a resource no
 * production code names, to show a catalog row is all it takes.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaWriteGuard
 * @covers com.etendoerp.go.usage.quota.CreatedRowMatcher
 */
@SuppressWarnings("java:S2187")
class QuotaWriteGuardTest {

  private static final QuotaDefinition INVOICES = quota("DOCS", "block", 10L, null)
      .hqlRestriction("e.salesTransaction = true").build();

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final AtomicReference<String> requestClient = new AtomicReference<>(CLIENT);
  private CreatedRowMatcher matcher;
  private QuotaLookup lookup;
  private ConsumptionReader reader;
  private QuotaWriteGuard guard;

  @BeforeEach
  void setUp() {
    matcher = mock(CreatedRowMatcher.class);
    lookup = mock(QuotaLookup.class);
    reader = mock(ConsumptionReader.class);
    when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, INVOICES));
    when(lookup.forTenant(OTHER_CLIENT)).thenReturn(TenantQuotas.none(OTHER_CLIENT));
    guard = new QuotaWriteGuard(new QuotaClientCache(lookup, now::get), matcher,
        new QuotaEvaluator(lookup, reader, new MutableClock(NOW)), requestClient::get);
  }

  private static boolean applies(QuotaWriteGuard g, String entity) {
    return g.entryIfChecked(entity).isPresent();
  }

  /** What the gate does: the entry read before the insert, checked after it. */
  private static Optional<QuotaEvaluation> check(QuotaWriteGuard g, String entity,
      List<String> ids) {
    return g.refusalFor(g.entryIfChecked(entity).orElse(null), entity, ids);
  }

  private void preCreate(QuotaDefinition quota, long consumed) {
    when(reader.readBeforeCreate(eq(quota), eq(CLIENT), any(), any())).thenReturn(consumed);
  }

  @Nested
  @DisplayName("which creates are checked")
  class Applicability {

    @Test
    void anEntityCountedByABlockQuotaOfTheClientIsChecked() {
      assertTrue(applies(guard, "Invoice"));
    }

    @Test
    void anEntityTheClientDoesNotCapIsNotCheckedAndRunsNoQuery() {
      assertFalse(applies(guard, "BusinessPartner"));
      assertTrue(check(guard, "BusinessPartner", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(matcher, reader);
      verify(lookup, times(1)).forTenant(CLIENT);
    }

    /**
     * REPRO (ETP-5051 redesign): the global index checked every create of an entity that ANY plan
     * capped. A client whose plan does not cap the entity must run no query, even while another
     * client has a block quota on that very entity.
     */
    @Test
    void anEntityCappedOnlyForAnotherClientIsNotCheckedForThisOne() {
      assertTrue(applies(guard, "Invoice"), "capped for CLIENT");

      requestClient.set(OTHER_CLIENT);
      assertFalse(applies(guard, "Invoice"));
      assertTrue(check(guard, "Invoice", List.of("ROW-1")).isEmpty());
      assertFalse(applies(guard, "Invoice"));

      verifyNoInteractions(matcher, reader);
      verify(lookup, times(1)).forTenant(OTHER_CLIENT);
    }

    /**
     * REPRO (ETP-5051 redesign): the client's quotas are looked up once per TTL, not on every
     * create; a client with no block quota at all is cached too.
     */
    @Test
    void theClientsQuotasAreLookedUpOnceForManyCreates() {
      when(matcher.tenantIfCounted(any(), anyString(), any())).thenReturn(CLIENT);
      when(reader.readBeforeCreate(any(), eq(CLIENT), any(), any())).thenReturn(1L);
      for (int create = 0; create < 5; create++) {
        assertTrue(applies(guard, "Invoice"));
        assertTrue(check(guard, "Invoice", List.of("ROW-" + create)).isEmpty());
      }
      requestClient.set(OTHER_CLIENT);
      for (int create = 0; create < 5; create++) {
        assertFalse(applies(guard, "Invoice"));
      }

      verify(lookup, times(1)).forTenant(CLIENT);
      verify(lookup, times(1)).forTenant(OTHER_CLIENT);
    }

    @Test
    void anExpiredEntryIsRebuiltOnTheNextCreate() {
      applies(guard, "Invoice");
      now.addAndGet(QuotaClientCache.TTL_MS - 1);
      applies(guard, "Invoice");
      verify(lookup, times(1)).forTenant(CLIENT);

      when(lookup.forTenant(CLIENT)).thenReturn(TenantQuotas.none(CLIENT));
      now.addAndGet(1);

      assertFalse(applies(guard, "Invoice"), "the quota switched off reached the client");
      verify(lookup, times(2)).forTenant(CLIENT);
    }

    @Test
    void aFailedLookupPropagatesSoTheGateFailsClosed() {
      when(lookup.forTenant(CLIENT)).thenThrow(new IllegalStateException("aborted"));

      assertThrows(IllegalStateException.class, () -> applies(guard, "Invoice"));
    }

    @Test
    void aRequestWithNoClientChecksNothing() {
      requestClient.set(null);

      assertFalse(applies(guard, "Invoice"));
      assertTrue(check(guard, "Invoice", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(lookup, matcher, reader);
    }

    @Test
    void noCreatedIdsMeansNothingToCheck() {
      assertTrue(check(guard, "Invoice", Collections.emptyList()).isEmpty());
      assertTrue(check(guard, "Invoice", null).isEmpty());
      // The entry is read (before the insert, as the gate does); nothing is matched or measured.
      verifyNoInteractions(matcher, reader);
    }
  }

  @Nested
  @DisplayName("a created row")
  class CreatedRow {

    @Test
    void outsideTheRestrictionIsLetThroughWithoutReadingConsumption() {
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-1"), any())).thenReturn(null);

      assertTrue(check(guard, "Invoice", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(reader);
    }

    @Test
    void insideTheRestrictionOverQuotaIsRefused() {
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-1"), any())).thenReturn(CLIENT);
      preCreate(INVOICES, 10L);

      Optional<QuotaEvaluation> refusal = check(guard, "Invoice", List.of("ROW-1"));

      assertTrue(refusal.isPresent());
      assertEquals(QuotaVerdict.DENY, refusal.get().getVerdict());
      assertEquals(Long.valueOf(10L), refusal.get().getConsumed());
    }

    @Test
    void insideTheRestrictionUnderQuotaStands() {
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-1"), any())).thenReturn(CLIENT);
      preCreate(INVOICES, 9L);

      assertTrue(check(guard, "Invoice", List.of("ROW-1")).isEmpty());
    }

    /** NEO creates rows in the request's client; another client's row is skipped, not measured. */
    @Test
    void aRowOfAnotherClientIsSkippedWithoutMeasuringAnything() {
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-1"), any())).thenReturn(OTHER_CLIENT);

      assertTrue(check(guard, "Invoice", List.of("ROW-1")).isEmpty());
      verify(lookup, never()).forTenant(OTHER_CLIENT);
      verifyNoInteractions(reader);
    }

    /**
     * REPRO (ETP-5051 re-review W2): a row dated outside the counted window does not consume this
     * period — the matcher, bound to the window, does not count it — so it stands at the cap and no
     * consumption is read.
     */
    @Test
    void aRowDatedOutsideTheWindowStandsAtTheCap() {
      preCreate(INVOICES, 10L);
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-1"), any())).thenReturn(null);

      assertTrue(check(guard, "Invoice", List.of("ROW-1")).isEmpty());
      ArgumentCaptor<QuotaPeriod> window = ArgumentCaptor.forClass(QuotaPeriod.class);
      verify(matcher).tenantIfCounted(eq(INVOICES), eq("ROW-1"), window.capture());
      assertEquals(PERIOD_START, window.getValue().getStart());
      verifyNoInteractions(reader);
    }

    /** REPRO (ETP-5051 re-review W2): an in-window row at the cap is refused. */
    @Test
    void anInWindowRowAtTheCapIsRefused() {
      preCreate(INVOICES, 10L);
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-1"), any())).thenReturn(CLIENT);

      Optional<QuotaEvaluation> refusal = check(guard, "Invoice", List.of("ROW-1"));

      assertTrue(refusal.isPresent());
      assertEquals(Long.valueOf(10L), refusal.get().getConsumed());
      verify(reader).readBeforeCreate(eq(INVOICES), eq(CLIENT), any(), eq(List.of("ROW-1")));
    }

    /** The rows of one create are charged one after the other: the one over the cap is refused. */
    @Test
    void theRowsOfOneCreateAreChargedOneAfterTheOther() {
      preCreate(INVOICES, 9L);
      when(matcher.tenantIfCounted(eq(INVOICES), anyString(), any())).thenReturn(CLIENT);

      Optional<QuotaEvaluation> refusal = check(guard, "Invoice", List.of("ROW-1", "ROW-2"));

      assertTrue(refusal.isPresent());
      assertEquals(Long.valueOf(10L), refusal.get().getConsumed());
      // ETP-5051 re-review N5: the consumption before the create is read once per quota.
      verify(reader, times(1)).readBeforeCreate(eq(INVOICES), eq(CLIENT), any(), any());
    }

    @Test
    void aTenantWithNoCurrentPeriodRunsNoRowMatch() {
      when(lookup.forTenant(CLIENT)).thenReturn(
          QuotaTestFixtures.tenantWithPeriod(CLIENT, null, null, false, INVOICES));

      assertTrue(check(guard, "Invoice", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(matcher, reader);
    }

    @Test
    void theFirstRefusedRowStopsTheCheck() {
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-1"), any())).thenReturn(OTHER_CLIENT);
      when(matcher.tenantIfCounted(eq(INVOICES), eq("ROW-2"), any())).thenReturn(CLIENT);
      preCreate(INVOICES, 12L);

      Optional<QuotaEvaluation> refusal =
          check(guard, "Invoice", List.of("ROW-1", "ROW-2", "ROW-3"));

      assertTrue(refusal.isPresent());
      verify(matcher, times(0)).tenantIfCounted(eq(INVOICES), eq("ROW-3"), any());
    }
  }

  /**
   * A resource added to the catalog later is evaluable and enforceable with no code change: a
   * declarative resource on an entity nothing in production code names, capped in block mode.
   */
  @Nested
  @DisplayName("a resource added to the catalog later")
  class CatalogAddedResource {

    private static final String ENTITY = "ETGO_ContractSignature";

    private QuotaDefinition signedContracts() {
      return QuotaDefinition.builder()
          .resourceId("RES-SIGNED")
          .resourceKey("SIGNED_CONTRACTS")
          .resourceName("Signed contracts")
          .unitLabel("contracts")
          .countingMode("D")
          .countedEntity(ENTITY)
          .dateProperty("signedAt")
          .hqlRestriction("e.status = 'SIGNED'")
          .enforcementMode("block")
          .includedQty(5L)
          .build();
    }

    private QuotaWriteGuard catalogGuard(QuotaDefinition definition) {
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, definition));
      when(reader.read(eq(definition), eq(CLIENT), any())).thenReturn(5L);
      preCreate(definition, 5L);
      return new QuotaWriteGuard(new QuotaClientCache(lookup, now::get), matcher,
          new QuotaEvaluator(lookup, reader, new MutableClock(NOW)), requestClient::get);
    }

    @Test
    void itIsDeniedAtItsLimit() {
      QuotaDefinition definition = signedContracts();
      catalogGuard(definition);

      QuotaEvaluation evaluation = new QuotaEvaluator(lookup, reader, new MutableClock(NOW))
          .evaluateTenant(CLIENT, QuotaEvaluator.Scope.ENFORCEMENT).get(0);

      assertEquals(QuotaVerdict.DENY, evaluation.getVerdict());
      assertEquals(Long.valueOf(5L), evaluation.getConsumed());
    }

    @Test
    void itsRefusalIsWordedFromTheCatalogNameAndUnit() throws Exception {
      QuotaDefinition definition = signedContracts();
      QuotaWriteGuard catalogGuard = catalogGuard(definition);
      when(matcher.tenantIfCounted(any(), eq("ROW-9"), any())).thenReturn(CLIENT);

      QuotaEvaluation refusal = check(catalogGuard, ENTITY, List.of("ROW-9")).get();
      NeoResponse response = NeoQuotaWriteGate.refusalResponse(refusal);

      assertEquals("Quota exceeded: Signed contracts (5/5 contracts)",
          QuotaItemJson.refusalMessage(refusal));
      assertEquals(403, response.getHttpStatus());
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals("Quota exceeded: Signed contracts (5/5 contracts)", error.getString("message"));
      assertEquals("QUOTA_EXCEEDED", error.getString("code"));
      assertEquals(403, error.getInt("status"));
      assertEquals("SIGNED_CONTRACTS", error.getJSONObject("quota").getString("resourceKey"));
      assertEquals(5L, error.getJSONObject("quota").getLong("consumed"));
      assertEquals("DENY", error.getJSONObject("quota").getString("verdict"));
    }

    @Test
    void theWriteGuardRefusesACreateOfItsEntity() {
      QuotaDefinition definition = signedContracts();
      QuotaWriteGuard catalogGuard = catalogGuard(definition);
      when(matcher.tenantIfCounted(any(), eq("ROW-9"), any())).thenReturn(CLIENT);

      assertTrue(applies(catalogGuard, ENTITY));
      Optional<QuotaEvaluation> refusal = check(catalogGuard, ENTITY, List.of("ROW-9"));

      assertTrue(refusal.isPresent());
      assertEquals("SIGNED_CONTRACTS", refusal.get().getQuota().getResourceKey());
      ArgumentCaptor<QuotaDefinition> matched = ArgumentCaptor.forClass(QuotaDefinition.class);
      verify(matcher).tenantIfCounted(matched.capture(), eq("ROW-9"), any());
      assertEquals("e.status = 'SIGNED'", matched.getValue().getHqlRestriction());
    }
  }

  /**
   * ETP-6060 — a resource that counts completed invoices does not match a draft, so the create
   * check never refuses it; the completion is checked before it runs, on the row as it will be.
   */
  @Nested
  @DisplayName("a status change")
  class StatusChange {

    private final QuotaDefinition completed = quota("DONE", "block", 2L, null)
        .hqlRestriction("e.salesTransaction = true and e.documentStatus in ('CO', 'CL')").build();
    private StatusChangeRowMatcher statusMatcher;
    private QuotaWriteGuard statusGuard;

    @BeforeEach
    void setUpStatusGuard() {
      statusMatcher = mock(StatusChangeRowMatcher.class);
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, completed));
      statusGuard = new QuotaWriteGuard(new QuotaClientCache(lookup, now::get), matcher,
          statusMatcher, new QuotaEvaluator(lookup, reader, new MutableClock(NOW)),
          requestClient::get);
    }

    private Optional<QuotaEvaluation> complete(String recordId) {
      return statusGuard.refusalForStatusChange(statusGuard.entryIfChecked("Invoice").orElse(null),
          "Invoice", recordId, "documentStatus", "CO");
    }

    private void willCount(String recordId) {
      when(statusMatcher.tenantIfCountedAs(eq(completed), eq(recordId), any(),
          eq("documentStatus"), eq("CO"))).thenReturn(CLIENT);
    }

    @Test
    void aDraftThatTheCompletionWouldTakeOverTheQuotaIsRefused() {
      willCount("DRAFT-1");
      preCreate(completed, 2L);

      Optional<QuotaEvaluation> refusal = complete("DRAFT-1");

      assertTrue(refusal.isPresent());
      assertEquals(QuotaVerdict.DENY, refusal.get().getVerdict());
      assertEquals(Long.valueOf(2L), refusal.get().getConsumed());
      verify(reader).readBeforeCreate(eq(completed), eq(CLIENT), any(), eq(List.of("DRAFT-1")));
    }

    @Test
    void aDraftUnderTheQuotaMayBeCompleted() {
      willCount("DRAFT-1");
      preCreate(completed, 1L);

      assertTrue(complete("DRAFT-1").isEmpty());
    }

    /** The row was charged when it started counting; changing it again charges nothing. */
    @Test
    void aRowThatAlreadyCountsIsNotChargedAgain() {
      when(matcher.tenantIfCounted(eq(completed), eq("DONE-1"), any())).thenReturn(CLIENT);
      preCreate(completed, 5L);

      assertTrue(complete("DONE-1").isEmpty());
      verifyNoInteractions(statusMatcher, reader);
    }

    /** A purchase invoice: completed, it still is no sales invoice. */
    @Test
    void aRowTheCompletionDoesNotMakeCountedStands() {
      preCreate(completed, 5L);

      assertTrue(complete("PURCHASE-1").isEmpty());
      verifyNoInteractions(reader);
    }

    @Test
    void aRowOfAnotherClientIsNotMeasured() {
      when(statusMatcher.tenantIfCountedAs(eq(completed), eq("DRAFT-1"), any(),
          eq("documentStatus"), eq("CO"))).thenReturn(OTHER_CLIENT);

      assertTrue(complete("DRAFT-1").isEmpty());
      verifyNoInteractions(reader);
    }

    /** Counted from creation on: the create check charged it, the completion must not. */
    @Test
    void aRestrictionThatDoesNotReadTheStatusIsLeftToTheCreateCheck() {
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, INVOICES));
      preCreate(INVOICES, 10L);

      assertTrue(complete("DRAFT-1").isEmpty());
      verifyNoInteractions(matcher, statusMatcher, reader);
    }

    @Test
    void aTenantWithNoCurrentPeriodRunsNoMatch() {
      when(lookup.forTenant(CLIENT)).thenReturn(
          QuotaTestFixtures.tenantWithPeriod(CLIENT, null, null, false, completed));

      assertTrue(complete("DRAFT-1").isEmpty());
      verifyNoInteractions(matcher, statusMatcher, reader);
    }

    @Test
    void noEntryOrNoRecordMeansNothingToCheck() {
      assertTrue(statusGuard.refusalForStatusChange(null, "Invoice", "DRAFT-1", "documentStatus",
          "CO").isEmpty());
      assertTrue(statusGuard.refusalForStatusChange(statusGuard.entryIfChecked("Invoice").get(),
          "Invoice", null, "documentStatus", "CO").isEmpty());
      verifyNoInteractions(matcher, statusMatcher, reader);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProductionMatcherBindsTheTargetValue() {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      Query<String> query = mock(Query.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(String.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(1)).thenReturn(query);
      when(query.uniqueResult()).thenReturn(CLIENT);
      QuotaPeriod period = QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);

      String tenant;
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        obDal.when(OBDal::getInstance).thenReturn(dal);
        tenant = StatusChangeRowMatcher.dal().tenantIfCountedAs(completed, "DRAFT-1", period,
            "documentStatus", "CO");
      }

      assertEquals(CLIENT, tenant);
      verify(session).createQuery(UsageQueryComposer.composeRowMatchInRangeAs("Invoice",
          "invoiceDate", completed.getHqlRestriction(), "documentStatus"), String.class);
      verify(query).setParameter(UsageQueryComposer.PARAM_TARGET_VALUE, "CO");
      verify(query).setParameter(UsageQueryComposer.PARAM_RECORD_ID, "DRAFT-1");
    }
  }

  @Nested
  @DisplayName("the production matcher")
  class DalMatcher {

    @Test
    @SuppressWarnings("unchecked")
    void itRunsTheComposedRowMatchBoundToTheRecordIdAndTheWindow() {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      Query<String> query = mock(Query.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(String.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(1)).thenReturn(query);
      when(query.uniqueResult()).thenReturn(CLIENT);
      QuotaPeriod period = QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);

      String tenant;
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        obDal.when(OBDal::getInstance).thenReturn(dal);
        tenant = CreatedRowMatcher.dal().tenantIfCounted(INVOICES, "ROW-1", period);
      }

      assertEquals(CLIENT, tenant);
      verify(session).createQuery(UsageQueryComposer.composeRowMatchInRange("Invoice",
          "invoiceDate", "e.salesTransaction = true"), String.class);
      verify(query).setParameter(UsageQueryComposer.PARAM_RECORD_ID, "ROW-1");
      verify(query).setParameter(UsageQueryComposer.PARAM_DAY_START, period.getWindowFrom());
      verify(query).setParameter(UsageQueryComposer.PARAM_DAY_END, period.getWindowTo());
    }
  }
}
