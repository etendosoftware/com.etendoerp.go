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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Converts GOClient sourcedata into Openbravo entity XML so it can be consumed by
 * {@code DataImportService.importDataFromXML(...)}.
 */
public class OnboardingDatasetNormalizer {

  private static final String SAMPLE_DATA_RESOURCE_ROOT =
      "com/etendoerp/go/onboarding/sampledata";
  private static final String SAMPLE_DATA_RESOURCE_DIRECTORY =
      SAMPLE_DATA_RESOURCE_ROOT + "/GOClient";
  private static final String SAMPLE_DATA_INDEX_RESOURCE =
      SAMPLE_DATA_RESOURCE_ROOT + "/index.txt";
  private static final String RESOURCE_PATH_SEPARATOR = "/";

  private final SourceFileProvider sourceFileProvider;
  private final EntityResolver entityResolver;
  private final ReferenceIdResolver referenceIdResolver;
  /**
   * Creates a normalizer that reads the packaged GOClient sourcedata from the runtime classpath.
   */
  public OnboardingDatasetNormalizer() {
    this(classpathSourceFileProvider(defaultClassLoader()), modelProviderEntityResolver());
  }

  OnboardingDatasetNormalizer(ClassLoader classLoader, EntityResolver entityResolver) {
    this(classpathSourceFileProvider(Objects.requireNonNull(classLoader, "classLoader is required")),
        entityResolver);
  }

  OnboardingDatasetNormalizer(ClassLoader classLoader, EntityResolver entityResolver,
      ReferenceIdResolver referenceIdResolver) {
    this(classpathSourceFileProvider(Objects.requireNonNull(classLoader, "classLoader is required")),
        entityResolver, referenceIdResolver);
  }

  /**
   * Creates a normalizer that reads onboarding sourcedata from the provided directory.
   *
   * @param sampleDataDirectory the directory that contains the GOClient sourcedata XML files
   */
  public OnboardingDatasetNormalizer(Path sampleDataDirectory) {
    this(sampleDataDirectory, modelProviderEntityResolver());
  }

  OnboardingDatasetNormalizer(Path sampleDataDirectory, EntityResolver entityResolver) {
    this(directorySourceFileProvider(Objects.requireNonNull(sampleDataDirectory,
        "sampleDataDirectory is required")), entityResolver);
  }

  OnboardingDatasetNormalizer(Path sampleDataDirectory, EntityResolver entityResolver,
      ReferenceIdResolver referenceIdResolver) {
    this(directorySourceFileProvider(Objects.requireNonNull(sampleDataDirectory,
        "sampleDataDirectory is required")), entityResolver, referenceIdResolver);
  }

  private OnboardingDatasetNormalizer(SourceFileProvider sourceFileProvider,
      EntityResolver entityResolver) {
    this(sourceFileProvider, entityResolver, dalReferenceIdResolver());
  }

  private OnboardingDatasetNormalizer(SourceFileProvider sourceFileProvider,
      EntityResolver entityResolver, ReferenceIdResolver referenceIdResolver) {
    this.sourceFileProvider = Objects.requireNonNull(sourceFileProvider,
        "sourceFileProvider is required");
    this.entityResolver = Objects.requireNonNull(entityResolver, "entityResolver is required");
    this.referenceIdResolver = Objects.requireNonNull(referenceIdResolver,
        "referenceIdResolver is required");
  }

  /**
   * Builds onboarding dataset XML without remapping organization ownership.
   *
   * @return the normalized Openbravo XML ready to be imported
   */
  public String buildDatasetXml() {
    return buildDatasetXml(null);
  }

  /**
   * Builds onboarding dataset XML and remaps non-system records to the target organization when provided.
   *
   * @param targetOrganizationId the organization that should own imported business data, or {@code null}
   * @return the normalized Openbravo XML ready to be imported
   */
  public String buildDatasetXml(String targetOrganizationId) {
    DocumentBuilder builder = newDocumentBuilder();
    Document output = builder.newDocument();
    Element root = output.createElement("Openbravo");
    root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
    output.appendChild(root);

    // Per-build state so repeated calls never leak excluded ids into one another.
    RowExclusionFilter rowExclusionFilter = new RowExclusionFilter();
    for (SourceFile sourceFile : listIncludedSourceFiles()) {
      appendEntities(sourceFile, builder, output, root, targetOrganizationId, rowExclusionFilter);
    }

    return toXml(output);
  }

  private void appendEntities(SourceFile sourceFile, DocumentBuilder builder, Document output, Element root,
      String targetOrganizationId, RowExclusionFilter rowExclusionFilter) {
    Entity entity = resolveEntity(tableName(sourceFile.fileName));
    try (InputStream inputStream = sourceFile.openStream()) {
      Document source = builder.parse(inputStream);
      NodeList childNodes = source.getDocumentElement().getChildNodes();
      for (int i = 0; i < childNodes.getLength(); i++) {
        Node child = childNodes.item(i);
        if (child instanceof Element) {
          Element converted = convertRow((Element) child, entity, targetOrganizationId, output,
              rowExclusionFilter);
          if (converted != null) {
            root.appendChild(converted);
          }
        }
      }
    } catch (Exception e) {
      throw new OnboardingDatasetNormalizationException(
          "Failed to normalize sourcedata file " + sourceFile.fileName, e);
    }
  }

  private Element convertRow(Element sourceRow, Entity entity, String targetOrganizationId,
      Document output, RowExclusionFilter rowExclusionFilter) {
    Map<String, String> rawColumns = readRawColumns(sourceRow);
    if (rowExclusionFilter.isExcludedRow(entity.getTableName(), rawColumns)) {
      return null;
    }

    Element entityElement = output.createElement(entity.getName());
    RowConversionState rowState = new RowConversionState();

    NodeList children = sourceRow.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element) {
        appendMappedField((Element) child, entity, entityElement, rowState, output);
      }
    }

    if (rowState.rowId == null) {
      throw new OBException("Missing ID for entity " + entity.getName());
    }
    entityElement.setAttribute("id", rowState.rowId);

    appendOrganizationReferenceIfNeeded(output, entityElement, entity, rowState.sourceOrganizationId,
        targetOrganizationId);
    return entityElement;
  }

  private void appendMappedField(Element sourceField, Entity entity, Element entityElement,
      RowConversionState rowState, Document output) {
    String columnName = sourceField.getTagName();
    String rawValue = normalizeFieldValue(sourceField.getTextContent());

    if (shouldSkipColumn(entity, columnName, rawValue)) {
      return;
    }

    if ("AD_ORG_ID".equals(columnName)) {
      rowState.sourceOrganizationId = rawValue;
      return;
    }

    Property property = entity.getPropertyByColumnName(columnName, false);
    if (property == null) {
      return;
    }
    if (property.isId()) {
      rowState.rowId = rawValue;
      return;
    }
    if (!property.isOneToMany()) {
      appendPropertyElement(output, entityElement, property, rawValue);
    }
  }

  /**
   * Reads the raw sourcedata columns of a row into a case-insensitive map (keys upper-cased),
   * so row-level filters can inspect ownership and parent references before conversion.
   */
  private Map<String, String> readRawColumns(Element sourceRow) {
    Map<String, String> columns = new HashMap<>();
    NodeList children = sourceRow.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element) {
        Element field = (Element) child;
        columns.put(field.getTagName().toUpperCase(), normalizeFieldValue(field.getTextContent()));
      }
    }
    return columns;
  }

  private String normalizeFieldValue(String rawValue) {
    return rawValue == null ? null : rawValue.trim();
  }

  private boolean shouldSkipColumn(Entity entity, String columnName, String rawValue) {
    return rawValue == null
        || rawValue.isEmpty()
        || "AD_CLIENT_ID".equals(columnName)
        || OnboardingDatasetDefinition.isStrippedColumn(entity.getTableName(), columnName);
  }

  private void appendPropertyElement(Document output, Element entityElement, Property property,
      String rawValue) {
    Element propertyElement = output.createElement(property.getName());
    if (property.isPrimitive()) {
      propertyElement.setTextContent(rawValue);
    } else {
      propertyElement.setAttribute("id", resolveReferenceId(property, rawValue));
    }
    entityElement.appendChild(propertyElement);
  }

  private void appendOrganizationReferenceIfNeeded(Document output, Element entityElement, Entity entity,
      String sourceOrganizationId, String targetOrganizationId) {
    if (!entity.isOrganizationEnabled() || sourceOrganizationId == null || sourceOrganizationId.isBlank()) {
      return;
    }

    String resolvedOrganizationId = "0".equals(sourceOrganizationId)
        ? "0"
        : targetOrganizationId;
    if (resolvedOrganizationId == null || resolvedOrganizationId.isBlank()) {
      return;
    }

    Element organizationElement = output.createElement("organization");
    organizationElement.setAttribute("id", resolvedOrganizationId);
    entityElement.appendChild(organizationElement);
  }

  /**
   * Resolves the value emitted as the {@code id} attribute of a reference property.
   *
   * <p>Most sourcedata reference columns already carry the referenced row's DAL id, so the raw value
   * is passed through unchanged. The exception is the {@code AD_LANGUAGE} column on translation
   * (_TRL) tables: GOClient stores the language <em>code</em> there (e.g. {@code es_ES}), but the
   * importer resolves references by DAL id ({@code AD_Language.AD_Language_ID}). For
   * {@code ADLanguage} references the code is therefore resolved to its installed id; the resolution
   * targets the always-present {@code AD_Language} master table, never GOAdmin.
   */
  private String resolveReferenceId(Property property, String rawValue) {
    Entity targetEntity = property.getTargetEntity();
    String targetEntityName = targetEntity == null ? null : targetEntity.getName();
    return referenceIdResolver.resolve(targetEntityName, rawValue);
  }

  /**
   * DAL-backed reference resolver. Language codes are resolved to their installed
   * {@code AD_Language} DAL id (cached per build); all other references pass through unchanged.
   */
  private static ReferenceIdResolver dalReferenceIdResolver() {
    Map<String, String> languageIdByCode = new HashMap<>();
    return (targetEntityName, rawValue) -> {
      if (!Language.ENTITY_NAME.equals(targetEntityName)) {
        return rawValue;
      }
      return languageIdByCode.computeIfAbsent(rawValue,
          OnboardingDatasetNormalizer::resolveInstalledLanguageId);
    };
  }

  private static String resolveInstalledLanguageId(String languageCode) {
    OBCriteria<Language> criteria = OBDal.getInstance().createCriteria(Language.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Language.PROPERTY_LANGUAGE, languageCode));
    criteria.setMaxResults(1);
    Language language = (Language) criteria.uniqueResult();
    if (language == null) {
      throw new OBException("Onboarding dataset references language '" + languageCode
          + "' which is not installed in this Etendo instance");
    }
    return language.getId();
  }

  private Entity resolveEntity(String tableName) {
    Entity entity = entityResolver.resolve(tableName);
    if (entity == null) {
      throw new OBException("Table " + tableName + " is not mapped in the runtime model");
    }
    return entity;
  }

  private static ClassLoader defaultClassLoader() {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    return contextClassLoader != null
        ? contextClassLoader
        : OnboardingDatasetNormalizer.class.getClassLoader();
  }


  private static EntityResolver modelProviderEntityResolver() {
    return tableName -> ModelProvider.getInstance().getEntityByTableName(tableName);
  }

  private List<SourceFile> listIncludedSourceFiles() {
    return sourceFileProvider.listIncludedSourceFiles();
  }

  private static SourceFileProvider directorySourceFileProvider(Path sampleDataDirectory) {
    return () -> {
      List<SourceFile> files = new ArrayList<>();
      try (var stream = Files.list(sampleDataDirectory)) {
        stream.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(".xml"))
            .filter(path -> OnboardingDatasetDefinition.shouldIncludeTable(
                tableName(path.getFileName().toString())))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .forEach(path -> files.add(new SourceFile(path.getFileName().toString(),
                () -> openFileSystemSourceFile(path))));
      } catch (Exception e) {
        throw new OnboardingDatasetNormalizationException(
            "Failed to list onboarding sourcedata in " + sampleDataDirectory, e);
      }
      return files;
    };
  }

  private static SourceFileProvider classpathSourceFileProvider(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "classLoader is required");
    return () -> {
      List<SourceFile> files = new ArrayList<>();
      for (String fileName : readBundledSourceFileNames(classLoader)) {
        if (fileName.endsWith(".xml")
            && OnboardingDatasetDefinition.shouldIncludeTable(tableName(fileName))) {
          files.add(new SourceFile(fileName, () -> openBundledSourceFile(classLoader, fileName)));
        }
      }
      files.sort(Comparator.comparing(sourceFile -> sourceFile.fileName));
      return files;
    };
  }

  private static List<String> readBundledSourceFileNames(ClassLoader classLoader) {
    try (InputStream inputStream = classLoader.getResourceAsStream(SAMPLE_DATA_INDEX_RESOURCE)) {
      if (inputStream == null) {
        throw new OnboardingDatasetNormalizationException(
            "Bundled GOClient sampledata index not found on the classpath: "
                + SAMPLE_DATA_INDEX_RESOURCE);
      }

      List<String> fileNames = new ArrayList<>();
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String fileName = line.trim();
          if (!fileName.isEmpty()) {
            fileNames.add(fileName);
          }
        }
      }

      if (fileNames.isEmpty()) {
        throw new OnboardingDatasetNormalizationException(
            "Bundled GOClient sampledata index is empty: " + SAMPLE_DATA_INDEX_RESOURCE);
      }
      return fileNames;
    } catch (IOException e) {
      throw new OnboardingDatasetNormalizationException(
          "Failed to read bundled GOClient sampledata index " + SAMPLE_DATA_INDEX_RESOURCE, e);
    }
  }

  private static InputStream openFileSystemSourceFile(Path path) throws SourceFileAccessException {
    try {
      return Files.newInputStream(path);
    } catch (IOException e) {
      throw new SourceFileAccessException(
          "Failed to open onboarding sourcedata file " + path.getFileName(), e);
    }
  }

  private static InputStream openBundledSourceFile(ClassLoader classLoader, String fileName)
      throws SourceFileAccessException {
    String resourcePath = String.join(RESOURCE_PATH_SEPARATOR, SAMPLE_DATA_RESOURCE_DIRECTORY, fileName);
    InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
    if (inputStream == null) {
      throw new SourceFileAccessException(
          "Bundled GOClient sampledata file not found on the classpath: " + resourcePath);
    }
    return inputStream;
  }

  private static String tableName(String sourceFileName) {
    int suffix = sourceFileName.lastIndexOf('.');
    return suffix == -1 ? sourceFileName : sourceFileName.substring(0, suffix);
  }

  private DocumentBuilder newDocumentBuilder() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
      setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder();
    } catch (Exception e) {
      throw new OnboardingDatasetNormalizationException(
          "Failed to create a secure XML parser for onboarding sourcedata", e);
    }
  }

  private String toXml(Document document) {
    try {
      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      setAttributeIfSupported(transformerFactory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
      setAttributeIfSupported(transformerFactory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(writer));
      return writer.toString();
    } catch (Exception e) {
      throw new OnboardingDatasetNormalizationException(
          "Failed to serialize onboarding dataset XML", e);
    }
  }

  private void setAttributeIfSupported(DocumentBuilderFactory factory, String attribute,
      String value) {
    try {
      factory.setAttribute(attribute, value);
    } catch (IllegalArgumentException ignored) {
      // Older XML implementations may not expose these JAXP attributes.
    }
  }

  private void setAttributeIfSupported(TransformerFactory factory, String attribute,
      String value) {
    try {
      factory.setAttribute(attribute, value);
    } catch (IllegalArgumentException ignored) {
      // Older XML implementations may not expose these JAXP attributes.
    }
  }

  /**
   * Resolves the runtime entity metadata for a sourcedata table name.
   */
  @FunctionalInterface
  interface EntityResolver {
    /**
     * Returns the entity mapped to the provided database table name.
     *
     * @param tableName the sourcedata table name
     * @return the mapped entity, or {@code null} when the table is not part of the runtime model
     */
    Entity resolve(String tableName);
  }

  /**
   * Resolves the {@code id} attribute value for a reference property, given the referenced entity's
   * name and the raw sourcedata value. Lets tests stub away the {@code AD_Language} DAL lookup.
   */
  @FunctionalInterface
  interface ReferenceIdResolver {
    /**
     * Returns the id to emit for a reference property.
     *
     * @param targetEntityName the referenced entity name, or {@code null} when unknown
     * @param rawValue         the raw sourcedata value of the reference column
     * @return the id attribute value to emit in the normalized XML
     */
    String resolve(String targetEntityName, String rawValue);
  }


  @FunctionalInterface
  private interface SourceFileProvider {
    List<SourceFile> listIncludedSourceFiles();
  }

  @FunctionalInterface
  private interface SourceFileOpener {
    InputStream open() throws SourceFileAccessException;
  }

  private static final class SourceFile {
    private final String fileName;
    private final SourceFileOpener opener;

    private SourceFile(String fileName, SourceFileOpener opener) {
      this.fileName = Objects.requireNonNull(fileName, "fileName is required");
      this.opener = Objects.requireNonNull(opener, "opener is required");
    }

    private InputStream openStream() throws SourceFileAccessException {
      return opener.open();
    }
  }
  private static final class SourceFileAccessException extends IOException {
    private static final long serialVersionUID = 1L;

    private SourceFileAccessException(String message) {
      super(message);
    }

    private SourceFileAccessException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class RowConversionState {
    private String rowId;
    private String sourceOrganizationId;
  }

  /**
   * Skips org-specific account-element trees so only the client-level
   * ({@code AD_ORG_ID = '0'}) chart of accounts reaches a new tenant.
   *
   * <p>GOClient ships a second, organization-owned {@code C_ELEMENT} tree that is not wired to any
   * accounting schema and has no valid combinations — a dangling chart. Importing it would create
   * an orphan chart of accounts in every onboarded tenant. This filter ignores it at import time
   * <em>without modifying the source dataset</em>: it drops the org-specific element row, then
   * cascades the exclusion to that element's {@code C_ELEMENTVALUE} rows and their
   * {@code C_ELEMENTVALUE_TRL} translations. The cascade relies on the alphabetical source-file
   * order ({@code C_ELEMENT} → {@code C_ELEMENTVALUE} → {@code C_ELEMENTVALUE_TRL}), which the
   * source providers guarantee.
   */
  private static final class AccountElementTreeFilter {
    private static final String ELEMENT_TABLE = "C_ELEMENT";
    private static final String ELEMENT_VALUE_TABLE = "C_ELEMENTVALUE";
    private static final String ELEMENT_VALUE_TRL_TABLE = "C_ELEMENTVALUE_TRL";
    private static final String CLIENT_LEVEL_ORG = "0";

    private final Set<String> excludedElementIds = new HashSet<>();
    private final Set<String> excludedElementValueIds = new HashSet<>();

    private boolean isExcludedRow(String tableName, Map<String, String> rawColumns) {
      if (tableName == null) {
        return false;
      }
      switch (tableName.toUpperCase()) {
        case ELEMENT_TABLE:
          return excludeOrgSpecificElement(rawColumns);
        case ELEMENT_VALUE_TABLE:
          return excludeValueOfExcludedElement(rawColumns);
        case ELEMENT_VALUE_TRL_TABLE:
          return excludedElementValueIds.contains(rawColumns.get("C_ELEMENTVALUE_ID"));
        default:
          return false;
      }
    }

    private boolean excludeOrgSpecificElement(Map<String, String> rawColumns) {
      String org = rawColumns.get("AD_ORG_ID");
      if (org == null || CLIENT_LEVEL_ORG.equals(org)) {
        return false;
      }
      String elementId = rawColumns.get("C_ELEMENT_ID");
      if (elementId != null) {
        excludedElementIds.add(elementId);
      }
      return true;
    }

    private boolean excludeValueOfExcludedElement(Map<String, String> rawColumns) {
      String parentElementId = rawColumns.get("C_ELEMENT_ID");
      if (parentElementId == null || !excludedElementIds.contains(parentElementId)) {
        return false;
      }
      String valueId = rawColumns.get("C_ELEMENTVALUE_ID");
      if (valueId != null) {
        excludedElementValueIds.add(valueId);
      }
      return true;
    }
  }

  /**
   * Aggregates the per-build stateful row filters applied while normalizing the dataset. Each
   * sub-filter owns mutable exclusion state, so a fresh instance is created per
   * {@link #buildDatasetXml(String)} call and shared across all source files of that build.
   */
  private static final class RowExclusionFilter {
    private final AccountElementTreeFilter accountElementTree = new AccountElementTreeFilter();
    private final DanglingCalendarFilter danglingCalendar = new DanglingCalendarFilter();

    private boolean isExcludedRow(String tableName, Map<String, String> rawColumns) {
      // Sub-filters operate on disjoint table sets, so a row excluded by one is never relevant to
      // the other; short-circuit evaluation keeps the unrelated filter's state untouched.
      return accountElementTree.isExcludedRow(tableName, rawColumns)
          || danglingCalendar.isExcludedRow(tableName, rawColumns);
    }
  }

  /**
   * Skips the dangling client-level fiscal calendar so only the operative calendar (remapped to the
   * new tenant's organization) reaches the tenant.
   *
   * <p>GOClient ships two calendars: an organization-owned one with a full year of periods and
   * period-control rows (the keeper, wired to the org by {@code OnboardingPeriodControlService}), and
   * a second, client-level ({@code AD_ORG_ID = '0'}) calendar whose single year has no periods — a
   * dangling empty calendar, the fiscal analogue of the orphan account-element tree. Importing it
   * would create a useless second calendar in every onboarded tenant. This filter ignores it at
   * import time <em>without modifying the source dataset</em>.
   *
   * <p>Unlike {@link AccountElementTreeFilter}, the cascade cannot rely on alphabetical source-file
   * order, because it does not match the fiscal hierarchy ({@code C_CALENDAR} &lt; {@code C_PERIOD}
   * &lt; {@code C_PERIODCONTROL} &lt; {@code C_YEAR} alphabetically, but the hierarchy is
   * calendar → year → period → period-control). Instead the filter keys on ownership: every
   * client-level ({@code AD_ORG_ID = '0'}) calendar, year, period and period-control row is dropped.
   * GOClient's only client-level fiscal rows are the dangling calendar and its empty year; all real
   * fiscal data lives at the operative organization level and is therefore kept. This rule is
   * order-independent and remains correct even if the dangling calendar ever ships periods.
   */
  private static final class DanglingCalendarFilter {
    private static final String CLIENT_LEVEL_ORG = "0";
    private static final Set<String> FISCAL_TABLES =
        Set.of("C_CALENDAR", "C_YEAR", "C_PERIOD", "C_PERIODCONTROL");

    private boolean isExcludedRow(String tableName, Map<String, String> rawColumns) {
      if (tableName == null || !FISCAL_TABLES.contains(tableName.toUpperCase())) {
        return false;
      }
      return CLIENT_LEVEL_ORG.equals(rawColumns.get("AD_ORG_ID"));
    }
  }
}
