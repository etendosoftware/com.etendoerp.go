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

import java.util.Map;

import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

import com.etendoerp.go.schemaforge.util.PostingAccountCombinations;

/**
 * ETP-5681: every generic FK selector that targets {@link AccountingCombination}
 * ({@code C_ValidCombination}) lists only combinations whose account is a POSTING account — a
 * leaf of the chart of accounts ({@code C_ElementValue.IsSummary = 'N'}) that is active.
 *
 * <p>A summary account is a heading that only adds up its children; nothing is ever posted to it.
 * Yet every accounting field (business-partner category, contacts, product, tax, warehouse,
 * assets, GL journal lines…) offered them, and Etendo does not reject a summary account at posting
 * time either: a heading configured as, say, a default receivables account would be posted to
 * silently. The Esquema contable and financial-account catalogs apply the same rule through
 * {@link PostingAccountCombinations}; the reports popup already did ({@code issummary='N'}).
 *
 * <p>Entity-keyed (like {@link ProductCategorySystemFlagSelectorPolicy}) because the 186 account
 * columns all share the one OBUISEL "Valid Combination Selector" — the target entity is the only
 * thing they have in common, and the rule is about the entity, not about any one window.
 *
 * <p>Scope: this filters the selector LISTS (UI and MCP alike). A write that sends a summary
 * combination id directly is not rejected here — that guard is ETP-5693.
 *
 * <p>Deliberately NOT applied to {@link ElementValue} selectors: the chart of accounts "parent
 * account" field must list summary accounts, since a parent is a heading.
 */
public final class PostingAccountCombinationSelectorPolicy implements SelectorContextPolicy {

  /**
   * Creates the policy.
   */
  public PostingAccountCombinationSelectorPolicy() {
    // Stateless policy; public constructor supports registry composition without CDI.
  }

  @Override
  public boolean supports(String entityName) {
    return AccountingCombination.ENTITY_NAME.equals(entityName);
  }

  @Override
  public String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
    String account = ((alias == null || alias.isEmpty()) ? "e" : alias) + "."
        + AccountingCombination.PROPERTY_ACCOUNT + ".";
    return "(" + account + ElementValue.PROPERTY_SUMMARYLEVEL + " = false and "
        + account + ElementValue.PROPERTY_ACTIVE + " = true)";
  }
}
