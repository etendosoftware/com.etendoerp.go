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

  @Nested
  @DisplayName("which creates are checked")
  class Applicability {

    @Test
    void anEntityCountedByABlockQuotaOfTheClientIsChecked() {
      assertTrue(guard.appliesTo("Invoice"));
    }

    @Test
    void anEntityTheClientDoesNotCapIsNotCheckedAndRunsNoQuery() {
      assertFalse(guard.appliesTo("BusinessPartner"));
      assertTrue(guard.refusalFor("BusinessPartner", List.of("ROW-1")).isEmpty());
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
      assertTrue(guard.appliesTo("Invoice"), "capped for CLIENT");

      requestClient.set(OTHER_CLIENT);
      assertFalse(guard.appliesTo("Invoice"));
      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1")).isEmpty());
      assertFalse(guard.appliesTo("Invoice"));

      verifyNoInteractions(matcher, reader);
      verify(lookup, times(1)).forTenant(OTHER_CLIENT);
    }

    /**
     * REPRO (ETP-5051 redesign): the client's quotas are looked up once per TTL, not on every
     * create; a client with no block quota at all is cached too.
     */
    @Test
    void theClientsQuotasAreLookedUpOnceForManyCreates() {
      when(matcher.tenantIfCounted(any(), anyString())).thenReturn(CLIENT);
      when(reader.read(any(), eq(CLIENT), any())).thenReturn(1L);
      for (int create = 0; create < 5; create++) {
        assertTrue(guard.appliesTo("Invoice"));
        assertTrue(guard.refusalFor("Invoice", List.of("ROW-" + create)).isEmpty());
      }
      requestClient.set(OTHER_CLIENT);
      for (int create = 0; create < 5; create++) {
        assertFalse(guard.appliesTo("Invoice"));
      }

      verify(lookup, times(1)).forTenant(CLIENT);
      verify(lookup, times(1)).forTenant(OTHER_CLIENT);
    }

    @Test
    void anExpiredEntryIsRebuiltOnTheNextCreate() {
      guard.appliesTo("Invoice");
      now.addAndGet(QuotaClientCache.TTL_MS - 1);
      guard.appliesTo("Invoice");
      verify(lookup, times(1)).forTenant(CLIENT);

      when(lookup.forTenant(CLIENT)).thenReturn(TenantQuotas.none(CLIENT));
      now.addAndGet(1);

      assertFalse(guard.appliesTo("Invoice"), "the quota switched off reached the client");
      verify(lookup, times(2)).forTenant(CLIENT);
    }

    @Test
    void aFailedLookupPropagatesSoTheGateFailsClosed() {
      when(lookup.forTenant(CLIENT)).thenThrow(new IllegalStateException("aborted"));

      assertThrows(IllegalStateException.class, () -> guard.appliesTo("Invoice"));
    }

    @Test
    void aRequestWithNoClientChecksNothing() {
      requestClient.set(null);

      assertFalse(guard.appliesTo("Invoice"));
      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(lookup, matcher, reader);
    }

    @Test
    void noCreatedIdsMeansNothingToCheck() {
      assertTrue(guard.refusalFor("Invoice", Collections.emptyList()).isEmpty());
      assertTrue(guard.refusalFor("Invoice", null).isEmpty());
      verifyNoInteractions(matcher, lookup, reader);
    }
  }

  @Nested
  @DisplayName("a created row")
  class CreatedRow {

    @Test
    void outsideTheRestrictionIsLetThroughWithoutReadingConsumption() {
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(null);

      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(reader);
    }

    @Test
    void insideTheRestrictionOverQuotaIsRefused() {
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(CLIENT);
      when(reader.read(eq(INVOICES), eq(CLIENT), any())).thenReturn(10L);

      Optional<QuotaEvaluation> refusal = guard.refusalFor("Invoice", List.of("ROW-1"));

      assertTrue(refusal.isPresent());
      assertEquals(QuotaVerdict.DENY, refusal.get().getVerdict());
      assertEquals(Long.valueOf(10L), refusal.get().getConsumed());
    }

    @Test
    void insideTheRestrictionUnderQuotaStands() {
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(CLIENT);
      when(reader.read(eq(INVOICES), eq(CLIENT), any())).thenReturn(9L);

      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1")).isEmpty());
    }

    @Test
    void aRowCreatedIntoAnotherClientIsMeasuredAgainstThatClientsQuotas() {
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(OTHER_CLIENT);

      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1")).isEmpty());
      verify(lookup, times(1)).forTenant(OTHER_CLIENT);
      verifyNoInteractions(reader);
    }

    @Test
    void theFirstRefusedRowStopsTheCheck() {
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(OTHER_CLIENT);
      when(matcher.tenantIfCounted(INVOICES, "ROW-2")).thenReturn(CLIENT);
      when(reader.read(eq(INVOICES), eq(CLIENT), any())).thenReturn(12L);

      Optional<QuotaEvaluation> refusal =
          guard.refusalFor("Invoice", List.of("ROW-1", "ROW-2", "ROW-3"));

      assertTrue(refusal.isPresent());
      verify(matcher, times(0)).tenantIfCounted(INVOICES, "ROW-3");
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
      when(matcher.tenantIfCounted(any(), eq("ROW-9"))).thenReturn(CLIENT);

      QuotaEvaluation refusal = catalogGuard.refusalFor(ENTITY, List.of("ROW-9")).get();
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
      when(matcher.tenantIfCounted(any(), eq("ROW-9"))).thenReturn(CLIENT);

      assertTrue(catalogGuard.appliesTo(ENTITY));
      Optional<QuotaEvaluation> refusal = catalogGuard.refusalFor(ENTITY, List.of("ROW-9"));

      assertTrue(refusal.isPresent());
      assertEquals("SIGNED_CONTRACTS", refusal.get().getQuota().getResourceKey());
      ArgumentCaptor<QuotaDefinition> matched = ArgumentCaptor.forClass(QuotaDefinition.class);
      verify(matcher).tenantIfCounted(matched.capture(), eq("ROW-9"));
      assertEquals("e.status = 'SIGNED'", matched.getValue().getHqlRestriction());
    }
  }

  @Nested
  @DisplayName("the production matcher")
  class DalMatcher {

    @Test
    @SuppressWarnings("unchecked")
    void itRunsTheComposedRowMatchBoundToTheRecordId() {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      Query<String> query = mock(Query.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(String.class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.setMaxResults(1)).thenReturn(query);
      when(query.uniqueResult()).thenReturn(CLIENT);

      String tenant;
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        obDal.when(OBDal::getInstance).thenReturn(dal);
        tenant = CreatedRowMatcher.dal().tenantIfCounted(INVOICES, "ROW-1");
      }

      assertEquals(CLIENT, tenant);
      verify(session).createQuery(
          UsageQueryComposer.composeRowMatch("Invoice", "e.salesTransaction = true"),
          String.class);
      verify(query).setParameter(UsageQueryComposer.PARAM_RECORD_ID, "ROW-1");
    }
  }
}
