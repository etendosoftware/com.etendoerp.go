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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;

/**
 * Creates the hardened XML parser and serializer used to normalize onboarding sourcedata.
 */
final class OnboardingDatasetXmlSupport {

  private OnboardingDatasetXmlSupport() {
    // Utility class.
  }

  static DocumentBuilder newDocumentBuilder() {
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

  static String toXml(Document document) {
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

  private static void setAttributeIfSupported(DocumentBuilderFactory factory, String attribute,
      String value) {
    try {
      factory.setAttribute(attribute, value);
    } catch (IllegalArgumentException ignored) {
      // Older XML implementations may not expose these JAXP attributes.
    }
  }

  private static void setAttributeIfSupported(TransformerFactory factory, String attribute,
      String value) {
    try {
      factory.setAttribute(attribute, value);
    } catch (IllegalArgumentException ignored) {
      // Older XML implementations may not expose these JAXP attributes.
    }
  }
}
