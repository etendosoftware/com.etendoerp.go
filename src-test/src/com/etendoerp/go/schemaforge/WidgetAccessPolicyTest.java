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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-5088 — Unit tests for {@link WidgetAccessPolicy}, the server-side half of the dashboard
 * widget gate.
 *
 * <p>Each role is expressed as the set of windows it actually holds in the tenant, so a failure
 * names the row of the attached widget × role matrix that broke.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetAccessPolicyTest {

  @Mock private Role role;

  private MockedStatic<NeoAccessHelper> accessHelperMock;

  /** Real grants: Finance is the only template role holding the financial-account window. */
  private static final Set<String> FINANCE_WINDOWS = new HashSet<>(Arrays.asList(
      WidgetAccessPolicy.WINDOW_CONTACTS, WidgetAccessPolicy.WINDOW_PRODUCT,
      WidgetAccessPolicy.WINDOW_SALES_ORDER, WidgetAccessPolicy.WINDOW_SALES_INVOICE,
      WidgetAccessPolicy.WINDOW_PHYSICAL_INVENTORY, WidgetAccessPolicy.WINDOW_PURCHASE_ORDER,
      WidgetAccessPolicy.WINDOW_PURCHASE_INVOICE, WidgetAccessPolicy.WINDOW_FINANCIAL_ACCOUNT));

  private static final Set<String> SALES_WINDOWS = new HashSet<>(Arrays.asList(
      WidgetAccessPolicy.WINDOW_CONTACTS, WidgetAccessPolicy.WINDOW_PRODUCT,
      WidgetAccessPolicy.WINDOW_SALES_ORDER, WidgetAccessPolicy.WINDOW_SALES_INVOICE,
      WidgetAccessPolicy.WINDOW_PHYSICAL_INVENTORY, WidgetAccessPolicy.WINDOW_GOODS_SHIPMENT));

  private static final Set<String> PURCHASING_WINDOWS = new HashSet<>(Arrays.asList(
      WidgetAccessPolicy.WINDOW_CONTACTS, WidgetAccessPolicy.WINDOW_PRODUCT,
      WidgetAccessPolicy.WINDOW_PURCHASE_ORDER, WidgetAccessPolicy.WINDOW_PURCHASE_INVOICE,
      WidgetAccessPolicy.WINDOW_GOODS_RECEIPT));

  @BeforeEach
  void setUp() {
    accessHelperMock = mockStatic(NeoAccessHelper.class);
    accessHelperMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(role);
  }

  @AfterEach
  void tearDown() {
    accessHelperMock.close();
  }

  /** Grants exactly {@code windows} to the mocked role, denying everything else. */
  private void grant(Set<String> windows) {
    accessHelperMock.when(() -> NeoAccessHelper.hasWindowAccess(any(Role.class), any(), any()))
        .thenAnswer(invocation -> windows.contains(invocation.getArgument(1)));
  }

  @Nested
  @DisplayName("canRead")
  class CanRead {

    @Test
    @DisplayName("Finance reaches the financial-account window; Sales and Purchasing do not")
    void financialAccountIsFinanceOnly() {
      grant(FINANCE_WINDOWS);
      assertTrue(WidgetAccessPolicy.canRead(role, WidgetAccessPolicy.WINDOW_FINANCIAL_ACCOUNT));

      grant(SALES_WINDOWS);
      assertFalse(WidgetAccessPolicy.canRead(role, WidgetAccessPolicy.WINDOW_FINANCIAL_ACCOUNT));

      grant(PURCHASING_WINDOWS);
      assertFalse(WidgetAccessPolicy.canRead(role, WidgetAccessPolicy.WINDOW_FINANCIAL_ACCOUNT));
    }

    @Test
    @DisplayName("all four template roles reach the product window")
    void productIsUniversal() {
      for (Set<String> windows : Arrays.asList(FINANCE_WINDOWS, SALES_WINDOWS, PURCHASING_WINDOWS)) {
        grant(windows);
        assertTrue(WidgetAccessPolicy.canRead(role, WidgetAccessPolicy.WINDOW_PRODUCT));
      }
    }

    @Test
    @DisplayName("a null role is denied without consulting the access helper")
    void nullRoleIsDenied() {
      assertFalse(WidgetAccessPolicy.canRead(null, WidgetAccessPolicy.WINDOW_PRODUCT));
      accessHelperMock.verify(
          () -> NeoAccessHelper.hasWindowAccess(any(Role.class), any(), any()), org.mockito.Mockito.never());
    }

    @Test
    @DisplayName("widgets only ever read, so the helper is always asked with GET")
    void alwaysAsksWithGet() {
      grant(SALES_WINDOWS);
      WidgetAccessPolicy.canRead(role, WidgetAccessPolicy.WINDOW_SALES_INVOICE);
      accessHelperMock.verify(() -> NeoAccessHelper.hasWindowAccess(
          any(Role.class), eq(WidgetAccessPolicy.WINDOW_SALES_INVOICE), eq("GET")));
    }
  }

  @Nested
  @DisplayName("canReadSlug — the per-item gate")
  class CanReadSlug {

    @Test
    @DisplayName("Sales sees sales documents but not purchase ones")
    void salesSeesItsOwnDocuments() {
      grant(SALES_WINDOWS);
      assertTrue(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_SALES_INVOICE));
      assertTrue(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_GOODS_SHIPMENT));
      assertFalse(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_PURCHASE_INVOICE));
      assertFalse(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_GOODS_RECEIPT));
    }

    @Test
    @DisplayName("Purchasing is the mirror image")
    void purchasingSeesItsOwnDocuments() {
      grant(PURCHASING_WINDOWS);
      assertTrue(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_PURCHASE_INVOICE));
      assertTrue(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_GOODS_RECEIPT));
      assertFalse(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_SALES_INVOICE));
      assertFalse(WidgetAccessPolicy.canReadSlug(role, WidgetAccessPolicy.SLUG_GOODS_SHIPMENT));
    }

    @Test
    @DisplayName("an unknown, blank or null slug is denied — a new row type drops, never leaks")
    void unresolvableSlugFailsClosed() {
      grant(FINANCE_WINDOWS);
      assertFalse(WidgetAccessPolicy.canReadSlug(role, "some-future-window"));
      assertFalse(WidgetAccessPolicy.canReadSlug(role, "   "));
      assertFalse(WidgetAccessPolicy.canReadSlug(role, null));
    }

    @Test
    @DisplayName("a null role is denied for every slug")
    void nullRoleIsDenied() {
      assertFalse(WidgetAccessPolicy.canReadSlug(null, WidgetAccessPolicy.SLUG_SALES_INVOICE));
    }
  }
}
