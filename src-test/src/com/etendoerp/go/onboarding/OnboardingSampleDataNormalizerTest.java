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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;

/**
 * ETP-5426 — the sample-data pass of {@link OnboardingDatasetNormalizer} over the real bundled
 * GOClient dataset ({@link OnboardingDatasetProfile#SAMPLE_DATA}).
 *
 * <p>Counted per entity rather than string-matched, for the reason
 * {@code OnboardingDatasetNormalizerTest#testNormalizerDropsDemoMasterDataTogetherWithItsChildRows}
 * gives: child rows carry only ids, so a substring assertion cannot see them. Each count below is
 * the row count the source file ships (or, for the shared master-data tables, the number of demo
 * rows in it), so a change to the dataset shows up here as a deliberate number to update.
 */
public class OnboardingSampleDataNormalizerTest {

  /**
   * The sample pass is the exact complement of the base pass in the shared master-data tables: it
   * keeps the demo rows the base pass drops, and none of the rows the base pass already imported.
   */
  @Test
  public void testSamplePassKeepsOnlyTheDemoMasterData() {
    String xml = sampleDataXml();

    assertEquals("the four sample products, never the internal ETGO_DTO", 4,
        countEntities(xml, "mProduct"));
    assertFalse("ETGO_DTO already reached the tenant through the base pass", xml.contains("ETGO_DTO"));
    assertEquals("the price rows of the four sample products", 8,
        countEntities(xml, "mProductprice"));
    assertEquals("Caja, Tarjeta and Cuenta de Banco", 3, countEntities(xml, "finFinancialAccount"));
    assertEquals("the payment methods of the three template accounts", 6,
        countEntities(xml, "finFinaccPaymentmethod"));
    assertEquals("only the secondary warehouse", 1, countEntities(xml, "mWarehouse"));
    assertFalse("the primary warehouse already reached the tenant", xml.contains("Almacen Principal"));
    assertEquals("only the secondary warehouse's locator", 1, countEntities(xml, "mLocator"));
    assertEquals("only the secondary warehouse's org assignment", 1,
        countEntities(xml, "adOrgWarehouse"));
    assertEquals("only Beverages", 1, countEntities(xml, "mProductCategory"));
    assertEquals("only Beverages' translation", 1, countEntities(xml, "mProductCategoryTrl"));
  }

  /** Every transactional row of GOClient is sample data, including the fixed assets. */
  @Test
  public void testSamplePassTakesTheTransactionalChainWhole() {
    String xml = sampleDataXml();

    assertEquals(5, countEntities(xml, "cBpartner"));
    assertEquals(5, countEntities(xml, "cBpartnerLocation"));
    assertEquals(9, countEntities(xml, "cOrder"));
    assertEquals(22, countEntities(xml, "cOrderline"));
    assertEquals(9, countEntities(xml, "cInvoice"));
    assertEquals(22, countEntities(xml, "cInvoiceline"));
    assertEquals(9, countEntities(xml, "mInout"));
    assertEquals(22, countEntities(xml, "mInoutline"));
    assertEquals(8, countEntities(xml, "finPayment"));
    assertEquals(8, countEntities(xml, "finFinaccTransaction"));
    assertEquals(18, countEntities(xml, "finPaymentSchedule"));
    assertEquals(1, countEntities(xml, "mInventory"));
    assertEquals(2, countEntities(xml, "mMovement"));
    assertEquals(8, countEntities(xml, "mStorageDetail"));
    assertEquals(30, countEntities(xml, "mTransaction"));
    assertEquals(10, countEntities(xml, "mMatchinv"));
    assertEquals(20, countEntities(xml, "mMatchpo"));
    assertEquals(29, countEntities(xml, "mCosting"));
    assertEquals(3, countEntities(xml, "aAsset"));
    assertEquals(1, countEntities(xml, "aAmortization"));
  }

  /**
   * The documents arrive unposted: no accounting entries, and no document claims to be posted.
   * {@code 'D'} (posting disabled — GOClient's payments and movements) is not a posted state and is
   * kept as it is.
   */
  @Test
  public void testSamplePassImportsDocumentsUnposted() {
    String xml = sampleDataXml();

    assertEquals("FACT_ACCT is never part of the sample data", 0, countEntities(xml, "factAcct"));
    Matcher posted = Pattern.compile("<posted>([^<]*)</posted>").matcher(xml);
    int postedColumns = 0;
    while (posted.find()) {
      postedColumns++;
      String value = posted.group(1);
      assertTrue("POSTED must be N or D, found " + value, "N".equals(value) || "D".equals(value));
    }
    assertTrue("the documents carry a POSTED column to rewrite", postedColumns > 0);
  }

  /** {@code AD_USER_ID} points at GOAdmin/GOuser, which a tenant does not have. */
  @Test
  public void testSamplePassStripsTheGoClientUsersFromDocuments() {
    String xml = sampleDataXml();

    assertFalse("documents and assets must not reference GOClient's users",
        xml.contains("<adUserId>"));
  }

  /**
   * Every sample row belongs to the tenant's business organization, including GOClient's
   * client-level partners, products, warehouse and locator. A partner left at {@code '0'} fails the
   * import: its {@code EM_Etgo_Identifier} is generated from a transactional sequence onboarding only
   * creates for the business organization ("No sequence found: EM_Etgo_Identifier").
   */
  @Test
  public void testSamplePassMovesClientLevelRowsToTheBusinessOrganization() {
    String xml = sampleDataXml();

    assertFalse("no sample row may stay at the client-level organization",
        xml.contains("<organization id=\"0\""));
    assertTrue(xml.contains("<organization id=\"TARGET_ORG\""));
  }

  /**
   * The four partners GOClient ships without an identifier get the stand-in, which keeps the
   * transactional sequence generator from handing all of them the same number during the import;
   * the service renumbers them afterwards. The partner that has one keeps it.
   */
  @Test
  public void testSamplePassGivesPartnersWithoutIdentifierThePendingStandIn() {
    String xml = sampleDataXml();

    String pending = "<emEtgoIdentifier>" + OnboardingSampleDataDefinition.PENDING_IDENTIFIER
        + "</emEtgoIdentifier>";
    assertEquals(4, xml.split(Pattern.quote(pending), -1).length - 1);
    assertTrue(xml.contains("<emEtgoIdentifier>Español</emEtgoIdentifier>"));
  }

  /** The base pass is unchanged: it still drops what the sample pass keeps. */
  @Test
  public void testBasePassStillExcludesTheSampleData() {
    String xml = new OnboardingDatasetNormalizer(sampleDataDir(), this::mockEntityForTable,
        (entityName, rawValue) -> rawValue, OnboardingDatasetProfile.ONBOARDING)
        .buildDatasetXml("TARGET_ORG");

    assertTrue("the base pass keeps client-level configuration at the client-level organization",
        xml.contains("<organization id=\"0\""));

    assertEquals("only ETGO_DTO", 1, countEntities(xml, "mProduct"));
    assertEquals(0, countEntities(xml, "cBpartner"));
    assertEquals(0, countEntities(xml, "cOrder"));
    assertEquals(0, countEntities(xml, "aAsset"));
  }

  // ── internals ──────────────────────────────────────────────────────────────

  private String sampleDataXml() {
    return new OnboardingDatasetNormalizer(sampleDataDir(), this::mockEntityForTable,
        (entityName, rawValue) -> rawValue, OnboardingDatasetProfile.SAMPLE_DATA)
        .buildDatasetXml("TARGET_ORG");
  }

  /** Same counting rule as {@code OnboardingDatasetNormalizerTest#countEntities}. */
  private int countEntities(String xml, String entityName) {
    String openingTag = "<" + entityName + " ";
    int count = 0;
    for (int at = xml.indexOf(openingTag); at >= 0; at = xml.indexOf(openingTag, at + 1)) {
      count++;
    }
    return count;
  }

  private Entity mockEntityForTable(String tableName) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn(toLowerCamel(tableName));
    when(entity.getTableName()).thenReturn(tableName);
    when(entity.isOrganizationEnabled()).thenReturn(true);
    when(entity.getPropertyByColumnName(anyString(), eq(false)))
        .thenAnswer(invocation -> mockProperty(tableName, invocation.getArgument(0)));
    return entity;
  }

  private Property mockProperty(String tableName, String columnName) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(
        columnName.equals(tableName + "_ID") ? "id" : toLowerCamel(columnName));
    when(property.isId()).thenReturn(columnName.equals(tableName + "_ID"));
    when(property.isOneToMany()).thenReturn(false);
    when(property.isPrimitive()).thenReturn(true);
    return property;
  }

  private String toLowerCamel(String value) {
    String[] parts = value.toLowerCase().split("_");
    StringBuilder builder = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      builder.append(Character.toUpperCase(parts[i].charAt(0)));
      builder.append(parts[i].substring(1));
    }
    return builder.toString();
  }

  private Path sampleDataDir() {
    Path moduleRelative = Paths.get("referencedata", "sampledata", "GOClient");
    if (Files.exists(moduleRelative)) {
      return moduleRelative;
    }
    Path rootRelative = Paths.get("modules", "com.etendoerp.go", "referencedata", "sampledata",
        "GOClient");
    if (Files.exists(rootRelative)) {
      return rootRelative;
    }
    throw new IllegalStateException("GOClient sampledata directory not found from current working directory");
  }
}
