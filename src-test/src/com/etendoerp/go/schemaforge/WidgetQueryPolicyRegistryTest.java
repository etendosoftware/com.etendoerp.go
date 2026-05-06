/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WidgetQueryPolicyRegistryTest {

  @Test
  void bestProductsTrendUsesZeroWhenCurrentPeriodHasNoSales() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestProducts();

    assertTrue(policy.fallbackSql.contains("COALESCE(curr_period.amount, 0)"));
    assertTrue(policy.rangedSql.contains("COALESCE(curr_period.amount, 0)"));
  }

  @Test
  void bestSellersTrendUsesZeroWhenCurrentPeriodHasNoSales() {
    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.bestSellers();

    assertTrue(policy.fallbackSql.contains("COALESCE(curr_period.qty, 0)"));
    assertTrue(policy.rangedSql.contains("COALESCE(curr_period.qty, 0)"));
  }
}
