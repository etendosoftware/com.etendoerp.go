/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Phase;

/**
 * Specs for {@link ReconciliationResult}: the counts and the one-paragraph summary an operator
 * reads in the Classic popup and in Process Monitor.
 *
 * @covers com.etendoerp.go.payment.reconcile.ReconciliationResult
 */
public class ReconciliationResultTest {

  @Test
  public void anEmptyRunSaysNothingWasCheckedAndNothingChanged() {
    ReconciliationResult result = new ReconciliationResult(false);

    assertEquals("Run " + result.getRunId()
        + ": nothing to check. Everything is consistent; nothing was changed.", result.toString());
    assertFalse(result.hasFailures());
    assertEquals(0, result.repairs());
  }

  @Test
  public void aConsistentRunCountsWhatItCheckedAndSaysNothingChanged() {
    ReconciliationResult result = new ReconciliationResult(false);
    result.increment(Counter.CHECKOUTS_CHECKED);
    result.increment(Counter.SUBSCRIPTIONS_CHECKED);
    result.increment(Counter.SUBSCRIPTIONS_CHECKED);
    result.increment(Counter.AWAITING_REVIEW);

    assertEquals("Run " + result.getRunId() + ": 1 checkouts checked, 1 purchases still awaiting "
        + "review, 2 subscriptions checked. Everything is consistent; nothing was changed.",
        result.toString());
    assertEquals("Checks and awaiting-review are not writes", 0, result.repairs());
  }

  @Test
  public void aDryRunWithRepairsSaysDryRunAndThatNothingWasWritten() {
    ReconciliationResult result = new ReconciliationResult(true);
    result.increment(Counter.PAYMENTS_RECOVERED);
    result.increment(Counter.PROVISIONED);

    String summary = result.toString();

    assertTrue(summary, summary.startsWith("Dry run " + result.getRunId() + ": "));
    assertTrue(summary, summary.endsWith(". Dry run: nothing was written."));
    assertTrue(result.isDryRun());
    assertEquals(2, result.repairs());
  }

  /** QA D4: a dry run never reads as if it had done something. */
  @Test
  public void aDryRunWordsEveryWriteAsWhatItWouldDo() {
    ReconciliationResult dry = new ReconciliationResult(true);
    dry.increment(Counter.CHECKOUTS_EXPIRED);
    dry.increment(Counter.CHECKOUTS_EXPIRED);
    dry.increment(Counter.PROVISIONED);
    dry.increment(Counter.SUBSCRIPTIONS_CLOSED);
    dry.increment(Counter.NEEDS_REVIEW);
    dry.increment(Counter.CHECKOUTS_CHECKED);

    String summary = dry.toString();

    assertTrue(summary, summary.contains("would close 2 abandoned checkouts"));
    assertTrue(summary, summary.contains("would provision 1 environments"));
    assertTrue(summary, summary.contains("would close/expire 1 subscriptions"));
    assertTrue(summary, summary.contains("would park 1 purchases for review"));
    assertTrue("A count that is no write keeps its wording", summary.contains(
        "1 checkouts checked"));
    assertFalse(summary, summary.contains("abandoned checkouts closed"));

    ReconciliationResult real = new ReconciliationResult(false);
    real.increment(Counter.CHECKOUTS_EXPIRED);
    assertTrue(real.toString(), real.toString().contains("1 abandoned checkouts closed"));
  }

  @Test
  public void affectedItemsAreListedWithWhoWhatAndWhyInBothKindsOfRun() {
    ReconciliationResult dry = new ReconciliationResult(true);
    dry.item("req 1a2b3c4d", "Acme S.L.", "close abandoned checkout", "the session expired");
    ReconciliationResult real = new ReconciliationResult(false);
    real.item("sub_1", null, "close (expire) the subscription", "provider status canceled");

    assertEquals(java.util.List.of("req 1a2b3c4d (Acme S.L.): would close abandoned checkout"
        + " — the session expired"), dry.itemLines());
    assertEquals(java.util.List.of("sub_1: close (expire) the subscription — provider status "
        + "canceled"), real.itemLines());
  }

  @Test
  public void theItemListIsCappedWithACountOfTheRest() {
    ReconciliationResult result = new ReconciliationResult(false);
    for (int i = 0; i < ReconciliationResult.MAX_ITEM_LINES + 7; i++) {
      result.item("req " + i, null, "park for review", "NO_SESSION_ID");
    }

    java.util.List<String> lines = result.itemLines();

    assertEquals(ReconciliationResult.MAX_ITEM_LINES + 1, lines.size());
    assertEquals("+7 more", lines.get(lines.size() - 1));
  }

  /** The popup renders HTML: one line per item, and a company name can never inject markup. */
  @Test
  public void theHtmlMessageHasOneLinePerItemAndEscapesEveryName() {
    ReconciliationResult result = new ReconciliationResult(false);
    result.increment(Counter.CHECKOUTS_EXPIRED);
    result.item("req 1", "<b>Evil</b> & Co", "close abandoned checkout", "expired");
    result.item("req 2", "Plain", "close abandoned checkout", "expired");

    String html = result.toHtml();

    assertEquals(2, html.split("<br/>", -1).length - 1);
    assertTrue(html, html.contains("req 1 (&lt;b&gt;Evil&lt;/b&gt; &amp; Co)"));
    assertFalse(html, html.contains("<b>"));
    assertTrue(html, html.startsWith(org.apache.commons.lang3.StringUtils.replace(
        result.toString(), "'", "&#39;")));
  }

  @Test
  public void aShortRequestIdKeepsItsFirstEightCharacters() {
    assertEquals("req 1a2b3c4d", ReconciliationResult.shortRequest("1a2b3c4d-5e6f-7a8b"));
    assertEquals("req ", ReconciliationResult.shortRequest(null));
  }

  @Test
  public void everyWriteKindCountsAsARepair() {
    ReconciliationResult result = new ReconciliationResult(false);
    Counter[] writes = { Counter.PAYMENTS_RECOVERED, Counter.CHECKOUTS_EXPIRED,
        Counter.PROVISIONED, Counter.PROVISIONING_FAILED, Counter.NEEDS_REVIEW,
        Counter.SUBSCRIPTIONS_CLOSED, Counter.SUBSCRIPTIONS_CURRENT, Counter.PRICES_FILLED,
        Counter.PAUSE_COLLECTION_ALERTS, Counter.PAUSED_ALERTS, Counter.STALE_EVENTS_SWEPT };
    for (Counter counter : writes) {
      result.increment(counter);
    }
    result.increment(Counter.ABSENT_SUBSCRIPTIONS);
    result.increment(Counter.PROVIDER_ERRORS);

    assertEquals(writes.length, result.repairs());
    assertFalse(result.toString().contains("Everything is consistent"));
  }

  @Test
  public void aTrippedBreakerIsAFailureAndIsSpelledOut() {
    ReconciliationResult result = new ReconciliationResult(false);
    assertNull(result.getBreakerReason());

    result.tripBreaker("3 of 4 subscriptions are absent");

    assertTrue(result.hasFailures());
    assertEquals("3 of 4 subscriptions are absent", result.getBreakerReason());
    assertTrue(result.toString(), result.toString().contains(
        "Absent-subscription breaker tripped, no absent subscription was closed: 3 of 4 "
            + "subscriptions are absent"));
  }

  @Test
  public void errorsAreAFailureAndOnlyTheFirstFiveAreQuoted() {
    ReconciliationResult result = new ReconciliationResult(false);
    for (int i = 1; i <= 7; i++) {
      result.error("e" + i);
    }

    assertTrue(result.hasFailures());
    assertEquals(7, result.getErrors().size());
    String summary = result.toString();
    assertTrue(summary, summary.endsWith(". 7 error(s): e1; e2; e3; e4; e5."));
  }

  // ── Phase completeness (ETP-5048 review fixes) ────────────────────────────────────────────

  @Test
  public void everyPhaseIsCompleteUntilMarkedOtherwise() {
    ReconciliationResult result = new ReconciliationResult(false);

    for (Phase phase : Phase.values()) {
      assertTrue(phase.name(), result.isComplete(phase));
    }
    assertFalse(result.hasFailures());
  }

  @Test
  public void anIncompletePhaseIsAFailureAndKeepsItsFirstReason() {
    ReconciliationResult result = new ReconciliationResult(false);

    result.markIncomplete(Phase.SUBSCRIPTIONS, "the subscription list sweep stopped: 500");
    result.markIncomplete(Phase.SUBSCRIPTIONS, "direct reads capped at 50");

    assertFalse(result.isComplete(Phase.SUBSCRIPTIONS));
    assertTrue("Only the marked phase", result.isComplete(Phase.CHECKOUT_RECOVERY));
    assertTrue(result.hasFailures());
    String summary = result.toString();
    assertTrue(summary, summary.contains(". Not everything could be checked, so the run is "
        + "reported as failed (subscriptions: the subscription list sweep stopped: 500)"));
    assertFalse(summary, summary.contains("direct reads capped"));
    assertFalse("A run that could not check everything is never consistent",
        summary.contains("Everything is consistent"));
  }

  @Test
  public void aFailedProviderReadAloneIsAFailureAndNeverConsistent() {
    ReconciliationResult result = new ReconciliationResult(false);
    result.increment(Counter.CHECKOUTS_CHECKED);
    result.increment(Counter.PROVIDER_ERRORS);

    assertTrue(result.hasFailures());
    assertEquals("Run " + result.getRunId() + ": 1 checkouts checked, 1 provider reads that "
        + "failed.", result.toString());
  }

  @Test
  public void everyIncompletePhaseIsListedInTheSummary() {
    ReconciliationResult result = new ReconciliationResult(false);
    result.markIncomplete(Phase.CHECKOUT_RECOVERY, "it met errors");
    result.markIncomplete(Phase.SUBSCRIPTIONS, "the absent-subscription breaker tripped");

    assertTrue(result.toString(), result.toString().contains("(checkout recovery: it met "
        + "errors; subscriptions: the absent-subscription breaker tripped)"));
  }

  @Test
  public void aDryRunThatFailedDoesNotClaimConsistency() {
    ReconciliationResult result = new ReconciliationResult(true);
    result.increment(Counter.PROVIDER_ERRORS);

    String summary = result.toString();
    assertFalse(summary, summary.contains("Everything is consistent"));
    assertTrue(summary, summary.contains("Dry run: nothing was written"));
  }

  @Test
  public void theNewRepairKindsCountAsWrites() {
    ReconciliationResult result = new ReconciliationResult(false);
    result.increment(Counter.REACTIVATIONS_APPLIED);
    result.increment(Counter.STALE_EVENTS_PARKED);
    result.add(Counter.AWAITING_REVIEW, 3);
    result.add(Counter.AWAITING_REVIEW, 0);

    assertEquals(2, result.repairs());
    assertEquals("add() accumulates and ignores zero", 3, result.get(Counter.AWAITING_REVIEW));
  }

  @Test
  public void whatARunReReadIsRemembered() {
    ReconciliationResult result = new ReconciliationResult(false);

    result.noteCheckoutReread("req-1");
    result.noteSubscriptionReread("sub_1", "canceled");

    assertTrue(result.wasCheckoutReread("req-1"));
    assertFalse(result.wasCheckoutReread("req-2"));
    assertEquals("canceled", result.rereadSubscriptionStatus("sub_1"));
    assertEquals(null, result.rereadSubscriptionStatus("sub_2"));
  }
}
