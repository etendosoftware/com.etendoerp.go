/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.currency.Currency;

/**
 * Unit tests for {@link NeoCurrencyPrecision#resolve}: where the currency of a written record is
 * found (body, stored record, its {@code IsParent} record, the body's parent link, {@code parentId})
 * and that every unresolvable or failing case costs the default scale, never the write.
 *
 * <p>The model is mocked: a line entity with a currency-typed property and an {@code IsParent}
 * property to a header entity that also has one. No property is matched by name in the source, so
 * the names used here are arbitrary.
 *
 * @covers com.etendoerp.go.schemaforge.NeoCurrencyPrecision
 */
public class NeoCurrencyPrecisionTest {

  private static final String LINE_TABLE = "LINE-TABLE";
  private static final String HEADER_TABLE = "HEADER-TABLE";
  private static final String LINE_ENTITY = "TestLine";
  private static final String HEADER_ENTITY = "TestHeader";
  private static final String CURRENCY_KEY = "currency";
  private static final String PARENT_KEY = "header";

  private MockedStatic<OBContext> obContext;
  private MockedStatic<ModelProvider> modelProviderStatic;
  private MockedStatic<OBDal> obDalStatic;
  private OBDal dal;
  private Tab lineTab;

  @Before
  public void setUp() {
    obContext = mockStatic(OBContext.class);
    modelProviderStatic = mockStatic(ModelProvider.class);
    obDalStatic = mockStatic(OBDal.class);

    Entity currencyEntity = mock(Entity.class);
    when(currencyEntity.getName()).thenReturn(Currency.ENTITY_NAME);

    Entity headerEntity = mock(Entity.class);
    when(headerEntity.getName()).thenReturn(HEADER_ENTITY);
    Property headerCurrency = reference(CURRENCY_KEY, currencyEntity, false);
    when(headerEntity.getProperties()).thenReturn(Collections.singletonList(headerCurrency));

    Entity lineEntity = mock(Entity.class);
    when(lineEntity.getName()).thenReturn(LINE_ENTITY);
    Property lineCurrency = reference(CURRENCY_KEY, currencyEntity, false);
    Property parent = reference(PARENT_KEY, headerEntity, true);
    when(lineEntity.getProperties()).thenReturn(Arrays.asList(lineCurrency, parent));

    ModelProvider modelProvider = mock(ModelProvider.class);
    modelProviderStatic.when(ModelProvider::getInstance).thenReturn(modelProvider);
    when(modelProvider.getEntityByTableId(LINE_TABLE)).thenReturn(lineEntity);
    when(modelProvider.getEntityByTableId(HEADER_TABLE)).thenReturn(headerEntity);

    dal = mock(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    lineTab = tab(LINE_TABLE);
  }

  @After
  public void tearDown() {
    obDalStatic.close();
    modelProviderStatic.close();
    obContext.close();
  }

  private static Property reference(String name, Entity target, boolean isParent) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(name);
    when(property.isOneToMany()).thenReturn(false);
    when(property.getTargetEntity()).thenReturn(target);
    when(property.isParent()).thenReturn(isParent);
    return property;
  }

  private static Tab tab(String tableId) {
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(tableId);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    return tab;
  }

  private Currency currencyWithPrecision(String id, long precision) {
    Currency currency = mock(Currency.class);
    when(currency.getStandardPrecision()).thenReturn(precision);
    when(dal.get(Currency.class, id)).thenReturn(currency);
    return currency;
  }

  /** A stored record of {@code entityName} whose currency property holds {@code currency}. */
  private BaseOBObject storedRecord(String entityName, String id, Currency currency) {
    BaseOBObject rec = mock(BaseOBObject.class);
    when(rec.get(CURRENCY_KEY)).thenReturn(currency);
    when(dal.get(entityName, id)).thenReturn(rec);
    return rec;
  }

  /** A currency id in the body itself wins; StdPrecision 0 is honoured, not floored to 2. */
  @Test
  public void resolvesFromTheBodyCurrency() throws Exception {
    currencyWithPrecision("JPY", 0);
    JSONObject body = new JSONObject().put(CURRENCY_KEY, "JPY");

    assertEquals(0, NeoCurrencyPrecision.resolve(lineTab, body, null));
  }

  /** An update whose body names no currency reads the stored record's own one. */
  @Test
  public void resolvesFromTheStoredRecordOnUpdate() throws Exception {
    Currency kwd = currencyWithPrecision("KWD", 3);
    storedRecord(LINE_ENTITY, "line-1", kwd);

    assertEquals(3, NeoCurrencyPrecision.resolve(lineTab, new JSONObject(), "line-1"));
  }

  /** A stored record without its own currency falls back to its IsParent record's. */
  @Test
  public void resolvesFromTheStoredRecordsParent() throws Exception {
    Currency kwd = currencyWithPrecision("KWD", 3);
    BaseOBObject header = mock(BaseOBObject.class);
    when(header.get(CURRENCY_KEY)).thenReturn(kwd);
    BaseOBObject line = storedRecord(LINE_ENTITY, "line-1", null);
    when(line.get(PARENT_KEY)).thenReturn(header);

    assertEquals(3, NeoCurrencyPrecision.resolve(lineTab, new JSONObject(), "line-1"));
  }

  /** A create whose body carries the parent link reads the parent's currency. */
  @Test
  public void resolvesFromTheParentLinkInTheBody() throws Exception {
    Currency jpy = currencyWithPrecision("JPY", 0);
    storedRecord(HEADER_ENTITY, "hdr-1", jpy);
    JSONObject body = new JSONObject().put(PARENT_KEY, "hdr-1");

    assertEquals(0, NeoCurrencyPrecision.resolve(lineTab, body, null));
  }

  /** A create that still carries only {@code parentId} reads the parent tab's record. */
  @Test
  public void resolvesFromParentIdThroughTheParentTab() throws Exception {
    Currency kwd = currencyWithPrecision("KWD", 3);
    storedRecord(HEADER_ENTITY, "hdr-1", kwd);
    Tab headerTab = tab(HEADER_TABLE);
    KernelUtils kernelUtils = mock(KernelUtils.class);
    when(kernelUtils.getParentTab(lineTab)).thenReturn(headerTab);
    JSONObject body = new JSONObject().put("parentId", "hdr-1");

    try (MockedStatic<KernelUtils> kernel = mockStatic(KernelUtils.class)) {
      kernel.when(KernelUtils::getInstance).thenReturn(kernelUtils);

      assertEquals(3, NeoCurrencyPrecision.resolve(lineTab, body, null));
    }
  }

  /** Nothing in the body, no stored record, no parent: the default scale. */
  @Test
  public void nothingResolvableFallsBackToTwo() throws Exception {
    JSONObject body = new JSONObject().put("description", "x");

    assertEquals(NeoCurrencyPrecision.DEFAULT_SCALE,
        NeoCurrencyPrecision.resolve(lineTab, body, "missing-line"));
  }

  /** A tab without a table id answers 2 before touching OBContext or the model. */
  @Test
  public void blankTableIdReturnsTwoWithoutTouchingTheDal() {
    assertEquals(NeoCurrencyPrecision.DEFAULT_SCALE,
        NeoCurrencyPrecision.resolve(tab(" "), new JSONObject(), "line-1"));

    obContext.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    modelProviderStatic.verify(ModelProvider::getInstance, never());
  }

  /**
   * A failing DAL (admin mode or model lookup) costs the default scale, never an exception, and
   * never a leaked admin mode: a throwing {@code setAdminMode} has already pushed it.
   */
  @Test
  public void failuresNeverThrow() throws Exception {
    modelProviderStatic.when(ModelProvider::getInstance)
        .thenThrow(new IllegalStateException("model not loaded"));
    assertEquals(NeoCurrencyPrecision.DEFAULT_SCALE,
        NeoCurrencyPrecision.resolve(lineTab, new JSONObject(), "line-1"));

    obContext.when(() -> OBContext.setAdminMode(anyBoolean()))
        .thenThrow(new NoClassDefFoundError("OBContext"));
    obContext.clearInvocations();
    assertEquals(NeoCurrencyPrecision.DEFAULT_SCALE,
        NeoCurrencyPrecision.resolve(lineTab, new JSONObject(), "line-1"));
    obContext.verify(OBContext::restorePreviousMode, times(1));

    // setAdminMode pushes before it throws: the currency-id lookup must pop it too, untouched DAL.
    obContext.clearInvocations();
    assertEquals(NeoCurrencyPrecision.DEFAULT_SCALE,
        NeoCurrencyPrecision.scaleOfCurrencyId("EUR"));
    obContext.verify(OBContext::restorePreviousMode, times(1));
    obDalStatic.verify(OBDal::getInstance, never());
  }
}
