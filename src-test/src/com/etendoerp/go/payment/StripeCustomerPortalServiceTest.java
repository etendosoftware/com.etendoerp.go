/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** Specs for the form sent when creating a Stripe Customer Portal session. */
class StripeCustomerPortalServiceTest {

  @Test
  void encodesCustomerAndReturnUrlAsFormValues() throws IOException {
    String form = StripeCustomerPortalService.buildForm(
        "cus/alpha + beta",
        "https://go.example.test/account?tab=billing&mode=full");

    assertEquals(
        "customer=cus%2Falpha+%2B+beta&return_url=https%3A%2F%2Fgo.example.test%2Faccount"
            + "%3Ftab%3Dbilling%26mode%3Dfull",
        form);
  }

  @Test
  void keepsTheAccountPathInTheEncodedReturnUrl() throws IOException {
    String form = StripeCustomerPortalService.buildForm(
        "cus_test_123",
        "https://go.example.test/account");

    assertEquals(
        "customer=cus_test_123&return_url=https%3A%2F%2Fgo.example.test%2Faccount",
        form);
  }

  @Test
  void encodesEmptyValuesWithoutDroppingFormKeys() throws IOException {
    assertEquals("customer=&return_url=", StripeCustomerPortalService.buildForm("", ""));
  }
}
