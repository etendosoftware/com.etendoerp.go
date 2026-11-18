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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

/**
 * ETP-6247 — the XSD validation of a remittance's SEPA file.
 *
 * <p><b>Where the XSD comes from.</b> First next to the generator class, where Classic's own
 * {@code SEPA_RemittanceCreateFile} looks for it ({@code getClass().getResource(version + ".xsd")}),
 * so a schema version an administrator configured in Classic is validated against its own XSD.
 * Otherwise from the copies GO ships next to this class ({@code sepa/pain.008.001.08.xsd},
 * {@code sepa/pain.001.001.09.xsd}, the versions the provisioned types use), so the validation
 * never depends on how the SEPA modules were packaged. No XSD at all is a refusal of the caller.
 *
 * <p><b>Which parser.</b> Etendo's class path carries Xerces 2.9 ({@code xercesImpl-2.9.0.jar}),
 * which the JAXP factories resolve to. It predates JAXP 1.5 and refuses
 * {@link XMLConstants#ACCESS_EXTERNAL_DTD} / {@link XMLConstants#ACCESS_EXTERNAL_SCHEMA} with
 * {@link SAXNotRecognizedException} — which, caught as a validation failure, made every file
 * invalid. So each hardening setting is applied on its own and an unsupported one is skipped,
 * and external access is closed by means every parser has:
 * <ul>
 *   <li>the document is read through an {@link XMLReader} that refuses any DOCTYPE
 *       ({@code disallow-doctype-decl}) and never loads external entities or DTDs; a document
 *       with a DOCTYPE is therefore invalid, whatever the parser;</li>
 *   <li>the schema factory and the validator get a resource resolver that refuses every
 *       resolution: the two SEPA XSDs import nothing, so nothing legitimate is refused;</li>
 *   <li>{@link XMLConstants#FEATURE_SECURE_PROCESSING} on the factory, plus the JAXP 1.5
 *       properties where the parser knows them (a newer JDK parser).</li>
 * </ul>
 */
final class RemittanceSepaSchema {

  private static final Logger log = LogManager.getLogger(RemittanceSepaSchema.class);

  /** Folder of the XSDs GO ships, relative to this class. */
  static final String BUNDLED_FOLDER = "sepa/";
  private static final String XSD = ".xsd";

  static final String FEATURE_DISALLOW_DOCTYPE =
      "http://apache.org/xml/features/disallow-doctype-decl";
  static final String FEATURE_EXTERNAL_GENERAL_ENTITIES =
      "http://xml.org/sax/features/external-general-entities";
  static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES =
      "http://xml.org/sax/features/external-parameter-entities";
  static final String FEATURE_LOAD_EXTERNAL_DTD =
      "http://apache.org/xml/features/nonvalidating/load-external-dtd";

  /** Refuses every external resource a schema or a document asks for. */
  static final LSResourceResolver REFUSE_ALL = RemittanceSepaSchema::refuse;

  private RemittanceSepaSchema() {
  }

  /**
   * The XSD of {@code version}: next to {@code generatorClass}, else GO's bundled copy.
   *
   * @param generatorClass the SEPA generator's class
   * @param version        its schema version, e.g. {@code pain.008.001.08}
   * @return the XSD, or {@code null} when neither place has it
   */
  static URL locate(Class<?> generatorClass, String version) {
    if (StringUtils.isBlank(version)) {
      return null;
    }
    URL own = generatorClass != null ? generatorClass.getResource(version + XSD) : null;
    return own != null ? own : RemittanceSepaSchema.class.getResource(BUNDLED_FOLDER + version + XSD);
  }

  /**
   * Validates {@code xml} against {@code xsd}, without any external access.
   *
   * @param xsd the schema
   * @param xml the document, UTF-8
   * @return {@code null} when valid, otherwise the parser's message
   */
  static String failure(URL xsd, byte[] xml) {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING);
      setProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
      setProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
      factory.setResourceResolver(REFUSE_ALL);
      Validator validator = factory.newSchema(xsd).newValidator();
      setProperty(validator, XMLConstants.ACCESS_EXTERNAL_DTD);
      setProperty(validator, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
      validator.setResourceResolver(REFUSE_ALL);
      validator.validate(new SAXSource(secureReader(),
          new InputSource(new ByteArrayInputStream(xml))));
      return null;
    } catch (SAXException e) {
      return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
    } catch (IOException | ParserConfigurationException | RuntimeException e) {
      log.warn("Could not validate the SEPA file", e);
      return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
    }
  }

  /**
   * A namespace-aware reader that refuses a DOCTYPE and never reads anything external.
   */
  static XMLReader secureReader() throws ParserConfigurationException, SAXException {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setValidating(false);
    setFeature(factory, FEATURE_DISALLOW_DOCTYPE, true);
    setFeature(factory, FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
    setFeature(factory, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
    setFeature(factory, FEATURE_LOAD_EXTERNAL_DTD, false);
    XMLReader reader = factory.newSAXParser().getXMLReader();
    // Belt and braces for a parser that ignored the features above: no entity is ever resolved.
    reader.setEntityResolver((publicId, systemId) -> {
      throw new SAXException("External entity refused: " + systemId);
    });
    return reader;
  }

  // ---------------------------------------------------------------------------
  // One setting at a time: a parser that does not know one keeps the others
  // ---------------------------------------------------------------------------

  private static void setFeature(SchemaFactory factory, String feature) {
    try {
      factory.setFeature(feature, true);
    } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
      log.debug("Schema factory does not support {}", feature);
    }
  }

  private static void setProperty(SchemaFactory factory, String property) {
    try {
      factory.setProperty(property, "");
    } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
      log.debug("Schema factory does not support {}", property);
    }
  }

  private static void setProperty(Validator validator, String property) {
    try {
      validator.setProperty(property, "");
    } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
      log.debug("Validator does not support {}", property);
    }
  }

  private static void setFeature(SAXParserFactory factory, String feature, boolean value) {
    try {
      factory.setFeature(feature, value);
    } catch (ParserConfigurationException | SAXNotRecognizedException
        | SAXNotSupportedException e) {
      log.debug("SAX parser factory does not support {}", feature);
    }
  }

  private static LSInput refuse(String type, String namespaceUri, String publicId,
      String systemId, String baseUri) {
    throw new IllegalStateException("External resource refused: " + systemId);
  }
}
