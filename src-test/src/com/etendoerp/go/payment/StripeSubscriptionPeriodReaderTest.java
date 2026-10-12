/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs for {@link StripeSubscriptionPeriodReader} (ETP-5047): the provider read that gives a
 * just-opened subscription row its billing period.
 *
 * <p>The property that matters most is the one nothing at runtime would report: <b>it never
 * throws</b>. It runs right after a paid onboarding committed, and a failure here must cost the
 * row its period, never the onboarding its result. Every failure branch is therefore asserted to
 * answer null rather than to raise.
 *
 * @covers com.etendoerp.go.payment.StripeSubscriptionPeriodReader
 */
class StripeSubscriptionPeriodReaderTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String SUBSCRIPTION_ID = "sub_1Q2w3E4r";
  private static final long START = 1_790_000_000L;
  private static final long END = 1_792_678_400L;

  /** Records every path asked of the provider and answers whatever the test staged. */
  private static final class RecordingStripeApiClient implements StripeApiClient {
    private final List<String> requestedPaths = new ArrayList<>();
    private StripeResponse answer = new StripeResponse(200, "{}");
    private StripeTransportException transportFailure;
    private RuntimeException unexpectedFailure;

    @Override
    public StripeResponse get(String path) throws StripeTransportException {
      requestedPaths.add(path);
      if (transportFailure != null) {
        throw transportFailure;
      }
      if (unexpectedFailure != null) {
        throw unexpectedFailure;
      }
      return answer;
    }

    @Override
    public StripeResponse postForm(String path, String formBody) {
      throw new UnsupportedOperationException("the period read never posts");
    }

    RecordingStripeApiClient answering(int status, String body) {
      this.answer = new StripeResponse(status, body);
      return this;
    }
  }

  private final RecordingStripeApiClient stripe = new RecordingStripeApiClient();

  private StripeSubscriptionPeriodReader reader() {
    return new StripeSubscriptionPeriodReader(stripe, () -> true);
  }

  @Test
  void readsTheTopLevelPeriodOfTheSubscription() {
    stripe.answering(200, "{\"id\":\"" + SUBSCRIPTION_ID + "\",\"current_period_start\":" + START
        + ",\"current_period_end\":" + END + "}");

    SubscriptionLifecycleApplier.BillingPeriod period = reader().read(CLIENT_ID, SUBSCRIPTION_ID);

    assertEquals(Instant.ofEpochSecond(START), period.start());
    assertEquals(Instant.ofEpochSecond(END), period.end());
    assertEquals(List.of("/v1/subscriptions/" + SUBSCRIPTION_ID), stripe.requestedPaths);
  }

  @Test
  void readsThePeriodFromTheFirstItemOnApi20250331() {
    // From API 2025-03-31 the period lives on the subscription item, not the subscription.
    stripe.answering(200, "{\"id\":\"" + SUBSCRIPTION_ID + "\",\"items\":{\"data\":[{"
        + "\"current_period_start\":" + START + ",\"current_period_end\":" + END + "}]}}");

    SubscriptionLifecycleApplier.BillingPeriod period = reader().read(CLIENT_ID, SUBSCRIPTION_ID);

    assertEquals(Instant.ofEpochSecond(START), period.start());
    assertEquals(Instant.ofEpochSecond(END), period.end());
  }

  @Test
  void encodesTheIdIntoThePath() {
    stripe.answering(404, "{}");

    reader().read(CLIENT_ID, " sub_a/../b ");

    assertEquals(List.of("/v1/subscriptions/sub_a%2F..%2Fb"), stripe.requestedPaths);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   " })
  void aBlankIdAsksTheProviderNothing(String subscriptionId) {
    assertNull(reader().read(CLIENT_ID, subscriptionId));
    assertTrue(stripe.requestedPaths.isEmpty());
  }

  @Test
  void withoutASecretKeyTheProviderIsNotCalled() {
    StripeSubscriptionPeriodReader unconfigured =
        new StripeSubscriptionPeriodReader(stripe, () -> false);

    assertNull(unconfigured.read(CLIENT_ID, SUBSCRIPTION_ID));
    assertTrue(stripe.requestedPaths.isEmpty());
  }

  @Test
  void aTransportFailureAnswersNullInsteadOfThrowing() {
    stripe.transportFailure = new StripeTransportException("Payment provider call GET "
        + "/v1/subscriptions/" + SUBSCRIPTION_ID + " did not complete");

    assertNull(reader().read(CLIENT_ID, SUBSCRIPTION_ID));
  }

  @Test
  void anUnexpectedFailureAnswersNullInsteadOfThrowing() {
    stripe.unexpectedFailure = new IllegalStateException("boom");

    assertNull(reader().read(CLIENT_ID, SUBSCRIPTION_ID));
  }

  @Test
  void aRejectionAnswersNull() {
    stripe.answering(404, "{\"error\":{\"code\":\"resource_missing\","
        + "\"message\":\"No such subscription\"}}");

    assertNull(reader().read(CLIENT_ID, SUBSCRIPTION_ID));
  }

  @Test
  void anAnswerWithoutAWholePeriodAnswersNull() {
    stripe.answering(200, "{\"id\":\"" + SUBSCRIPTION_ID + "\",\"current_period_end\":" + END
        + "}");

    assertNull(reader().read(CLIENT_ID, SUBSCRIPTION_ID));
  }

  @Test
  void anUnparseableBodyAnswersNull() {
    stripe.answering(200, "<html>proxy</html>");

    assertNull(reader().read(CLIENT_ID, SUBSCRIPTION_ID));
  }

  @Test
  void aPeriodEndingBeforeItStartsAnswersNull() {
    // ETGO_SUB_PERIOD_CHK (END >= START) would refuse it at flush.
    stripe.answering(200, "{\"current_period_start\":" + END + ",\"current_period_end\":" + START
        + "}");

    assertNull(reader().read(CLIENT_ID, SUBSCRIPTION_ID));
  }

  @Test
  void theProductionReaderUsesShortTimeouts() {
    // Shorter than the checkout budget (10 s connect / 20 s read): this read only enriches a row.
    assertTrue(StripeSubscriptionPeriodReader.CONNECT_TIMEOUT_MS <= 5_000);
    assertTrue(StripeSubscriptionPeriodReader.READ_TIMEOUT_MS <= 10_000);
  }
}
