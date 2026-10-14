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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Remembers, for the length of one batch request, which create-cascade callouts produced nothing
 * that was kept, so the following operations of the same shape do not run them again (ETP-5676).
 *
 * <p><b>Why it is safe to skip.</b> On create, a callout's output is merged into the body only for
 * fields that are neither protected (the caller sent a non-blank value) nor suppressed
 * ({@code NeoHandler#protectedCreateCalloutFields}, ETP-5350). When every field a callout returned
 * fell into one of those two sets, and it raised no message and no error, running it changed
 * nothing at all. The products import is the measured case: {@code SL_TaxCategory_Org} returns the
 * tax category and product category the body already carries, {@code SL_Costing_Currency} returns
 * the currency the costing customization already put there — about 90 ms per row spent producing
 * values that are thrown away.</p>
 *
 * <p><b>The key is the callout's whole input shape that decides the discard</b>: the tab, the
 * trigger field, the trigger's value, and the exact set of fields that would be kept over any
 * callout output at that moment. A row that leaves one of those fields blank, or that triggers
 * with a different value, has a different key and runs the callout. The first observation of a
 * key decides it, and a key once seen producing a kept output is never marked skippable later.
 * The residual assumption — the callout's set of output fields does not depend on any other
 * field of the body — is what makes this an optimisation for bulk writes of uniform rows and why
 * it is confined to a batch: it is never consulted outside one, and dies with the request.</p>
 *
 * <p>Generic: nothing here, nor in the code that consults it, names a callout or an entity.</p>
 */
final class NeoCalloutDiscardMemory {

  private static final Logger log = LogManager.getLogger(NeoCalloutDiscardMemory.class);

  /** Key to "every output was discarded" (true) or "something was kept / reported" (false). */
  private final Map<String, Boolean> decisions = new HashMap<>();
  /** Skips per skippable key, for the end-of-batch summary. Insertion-ordered for a stable log. */
  private final Map<String, Integer> skips = new LinkedHashMap<>();

  /**
   * Builds the memory key for one callout invocation.
   *
   * @param tabId       the tab whose callout runs
   * @param field       the trigger field
   * @param value       the trigger field's value
   * @param keptFields  the fields that would keep their value over any callout output right now
   * @return the key
   */
  static String keyFor(String tabId, String field, Object value, Set<String> keptFields) {
    return tabId + '|' + field + '|' + value + '|' + new TreeSet<>(keptFields);
  }

  /**
   * Whether this invocation can be skipped. Counts the skip when it can.
   *
   * @param key the key from {@link #keyFor}
   * @return {@code true} when an earlier invocation with the same key had every output discarded
   */
  boolean shouldSkip(String key) {
    if (!Boolean.TRUE.equals(decisions.get(key))) {
      return false;
    }
    skips.merge(key, 1, Integer::sum);
    return true;
  }

  /**
   * Records the outcome of a callout that actually ran. Only the first outcome per key counts.
   *
   * @param key           the key from {@link #keyFor}
   * @param outputs       the fields the callout returned a value or a selection for
   * @param keptFields    the fields that kept their value over any output
   * @param reported      whether the callout raised a message or an error
   */
  void record(String key, Set<String> outputs, Set<String> keptFields, boolean reported) {
    if (decisions.containsKey(key)) {
      return;
    }
    boolean allDiscarded = !reported && keptFields.containsAll(outputs);
    decisions.put(key, allDiscarded);
    if (allDiscarded) {
      log.info("[NEO-PERF] batch callout skip learned key={} outputs={} — every output is kept "
          + "by a caller-sent or suppressed field; later operations with this key skip it",
          key, new TreeSet<>(outputs));
    }
  }

  /** Logs how many callout runs the batch saved, one line per skipped key. */
  void logSummary() {
    for (Map.Entry<String, Integer> entry : skips.entrySet()) {
      log.info("[NEO-PERF] batch callout skipped {} time(s) key={}", entry.getValue(),
          entry.getKey());
    }
  }
}
