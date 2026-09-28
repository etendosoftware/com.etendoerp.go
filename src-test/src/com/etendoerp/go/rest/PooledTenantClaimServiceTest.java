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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;

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

  @Test
  public void anExistingClientNameKeepsResumeAndCollisionHandlingClassic() {
    TestClaimService service = new TestClaimService(true);
    service.existingClientId = "CLIENT-EXISTING";

    assertNull(service.claim(new RecordingSink(), SUPPORTED));

    assertTrue(service.fakeStore.calls.isEmpty());
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
    String existingClientId;
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
    String findClientIdByName(String clientName) {
      return existingClientId;
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
