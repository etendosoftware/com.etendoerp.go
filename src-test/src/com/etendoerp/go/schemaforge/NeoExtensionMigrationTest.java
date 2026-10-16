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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

import com.etendoerp.go.schemaforge.handlers.PaymentInReactivateHandler;
import com.etendoerp.go.schemaforge.handlers.PaymentOutReactivateHandler;
import com.etendoerp.go.schemaforge.handlers.ReactivatePaymentHandler;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * The handlers migrated to {@link NeoExtension} by ETP-5588: the financial-account, sales-invoice,
 * purchase-invoice and period-control headers, and the payment Reactivate/Confirm/Remove handler
 * split into one binding per entity. What can regress is the binding itself (a wrong
 * {@code (spec, entity)} pair, a lost {@code @Named} the direct qualifier readers rely on, or a
 * stray {@code @Named} on a payment split that would make {@code payment-reactivate} ambiguous) and
 * the resolution path — the annotation must win on REST single, REST batch and MCP, ahead of the
 * qualifier fallback.
 *
 * <p>{@link NeoExtensionIndex} is exercised for real: only {@link WeldUtils}'s bean manager is
 * mocked, so the index is built from the handlers' own annotations exactly as in production. The
 * two qualifier resolvers are stubbed to a sentinel only in the dispatcher case, so that a
 * resolution pointing at the qualifier instead of the annotation fails visibly rather than passing
 * on a coincidence.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountHandler
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PeriodOpenCloseHandler
 * @covers com.etendoerp.go.schemaforge.handlers.PaymentInReactivateHandler
 * @covers com.etendoerp.go.schemaforge.handlers.PaymentOutReactivateHandler
 */
@DisplayName("ETP-5588 — migrated handlers resolve through @NeoExtension on every channel")
class NeoExtensionMigrationTest {

  /**
   * One {@code (spec, entity)} binding: the class that carries it, the pair, the row's
   * {@code Java_Qualifier} (kept as the fallback), and whether the class must also carry a
   * {@code @Named} equal to it. {@code false} for the payment split, whose two subclasses
   * deliberately carry no {@code @Named} (the base owns {@code payment-reactivate}).
   */
  private record Binding(Class<? extends NeoHandler> type, String spec, String entity,
      String qualifier, boolean carriesNamed) {
  }

  private static final Binding FINANCIAL_ACCOUNT = new Binding(FinancialAccountHandler.class,
      "financial-account", "account", "financialAccountHeaderHandler", true);
  private static final Binding SALES_INVOICE = new Binding(SalesInvoiceHeaderHandler.class,
      "sales-invoice", "header", "salesInvoiceHeaderHandler", true);
  private static final Binding PURCHASE_INVOICE = new Binding(PurchaseInvoiceHeaderHandler.class,
      "purchase-invoice", "header", "purchaseInvoiceHeaderHandler", true);
  private static final Binding PERIOD_CONTROL = new Binding(PeriodOpenCloseHandler.class,
      "open-close-period-control", "periodControl", "period-openclose", true);
  private static final Binding PAYMENT_IN = new Binding(PaymentInReactivateHandler.class,
      "payment-in", "finPayment", "payment-reactivate", false);
  private static final Binding PAYMENT_OUT = new Binding(PaymentOutReactivateHandler.class,
      "payment-out", "header", "payment-reactivate", false);

  private static final Binding[] MIGRATED = { FINANCIAL_ACCOUNT, SALES_INVOICE, PURCHASE_INVOICE,
      PERIOD_CONTROL, PAYMENT_IN, PAYMENT_OUT };

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
  void bindingIsTheAnnotationAndKeepsTheQualifierItNeeds(Binding binding) {
    NeoExtension annotation = binding.type().getAnnotation(NeoExtension.class);

    assertNotNull(annotation, binding.type().getSimpleName() + " must be bound by @NeoExtension");
    assertEquals(binding.spec(), annotation.spec(), "spec must match the ETGO_SF_SPEC row");
    assertEquals(binding.entity(), annotation.entity(), "entity must match the ETGO_SF_ENTITY row");

    Named named = binding.type().getAnnotation(Named.class);
    if (binding.carriesNamed()) {
      assertNotNull(named, binding.type().getSimpleName()
          + " must keep the @Named the direct qualifier readers resolve");
      assertEquals(binding.qualifier(), named.value(),
          "the @Named and the row's Java_Qualifier must be the same name, or every resolution logs "
              + "a disagreement");
    } else {
      assertNull(named, binding.type().getSimpleName()
          + " must not carry @Named: a second bean named " + binding.qualifier()
          + " would make that name ambiguous");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bindings")
  void indexResolvesEveryMigratedPair(Binding binding) {
    BeanManager bm = mock(BeanManager.class);
    Map<Class<?>, NeoHandler> deployed = deploy(bm, MIGRATED_TYPES);

    try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);

      assertSame(deployed.get(binding.type()),
          NeoExtensionIndex.resolve(binding.spec(), binding.entity(), binding.qualifier()),
          binding.spec() + "/" + binding.entity() + " must resolve by annotation");

      assertNull(NeoExtensionIndex.resolve("no-such-spec", "no-such-entity", null),
          "a pair nothing claims must miss, so the caller falls back to the qualifier");
    }
  }

  /**
   * The acceptance criterion in one assertion: the annotation wins on REST single, REST batch and
   * MCP. {@link NeoExtensionDispatcher#resolveOnly} resolves without invoking, so no handler's
   * {@code handle} runs and the test stays a pure routing check. Both qualifier resolvers answer a
   * sentinel, so a result that is not the annotated instance means the annotation branch was
   * skipped — the divergence that produces no error anywhere and is the whole reason the dispatcher
   * exists.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("bindings")
  void dispatcherResolvesTheAnnotationOnEveryChannel(Binding binding) {
    BeanManager bm = mock(BeanManager.class);
    Map<Class<?>, NeoHandler> deployed = deploy(bm, MIGRATED_TYPES);
    NeoHandler viaQualifier = mock(NeoHandler.class);

    try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class);
        MockedStatic<NeoServletSupport> rest = mockStatic(NeoServletSupport.class);
        MockedStatic<NeoHandlerLookup> mcp = mockStatic(NeoHandlerLookup.class)) {
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);
      rest.when(() -> NeoServletSupport.lookupHandler(binding.qualifier())).thenReturn(viaQualifier);
      mcp.when(() -> NeoHandlerLookup.byQualifier(binding.qualifier())).thenReturn(viaQualifier);

      for (NeoExtensionChannel channel : NeoExtensionChannel.values()) {
        NeoHandler resolved = NeoExtensionDispatcher.resolveOnly(request(binding, channel));

        assertSame(deployed.get(binding.type()), resolved, binding.spec() + "/" + binding.entity()
            + " must resolve by annotation on " + channel + ", not through the qualifier fallback");
      }
    }
  }

  /** A request for the entity, on one channel, with the surface fixed so no context is needed. */
  private static NeoExtensionRequest request(Binding binding, NeoExtensionChannel channel) {
    return NeoExtensionRequest.builder()
        .qualifier(binding.qualifier())
        .specName(binding.spec())
        .entityName(binding.entity())
        .surface(NeoExtensionSurface.READ)
        .channel(channel)
        .build();
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

  /**
   * The payment base stays unannotated, exactly as in production: it holds the behaviour and the
   * {@code payment-reactivate} qualifier the direct readers resolve, and never becomes an index
   * entry itself. Annotating it would duplicate the pair the two subclasses already claim.
   */
  @Test
  @DisplayName("the shared payment base is not itself an index entry")
  void theSharedPaymentBaseIsNotItselfAnIndexEntry() {
    assertNull(ReactivatePaymentHandler.class.getAnnotation(NeoExtension.class),
        "the base holds the behaviour and the qualifier; the binding lives in its subclasses");
  }
}
