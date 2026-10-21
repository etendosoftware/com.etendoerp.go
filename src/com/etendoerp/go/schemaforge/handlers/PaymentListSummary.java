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

package com.etendoerp.go.schemaforge.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoCurrencyTotals;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.Converted;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.Subtotal;
import com.etendoerp.go.schemaforge.NeoCurrencyTotals.TargetCurrency;
import com.etendoerp.go.schemaforge.NeoListAggregateQuery;

/**
 * Whole-set summary behind the Cobro/Pago list sidebar (ETP-5778). One grouped query over the same
 * filters as the list, folded into the organization currency at today's rate.
 *
 * <p>Buckets (voided payments are excluded everywhere):
 * <ul>
 *   <li><b>total</b>: deposited payments, i.e. {@link #DEPOSITED_STATUSES};</li>
 *   <li><b>pending</b>: neither deposited nor voided; {@code draftCount} is its count;</li>
 *   <li><b>byMethod</b>: deposited, converted per payment method;</li>
 *   <li><b>byCurrency</b>: deposited, in the ORIGINAL currency (never approximate), organization
 *       currency first, then by amount descending.</li>
 * </ul>
 * {@code rowCount} is total + pending: every non-voided row of the filtered set.
 *
 * <p>{@code approximate} follows the Financial Accounts rule ({@link NeoCurrencyTotals}): a
 * non-zero subtotal in another currency was converted. Each bucket and each method rounds its own
 * subtotals, so methods may differ from the total by a cent.
 */
final class PaymentListSummary {

  /**
   * Confirmed payment states. Mirrors {@code DEPOSITED_STATUSES} in
   * {@code schema_forge/tools/app-shell/src/windows/custom/shared/paymentStatuses.js}; change both.
   * PPM ("Payment Made") counts as deposited because the grid does; ETGOERR lands in pending.
   */
  static final Set<String> DEPOSITED_STATUSES = Set.of("RPR", "RPPC", "RDNC", "PPM", "PWNC",
      "RPAE");

  /** Voided payments: left out of every bucket. */
  static final String VOID_STATUS = "RPVOID";

  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_COUNT = "count";
  private static final String KEY_TOTAL = "total";
  private static final String KEY_APPROXIMATE = "approximate";
  private static final String KEY_LABEL = "label";

  /** Server-side constant select for {@link NeoListAggregateQuery#groupBy}; alias {@code e}. */
  static final String SELECT = "e.currency.id, e.currency.iSOCode, e.paymentMethod.id, "
      + "e.paymentMethod.name, e.status, sum(e.amount), count(e.id)";

  static final String GROUP_BY = "e.currency.id, e.currency.iSOCode, e.paymentMethod.id, "
      + "e.paymentMethod.name, e.status";

  private PaymentListSummary() {
  }

  /** One group of the grouped query: a currency, a method and a status. */
  static final class Group {
    final String currencyId;
    final String currencyIso;
    final String methodId;
    final String methodLabel;
    final String status;
    final BigDecimal amount;
    final long count;

    Group(String currencyId, String currencyIso, String methodId, String methodLabel,
        String status, BigDecimal amount, long count) {
      this.currencyId = currencyId;
      this.currencyIso = currencyIso;
      this.methodId = methodId;
      this.methodLabel = methodLabel;
      this.status = status;
      this.amount = amount == null ? BigDecimal.ZERO : amount;
      this.count = count;
    }
  }

  /** Runs the grouped query over the filtered set. */
  static List<Group> load(NeoListAggregateQuery query) {
    List<Group> groups = new ArrayList<>();
    for (Object[] row : query.groupBy(SELECT, GROUP_BY)) {
      groups.add(new Group((String) row[0], (String) row[1], (String) row[2], (String) row[3],
          (String) row[4], (BigDecimal) row[5], ((Number) row[6]).longValue()));
    }
    return groups;
  }

  /**
   * Builds the {@code summary} object.
   *
   * @param groups the grouped rows
   * @param org the organization currency, or {@code null} when unresolvable (raw sums)
   * @param converter folds subtotals into {@code org}
   */
  static JSONObject build(List<Group> groups, TargetCurrency org, NeoCurrencyTotals converter)
      throws JSONException {
    List<Group> deposited = new ArrayList<>();
    List<Group> pending = new ArrayList<>();
    for (Group group : groups) {
      if (VOID_STATUS.equals(group.status)) {
        continue;
      }
      (DEPOSITED_STATUSES.contains(group.status) ? deposited : pending).add(group);
    }

    Converted total = converter.convert(subtotals(deposited), org);
    Converted pend = converter.convert(subtotals(pending), org);
    long totalCount = countOf(deposited);
    long pendingCount = countOf(pending);

    JSONObject summary = new JSONObject();
    summary.put("orgCurrencyIso", total.iso == null ? JSONObject.NULL : total.iso);
    summary.put("rowCount", totalCount + pendingCount);
    summary.put(KEY_TOTAL, bucket(total, totalCount));
    summary.put("pending", bucket(pend, pendingCount));
    summary.put("draftCount", pendingCount);
    summary.put("byMethod", byMethod(deposited, org, converter));
    summary.put("byCurrency", byCurrency(deposited, org));
    return summary;
  }

  private static JSONObject bucket(Converted converted, long count) throws JSONException {
    JSONObject bucket = new JSONObject();
    bucket.put(KEY_AMOUNT, converted.total);
    bucket.put(KEY_APPROXIMATE, converted.approximate);
    bucket.put(KEY_COUNT, count);
    JSONArray missing = new JSONArray();
    converted.missingRateCurrencies.forEach(missing::put);
    bucket.put("missingRateCurrencies", missing);
    return bucket;
  }

  private static JSONArray byMethod(List<Group> deposited, TargetCurrency org,
      NeoCurrencyTotals converter) throws JSONException {
    Map<String, List<Group>> byMethod = new LinkedHashMap<>();
    for (Group group : deposited) {
      byMethod.computeIfAbsent(group.methodId, k -> new ArrayList<>()).add(group);
    }
    List<JSONObject> rows = new ArrayList<>();
    for (Map.Entry<String, List<Group>> entry : byMethod.entrySet()) {
      List<Group> methodGroups = entry.getValue();
      Converted converted = converter.convert(subtotals(methodGroups), org);
      JSONObject row = new JSONObject();
      row.put("id", entry.getKey());
      row.put(KEY_LABEL, methodGroups.get(0).methodLabel);
      row.put(KEY_AMOUNT, converted.total);
      row.put(KEY_APPROXIMATE, converted.approximate);
      row.put(KEY_COUNT, countOf(methodGroups));
      rows.add(row);
    }
    rows.sort(Comparator.comparing((JSONObject r) -> (BigDecimal) r.opt(KEY_AMOUNT)).reversed()
        .thenComparing(r -> r.optString(KEY_LABEL, null), Comparator.nullsLast(String::compareTo)));
    return new JSONArray(rows);
  }

  private static JSONArray byCurrency(List<Group> deposited, TargetCurrency org)
      throws JSONException {
    Map<String, List<Group>> byCurrency = new LinkedHashMap<>();
    for (Group group : deposited) {
      byCurrency.computeIfAbsent(group.currencyId, k -> new ArrayList<>()).add(group);
    }
    List<Map.Entry<String, List<Group>>> entries = new ArrayList<>(byCurrency.entrySet());
    Comparator<Map.Entry<String, List<Group>>> byAmountDesc = Comparator
        .comparing((Map.Entry<String, List<Group>> e) -> amountOf(e.getValue())).reversed();
    entries.sort(Comparator
        .comparing((Map.Entry<String, List<Group>> e) -> !isOrg(org, e.getValue().get(0)))
        .thenComparing(byAmountDesc)
        .thenComparing(e -> e.getValue().get(0).currencyIso,
            Comparator.nullsLast(String::compareTo)));
    JSONArray rows = new JSONArray();
    for (Map.Entry<String, List<Group>> entry : entries) {
      JSONObject row = new JSONObject();
      row.put("currencyIso", entry.getValue().get(0).currencyIso);
      row.put(KEY_TOTAL, amountOf(entry.getValue()));
      row.put(KEY_COUNT, countOf(entry.getValue()));
      rows.put(row);
    }
    return rows;
  }

  private static boolean isOrg(TargetCurrency org, Group group) {
    return org != null && org.matches(group.currencyId, group.currencyIso);
  }

  /** Sums the groups per currency, keeping first-seen order. */
  private static List<Subtotal> subtotals(List<Group> groups) {
    Map<String, Subtotal> byCurrency = new LinkedHashMap<>();
    for (Group group : groups) {
      byCurrency.merge(group.currencyId,
          new Subtotal(group.currencyId, group.currencyIso, group.amount),
          (a, b) -> new Subtotal(a.currencyId, a.currencyIso, a.total.add(b.total)));
    }
    return new ArrayList<>(byCurrency.values());
  }

  private static BigDecimal amountOf(List<Group> groups) {
    BigDecimal sum = BigDecimal.ZERO;
    for (Group group : groups) {
      sum = sum.add(group.amount);
    }
    return sum;
  }

  private static long countOf(List<Group> groups) {
    long count = 0;
    for (Group group : groups) {
      count += group.count;
    }
    return count;
  }
}
