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

  private static CheckoutRequest request(String status, String failureReason,
      Date provisioningAt) {
    CheckoutRequest request = mock(CheckoutRequest.class);
    when(request.getCheckoutRequestStatus()).thenReturn(status);
    when(request.getFailureReason()).thenReturn(failureReason);
    when(request.getProvisioningAt()).thenReturn(provisioningAt);
    return request;
  }
}
