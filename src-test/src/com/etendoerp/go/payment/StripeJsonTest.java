/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs for {@link StripeJson#text}, the one way this module reads a string field from a Stripe
 * object (ETP-5047).
 *
 * <p>The contract that matters is the null-safety one: Jettison's {@code optString} turns a JSON
 * {@code null} into the literal text {@code "null"}, and Stripe sends JSON null for every unset
 * optional field. That once stored {@code "null"} as a Stripe subscription id. Every "nothing
 * here" shape must therefore read as the empty string, never as a value.
 *
 * @covers com.etendoerp.go.payment.StripeJson
 */
class StripeJsonTest {

  @Test
  void aNullObjectReadsEmpty() {
    assertEquals("", StripeJson.text(null, "subscription"));
  }

  @Test
  void anAbsentFieldReadsEmpty() throws Exception {
    assertEquals("", StripeJson.text(new JSONObject("{\"id\":\"cs_1\"}"), "subscription"));
  }

  @Test
  void aJsonNullReadsEmptyNeverAsTheWordNull() throws Exception {
    JSONObject session = new JSONObject("{\"subscription\":null}");

    // Sanity: what the helper exists to avoid.
    assertEquals("null", session.optString("subscription", ""));
    assertEquals("", StripeJson.text(session, "subscription"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", " ", "   \t " })
  void aBlankValueReadsEmpty(String blank) throws Exception {
    JSONObject price = new JSONObject().put("nickname", blank);

    assertEquals("", StripeJson.text(price, "nickname"));
  }

  @Test
  void aValueIsReadTrimmed() throws Exception {
    assertEquals("sub_1Q2w3E4r",
        StripeJson.text(new JSONObject("{\"subscription\":\"  sub_1Q2w3E4r \"}"), "subscription"));
  }

  /** The text "null" sent as a real string is a value like any other: only JSON null is absent. */
  @Test
  void theWordNullSentAsAStringIsKeptAsSent() throws Exception {
    assertEquals("null", StripeJson.text(new JSONObject("{\"nickname\":\"null\"}"), "nickname"));
  }

  /** Not a string field, so not what the helper is for; pinned as it behaves today. */
  @Test
  void aNumberOrBooleanReadsAsItsText() throws Exception {
    JSONObject price = new JSONObject("{\"unit_amount\":2900,\"livemode\":false}");

    assertEquals("2900", StripeJson.text(price, "unit_amount"));
    assertEquals("false", StripeJson.text(price, "livemode"));
  }

  /**
   * An expanded object in place of an id (Stripe's {@code expand[]}) reads as its JSON text, not
   * as its id: a caller that may receive an expanded object must read its {@code id} itself.
   * Pinned as it behaves today.
   */
  @Test
  void anExpandedObjectReadsAsItsJsonTextNotItsId() throws Exception {
    JSONObject invoice = new JSONObject("{\"customer\":{\"id\":\"cus_1\"}}");

    assertEquals("{\"id\":\"cus_1\"}", StripeJson.text(invoice, "customer"));
  }
}
