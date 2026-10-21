/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ETP-5048 — what one billing reconciliation run did, counted per kind, plus the errors it met.
 *
 * <p>The summary is what the operator reads in the Classic popup and in Process Monitor, so every
 * counter that can be non-zero on a healthy environment is spelled out, and a run with nothing to
 * do says exactly that.
 */
public final class ReconciliationResult {

  /** One counted kind. The label is the operator-facing wording. */
  public enum Counter {
    CHECKOUTS_CHECKED("checkouts checked"),
    PAYMENTS_RECOVERED("payments recovered from the provider"),
    CHECKOUTS_EXPIRED("abandoned checkouts closed"),
    PROVISIONED("environments provisioned"),
    REACTIVATIONS_APPLIED("re-subscriptions applied"),
    PROVISIONING_FAILED("provisioning attempts failed"),
    NEEDS_REVIEW("new purchases needing review"),
    AWAITING_REVIEW("purchases still awaiting review"),
    SUBSCRIPTIONS_CHECKED("subscriptions checked"),
    SUBSCRIPTIONS_CLOSED("subscriptions closed"),
    SUBSCRIPTIONS_CURRENT("subscriptions set back to current"),
    PRICES_FILLED("subscription prices filled"),
    PAUSE_COLLECTION_ALERTS("paused-collection alerts"),
    PAUSED_ALERTS("paused subscriptions needing review"),
    ABSENT_SUBSCRIPTIONS("subscriptions absent at the provider"),
    // ETP-5046-TRANSITIONAL-FALLBACK — tenants with no subscription row, counted on their own so
    // the cutover's progress shows; delete with the preference route in Phase F.
    PREFERENCE_ROUTE_CHECKED("tenants on the preference route checked"),
    PREFERENCE_ROUTE_CORRECTED("tenants on the preference route corrected"),
    STALE_EVENTS_SWEPT("stuck provider events superseded"),
    PROVIDER_ERRORS("provider reads that failed");

    private final String label;

    Counter(String label) {
      this.label = label;
    }

    /** @return the operator-facing wording */
    public String label() {
      return label;
    }
  }

  private final String runId = UUID.randomUUID().toString().substring(0, 8);
  private final boolean dryRun;
  private final Map<Counter, Integer> counts = new EnumMap<>(Counter.class);
  private final List<String> errors = new ArrayList<>();
  private String breakerReason;

  ReconciliationResult(boolean dryRun) {
    this.dryRun = dryRun;
  }

  void increment(Counter counter) {
    counts.merge(counter, 1, Integer::sum);
  }

  void error(String message) {
    errors.add(message);
  }

  void tripBreaker(String reason) {
    this.breakerReason = reason;
  }

  /**
   * Returns a short id of this run, part of the event ids of its non-terminal repairs.
   *
   * @return the run id
   */
  public String getRunId() {
    return runId;
  }

  /**
   * Tells whether this was a dry run, which writes nothing.
   *
   * @return whether nothing was written
   */
  public boolean isDryRun() {
    return dryRun;
  }

  /**
   * Returns how many of one kind this run counted.
   *
   * @param counter a kind
   * @return the count
   */
  public int get(Counter counter) {
    return counts.getOrDefault(counter, 0);
  }

  /**
   * Returns the item-level errors this run met, in order.
   *
   * @return the errors
   */
  public List<String> getErrors() {
    return Collections.unmodifiableList(errors);
  }

  /**
   * Returns why the absent-subscription breaker tripped.
   *
   * @return the reason, or null when it did not trip
   */
  public String getBreakerReason() {
    return breakerReason;
  }

  /**
   * Tells whether the run must be reported as failed: it met errors or tripped the breaker.
   *
   * @return whether the run failed
   */
  public boolean hasFailures() {
    return breakerReason != null || !errors.isEmpty();
  }

  /**
   * Counts the writes (repairs plus alert rows) the run made or, in a dry run, would make.
   *
   * @return the number of writes
   */
  public int repairs() {
    return get(Counter.PAYMENTS_RECOVERED) + get(Counter.CHECKOUTS_EXPIRED)
        + get(Counter.PROVISIONED) + get(Counter.REACTIVATIONS_APPLIED)
        + get(Counter.PROVISIONING_FAILED) + get(Counter.NEEDS_REVIEW)
        + get(Counter.SUBSCRIPTIONS_CLOSED) + get(Counter.SUBSCRIPTIONS_CURRENT)
        + get(Counter.PRICES_FILLED) + get(Counter.PAUSE_COLLECTION_ALERTS)
        + get(Counter.PAUSED_ALERTS)
        + get(Counter.STALE_EVENTS_SWEPT);
  }

  /**
   * Builds the one-paragraph summary an operator reads.
   *
   * @return the summary
   */
  @Override
  public String toString() {
    StringBuilder summary = new StringBuilder();
    summary.append(dryRun ? "Dry run " : "Run ").append(runId).append(": ");
    List<String> parts = new ArrayList<>();
    for (Counter counter : Counter.values()) {
      int value = get(counter);
      if (value > 0) {
        parts.add(value + " " + counter.label());
      }
    }
    if (parts.isEmpty()) {
      summary.append("nothing to check");
    } else {
      summary.append(String.join(", ", parts));
    }
    if (repairs() == 0 && breakerReason == null && errors.isEmpty()) {
      summary.append(". Everything is consistent; nothing was changed");
    } else if (dryRun) {
      summary.append(". Dry run: nothing was written");
    }
    if (breakerReason != null) {
      summary.append(". Absent-subscription breaker tripped, no absent subscription was closed: ")
          .append(breakerReason);
    }
    if (!errors.isEmpty()) {
      summary.append(". ").append(errors.size()).append(" error(s): ")
          .append(String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
    }
    return summary.append('.').toString();
  }
}
