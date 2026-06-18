/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Unit tests for {@link AutoMatchSupport#matchByKey} — the 1:N signal-grouping core. Pure logic,
 * driven with mocked transactions and a simple in-memory key function.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AutoMatchSupportTest {

  private static final BigDecimal TOL = new BigDecimal("0.01");

  /** Builds a mock transaction with a deposit-minus-payment net amount and a signal key. */
  private static FIN_FinaccTransaction txn(String id, String amount, String key) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    lenient().when(t.getId()).thenReturn(id);
    BigDecimal amt = new BigDecimal(amount);
    if (amt.signum() >= 0) {
      lenient().when(t.getDepositAmount()).thenReturn(amt);
      lenient().when(t.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    } else {
      lenient().when(t.getDepositAmount()).thenReturn(BigDecimal.ZERO);
      lenient().when(t.getPaymentAmount()).thenReturn(amt.abs());
    }
    KEYS.put(t, key);
    return t;
  }

  private static final java.util.Map<FIN_FinaccTransaction, String> KEYS = new java.util.HashMap<>();
  private static final Function<FIN_FinaccTransaction, String> KEY_FN = KEYS::get;

  @Test
  public void matchByKey_fullPartnerGroupSums_returnsGroup() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");
    FIN_FinaccTransaction b = txn("b", "50.00", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertEquals(2, result.size());
    assertTrue(result.contains(a));
    assertTrue(result.contains(b));
  }

  @Test
  public void matchByKey_groupSumDoesNotMatch_returnsEmpty() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");
    FIN_FinaccTransaction b = txn("b", "30.00", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertTrue(result.isEmpty());
  }

  @Test
  public void matchByKey_singletonGroupIgnored() {
    // A single transaction is a 1:1 case, not a 1:N group — must be ignored even if it matches.
    FIN_FinaccTransaction a = txn("a", "150.00", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertTrue(result.isEmpty());
  }

  @Test
  public void matchByKey_picksTheMatchingPartitionAmongSeveral() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");   // bp:1 sums 100, no match alone
    FIN_FinaccTransaction b = txn("b", "70.00", "bp:2");
    FIN_FinaccTransaction c = txn("c", "30.00", "bp:2");    // bp:2 sums 100 → match
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b, c);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("100.00"), TOL, KEY_FN);

    assertEquals(2, result.size());
    assertTrue(result.contains(b));
    assertTrue(result.contains(c));
  }

  @Test
  public void matchByKey_withinTolerance() {
    FIN_FinaccTransaction a = txn("a", "100.00", "bp:1");
    FIN_FinaccTransaction b = txn("b", "50.005", "bp:1");
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertEquals(2, result.size());
  }

  @Test
  public void matchByKey_blankKeySkipped() {
    FIN_FinaccTransaction a = txn("a", "100.00", null);
    FIN_FinaccTransaction b = txn("b", "50.00", null);
    List<FIN_FinaccTransaction> pool = Arrays.asList(a, b);

    List<FIN_FinaccTransaction> result =
        AutoMatchSupport.matchByKey(pool, new BigDecimal("150.00"), TOL, KEY_FN);

    assertTrue(result.isEmpty());
  }
}
