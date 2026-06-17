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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

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

  /** Verifies that business partner rows are excluded while the shared BP group catalog stays available. */
  @Test
  public void testDefinitionExcludesBusinessPartnerRowsButKeepsBpGroup() {
    assertFalse(OnboardingDatasetDefinition.getIncludedTables().contains("C_BPARTNER"));
    assertFalse(OnboardingDatasetDefinition.getIncludedTables().contains("C_BPARTNER_LOCATION"));
    assertTrue(OnboardingDatasetDefinition.getIncludedTables().contains("C_BP_GROUP"));
  }

  /** Verifies that normalized onboarding XML does not import business partner entity rows. */
  @Test
  public void testNormalizerExcludesBusinessPartnersFromDatasetXml() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertFalse(xml.contains("<cBpartner>"));
    assertFalse(xml.contains("<cBpartnerLocation>"));
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

  /** Verifies that translation-only payment term tables are excluded from onboarding metadata. */
  @Test
  public void testDefinitionExcludesPaymentTermTranslations() {
    assertFalse(OnboardingDatasetDefinition.getIncludedTables().contains("C_PAYMENTTERM_TRL"));
  }


  /** Verifies that the remaining onboarding dataset still keeps shared setup content after removing BP rows. */
  @Test
  public void testNormalizerKeepsSharedSetupContent() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("Agua"));
    assertTrue(xml.contains("Cuenta de Banco"));
    assertTrue(xml.contains("30 Días"));
    assertTrue(xml.contains("Inmediato"));
    assertTrue(xml.contains("Efectivo"));
    assertTrue(xml.contains("Consumidor Final"));
  }

  /** Verifies that user-scoped sales representative columns are stripped from product rows. */
  @Test
  public void testNormalizerStripsUserScopedProductSalesRepField() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertFalse(xml.contains("<SALESREP_ID>"));
  }

  /**
   * Verifies that the {@code AD_LANGUAGE} column of translation (_TRL) rows is retained in the
   * normalized XML. It is a mandatory NOT-NULL key on {@code C_ELEMENTVALUE_TRL}, so dropping it
   * would make the import fail. The raw uppercase {@code <AD_LANGUAGE>} source tag is renamed to the
   * property name during normalization; with the always-primitive mock the column is emitted as
   * {@code <adLanguage>es_ES</adLanguage>}, carrying the GOClient language code.
   */
  @Test
  public void testNormalizerRetainsLanguageColumnOnTranslationRows() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("<adLanguage>es_ES</adLanguage>"));
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


  /**
   * Verifies that the org-specific (orphan) account-element tree shipped by GOClient is ignored at
   * import time while the client-level ({@code AD_ORG_ID='0'}) chart of accounts is kept. The source
   * dataset is never modified — exclusion happens during normalization. The orphan element
   * ({@code 91D04...}, org-owned) and its element values must be absent, while the wired element
   * ({@code BB9B...}, client-level) survives.
   */
  @Test
  public void testNormalizerExcludesOrgSpecificAccountElementTree() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    String wiredElementId = "BB9B64C5B6534A40A36F7C0F45C2CC0B";
    String orphanElementId = "91D04C02EF8F4975B9E4F5E07543B6EA";

    assertTrue(xml.contains(wiredElementId));
    assertFalse(xml.contains(orphanElementId));
  }

  /**
   * Verifies that non-primitive reference columns route their raw value through the injected
   * {@link OnboardingDatasetNormalizer.ReferenceIdResolver} and emit the resolver-returned id rather
   * than the raw code. The {@code AD_LANGUAGE} column on {@code C_ELEMENTVALUE_TRL} is an
   * {@code ADLanguage} reference whose GOClient code ({@code es_ES}) must be translated to its
   * installed DAL id ({@code 140}) before import. The stub resolver records the call so we can assert
   * it was invoked with the {@code ADLanguage} target entity name.
   */
  @Test
  public void testNormalizerResolvesLanguageReferenceIdViaResolver() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-language-reference");
    Files.write(sampleDir.resolve("C_ELEMENTVALUE_TRL.xml"),
        ("<data>"
            + "<C_ELEMENTVALUE_TRL>"
            + "<C_ELEMENTVALUE_TRL_ID><![CDATA[ROW1]]></C_ELEMENTVALUE_TRL_ID>"
            + "<AD_LANGUAGE><![CDATA[es_ES]]></AD_LANGUAGE>"
            + "</C_ELEMENTVALUE_TRL>"
            + "</data>").getBytes(StandardCharsets.UTF_8));

    AtomicReference<String> observedTargetEntityName = new AtomicReference<>();
    OnboardingDatasetNormalizer.ReferenceIdResolver recordingResolver =
        (targetEntityName, rawValue) -> {
          observedTargetEntityName.set(targetEntityName);
          return "ADLanguage".equals(targetEntityName) ? "140" : rawValue;
        };

    OnboardingDatasetNormalizer normalizer = new OnboardingDatasetNormalizer(
        sampleDir, this::mockLanguageReferenceEntityForTable, recordingResolver);

    String xml = normalizer.buildDatasetXml();

    assertEquals("ADLanguage", observedTargetEntityName.get());
    assertTrue(xml.contains("<adLanguage id=\"140\""));
    assertFalse(xml.contains("es_ES"));
  }

  /**
   * Builds an entity whose {@code AD_LANGUAGE} column is a non-primitive {@code ADLanguage}
   * reference, so the resolver branch in {@code appendPropertyElement} is exercised. All other
   * columns keep the default always-primitive behavior.
   */
  private Entity mockLanguageReferenceEntityForTable(String tableName) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(toLowerCamel(tableName));
    when(entity.getTableName()).thenReturn(tableName);
    when(entity.isOrganizationEnabled()).thenReturn(true);
    when(entity.getPropertyByColumnName(anyString(), eq(false)))
        .thenAnswer(invocation -> {
          String columnName = invocation.getArgument(0);
          return "AD_LANGUAGE".equals(columnName)
              ? mockReferenceProperty(columnName, "ADLanguage")
              : mockProperty(tableName, columnName);
        });
    return entity;
  }

  /**
   * Mocks a non-primitive reference property whose target entity reports the given name, so the
   * normalizer emits an {@code id} attribute resolved through the {@code ReferenceIdResolver}.
   */
  private Property mockReferenceProperty(String columnName, String targetEntityName) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(toLowerCamel(columnName));
    when(property.isId()).thenReturn(false);
    when(property.isOneToMany()).thenReturn(false);
    when(property.isPrimitive()).thenReturn(false);
    Entity targetEntity = mock(Entity.class);
    when(targetEntity.getName()).thenReturn(targetEntityName);
    when(property.getTargetEntity()).thenReturn(targetEntity);
    return property;
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
    when(entity.getTableName()).thenReturn(tableName);
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
