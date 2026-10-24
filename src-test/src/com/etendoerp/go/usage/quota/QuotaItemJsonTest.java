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

package com.etendoerp.go.usage.quota;

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.NOW;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.quota;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The {@code QuotaItem} JSON of the ETP-5051 contract and the refusal sentence, both built from
 * catalog metadata only.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaItemJson
 */
class QuotaItemJsonTest {

  private static final Set<String> CONTRACT_KEYS = new TreeSet<>(Arrays.asList("resourceKey",
      "resourceName", "unitLabel", "includedQty", "enforcementMode", "warningThreshold", "period",
      "consumptionSource", "consumed", "verdict", "periodStart", "periodEnd"));

  private static QuotaPeriod period() {
    return QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);
  }

  private static Set<String> keys(JSONObject item) {
    Set<String> keys = new TreeSet<>();
    item.keys().forEachRemaining(key -> keys.add((String) key));
    return keys;
  }

  @Test
  void aMeasuredItemCarriesEveryContractField() throws Exception {
    QuotaDefinition definition = quota("POSTED_SALES_INVOICES", "block", 100L, 80L)
        .resourceName("Posted sales invoices").unitLabel("Inv")
        .source(ConsumptionSource.LIVE).build();

    JSONObject item = QuotaItemJson.of(
        new QuotaEvaluation(definition, "month", period(), 87L, QuotaVerdict.WARN));

    assertEquals(CONTRACT_KEYS, keys(item));
    assertEquals("POSTED_SALES_INVOICES", item.getString("resourceKey"));
    assertEquals("Posted sales invoices", item.getString("resourceName"));
    assertEquals("Inv", item.getString("unitLabel"));
    assertEquals(100L, item.getLong("includedQty"));
    assertEquals("block", item.getString("enforcementMode"));
    assertEquals(80L, item.getLong("warningThreshold"));
    assertEquals("month", item.getString("period"));
    assertEquals("live", item.getString("consumptionSource"));
    assertEquals(87L, item.getLong("consumed"));
    assertEquals("WARN", item.getString("verdict"));
    assertEquals(PERIOD_START.toInstant().toString(), item.getString("periodStart"));
    assertEquals(PERIOD_END.toInstant().toString(), item.getString("periodEnd"));
  }

  @Test
  void anUnmeasuredItemSendsJsonNullsNotAbsentKeys() throws Exception {
    QuotaDefinition definition = quota("USERS", "off", 3L, null).unitLabel(null).build();

    JSONObject item = QuotaItemJson.of(new QuotaEvaluation(definition, null, null, null, null));

    assertEquals(CONTRACT_KEYS, keys(item));
    for (String key : new String[] { "unitLabel", "warningThreshold", "period", "consumed",
        "verdict", "periodStart", "periodEnd" }) {
      assertTrue(item.isNull(key), key + " must be JSON null: " + item);
    }
    assertEquals("off", item.getString("enforcementMode"));
    assertEquals("aggregate", item.getString("consumptionSource"));
  }

  @Test
  void aResourceWithoutANameSendsAnEmptyNameAndOneWithoutAKeyOrNameSendsNull()
      throws Exception {
    QuotaDefinition unnamed = quota("DOCS", "block", 1L, null).resourceName(null).build();
    QuotaDefinition orphan = QuotaDefinition.builder().enforcementMode("block").build();

    assertEquals("", QuotaItemJson.of(new QuotaEvaluation(unnamed, null, null, null, null))
        .getString("resourceName"));
    assertTrue(QuotaItemJson.of(new QuotaEvaluation(orphan, null, null, null, null))
        .isNull("resourceName"));
  }

  @Test
  void theRefusalSentenceIsBuiltFromTheCatalogNameAndUnit() {
    QuotaDefinition definition = quota("POSTED_SALES_INVOICES", "block", 100L, null)
        .resourceName("Posted sales invoices").unitLabel("Inv").build();

    assertEquals("Quota exceeded: Posted sales invoices (100/100 Inv)",
        QuotaItemJson.refusalMessage(
            new QuotaEvaluation(definition, "month", period(), 100L, QuotaVerdict.DENY)));
  }

  @Test
  void theRefusalSentenceLeavesOutAMissingUnitAndFallsBackToTheKey() {
    QuotaDefinition definition = quota("DOCS", "block", 5L, null).resourceName(" ")
        .unitLabel("").build();

    assertEquals("Quota exceeded: DOCS (7/5)", QuotaItemJson.refusalMessage(
        new QuotaEvaluation(definition, null, period(), 7L, QuotaVerdict.DENY)));
  }

  @Test
  void aListKeepsItsOrderAndNullIsEmpty() throws Exception {
    JSONArray items = QuotaItemJson.ofAll(Arrays.asList(
        new QuotaEvaluation(quota("B", "warn", 1L, null).build(), null, null, null, null),
        new QuotaEvaluation(quota("A", "warn", 1L, null).build(), null, null, null, null)));

    assertEquals(2, items.length());
    assertEquals("B", items.getJSONObject(0).getString("resourceKey"));
    assertEquals("A", items.getJSONObject(1).getString("resourceKey"));
    assertEquals(0, QuotaItemJson.ofAll(null).length());
  }
}
