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
 * Guards that the GOClient onboarding dataset ships its two price lists FLAGGED AS DEFAULT, one per
 * direction (sales and purchase).
 *
 * <p>{@code M_PRICELIST.ISDEFAULT} is not decoration: it is the tie-break three independent
 * consumers rely on to pick "the" price list when a tenant has more than one in the same direction.
 * The dataset shipped both rows with {@code ISDEFAULT='N'}, so all three degraded SILENTLY — no
 * error anywhere, just the wrong price:</p>
 *
 * <ol>
 *   <li>The computed columns {@code ETGO_PRODUCT_SALE_PRICE} / {@code ETGO_PRODUCT_PURCHASE_PRICE}
 *       (see {@code src-db/database/model/functions/}) order by
 *       {@code (pl.isdefault = 'Y') DESC} as their first sort key. With no row flagged, the key
 *       never discriminates and the "Sale price" column of the product list falls through to the
 *       remaining tie-breaks — i.e. an arbitrary price list once a tenant has two.</li>
 *   <li>{@code PriceListPicker.jsx} resolves its preselection with
 *       {@code matches.find(p =&gt; p.default)} and falls back to {@code matches[0]}; with no
 *       default flagged the fallback is ALWAYS what the user gets, so the picker preselects
 *       whichever price list the backend happened to return first.</li>
 *   <li>The standard-cost data fix ({@code R33-standard-cost-anchor-unified}) orders by
 *       {@code pl.isdefault DESC} to decide which price list the standard cost is anchored to.</li>
 * </ol>
 *
 * <p>Same shape as {@link ReconciliationDocTypeSampleDataTest}: it reads the shipped XML, no
 * database involved. And, as that class's javadoc warns, shipping a row and importing it are two
 * different claims — {@link #testTheRequiredTablesAreImportedAtOnboarding()} makes the second one,
 * because a perfectly flagged {@code M_PRICELIST.xml} that is not in the import contract reaches
 * exactly zero tenants.</p>
 */
public class DefaultPriceListSampleDataTest {

  private static final String PRICELIST_FILE = "M_PRICELIST.xml";
  private static final String PRICELIST_TAG = "M_PRICELIST";
  private static final String PRICELIST_VERSION_FILE = "M_PRICELIST_VERSION.xml";
  private static final String PRICELIST_VERSION_TAG = "M_PRICELIST_VERSION";
  private static final String PRICELIST_ID = "M_PRICELIST_ID";
  private static final String IS_SO_PRICELIST = "ISSOPRICELIST";
  private static final String IS_DEFAULT = "ISDEFAULT";
  private static final String IS_ACTIVE = "ISACTIVE";
  private static final String YES = "Y";
  private static final String NO = "N";

  private static final String SALES_PRICELIST_ID = "782B468DCC3948D69BC2AE5B68C3F4A4";
  private static final String PURCHASE_PRICELIST_ID = "F888E6AAB93E44E88433C21A8F3C0161";
  private static final String SALES_PRICELIST_VERSION_ID = "65277E8E582F4EDCBC9151BE51FC6318";
  private static final String PURCHASE_PRICELIST_VERSION_ID = "A261D0992E814847889EC935562C5993";

  /** The dataset ships exactly one active price list per direction, and both are the known rows. */
  @Test
  public void testDatasetShipsOneActivePriceListPerDirection() throws Exception {
    List<Element> salesPriceLists = priceListsFor(YES);
    List<Element> purchasePriceLists = priceListsFor(NO);

    assertEquals("the dataset must ship exactly one sales price list (ISSOPRICELIST='Y');"
        + " a tenant with none cannot price a sales order at all", 1, salesPriceLists.size());
    assertEquals("the dataset must ship exactly one purchase price list (ISSOPRICELIST='N')",
        1, purchasePriceLists.size());

    assertEquals("the sales price list row must keep its shipped id, the sales price list version"
            + " and the standard-cost data fix resolve it by id",
        SALES_PRICELIST_ID, childText(salesPriceLists.get(0), PRICELIST_ID));
    assertEquals(PURCHASE_PRICELIST_ID, childText(purchasePriceLists.get(0), PRICELIST_ID));

    assertEquals("the shipped sales price list must be active", YES,
        childText(salesPriceLists.get(0), IS_ACTIVE));
    assertEquals("the shipped purchase price list must be active", YES,
        childText(purchasePriceLists.get(0), IS_ACTIVE));
  }

  /**
   * THE regression this class exists for: each direction must have EXACTLY ONE price list flagged
   * as default.
   *
   * <p>Zero and two are the same bug seen from opposite sides — both leave
   * {@code ORDER BY (pl.isdefault = 'Y') DESC} unable to discriminate, so the product list, the
   * price list picker and the standard-cost anchor all fall through to an arbitrary row. The
   * dataset shipped the "zero" variant; this test fails loudly on either.</p>
   */
  @Test
  public void testExactlyOneDefaultPriceListPerDirection() throws Exception {
    assertDefaultCount("sales", YES);
    assertDefaultCount("purchase", NO);
  }

  private void assertDefaultCount(String direction, String isSoPriceList) throws Exception {
    List<String> defaults = new ArrayList<>();
    for (Element priceList : priceListsFor(isSoPriceList)) {
      if (YES.equals(childText(priceList, IS_DEFAULT))) {
        assertEquals("a price list flagged as default must be active, an inactive one is not"
                + " picked up by the consumers that order by ISDEFAULT",
            YES, childText(priceList, IS_ACTIVE));
        defaults.add(childText(priceList, PRICELIST_ID));
      }
    }

    assertEquals("exactly one " + direction + " price list (ISSOPRICELIST='" + isSoPriceList
            + "') must ship with ISDEFAULT='Y' — found " + defaults.size() + " " + defaults
            + ". With zero, the ORDER BY (pl.isdefault = 'Y') DESC tie-break in"
            + " ETGO_PRODUCT_SALE_PRICE / ETGO_PRODUCT_PURCHASE_PRICE never discriminates and"
            + " PriceListPicker always falls back to matches[0]; with two, it discriminates into"
            + " an arbitrary one of them. Both are the same silent wrong-price bug",
        1, defaults.size());
  }

  /**
   * Every table backing the rows above has to be in the import contract, or those rows never reach
   * a tenant.
   *
   * <p>{@code M_PRICELIST_VERSION} belongs here next to {@code M_PRICELIST}: a price list without a
   * version holds no prices, so importing one without the other ships a default flag that points at
   * an empty price list.</p>
   */
  @Test
  public void testTheRequiredTablesAreImportedAtOnboarding() {
    assertTrue("M_PRICELIST must be an included table — otherwise the default-flagged price lists"
            + " asserted above never reach a tenant",
        OnboardingDatasetDefinition.getIncludedTables().contains(PRICELIST_TAG));
    assertTrue("M_PRICELIST_VERSION must be an included table — a price list with no version holds"
            + " no prices, and the computed columns join through it",
        OnboardingDatasetDefinition.getIncludedTables().contains(PRICELIST_VERSION_TAG));
  }

  /**
   * Each shipped price list must be backed by an active version, since that is what the computed
   * columns join through ({@code m_productprice → m_pricelist_version → m_pricelist}).
   */
  @Test
  public void testEachPriceListHasAnActiveVersion() throws Exception {
    assertHasActiveVersion(SALES_PRICELIST_ID, SALES_PRICELIST_VERSION_ID, "sales");
    assertHasActiveVersion(PURCHASE_PRICELIST_ID, PURCHASE_PRICELIST_VERSION_ID, "purchase");
  }

  private void assertHasActiveVersion(String priceListId, String expectedVersionId,
      String direction) throws Exception {
    Element version = null;
    for (Element row : rows(PRICELIST_VERSION_FILE, PRICELIST_VERSION_TAG)) {
      if (priceListId.equals(childText(row, PRICELIST_ID))) {
        version = row;
      }
    }

    assertNotNull("M_PRICELIST_VERSION.xml must ship a version for the " + direction
        + " price list (" + priceListId + "); a price list with no version holds no prices, so the"
        + " default flag points at nothing", version);
    assertEquals("the " + direction + " price list version must keep its shipped id",
        expectedVersionId, childText(version, "M_PRICELIST_VERSION_ID"));
    assertEquals("the " + direction + " price list version must be active", YES,
        childText(version, IS_ACTIVE));
  }

  private List<Element> priceListsFor(String isSoPriceList) throws Exception {
    List<Element> matches = new ArrayList<>();
    for (Element row : rows(PRICELIST_FILE, PRICELIST_TAG)) {
      if (isSoPriceList.equals(childText(row, IS_SO_PRICELIST))) {
        matches.add(row);
      }
    }
    return matches;
  }

  // ── XML helpers (same approach as ReconciliationDocTypeSampleDataTest) ──────

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
