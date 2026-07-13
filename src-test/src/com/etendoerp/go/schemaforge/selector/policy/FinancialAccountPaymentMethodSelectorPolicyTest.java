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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link FinancialAccountPaymentMethodSelectorPolicy}.
 *
 * <p>{@code resolveFilter} is a pure function of the context params, so no DB access is needed.
 * Guards the Contacto "Cuenta" selector filtering by payment method (customer + vendor), matching
 * Etendo Classic's {@code Fin_Finacc_Paymentmethod} membership rule.</p>
 */
public class FinancialAccountPaymentMethodSelectorPolicyTest {

  private static final String ENTITY = "FIN_Financial_Account";
  private static final String PM_ID = "5A1B2C3D4E5F60718293A4B5C6D7E8F9";

  private final FinancialAccountPaymentMethodSelectorPolicy policy =
      new FinancialAccountPaymentMethodSelectorPolicy();

  @Test
  public void supportsOnlyFinancialAccount() {
    assertTrue(policy.supports(ENTITY));
    assertFalse(policy.supports("BusinessPartner"));
    assertFalse(policy.supports(null));
  }

  @Test
  public void customerPaymentMethodProducesExistsFilter() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("Fin_Paymentmethod_ID", PM_ID);

    String filter = policy.resolveFilter(ENTITY, ctx, "e");

    assertTrue(filter.startsWith("EXISTS (SELECT 1 FROM FinancialMgmtFinAccPaymentMethod fapm"));
    assertTrue(filter.contains("fapm.account.id = e.id"));
    assertTrue(filter.contains("fapm.paymentMethod.id = :finAccPaymentMethodId"));
    assertTrue(filter.contains("fapm.active = true"));
    // Payment method id must be bound as a named param, never interpolated (injection safety).
    assertFalse(filter.contains(PM_ID));
  }

  @Test
  public void vendorPaymentMethodProducesExistsFilter() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("PO_Paymentmethod_ID", PM_ID);

    String filter = policy.resolveFilter(ENTITY, ctx, "e");

    assertTrue(filter.contains("fapm.paymentMethod.id = :finAccPaymentMethodId"));
  }

  @Test
  public void blankAliasFallsBackToDefault() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("Fin_Paymentmethod_ID", PM_ID);

    String filter = policy.resolveFilter(ENTITY, ctx, "  ");

    assertTrue(filter.contains("fapm.account.id = e.id"));
  }

  @Test
  public void noPaymentMethodReturnsNull() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("AD_Org_ID", "0");

    assertNull(policy.resolveFilter(ENTITY, ctx, "e"));
  }

  @Test
  public void emptyOrNullContextReturnsNull() {
    assertNull(policy.resolveFilter(ENTITY, Collections.emptyMap(), "e"));
    assertNull(policy.resolveFilter(ENTITY, null, "e"));
  }

  @Test
  public void unsupportedEntityReturnsNull() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("Fin_Paymentmethod_ID", PM_ID);

    assertNull(policy.resolveFilter("BusinessPartner", ctx, "e"));
  }

  @Test
  public void malformedIdReturnsNull() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put("Fin_Paymentmethod_ID", "'; DROP TABLE fin_financial_account; --");

    assertNull(policy.resolveFilter(ENTITY, ctx, "e"));
  }
}
