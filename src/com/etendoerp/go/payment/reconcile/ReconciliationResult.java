/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.email.render.EmailEscape;

/**
 * ETP-5048 — what one billing reconciliation run did, counted per kind, plus the errors it met.
 *
 * <p>The summary is what the operator reads in the Classic popup and in Process Monitor, so every
 * counter that can be non-zero on a healthy environment is spelled out, and a run with nothing to
 * do says exactly that.
 */
public final class ReconciliationResult {

  /**
   * One counted kind. The label is the operator-facing wording of a real run; a kind that is a
   * write also has the wording of a dry run ("would close N …"), so a dry run never reads as if
   * it had done something.
   */
  public enum Counter {
    CHECKOUTS_CHECKED("checkouts checked", null),
    PAYMENTS_RECOVERED("payments recovered from the provider",
        "would record %d payments recovered from the provider"),
    CHECKOUTS_EXPIRED("abandoned checkouts closed", "would close %d abandoned checkouts"),
    PROVISIONED("environments provisioned", "would provision %d environments"),
    REACTIVATIONS_APPLIED("re-subscriptions applied", "would apply %d re-subscriptions"),
    PROVISIONING_FAILED("provisioning attempts failed", null),
    NEEDS_REVIEW("new purchases needing review", "would park %d purchases for review"),
    AWAITING_REVIEW("purchases still awaiting review", null),
    SUBSCRIPTIONS_CHECKED("subscriptions checked", null),
    SUBSCRIPTIONS_CLOSED("subscriptions closed", "would close/expire %d subscriptions"),
    SUBSCRIPTIONS_CURRENT("subscriptions set back to current",
        "would set %d subscriptions back to current"),
    PRICES_FILLED("subscription prices filled", "would fill %d subscription prices"),
    PAUSE_COLLECTION_ALERTS("paused-collection alerts", "would raise %d paused-collection alerts"),
    PAUSED_ALERTS("paused subscriptions needing review",
        "would raise %d paused-subscription review alerts"),
    ABSENT_SUBSCRIPTIONS("subscriptions absent at the provider", null),
    // ETP-5046-TRANSITIONAL-FALLBACK — tenants with no subscription row, counted on their own so
    // the cutover's progress shows; delete with the preference route in Phase F.
    PREFERENCE_ROUTE_CHECKED("tenants on the preference route checked", null),
    PREFERENCE_ROUTE_CORRECTED("tenants on the preference route corrected",
        "would correct %d tenants on the preference route"),
    STALE_EVENTS_SWEPT("stuck provider events superseded",
        "would supersede %d stuck provider events"),
    STALE_EVENTS_PARKED("stuck provider events needing review",
        "would park %d stuck provider events for review"),
    PROVIDER_ERRORS("provider reads that failed", null);

    private final String label;
    private final String dryRunTemplate;

    Counter(String label, String dryRunTemplate) {
      this.label = label;
      this.dryRunTemplate = dryRunTemplate;
    }

    /** @return the operator-facing wording of a real run */
    public String label() {
      return label;
    }

    /**
     * Words a count for the summary.
     *
     * @param count the count
     * @param dryRun whether the run wrote nothing
     * @return e.g. {@code 2 abandoned checkouts closed}, or {@code would close 2 abandoned
     *     checkouts} in a dry run
     */
    public String describe(int count, boolean dryRun) {
      return dryRun && dryRunTemplate != null ? String.format(dryRunTemplate, count)
          : count + " " + label;
    }
  }

  /** At most this many item lines are listed; the rest is summed up as "+N more". */
  static final int MAX_ITEM_LINES = 20;

  /**
   * A phase whose re-read of the provider state the stuck-event sweep depends on. A phase that
   * met an error, a failed provider read or a cap did not re-read everything, so no stuck event of
   * its family may be superseded on its strength.
   */
  public enum Phase {
    CHECKOUT_RECOVERY("checkout recovery"),
    PROVISIONING_REPAIR("provisioning repair"),
    SUBSCRIPTIONS("subscriptions");

    private final String label;

    Phase(String label) {
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
  private final Map<Phase, String> incomplete = new EnumMap<>(Phase.class);
  private final Set<String> rereadCheckouts = new HashSet<>();
  private final Map<String, String> rereadSubscriptions = new HashMap<>();
  private final List<String> items = new ArrayList<>();

  ReconciliationResult(boolean dryRun) {
    this.dryRun = dryRun;
  }

  void increment(Counter counter) {
    counts.merge(counter, 1, Integer::sum);
  }

  void add(Counter counter, int amount) {
    if (amount > 0) {
      counts.merge(counter, amount, Integer::sum);
    }
  }

  /**
   * Lists one affected item under the counts, in dry runs and real runs alike: what it is, whose,
   * what the run did (or would do) and why. Only ids, the company name and short reasons — the
   * same facts the billing-event rows show, never raw user input.
   *
   * @param subject e.g. {@code req 1a2b3c4d} or {@code sub_123}
   * @param name the company or client name, or null
   * @param action in the imperative, e.g. {@code close abandoned checkout}; a dry run prefixes
   *     it with "would"
   * @param reason why, e.g. {@code provider session expired}
   */
  void item(String subject, String name, String action, String reason) {
    StringBuilder line = new StringBuilder(StringUtils.defaultIfBlank(subject, "-"));
    if (StringUtils.isNotBlank(name)) {
      line.append(" (").append(name.trim()).append(')');
    }
    line.append(": ").append(dryRun ? "would " : "").append(action);
    if (StringUtils.isNotBlank(reason)) {
      line.append(" — ").append(reason);
    }
    items.add(line.toString());
  }

  /**
   * The item lines an operator reads under the counts, capped at {@link #MAX_ITEM_LINES} plus a
   * {@code +N more} line.
   *
   * @return the lines, empty when nothing was affected
   */
  public List<String> itemLines() {
    if (items.size() <= MAX_ITEM_LINES) {
      return Collections.unmodifiableList(items);
    }
    List<String> lines = new ArrayList<>(items.subList(0, MAX_ITEM_LINES));
    lines.add("+" + (items.size() - MAX_ITEM_LINES) + " more");
    return lines;
  }

  /**
   * The summary and its item lines as HTML for the Classic popup, one line each. Every piece of
   * text is escaped, a company name included.
   *
   * @return the HTML message
   */
  public String toHtml() {
    StringBuilder html = new StringBuilder(EmailEscape.escapeHtml(toString()));
    for (String line : itemLines()) {
      html.append("<br/>").append(EmailEscape.escapeHtml(line));
    }
    return html.toString();
  }

  /** A checkout request id in the short form the item lines use. */
  static String shortRequest(String requestId) {
    return "req " + StringUtils.left(StringUtils.defaultString(requestId), 8);
  }

  void error(String message) {
    errors.add(message);
  }

  void tripBreaker(String reason) {
    this.breakerReason = reason;
  }

  /** Records that a phase did not re-read everything; the first reason is kept. */
  void markIncomplete(Phase phase, String reason) {
    incomplete.putIfAbsent(phase, reason);
  }

  /** Records a checkout request whose provider session this run read. */
  void noteCheckoutReread(String requestId) {
    rereadCheckouts.add(requestId);
  }

  /** Records a subscription this run read and reconciled, with the status the provider gave. */
  void noteSubscriptionReread(String subscriptionId, String status) {
    rereadSubscriptions.put(subscriptionId, status);
  }

  boolean wasCheckoutReread(String requestId) {
    return rereadCheckouts.contains(requestId);
  }

  /** @return the provider status this run read for the subscription, or null when not read */
  String rereadSubscriptionStatus(String subscriptionId) {
    return rereadSubscriptions.get(subscriptionId);
  }

  /**
   * Tells whether a phase re-read everything it should have.
   *
   * @param phase a phase
   * @return whether it completed without errors, failed reads or caps
   */
  public boolean isComplete(Phase phase) {
    return !incomplete.containsKey(phase);
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
   * Tells whether the run must be reported as failed: it met errors, a provider read failed, a
   * phase could not re-read everything, or the breaker tripped. A run that could not read the
   * provider never reports itself consistent.
   *
   * @return whether the run failed
   */
  public boolean hasFailures() {
    return breakerReason != null || !errors.isEmpty() || get(Counter.PROVIDER_ERRORS) > 0
        || !incomplete.isEmpty();
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
        + get(Counter.STALE_EVENTS_SWEPT) + get(Counter.STALE_EVENTS_PARKED);
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
        parts.add(counter.describe(value, dryRun));
      }
    }
    if (parts.isEmpty()) {
      summary.append("nothing to check");
    } else {
      summary.append(String.join(", ", parts));
    }
    if (repairs() == 0 && !hasFailures()) {
      summary.append(". Everything is consistent; nothing was changed");
    } else if (dryRun) {
      summary.append(". Dry run: nothing was written");
    }
    if (!incomplete.isEmpty()) {
      List<String> notes = new ArrayList<>();
      incomplete.forEach((phase, reason) -> notes.add(phase.label() + ": " + reason));
      summary.append(". Not everything could be checked, so the run is reported as failed (")
          .append(String.join("; ", notes)).append(')');
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
