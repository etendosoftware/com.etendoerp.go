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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Data-hygiene regression test for {@code src-db/database/sourcedata/AD_MESSAGE_TRL.xml}.
 *
 * <p>ETP-5175 QA follow-up (BUG-1): the Spanish ({@code es_ES}) {@code AD_MESSAGE_TRL} rows for
 * {@code ETGO_InvalidAccountBpAndGroup} (id {@code 30005C8B451348219FFEB80CE53FD1DB}) and {@code
 * ETGO_InvalidAccountBpOnly} (id {@code 0DCEE0AA0ED64DA3ACD8DEC732D11EED}) were missing the
 * enclosing parentheses that the English {@code AD_MESSAGE} base text has, producing a run-on
 * sentence when {@link DocumentPostingService} concatenates the business-partner detail clause
 * with the next sentence (e.g. "...Grupo de Terceros: Proveedora Revise la configuración
 * contable..." with no delimiter).
 *
 * <p>The original pinning regression test for this bug,
 * {@link DocumentPostingServiceTest#postComposesExactSpanishMessageForBpGroupAndProductScenario},
 * mocks {@code OBMessageUtils.messageBD(...)} directly with hardcoded Java strings — it never
 * reads this XML file, so it cannot catch a regression re-introduced here. THIS test closes that
 * gap by parsing the actual sourcedata file and asserting the two {@code MSGTEXT} values are
 * still wrapped in parentheses. It would have failed before commit {@code 71aeb484} (missing
 * parens) and passes after it.
 */
public class AccountErrorMessageTrlSampleDataTest {

  private static final String BP_AND_GROUP_ROW_ID = "30005C8B451348219FFEB80CE53FD1DB";
  private static final String BP_ONLY_ROW_ID = "0DCEE0AA0ED64DA3ACD8DEC732D11EED";

  @Test
  public void testInvalidAccountBpAndGroupSpanishMessageIsWrappedInParentheses()
      throws ParserConfigurationException, SAXException, IOException {
    assertMsgTextWrappedInParentheses(BP_AND_GROUP_ROW_ID);
  }

  @Test
  public void testInvalidAccountBpOnlySpanishMessageIsWrappedInParentheses()
      throws ParserConfigurationException, SAXException, IOException {
    assertMsgTextWrappedInParentheses(BP_ONLY_ROW_ID);
  }

  private void assertMsgTextWrappedInParentheses(String adMessageTrlId)
      throws ParserConfigurationException, SAXException, IOException {
    Element row = findRow(adMessageTrlId);
    assertNotNull("AD_MESSAGE_TRL row " + adMessageTrlId + " not found in sourcedata", row);

    String msgText = childText(row, "MSGTEXT");
    assertNotNull(
        "MSGTEXT element is missing from AD_MESSAGE_TRL row " + adMessageTrlId, msgText);
    assertTrue(
        "AD_MESSAGE_TRL row " + adMessageTrlId + " MSGTEXT must start with '(' — was: "
            + msgText,
        msgText.startsWith("("));
    assertTrue(
        "AD_MESSAGE_TRL row " + adMessageTrlId + " MSGTEXT must end with ')' — was: " + msgText,
        msgText.endsWith(")"));
  }

  private Element findRow(String adMessageTrlId)
      throws ParserConfigurationException, SAXException, IOException {
    Document document = parseSourceDataFile();
    NodeList rows = document.getElementsByTagName("AD_MESSAGE_TRL");
    for (int i = 0; i < rows.getLength(); i++) {
      Element row = (Element) rows.item(i);
      if (adMessageTrlId.equals(childText(row, "AD_MESSAGE_TRL_ID"))) {
        return row;
      }
    }
    return null;
  }

  private String childText(Element row, String tagName) {
    NodeList children = row.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element && tagName.equals(child.getNodeName())) {
        return child.getTextContent();
      }
    }
    return null;
  }

  private Document parseSourceDataFile()
      throws ParserConfigurationException, SAXException, IOException {
    Path file = sourceDataFile();
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(file.toFile());
  }

  private Path sourceDataFile() {
    Path moduleRelative = Paths.get("src-db", "database", "sourcedata", "AD_MESSAGE_TRL.xml");
    if (Files.exists(moduleRelative)) {
      return moduleRelative;
    }

    Path rootRelative = Paths.get("modules", "com.etendoerp.go", "src-db", "database",
        "sourcedata", "AD_MESSAGE_TRL.xml");
    if (Files.exists(rootRelative)) {
      return rootRelative;
    }

    fail("AD_MESSAGE_TRL.xml sourcedata file not found from current working directory");
    return null;
  }
}
