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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link OnboardingDatasetDefinition}, the table/column allow-list that drives
 * onboarding dataset normalization.
 */
public class OnboardingDatasetDefinitionTest {

  @Test
  public void testGloballyStrippedColumnIsStrippedForAnyTable() {
    assertTrue(OnboardingDatasetDefinition.isStrippedColumn("M_PRODUCT", "CREATED"));
    assertTrue(OnboardingDatasetDefinition.isStrippedColumn("ANY_TABLE", "SALESREP_ID"));
  }

  @Test
  public void testGloballyStrippedColumnIsStrippedEvenWhenTableIsNull() {
    // The global check runs before the null-table guard.
    assertTrue(OnboardingDatasetDefinition.isStrippedColumn(null, "UPDATEDBY"));
  }

  @Test
  public void testPerTableStrippedColumnIsStrippedOnlyForThatTable() {
    assertTrue(OnboardingDatasetDefinition.isStrippedColumn("C_ELEMENT", "AD_TREE_ID"));
    assertFalse(OnboardingDatasetDefinition.isStrippedColumn("M_PRODUCT", "AD_TREE_ID"));
  }

  @Test
  public void testPerTableStrippedColumnMatchIsCaseInsensitiveOnTableName() {
    assertTrue(OnboardingDatasetDefinition.isStrippedColumn("c_element", "AD_TREE_ID"));
  }

  @Test
  public void testPerTableStrippedColumnIsNotStrippedForNullTable() {
    assertFalse(OnboardingDatasetDefinition.isStrippedColumn(null, "AD_TREE_ID"));
  }

  @Test
  public void testNonStrippedColumnIsKept() {
    assertFalse(OnboardingDatasetDefinition.isStrippedColumn("C_ELEMENT", "NAME"));
  }

  @Test
  public void testIncludedTableIsIncluded() {
    assertTrue(OnboardingDatasetDefinition.shouldIncludeTable("M_PRODUCT"));
    assertTrue(OnboardingDatasetDefinition.shouldIncludeTable("C_PERIODCONTROL"));
  }

  @Test
  public void testExcludedTableIsNotIncluded() {
    assertFalse(OnboardingDatasetDefinition.shouldIncludeTable("AD_ORG"));
    assertFalse(OnboardingDatasetDefinition.shouldIncludeTable("AD_TREENODE"));
  }

  @Test
  public void testUnlistedTableIsNotIncluded() {
    assertFalse(OnboardingDatasetDefinition.shouldIncludeTable("SOME_RANDOM_TABLE"));
  }

  @Test
  public void testNullTableIsNotIncluded() {
    assertFalse(OnboardingDatasetDefinition.shouldIncludeTable(null));
  }

  @Test
  public void testIncludedAndExcludedTableSetsAreDisjoint() {
    // shouldIncludeTable relies on the two sets never overlapping; guard against future drift.
    Set<String> included = OnboardingDatasetDefinition.getIncludedTables();
    Set<String> excluded = OnboardingDatasetDefinition.getExcludedTables();
    for (String table : included) {
      assertFalse("Table listed as both included and excluded: " + table,
          excluded.contains(table));
    }
  }

  @Test
  public void testAccessorsExposeConfiguredEntries() {
    assertTrue(OnboardingDatasetDefinition.getStrippedFields().contains("CREATED"));
    assertTrue(OnboardingDatasetDefinition.getIncludedTables().contains("C_ACCTSCHEMA"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_CLIENT"));
  }
}
