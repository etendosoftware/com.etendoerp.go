/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Named;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.base.weld.WeldUtils;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.handlers.PaymentInReactivateHandler;
import com.etendoerp.go.schemaforge.handlers.PaymentOutReactivateHandler;
import com.etendoerp.go.schemaforge.handlers.ReactivatePaymentHandler;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * The handlers migrated to {@link NeoExtension} by ETP-5588: the financial-account, sales-invoice,
 * purchase-invoice and period-control headers, and the payment Reactivate/Confirm/Remove handler
 * split into one binding per entity, later joined by the goods-shipment and sales-quotation
 * headers. Their rows carry no {@code Java_Qualifier} and the classes no
 * {@code @Named}, so the annotation is the only binding. What can regress is the binding itself (a
 * wrong {@code (spec, entity)} pair, a {@code @Named} creeping back) and the resolution: every
 * channel and every entity-level reader must find the class with no qualifier to fall back on.
 *
 * <p>{@link NeoExtensionIndex} is exercised for real: only {@link WeldUtils}'s bean manager is
 * mocked, so the index is built from the handlers' own annotations exactly as in production.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountHandler
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PeriodOpenCloseHandler
 * @covers com.etendoerp.go.schemaforge.handlers.PaymentInReactivateHandler
 * @covers com.etendoerp.go.schemaforge.handlers.PaymentOutReactivateHandler
 * @covers com.etendoerp.go.schemaforge.GoodsShipmentHeaderHandler
 * @covers com.etendoerp.go.schemaforge.SalesQuotationHeaderHandler
 * @covers com.etendoerp.go.schemaforge.util.NeoHandlerLookup
 */
@DisplayName("ETP-5588 — migrated handlers resolve through @NeoExtension alone")
class NeoExtensionMigrationTest {

  /** One {@code (spec, entity)} binding: the class that carries it and the pair. */
  private record Binding(Class<? extends NeoHandler> type, String spec, String entity) {
  }

  private static final Binding[] MIGRATED = {
      new Binding(FinancialAccountHandler.class, "financial-account", "account"),
      new Binding(SalesInvoiceHeaderHandler.class, "sales-invoice", "header"),
      new Binding(PurchaseInvoiceHeaderHandler.class, "purchase-invoice", "header"),
      new Binding(PeriodOpenCloseHandler.class, "open-close-period-control", "periodControl"),
      new Binding(PaymentInReactivateHandler.class, "payment-in", "finPayment"),
      new Binding(PaymentOutReactivateHandler.class, "payment-out", "header"),
      new Binding(GoodsShipmentHeaderHandler.class, "goods-shipment", "goodsShipment"),
      new Binding(SalesQuotationHeaderHandler.class, "sales-quotation", "quotation") };

  /** The bean classes to deploy in the mocked CDI container, one per migrated binding. */
  private static final List<Class<? extends NeoHandler>> MIGRATED_TYPES = Stream.of(MIGRATED)
      .map(Binding::type).collect(Collectors.toList());

  static Stream<Binding> bindings() {
    return Stream.of(MIGRATED);
  }

  @BeforeEach
  void resetIndex() {
    NeoExtensionIndex.invalidate();
  }

  @AfterEach
  void dropIndexBuiltFromMocks() {
    NeoExtensionIndex.invalidate();
    NeoExtensionTraceRecorder.stopRecording();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bindings")
  void bindingIsTheAnnotationAlone(Binding binding) {
    NeoExtension annotation = binding.type().getAnnotation(NeoExtension.class);

    assertNotNull(annotation, binding.type().getSimpleName() + " must be bound by @NeoExtension");
    assertEquals(binding.spec(), annotation.spec(), "spec must match the ETGO_SF_SPEC row");
    assertEquals(binding.entity(), annotation.entity(), "entity must match the ETGO_SF_ENTITY row");
    assertNull(binding.type().getAnnotation(Named.class), binding.type().getSimpleName()
        + " must not carry @Named: the qualifier binding is retired for this pair");
  }

  /**
   * The acceptance criterion in one assertion: with no qualifier at all, the annotation is found on
   * REST single, REST batch and MCP. {@link NeoExtensionDispatcher#resolveOnly} resolves without
   * invoking, so no handler's {@code handle} runs and the test stays a pure routing check.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("bindings")
  void dispatcherResolvesTheAnnotationOnEveryChannelWithoutAQualifier(Binding binding) {
    BeanManager bm = mock(BeanManager.class);
    Map<Class<?>, NeoHandler> deployed = deploy(bm, MIGRATED_TYPES);

    try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);

      for (NeoExtensionChannel channel : NeoExtensionChannel.values()) {
        NeoHandler resolved = NeoExtensionDispatcher.resolveOnly(NeoExtensionRequest.builder()
            .specName(binding.spec())
            .entityName(binding.entity())
            .surface(NeoExtensionSurface.READ)
            .channel(channel)
            .build());

        assertSame(deployed.get(binding.type()), resolved,
            binding.spec() + "/" + binding.entity() + " must resolve by annotation on " + channel);
      }
    }
  }

  /**
   * The readers that do not dispatch a hook — the MCP read post-hook, the REST {@code afterCallout},
   * the action contracts, {@code protectedCreateCalloutFields}, the field filter's read-only
   * exemption, the report path — all go through {@link NeoHandlerLookup}. Before ETP-5588 they read
   * {@code Java_Qualifier} directly, so clearing it switched the customization off on those
   * surfaces; this is the assertion that it no longer does.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("bindings")
  void entityLevelReadersFindTheAnnotationWithoutAQualifier(Binding binding) {
    BeanManager bm = mock(BeanManager.class);
    Map<Class<?>, NeoHandler> deployed = deploy(bm, MIGRATED_TYPES);
    SFEntity entity = entity(binding.spec(), binding.entity(), null);

    try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);

      NeoHandler expected = deployed.get(binding.type());
      assertSame(expected, NeoHandlerLookup.forEntity(entity));
      assertSame(expected, NeoHandlerLookup.forEntityQuietly(entity));
      assertSame(expected, NeoHandlerLookup.forEntity(entity,
          q -> fail("no qualifier to fall back on, got " + q)));
      assertTrue(NeoHandlerLookup.hasCustomization(entity),
          "an annotated entity is customized even without a qualifier");
    }
  }

  @Test
  @DisplayName("an entity no class claims falls back to its qualifier, and without one has nothing")
  void unclaimedEntityFallsBackToTheQualifier() {
    BeanManager bm = mock(BeanManager.class);
    deploy(bm, MIGRATED_TYPES);
    NeoHandler legacy = mock(NeoHandler.class);

    try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);

      SFEntity qualified = entity("contacts", "businessPartner", "businessPartnerHandler");
      assertSame(legacy, NeoHandlerLookup.forEntity(qualified,
          q -> "businessPartnerHandler".equals(q) ? legacy : null));
      assertTrue(NeoHandlerLookup.hasCustomization(qualified));

      SFEntity plain = entity("contacts", "contact", null);
      assertNull(NeoHandlerLookup.forEntity(plain, q -> fail("a blank qualifier is never resolved")));
      assertFalse(NeoHandlerLookup.hasCustomization(plain));
    }
  }

  /**
   * The payment base stays unannotated and unnamed: it holds the behaviour, and the binding lives in
   * its two subclasses. Annotating it would duplicate the pair they already claim; naming it would
   * reintroduce a qualifier binding nothing uses.
   */
  @Test
  @DisplayName("the shared payment base binds nothing itself")
  void theSharedPaymentBaseBindsNothingItself() {
    assertNull(ReactivatePaymentHandler.class.getAnnotation(NeoExtension.class));
    assertNull(ReactivatePaymentHandler.class.getAnnotation(Named.class));
  }

  private static SFEntity entity(String specName, String entityName, String qualifier) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn(specName);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(entity.getName()).thenReturn(entityName);
    when(entity.getJavaQualifier()).thenReturn(qualifier);
    return entity;
  }

  /**
   * Deploy a mocked bean per handler class, so {@link NeoExtensionIndex} is built from the real
   * annotations while each bean resolves to the instance kept in the returned map.
   *
   * @return {@code bean class -> the instance its {@code getReference} answers}
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Map<Class<?>, NeoHandler> deploy(BeanManager bm,
      List<Class<? extends NeoHandler>> types) {
    Set<Bean<?>> beans = new LinkedHashSet<>();
    Map<Class<?>, NeoHandler> instances = new LinkedHashMap<>();

    for (Class<? extends NeoHandler> type : types) {
      NeoHandler instance = mock(type);
      instances.put(type, instance);

      Bean<NeoHandler> bean = mock(Bean.class);
      doReturn(type).when(bean).getBeanClass();
      doReturn(instance).when(bm).getReference(same(bean), eq(NeoHandler.class), any());
      beans.add(bean);
    }

    when(bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL)).thenReturn(beans);
    return instances;
  }
}
