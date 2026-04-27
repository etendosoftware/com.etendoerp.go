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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;

/**
 * Test class for {@link OnboardingDatasetNormalizer}.
 */
public class OnboardingDatasetNormalizerTest {

  /** Verifies that the bootstrap exclusion list contains the expected tenant-definition tables. */
  @Test
  public void testDefinitionExcludesBootstrapTables() {
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_CLIENT"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_ORG"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_USER"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_ROLE"));
    assertTrue(OnboardingDatasetDefinition.getExcludedTables().contains("AD_REF_DATA_LOADED"));
  }

  /** Verifies that document types keep their required dependent tables in the curated dataset. */
  @Test
  public void testDefinitionIncludesDocumentTypesWithDependentTables() {
    assertTrue(OnboardingDatasetDefinition.getIncludedTables().contains("C_DOCTYPE"));
    assertTrue(OnboardingDatasetDefinition.getIncludedTables().contains("AD_SEQUENCE"));
    assertTrue(OnboardingDatasetDefinition.getIncludedTables().contains("GL_CATEGORY"));
    assertFalse(OnboardingDatasetDefinition.getExcludedTables().contains("AD_SEQUENCE"));
  }

  /** Verifies that normalized onboarding XML emits document types together with their dependencies. */
  @Test
  public void testNormalizerIncludesDocumentTypesWithDependencies() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("<cDoctype"));
    assertTrue(xml.contains("<adSequence"));
    assertTrue(xml.contains("<glCategory"));
    assertTrue(xml.contains("Quotation"));
  }

  /** Verifies that payment terms are kept in the curated onboarding dataset. */
  @Test
  public void testDefinitionIncludesPaymentTerms() {
    assertTrue(OnboardingDatasetDefinition.getIncludedTables().contains("C_PAYMENTTERM"));
  }

  /** Verifies that normalized onboarding XML emits payment term rows from GOClient. */
  @Test
  public void testNormalizerIncludesPaymentTerms() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("<cPaymentterm"));
    assertTrue(xml.contains("30 Días"));
  }



  @Test
  public void testNormalizerBuildsEmptyDatasetWithoutUnsupportedJaxpFailures() throws Exception {
    Path emptySampleDataDir = Files.createTempDirectory("onboarding-empty-sampledata");

    String xml = new OnboardingDatasetNormalizer(emptySampleDataDir).buildDatasetXml();

    assertTrue(xml.contains("<Openbravo"));
  }


  /** Verifies that the generated onboarding XML uses the Openbravo root element. */

  @Test
  public void testNormalizerUsesOpenbravoRootElement() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("<Openbravo"));
    assertFalse(xml.contains("<data>"));
  }

  /** Verifies that bootstrap records are removed from the generated onboarding dataset. */
  @Test
  public void testNormalizerRemovesBootstrapTablesFromDatasetXml() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertFalse(xml.contains("<AD_CLIENT>"));
    assertFalse(xml.contains("<AD_ORG>"));
    assertFalse(xml.contains("<AD_USER>"));
    assertFalse(xml.contains("<AD_ROLE>"));
    assertFalse(xml.contains("<AD_REF_DATA_LOADED>"));
    assertFalse(OnboardingDatasetDefinition.getIncludedTables().contains("C_PAYMENTTERM_TRL"));
  }

  /** Verifies that representative foundation business records remain in the normalized XML. */
  @Test
  public void testNormalizerKeepsFoundationBusinessContent() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("Agua"));
    assertTrue(xml.contains("Consumidor Final"));
    assertTrue(xml.contains("Cuenta de Banco"));
    assertTrue(xml.contains("30 Días"));
    assertTrue(xml.contains("Inmediato"));
    assertTrue(xml.contains("Juan Perez"));
    assertTrue(xml.contains("Efectivo"));
  }

  /** Verifies that user-scoped sales representative columns are stripped from product rows. */
  @Test
  public void testNormalizerStripsUserScopedProductSalesRepField() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertFalse(xml.contains("<SALESREP_ID>"));
    assertFalse(xml.contains("<AD_LANGUAGE>"));
  }

  /** Verifies that sourcedata table and column tags do not leak into the final XML. */
  @Test
  public void testNormalizerDoesNotEmitSourcedataTableOrColumnTags() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertFalse(xml.contains("<AD_ORG_WAREHOUSE>"));
    assertFalse(xml.contains("<C_BP_GROUP>"));
    assertFalse(xml.contains("<AD_ORG_WAREHOUSE_ID>"));
    assertFalse(xml.contains("<M_PRODUCT_ID>"));
  }

  /** Verifies that the default normalizer can load packaged sampledata without a repo checkout. */
  @Test
  public void testDefaultNormalizerLoadsBundledSampledataFromClasspath() {
    String xml = classpathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("<Openbravo"));
    assertTrue(xml.contains("Almacen GO"));
    assertFalse(xml.contains("<AD_CLIENT>"));
  }


  private OnboardingDatasetNormalizer pathBackedNormalizer() {
    return new OnboardingDatasetNormalizer(sampleDataDir(), this::mockEntityForTable);
  }

  private OnboardingDatasetNormalizer classpathBackedNormalizer() {
    return new OnboardingDatasetNormalizer(getClass().getClassLoader(), this::mockEntityForTable);
  }

  private Entity mockEntityForTable(String tableName) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(toLowerCamel(tableName));
    when(entity.isOrganizationEnabled()).thenReturn(true);
    when(entity.getPropertyByColumnName(anyString(), eq(false)))
        .thenAnswer(invocation -> mockProperty(tableName, invocation.getArgument(0)));
    return entity;
  }

  private Property mockProperty(String tableName, String columnName) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(
        columnName.equals(tableName + "_ID") ? "id" : toLowerCamel(columnName));
    when(property.isId()).thenReturn(columnName.equals(tableName + "_ID"));
    when(property.isOneToMany()).thenReturn(false);
    when(property.isPrimitive()).thenReturn(true);
    return property;
  }

  private String toLowerCamel(String value) {
    String[] parts = value.toLowerCase().split("_");
    StringBuilder builder = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      builder.append(Character.toUpperCase(parts[i].charAt(0)));
      builder.append(parts[i].substring(1));
    }
    return builder.toString();
  }

  private Path sampleDataDir() {
    Path moduleRelative = Paths.get("referencedata", "sampledata", "GOClient");
    if (Files.exists(moduleRelative)) {
      return moduleRelative;
    }

    Path rootRelative = Paths.get("modules", "com.etendoerp.go", "referencedata", "sampledata",
        "GOClient");
    if (Files.exists(rootRelative)) {
      return rootRelative;
    }

    throw new IllegalStateException("GOClient sampledata directory not found from current working directory");
  }
}
