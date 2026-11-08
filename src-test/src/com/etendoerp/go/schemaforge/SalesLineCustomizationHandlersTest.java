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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.ReversedInvoice;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;

/**
 * The ETP-5528 / ETP-5710 line customizations — {@link SalesOrderLineHandler},
 * {@link SalesQuotationLineHandler}, {@link SalesInvoiceLineHandler} and
 * {@link PurchaseInvoiceLineHandler} — share one shape: bound by
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
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseOrderLineHandler
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceLineHandler
 * @covers com.etendoerp.go.schemaforge.SalesRectificativeLineLinkSupport
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceShipmentLineResolver
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
            "lines", invoiceLine, "grossAmount"),
        Arguments.of(PurchaseInvoiceLineHandler.class, InvoiceLineHandler.class,
            "purchase-invoice", "lines", invoiceLine, "grossAmount"));
  }

  @BeforeEach
  void resetIndex() {
    NeoExtensionIndex.invalidate();
  }

  /**
   * Drops the index built from the mocked bean manager, and clears the admin-mode stack this class
   * leaks without a DAL.
   *
   * <p>ETP-6007: {@code handleRecalculatesTheAmountsOnUpdate} is the only case here that builds a
   * PATCH context carrying a record id, so it is the only one that reaches
   * {@code CompletedInvoiceWriteFence#read}. That method calls {@code OBContext.setAdminMode(true)}
   * OUTSIDE its {@code try}, and {@code setAdminMode} pushes onto its thread-local stack before
   * {@code setAdminContextLocally()} throws for the missing session factory — so the {@code try} is
   * never entered and its {@code finally} never restores the frame. {@code checkLine} then swallows
   * the exception ("Could not evaluate the completed-invoice fence for line …"), the case passes,
   * and the orphan frame is charged to the next {@code OBBaseTest} class in the same Gradle worker
   * running as a non-admin user — {@code StorageDetailCostColumnsIntegrationTest} in the merge-block
   * cut. Same symptom and same remedy as ETP-5953 ({@code NeoDefaultsServiceTest},
   * {@code NeoProcessServiceTest}).
   *
   * <p>Unlike those two, the production code here is NOT balanced. The root fix is to make
   * {@code read} enter through {@code NeoAdminMode#enter}, which already restores the frame when
   * {@code setAdminMode} throws; that is product code, so it goes in its own ticket against
   * ETP-5692. It is a test-environment leak only: in production an {@code OBContext} always exists,
   * so {@code setAdminContextLocally()} is never reached and the pair stays balanced.
   */
  @AfterEach
  void dropIndexAndAdminModeLeakedWithoutDal() {
    NeoExtensionIndex.invalidate();
    OBContext.clearAdminModeStack();
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
    assertTrue(parent.isAssignableFrom(handler),
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
        Arguments.of("purchase-invoice", "lines", "invoiceLineHandler",
            PurchaseInvoiceLineHandler.class));
  }

  /**
   * The index resolves each customization for its own pair and nothing for purchase-order lines,
   * which fall back to the row's qualifier — the plain parent — as before (ETP-5727 owns them). The deployment also holds
   * the unannotated parents, as in production.
   */
  @ParameterizedTest(name = "{0}/{1}")
  @MethodSource("resolutions")
  void indexResolvesEachCustomizedPairAndNothingElse(String spec, String entity, String qualifier,
      Class<?> expected) {
    BeanManager bm = mock(BeanManager.class);
    Set<Bean<?>> beans = new LinkedHashSet<>();
    beans.add(deployedBean(bm, new SalesOrderLineHandler()));
    beans.add(deployedBean(bm, new SalesQuotationLineHandler()));
    beans.add(deployedBean(bm, new SalesInvoiceLineHandler()));
    beans.add(deployedBean(bm, new PurchaseInvoiceLineHandler()));
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

  /**
   * The invoice-line customizations (sales and purchase) also recalculate on a PATCH: 10 x 24.50
   * patched to quantity 20 → net 490. The plain parent leaves the body alone.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invoiceCustomizations")
  void handleRecalculatesTheAmountsOnUpdate(Class<? extends NeoHandler> handler, String spec)
      throws Exception {
    JSONObject patch = new JSONObject("{\"invoicedQuantity\":20}");
    JSONObject plainPatch = new JSONObject("{\"invoicedQuantity\":20}");
    NeoContext ctx = NeoContext.builder().specName(spec).entityName("lines").httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD).recordId(LineAmountTestSupport.STORED_LINE_ID)
        .requestBody(patch).build();
    NeoContext plainCtx = NeoContext.builder().specName(spec).entityName("lines")
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .recordId(LineAmountTestSupport.STORED_LINE_ID).requestBody(plainPatch).build();

    NeoResponse response;
    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0,
        LineAmountTestSupport.storedInvoiceLine("10", "24.5", "25", "2", "0", TAX_ID))) {
      response = handler.getDeclaredConstructor().newInstance().handle(ctx);
      new InvoiceLineHandler().handle(plainCtx);
    }

    assertNull(response);
    assertAmount(handler.getSimpleName(), "490", patch, "lineNetAmount");
    assertAmount("plain parent", null, plainPatch, "lineNetAmount");
  }

  static Stream<Arguments> invoiceCustomizations() {
    return Stream.of(Arguments.of(SalesInvoiceLineHandler.class, "sales-invoice"),
        Arguments.of(PurchaseInvoiceLineHandler.class, "purchase-invoice"));
  }

  /**
   * Over MCP, {@link PurchaseOrderLineHandler} runs the parent's pre-hook and then every order line
   * rule (ETP-5881 pricing from the price list, ETP-5852 discount and amounts, as on sales lines);
   * it lets default CRUD continue. The parent's own response cannot be non-null (see the class
   * note), so only the rules leg is observable.
   */
  @Test
  void purchaseOrderLineHandlerAppliesTheLineRulesOverMcp() throws Exception {
    NeoContext ctx = NeoContext.builder().specName("purchase-order").entityName("lines")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).mcpOrigin(true)
        .requestBody(new JSONObject("{\"product\":\"P1\"}")).build();

    NeoResponse response;
    try (MockedStatic<OrderLineDiscountSupport> pricing = mockStatic(
        OrderLineDiscountSupport.class)) {
      response = new PurchaseOrderLineHandler().handle(ctx);

      pricing.verify(() -> OrderLineDiscountSupport.applyLineRules(same(ctx)), times(1));
    }

    assertNull(response, "the pre-hook must let default CRUD continue");
  }

  /**
   * On REST (the app's form) a purchase line keeps its own rules only: the price-list pricing of a
   * created line, never the sales discount and amount rules — the form derives those itself.
   */
  @Test
  void purchaseOrderLineHandlerKeepsItsOwnRulesOnRest() throws Exception {
    NeoContext ctx = createContext("purchase-order", "lines", new JSONObject("{\"product\":\"P1\"}"));

    NeoResponse response;
    try (MockedStatic<OrderLineDiscountSupport> pricing = mockStatic(
        OrderLineDiscountSupport.class)) {
      response = new PurchaseOrderLineHandler().handle(ctx);

      pricing.verify(() -> OrderLineDiscountSupport.setStandardPriceOnCreate(same(ctx)), times(1));
      pricing.verify(() -> OrderLineDiscountSupport.applyLineRules(any()), never());
    }

    assertNull(response, "the pre-hook must let default CRUD continue");
  }

  /**
   * ETP-6012: on an update the purchase line carries the stored price and tax into a sparse patch,
   * on the same context the pre-hook received.
   */
  @Test
  void purchaseOrderLineHandlerCompletesTheAmountInputsOfAnUpdate() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .specName("purchase-order")
        .entityName("lines")
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("LINE-1")
        .requestBody(new JSONObject("{\"orderedQuantity\":3}"))
        .build();

    NeoResponse response;
    try (MockedStatic<OrderLineDiscountSupport> pricing = mockStatic(
        OrderLineDiscountSupport.class)) {
      response = new PurchaseOrderLineHandler().handle(ctx);

      pricing.verify(() -> OrderLineDiscountSupport.completeAmountInputsOnUpdate(same(ctx)),
          times(1));
    }

    assertNull(response, "the pre-hook must let default CRUD continue");
  }

  @Test
  void purchaseOrderLineHandlerIsBoundByAnnotationOnly() {
    NeoExtension binding = PurchaseOrderLineHandler.class.getAnnotation(NeoExtension.class);

    assertNotNull(binding);
    assertEquals("purchase-order", binding.spec());
    assertEquals("lines", binding.entity());
    assertNull(PurchaseOrderLineHandler.class.getAnnotation(Named.class));
    assertTrue(OrderLineHandler.class.isAssignableFrom(PurchaseOrderLineHandler.class));
  }

  private static final String RECTIFICATIVE_ID = "INV-R";
  private static final String ORIGIN_ID = "INV-O";
  private static final String SOURCE_LINE_ID = "SRC-L";

  /**
   * Repro: a line imported into a sales rectificative from a source invoice line links the
   * rectificative to that line's invoice ({@code C_Invoice_Reverse}) in the pre-hook, flushed
   * there so a trigger refusal stops the create. The link used to depend on a header PATCH the
   * SPA sent after the import, which always failed with 409 stale_record.
   */
  @Test
  void salesInvoiceLineCreateLinksTheRectificativeToTheSourceInvoice() throws Exception {
    LinkFixture fx = new LinkFixture(Collections.emptyList());

    NeoResponse response = fx.run();

    assertNull(response, "the pre-hook must let default CRUD continue");
    verify(fx.link).setInvoice(fx.rectificative);
    verify(fx.link).setReversedInvoice(fx.origin);
    verify(fx.dal).save(fx.link);
    verify(fx.dal).flush();
  }

  /** A pair that is already linked (a second import from the same invoice) is not linked again. */
  @Test
  void salesInvoiceLineCreateDoesNotDuplicateAnExistingLink() throws Exception {
    LinkFixture fx = new LinkFixture(List.of(mock(ReversedInvoice.class)));

    NeoResponse response = fx.run();

    assertNull(response);
    verify(fx.dal, never()).save(fx.link);
    verify(fx.dal, never()).flush();
  }

  /**
   * A line created from a return line ({@code goodsShipmentLine} whose {@code Canceled_Inoutline_ID}
   * is set) links the rectificative to every completed invoice of its own business partner that
   * billed the original shipment line — one link per invoice, flushed once. The query is filtered
   * by the original line, the rectificative's partner and the completed status.
   */
  @ParameterizedTest(name = "{0} billing invoice(s)")
  @MethodSource("billingInvoiceCounts")
  void salesInvoiceLineCreateFromAReturnLineLinksEveryInvoiceThatBilledTheOriginal(int count)
      throws Exception {
    LinkFixture fx = new LinkFixture(true, Collections.emptyList());
    Invoice[] billing = new Invoice[count];
    for (int i = 0; i < count; i++) {
      billing[i] = fx.invoice("INV-B" + i);
    }
    OBQuery<InvoiceLine> query = fx.returnLine(true, billing);

    NeoResponse response = fx.run(fx.returnLineBody());

    assertNull(response, "the pre-hook must let default CRUD continue");
    for (Invoice origin : billing) {
      verify(fx.link).setReversedInvoice(origin);
    }
    verify(fx.dal, times(count)).save(fx.link);
    verify(fx.dal).flush();
    verify(query).setNamedParameter("lineId", ORIGINAL_SHIPMENT_LINE_ID);
    verify(query).setNamedParameter("bpId", BP_ID);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(fx.dal).createQuery(eq(InvoiceLine.class), hql.capture());
    assertTrue(hql.getValue().contains("documentStatus = 'CO'"),
        "only completed invoices may be linked: " + hql.getValue());
  }

  static Stream<Arguments> billingInvoiceCounts() {
    return Stream.of(Arguments.of(1), Arguments.of(2));
  }

  /**
   * The return-line route links nothing, and raises nothing, when there is nothing to derive: the
   * original line was never invoiced, the shipment line is not a return line, or the rectificative
   * has no business partner yet (then the billing query is not even run).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("returnLinesWithNothingToLink")
  void salesInvoiceLineCreateFromAReturnLineLinksNothingWhenNothingToDerive(String scenario,
      boolean isReturnLine, boolean hasBusinessPartner, boolean expectQuery) throws Exception {
    LinkFixture fx = new LinkFixture(true, Collections.emptyList());
    fx.returnLine(isReturnLine);
    if (!hasBusinessPartner) {
      when(fx.rectificative.getBusinessPartner()).thenReturn(null);
    }

    NeoResponse response = fx.run(fx.returnLineBody());

    assertNull(response, scenario + ": the create must continue");
    verify(fx.dal, never()).save(any());
    verify(fx.dal, never()).flush();
    verify(fx.dal, times(expectQuery ? 1 : 0)).createQuery(eq(InvoiceLine.class), anyString());
  }

  static Stream<Arguments> returnLinesWithNothingToLink() {
    return Stream.of(
        Arguments.of("original line never invoiced", true, true, true),
        Arguments.of("not a return line", false, true, false),
        Arguments.of("rectificative without business partner", true, false, false));
  }

  /** A line created into an invoice that is not a rectificative links nothing, whatever it carries. */
  @Test
  void salesInvoiceLineCreateIntoAPlainInvoiceLinksNothing() throws Exception {
    LinkFixture fx = new LinkFixture(false, Collections.emptyList());
    JSONObject body = fx.sourceLineBody(PARENT_ID_KEY).put("salesOrderLine", "OL-SRC");

    NeoResponse response = fx.run(body);

    assertNull(response);
    verify(fx.dal, never()).createCriteria(ReversedInvoice.class);
    verify(fx.dal, never()).save(any());
    // ETP-6002: the order line is dropped only on a rectificative; a plain invoice keeps it.
    assertEquals("OL-SRC", body.optString("salesOrderLine"));
  }

  /** A {@code sourceInvoiceLineId} that names no invoice line is refused before the create. */
  @Test
  void salesInvoiceLineCreateWithAnUnknownSourceLineIsRefusedWith422() throws Exception {
    LinkFixture fx = new LinkFixture(true, Collections.emptyList());
    when(fx.dal.get(InvoiceLine.class, SOURCE_LINE_ID)).thenReturn(null);

    NeoResponse response = fx.run(fx.sourceLineBody(PARENT_ID_KEY));

    assertNotNull(response, "an unknown source line must stop the create");
    assertEquals(422, response.getHttpStatus());
    assertEquals("sourceInvoiceLineNotFound",
        response.getBody().getJSONObject("error").getString("code"));
    verify(fx.dal, never()).save(any());
  }

  /** Without {@code parentId}, the rectificative is read from the {@code invoice} key. */
  @Test
  void salesInvoiceLineCreateResolvesTheRectificativeFromTheInvoiceKey() throws Exception {
    LinkFixture fx = new LinkFixture(true, Collections.emptyList());

    NeoResponse response = fx.run(fx.sourceLineBody("invoice"));

    assertNull(response);
    verify(fx.link).setInvoice(fx.rectificative);
    verify(fx.link).setReversedInvoice(fx.origin);
    verify(fx.dal).save(fx.link);
  }

  /**
   * Repro (ETP-6002): a line copied from a source invoice line into a rectificative loses the
   * source line's order line, so C_INVOICE_POST neither lowers the order's invoiced quantity nor
   * matches the rectificative to the order's shipment — the rule ETP-5729 applies to the return
   * path. Any client (REST, batch) that sends it is overridden.
   */
  @Test
  void salesInvoiceLineCopiedIntoARectificativeDropsTheOrderLine() throws Exception {
    LinkFixture fx = new LinkFixture(true, Collections.emptyList());
    JSONObject body = fx.sourceLineBody(PARENT_ID_KEY).put("salesOrderLine", "OL-SRC");

    NeoResponse response = fx.run(body);

    assertNull(response);
    assertFalse(body.has("salesOrderLine"), "the source line's order line must not be copied");
  }

  /**
   * {@code sourceInvoiceLineId} is declared as a create directive, so the MCP write verbs accept it
   * instead of refusing it as an unknown field; the parent handler does not declare it.
   */
  @Test
  void salesInvoiceLineHandlerDeclaresSourceInvoiceLineIdAsACreateDirective() {
    assertTrue(new SalesInvoiceLineHandler().createDirectives().containsKey("sourceInvoiceLineId"));
    assertFalse(new InvoiceLineHandler().createDirectives().containsKey("sourceInvoiceLineId"));
  }

  private static final String PARENT_ID_KEY = "parentId";
  private static final String RETURN_LINE_ID = "RET-L";
  private static final String ORIGINAL_SHIPMENT_LINE_ID = "ORIG-L";
  private static final String BP_ID = "BP-1";

  /**
   * A sales rectificative (or, with {@code rectificativeDocType = false}, a plain ARI invoice) of
   * business partner {@link #BP_ID}, the invoice its source line belongs to, and the existing
   * {@code C_Invoice_Reverse} rows for any pair. Every mock is built and stubbed before it is handed
   * to another stub (no nested stubbing).
   */
  private static final class LinkFixture {
    final OBDal dal = mock(OBDal.class);
    final Invoice rectificative = mock(Invoice.class);
    final Invoice origin = mock(Invoice.class);
    final ReversedInvoice link = mock(ReversedInvoice.class);
    private final OBProvider provider = mock(OBProvider.class);

    LinkFixture(List<ReversedInvoice> existingLinks) {
      this(true, existingLinks);
    }

    @SuppressWarnings("unchecked")
    LinkFixture(boolean rectificativeDocType, List<ReversedInvoice> existingLinks) {
      DocumentType docType = mock(DocumentType.class);
      when(docType.isEtsgIsRectificative()).thenReturn(rectificativeDocType);
      when(docType.getDocumentCategory()).thenReturn("ARI");
      BusinessPartner bp = mock(BusinessPartner.class);
      when(bp.getId()).thenReturn(BP_ID);
      when(rectificative.getId()).thenReturn(RECTIFICATIVE_ID);
      when(rectificative.getTransactionDocument()).thenReturn(docType);
      when(rectificative.getBusinessPartner()).thenReturn(bp);
      when(origin.getId()).thenReturn(ORIGIN_ID);
      InvoiceLine sourceLine = mock(InvoiceLine.class);
      when(sourceLine.getInvoice()).thenReturn(origin);
      when(dal.get(Invoice.class, RECTIFICATIVE_ID)).thenReturn(rectificative);
      when(dal.get(InvoiceLine.class, SOURCE_LINE_ID)).thenReturn(sourceLine);
      OBCriteria<ReversedInvoice> criteria = mock(OBCriteria.class);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(existingLinks);
      when(dal.createCriteria(ReversedInvoice.class)).thenReturn(criteria);
      when(provider.get(ReversedInvoice.class)).thenReturn(link);
    }

    /** An invoice mock answering to {@code id}. */
    Invoice invoice(String id) {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn(id);
      return invoice;
    }

    /**
     * Stubs shipment line {@link #RETURN_LINE_ID}: a return line cancelling
     * {@link #ORIGINAL_SHIPMENT_LINE_ID} when {@code isReturnLine}, a plain shipment line otherwise.
     * The billing query answers one invoice line per {@code billing} invoice.
     */
    @SuppressWarnings("unchecked")
    OBQuery<InvoiceLine> returnLine(boolean isReturnLine, Invoice... billing) {
      ShipmentInOutLine original = mock(ShipmentInOutLine.class);
      when(original.getId()).thenReturn(ORIGINAL_SHIPMENT_LINE_ID);
      ShipmentInOutLine returnLine = mock(ShipmentInOutLine.class);
      when(returnLine.getCanceledInoutLine()).thenReturn(isReturnLine ? original : null);
      when(dal.get(ShipmentInOutLine.class, RETURN_LINE_ID)).thenReturn(returnLine);
      List<InvoiceLine> billed = new ArrayList<>();
      for (Invoice invoice : billing) {
        InvoiceLine line = mock(InvoiceLine.class);
        when(line.getInvoice()).thenReturn(invoice);
        billed.add(line);
      }
      OBQuery<InvoiceLine> query = mock(OBQuery.class);
      when(query.setNamedParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(billed);
      when(dal.createQuery(eq(InvoiceLine.class), anyString())).thenReturn(query);
      return query;
    }

    JSONObject sourceLineBody(String parentKey) throws Exception {
      return new JSONObject().put(parentKey, RECTIFICATIVE_ID).put("product", "P1")
          .put("unitPrice", 10).put("sourceInvoiceLineId", SOURCE_LINE_ID);
    }

    JSONObject returnLineBody() throws Exception {
      return new JSONObject().put(PARENT_ID_KEY, RECTIFICATIVE_ID).put("product", "P1")
          .put("unitPrice", 10).put("goodsShipmentLine", RETURN_LINE_ID);
    }

    NeoResponse run() throws Exception {
      return run(sourceLineBody(PARENT_ID_KEY));
    }

    NeoResponse run(JSONObject body) throws Exception {
      RectificativeSupport.setColumnPresentForTests(true);
      try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
        obProvider.when(OBProvider::getInstance).thenReturn(provider);
        return new SalesInvoiceLineHandler()
            .handle(createContext("sales-invoice", "lines", body));
      } finally {
        RectificativeSupport.setColumnPresentForTests(null);
      }
    }
  }

  private static final String PLAIN_INVOICE_ID = "INV-F";
  private static final String ORDER_LINE_ID = "OL-1";

  /**
   * Repro (ETP-6002 IMP-1): a sales invoice line created from an order line is bound to the
   * shipment line the shared rule allocates first (the oldest one still pending), so the invoice
   * reaches its shipment through the line link — the order-line arm that used to deduce it is gone.
   */
  @Test
  void salesInvoiceLineFromAnOrderLineIsBoundToItsPendingShipmentLine() throws Exception {
    JSONObject body = runOrderLineCreate("SIL-OLDEST");

    assertEquals("SIL-OLDEST", body.optString("goodsShipmentLine"));
  }

  /** An order line with nothing shipped (or all of it invoiced) is invoiced unlinked. */
  @Test
  void salesInvoiceLineFromAnOrderLineWithNoPendingShipmentStaysUnlinked() throws Exception {
    JSONObject body = runOrderLineCreate(null);

    assertTrue(!body.has("goodsShipmentLine"), "no pending shipment line → no link");
  }

  /**
   * Creates a line of a plain (non-rectificative) sales invoice from {@link #ORDER_LINE_ID}; the
   * shared rule allocates {@code pendingLineId} first, or nothing when it is null.
   */
  private static JSONObject runOrderLineCreate(String pendingLineId) throws Exception {
    OBDal dal = mock(OBDal.class);
    DocumentType docType = mock(DocumentType.class);
    when(docType.isEtsgIsRectificative()).thenReturn(false);
    when(docType.getDocumentCategory()).thenReturn("ARI");
    Invoice invoice = mock(Invoice.class);
    when(invoice.getTransactionDocument()).thenReturn(docType);
    when(dal.get(Invoice.class, PLAIN_INVOICE_ID)).thenReturn(invoice);
    OrderLine orderLine = mock(OrderLine.class);
    when(dal.get(OrderLine.class, ORDER_LINE_ID)).thenReturn(orderLine);
    List<OrderLineMovementAllocation.Allocation> allocations = new java.util.ArrayList<>();
    if (pendingLineId != null) {
      ShipmentInOutLine shipmentLine = mock(ShipmentInOutLine.class);
      when(shipmentLine.getId()).thenReturn(pendingLineId);
      allocations.add(new OrderLineMovementAllocation.Allocation(shipmentLine, new BigDecimal("2")));
    }

    JSONObject body = new JSONObject().put("parentId", PLAIN_INVOICE_ID).put("product", "P1")
        .put("unitPrice", 10).put("invoicedQuantity", 2).put("salesOrderLine", ORDER_LINE_ID);
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = LineAmountTestSupport.taxRate(21.0, dal);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OrderLineMovementAllocation> rule =
            mockStatic(OrderLineMovementAllocation.class)) {
      rule.when(() -> OrderLineMovementAllocation.allocateOrderLine(same(orderLine), any()))
          .thenReturn(allocations);
      assertNull(new SalesInvoiceLineHandler()
          .handle(createContext("sales-invoice", "lines", body)));
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
    return body;
  }

  /**
   * Every request the shipment-line binding must leave alone, one row each: the body is written
   * only for a CRUD POST of a line from an order line, with no shipment line or source invoice line
   * of its own, a positive quantity, and a parent that is not a rectificative. The shared rule would
   * answer a pending shipment line in every row, so a missed skip shows up as a written link.
   */
  static Stream<Arguments> shipmentLineSkipCases() {
    return Stream.of(
        Arguments.of("goodsShipmentLine already in the body", "POST", "SIL-SENT", null, false, true,
            "2"),
        Arguments.of("sourceInvoiceLineId present", "POST", null, SOURCE_LINE_ID, false, true, "2"),
        Arguments.of("rectificative parent", "POST", null, null, true, true, "2"),
        Arguments.of("zero quantity", "POST", null, null, false, true, "0"),
        Arguments.of("negative quantity", "POST", null, null, false, true, "-1"),
        Arguments.of("no parent id", "POST", null, null, false, false, "2"),
        Arguments.of("not a POST", "PATCH", null, null, false, true, "2"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shipmentLineSkipCases")
  void bindPendingShipmentLineOnCreateLeavesTheBodyAlone(String label, String httpMethod,
      String sentShipmentLine, String sourceInvoiceLineId, boolean rectificativeParent,
      boolean withParent, String quantity) throws Exception {
    OBDal dal = mock(OBDal.class);
    DocumentType docType = mock(DocumentType.class);
    when(docType.isEtsgIsRectificative()).thenReturn(rectificativeParent);
    when(docType.getDocumentCategory()).thenReturn("ARI");
    Invoice invoice = mock(Invoice.class);
    when(invoice.getTransactionDocument()).thenReturn(docType);
    when(dal.get(Invoice.class, PLAIN_INVOICE_ID)).thenReturn(invoice);
    OrderLine orderLine = mock(OrderLine.class);
    when(dal.get(OrderLine.class, ORDER_LINE_ID)).thenReturn(orderLine);
    ShipmentInOutLine pending = mock(ShipmentInOutLine.class);
    when(pending.getId()).thenReturn("SIL-PENDING");
    List<OrderLineMovementAllocation.Allocation> allocations = new ArrayList<>();
    allocations.add(new OrderLineMovementAllocation.Allocation(pending, new BigDecimal("2")));

    JSONObject body = new JSONObject().put("salesOrderLine", ORDER_LINE_ID)
        .put("invoicedQuantity", quantity);
    if (withParent) {
      body.put(PARENT_ID_KEY, PLAIN_INVOICE_ID);
    }
    if (sentShipmentLine != null) {
      body.put("goodsShipmentLine", sentShipmentLine);
    }
    NeoContext context = NeoContext.builder()
        .specName("sales-invoice")
        .entityName("lines")
        .httpMethod(httpMethod)
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();

    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OrderLineMovementAllocation> rule =
            mockStatic(OrderLineMovementAllocation.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      rule.when(() -> OrderLineMovementAllocation.allocateOrderLine(any(), any()))
          .thenReturn(allocations);

      SalesInvoiceShipmentLineResolver.bindPendingShipmentLineOnCreate(context,
          sourceInvoiceLineId);

      if (sentShipmentLine == null) {
        assertFalse(body.has("goodsShipmentLine"), label + ": no link may be written");
      } else {
        assertEquals(sentShipmentLine, body.getString("goodsShipmentLine"),
            label + ": the shipment line the request sent is kept");
      }
      rule.verify(() -> OrderLineMovementAllocation.allocateOrderLine(any(), any()), never());
    } finally {
      RectificativeSupport.setColumnPresentForTests(null);
    }
  }
}
