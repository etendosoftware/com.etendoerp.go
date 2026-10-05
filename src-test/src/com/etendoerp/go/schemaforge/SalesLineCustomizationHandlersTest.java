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

import static com.etendoerp.go.schemaforge.LineAmountTestSupport.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;

/**
 * The three ETP-5528 line customizations — {@link SalesOrderLineHandler},
 * {@link SalesQuotationLineHandler} and {@link SalesInvoiceLineHandler} — share one shape: bound by
 * {@link NeoExtension} only, extending the handler the purchase side keeps using. What can regress
 * is the binding (a wrong pair, a stray {@code @Named} making the parent's qualifier ambiguous, a
 * purchase entity caught by it) and the wiring of {@code handle} to its support class.
 *
 * <p>{@link NeoExtensionIndex} is exercised for real: only {@link WeldUtils}'s bean manager is
 * mocked, so the index is built from the handlers' own annotations exactly as in production.
 *
 * <p>Not covered: "a response from the parent's {@code handle} short-circuits". Neither
 * {@link OrderLineHandler#handle} nor {@link InvoiceLineHandler#handle} ever returns a non-null
 * response, so that branch cannot be reached through real input.
 */
class SalesLineCustomizationHandlersTest {

  private static final String TAX_ID = "TAX21";

  static Stream<Arguments> customizations() {
    String orderLine = "{\"orderedQuantity\":10,\"unitPrice\":18,\"tax\":\"" + TAX_ID + "\"}";
    String invoiceLine = "{\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":\"" + TAX_ID + "\"}";
    return Stream.of(
        Arguments.of(SalesOrderLineHandler.class, OrderLineHandler.class, "sales-order", "lines",
            orderLine, "lineGrossAmount"),
        Arguments.of(SalesQuotationLineHandler.class, OrderLineHandler.class, "sales-quotation",
            "quotationLine", orderLine, "lineGrossAmount"),
        Arguments.of(SalesInvoiceLineHandler.class, InvoiceLineHandler.class, "sales-invoice",
            "lines", invoiceLine, "grossAmount"));
  }

  @BeforeEach
  void resetIndex() {
    NeoExtensionIndex.invalidate();
  }

  @AfterEach
  void dropIndexBuiltFromMocks() {
    NeoExtensionIndex.invalidate();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("customizations")
  void bindingIsTheAnnotationOnly(Class<?> handler, Class<?> parent, String spec,
      String entity) {
    NeoExtension binding = handler.getAnnotation(NeoExtension.class);

    assertNotNull(binding, handler.getSimpleName() + " must be bound by @NeoExtension");
    assertEquals(spec, binding.spec());
    assertEquals(entity, binding.entity());
    assertNull(handler.getAnnotation(Named.class),
        "a @Named here would make the parent's qualifier ambiguous");
    assertEquals(parent, handler.getSuperclass(),
        "the customization must inherit everything the parent handler does");
  }

  /**
   * A create with quantity, price and tax: the parent abstains (no gross price to normalise, no
   * return invoice), then the customization derives the gross amount — Fernet at 18, qty 10, 21 %
   * → 217.80 — and still returns {@code null}, so default CRUD continues. The parent alone, which
   * is what the purchase side runs, leaves the body without it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("customizations")
  void handleRunsTheParentThenDerivesTheAmounts(Class<? extends NeoHandler> handler,
      Class<? extends NeoHandler> parent, String spec, String entity, String createBody,
      String amountKey) throws Exception {
    JSONObject customized = new JSONObject(createBody);
    JSONObject plain = new JSONObject(createBody);

    NeoResponse response;
    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      response = handler.getDeclaredConstructor().newInstance()
          .handle(createContext(spec, entity, customized));
      parent.getDeclaredConstructor().newInstance().handle(createContext(spec, entity, plain));
    }

    assertNull(response, "the pre-hook must let default CRUD continue");
    assertAmount(handler.getSimpleName(), "217.80", customized, amountKey);
    assertAmount(parent.getSimpleName(), null, plain, amountKey);
  }

  static Stream<Arguments> resolutions() {
    return Stream.of(
        Arguments.of("sales-order", "lines", "orderLineHandler", SalesOrderLineHandler.class),
        Arguments.of("sales-quotation", "quotationLine", "orderLineHandler",
            SalesQuotationLineHandler.class),
        Arguments.of("sales-invoice", "lines", "invoiceLineHandler",
            SalesInvoiceLineHandler.class),
        Arguments.of("purchase-order", "lines", "orderLineHandler", null),
        Arguments.of("purchase-invoice", "lines", "invoiceLineHandler", null));
  }

  /**
   * The index resolves each customization for its own pair and nothing for the purchase pairs,
   * which fall back to the row's qualifier — the plain parent — as before. The deployment also holds
   * the unannotated parents, as in production.
   */
  @ParameterizedTest(name = "{0}/{1}")
  @MethodSource("resolutions")
  void indexResolvesOnlyTheSalesPairs(String spec, String entity, String qualifier,
      Class<?> expected) {
    BeanManager bm = mock(BeanManager.class);
    Set<Bean<?>> beans = new LinkedHashSet<>();
    beans.add(deployedBean(bm, new SalesOrderLineHandler()));
    beans.add(deployedBean(bm, new SalesQuotationLineHandler()));
    beans.add(deployedBean(bm, new SalesInvoiceLineHandler()));
    beans.add(deployedBean(bm, new OrderLineHandler()));
    beans.add(deployedBean(bm, new InvoiceLineHandler()));
    when(bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL)).thenReturn(beans);

    NeoHandler resolved;
    try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);
      resolved = NeoExtensionIndex.resolve(spec, entity, qualifier);
    }

    if (expected == null) {
      assertNull(resolved, spec + "/" + entity + " must fall back to its qualifier");
    } else {
      assertNotNull(resolved, spec + "/" + entity + " must resolve by annotation");
      assertEquals(expected, resolved.getClass());
    }
  }

  /** A CDI bean for {@code instance}: its declared class, and the instance as its reference. */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Bean<?> deployedBean(BeanManager bm, NeoHandler instance) {
    Bean bean = mock(Bean.class);
    doReturn(instance.getClass()).when(bean).getBeanClass();
    doReturn(instance).when(bm).getReference(same(bean), eq(NeoHandler.class), any());
    return bean;
  }

  private static NeoContext createContext(String spec, String entity, JSONObject body) {
    return NeoContext.builder()
        .specName(spec)
        .entityName(entity)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
  }
}
