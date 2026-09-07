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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * ETP-5184 — unit tests for {@link McpLinePriceInjector}, the MCP-only price compensation added
 * because the NEO selector-aux path resolves a line's price from an arbitrary
 * {@code ProductByPriceAndWarehouse} row (or none), leaving the callout to publish a price of 0.
 *
 * <p>The injector is a chain of guard clauses and every one of them is a decision to ABSTAIN
 * rather than guess — a wrong price looks plausible where a zero does not. Its whole body also
 * sits inside a {@code catch (Exception)} that swallows failures, so "the body was left alone" is
 * not on its own evidence that the intended guard fired: each abstain test below therefore also
 * asserts that {@link FinancialUtils#getProductPrice} was never reached.</p>
 */
class McpLinePriceInjectorTest {

  private static final String PRODUCT = "product";
  private static final String UNIT_PRICE = "unitPrice";
  private static final String LIST_PRICE = "listPrice";
  private static final String PRICE_LIMIT = "priceLimit";
  private static final String PARENT_FIELD = "salesOrder";
  private static final String PARENT_ENTITY_NAME = "Order";

  private static final String PRODUCT_ID = "PROD-001";
  private static final String PARENT_ID = "ORDER-001";
  private static final String REF_PLACEHOLDER = "$ref:op-1";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<FinancialUtils> financialUtilsMock;
  private MockedStatic<McpParentScope> parentScopeMock;

  private OBDal dal;
  private Logger log;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);

    financialUtilsMock = mockStatic(FinancialUtils.class);
    parentScopeMock = mockStatic(McpParentScope.class);
    log = mock(Logger.class);
  }

  @AfterEach
  void tearDown() {
    parentScopeMock.close();
    financialUtilsMock.close();
    obDalMock.close();
  }

  // ─────────────────────────────────────────────────────────────────────
  // Fixture
  // ─────────────────────────────────────────────────────────────────────

  /** A line entity declaring exactly the given properties and nothing else. */
  private static Entity lineEntity(String... properties) {
    Entity entity = mock(Entity.class);
    when(entity.getName()).thenReturn("OrderLine");
    for (String property : properties) {
      when(entity.hasProperty(property)).thenReturn(true);
    }
    return entity;
  }

  /**
   * A commercial line complete in every respect (parent property wired included) except that it
   * declares only {@code properties} — so a test removing one declaration stays single-factor.
   */
  private static Entity lineDeclaringOnly(String... properties) {
    Entity entity = lineEntity(properties);
    withParentProperty(entity, PARENT_ENTITY_NAME);
    return entity;
  }

  /** The commercial-line shape the injector is meant to fire on. */
  private static Entity commercialLineEntity() {
    Entity entity = lineEntity(UNIT_PRICE, LIST_PRICE, PRICE_LIMIT, PARENT_FIELD);
    withParentProperty(entity, PARENT_ENTITY_NAME);
    return entity;
  }

  private static void withParentProperty(Entity lineEntity, String targetEntityName) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(PARENT_FIELD);
    if (targetEntityName != null) {
      Entity target = mock(Entity.class);
      when(target.getName()).thenReturn(targetEntityName);
      when(property.getTargetEntity()).thenReturn(target);
    }
    when(lineEntity.getProperty(PARENT_FIELD, false)).thenReturn(property);
  }

  /**
   * Builds a real {@link McpParentScope.Scope} through its private constructor so the tests bind
   * to the production type rather than to a mock's idea of it.
   */
  private static McpParentScope.Scope scope(McpParentScope.Kind kind, String parentField)
      throws Exception {
    Constructor<McpParentScope.Scope> ctor = McpParentScope.Scope.class.getDeclaredConstructor(
        McpParentScope.Kind.class, String.class, String.class, Set.class, String.class,
        String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(kind, parentField, PARENT_ENTITY_NAME, Set.of(), null, null);
  }

  private void withResolvedScope() throws Exception {
    withScope(scope(McpParentScope.Kind.RESOLVED, PARENT_FIELD));
  }

  private void withScope(McpParentScope.Scope resolved) {
    parentScopeMock.when(() -> McpParentScope.forEntity(any())).thenReturn(resolved);
  }

  private static Date date(int year, int month, int day) {
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(year, month - 1, day);
    return calendar.getTime();
  }

  /** A parent document exposing the given property/value pairs and nothing else. */
  private BaseOBObject parentDocument(Object... propertyValuePairs) {
    Entity parentEntity = mock(Entity.class);
    when(parentEntity.getName()).thenReturn(PARENT_ENTITY_NAME);
    BaseOBObject parent = mock(BaseOBObject.class);
    when(parent.getEntity()).thenReturn(parentEntity);
    when(parent.getEntityName()).thenReturn(PARENT_ENTITY_NAME);
    for (int i = 0; i < propertyValuePairs.length; i += 2) {
      String property = (String) propertyValuePairs[i];
      when(parentEntity.hasProperty(property)).thenReturn(true);
      when(parent.get(property)).thenReturn(propertyValuePairs[i + 1]);
    }
    when(dal.get(PARENT_ENTITY_NAME, PARENT_ID)).thenReturn(parent);
    return parent;
  }

  private static PriceList priceList(boolean includesTax) {
    PriceList priceList = mock(PriceList.class);
    when(priceList.isPriceIncludesTax()).thenReturn(includesTax);
    when(priceList.getIdentifier()).thenReturn("Sales price list");
    return priceList;
  }

  private Product withProduct() {
    Product product = mock(Product.class);
    when(product.getIdentifier()).thenReturn("Widget");
    when(dal.get(Product.class, PRODUCT_ID)).thenReturn(product);
    return product;
  }

  private ProductPrice withResolvedPrice(String standard, String list, String limit) {
    ProductPrice productPrice = mock(ProductPrice.class);
    when(productPrice.getStandardPrice()).thenReturn(new BigDecimal(standard));
    when(productPrice.getListPrice()).thenReturn(new BigDecimal(list));
    when(productPrice.getPriceLimit()).thenReturn(limit == null ? null : new BigDecimal(limit));
    financialUtilsMock.when(() -> FinancialUtils.getProductPrice(any(Product.class),
        any(Date.class), anyBoolean(), any(PriceList.class), eq(false))).thenReturn(productPrice);
    return productPrice;
  }

  private static JSONObject body(Object... keyValuePairs) throws Exception {
    JSONObject body = new JSONObject();
    body.put(PRODUCT, PRODUCT_ID);
    body.put(PARENT_FIELD, PARENT_ID);
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      body.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return body;
  }

  private static SFEntity sfEntity() {
    return mock(SFEntity.class);
  }

  /**
   * Wires everything a successful injection needs — parent document, price list, document date,
   * product and a resolvable price — so that a test which then removes ONE precondition is
   * genuinely single-factor: if the guard under test disappeared, the price WOULD be written.
   */
  private void withEverythingNeededForAPrice() {
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7),
        "salesTransaction", Boolean.TRUE);
    withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
  }

  /** No price was resolved: the price fields are exactly as the caller left them. */
  private void assertAbstained(JSONObject body) {
    financialUtilsMock.verifyNoInteractions();
    assertFalse(body.has(UNIT_PRICE) && body.optDouble(UNIT_PRICE, 0d) != 0d,
        "no non-zero unitPrice may have been written");
    assertFalse(body.has(PRICE_LIMIT), "no priceLimit may have been written");
  }

  /**
   * The guard fired before anything was loaded. Sharper than {@link #assertAbstained}: every
   * eligibility and parent-field guard sits ahead of the first DAL access, so an abstain that
   * touched the DAL means a later guard did the abstaining and the one under test did not fire.
   */
  private void assertAbstainedBeforeAnyLoad(JSONObject body) {
    obDalMock.verifyNoInteractions();
    assertAbstained(body);
  }

  // ─────────────────────────────────────────────────────────────────────
  // Eligibility guards
  // ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Abstains on a null body or a null entity")
  void abstainsOnNullArguments() throws Exception {
    McpLinePriceInjector.injectIfMissing(null, commercialLineEntity(), sfEntity(), Set.of(), log);
    McpLinePriceInjector.injectIfMissing(body(), null, sfEntity(), Set.of(), log);
    financialUtilsMock.verifyNoInteractions();
  }

  @Test
  @DisplayName("Abstains when the entity does not declare unitPrice — not a commercial line")
  void abstainsWhenUnitPriceIsNotDeclared() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body,
        lineDeclaringOnly(LIST_PRICE, PRICE_LIMIT, PARENT_FIELD), sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the entity does not declare listPrice")
  void abstainsWhenListPriceIsNotDeclared() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body,
        lineDeclaringOnly(UNIT_PRICE, PRICE_LIMIT, PARENT_FIELD), sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the body carries no product")
  void abstainsWithoutAProduct() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = new JSONObject();
    body.put(PARENT_FIELD, PARENT_ID);

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the product is still a $ref placeholder — the op has not run yet")
  void abstainsOnAProductRefPlaceholder() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body();
    body.put(PRODUCT, REF_PLACEHOLDER);

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the agent supplied unitPrice itself — the agent's price wins")
  void abstainsWhenTheAgentSuppliedUnitPrice() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body(UNIT_PRICE, new BigDecimal("0"));

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(),
        Set.of(UNIT_PRICE), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the agent supplied listPrice itself")
  void abstainsWhenTheAgentSuppliedListPrice() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(),
        Set.of(LIST_PRICE), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when a non-zero price is already present, whatever put it there")
  void abstainsWhenANonZeroPriceIsAlreadyPresent() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body(UNIT_PRICE, new BigDecimal("42.00"));

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    financialUtilsMock.verifyNoInteractions();
    assertEquals("42.00", body.get(UNIT_PRICE).toString());
  }

  @Test
  @DisplayName("A zero price does not count as present — that is what the injector replaces")
  void aZeroPriceIsNotTreatedAsPresent() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
    JSONObject body = body(UNIT_PRICE, new BigDecimal("0"));

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertEquals("12.50", body.get(UNIT_PRICE).toString());
  }

  // ─────────────────────────────────────────────────────────────────────
  // Parent resolution
  // ─────────────────────────────────────────────────────────────────────

  @ParameterizedTest
  @EnumSource(value = McpParentScope.Kind.class,
      names = { "NOT_CHILD", "SAME_RECORD", "UNPARENTED", "UNRESOLVABLE" })
  @DisplayName("Abstains for every parent Kind that carries no link field")
  void abstainsForEveryKindWithoutAParentField(McpParentScope.Kind kind) throws Exception {
    McpParentScope.Scope resolved = scope(kind, null);
    // The premise of the branch: only RESOLVED names a parent field.
    assertEquals(kind, resolved.getKind());
    withScope(resolved);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstained(body);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   " })
  @DisplayName("A blank parent field is never even looked up on the line entity")
  void aBlankParentFieldIsNeverLookedUp(String parentField) throws Exception {
    // Pins the isBlank half of the parent-field guard. The four Kinds that are not RESOLVED
    // carry no parent field, and asking the DAL entity for a property under a blank name is a
    // question that must not be asked at all: production's Entity.hasProperty(null) happens to
    // answer false, so only the short-circuit itself distinguishes the guard from its absence.
    withScope(scope(McpParentScope.Kind.NOT_CHILD, parentField));
    withEverythingNeededForAPrice();
    Entity entity = commercialLineEntity();
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, entity, sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
    verify(entity, never()).hasProperty(parentField);
    verify(entity, never()).getProperty(anyString(), anyBoolean());
  }

  @Test
  @DisplayName("Abstains when the line entity does not declare the resolved parent field")
  void abstainsWhenTheParentFieldIsNotAProperty() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body();

    // The parent property resolves fine; the entity simply does not declare it.
    McpLinePriceInjector.injectIfMissing(body,
        lineDeclaringOnly(UNIT_PRICE, LIST_PRICE, PRICE_LIMIT), sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the body carries no parent id")
  void abstainsWithoutAParentId() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body();
    body.remove(PARENT_FIELD);

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the parent id is still a $ref placeholder")
  void abstainsOnAParentRefPlaceholder() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    JSONObject body = body();
    body.put(PARENT_FIELD, REF_PLACEHOLDER);

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the parent property has no target entity")
  void abstainsWhenTheParentPropertyHasNoTargetEntity() throws Exception {
    withResolvedScope();
    withEverythingNeededForAPrice();
    Entity entity = lineEntity(UNIT_PRICE, LIST_PRICE, PRICE_LIMIT, PARENT_FIELD);
    withParentProperty(entity, null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, entity, sfEntity(), Set.of(), log);

    assertAbstainedBeforeAnyLoad(body);
  }

  @Test
  @DisplayName("Abstains when the parent record cannot be loaded")
  void abstainsWhenTheParentRecordIsMissing() throws Exception {
    withResolvedScope();
    when(dal.get(anyString(), any())).thenReturn(null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstained(body);
  }

  // ─────────────────────────────────────────────────────────────────────
  // Price list and date
  // ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Abstains when the parent document has no price list")
  void abstainsWithoutAPriceList() throws Exception {
    withResolvedScope();
    parentDocument("orderDate", date(2026, 9, 7));
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstained(body);
    // Reported, not swallowed: without the explicit guard the same abstain happens by NPE inside
    // the catch-all, and the reason for a price of 0 becomes invisible.
    verify(log).debug(contains("no price list"), any(Object.class));
  }

  @Test
  @DisplayName("Abstains on a tax-included price list — the net side is SL_Order_Product's job")
  void abstainsOnATaxIncludedPriceList() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(true), "orderDate", date(2026, 9, 7));
    withProduct();
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstained(body);
    verify(log).debug(contains("includes tax"), any(Object.class));
  }

  @Test
  @DisplayName("Abstains when the parent exposes none of the three document dates")
  void abstainsWithoutADocumentDate() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false));
    // The product resolves fine: the missing date is the only reason to abstain.
    withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstained(body);
    verify(log).debug(contains("no usable document date"), any(Object.class));
  }

  @Test
  @DisplayName("Reads invoiceDate when the parent has no orderDate")
  void fallsBackToInvoiceDate() throws Exception {
    withResolvedScope();
    Date invoiceDate = date(2026, 8, 10);
    parentDocument("priceList", priceList(false), "invoiceDate", invoiceDate);
    withProduct();
    withResolvedPrice("7.00", "8.00", null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    financialUtilsMock.verify(() -> FinancialUtils.getProductPrice(any(Product.class),
        eq(invoiceDate), anyBoolean(), any(PriceList.class), eq(false)));
  }

  @Test
  @DisplayName("Falls back to accountingDate as the last resort")
  void fallsBackToAccountingDate() throws Exception {
    withResolvedScope();
    Date accountingDate = date(2026, 7, 1);
    parentDocument("priceList", priceList(false), "accountingDate", accountingDate);
    withProduct();
    withResolvedPrice("7.00", "8.00", null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    financialUtilsMock.verify(() -> FinancialUtils.getProductPrice(any(Product.class),
        eq(accountingDate), anyBoolean(), any(PriceList.class), eq(false)));
  }

  @Test
  @DisplayName("orderDate takes precedence over invoiceDate")
  void orderDateWinsOverInvoiceDate() throws Exception {
    withResolvedScope();
    Date orderDate = date(2026, 9, 7);
    parentDocument("priceList", priceList(false), "orderDate", orderDate,
        "invoiceDate", date(2026, 1, 1));
    withProduct();
    withResolvedPrice("7.00", "8.00", null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    financialUtilsMock.verify(() -> FinancialUtils.getProductPrice(any(Product.class),
        eq(orderDate), anyBoolean(), any(PriceList.class), eq(false)));
  }

  // ─────────────────────────────────────────────────────────────────────
  // Price resolution
  // ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Abstains when the product itself cannot be loaded")
  void abstainsWhenTheProductIsMissing() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    when(dal.get(eq(Product.class), any())).thenReturn(null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertAbstained(body);
  }

  @Test
  @DisplayName("Abstains when the product has no price in that list at that date")
  void abstainsWhenCoreResolvesNoPrice() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    withProduct();
    financialUtilsMock.when(() -> FinancialUtils.getProductPrice(any(Product.class),
        any(Date.class), anyBoolean(), any(PriceList.class), eq(false))).thenReturn(null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertFalse(body.has(UNIT_PRICE));
    assertFalse(body.has(PRICE_LIMIT));
    verify(log).debug(contains("has no price in list"), any(Object.class), any(Object.class),
        any(Object.class));
  }

  @Test
  @DisplayName("Happy path: writes unitPrice, listPrice and priceLimit from the resolved price")
  void happyPathWritesAllThreePrices() throws Exception {
    withResolvedScope();
    Date orderDate = date(2026, 9, 7);
    PriceList list = priceList(false);
    parentDocument("priceList", list, "orderDate", orderDate, "salesTransaction", Boolean.TRUE);
    Product product = withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertEquals("12.50", body.get(UNIT_PRICE).toString());
    assertEquals("15.00", body.get(LIST_PRICE).toString());
    assertEquals("10.00", body.get(PRICE_LIMIT).toString());
    // The parent's own salesTransaction flag decides which price list side core reads.
    financialUtilsMock.verify(() -> FinancialUtils.getProductPrice(eq(product), eq(orderDate),
        eq(true), eq(list), eq(false)));
  }

  @Test
  @DisplayName("A purchase parent resolves the purchase side of the price list")
  void purchaseParentResolvesThePurchaseSide() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7),
        "salesTransaction", Boolean.FALSE);
    withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    financialUtilsMock.verify(() -> FinancialUtils.getProductPrice(any(Product.class),
        any(Date.class), eq(false), any(PriceList.class), eq(false)));
  }

  @Test
  @DisplayName("A parent with no salesTransaction property is treated as a purchase")
  void missingSalesTransactionDefaultsToFalse() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    financialUtilsMock.verify(() -> FinancialUtils.getProductPrice(any(Product.class),
        any(Date.class), eq(false), any(PriceList.class), eq(false)));
  }

  // ─────────────────────────────────────────────────────────────────────
  // putIfDeclared
  // ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("priceLimit is not written on a line entity that does not declare it")
  void priceLimitIsSkippedWhenNotDeclared() throws Exception {
    withResolvedScope();
    Entity entity = lineEntity(UNIT_PRICE, LIST_PRICE, PARENT_FIELD);
    withParentProperty(entity, PARENT_ENTITY_NAME);
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, entity, sfEntity(), Set.of(), log);

    assertEquals("12.50", body.get(UNIT_PRICE).toString());
    assertEquals("15.00", body.get(LIST_PRICE).toString());
    assertFalse(body.has(PRICE_LIMIT),
        "writing a property the DAL entity does not declare would fail the insert");
  }

  @Test
  @DisplayName("A null amount from core is not written")
  void nullAmountsAreSkipped() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    withProduct();
    withResolvedPrice("12.50", "15.00", null);
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertTrue(body.has(UNIT_PRICE));
    assertFalse(body.has(PRICE_LIMIT));
  }

  // ─────────────────────────────────────────────────────────────────────
  // Robustness
  // ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A failure inside the resolution never propagates out of injectIfMissing")
  void failuresAreSwallowed() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    withProduct();
    financialUtilsMock.when(() -> FinancialUtils.getProductPrice(any(Product.class),
            any(Date.class), anyBoolean(), any(PriceList.class), eq(false)))
        .thenThrow(new IllegalStateException("boom"));
    JSONObject body = body();

    // A convenience default must never turn a valid create into a 500.
    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), Set.of(), log);

    assertFalse(body.has(UNIT_PRICE));
  }

  @Test
  @DisplayName("A null agentProvided set is tolerated")
  void nullAgentProvidedSetIsTolerated() throws Exception {
    withResolvedScope();
    parentDocument("priceList", priceList(false), "orderDate", date(2026, 9, 7));
    withProduct();
    withResolvedPrice("12.50", "15.00", "10.00");
    JSONObject body = body();

    McpLinePriceInjector.injectIfMissing(body, commercialLineEntity(), sfEntity(), null, log);

    assertEquals("12.50", body.get(UNIT_PRICE).toString());
  }
}
