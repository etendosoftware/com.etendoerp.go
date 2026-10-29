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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ETP-5852 (M-1 / M-70): listMovements narrowed by an inclusive date range, paged newest first,
 * with the count in the range and the balance at the end of toDate.
 *
 * @covers com.etendoerp.go.schemaforge.MovementWindow
 */
class MovementWindowTest {

  /** Current balance 100; movements newest first: +50 on 10-03, -20 on 10-02, +70 on 10-01. */
  private static NeoResponse listed() throws Exception {
    JSONArray rows = new JSONArray()
        .put(row("c", "2026-10-03T00:00:00", 50))
        .put(row("b", "2026-10-02T00:00:00", -20))
        .put(row("a", "2026-10-01T00:00:00", 70));
    JSONObject data = new JSONObject().put("transactions", rows)
        .put("totals", new JSONObject().put("balance", 100));
    return NeoResponse.ok(new JSONObject().put("response", new JSONObject().put("data", data)));
  }

  private static JSONObject row(String id, String date, double amount) throws Exception {
    return new JSONObject().put("id", id).put("date", date).put("amount", amount);
  }

  static Stream<Arguments> windows() {
    return Stream.of(
        Arguments.of("no parameters: everything, current balance", "{}", "c,b,a", 3, "100"),
        Arguments.of("toDate excludes later days and rolls the balance back", "{\"toDate\":\"2026-10-02\"}",
            "b,a", 2, "50"),
        Arguments.of("both bounds inclusive", "{\"fromDate\":\"2026-10-02\",\"toDate\":\"2026-10-02\"}",
            "b", 1, "50"),
        Arguments.of("before any movement: opening balance", "{\"toDate\":\"2026-09-30\"}", "", 0,
            "0"),
        Arguments.of("fromDate only", "{\"fromDate\":\"2026-10-02\"}", "c,b", 2, "100"),
        Arguments.of("page newest first; totalRows counts the range", "{\"limit\":1,\"offset\":1}",
            "b", 3, "100"),
        Arguments.of("offset past the end", "{\"offset\":5}", "", 3, "100"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("windows")
  void narrowsPagesAndBalances(String label, String params, String ids, int total, String balance)
      throws Exception {
    JSONObject data = MovementWindow.apply(listed(), new JSONObject(params)).getBody()
        .getJSONObject("response").getJSONObject("data");

    JSONArray page = data.getJSONArray("transactions");
    StringBuilder got = new StringBuilder();
    for (int i = 0; i < page.length(); i++) {
      got.append(i == 0 ? "" : ",").append(page.getJSONObject(i).getString("id"));
    }
    assertEquals(ids, got.toString(), label);
    assertEquals(total, data.getInt("totalRows"), label);
    assertEquals(0, new BigDecimal(balance).compareTo(
        new BigDecimal(data.get("balanceAtToDate").toString())), label);
  }
}
