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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

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
 * Referential-integrity guard over the whole bundled GOClient dataset. No database involved: it
 * reads the shipped XML only.
 *
 * <p>This is the test that was missing when ETP-5079 first removed the demo master data (four
 * sample products, three template financial accounts, the second warehouse with its locator, and
 * the "Bebidas" product category) directly from the source dataset. The removal left ~394 dangling
 * references behind — invoice lines, order lines, shipment lines, inventory, costing, storage,
 * payments and {@code FACT_ACCT} rows all still pointed at the deleted parents — and the whole
 * onboarding suite stayed green, because every existing test asserts either on the NORMALIZED
 * onboarding output (where those transactional tables are filtered out anyway) or on one file at a
 * time. The defect only surfaced much later, in {@code ./gradlew install}: {@code
 * ImportSampledata} loads all 121 files with foreign keys disabled and then re-enables them, and
 * {@code enableAllFK} blew up on {@code C_BPARTNER_FIN_FINACC}.</p>
 *
 * <p><b>What is asserted, precisely.</b> The dataset legitimately references plenty of ids it does
 * not define — System ({@code '0'}), the {@code 100} / {@code 0} bootstrap users, and any number of
 * Core master rows (currencies, units of measure, countries, references). Demanding that every
 * reference resolve inside the dataset would fail on all of those, so the assertion is scoped by
 * TABLE instead of by id:</p>
 *
 * <blockquote>when the dataset ships a file for table {@code T}, every {@code T_ID} reference in
 * any dataset row must resolve to a row that {@code T.xml} actually defines.</blockquote>
 *
 * <p>That is exactly the shape of the bug — a deletion inside {@code M_PRODUCT.xml} that left
 * {@code M_PRODUCT_ID} references elsewhere — while a reference to a table the dataset does not
 * ship at all (Core data) is silently out of scope, as it should be. A reference column whose name
 * does not match its table ({@code SALESREP_ID}, {@code BILLTO_ID}, {@code EM_ETGO_*_ID}, …) is
 * likewise out of scope: the check only fires when {@code <column minus _ID>.xml} exists, so no
 * naming exception list is needed.</p>
 *
 * <p><b>Scope note.</b> The guard covers the FULL dataset, not just the curated onboarding subset.
 * That is deliberate: the dataset has two consumers with opposite needs — {@code install.source}
 * seeds GOClient from all 121 files, while onboarding reads a 40-table subset — and it was the
 * install consumer, the one no test looked at, that broke. Rows that must not reach a new tenant
 * are dropped at import time by {@code OnboardingDatasetNormalizer}'s row filters, never by
 * deleting them from the source.</p>
 */
public class OnboardingDatasetReferentialIntegrityTest {

  /** An Etendo id: 32 uppercase hex characters, no hyphens. */
  private static final Pattern ETENDO_ID = Pattern.compile("[0-9A-F]{32}");

  private static final String ID_SUFFIX = "_ID";

  /**
   * The dataset ships 121 files today. Asserted so a future re-export that drops whole files
   * (rather than rows) cannot quietly shrink what this guard covers.
   */
  private static final int MINIMUM_EXPECTED_FILES = 121;

  /**
   * Every reference to a table the dataset defines resolves to a row that table actually ships.
   *
   * <p>Reports ALL violations at once, grouped by source table and column, because a single
   * deletion produces hundreds of them across a dozen files and a one-at-a-time failure would take
   * a dozen runs to diagnose.</p>
   */
  @Test
  public void testEveryReferenceToADatasetOwnedTableResolves() throws Exception {
    Map<String, Path> datasetFiles = datasetFilesByTable();
    assertTrue("the GOClient dataset must ship at least " + MINIMUM_EXPECTED_FILES + " files (found "
        + datasetFiles.size() + ")", datasetFiles.size() >= MINIMUM_EXPECTED_FILES);

    Map<String, Set<String>> definedIdsByTable = definedIdsByTable(datasetFiles);
    Map<String, Set<String>> danglingByOrigin = new TreeMap<>();

    for (Map.Entry<String, Path> datasetFile : datasetFiles.entrySet()) {
      String sourceTable = datasetFile.getKey();
      for (Element row : rows(datasetFile.getValue(), sourceTable)) {
        collectDanglingReferences(sourceTable, row, datasetFiles.keySet(), definedIdsByTable,
            danglingByOrigin);
      }
    }

    if (!danglingByOrigin.isEmpty()) {
      fail(danglingReferenceReport(danglingByOrigin));
    }
  }

  /**
   * The ten demo master-data rows ETP-5079 removes from a new tenant are still DEFINED by the
   * dataset, so GOClient keeps its sample data at install time.
   *
   * <p>Pinned separately from the integrity check above because the two failures mean opposite
   * things and want opposite fixes. The check above going red says "you deleted a definition and
   * left references behind"; this one going red says "you deleted these rows from the source again
   * instead of filtering them at import" — which is the mistake this ticket exists to undo. The
   * complementary claim, that these rows do NOT reach an onboarded tenant, is asserted on the
   * normalized output by
   * {@code OnboardingDatasetNormalizerTest#testNormalizerShipsCorrectedInitialDataset()}.</p>
   */
  @Test
  public void testTheDemoMasterDataStaysInTheSourceDatasetForGoClient() throws Exception {
    Map<String, Path> datasetFiles = datasetFilesByTable();
    Map<String, Set<String>> definedIdsByTable = definedIdsByTable(datasetFiles);

    Map<String, Integer> expectedRowCount = new TreeMap<>();
    expectedRowCount.put("FIN_FINANCIAL_ACCOUNT", 3);
    expectedRowCount.put("FIN_FINACC_PAYMENTMETHOD", 6);
    expectedRowCount.put("M_PRODUCT", 5);
    expectedRowCount.put("M_PRODUCTPRICE", 8);
    expectedRowCount.put("M_LOCATOR", 2);
    expectedRowCount.put("M_WAREHOUSE", 2);
    expectedRowCount.put("M_PRODUCT_CATEGORY", 3);
    expectedRowCount.put("AD_ORG_WAREHOUSE", 2);

    for (Map.Entry<String, Integer> expected : expectedRowCount.entrySet()) {
      String table = expected.getKey();
      List<Element> tableRows = rows(datasetFiles.get(table), table);
      assertEquals(table + ".xml must ship " + expected.getValue() + " rows — GOClient's sample"
          + " data is seeded from the source dataset by install.source; rows a new tenant must not"
          + " receive are dropped at import time by OnboardingDatasetNormalizer, not deleted here"
          + " (ETP-5079)", expected.getValue(), Integer.valueOf(tableRows.size()));
    }

    for (String demoId : OnboardingDemoMasterData.ALL) {
      assertTrue("demo master-data row " + demoId + " must still be defined by the dataset",
          definedIdsByTable.values().stream().anyMatch(ids -> ids.contains(demoId)));
    }
  }

  // ── internals ──────────────────────────────────────────────────────────────

  private void collectDanglingReferences(String sourceTable, Element row, Set<String> datasetTables,
      Map<String, Set<String>> definedIdsByTable, Map<String, Set<String>> danglingByOrigin) {
    NodeList children = row.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (!(child instanceof Element)) {
        continue;
      }
      String columnName = child.getNodeName().toUpperCase();
      if (!columnName.endsWith(ID_SUFFIX)) {
        continue;
      }
      String rawValue = child.getTextContent() == null ? "" : child.getTextContent().trim();
      if (!ETENDO_ID.matcher(rawValue).matches()) {
        continue;
      }
      String referencedTable = columnName.substring(0, columnName.length() - ID_SUFFIX.length());
      // The row's own primary key is a definition, not a reference; and a reference to a table the
      // dataset does not ship (Core master data) is out of scope by design.
      if (referencedTable.equals(sourceTable) || !datasetTables.contains(referencedTable)) {
        continue;
      }
      if (!definedIdsByTable.get(referencedTable).contains(rawValue)) {
        danglingByOrigin
            .computeIfAbsent(sourceTable + "." + columnName + " -> " + referencedTable,
                key -> new TreeSet<>())
            .add(rawValue);
      }
    }
  }

  private String danglingReferenceReport(Map<String, Set<String>> danglingByOrigin) {
    int total = danglingByOrigin.values().stream().mapToInt(Set::size).sum();
    StringBuilder report = new StringBuilder();
    report.append("the GOClient dataset has ")
        .append(total)
        .append(" dangling reference(s): a row references an id that the referenced table's own")
        .append(" file no longer defines. install.source loads all 121 files with foreign keys")
        .append(" disabled and then re-enables them, so this fails ./gradlew install in")
        .append(" enableAllFK. Do NOT fix it by deleting the referencing rows: rows a new tenant")
        .append(" must not receive belong in an OnboardingDatasetNormalizer row filter, which")
        .append(" leaves the source dataset intact (ETP-5079).\n");
    for (Map.Entry<String, Set<String>> dangling : danglingByOrigin.entrySet()) {
      report.append("  ")
          .append(dangling.getKey())
          .append(": ")
          .append(dangling.getValue())
          .append('\n');
    }
    return report.toString();
  }

  /** Maps every {@code <TABLE>.xml} in the bundled dataset to its table name. */
  private Map<String, Path> datasetFilesByTable() throws IOException {
    Map<String, Path> filesByTable = new TreeMap<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(sampleDataDirectory(), "*.xml")) {
      for (Path file : files) {
        String fileName = file.getFileName().toString();
        filesByTable.put(fileName.substring(0, fileName.length() - ".xml".length()).toUpperCase(),
            file);
      }
    }
    return filesByTable;
  }

  /** Collects, per table, the primary keys its own file defines. */
  private Map<String, Set<String>> definedIdsByTable(Map<String, Path> datasetFiles)
      throws ParserConfigurationException, SAXException, IOException {
    Map<String, Set<String>> definedIds = new HashMap<>();
    for (Map.Entry<String, Path> datasetFile : datasetFiles.entrySet()) {
      String table = datasetFile.getKey();
      Set<String> ids = new HashSet<>();
      for (Element row : rows(datasetFile.getValue(), table)) {
        String id = childText(row, table + ID_SUFFIX);
        if (id != null && !id.trim().isEmpty()) {
          ids.add(id.trim());
        }
      }
      definedIds.put(table, ids);
    }
    return definedIds;
  }

  /**
   * Returns the direct {@code <TABLE>} children of the file's {@code <data>} root. Deliberately not
   * {@code getElementsByTagName}: a row element and a same-named descendant would both match.
   */
  private List<Element> rows(Path file, String tagName)
      throws ParserConfigurationException, SAXException, IOException {
    if (file == null) {
      fail("the GOClient dataset does not ship a " + tagName + ".xml file");
      return Collections.emptyList();
    }
    Document document = parse(file);
    NodeList children = document.getDocumentElement().getChildNodes();
    List<Element> rows = new ArrayList<>();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element && tagName.equalsIgnoreCase(child.getNodeName())) {
        rows.add((Element) child);
      }
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
      if (child instanceof Element && tagName.equalsIgnoreCase(child.getNodeName())) {
        return child.getTextContent();
      }
    }
    return null;
  }

  /** Resolves the sampledata directory from either the module directory or the Etendo root. */
  private Path sampleDataDirectory() {
    Path moduleRelative = Paths.get("referencedata", "sampledata", "GOClient");
    if (Files.isDirectory(moduleRelative)) {
      return moduleRelative;
    }
    Path rootRelative = Paths.get("modules", "com.etendoerp.go", "referencedata", "sampledata",
        "GOClient");
    if (Files.isDirectory(rootRelative)) {
      return rootRelative;
    }
    fail("GOClient sampledata directory not found from current working directory");
    return null;
  }
}
