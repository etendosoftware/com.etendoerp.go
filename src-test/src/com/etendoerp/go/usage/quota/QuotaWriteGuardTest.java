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
import com.etendoerp.go.usage.quota.QuotaEntityIndex.IndexedResource;
import com.etendoerp.go.usage.quota.QuotaTestFixtures.MutableClock;

/**
 * The NEO write check: which created rows can be refused, and when.
 *
 * <p>The index decides which entities are checked at all, the matcher whether a created row is
 * the kind of row the resource counts, and the evaluator whether the tenant is already at its
 * {@code block} quota. The {@link CatalogAddedResource} block wires the real index and evaluator
 * together on a resource no production code names, to show a catalog row is all it takes.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaWriteGuard
 * @covers com.etendoerp.go.usage.quota.CreatedRowMatcher
 */
@SuppressWarnings("java:S2187")
class QuotaWriteGuardTest {

  private static final IndexedResource INVOICES =
      new IndexedResource("RES-DOCS", "Invoice", "e.salesTransaction = true");

  private QuotaEntityIndex index;
  private CreatedRowMatcher matcher;
  private QuotaLookup lookup;
  private ConsumptionReader reader;
  private QuotaWriteGuard guard;

  @BeforeEach
  void setUp() {
    index = new QuotaEntityIndex(() -> List.of(INVOICES), () -> 0L);
    matcher = mock(CreatedRowMatcher.class);
    lookup = mock(QuotaLookup.class);
    reader = mock(ConsumptionReader.class);
    guard = new QuotaWriteGuard(index, matcher, lookup,
        new QuotaEvaluator(lookup, reader, new MutableClock(NOW)));
  }

  @Nested
  @DisplayName("which creates are checked")
  class Applicability {

    @Test
    void anEntityCountedByABlockQuotaIsChecked() {
      assertTrue(guard.appliesTo("Invoice"));
    }

    @Test
    void anEntityOutsideTheIndexIsNotCheckedAndRunsNoQuery() {
      assertFalse(guard.appliesTo("BusinessPartner"));
      assertTrue(guard.refusalFor("BusinessPartner", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(matcher, lookup, reader);
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
    void outsideTheRestrictionIsLetThroughWithoutLookingUpTheTenant() {
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(null);

      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1")).isEmpty());
      verifyNoInteractions(lookup, reader);
    }

    @Test
    void insideTheRestrictionOverQuotaIsRefused() {
      QuotaDefinition docs = quota("DOCS", "block", 10L, null).build();
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(CLIENT);
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, docs));
      when(reader.read(eq(docs), eq(CLIENT), any())).thenReturn(10L);

      Optional<QuotaEvaluation> refusal = guard.refusalFor("Invoice", List.of("ROW-1"));

      assertTrue(refusal.isPresent());
      assertEquals(QuotaVerdict.DENY, refusal.get().getVerdict());
      assertEquals(Long.valueOf(10L), refusal.get().getConsumed());
    }

    @Test
    void insideTheRestrictionUnderQuotaStands() {
      QuotaDefinition docs = quota("DOCS", "block", 10L, null).build();
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(CLIENT);
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, docs));
      when(reader.read(eq(docs), eq(CLIENT), any())).thenReturn(9L);

      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1")).isEmpty());
    }

    @Test
    void aTenantIsLookedUpOncePerCheckWhateverTheNumberOfRows() {
      QuotaDefinition docs = quota("DOCS", "block", 10L, null).build();
      when(matcher.tenantIfCounted(any(), anyString())).thenReturn(CLIENT);
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, docs));
      when(reader.read(eq(docs), eq(CLIENT), any())).thenReturn(1L);

      assertTrue(guard.refusalFor("Invoice", List.of("ROW-1", "ROW-2", "ROW-3")).isEmpty());
      verify(lookup, times(1)).forTenant(CLIENT);
    }

    @Test
    void theFirstRefusedRowStopsTheCheck() {
      QuotaDefinition docs = quota("DOCS", "block", 10L, null).build();
      when(matcher.tenantIfCounted(INVOICES, "ROW-1")).thenReturn(OTHER_CLIENT);
      when(matcher.tenantIfCounted(INVOICES, "ROW-2")).thenReturn(CLIENT);
      when(lookup.forTenant(OTHER_CLIENT)).thenReturn(TenantQuotas.none(OTHER_CLIENT));
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, docs));
      when(reader.read(eq(docs), eq(CLIENT), any())).thenReturn(12L);

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
      QuotaEntityIndex catalogIndex = new QuotaEntityIndex(() -> List.of(new IndexedResource(
          definition.getResourceId(), definition.getCountedEntity(),
          definition.getHqlRestriction())), () -> 0L);
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, definition));
      when(reader.read(eq(definition), eq(CLIENT), any())).thenReturn(5L);
      return new QuotaWriteGuard(catalogIndex, matcher, lookup,
          new QuotaEvaluator(lookup, reader, new MutableClock(NOW)));
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
      ArgumentCaptor<IndexedResource> matched = ArgumentCaptor.forClass(IndexedResource.class);
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
