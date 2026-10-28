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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ETP-5852 (M-173): a list validation rule that names AD_Ref_List is run against the criteria
 * query's own alias, the only name PostgreSQL accepts there.
 */
class ListReferenceSelectorExecutorTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "AD_Ref_List.Value IN ('ARABA', 'BIZKAIA')|{alias}.Value IN ('ARABA', 'BIZKAIA')",
      "ad_ref_list.value <> 'X' AND AD_Ref_List.IsActive='Y'|{alias}.value <> 'X' AND {alias}.IsActive='Y'",
      "Value IN ('A')|Value IN ('A')",
      "X_AD_Ref_List.Value = 'A'|X_AD_Ref_List.Value = 'A'",
      "exists (select 1 from ad_ref_list r where r.value = 'A')|exists (select 1 from ad_ref_list r where r.value = 'A')" })
  void qualifiesTheListTableByTheQueryAlias(String rule, String expected) {
    assertEquals(expected, ListReferenceSelectorExecutor.qualifyListTable(rule));
  }
}
