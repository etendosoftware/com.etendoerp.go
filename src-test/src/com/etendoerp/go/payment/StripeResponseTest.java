/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specs for the completed provider answer.
 *
 * <p>Everything reaching this class completed, whatever its status, so the questions it answers
 * are "did the provider accept the request" and "what exactly did it refuse". The error envelope
 * is the only machine-readable part of a refusal — {@code error.code} is what distinguishes a
 * price id that does not exist from every other 404 — so it is pinned here rather than parsed
 * ad hoc at each call site.
 */
class StripeResponseTest {

  private static final String PRICE_BODY =
      "{\"id\":\"price_123\",\"object\":\"price\",\"unit_amount\":4900,\"currency\":\"eur\"}";

  private static final String MISSING_BODY = "{\"error\":{\"type\":\"invalid_request_error\","
      + "\"code\":\"resource_missing\",\"message\":\"No such price: 'price_nope'\"}}";

  @Test
  @DisplayName("2xx is success, everything else is not")
  void successIsTheTwoHundredRange() {
    assertAll(() -> assertTrue(new StripeResponse(200, PRICE_BODY).isSuccess()),
        () -> assertTrue(new StripeResponse(204, "").isSuccess()),
        () -> assertFalse(new StripeResponse(400, "{}").isSuccess()),
        () -> assertFalse(new StripeResponse(404, MISSING_BODY).isSuccess()),
        () -> assertFalse(new StripeResponse(302, "").isSuccess()));
  }

  @Test
  @DisplayName("the status and the raw body are kept verbatim")
  void statusAndBodyAreKept() {
    StripeResponse response = new StripeResponse(404, MISSING_BODY);

    assertAll(() -> assertEquals(404, response.status()),
        () -> assertEquals(MISSING_BODY, response.body()));
  }

  @Test
  @DisplayName("a successful body parses into the object the caller reads fields from")
  void successfulBodyParses() {
    StripeResponse response = new StripeResponse(200, PRICE_BODY);

    assertAll(() -> assertEquals("price_123", response.json().optString("id", "")),
        () -> assertEquals(4900L, response.json().optLong("unit_amount", 0L)),
        () -> assertEquals("eur", response.json().optString("currency", "")));
  }

  @Test
  @DisplayName("the error envelope is read out of the refusal")
  void errorEnvelopeIsParsed() {
    StripeResponse response = new StripeResponse(404, MISSING_BODY);

    assertAll(() -> assertEquals("resource_missing", response.errorCode()),
        () -> assertEquals("No such price: 'price_nope'", response.errorMessage()));
  }

  @Test
  @DisplayName("a response with no error envelope reports no code and no message")
  void withoutAnEnvelopeThereIsNoCode() {
    StripeResponse response = new StripeResponse(200, PRICE_BODY);

    assertAll(() -> assertNull(response.errorCode()), () -> assertNull(response.errorMessage()));
  }

  @Test
  @DisplayName("a non-JSON body degrades to an empty object instead of blowing up")
  void nonJsonBodyDoesNotThrow() {
    // A proxy or a WAF in front of the provider answers HTML. Parsing eagerly, or letting the
    // parse failure escape, would turn a readable "502 from a proxy" into an unexplained crash
    // inside the caller's own error handling, with the status — the only useful part — lost.
    StripeResponse response = new StripeResponse(502, "<html><body>Bad Gateway</body></html>");

    assertAll(() -> assertNotNull(response.json()),
        () -> assertEquals(0, response.json().length()),
        () -> assertNull(response.errorCode()),
        () -> assertEquals(502, response.status()));
  }

  @Test
  @DisplayName("an empty body is an empty object, not a null")
  void emptyBodyIsAnEmptyObject() {
    StripeResponse response = new StripeResponse(204, null);

    assertAll(() -> assertEquals("", response.body()),
        () -> assertNotNull(response.json()),
        () -> assertEquals(0, response.json().length()));
  }

  @Test
  @DisplayName("the body is parsed once and reused")
  void parsingHappensOnlyOnce() {
    StripeResponse response = new StripeResponse(200, PRICE_BODY);

    assertSame(response.json(), response.json(),
        "a fresh parse per call would be wasted work on every field read");
  }
}
