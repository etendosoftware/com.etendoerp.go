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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.SimpleExpression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * Unit tests for {@link ProductHandlerUtils}.
 */
class ProductHandlerUtilsTest {

  @Test
  @DisplayName("Utility class hides its constructor")
  void utilityClassHidesConstructor() throws ReflectiveOperationException {
    Constructor<ProductHandlerUtils> constructor = ProductHandlerUtils.class.getDeclaredConstructor();
    assertEquals(Modifier.PRIVATE, constructor.getModifiers() & Modifier.PRIVATE);
    constructor.setAccessible(true);
    constructor.newInstance();
  }


  private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = ProductHandlerUtils.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  @Nested
  @DisplayName("buildListResponse")
  class BuildListResponse {
    @Test
    void wrapsDataInStandardEnvelope() throws Exception {
      JSONArray data = new JSONArray();
      data.put(new JSONObject().put("id", "1"));
      data.put(new JSONObject().put("id", "2"));

      NeoResponse response = (NeoResponse) invokeStatic("buildListResponse",
          new Class<?>[]{ JSONArray.class }, data);

      assertEquals(200, response.getHttpStatus());
      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(2, inner.getJSONArray("data").length());
      assertEquals(0, inner.getInt("startRow"));
      assertEquals(2, inner.getInt("endRow"));
      assertEquals(2, inner.getInt("totalRows"));
      assertEquals(0, inner.getInt("status"));
    }

    @Test
    void nullDataReturnsEmptyArray() throws Exception {
      NeoResponse response = (NeoResponse) invokeStatic("buildListResponse",
          new Class<?>[]{ JSONArray.class }, (JSONArray) null);

      assertEquals(200, response.getHttpStatus());
      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(0, inner.getJSONArray("data").length());
      assertEquals(0, inner.getInt("totalRows"));
    }

    @Test
    void emptyDataReturnsZeroRows() throws Exception {
      NeoResponse response = (NeoResponse) invokeStatic("buildListResponse",
          new Class<?>[]{ JSONArray.class }, new JSONArray());

      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(0, inner.getInt("endRow"));
    }
  }

  @Nested
  @DisplayName("toBigDecimal")
  class ToBigDecimal {
    @Test
    void nullReturnsZero() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, (Object) null);
      assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void bigDecimalPassesThrough() throws Exception {
      BigDecimal input = new BigDecimal("123.45");
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, input);
      assertEquals(input, result);
    }

    @Test
    void stringConvertsToBigDecimal() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, "999.99");
      assertEquals(new BigDecimal("999.99"), result);
    }

    @Test
    void integerConvertsToBigDecimal() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, 42);
      assertEquals(new BigDecimal("42"), result);
    }

    @Test
    void unparseableStringReturnsZero() throws Exception {
      BigDecimal result = (BigDecimal) invokeStatic("toBigDecimal",
          new Class<?>[]{ Object.class }, "not-a-number");
      assertEquals(BigDecimal.ZERO, result);
    }
  }

  /**
   * ETP-5245: the shared "is this product already priced on this tariff?" lookup. Both writers of
   * M_ProductPrice (the default-tariff seeding in {@code ProductDefaultsHandler} and the upsert in
   * {@code ProductPriceHandler}) go through it, so the unique constraint on
   * {@code (M_PriceList_Version_ID, M_Product_ID)} is checked the same way on both paths.
   */
  @Nested
  @DisplayName("findExistingPrice")
  class FindExistingPrice {

    /** Criteria mock that records its Restrictions.eq calls and returns a canned result list. */
    @SuppressWarnings("unchecked")
    private OBCriteria<ProductPrice> stubCriteria(OBDal dal, Map<String, Object> restrictions,
        java.util.List<ProductPrice> results) {
      OBCriteria<ProductPrice> crit = mock(OBCriteria.class);
      when(dal.createCriteria(ProductPrice.class)).thenReturn(crit);
      when(crit.add(any(Criterion.class))).thenAnswer(invocation -> {
        Criterion criterion = invocation.getArgument(0);
        if (criterion instanceof SimpleExpression) {
          SimpleExpression expression = (SimpleExpression) criterion;
          restrictions.put(expression.getPropertyName(), expression.getValue());
        }
        return crit;
      });
      when(crit.setMaxResults(anyInt())).thenReturn(crit);
      when(crit.list()).thenReturn(results);
      return crit;
    }

    @Test
    @DisplayName("null productId short-circuits without touching the database")
    void nullProductIdReturnsNull() {
      // OBDal is deliberately NOT mocked: reaching it would blow up, proving the guard runs first.
      assertNull(ProductHandlerUtils.findExistingPrice(null, "plv-1"));
    }

    @Test
    @DisplayName("null priceListVersionId short-circuits without touching the database")
    void nullVersionIdReturnsNull() {
      assertNull(ProductHandlerUtils.findExistingPrice("prod-1", null));
    }

    @Test
    @DisplayName("returns null when the product has no price on that tariff")
    void noRowReturnsNull() {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        stubCriteria(dal, new HashMap<>(), Collections.emptyList());

        assertNull(ProductHandlerUtils.findExistingPrice("prod-1", "plv-1"));
      }
    }

    @Test
    @DisplayName("returns the existing row when the product is already priced there")
    void existingRowIsReturned() {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        ProductPrice existing = mock(ProductPrice.class);
        ProductPrice other = mock(ProductPrice.class);
        stubCriteria(dal, new HashMap<>(), Arrays.asList(existing, other));

        assertSame(existing, ProductHandlerUtils.findExistingPrice("prod-1", "plv-1"));
      }
    }

    @Test
    @DisplayName("scopes the lookup to the product and the version, and to nothing else")
    void appliesTheExpectedRestrictions() {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        Map<String, Object> restrictions = new HashMap<>();
        OBCriteria<ProductPrice> crit = stubCriteria(dal, restrictions, Collections.emptyList());

        ProductHandlerUtils.findExistingPrice("prod-1", "plv-1");

        assertEquals("prod-1", restrictions.get("product.id"));
        assertEquals("plv-1", restrictions.get("priceListVersion.id"));
        // The lookup answers "is the unique pair taken?", and M_PRODUCTPRICE_PRICELIST_VE_UN
        // covers (M_PriceList_Version_ID, M_Product_ID) only — a deactivated row still occupies
        // it. Narrowing the search to active rows would report a taken pair as free and the
        // INSERT that follows would hit the constraint, so the flag must not be restricted here.
        assertFalse(restrictions.containsKey("active"),
            "findExistingPrice must not restrict on the active flag");
        verify(crit, times(1)).setMaxResults(1);
      }
    }

    @Test
    @DisplayName("turns OBCriteria's implicit active filter off, or the blindness is only half done")
    void disablesTheImplicitActiveFilter() {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        OBCriteria<ProductPrice> crit =
            stubCriteria(dal, new HashMap<>(), Collections.emptyList());

        ProductHandlerUtils.findExistingPrice("prod-1", "plv-1");

        // OBCriteria filters on isActive by default, so dropping the explicit Restrictions.eq
        // alone would change nothing — this call is what actually widens the search.
        verify(crit, times(1)).setFilterOnActive(false);
      }
    }

    @Test
    @DisplayName("finds a DEACTIVATED row — the case that used to violate the unique constraint")
    void inactiveRowIsStillFound() {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        ProductPrice deactivated = mock(ProductPrice.class);
        when(deactivated.isActive()).thenReturn(Boolean.FALSE);
        stubCriteria(dal, new HashMap<>(), Collections.singletonList(deactivated));

        // The row is invisible in the UI but still holds the (version, product) pair, so it has
        // to be reported as "already priced" and upserted rather than inserted alongside.
        assertSame(deactivated, ProductHandlerUtils.findExistingPrice("prod-1", "plv-1"));
      }
    }
  }
}
