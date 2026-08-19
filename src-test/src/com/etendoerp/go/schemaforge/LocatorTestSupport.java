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
package com.etendoerp.go.schemaforge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * Shared mock builders for the ETP-4863 storage-bin anchoring tests.
 *
 * <p>Every write path that creates an {@code M_InOutLine} anchors its locator to the header
 * document's warehouse via {@code NeoHandlerUtils.anchorLocatorToWarehouse}, so the same three
 * fixtures (a warehouse, a locator belonging to one, and the {@code M_Locator} lookup criteria)
 * were being rebuilt verbatim in every test class that exercises one of those paths. They live
 * here once instead.
 */
final class LocatorTestSupport {

  static final String WH_PRINCIPAL = "wh-principal";
  static final String WH_SECONDARY = "wh-secondary";
  static final String LOC_PRINCIPAL_DEFAULT = "loc-principal-default";

  private LocatorTestSupport() {
  }

  static Warehouse mockWarehouse(String id) {
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn(id);
    return warehouse;
  }

  static Locator mockLocator(String id, Warehouse warehouse) {
    Locator locator = mock(Locator.class);
    when(locator.getId()).thenReturn(id);
    when(locator.getWarehouse()).thenReturn(warehouse);
    return locator;
  }

  /**
   * Stubs the {@code M_Locator} criteria chain and makes it yield {@code result} for the
   * {@code isDefault} lookup (cascade step 2), so no fallback query is ever reached.
   */
  static OBCriteria<Locator> stubDefaultLocatorLookup(OBDal dal, Locator result) {
    OBCriteria<Locator> criteria = stubLocatorCriteria(dal);
    when(criteria.uniqueResult()).thenReturn(result);
    return criteria;
  }

  /**
   * Stubs the {@code M_Locator} criteria chain for a warehouse with NO default-flagged bin: the
   * first {@code uniqueResult()} (step 2, {@code isDefault}) yields {@code null} and the second
   * (step 3, any active bin ordered by {@code searchKey}) yields {@code anyActive}. Pass
   * {@code null} for {@code anyActive} to model a warehouse with no active locator at all
   * (cascade step 4), where both lookups come back empty.
   */
  static OBCriteria<Locator> stubLocatorCascade(OBDal dal, Locator anyActive) {
    OBCriteria<Locator> criteria = stubLocatorCriteria(dal);
    when(criteria.uniqueResult()).thenReturn(null).thenReturn(anyActive);
    return criteria;
  }

  /** The criteria builder chain shared by both {@code M_Locator} lookups. */
  @SuppressWarnings("unchecked")
  private static OBCriteria<Locator> stubLocatorCriteria(OBDal dal) {
    OBCriteria<Locator> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Locator.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    return criteria;
  }
}
