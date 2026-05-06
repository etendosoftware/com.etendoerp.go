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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Regression tests for Business Partner sequence metadata.
 */
public class BusinessPartnerSequenceMetadataTest {

  private static final String BUSINESS_PARTNER_IDENTIFIER_COLUMN_ID =
      "294937FFC81749289BD9BB28E400D4B2";
  private static final String TRANSACTIONAL_SEQUENCE_REFERENCE_ID =
      "B82E1C56F57749AD97DD9924624F08D3";

  @Test
  public void testEtgoIdentifierColumnIsConfiguredForTransactionalSequence() throws Exception {
    Node column = findBusinessPartnerIdentifierColumn();

    assertEquals("EM_Etgo_Identifier", text(column, "COLUMNNAME"));
    assertEquals(TRANSACTIONAL_SEQUENCE_REFERENCE_ID, text(column, "AD_REFERENCE_ID"));
    assertEquals("Y", text(column, "ISUSEDSEQUENCE"));
  }

  private Node findBusinessPartnerIdentifierColumn() throws Exception {
    Document document = parseAdColumnMetadata();
    XPath xpath = XPathFactory.newInstance().newXPath();
    return (Node) xpath.evaluate(
        "/data/AD_COLUMN[AD_COLUMN_ID='" + BUSINESS_PARTNER_IDENTIFIER_COLUMN_ID + "']",
        document, XPathConstants.NODE);
  }

  private Document parseAdColumnMetadata() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder()
        .parse(new File("src-db/database/sourcedata/AD_COLUMN.xml"));
  }

  private String text(Node node, String fieldName) throws Exception {
    XPath xpath = XPathFactory.newInstance().newXPath();
    return xpath.evaluate(fieldName + "/text()", node).trim();
  }
}
