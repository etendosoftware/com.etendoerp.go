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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.model.ad.ui.Tab;

/**
 * Tests for {@link NeoParentTabFilterResolver#resolveTabWhere(Tab, String)} — the one rule both the
 * REST read ({@code NeoCrudHandler.applyWhereClause}) and the MCP read
 * ({@code McpToolRouter.handleList}) use to turn a child tab's HQL where clause into something a
 * query can run (ETP-5542).
 *
 * <p>The bug it covers: the Bin Contents tab stores {@code e.storageBin.id=@Locator.id@}. REST
 * filled the placeholder with the parent id; MCP passed the clause on verbatim, so the query
 * matched nothing and answered {@code 200} with an empty list. With no parent tab to look up,
 * {@code resolveTokenFromParent} falls back to the parent id for every token, which is what these
 * tests rely on to stay free of a database.</p>
 */
class NeoParentTabFilterResolverTest {

  private static final String PARENT_ID = "PARENT-1";

  private static Tab tabWithWhere(String hqlWhere) {
    Tab tab = mock(Tab.class);
    when(tab.getHqlwhereclause()).thenReturn(hqlWhere);
    return tab;
  }

  @Test
  @DisplayName("A tab with no where clause yields null")
  void nullWhereYieldsNull() {
    assertNull(NeoParentTabFilterResolver.resolveTabWhere(tabWithWhere(null), PARENT_ID));
  }

  @Test
  @DisplayName("A blank where clause is returned as declared")
  void blankWhereReturnedUnchanged() {
    assertEquals("   ",
        NeoParentTabFilterResolver.resolveTabWhere(tabWithWhere("   "), PARENT_ID));
  }

  @Test
  @DisplayName("A clause without a placeholder is returned untouched even with a parent id")
  void plainClauseUntouchedWithParentId() {
    // No KernelUtils mock on purpose: nothing may be looked up for a clause with no '@'.
    assertEquals("e.active = true",
        NeoParentTabFilterResolver.resolveTabWhere(tabWithWhere("e.active = true"), PARENT_ID));
  }

  @Test
  @DisplayName("A placeholder is left alone when there is no parent id")
  void placeholderUntouchedWithoutParentId() {
    // A top-level read: whatever the clause carries is left to the core JSON service.
    assertEquals("e.organization.id = @AD_Org_ID@",
        NeoParentTabFilterResolver.resolveTabWhere(
            tabWithWhere("e.organization.id = @AD_Org_ID@"), null));
  }

  @Test
  @DisplayName("The parent placeholder of Bin Contents is resolved to the parent id")
  void resolvesBinContentsParentPlaceholder() {
    Tab tab = tabWithWhere("e.quantityOnHand<>0 AND e.storageBin.id=@Locator.id@");

    try (MockedStatic<KernelUtils> kernelMock = Mockito.mockStatic(KernelUtils.class)) {
      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(tab)).thenReturn(null);

      // The value is wrapped in single quotes; the token is written unquoted, as in real AD tabs.
      assertEquals("e.quantityOnHand<>0 AND e.storageBin.id='" + PARENT_ID + "'",
          NeoParentTabFilterResolver.resolveTabWhere(tab, PARENT_ID));
    }
  }

  @Test
  @DisplayName("Every placeholder in the clause is resolved")
  void resolvesEveryPlaceholder() {
    Tab tab = tabWithWhere("e.client.id = @AD_Client_ID@ AND e.storageBin.id = @Locator.id@");

    try (MockedStatic<KernelUtils> kernelMock = Mockito.mockStatic(KernelUtils.class)) {
      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(tab)).thenReturn(null);

      assertEquals("e.client.id = '" + PARENT_ID + "' AND e.storageBin.id = '" + PARENT_ID + "'",
          NeoParentTabFilterResolver.resolveTabWhere(tab, PARENT_ID));
    }
  }

  @Test
  @DisplayName("A session variable is not treated as a parent placeholder")
  void sessionVariableIsNotResolved() {
    Tab tab = tabWithWhere("e.id in (@#AccessibleOrgTree@)");

    try (MockedStatic<KernelUtils> kernelMock = Mockito.mockStatic(KernelUtils.class)) {
      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(tab)).thenReturn(null);

      // '#' is outside the placeholder pattern, so the variable survives for the core to bind.
      assertEquals("e.id in (@#AccessibleOrgTree@)",
          NeoParentTabFilterResolver.resolveTabWhere(tab, PARENT_ID));
    }
  }

  @Test
  @DisplayName("The parent id still resolves when the parent tab lookup fails")
  void fallsBackToParentIdWhenParentTabLookupFails() {
    Tab tab = tabWithWhere("e.storageBin.id=@Locator.id@");

    try (MockedStatic<KernelUtils> kernelMock = Mockito.mockStatic(KernelUtils.class)) {
      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(tab)).thenThrow(new IllegalStateException("no parent tab"));

      assertEquals("e.storageBin.id='" + PARENT_ID + "'",
          NeoParentTabFilterResolver.resolveTabWhere(tab, PARENT_ID));
    }
  }

  @Test
  @DisplayName("A quote in the parent id is escaped, not concatenated raw")
  void quoteInParentIdIsEscaped() {
    Tab tab = tabWithWhere("e.storageBin.id=@Locator.id@");

    try (MockedStatic<KernelUtils> kernelMock = Mockito.mockStatic(KernelUtils.class)) {
      KernelUtils kernelUtils = mock(KernelUtils.class);
      kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
      when(kernelUtils.getParentTab(tab)).thenReturn(null);

      assertEquals("e.storageBin.id='A''B'",
          NeoParentTabFilterResolver.resolveTabWhere(tab, "A'B"));
    }
  }
}
