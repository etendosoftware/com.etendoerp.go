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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingProgressSink;
import com.etendoerp.go.onboarding.pool.FakeTenantPoolStore;
import com.etendoerp.go.onboarding.pool.TenantPoolStore;

/**
 * ETP-5389 — when onboarding takes a pooled tenant and when it falls back to the classic path.
 * The row-level atomicity itself lives in {@code TenantPoolStore#claimReady}'s
 * {@code FOR UPDATE SKIP LOCKED}; what is pinned here is that the claim runs inside the caller's
 * transaction (never commits on success) and that every refusal leaves the pool untouched.
 */
public class PooledTenantClaimServiceTest {

  private static final PooledTenantClaimService.ClaimRequest SUPPORTED =
      new PooledTenantClaimService.ClaimRequest("owner@acme.test", "Acme", "Ada Lovelace", "EUR",
          "ES", "es_ES", "Calle Mayor 1", "secret");

  /**
   * ETP-5548: a claimed tenant showed "POOL-<id> Admin" as its role and kept the placeholder in its
   * ledger, chart of accounts, calendar and trees. Every rewrite stays inside the claimed client.
   */
  @Test
  void renamesEveryNameDerivedFromThePlaceholderInsideTheClaimedClient() {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    @SuppressWarnings("unchecked")
    NativeQuery<Object> query = mock(NativeQuery.class, RETURNS_SELF);
    List<String> statements = new ArrayList<>();
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenAnswer(invocation -> {
      statements.add(invocation.getArgument(0));
      return query;
    });
    when(query.executeUpdate()).thenReturn(1, 17, 1, 1, 1);

    int rewritten;
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      rewritten = new PooledTenantClaimService().renamePlaceholderDerivedNames("CLIENT-1",
          "POOL-ABC", "Acme SL");
    }

    assertEquals(21, rewritten);
    assertEquals(PooledTenantClaimService.PLACEHOLDER_DERIVED_NAME_UPDATES, statements);
    for (String table : List.of("ad_role", "ad_tree", "c_acctschema", "c_element", "c_calendar")) {
      assertTrue(statements.stream().anyMatch(sql -> sql.startsWith("update " + table + " ")),
          table + " must be rewritten");
    }
    statements.forEach(sql -> assertTrue(sql.contains("where ad_client_id = :clientId"),
        "a rewrite must never leave the claimed client: " + sql));
    verify(query, times(5)).setParameter("placeholder", "POOL-ABC");
    verify(query, times(5)).setParameter("clientName", "Acme SL");
    verify(query, times(5)).setParameter("clientId", "CLIENT-1");
  }

  @Test
  public void flagOffNeverTouchesThePool() {
    TestClaimService service = new TestClaimService(false);

    assertNull(service.claim(new RecordingSink(), SUPPORTED));

    assertTrue(service.fakeStore.calls.isEmpty());
    assertFalse(service.personalized);
  }

  @Test
  public void unsupportedCombinationTakesTheClassicPath() {
    TestClaimService service = new TestClaimService(true);
    PooledTenantClaimService.ClaimRequest usd = new PooledTenantClaimService.ClaimRequest(
        "owner@acme.test", "Acme", "", "USD", "ES", "es_ES", "", "secret");
    PooledTenantClaimService.ClaimRequest english = new PooledTenantClaimService.ClaimRequest(
        "owner@acme.test", "Acme", "", "EUR", "ES", "en_US", "", "secret");

    assertNull(service.claim(new RecordingSink(), usd));
    assertNull(service.claim(new RecordingSink(), english));

    assertTrue(service.fakeStore.calls.isEmpty());
  }

  /**
   * ETP-5548: company names are not unique, so eligibility never looks the name up — no DAL is
   * stubbed here, and a lookup would fail the test. Resuming a half-built client is decided by the
   * servlet before the claim is attempted.
   */
  @Test
  public void eligibilityNeverDependsOnTheCompanyNameBeingTaken() {
    assertTrue(new TestClaimService(true).isEligible(SUPPORTED));
  }

  @Test
  public void aPlaceholderNameIsNeverServedFromThePool() {
    TestClaimService service = new TestClaimService(true);
    PooledTenantClaimService.ClaimRequest placeholder = new PooledTenantClaimService.ClaimRequest(
        "owner@acme.test", "POOL-123", "", "EUR", "ES", "es_ES", "", "secret");

    assertNull(service.claim(new RecordingSink(), placeholder));

    assertTrue(service.fakeStore.calls.isEmpty());
  }

  @Test
  public void anEmptyPoolFallsBackWithoutError() {
    TestClaimService service = new TestClaimService(true);
    RecordingSink sink = new RecordingSink();

    assertNull(service.claim(sink, SUPPORTED));

    assertEquals(1, service.fakeStore.calls.size());
    assertTrue(service.fakeStore.calls.get(0)
        .startsWith("claim:" + OnboardingProvisioningChain.CHAIN_REVISION + "@"));
    assertTrue(sink.events.isEmpty());
  }

  @Test
  public void aClaimedTenantIsPersonalizedInsideTheCallerTransaction() {
    TestClaimService service = new TestClaimService(true);
    service.fakeStore.nextClaim = new TenantPoolStore.Claim("ROW1", "POOLED-CLIENT");
    RecordingSink sink = new RecordingSink();

    String clientId = service.claim(sink, SUPPORTED);

    assertEquals("POOLED-CLIENT", clientId);
    assertTrue(service.personalized);
    assertEquals("POOLED-CLIENT", service.personalizedClientId);
    assertEquals(SUPPORTED, service.personalizedRequest);
    // Never committed here: the claim only becomes durable with the onboarding commit, so a
    // failed onboarding rolls it back and the tenant is READY again.
    assertEquals(0, service.fakeStore.commits);
    assertEquals(0, service.fakeStore.rollbacks);
    assertEquals(List.of("client:in_progress", "client:done"), sink.events);
    assertFalse(sink.failed);
  }

  @Test
  public void aPersonalizationFailureRetiresTheTenantAndFallsBack() {
    TestClaimService service = new TestClaimService(true);
    service.fakeStore.nextClaim = new TenantPoolStore.Claim("ROW1", "POOLED-CLIENT");
    service.personalizationFailure = new IllegalStateException("admin user missing");

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      assertNull(service.claim(new RecordingSink(), SUPPORTED));
    }

    assertEquals(TenantPoolStore.STATUS_FAILED, service.fakeStore.statusByRow.get("ROW1"));
    assertTrue(service.fakeStore.errorByRow.get("ROW1").contains("admin user missing"));
    assertEquals(1, service.fakeStore.commits);
  }

  @Test
  public void aStoreFailureFallsBackToTheClassicPath() {
    TestClaimService service = new TestClaimService(true);
    service.fakeStore.claimFailure = new IllegalStateException("relation does not exist");

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      assertNull(service.claim(new RecordingSink(), SUPPORTED));
    }

    assertFalse(service.personalized);
  }

  private static final class TestClaimService extends PooledTenantClaimService {
    final FakeTenantPoolStore fakeStore = new FakeTenantPoolStore();
    private final boolean enabled;
    RuntimeException personalizationFailure;
    boolean personalized;
    String personalizedClientId;
    ClaimRequest personalizedRequest;

    TestClaimService(boolean enabled) {
      this.enabled = enabled;
      this.store = fakeStore;
    }

    @Override
    boolean isPoolEnabled(String accountEmail) {
      return enabled;
    }

    @Override
    void personalize(String clientId, ClaimRequest request) {
      if (personalizationFailure != null) {
        throw personalizationFailure;
      }
      personalized = true;
      personalizedClientId = clientId;
      personalizedRequest = request;
    }
  }

  private static final class RecordingSink implements OnboardingProgressSink {
    final List<String> events = new ArrayList<>();
    boolean failed;

    @Override
    public void progress(String step, String status, String message) {
      events.add(step + ":" + status);
    }

    @Override
    public void result(boolean success, String message, String code) {
      failed = !success;
    }
  }
}
