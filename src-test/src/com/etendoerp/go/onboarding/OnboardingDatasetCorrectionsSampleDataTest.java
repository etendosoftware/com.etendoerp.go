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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Regression guards for the ETP-5079 dataset corrections that had no test of their own: the
 * document-number sequences, the Spanish document-type translations, and the Spanish product-category
 * translation.
 *
 * <p>Every one of those defects reached every onboarded tenant while the whole onboarding suite was
 * green, which is the reason this class exists. Same shape as {@link BpGroupAcctSampleDataTest} and
 * {@link ReconciliationDocTypeSampleDataTest}: it reads the shipped XML, no database involved.</p>
 *
 * <p>Shipping a row and importing it are two different claims, and the suite has to make both. For
 * the sequences and the document types the second claim lives in
 * {@link ReconciliationDocTypeSampleDataTest#testTheRequiredTablesAreImportedAtOnboarding()}, which
 * asserts {@code AD_SEQUENCE} and {@code C_DOCTYPE_TRL} are in
 * {@link OnboardingDatasetDefinition#getIncludedTables()} — see that method's javadoc for what it
 * costs when only the first one is asserted. For the product category the two claims are made here,
 * next to each other: {@link #testEveryUserFacingProductCategoryHasARealSpanishTranslation()} and
 * {@link #testTheProductCategoryTranslationTableIsImportedAtOnboarding()}.</p>
 */
public class OnboardingDatasetCorrectionsSampleDataTest {

  private static final String SPANISH = "es_ES";

  /**
   * The Etendo GO column that hides a product category from every window and selector
   * ({@code ProductCategorySystemFlagSelectorPolicy}). Rows carrying {@code 'Y'} are infrastructure,
   * not user-facing data, and are scoped out of the translation-coverage requirement below.
   */
  private static final String SYSTEM_CATEGORY_FLAG = "EM_ETGO_ISSYSTEMCATEGORY";

  /**
   * The eleven document sequences ETP-5079 corrected, mapped to the value BOTH {@code STARTNO} and
   * {@code CURRENTNEXT} must carry.
   *
   * <p>This table is duplicated, deliberately and by value, from the corrective fix
   * {@code schema_forge/cli/src/data-fixes/sql/20260902T120000Z__R31-document-sequence-startno.sql},
   * which repairs tenants onboarded before the dataset was fixed. The preventive and the corrective
   * fronts have to agree on the target numbers or existing tenants and new ones drift apart, so if
   * one of these ever changes, change the other in the same commit.</p>
   */
  private static final Map<String, String> EXPECTED_SEQUENCE_START = new LinkedHashMap<>();

  static {
    EXPECTED_SEQUENCE_START.put("AR Invoice", "10000000");
    EXPECTED_SEQUENCE_START.put("AP Payment", "1000000");
    EXPECTED_SEQUENCE_START.put("AR Receipt", "1000000");
    EXPECTED_SEQUENCE_START.put("MM Shipment", "1000000");
    EXPECTED_SEQUENCE_START.put("Standard Order", "1000000");
    EXPECTED_SEQUENCE_START.put("Purchase Order", "1000000");
    EXPECTED_SEQUENCE_START.put("DocumentNo_C_Invoice", "10000000");
    EXPECTED_SEQUENCE_START.put("Secuencia TICKETBAI", "1000000");
    EXPECTED_SEQUENCE_START.put("DocumentNo_M_InOut", "10000000");
    EXPECTED_SEQUENCE_START.put("DocumentNo_M_Movement", "10000000");
    EXPECTED_SEQUENCE_START.put("DocumentNo_A_Asset", "10000000");
  }

  // ─── Document sequences (ETP-5079, gap N1) ─────────────────────────────────

  /**
   * Each of the eleven appears exactly once in the dataset.
   *
   * <p>Guarded because the assertions below match on {@code NAME}: a second row under the same name
   * would let a wrong one hide behind a right one. (The dataset separately ships duplicated
   * {@code DocumentNo_*} rows at RUNTIME — Core's {@code InitialClientSetup} creates them at T+0 and
   * the dataset import re-creates them seconds later — but that is a tenant-side duplication, not an
   * XML one, and is out of scope here.)</p>
   */
  @Test
  public void testTheCorrectedSequencesAppearExactlyOnce() throws Exception {
    Map<String, Integer> occurrences = new HashMap<>();
    for (Element row : rows("AD_SEQUENCE.xml", "AD_SEQUENCE")) {
      String name = childText(row, "NAME");
      if (EXPECTED_SEQUENCE_START.containsKey(name)) {
        occurrences.merge(name, 1, Integer::sum);
      }
    }
    for (String name : EXPECTED_SEQUENCE_START.keySet()) {
      assertEquals("AD_SEQUENCE.xml must ship exactly one sequence named '" + name + "'",
          Integer.valueOf(1), occurrences.get(name));
    }
  }

  /**
   * A new tenant is born with every corrected sequence at delta 0 — {@code CURRENTNEXT} equal to
   * {@code STARTNO}, both at the expected value. This is ETP-5079's TC-6.
   *
   * <p>The dataset used to ship these wildly out of step: Standard Order at {@code STARTNO} 50,000
   * with {@code CURRENTNEXT} 1,000,011, AR Invoice at 100,000 against 10,000,016. The counters were
   * captured from the source instance during the export, so every tenant inherited that instance's
   * usage instead of starting clean.</p>
   *
   * <p>SCOPE: exactly these eleven. The dataset carries 15 FURTHER sequences that still have a
   * non-zero delta today (GL Journal, Quotation, Proposal, Credit Order, POS Order, AR Credit Memo,
   * MM Shipment Indirect, Purchase Requisition, Settlement, Manual Settlement, Depreciation, Debt
   * Payment Management, Prepay Order, Return Material, Warehouse Order). They are deliberately NOT
   * asserted here — asserting them as-is would freeze a defect in place and block the widening;
   * asserting them at delta 0 would fail today. If they are ever corrected, add them here AND to
   * R31 together.</p>
   */
  @Test
  public void testTheCorrectedSequencesStartAtDeltaZero() throws Exception {
    List<String> seen = new ArrayList<>();
    for (Element row : rows("AD_SEQUENCE.xml", "AD_SEQUENCE")) {
      String name = childText(row, "NAME");
      String expected = EXPECTED_SEQUENCE_START.get(name);
      if (expected == null) {
        continue;
      }
      seen.add(name);

      String startNo = childText(row, "STARTNO");
      String currentNext = childText(row, "CURRENTNEXT");
      assertNotNull("sequence '" + name + "' must declare STARTNO", startNo);
      assertNotNull("sequence '" + name + "' must declare CURRENTNEXT", currentNext);

      assertEqualNumbers("sequence '" + name + "' must start at " + expected, expected, startNo);
      assertEqualNumbers("sequence '" + name + "' must be born unused: CURRENTNEXT has to equal"
          + " STARTNO (" + expected + "), otherwise a new tenant inherits the source instance's"
          + " document counter instead of starting clean", expected, currentNext);
    }
    assertEquals("all eleven corrected sequences must be present in AD_SEQUENCE.xml",
        EXPECTED_SEQUENCE_START.keySet().size(), seen.size());
  }

  // ─── Document-type translations (ETP-5079) ─────────────────────────────────

  /**
   * Every document type the dataset ships carries exactly one Spanish translation, and that
   * translation is marked as one.
   *
   * <p>The reported symptom was document types rendering in English in the UI. Two causes stacked:
   * {@code C_DOCTYPE_TRL} was not imported at all (guarded elsewhere — see this class's javadoc),
   * and 47 of the 49 es_ES rows were placeholders that merely copied the English name with
   * {@code ISTRANSLATED='N'}, so even once imported they would have translated nothing. This test
   * covers the second cause; it is the one that would silently come back on a careless re-export.</p>
   */
  @Test
  public void testEveryDocumentTypeHasARealSpanishTranslation() throws Exception {
    Map<String, String> englishNameById = new LinkedHashMap<>();
    for (Element row : rows("C_DOCTYPE.xml", "C_DOCTYPE")) {
      englishNameById.put(childText(row, "C_DOCTYPE_ID"), childText(row, "NAME"));
    }
    assertFalse("C_DOCTYPE.xml must ship document types", englishNameById.isEmpty());

    Map<String, Integer> spanishRowsById = new HashMap<>();
    for (Element row : rows("C_DOCTYPE_TRL.xml", "C_DOCTYPE_TRL")) {
      String docTypeId = childText(row, "C_DOCTYPE_ID");
      assertTrue("C_DOCTYPE_TRL row references a document type that C_DOCTYPE.xml does not ship ("
          + docTypeId + "); a dangling translation is dropped at import",
          englishNameById.containsKey(docTypeId));

      if (!SPANISH.equals(childText(row, "AD_LANGUAGE"))) {
        continue;
      }
      spanishRowsById.merge(docTypeId, 1, Integer::sum);

      String englishName = englishNameById.get(docTypeId);
      String translated = childText(row, "NAME");
      assertTrue("the es_ES translation of '" + englishName + "' must not be blank",
          translated != null && !translated.trim().isEmpty());
      assertFalse("the es_ES row for '" + englishName + "' merely repeats the English name — that is"
          + " the placeholder shape ETP-5079 replaced, and it renders as English in the UI",
          englishName.equals(translated));
      assertEquals("the es_ES translation of '" + englishName + "' must be flagged ISTRANSLATED='Y';"
          + " Etendo falls back to the base NAME when it is not", "Y", childText(row, "ISTRANSLATED"));
    }

    for (Map.Entry<String, String> docType : englishNameById.entrySet()) {
      assertEquals("document type '" + docType.getValue() + "' must have exactly one es_ES"
          + " translation", Integer.valueOf(1), spanishRowsById.get(docType.getKey()));
    }
  }

  // ── XML helpers (same approach as ReconciliationDocTypeSampleDataTest) ──────

  /**
   * Every user-facing product category the dataset ships carries exactly one Spanish translation,
   * and that translation is a real one.
   *
   * <p>Same shape, and the same class of defect, as
   * {@link #testEveryDocumentTypeHasARealSpanishTranslation()}. ETP-5079 renamed the starter
   * category from the Spanish "Otros" to the English base name {@code Generic} and moved the Spanish
   * into an {@code M_PRODUCT_CATEGORY_TRL} row ("Genérico") — the same English-base-plus-translation
   * convention this ticket applied to document types. A placeholder row (blank, flagged
   * {@code ISTRANSLATED='N'}, or a verbatim copy of the English name) renders as English in the UI
   * with no error anywhere, which is exactly how the document-type defect stayed invisible.</p>
   *
   * <p>SCOPE: non-system categories only. The other shipped category, {@code Discounts}
   * ({@code EM_Etgo_IsSystemCategory='Y'}), deliberately has NO translation row: it is filtered out
   * of every window and selector, it exists only to carry the internal {@code ETGO_DTO} discount
   * product, and it is already English-named. It is excluded from the coverage requirement rather
   * than asserted to have zero rows, so translating it later stays a choice instead of a test
   * failure.</p>
   */
  @Test
  public void testEveryUserFacingProductCategoryHasARealSpanishTranslation() throws Exception {
    Map<String, String> englishNameById = new LinkedHashMap<>();
    Map<String, String> userFacingNameById = new LinkedHashMap<>();
    for (Element row : rows("M_PRODUCT_CATEGORY.xml", "M_PRODUCT_CATEGORY")) {
      String categoryId = childText(row, "M_PRODUCT_CATEGORY_ID");
      String name = childText(row, "NAME");
      englishNameById.put(categoryId, name);
      if (!"Y".equals(childText(row, SYSTEM_CATEGORY_FLAG))) {
        userFacingNameById.put(categoryId, name);
      }
    }
    assertFalse("M_PRODUCT_CATEGORY.xml must ship product categories", englishNameById.isEmpty());
    assertFalse("M_PRODUCT_CATEGORY.xml must ship at least one user-facing (non system-flagged)"
            + " category — a tenant with only the hidden Discounts category has nothing to file a"
            + " product under",
        userFacingNameById.isEmpty());

    Map<String, Integer> spanishRowsById = new HashMap<>();
    for (Element row : rows("M_PRODUCT_CATEGORY_TRL.xml", "M_PRODUCT_CATEGORY_TRL")) {
      String categoryId = childText(row, "M_PRODUCT_CATEGORY_ID");
      assertTrue("M_PRODUCT_CATEGORY_TRL row references a category that M_PRODUCT_CATEGORY.xml does"
          + " not ship (" + categoryId + "); a dangling translation is dropped at import",
          englishNameById.containsKey(categoryId));

      if (!SPANISH.equals(childText(row, "AD_LANGUAGE"))) {
        continue;
      }
      spanishRowsById.merge(categoryId, 1, Integer::sum);

      String englishName = englishNameById.get(categoryId);
      String translated = childText(row, "NAME");
      assertTrue("the es_ES translation of '" + englishName + "' must not be blank",
          translated != null && !translated.trim().isEmpty());
      assertFalse("the es_ES row for '" + englishName + "' merely repeats the English name — that is"
          + " the placeholder shape ETP-5079 replaced, and it renders as English in the UI",
          englishName.equals(translated));
      assertEquals("the es_ES translation of '" + englishName + "' must be flagged ISTRANSLATED='Y';"
          + " Etendo falls back to the base NAME when it is not", "Y", childText(row, "ISTRANSLATED"));
    }

    for (Map.Entry<String, String> category : userFacingNameById.entrySet()) {
      assertEquals("user-facing product category '" + category.getValue() + "' must have exactly one"
          + " es_ES translation", Integer.valueOf(1), spanishRowsById.get(category.getKey()));
    }
  }

  /**
   * The category translation actually reaches a tenant.
   *
   * <p>This is the whole point of the test above it. {@code C_DOCTYPE_TRL} shipped correct
   * translations for months while missing from
   * {@link OnboardingDatasetDefinition#getIncludedTables()}, so no tenant ever received them and the
   * document types rendered in English — a defect no assertion over the XML alone can see. Without
   * {@code M_PRODUCT_CATEGORY_TRL} in the allowlist a future dataset re-export silently reverts to
   * an untranslated starter category showing "Generic" to a Spanish user.</p>
   */
  @Test
  public void testTheProductCategoryTranslationTableIsImportedAtOnboarding() {
    assertTrue("M_PRODUCT_CATEGORY must be an included table",
        OnboardingDatasetDefinition.getIncludedTables().contains("M_PRODUCT_CATEGORY"));
    assertTrue("M_PRODUCT_CATEGORY_TRL must be an included table — without it the translation"
            + " asserted by testEveryUserFacingProductCategoryHasARealSpanishTranslation() never"
            + " reaches the tenant (ETP-5079)",
        OnboardingDatasetDefinition.getIncludedTables().contains("M_PRODUCT_CATEGORY_TRL"));
  }

  private void assertEqualNumbers(String message, String expected, String actual) {
    assertEquals(message + " (was " + actual + ")", 0,
        new BigDecimal(expected).compareTo(new BigDecimal(actual.trim())));
  }

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
