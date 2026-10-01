/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"). You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.Test;

import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/** Unit contract for the checkout polling state projection. */
public class CheckoutRequestProvisioningStatusTest {

  private final CheckoutRequestStore store = new CheckoutRequestStore();

  @Test
  public void provisionedIsTerminalAndCannotBeRetried() {
    CheckoutRequest request = request("PROVISIONED", null, new Date());

    assertEquals("provisioned", store.deriveProvisioningStatus(request));
    assertFalse(store.isProvisioningRetryAllowed(request));
  }

  @Test
  public void activeProvisioningIsNotRetriedByPolling() {
    CheckoutRequest request = request("PROVISIONING", null, new Date());

    assertEquals("provisioning", store.deriveProvisioningStatus(request));
    assertFalse(store.isProvisioningRetryAllowed(request));
  }

  @Test
  public void failedProvisioningIsRetryableWithoutCreatingAnotherLifecycleState() {
    CheckoutRequest request = request("PROVISIONING", "warehouse setup failed", new Date());

    assertEquals(CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED,
        store.deriveProvisioningStatus(request));
    assertTrue(store.isProvisioningRetryAllowed(request));
  }

  @Test
  public void staleProvisioningIsRetryableWithAReclaimFence() {
    CheckoutRequest request = request("PROVISIONING", null,
        new Date(System.currentTimeMillis() - 31L * 60L * 1000L));

    assertEquals(CheckoutRequestStore.DERIVED_STATUS_STALLED,
        store.deriveProvisioningStatus(request));
    assertTrue(store.isProvisioningRetryAllowed(request));
  }

  @Test
  public void deterministicFailureIsNotRetryable() {
    CheckoutRequest request = request("PROVISIONING", CheckoutRequestStore.encodeFailureReason(
        CheckoutRequestStore.FAILURE_CODE_CLIENT_NAME_IN_USE, "The company name 'Acme' is taken"),
        new Date());

    assertEquals(CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED,
        store.deriveProvisioningStatus(request));
    assertFalse("Retrying a name collision would fail the same way forever",
        store.isProvisioningRetryAllowed(request));
  }

  @Test
  public void failureCodeRoundTripsThroughThePersistedReason() {
    String persisted = CheckoutRequestStore.encodeFailureReason(
        CheckoutRequestStore.FAILURE_CODE_CLIENT_NAME_IN_USE, "raw  cause\nwith SQL");

    assertEquals("CLIENT_NAME_IN_USE: raw cause with SQL", persisted);
    assertEquals(CheckoutRequestStore.FAILURE_CODE_CLIENT_NAME_IN_USE,
        CheckoutRequestStore.failureCode(persisted));
  }

  @Test
  public void failureWithoutCodeReadsAsGenericRetryableFailure() {
    assertEquals(CheckoutRequestStore.FAILURE_CODE_PROVISIONING_FAILED,
        CheckoutRequestStore.encodeFailureReason(null, null).split(":")[0]);
    assertEquals("A reason written before codes existed keeps its old, retryable meaning",
        CheckoutRequestStore.FAILURE_CODE_PROVISIONING_FAILED,
        CheckoutRequestStore.failureCode("warehouse setup failed"));
    assertNull(CheckoutRequestStore.failureCode("  "));
  }

  @Test
  public void safeDescriptionNeverEchoesThePersistedCause() {
    String description = CheckoutRequestStore.safeFailureDescription(
        CheckoutRequestStore.failureCode("PROVISIONING_FAILED: ERROR duplicate key ad_client_name"));

    assertFalse(description.contains("duplicate key"));
    assertEquals(CheckoutRequestStore.safeFailureDescription(
        CheckoutRequestStore.FAILURE_CODE_PROVISIONING_FAILED),
        CheckoutRequestStore.safeFailureDescription("SOME_UNKNOWN_CODE"));
  }

  private static CheckoutRequest request(String status, String failureReason,
      Date provisioningAt) {
    CheckoutRequest request = mock(CheckoutRequest.class);
    when(request.getCheckoutRequestStatus()).thenReturn(status);
    when(request.getFailureReason()).thenReturn(failureReason);
    when(request.getProvisioningAt()).thenReturn(provisioningAt);
    return request;
  }
}
