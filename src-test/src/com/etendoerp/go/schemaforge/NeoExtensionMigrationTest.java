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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.handlers.PaymentInReactivateHandler;
import com.etendoerp.go.schemaforge.handlers.PaymentOutReactivateHandler;
import com.etendoerp.go.schemaforge.handlers.ReactivatePaymentHandler;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * The handlers migrated to {@link NeoExtension} by ETP-5588: the financial-account, sales-invoice,
 * purchase-invoice and period-control headers, and the payment Reactivate/Confirm/Remove handler
 * split into one binding per entity, later joined by the goods-shipment and sales-quotation
 * headers and, with ETP-5581, by the two report specs of the bank reconciliation
 * ({@code bank-reconciliation}, {@code bank-statements}). Their rows carry no {@code Java_Qualifier}
 * and the classes no {@code @Named}, so the annotation is the only binding. What can regress is the binding itself (a
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
 * @covers com.etendoerp.go.schemaforge.ReconciliationHandler
 * @covers com.etendoerp.go.schemaforge.BankStatementsHandler
 * @covers com.etendoerp.go.schemaforge.NeoRequestRouter
 * @covers com.etendoerp.go.schemaforge.util.NeoHandlerLookup
 * @covers com.etendoerp.go.schemaforge.util.NeoReportCallability
 * @covers com.etendoerp.go.schemaforge.util.NeoActionContract
 * @covers com.etendoerp.go.schemaforge.util.NeoAccessHelper
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
      new Binding(SalesQuotationHeaderHandler.class, "sales-quotation", "quotation"),
      new Binding(ReconciliationHandler.class, "bank-reconciliation", "bank-reconciliation"),
      new Binding(BankStatementsHandler.class, "bank-statements", "bank-statements") };

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

  // ── ETP-5581: the report pairs, under both bindings ──────────────────────

  /**
   * The two report specs of the bank reconciliation are reached through readers the entity-level
   * tests above do not exercise: the SPA's {@code /sws/neo/<spec>?action=} route, the declared
   * action catalog, the report callability helpers and the role gate. Each reader is asserted for
   * both pairs in two states: the row has no qualifier and only the annotation binds it (the state
   * ETP-5581 ships), and the row still has its former qualifier while no class claims the pair by
   * annotation (the fallback a legacy deployment relies on). The handler is a spy of the real
   * class, so what a reader returns can be compared by identity.
   */
  @Nested
  @DisplayName("ETP-5581 — the bank report pairs resolve on every reader, by annotation or by qualifier")
  class BankReportPairReaders {

    /** REST report route: ?action= reaches the handler with entityName = the spec name. */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.schemaforge.NeoExtensionMigrationTest#reportPairs")
    @DisplayName("REST report route: ?action= reaches the handler with entityName = the spec name")
    void restReportRouteDispatchesToTheHandler(ReportPair pair, BoundBy mode) throws Exception {
      try (ReportFixture f = new ReportFixture(pair, mode);
          MockedStatic<NeoRequestBodyParser> body = mockStatic(NeoRequestBodyParser.class);
          MockedStatic<NeoCsvExportService> csv = mockStatic(NeoCsvExportService.class)) {
        NeoResponse answer = NeoResponse.ok(new JSONObject().put("answeredBy", pair.spec()));
        doReturn(answer).when(f.handler).handle(any());
        NeoServlet servlet = f.servlet();
        when(servlet.extractQueryParams(any())).thenReturn(Map.of("action", pair.restAction()));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        new NeoRequestRouter(servlet).handleReportSpecRequest(f.spec,
            new NeoServlet.NeoPathInfo(pair.spec(), null, null), "GET", request, response);

        verify(servlet).writeResponse(response, answer);
        ArgumentCaptor<NeoContext> context = ArgumentCaptor.forClass(NeoContext.class);
        verify(f.handler).handle(context.capture());
        assertEquals(pair.spec(), context.getValue().getEntityName(),
            "the report route names the entity after the spec, which is why the annotation does");
        assertEquals(pair.restAction(), context.getValue().getQueryParams().get("action"));
        f.assertQualifierResolvers(true);
      }
    }

    /** NeoActionContract.resolve: the spec's declared actions, served by its entity. */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.schemaforge.NeoExtensionMigrationTest#reportPairs")
    @DisplayName("NeoActionContract.resolve: the spec's declared actions, served by its entity")
    void actionContractsResolve(ReportPair pair, BoundBy mode) {
      try (ReportFixture f = new ReportFixture(pair, mode)) {
        NeoActionContract.SpecActions actions = NeoActionContract.resolve(f.spec).orElseThrow();

        assertEquals(pair.spec(), actions.getEntityName());
        assertEquals(f.handler.actionContracts().keySet(), actions.getContracts().keySet());
        assertFalse(actions.getContracts().isEmpty());
        f.assertQualifierResolvers(false);
      }
    }

    /** NeoReportCallability: report entity, customization, handler and its name. */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.schemaforge.NeoExtensionMigrationTest#reportPairs")
    @DisplayName("NeoReportCallability: report entity, customization, handler and its name")
    void reportCallabilityResolvesTheHandler(ReportPair pair, BoundBy mode) {
      try (ReportFixture f = new ReportFixture(pair, mode)) {
        assertSame(f.entity, NeoReportCallability.resolveReportEntity(f.spec));
        assertTrue(NeoHandlerLookup.hasCustomization(f.entity));
        assertSame(f.handler, NeoReportCallability.resolveReportHandler(f.spec));
        assertEquals(mode == BoundBy.ANNOTATION_ONLY ? pair.type().getSimpleName()
            : pair.formerQualifier(), NeoReportCallability.reportHandlerName(f.spec),
            "the qualifier while the row has one, the class name otherwise");
        assertFalse(NeoReportCallability.isReportCallable(f.spec),
            "an action handler declares no report contract");
        f.assertQualifierResolvers(false);
      }
    }

    /** Role gate: hasReportSpecAccess asks the resolved handler for its rule. */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.schemaforge.NeoExtensionMigrationTest#reportPairs")
    @DisplayName("role gate: hasReportSpecAccess asks the resolved handler for its rule")
    void roleGateAsksTheResolvedHandler(ReportPair pair, BoundBy mode) {
      try (ReportFixture f = new ReportFixture(pair, mode)) {
        doReturn(false).when(f.handler).isAccessibleForCurrentRole();
        assertFalse(NeoAccessHelper.hasReportSpecAccess(f.spec, "GET"),
            "a handler that refuses the role closes the spec");

        doReturn(true).when(f.handler).isAccessibleForCurrentRole();
        assertTrue(NeoAccessHelper.hasReportSpecAccess(f.spec, "GET"));
        verify(f.handler, Mockito.times(2)).isAccessibleForCurrentRole();
      }
    }
  }

  /** How a report pair is bound in one test. */
  enum BoundBy {
    /** The row has no qualifier; the class's {@code @NeoExtension} is the only binding. */
    ANNOTATION_ONLY,
    /** The row keeps its former qualifier and no deployed class claims the pair by annotation. */
    QUALIFIER_ONLY
  }

  /** One migrated report pair, the qualifier its row carried and one action per channel. */
  record ReportPair(Class<? extends NeoHandler> type, String spec, String formerQualifier,
      String restAction) {
    /**
     * The spec name, so the parameterized case names read well.
     *
     * @return the spec
     */
    @Override
    public String toString() {
      return spec;
    }
  }

  static final ReportPair BANK_RECONCILIATION = new ReportPair(ReconciliationHandler.class,
      "bank-reconciliation", "bankReconciliation", "pendingLines");
  static final ReportPair BANK_STATEMENTS = new ReportPair(BankStatementsHandler.class,
      "bank-statements", "bank-statements", "lines");

  /**
   * The two report specs migrated to {@code @NeoExtension} by ETP-5581, each bound once by its
   * annotation and once by its former qualifier only.
   *
   * @return one {@code (pair, binding)} argument set per case
   */
  static Stream<Arguments> reportPairs() {
    return Stream.of(BANK_RECONCILIATION, BANK_STATEMENTS).flatMap(pair -> Stream.of(
        Arguments.of(pair, BoundBy.ANNOTATION_ONLY), Arguments.of(pair, BoundBy.QUALIFIER_ONLY)));
  }

  /**
   * The report spec, its one entity row and a spy of the real handler, deployed per {@link BoundBy}.
   * The index is built for real from a mocked bean manager; the qualifier resolvers ({@code
   * NeoHandlerLookup.byQualifier*} for MCP and discovery, {@code NeoServletSupport.lookupHandler}
   * for REST) are stubbed to answer the spy for the former qualifier only.
   */
  static final class ReportFixture implements AutoCloseable {
    final ReportPair pair;
    final BoundBy mode;
    final NeoHandler handler;
    final SFSpec spec;
    final SFEntity entity;
    private static final String MCP_RESOLVER = "NeoHandlerLookup.byQualifier";
    private static final String REST_RESOLVER = "NeoServletSupport.lookupHandler";
    /** Every call a qualifier resolver received, as {@code resolver:qualifier}. */
    private final List<String> asked = new java.util.ArrayList<>();
    private final MockedStatic<WeldUtils> weld;
    private final MockedStatic<NeoHandlerLookup> lookup;
    private final MockedStatic<NeoServletSupport> support;
    private final MockedStatic<OBDal> obDal;
    private final MockedStatic<OBContext> obContext;

    /**
     * Builds the fixture: a spy of the real handler of the pair, its spec and entity rows, and the
     * static mocks that resolve it by annotation or by its former qualifier only.
     *
     * @param pair the report spec under test
     * @param mode how its handler is bound
     */
    @SuppressWarnings("unchecked")
    ReportFixture(ReportPair pair, BoundBy mode) {
      this.pair = pair;
      this.mode = mode;
      try {
        handler = spy(pair.type().getDeclaredConstructor().newInstance());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
      spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-" + pair.spec());
      when(spec.getName()).thenReturn(pair.spec());
      when(spec.getSpecType()).thenReturn("R");
      entity = mock(SFEntity.class);
      when(entity.getETGOSFSpec()).thenReturn(spec);
      when(entity.getName()).thenReturn(pair.spec());
      when(entity.getJavaQualifier())
          .thenReturn(mode == BoundBy.QUALIFIER_ONLY ? pair.formerQualifier() : null);

      BeanManager bm = mock(BeanManager.class);
      Set<Bean<?>> beans = new LinkedHashSet<>();
      if (mode == BoundBy.ANNOTATION_ONLY) {
        Bean<NeoHandler> bean = mock(Bean.class);
        doReturn(pair.type()).when(bean).getBeanClass();
        doReturn(handler).when(bm).getReference(same(bean), eq(NeoHandler.class), any());
        beans.add(bean);
      }
      when(bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL)).thenReturn(beans);
      weld = mockStatic(WeldUtils.class);
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);

      lookup = mockStatic(NeoHandlerLookup.class, Mockito.CALLS_REAL_METHODS);
      lookup.when(() -> NeoHandlerLookup.byQualifier(any()))
          .thenAnswer(inv -> byQualifier(MCP_RESOLVER, inv));
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(any()))
          .thenAnswer(inv -> byQualifier(MCP_RESOLVER, inv));
      support = mockStatic(NeoServletSupport.class, Mockito.CALLS_REAL_METHODS);
      support.when(() -> NeoServletSupport.lookupHandler(any()))
          .thenAnswer(inv -> byQualifier(REST_RESOLVER, inv));
      // Stubbing a CALLS_REAL_METHODS static runs the real method once (byQualifierQuietly calls
      // byQualifier(null)); only what the readers ask afterwards counts.
      asked.clear();

      OBDal dal = mock(OBDal.class);
      OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(List.of(entity));
      obDal = mockStatic(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      OBContext context = mock(OBContext.class);
      when(context.getRole()).thenReturn(mock(Role.class));
      obContext = mockStatic(OBContext.class);
      obContext.when(OBContext::getOBContext).thenReturn(context);
    }

    /**
     * Answers a qualifier lookup: the spied handler for the pair's former qualifier when the
     * fixture binds by qualifier only, else nothing. Every lookup is recorded with its resolver.
     *
     * @param resolver   the name of the resolver that asked
     * @param invocation the lookup call
     * @return the handler, or {@code null}
     */
    private NeoHandler byQualifier(String resolver, InvocationOnMock invocation) {
      asked.add(resolver + ":" + invocation.getArgument(0));
      return mode == BoundBy.QUALIFIER_ONLY
          && pair.formerQualifier().equals(invocation.getArgument(0)) ? handler : null;
    }

    /** A servlet mock whose report dispatch runs the real hook pipeline. */
    NeoServlet servlet() {
      NeoServlet servlet = mock(NeoServlet.class);
      NeoAuthenticator authenticator = mock(NeoAuthenticator.class);
      when(authenticator.hasReportSpecAccess(spec, "GET")).thenReturn(true);
      setField(servlet, "authenticator", authenticator);
      setField(servlet, "crudHandler", mock(NeoCrudHandler.class));
      when(servlet.handleWithHooks(any(), any(), any(), any())).thenCallRealMethod();
      return servlet;
    }

    /**
     * Annotation-only: no qualifier resolver is ever asked. Qualifier-only: the one each channel
     * uses is asked for the former qualifier.
     *
     * @param rest whether the reader is the REST channel (its fallback is lookupHandler)
     */
    void assertQualifierResolvers(boolean rest) {
      if (mode == BoundBy.ANNOTATION_ONLY) {
        assertTrue(asked.isEmpty(), "no qualifier to fall back on, yet asked: " + asked);
        return;
      }
      String expected = (rest ? REST_RESOLVER : MCP_RESOLVER) + ":" + pair.formerQualifier();
      assertTrue(asked.contains(expected), "expected " + expected + ", asked: " + asked);
    }

    /**
     * Sets a private field of {@link NeoServlet} on {@code target} by reflection.
     *
     * @param target the servlet
     * @param name   the field name
     * @param value  the value to set
     */
    private static void setField(Object target, String name, Object value) {
      try {
        java.lang.reflect.Field field = NeoServlet.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    /** Closes every static mock the fixture opened. */
    @Override
    public void close() {
      obContext.close();
      obDal.close();
      support.close();
      lookup.close();
      weld.close();
    }
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
