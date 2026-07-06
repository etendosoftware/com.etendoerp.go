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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;

/**
 * Test class for {@link OnboardingDatasetNormalizer}.
 */
public class OnboardingDatasetNormalizerTest {

  /**
   * Returns true when {@code throwable} or any of its causes carries a message containing
   * {@code expected}. Row-level guards in the normalizer are wrapped with source-file context by
   * {@link OnboardingDatasetNormalizer} (see appendEntities), so the original message lives on the
   * cause rather than the top-level throwable.
   */
  private static boolean causeChainContains(Throwable throwable, String expected) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(expected)) {
        return true;
      }
    }
    return false;
  }

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

  /** Verifies that the user-definable transaction type lookup is kept in the curated dataset. */
  @Test
  public void testDefinitionIncludesTransactionTypes() {
    assertTrue(OnboardingDatasetDefinition.getIncludedTables().contains("ETGO_TRANSACTION_TYPE"));
  }

  /** Verifies that normalized onboarding XML seeds the default transaction types from GOClient. */
  @Test
  public void testNormalizerIncludesDefaultTransactionTypes() {
    String xml = pathBackedNormalizer().buildDatasetXml();

    assertTrue(xml.contains("<etgoTransactionType"));
    assertTrue(xml.contains("Comisión"));
    assertTrue(xml.contains("Transferencia"));
    assertTrue(xml.contains("Retención"));
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
    // C_BP_GROUP's default group was renamed "Consumidor Final" -> "Cliente" (Feature ETP-4402,
    // commit 73d412c8, referencedata/sampledata/GOClient/C_BP_GROUP.xml) as part of adding the
    // "Acreedor" BP category; assert on the current bundled content.
    assertTrue(xml.contains("Cliente"));
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

  // ---------------------------------------------------------------------------------------------
  // Constructor overloads — classpath/EntityResolver/ReferenceIdResolver variant
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies the {@code (ClassLoader, EntityResolver, ReferenceIdResolver)} constructor builds a
   * working normalizer that routes reference resolution through the supplied resolver.
   */
  @Test
  public void testClassLoaderEntityResolverReferenceResolverConstructorBuildsDataset() {
    OnboardingDatasetNormalizer normalizer = new OnboardingDatasetNormalizer(
        getClass().getClassLoader(), this::mockEntityForTable, (entityName, rawValue) -> rawValue);

    String xml = normalizer.buildDatasetXml();

    assertTrue(xml.contains("<Openbravo"));
  }

  // ---------------------------------------------------------------------------------------------
  // appendEntities() — malformed sourcedata is wrapped in OnboardingDatasetNormalizationException
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a sourcedata file with malformed XML surfaces as an
   * {@link OnboardingDatasetNormalizationException} naming the offending file.
   */
  @Test
  public void testNormalizerWrapsMalformedSourcedataFile() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-malformed");
    Files.write(sampleDir.resolve("C_PAYMENTTERM.xml"),
        "<data><C_PAYMENTTERM><unterminated></data>".getBytes(StandardCharsets.UTF_8));

    try {
      new OnboardingDatasetNormalizer(sampleDir, this::mockEntityForTable).buildDatasetXml();
      fail("Expected OnboardingDatasetNormalizationException");
    } catch (OnboardingDatasetNormalizationException e) {
      assertTrue(e.getMessage().contains("C_PAYMENTTERM.xml"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // convertRow() — a row without an ID column fails fast
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a row whose ID column never resolves to a value raises an {@link OBException}
   * naming the entity, exercising the missing-id guard in {@code convertRow}.
   */
  @Test
  public void testNormalizerFailsWhenRowHasNoId() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-missing-id");
    // Row carries only a non-id primitive column, so rowState.rowId stays null.
    Files.write(sampleDir.resolve("C_PAYMENTTERM.xml"),
        ("<data><C_PAYMENTTERM><NAME><![CDATA[Immediate]]></NAME></C_PAYMENTTERM></data>")
            .getBytes(StandardCharsets.UTF_8));

    try {
      new OnboardingDatasetNormalizer(sampleDir, this::mockEntityForTable).buildDatasetXml();
      fail("Expected OBException for missing ID");
    } catch (OBException e) {
      // appendEntities() wraps the row-level guard in an OnboardingDatasetNormalizationException
      // that adds the offending file name; the original "Missing ID for entity" message is kept as
      // the cause, so assert against the full cause chain.
      assertTrue(causeChainContains(e, "Missing ID for entity"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // appendMappedField() — unknown columns (no mapped property) are silently dropped
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a sourcedata column with no mapped runtime property is skipped (the
   * {@code property == null} branch) while the row's other columns still convert.
   */
  @Test
  public void testNormalizerSkipsColumnWithoutMappedProperty() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-unknown-column");
    Files.write(sampleDir.resolve("C_PAYMENTTERM.xml"),
        ("<data><C_PAYMENTTERM>"
            + "<C_PAYMENTTERM_ID><![CDATA[PT1]]></C_PAYMENTTERM_ID>"
            + "<UNKNOWN_COLUMN><![CDATA[ignored]]></UNKNOWN_COLUMN>"
            + "<NAME><![CDATA[Immediate]]></NAME>"
            + "</C_PAYMENTTERM></data>").getBytes(StandardCharsets.UTF_8));

    String xml = new OnboardingDatasetNormalizer(sampleDir, this::mockEntityWithUnknownColumn)
        .buildDatasetXml();

    assertTrue(xml.contains("id=\"PT1\""));
    assertTrue(xml.contains("Immediate"));
    assertFalse(xml.contains("ignored"));
  }

  // ---------------------------------------------------------------------------------------------
  // dalReferenceIdResolver() — default resolver passthrough + cached language lookup
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies the default DAL-backed resolver passes non-language references through unchanged
   * (the {@code !Language.ENTITY_NAME.equals(...)} branch). A reference column targeting a
   * non-language entity must keep its raw value; no DAL lookup happens.
   */
  @Test
  public void testDefaultResolverPassesNonLanguageReferencesThrough() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-nonlanguage-ref");
    Files.write(sampleDir.resolve("C_PAYMENTTERM.xml"),
        ("<data><C_PAYMENTTERM>"
            + "<C_PAYMENTTERM_ID><![CDATA[PT1]]></C_PAYMENTTERM_ID>"
            + "<C_CURRENCY_ID><![CDATA[CUR-1]]></C_CURRENCY_ID>"
            + "</C_PAYMENTTERM></data>").getBytes(StandardCharsets.UTF_8));

    // Default resolver (DAL-backed) is used because only the EntityResolver overload is supplied.
    String xml = new OnboardingDatasetNormalizer(sampleDir, this::mockCurrencyReferenceEntity)
        .buildDatasetXml();

    assertTrue(xml.contains("id=\"CUR-1\""));
  }

  /**
   * Verifies the default DAL-backed resolver translates an {@code ADLanguage} reference code to its
   * installed {@code AD_Language} DAL id, exercising the {@code computeIfAbsent} +
   * {@code resolveInstalledLanguageId} happy path under a mocked {@link OBDal}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testDefaultResolverResolvesInstalledLanguageId() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-language-dal");
    Files.write(sampleDir.resolve("C_ELEMENTVALUE_TRL.xml"),
        ("<data><C_ELEMENTVALUE_TRL>"
            + "<C_ELEMENTVALUE_TRL_ID><![CDATA[ROW1]]></C_ELEMENTVALUE_TRL_ID>"
            + "<AD_LANGUAGE><![CDATA[es_ES]]></AD_LANGUAGE>"
            + "</C_ELEMENTVALUE_TRL></data>").getBytes(StandardCharsets.UTF_8));

    Language language = mock(Language.class);
    when(language.getId()).thenReturn("140");

    OBDal dal = mock(OBDal.class);
    OBCriteria<Language> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Language.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(language);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      String xml = new OnboardingDatasetNormalizer(sampleDir, this::mockLanguageReferenceEntityForTable)
          .buildDatasetXml();
      assertTrue(xml.contains("<adLanguage id=\"140\""));
    }
  }

  /**
   * Verifies the default DAL-backed resolver throws when the referenced language code is not
   * installed, exercising the {@code language == null} guard in {@code resolveInstalledLanguageId}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testDefaultResolverFailsForUninstalledLanguage() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-language-missing");
    Files.write(sampleDir.resolve("C_ELEMENTVALUE_TRL.xml"),
        ("<data><C_ELEMENTVALUE_TRL>"
            + "<C_ELEMENTVALUE_TRL_ID><![CDATA[ROW1]]></C_ELEMENTVALUE_TRL_ID>"
            + "<AD_LANGUAGE><![CDATA[xx_XX]]></AD_LANGUAGE>"
            + "</C_ELEMENTVALUE_TRL></data>").getBytes(StandardCharsets.UTF_8));

    OBDal dal = mock(OBDal.class);
    OBCriteria<Language> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Language.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      new OnboardingDatasetNormalizer(sampleDir, this::mockLanguageReferenceEntityForTable)
          .buildDatasetXml();
      fail("Expected OBException for uninstalled language");
    } catch (OBException e) {
      // The language guard fires inside convertRow() and is wrapped with the source-file context by
      // appendEntities(); the "not installed" message is preserved as the cause.
      assertTrue(causeChainContains(e, "not installed"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // resolveEntity() — an unmapped table raises an OBException
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a sourcedata table the runtime model cannot resolve raises an {@link OBException}
   * naming the table, exercising the {@code entity == null} guard in {@code resolveEntity}.
   */
  @Test
  public void testNormalizerFailsForUnmappedTable() throws Exception {
    Path sampleDir = Files.createTempDirectory("onboarding-unmapped-table");
    Files.write(sampleDir.resolve("C_PAYMENTTERM.xml"),
        ("<data><C_PAYMENTTERM>"
            + "<C_PAYMENTTERM_ID><![CDATA[PT1]]></C_PAYMENTTERM_ID>"
            + "</C_PAYMENTTERM></data>").getBytes(StandardCharsets.UTF_8));

    try {
      new OnboardingDatasetNormalizer(sampleDir, tableName -> null).buildDatasetXml();
      fail("Expected OBException for unmapped table");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("is not mapped in the runtime model"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // directorySourceFileProvider() — listing a missing directory fails fast
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a directory-backed normalizer pointed at a non-existent path surfaces an
   * {@link OnboardingDatasetNormalizationException}, exercising the listing catch block.
   */
  @Test
  public void testNormalizerFailsWhenSourceDirectoryMissing() {
    Path missing = Paths.get("modules", "com.etendoerp.go", "no-such-onboarding-dir-12345");

    try {
      new OnboardingDatasetNormalizer(missing).buildDatasetXml();
      fail("Expected OnboardingDatasetNormalizationException for missing directory");
    } catch (OnboardingDatasetNormalizationException e) {
      assertTrue(e.getMessage().contains("Failed to list onboarding sourcedata"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // readBundledSourceFileNames() — classpath index missing / empty
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies that a classpath without the bundled sampledata index raises an
   * {@link OnboardingDatasetNormalizationException}, exercising the {@code inputStream == null}
   * branch in {@code readBundledSourceFileNames}.
   */
  @Test
  public void testNormalizerFailsWhenBundledIndexMissing() {
    ClassLoader emptyClassLoader = new ClassLoader(null) {
      @Override
      public java.io.InputStream getResourceAsStream(String name) {
        return null;
      }
    };

    try {
      new OnboardingDatasetNormalizer(emptyClassLoader, this::mockEntityForTable).buildDatasetXml();
      fail("Expected OnboardingDatasetNormalizationException for missing index");
    } catch (OnboardingDatasetNormalizationException e) {
      assertTrue(e.getMessage().contains("index not found on the classpath"));
    }
  }

  /**
   * Verifies that a classpath whose sampledata index is present but blank raises an
   * {@link OnboardingDatasetNormalizationException}, exercising the {@code fileNames.isEmpty()}
   * branch in {@code readBundledSourceFileNames}.
   */
  @Test
  public void testNormalizerFailsWhenBundledIndexEmpty() {
    ClassLoader blankIndexClassLoader = new ClassLoader(null) {
      @Override
      public java.io.InputStream getResourceAsStream(String name) {
        if (name.endsWith("index.txt")) {
          return new java.io.ByteArrayInputStream("   \n\n".getBytes(StandardCharsets.UTF_8));
        }
        return null;
      }
    };

    try {
      new OnboardingDatasetNormalizer(blankIndexClassLoader, this::mockEntityForTable)
          .buildDatasetXml();
      fail("Expected OnboardingDatasetNormalizationException for empty index");
    } catch (OnboardingDatasetNormalizationException e) {
      assertTrue(e.getMessage().contains("index is empty"));
    }
  }

  /**
   * Verifies that a missing bundled sourcedata file (listed in the index but absent from the
   * classpath) surfaces as a normalization failure, exercising the {@code inputStream == null}
   * branch of {@code openBundledSourceFile}.
   */
  @Test
  public void testNormalizerFailsWhenBundledSourceFileMissing() {
    ClassLoader danglingFileClassLoader = new ClassLoader(null) {
      @Override
      public java.io.InputStream getResourceAsStream(String name) {
        if (name.endsWith("index.txt")) {
          return new java.io.ByteArrayInputStream(
              "C_PAYMENTTERM.xml\n".getBytes(StandardCharsets.UTF_8));
        }
        // The listed sourcedata file is not actually present on the classpath.
        return null;
      }
    };

    try {
      new OnboardingDatasetNormalizer(danglingFileClassLoader, this::mockEntityForTable)
          .buildDatasetXml();
      fail("Expected OnboardingDatasetNormalizationException for missing bundled file");
    } catch (OnboardingDatasetNormalizationException e) {
      assertTrue(e.getMessage().contains("Failed to normalize sourcedata file"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // defaultClassLoader() — no-arg constructor resolves a working class loader
  // ---------------------------------------------------------------------------------------------

  /**
   * Verifies the no-arg constructor resolves a usable default class loader and produces a
   * normalizer. The thread context class loader is cleared so the
   * {@code OnboardingDatasetNormalizer.class.getClassLoader()} fallback branch is taken.
   */
  @Test
  public void testNoArgConstructorUsesClassLoaderFallback() {
    Thread current = Thread.currentThread();
    ClassLoader previous = current.getContextClassLoader();
    try {
      current.setContextClassLoader(null);
      assertNotNull(new OnboardingDatasetNormalizer());
    } finally {
      current.setContextClassLoader(previous);
    }
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

  /**
   * Builds an entity that maps the row id and {@code NAME} columns but reports no property for
   * {@code UNKNOWN_COLUMN}, so the {@code property == null} skip branch is exercised.
   */
  private Entity mockEntityWithUnknownColumn(String tableName) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(toLowerCamel(tableName));
    when(entity.getTableName()).thenReturn(tableName);
    when(entity.isOrganizationEnabled()).thenReturn(false);
    when(entity.getPropertyByColumnName(anyString(), eq(false)))
        .thenAnswer(invocation -> {
          String columnName = invocation.getArgument(0);
          return "UNKNOWN_COLUMN".equals(columnName) ? null : mockProperty(tableName, columnName);
        });
    return entity;
  }

  /**
   * Builds an entity whose {@code C_CURRENCY_ID} column is a non-primitive, non-language reference,
   * so the default DAL resolver's passthrough branch is exercised without any DAL lookup.
   */
  private Entity mockCurrencyReferenceEntity(String tableName) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(toLowerCamel(tableName));
    when(entity.getTableName()).thenReturn(tableName);
    when(entity.isOrganizationEnabled()).thenReturn(false);
    when(entity.getPropertyByColumnName(anyString(), eq(false)))
        .thenAnswer(invocation -> {
          String columnName = invocation.getArgument(0);
          return "C_CURRENCY_ID".equals(columnName)
              ? mockReferenceProperty(columnName, "Currency")
              : mockProperty(tableName, columnName);
        });
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
