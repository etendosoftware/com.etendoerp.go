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
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Schema guard for {@code ETGO_PLAN_QUOTA} (ETP-5046). No database, no mock: it reads the shipped
 * XML from the filesystem.
 *
 * <p><b>The invariant.</b> A plan is UNLIMITED for a resource when it has NO quota row for that
 * resource. The absence of the row IS the "unlimited" answer — that is the load-bearing rule of the
 * whole plan/quota feature, and it is what lets the ETP-5046 grandfathered plan
 * ({@code legacy-productive}, shipped as sourcedata with zero quota children) keep every existing
 * productive tenant unrestricted. Therefore nothing may ever pre-fill a quantity into a quota row:
 * a row that exists is a real, deliberate cap.</p>
 *
 * <p><b>Why this test exists.</b> {@code INCLUDED_QTY} is {@code required="true"} with NO default,
 * deliberately — but every comparable required DECIMAL in this module carries
 * {@code <default>0</default>} ({@code ETGO_USAGE_DAILY.QTY},
 * {@code ETGO_CHECKOUT_REQUEST.PROVISIONING_ATTEMPTS}, {@code ETGO_BILLING_EVENT.DUPLICATE_COUNT}).
 * A reviewer pattern-matching against those siblings would "fix" ours for consistency and silently
 * reintroduce the bug: a default of zero caps EVERY resource on EVERY plan at zero the moment
 * ETP-5051's quota evaluator goes live. The failure is invisible until then, and then it is total.
 * </p>
 *
 * <p><b>Both sides are guarded, on purpose.</b> The DDL default
 * ({@code src-db/database/model/tables/ETGO_PLAN_QUOTA.xml}) is only half the surface: a Classic
 * {@code AD_COLUMN.DEFAULTVALUE} pre-fills {@code 0} into a new quota row the moment an operator
 * opens the tab — even just to look — and that caps the plan exactly as effectively as a DDL
 * default would, while leaving the DDL clean.</p>
 *
 * <p><b>Not covered elsewhere.</b> {@code ./check-etgo-xml.sh} validates record order, unique
 * constraints and referential integrity; it does not look at defaults. This test is the only
 * automated guard for this class of problem.</p>
 */
public class PlanQuotaSchemaInvariantTest {

  /** {@code AD_TABLE_ID} of {@code ETGO_PLAN_QUOTA}. */
  private static final String PLAN_QUOTA_TABLE_ID = "C2DF98CC43D74F39988C581ECDE34852";

  private static final Path DDL_RELATIVE =
      Paths.get("src-db", "database", "model", "tables", "ETGO_PLAN_QUOTA.xml");
  private static final Path AD_COLUMN_RELATIVE =
      Paths.get("src-db", "database", "sourcedata", "AD_COLUMN.xml");

  private static final String WHY_INCLUDED_QTY =
      "INCLUDED_QTY must stay WITHOUT a default value. A plan with no quota row for a resource is "
          + "UNLIMITED for that resource; a default of 0 turns every newly created quota row into "
          + "a hard zero cap, so ETP-5051's quota evaluator would deny every resource on every "
          + "plan the moment it goes live. The author of a quota must state the number explicitly. "
          + "If you added this default for consistency with ETGO_USAGE_DAILY.QTY or "
          + "ETGO_CHECKOUT_REQUEST.PROVISIONING_ATTEMPTS: those are counters, this is a cap — "
          + "revert it. See ETP-5046 / ETP-5051.";

  private static final String WHY_ENFORCEMENT_MODE =
      "ENFORCEMENT_MODE must stay WITHOUT a default value. Defaulting it picks the enforcement "
          + "policy ('warn' vs 'block') on the operator's behalf, so a quota row created by "
          + "accident would silently start blocking (or silently stop blocking) real tenant "
          + "traffic once ETP-5051's quota evaluator reads it. See ETP-5046 / ETP-5051.";

  @Test
  @DisplayName("The DDL must not give INCLUDED_QTY or ENFORCEMENT_MODE a default")
  public void ddlDeclaresNoDefaultForQuotaColumns() throws Exception {
    Path ddl = moduleFile(DDL_RELATIVE);
    Element includedQty = ddlColumn(ddl, "INCLUDED_QTY");
    Element enforcementMode = ddlColumn(ddl, "ENFORCEMENT_MODE");

    assertAll(
        () -> assertTrue(isBlank(childText(includedQty, "default")),
            () -> "ETGO_PLAN_QUOTA.INCLUDED_QTY has a DDL <default> of '"
                + childText(includedQty, "default") + "' in " + ddl + ". " + WHY_INCLUDED_QTY),
        () -> assertTrue(isBlank(childText(includedQty, "onCreateDefault")),
            () -> "ETGO_PLAN_QUOTA.INCLUDED_QTY has a DDL <onCreateDefault> of '"
                + childText(includedQty, "onCreateDefault") + "' in " + ddl
                + ". An onCreateDefault back-fills existing rows on update.database, which is the "
                + "same zero cap applied retroactively. " + WHY_INCLUDED_QTY),
        () -> assertTrue(isBlank(childText(enforcementMode, "default")),
            () -> "ETGO_PLAN_QUOTA.ENFORCEMENT_MODE has a DDL <default> of '"
                + childText(enforcementMode, "default") + "' in " + ddl + ". "
                + WHY_ENFORCEMENT_MODE),
        () -> assertTrue(isBlank(childText(enforcementMode, "onCreateDefault")),
            () -> "ETGO_PLAN_QUOTA.ENFORCEMENT_MODE has a DDL <onCreateDefault> of '"
                + childText(enforcementMode, "onCreateDefault") + "' in " + ddl + ". "
                + WHY_ENFORCEMENT_MODE));
  }

  @Test
  @DisplayName("AD_COLUMN must not give Included_Qty or Enforcement_Mode a DEFAULTVALUE")
  public void applicationDictionaryDeclaresNoDefaultValueForQuotaColumns() throws Exception {
    Path adColumn = moduleFile(AD_COLUMN_RELATIVE);
    Element includedQty = adColumnRecord(adColumn, "Included_Qty");
    Element enforcementMode = adColumnRecord(adColumn, "Enforcement_Mode");

    assertAll(
        () -> assertTrue(isBlank(childText(includedQty, "DEFAULTVALUE")),
            () -> "AD_COLUMN record for ETGO_PLAN_QUOTA.Included_Qty declares DEFAULTVALUE='"
                + childText(includedQty, "DEFAULTVALUE") + "' in " + adColumn
                + ". A Classic DEFAULTVALUE pre-fills the value into a new quota row as soon as an "
                + "operator opens the tab — even just to look — which caps the plan just as "
                + "effectively as a DDL default. " + WHY_INCLUDED_QTY),
        () -> assertTrue(isBlank(childText(enforcementMode, "DEFAULTVALUE")),
            () -> "AD_COLUMN record for ETGO_PLAN_QUOTA.Enforcement_Mode declares DEFAULTVALUE='"
                + childText(enforcementMode, "DEFAULTVALUE") + "' in " + adColumn
                + ". A Classic DEFAULTVALUE pre-fills the value into a new quota row as soon as an "
                + "operator opens the tab — even just to look. " + WHY_ENFORCEMENT_MODE));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Resolves a module-relative file, so the test runs whatever the working directory of the test
   * JVM is (module root or Etendo root). Skips (rather than fails) when the module root cannot be
   * located at all, because that means the checkout layout is not the one this guard inspects.
   */
  private Path moduleFile(Path relative) {
    Path root = moduleRoot();
    Path file = root.resolve(relative);
    assertTrue(Files.isRegularFile(file),
        () -> "Expected " + relative + " under the module root " + root.toAbsolutePath()
            + " but it is missing. This guard protects the 'no quota row means unlimited' "
            + "invariant of ETP-5046 / ETP-5051 and must be updated alongside the schema.");
    return file;
  }

  /**
   * Locates the {@code com.etendoerp.go} module root. Anchored on the module's OWN DDL file rather
   * than on each inspected path: Etendo Core ships its own
   * {@code src-db/database/sourcedata/AD_COLUMN.xml}, so a plain "does this relative path exist"
   * probe run from the Etendo root silently reads the CORE dictionary instead of the module's.
   */
  private Path moduleRoot() {
    Path here = Paths.get("");
    Optional<Path> root = firstModuleRoot(here, here.resolve(Paths.get("modules",
        "com.etendoerp.go")));
    Assumptions.assumeTrue(root.isPresent(),
        () -> "Skipping: could not locate the com.etendoerp.go module root from the working "
            + "directory (" + here.toAbsolutePath() + "). Expected to run with the module root or "
            + "the Etendo root as working directory.");
    return root.orElseThrow();
  }

  private Optional<Path> firstModuleRoot(Path... candidates) {
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate.resolve(DDL_RELATIVE))) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /** Finds a {@code <column name="..."/>} element in an Etendo DDL table file. */
  private Element ddlColumn(Path ddl, String columnName) throws Exception {
    NodeList columns = parse(ddl).getElementsByTagName("column");
    for (int i = 0; i < columns.getLength(); i++) {
      Element column = (Element) columns.item(i);
      if (columnName.equalsIgnoreCase(column.getAttribute("name"))) {
        return column;
      }
    }
    fail("Column " + columnName + " not found in " + ddl
        + ". If it was renamed or removed, this guard must be updated with it: it protects the "
        + "'no quota row means unlimited' invariant of ETP-5046 / ETP-5051.");
    return null;
  }

  /**
   * Finds the {@code <AD_COLUMN>} record of {@code ETGO_PLAN_QUOTA} whose {@code COLUMNNAME}
   * matches, matching on {@code COLUMNNAME} + {@code AD_TABLE_ID} so a same-named column of another
   * table cannot be picked up by accident.
   */
  private Element adColumnRecord(Path adColumn, String columnName) throws Exception {
    NodeList records = parse(adColumn).getElementsByTagName("AD_COLUMN");
    for (int i = 0; i < records.getLength(); i++) {
      Element record = (Element) records.item(i);
      if (columnName.equalsIgnoreCase(childText(record, "COLUMNNAME"))
          && PLAN_QUOTA_TABLE_ID.equalsIgnoreCase(childText(record, "AD_TABLE_ID"))) {
        return record;
      }
    }
    fail("No AD_COLUMN record for " + columnName + " of table ETGO_PLAN_QUOTA ("
        + PLAN_QUOTA_TABLE_ID + ") in " + adColumn
        + ". If it was renamed or removed, this guard must be updated with it: it protects the "
        + "'no quota row means unlimited' invariant of ETP-5046 / ETP-5051.");
    return null;
  }

  /** Direct-child text of {@code parent}, or the empty string when the child is absent. */
  private static String childText(Element parent, String tagName) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element && tagName.equalsIgnoreCase(child.getNodeName())) {
        String text = child.getTextContent();
        return text == null ? "" : text;
      }
    }
    return "";
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static final String DISALLOW_DOCTYPE_DECL =
      "http://apache.org/xml/features/disallow-doctype-decl";

  private static void setAttributeIfSupported(DocumentBuilderFactory factory, String name) {
    try {
      factory.setAttribute(name, "");
    } catch (IllegalArgumentException unsupported) {
      // The parser in use does not know this attribute; DISALLOW_DOCTYPE_DECL already covers us.
    }
  }

  private static Document parse(Path file)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // Primary XXE defence. Both the JDK parser and Xerces (which is on this module's runtime
    // classpath) support this feature, and disallowing the DOCTYPE outright makes the external
    // DTD/schema attributes below redundant rather than load-bearing.
    factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
    // Best effort only: Xerces' DocumentBuilderFactoryImpl does not recognise these two as
    // ATTRIBUTES and throws IllegalArgumentException, which previously failed this test before
    // it could assert anything. Belt-and-braces on parsers that do support them.
    setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
    setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(file.toFile());
  }
}
