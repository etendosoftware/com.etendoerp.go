/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Test class for {@link OnboardingDatasetNormalizer}.
 */
public class OnboardingDatasetNormalizerTest {

  /** Test method for {@link OnboardingDatasetNormalizer#buildDatasetXml()}. */
  @Test
  public void testDefinitionExcludesBootstrapTables() {
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_CLIENT"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_ORG"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_USER"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_ROLE"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_REF_DATA_LOADED"));
  }

  /** Test method for {@link OnboardingDatasetNormalizer#buildDatasetXml()}. */

  @Test
  public void testNormalizerUsesOpenbravoRootElement() {
    String xml = new OnboardingDatasetNormalizer(sampleDataDir()).buildDatasetXml();

    assertTrue(xml.contains("<Openbravo"));
    assertFalse(xml.contains("<data>"));
  }

  /* Test method for {@link OnboardingDatasetNormalizer#buildDatasetXml()}. */
  @Test
  public void testNormalizerRemovesBootstrapTablesFromDatasetXml() {
    String xml = new OnboardingDatasetNormalizer(sampleDataDir()).buildDatasetXml();

    assertFalse(xml.contains("<AD_CLIENT>"));
    assertFalse(xml.contains("<AD_ORG>"));
    assertFalse(xml.contains("<AD_USER>"));
    assertFalse(xml.contains("<AD_ROLE>"));
    assertFalse(xml.contains("<AD_REF_DATA_LOADED>"));
  }

  /** Test method for {@link OnboardingDatasetNormalizer#buildDatasetXml()}. */
  @Test
  public void testNormalizerKeepsFoundationBusinessContent() {
    String xml = new OnboardingDatasetNormalizer(sampleDataDir()).buildDatasetXml();

    assertTrue(xml.contains("Agua"));
    assertTrue(xml.contains("Consumidor Final"));
    assertTrue(xml.contains("Cuenta de Banco"));
    assertTrue(xml.contains("Efectivo"));
  }

  /** Test method for {@link OnboardingDatasetNormalizer#buildDatasetXml()}. */
  @Test
  public void testNormalizerStripsUserScopedProductSalesRepField() {
    String xml = new OnboardingDatasetNormalizer(sampleDataDir()).buildDatasetXml();

    assertFalse(xml.contains("<SALESREP_ID>"));
  }

  /** Test method for {@link OnboardingDatasetNormalizer#buildDatasetXml()}. */
  @Test
  public void testNormalizerDoesNotEmitSourcedataTableOrColumnTags() {
    String xml = new OnboardingDatasetNormalizer(sampleDataDir()).buildDatasetXml();

    assertFalse(xml.contains("<AD_ORG_WAREHOUSE>"));
    assertFalse(xml.contains("<C_BP_GROUP>"));
    assertFalse(xml.contains("<AD_ORG_WAREHOUSE_ID>"));
    assertFalse(xml.contains("<M_PRODUCT_ID>"));
  }

  private Path sampleDataDir() {
    return Paths.get("referencedata", "sampledata", "GOClient");
  }
}
