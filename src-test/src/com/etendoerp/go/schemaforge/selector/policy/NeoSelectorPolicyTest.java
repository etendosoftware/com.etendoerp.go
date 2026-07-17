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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Wiring tests for {@link NeoSelectorPolicy}'s context-filter registry.
 *
 * <p>Guards that {@link FinancialAccountPaymentMethodSelectorPolicy} and
 * {@link CurrencyIsoAllowlistSelectorPolicy} are registered and dispatched for their respective
 * entities. Pure logic — no DB access.</p>
 */
public class NeoSelectorPolicyTest {

  private static final String PM_ID = "5A1B2C3D4E5F60718293A4B5C6D7E8F9";

  @Test
  public void registryDispatchesFinancialAccountPaymentMethodPolicy() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("Fin_Paymentmethod_ID", PM_ID);

    String filter = NeoSelectorPolicy.resolveContextParamFilter("FIN_Financial_Account", ctx, "e");

    assertTrue(filter.contains("FinancialMgmtFinAccPaymentMethod"));
    assertTrue(filter.contains("fapm.paymentMethod.id = :finAccPaymentMethodId"));
  }

  @Test
  public void registryDispatchesCurrencyIsoAllowlistPolicy() {
    String filter = NeoSelectorPolicy.resolveContextParamFilter("Currency", new HashMap<>(), "c");

    assertTrue(filter.contains("c.iSOCode in ('EUR', 'USD', 'GBP')"));
  }

  @Test
  public void unrelatedEntityYieldsNoFilter() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("Fin_Paymentmethod_ID", PM_ID);

    assertNull(NeoSelectorPolicy.resolveContextParamFilter("SomeUnrelatedEntity", ctx, "e"));
  }
}
