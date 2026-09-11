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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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
 * Guards that the GOClient onboarding dataset ships the Reconciliation ({@code REC}) document type
 * and its document-number sequence.
 *
 * <p>Without them, EVERY freshly onboarded tenant is unable to reconcile anything. Both the cash
 * close ({@code CashCloseHandler.createDraft} → {@code FIN_Utility.getDocumentType(org, "REC")}) and
 * the bank reconciliation (Core's {@code APRM_MatchingUtility.addNewDraftReconciliation} →
 * {@code AD_GET_DOCTYPE(..., 'REC')}) resolve that document type before creating the draft, and
 * both fail with a business error when it is missing. That is exactly what happened: the dataset
 * shipped 48 document types covering 32 base types, {@code REC} not among them, so confirming a
 * cash close on a new tenant returned HTTP 400 "No 'REC' document type configured for
 * organization…" while the same flow worked on GOClient itself, which does have the record.</p>
 *
 * <p>The document types are provisioned ONLY by this dataset — the old {@code CreateDocTypesStep}
 * that used to create them programmatically was removed by ETP-4428 in favour of the dataset
 * import — so these XML files are the single source of truth and this test is the only cheap place
 * to catch the omission. Same shape as {@link BpGroupAcctSampleDataTest}: it reads the shipped XML,
 * no database involved.</p>
 */
public class ReconciliationDocTypeSampleDataTest {

  private static final String REC = "REC";
  private static final String SEQUENCE_START = "1000000";

  /** The REC document type must exist, exactly once, and be document-number controlled. */
  @Test
  public void testDatasetShipsTheReconciliationDocumentType() throws Exception {
    List<Element> recRows = new ArrayList<>();
    for (Element row : rows("C_DOCTYPE.xml", "C_DOCTYPE")) {
      if (REC.equals(childText(row, "DOCBASETYPE"))) {
        recRows.add(row);
      }
    }

    assertEquals("the dataset must ship exactly one REC (Reconciliation) document type,"
        + " otherwise no new tenant can close a cash drawer or reconcile a bank account",
        1, recRows.size());

    Element rec = recRows.get(0);
    assertEquals("a reconciliation document must take its number from a sequence",
        "Y", childText(rec, "ISDOCNOCONTROLLED"));
    assertEquals("Y", childText(rec, "ISACTIVE"));
    assertNotNull("the REC document type must reference its document-number sequence",
        childText(rec, "DOCNOSEQUENCE_ID"));
    assertNotNull("FIN_Utility.getDocumentType resolves by base type, but the AD table binding is"
        + " what makes the document type usable from the Reconciliation window",
        childText(rec, "AD_TABLE_ID"));
  }

  /**
   * The sequence the REC document type points at must be shipped too, as an auto-sequence starting
   * from scratch — carrying a development counter over would make a brand-new tenant start
   * numbering its reconciliations wherever the source instance happened to be.
   */
  @Test
  public void testDatasetShipsTheReconciliationSequence() throws Exception {
    String sequenceId = null;
    for (Element row : rows("C_DOCTYPE.xml", "C_DOCTYPE")) {
      if (REC.equals(childText(row, "DOCBASETYPE"))) {
        sequenceId = childText(row, "DOCNOSEQUENCE_ID");
      }
    }
    assertNotNull("no REC document type to resolve the sequence from", sequenceId);

    Element sequence = null;
    for (Element row : rows("AD_SEQUENCE.xml", "AD_SEQUENCE")) {
      if (sequenceId.equals(childText(row, "AD_SEQUENCE_ID"))) {
        sequence = row;
      }
    }

    assertNotNull("AD_SEQUENCE.xml must ship the sequence the REC document type points at ("
        + sequenceId + "); a dangling reference leaves the document type unusable", sequence);
    assertEquals("Y", childText(sequence, "ISAUTOSEQUENCE"));
    assertEquals("Y", childText(sequence, "ISACTIVE"));
    assertEquals(SEQUENCE_START, childText(sequence, "STARTNO"));
    assertEquals("a new tenant must start numbering from STARTNO, not from the source instance's"
        + " current counter", SEQUENCE_START, childText(sequence, "CURRENTNEXT"));
  }

  /**
   * Every table backing the rows above has to be in the import contract, or those rows never reach
   * a tenant.
   *
   * <p>{@code C_DOCTYPE_TRL} was added to this assertion by ETP-5079, and the reason is a warning
   * worth keeping: it was MISSING from {@code INCLUDED_TABLES} while
   * {@link #testTheDocumentTypeIsTranslated()} right below happily asserted that the dataset ships
   * the es_ES translation. Both tests were green throughout, and every freshly onboarded tenant
   * still landed with 49 document types and ZERO translations — the document types rendered in
   * English in the UI. Shipping a row and importing it are two different claims; this class has to
   * make both, for every file it reads.</p>
   */
  @Test
  public void testTheRequiredTablesAreImportedAtOnboarding() {
    assertTrue("C_DOCTYPE must be an included table",
        OnboardingDatasetDefinition.getIncludedTables().contains("C_DOCTYPE"));
    assertTrue("AD_SEQUENCE must be an included table",
        OnboardingDatasetDefinition.getIncludedTables().contains("AD_SEQUENCE"));
    assertTrue("C_DOCTYPE_TRL must be an included table — without it the translations asserted by"
            + " testTheDocumentTypeIsTranslated() never reach the tenant (ETP-5079)",
        OnboardingDatasetDefinition.getIncludedTables().contains("C_DOCTYPE_TRL"));
  }

  /** The document type is translated in both shipped languages, like every other one. */
  @Test
  public void testTheDocumentTypeIsTranslated() throws Exception {
    String docTypeId = null;
    for (Element row : rows("C_DOCTYPE.xml", "C_DOCTYPE")) {
      if (REC.equals(childText(row, "DOCBASETYPE"))) {
        docTypeId = childText(row, "C_DOCTYPE_ID");
      }
    }
    assertNotNull("no REC document type to resolve translations for", docTypeId);

    List<String> languages = new ArrayList<>();
    for (Element row : rows("C_DOCTYPE_TRL.xml", "C_DOCTYPE_TRL")) {
      if (docTypeId.equals(childText(row, "C_DOCTYPE_ID"))) {
        languages.add(childText(row, "AD_LANGUAGE"));
      }
    }

    assertTrue("missing en_US translation for the REC document type", languages.contains("en_US"));
    assertTrue("missing es_ES translation for the REC document type", languages.contains("es_ES"));
  }

  // ── XML helpers (same approach as BpGroupAcctSampleDataTest) ────────────────

  private List<Element> rows(String fileName, String tagName)
      throws ParserConfigurationException, SAXException, IOException {
    Document document = parse(sampleDataFile(fileName));
    NodeList nodes = document.getElementsByTagName(tagName);
    List<Element> rows = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      rows.add((Element) nodes.item(i));
    }
    return rows;
  }

  private Document parse(Path file)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(file.toFile());
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

  /** Resolves the sampledata file from either the module directory or the Etendo root. */
  private Path sampleDataFile(String fileName) {
    Path moduleRelative = Paths.get("referencedata", "sampledata", "GOClient", fileName);
    if (Files.exists(moduleRelative)) {
      return moduleRelative;
    }
    Path rootRelative = Paths.get("modules", "com.etendoerp.go", "referencedata", "sampledata",
        "GOClient", fileName);
    if (Files.exists(rootRelative)) {
      return rootRelative;
    }
    fail(fileName + " sampledata file not found from current working directory");
    return null;
  }
}
