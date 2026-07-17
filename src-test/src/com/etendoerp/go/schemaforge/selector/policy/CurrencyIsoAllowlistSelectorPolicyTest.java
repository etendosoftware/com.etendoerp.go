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

package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link CurrencyIsoAllowlistSelectorPolicy}.
 *
 * <p>{@code resolveFilter} is a pure function, no DB access is needed. Guards the simplified UI's
 * currency restriction (EUR/USD/GBP only) across every {@code Currency} TableDir selector, such as
 * the financial account creation form's currency picker.</p>
 */
public class CurrencyIsoAllowlistSelectorPolicyTest {

  private static final String ENTITY = "Currency";

  private final CurrencyIsoAllowlistSelectorPolicy policy = new CurrencyIsoAllowlistSelectorPolicy();

  @Test
  public void supportsOnlyCurrency() {
    assertTrue(policy.supports(ENTITY));
    assertFalse(policy.supports("FIN_Financial_Account"));
    assertFalse(policy.supports(null));
  }

  @Test
  public void producesIsoAllowlistFilterWithGivenAlias() {
    String filter = policy.resolveFilter(ENTITY, Collections.emptyMap(), "c");

    assertEquals("c.iSOCode in ('EUR', 'USD', 'GBP')", filter);
  }

  @Test
  public void ignoresContextParams() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("AD_Org_ID", "0");

    String filter = policy.resolveFilter(ENTITY, ctx, "e");

    assertEquals("e.iSOCode in ('EUR', 'USD', 'GBP')", filter);
    assertEquals(filter, policy.resolveFilter(ENTITY, null, "e"));
  }

  @Test
  public void blankAliasFallsBackToDefault() {
    String filter = policy.resolveFilter(ENTITY, null, "  ");

    assertEquals("e.iSOCode in ('EUR', 'USD', 'GBP')", filter);
  }

  @Test
  public void unsupportedEntityReturnsNull() {
    assertNull(policy.resolveFilter("BusinessPartner", null, "e"));
  }
}
