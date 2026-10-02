/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.onboarding.pool.TenantPoolStore;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

class DevProvisioningFailureFixtureServiceTest {

  @AfterEach
  void clearProperties() {
    System.clearProperty(DevProvisioningFailureFixtureService.ENVIRONMENT_PROPERTY);
  }

  @Test
  void requiresLocalEnvironment() {
    System.setProperty(DevProvisioningFailureFixtureService.ENVIRONMENT_PROPERTY, "production");
    assertFalse(DevProvisioningFailureFixtureService.isEnabled());

    System.setProperty(DevProvisioningFailureFixtureService.ENVIRONMENT_PROPERTY, "local");
    assertTrue(DevProvisioningFailureFixtureService.isEnabled());
  }

  @Test
  void remainsDisabledOutsideLocal() {
    System.setProperty(DevProvisioningFailureFixtureService.ENVIRONMENT_PROPERTY, "development");
    assertFalse(DevProvisioningFailureFixtureService.isEnabled());
  }

  @Test
  void unknownRequestNeverArmsFailure() {
    System.setProperty(DevProvisioningFailureFixtureService.ENVIRONMENT_PROPERTY, "local");
    DevProvisioningFailureFixtureService fixture = new DevProvisioningFailureFixtureService(
        mock(CheckoutRequestStore.class), mock(TenantPoolStore.class));
    assertFalse(fixture.shouldFail("unknown"));
  }

  @Test
  void reservedClientIsVisibleOnlyToCheckoutOwner() {
    System.setProperty(DevProvisioningFailureFixtureService.ENVIRONMENT_PROPERTY, "local");
    CheckoutRequestStore requests = mock(CheckoutRequestStore.class);
    TenantPoolStore pool = mock(TenantPoolStore.class);
    DevProvisioningFailureFixtureService fixture = new DevProvisioningFailureFixtureService(
        requests, pool);
    String requestId = "e2e-fixture-request-1";
    when(requests.find(requestId, "owner-id", "owner@example.test"))
        .thenReturn(mock(CheckoutRequest.class));
    when(pool.findFixtureReservation(requestId))
        .thenReturn(new TenantPoolStore.Claim("pool-row", "fixture-client"));

    assertEquals("fixture-client", fixture.reservedClientId(requestId, "owner-id",
        "owner@example.test"));
    assertNull(fixture.reservedClientId(requestId, "other-id", "other@example.test"));
  }
}
