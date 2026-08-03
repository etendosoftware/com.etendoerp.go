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
import static org.junit.Assert.assertNotNull;
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
 * Data-hygiene regression test for {@code referencedata/sampledata/GOClient/C_BP_GROUP_ACCT.xml}.
 *
 * <p>This static XML dump carried a stale row for {@code C_BP_GROUP_ACCT_ID =
 * 69081038A3AC421AB8DB93A096D58D57} that mirrored, byte-for-byte in identifying fields (same id,
 * same {@code CREATED} timestamp), the live NULL diagnosed for GOClient's
 * {@code notinvoicedreceipts_acct} column (ETP-4706, gap R17). The table is NOT part of {@link
 * OnboardingDatasetDefinition#getIncludedTables()} — only {@code C_BP_GROUP} is imported from this
 * dataset at onboarding time, and every tenant's {@code C_BP_Group_Acct} rows are instead computed
 * fresh at runtime by {@code OnboardingAccountingWiringService.BP_GROUP_ACCT_SQL}. So this file is
 * inert today: fixing it is pure historical-data hygiene, not a functional onboarding change. This
 * test guards against the stale row resurfacing (e.g. if the file is ever regenerated from a
 * broken source) and against {@code C_BP_GROUP_ACCT} ever being added to {@code INCLUDED_TABLES}
 * without first checking this data is correct.
 */
public class BpGroupAcctSampleDataTest {

  private static final String STALE_ROW_ID = "69081038A3AC421AB8DB93A096D58D57";

  /**
   * Value backfilled by the R17 corrective data-fix from {@code C_AcctSchema_Default
   * .notinvoicedreceipts_acct} for GOClient's account schema ({@code
   * C06B100312FA48159DB36B9A4B461019}) — confirmed live in the DB on 2026-07-29.
   */
  private static final String EXPECTED_NOTINVOICEDRECEIPTS_ACCT = "6E9DA718417A48A290FE376448A12BF6";

  @Test
  public void testStaleBpGroupAcctRowHasNotInvoicedReceiptsAcct()
      throws ParserConfigurationException, SAXException, IOException {
    Element row = findRow(STALE_ROW_ID);
    assertNotNull("C_BP_GROUP_ACCT row " + STALE_ROW_ID + " not found in sampledata", row);

    String actual = childText(row, "NOTINVOICEDRECEIPTS_ACCT");
    assertNotNull(
        "NOTINVOICEDRECEIPTS_ACCT element is missing from C_BP_GROUP_ACCT row " + STALE_ROW_ID,
        actual);
    assertEquals(EXPECTED_NOTINVOICEDRECEIPTS_ACCT, actual);
  }

  private Element findRow(String bpGroupAcctId)
      throws ParserConfigurationException, SAXException, IOException {
    Document document = parseSampleDataFile();
    NodeList rows = document.getElementsByTagName("C_BP_GROUP_ACCT");
    for (int i = 0; i < rows.getLength(); i++) {
      Element row = (Element) rows.item(i);
      if (bpGroupAcctId.equals(childText(row, "C_BP_GROUP_ACCT_ID"))) {
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

  private Document parseSampleDataFile()
      throws ParserConfigurationException, SAXException, IOException {
    Path file = sampleDataFile();
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(file.toFile());
  }

  private Path sampleDataFile() {
    Path moduleRelative = Paths.get("referencedata", "sampledata", "GOClient", "C_BP_GROUP_ACCT.xml");
    if (Files.exists(moduleRelative)) {
      return moduleRelative;
    }

    Path rootRelative = Paths.get("modules", "com.etendoerp.go", "referencedata", "sampledata",
        "GOClient", "C_BP_GROUP_ACCT.xml");
    if (Files.exists(rootRelative)) {
      return rootRelative;
    }

    fail("C_BP_GROUP_ACCT.xml sampledata file not found from current working directory");
    return null;
  }
}
