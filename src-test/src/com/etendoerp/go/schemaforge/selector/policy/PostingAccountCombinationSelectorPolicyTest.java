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
package com.etendoerp.go.schemaforge.selector.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link PostingAccountCombinationSelectorPolicy} (ETP-5681).
 *
 * <p>{@code resolveFilter} is a pure function, no DB access is needed. Guards that every generic
 * {@code C_ValidCombination} selector lists only combinations whose account is an active posting
 * (non-summary) account, and that the chart of accounts' own {@code ElementValue} selectors — whose
 * "parent account" field must list summary accounts — are left alone.</p>
 *
 * @covers com.etendoerp.go.schemaforge.selector.policy.PostingAccountCombinationSelectorPolicy
 */
public class PostingAccountCombinationSelectorPolicyTest {

  private static final String COMBINATION_ENTITY = "FinancialMgmtAccountingCombination";

  private final PostingAccountCombinationSelectorPolicy policy = new PostingAccountCombinationSelectorPolicy();

  @Test
  public void supportsOnlyAccountingCombination() {
    assertTrue(policy.supports(COMBINATION_ENTITY));
    assertFalse("the chart of accounts parent field must keep listing summary accounts",
        policy.supports("FinancialMgmtElementValue"));
    assertFalse(policy.supports("Currency"));
    assertFalse(policy.supports(null));
  }

  @Test
  public void filtersOnPostingActiveAccountWithGivenAlias() {
    assertEquals("(vc.account.summaryLevel = false and vc.account.active = true)",
        policy.resolveFilter(COMBINATION_ENTITY, Collections.emptyMap(), "vc"));
  }

  @Test
  public void defaultsToAliasEWhenNoneIsGiven() {
    String expected = "(e.account.summaryLevel = false and e.account.active = true)";
    assertEquals(expected, policy.resolveFilter(COMBINATION_ENTITY, Collections.emptyMap(), null));
    assertEquals(expected, policy.resolveFilter(COMBINATION_ENTITY, Collections.emptyMap(), ""));
  }

  @Test
  public void ignoresContextParams() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("C_AcctSchema_ID", "ANY");
    assertEquals(policy.resolveFilter(COMBINATION_ENTITY, Collections.emptyMap(), "e"),
        policy.resolveFilter(COMBINATION_ENTITY, ctx, "e"));
  }
}
