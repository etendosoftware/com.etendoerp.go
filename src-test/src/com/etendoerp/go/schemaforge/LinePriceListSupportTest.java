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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * Unit tests for {@link LinePriceListSupport}.
 *
 * @covers com.etendoerp.go.schemaforge.LinePriceListSupport
 */
public class LinePriceListSupportTest {

  private static final String UNIT = "unitPrice";
  private static final String LIST = "listPrice";
  private static final String DISC = "discount";

  private static boolean clientPrice(JSONObject body) throws Exception {
    return LinePriceListSupport.applyClientListPrice(body, UNIT, LIST, DISC);
  }

  private static ProductPrice catalog(String standard, String list) {
    ProductPrice price = mock(ProductPrice.class);
    when(price.getStandardPrice()).thenReturn(standard == null ? null : new BigDecimal(standard));
    when(price.getListPrice()).thenReturn(list == null ? null : new BigDecimal(list));
    return price;
  }

  @Test
  public void applyClientListPriceAppliesTheDiscountWithSixDecimalsHalfUp() throws Exception {
    JSONObject body = new JSONObject("{\"listPrice\":10,\"discount\":33.333333}");

    assertTrue(clientPrice(body));

    // 10 * 66.666667 / 100 = 6.6666667 -> 6.666667
    assertEquals(0, new BigDecimal("6.666667").compareTo(new BigDecimal(body.get(UNIT).toString())));
  }

  @Test
  public void applyClientListPriceTreatsAMissingDiscountAsZero() throws Exception {
    JSONObject body = new JSONObject("{\"listPrice\":12.5}");

    assertTrue(clientPrice(body));

    assertEquals(0, new BigDecimal("12.5").compareTo(new BigDecimal(body.get(UNIT).toString())));
  }

  @Test
  public void applyClientListPriceTreatsANullDiscountAsZero() throws Exception {
    JSONObject body = new JSONObject("{\"listPrice\":12.5,\"discount\":null}");

    assertTrue(clientPrice(body));

    assertEquals(0, new BigDecimal("12.5").compareTo(new BigDecimal(body.get(UNIT).toString())));
  }

  @Test
  public void applyClientListPriceAcceptsTheDiscountBoundaries() throws Exception {
    JSONObject full = new JSONObject("{\"listPrice\":10,\"discount\":100}");
    JSONObject none = new JSONObject("{\"listPrice\":10,\"discount\":0}");

    assertTrue(clientPrice(full));
    assertTrue(clientPrice(none));

    assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(full.get(UNIT).toString())));
    assertEquals(0, BigDecimal.TEN.compareTo(new BigDecimal(none.get(UNIT).toString())));
  }

  @Test
  public void applyClientListPriceWritesNothingForANegativeDiscountButStillHandlesIt()
      throws Exception {
    JSONObject body = new JSONObject("{\"listPrice\":10,\"discount\":-1}");

    assertTrue(clientPrice(body));

    assertFalse(body.has(UNIT));
  }

  @Test
  public void applyClientListPriceWritesNothingForADiscountAboveOneHundred() throws Exception {
    JSONObject body = new JSONObject("{\"listPrice\":10,\"discount\":100.01}");

    assertTrue(clientPrice(body));

    assertFalse(body.has(UNIT));
  }

  @Test
  public void applyClientListPriceReturnsFalseForAZeroOrMissingListPrice() throws Exception {
    JSONObject zero = new JSONObject("{\"listPrice\":0,\"discount\":5}");
    JSONObject missing = new JSONObject("{\"discount\":5}");

    assertFalse(clientPrice(zero));
    assertFalse(clientPrice(missing));

    assertFalse(zero.has(UNIT));
    assertFalse(missing.has(UNIT));
  }

  @Test
  public void applyClientListPriceDoesNotOverrideAClientUnitPrice() throws Exception {
    JSONObject body = new JSONObject("{\"listPrice\":10,\"unitPrice\":7}");

    assertFalse(clientPrice(body));

    assertEquals(7, body.getInt(UNIT));
  }

  @Test
  public void applyClientListPriceReturnsFalseForANullBody() throws Exception {
    assertFalse(clientPrice(null));
  }

  @Test
  public void applyCatalogPriceWritesStandardAndListPrice() throws Exception {
    JSONObject body = new JSONObject();

    assertTrue(LinePriceListSupport.applyCatalogPrice(body, catalog("8", "10"), UNIT, LIST));

    assertEquals(0, new BigDecimal("8").compareTo((BigDecimal) body.get(UNIT)));
    assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) body.get(LIST)));
  }

  @Test
  public void applyCatalogPriceKeepsAListPriceAlreadyInTheBody() throws Exception {
    JSONObject body = new JSONObject("{\"listPrice\":99}");

    assertTrue(LinePriceListSupport.applyCatalogPrice(body, catalog("8", "10"), UNIT, LIST));

    assertEquals(99, body.getInt(LIST));
  }

  @Test
  public void applyCatalogPriceSkipsAZeroOrNullCatalogListPrice() throws Exception {
    JSONObject zeroList = new JSONObject();
    JSONObject nullList = new JSONObject();

    assertTrue(LinePriceListSupport.applyCatalogPrice(zeroList, catalog("8", "0"), UNIT, LIST));
    assertTrue(LinePriceListSupport.applyCatalogPrice(nullList, catalog("8", null), UNIT, LIST));

    assertFalse(zeroList.has(LIST));
    assertFalse(nullList.has(LIST));
  }

  @Test
  public void applyCatalogPriceAbstainsOnANullOrZeroStandardPrice() throws Exception {
    JSONObject nullStandard = new JSONObject();
    JSONObject zeroStandard = new JSONObject();

    assertFalse(LinePriceListSupport.applyCatalogPrice(nullStandard, catalog(null, "10"), UNIT,
        LIST));
    assertFalse(LinePriceListSupport.applyCatalogPrice(zeroStandard, catalog("0", "10"), UNIT,
        LIST));

    assertFalse(nullStandard.has(UNIT));
    assertFalse(zeroStandard.has(UNIT));
  }

  @Test
  public void applyCatalogPriceAbstainsWithoutACatalogEntryOrWhenTheBodyIsPriced()
      throws Exception {
    JSONObject priced = new JSONObject("{\"unitPrice\":5}");

    assertFalse(LinePriceListSupport.applyCatalogPrice(new JSONObject(), null, UNIT, LIST));
    assertFalse(LinePriceListSupport.applyCatalogPrice(priced, catalog("8", "10"), UNIT, LIST));
    assertFalse(LinePriceListSupport.applyCatalogPrice(null, catalog("8", "10"), UNIT, LIST));

    assertEquals(5, priced.getInt(UNIT));
  }
}
