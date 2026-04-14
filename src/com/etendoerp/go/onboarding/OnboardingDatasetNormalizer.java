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

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.session.OBPropertiesProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Converts GOClient sourcedata into Openbravo entity XML so it can be consumed by
 * {@code DataImportService.importDataFromXML(...)}.
 */
public class OnboardingDatasetNormalizer {

  private static final Path MODULE_RELATIVE_SAMPLE_DATA = Paths.get(
      "referencedata", "sampledata", "GOClient");
  private static final Path WORKSPACE_RELATIVE_SAMPLE_DATA = Paths.get(
      "etendo_core", "modules", "com.etendoerp.go", "referencedata", "sampledata", "GOClient");

  private final Path sampleDataDirectory;

  /**
   * Creates a normalizer that resolves the GOClient sourcedata directory from known module paths.
   */
  public OnboardingDatasetNormalizer() {
    this(resolveDefaultSampleDataDirectory());
  }

  /**
   * Creates a normalizer that reads onboarding sourcedata from the provided directory.
   *
   * @param sampleDataDirectory the directory that contains the GOClient sourcedata XML files
   */
  public OnboardingDatasetNormalizer(Path sampleDataDirectory) {
    this.sampleDataDirectory = sampleDataDirectory;
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

    for (Path sourceFile : listIncludedSourceFiles()) {
      appendEntities(sourceFile, builder, output, root, targetOrganizationId);
    }

    return toXml(output);
  }

  private void appendEntities(Path sourceFile, DocumentBuilder builder, Document output, Element root,
      String targetOrganizationId) {
    Entity entity = resolveEntity(tableName(sourceFile));
    try (var inputStream = Files.newInputStream(sourceFile)) {
      Document source = builder.parse(inputStream);
      NodeList childNodes = source.getDocumentElement().getChildNodes();
      for (int i = 0; i < childNodes.getLength(); i++) {
        Node child = childNodes.item(i);
        if (child instanceof Element) {
          root.appendChild(convertRow((Element) child, entity, targetOrganizationId, output));
        }
      }
    } catch (Exception e) {
      throw new OnboardingDatasetNormalizationException(
          "Failed to normalize sourcedata file " + sourceFile.getFileName(), e);
    }
  }

  private Element convertRow(Element sourceRow, Entity entity, String targetOrganizationId,
      Document output) {
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

    if (shouldSkipColumn(columnName, rawValue)) {
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

  private String normalizeFieldValue(String rawValue) {
    return rawValue == null ? null : rawValue.trim();
  }

  private boolean shouldSkipColumn(String columnName, String rawValue) {
    return rawValue == null
        || rawValue.isEmpty()
        || "AD_CLIENT_ID".equals(columnName)
        || OnboardingDatasetDefinition.getStrippedFields().contains(columnName);
  }

  private void appendPropertyElement(Document output, Element entityElement, Property property,
      String rawValue) {
    Element propertyElement = output.createElement(property.getName());
    if (property.isPrimitive()) {
      propertyElement.setTextContent(rawValue);
    } else {
      propertyElement.setAttribute("id", mapReferenceId(rawValue));
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

  private String mapReferenceId(String rawValue) {
    return rawValue;
  }

  private Entity resolveEntity(String tableName) {
    Entity entity = ModelProvider.getInstance().getEntityByTableName(tableName);
    if (entity == null) {
      throw new OBException("Table " + tableName + " is not mapped in the runtime model");
    }
    return entity;
  }

  private List<Path> listIncludedSourceFiles() {
    List<Path> files = new ArrayList<>();
    try (var stream = Files.list(sampleDataDirectory)) {
      stream.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".xml"))
          .filter(path -> OnboardingDatasetDefinition.shouldIncludeTable(tableName(path)))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(files::add);
    } catch (Exception e) {
      throw new OnboardingDatasetNormalizationException(
          "Failed to list onboarding sourcedata in " + sampleDataDirectory, e);
    }
    return files;
  }

  private String tableName(Path sourceFile) {
    String fileName = sourceFile.getFileName().toString();
    int suffix = fileName.lastIndexOf('.');
    return suffix == -1 ? fileName : fileName.substring(0, suffix);
  }

  private static Path resolveDefaultSampleDataDirectory() {
    List<Path> candidates = new ArrayList<>();
    String sourcePath = sourcePath();
    if (sourcePath != null && !sourcePath.isBlank()) {
      candidates.add(Paths.get(sourcePath, "modules", "com.etendoerp.go",
          MODULE_RELATIVE_SAMPLE_DATA.toString()));
    }
    candidates.add(MODULE_RELATIVE_SAMPLE_DATA);
    candidates.add(WORKSPACE_RELATIVE_SAMPLE_DATA);

    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new OnboardingDatasetNormalizationException(
        "GOClient sampledata directory not found in known locations: " + candidates);
  }

  private static String sourcePath() {
    try {
      Properties properties = OBPropertiesProvider.getInstance().getOpenbravoProperties();
      return properties == null ? null : properties.getProperty("source.path");
    } catch (Exception ignored) {
      return null;
    }
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
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
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
      transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
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

  private static final class RowConversionState {
    private String rowId;
    private String sourceOrganizationId;
  }
}
