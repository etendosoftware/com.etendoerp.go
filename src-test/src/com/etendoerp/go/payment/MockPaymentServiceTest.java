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

package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MockPaymentServiceTest {

  private final MockPaymentService service = new MockPaymentService();

  @ParameterizedTest
  @ValueSource(strings = {
      "mock-paid-a1b2c3",
      "mock-paid-0",
      "mock-paid-deadbeef",
      "mock-paid-0123456789abcdef"
  })
  void approvesTokensMatchingTheSettledPaymentShape(String token) {
    assertEquals(PaymentOutcome.APPROVED, service.validate(token));
  }

  @Test
  void trimsSurroundingWhitespaceBeforeMatching() {
    assertEquals(PaymentOutcome.APPROVED, service.validate("  mock-paid-abc123  "));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "mock-declined",
      "mock-paid-",
      "mock-paid-XYZ",
      "mock-paid-ABCDEF",
      "mock-paid-g1",
      "MOCK-PAID-abc",
      "mock-paid-abc extra",
      "prefix-mock-paid-abc",
      "mock-paid-abc\nmock-paid-def",
      "tok_visa"
  })
  void declinesEverythingElse(String token) {
    assertEquals(PaymentOutcome.DECLINED, service.validate(token));
  }

  @Test
  void reportsMissingTokenForNull() {
    assertEquals(PaymentOutcome.MISSING_TOKEN, service.validate(null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   ", "\t" })
  void reportsMissingTokenForBlankInput(String token) {
    assertEquals(PaymentOutcome.MISSING_TOKEN, service.validate(token));
  }
}
