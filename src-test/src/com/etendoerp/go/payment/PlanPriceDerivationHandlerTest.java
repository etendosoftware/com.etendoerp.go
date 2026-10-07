/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.Plan;

/**
 * Specs for the plan price derivation observer.
 *
 * <p>Two things are being pinned here, and they are different in kind.
 *
 * <p><b>The arithmetic.</b> The provider sends an integer in minor units and never sends the
 * currency's exponent, so the same 4900 is 49.00 EUR, 4900 JPY and 4.900 KWD. A mistake here does
 * not fail — it prices a plan a hundred or a thousand times off on a page a customer reads. The
 * KWD case is kept explicitly because it is the one a two-decimal column would have destroyed
 * outright.
 *
 * <p><b>The refusals.</b> Every branch below aborts a save, and each aborts it for a different
 * reason that the person at the screen has to act on differently. The pair that matters most is
 * {@code ETGO_PlanPriceNotFound} against {@code ETGO_PlanPriceUnreachable}: if a network blip
 * rendered as "that price does not exist", the user's correct reaction — retry in a minute —
 * would be replaced by editing a price id that was right all along. There is an explicit spec for
 * that below, because it is the one property no single-branch test can state.
 *
 * <p>The provider gateway is a hand-rolled recording fake rather than a mock, in the style of
 * {@code CheckoutWebhookProcessorTest}'s {@code RecordingEventStore}: the two branches that must
 * make <b>zero</b> calls are only assertable if something counts them.
 */
class PlanPriceDerivationHandlerTest {

  private static final String PLAN_KEY = "PRO_MONTHLY";
  private static final String PRICE_ID = "price_123";

  // --- the recording fake -------------------------------------------------

  /**
   * Records every path asked of the provider and answers with whatever the test staged, so both
   * "what was asked" and "was anything asked at all" are assertable.
   */
  private static final class RecordingStripeApiClient implements StripeApiClient {
    private final List<String> requestedPaths = new ArrayList<>();
    private StripeResponse answer = new StripeResponse(200, "{}");
    private StripeTransportException failure;

    @Override
    public StripeResponse get(String path) throws StripeTransportException {
      requestedPaths.add(path);
      if (failure != null) {
        throw failure;
      }
      return answer;
    }

    @Override
    public StripeResponse postForm(String path, String formBody) {
      throw new UnsupportedOperationException("the derivation never posts");
    }

    RecordingStripeApiClient answering(int status, String body) {
      this.answer = new StripeResponse(status, body);
      this.failure = null;
      return this;
    }

    RecordingStripeApiClient failingWith(StripeTransportException transportFailure) {
      this.failure = transportFailure;
      return this;
    }

    int callCount() {
      return requestedPaths.size();
    }
  }

  private static String recurringPrice(String currency, String unitAmount, String interval) {
    return "{\"id\":\"" + PRICE_ID + "\",\"object\":\"price\",\"type\":\"recurring\","
        + "\"active\":true,\"currency\":\"" + currency + "\",\"unit_amount\":" + unitAmount + ","
        + "\"recurring\":{\"interval\":\"" + interval + "\",\"interval_count\":1}}";
  }

  private static PlanPriceDerivationHandler handlerBackedBy(RecordingStripeApiClient fake) {
    PlanPriceDerivationHandler handler = new PlanPriceDerivationHandler();
    handler.stripeApiClient = fake;
    return handler;
  }

  /**
   * Stubs the message catalog so the assertions can name the AD_MESSAGE value that was chosen.
   * The message catalog itself needs an Openbravo runtime, and which message is picked — not
   * its wording — is what these specs are about.
   */
  private static void givenAMessageCatalog(MockedStatic<OBMessageUtils> messages) {
    messages.when(() -> OBMessageUtils.messageBD(anyString()))
        .thenAnswer(invocation -> "[" + invocation.getArgument(0) + "] price @price@ every @count@"
            + " @interval@ provider @providerInterval@ plan @planInterval@");
  }

  private static void assertRefusedWith(String expectedMessageValue, Executable save) {
    OBException thrown = assertThrows(OBException.class, save,
        "an unverifiable price must not save");
    assertTrue(thrown.getMessage().contains("[" + expectedMessageValue + "]"),
        "expected " + expectedMessageValue + " but got: " + thrown.getMessage());
  }

  // --- the arithmetic -----------------------------------------------------

  @Test
  @DisplayName("a two-decimal currency: 4900 EUR is 49.00, not 4900")
  void twoDecimalCurrencyShiftsByTwo() {
    RecordingStripeApiClient fake = new RecordingStripeApiClient()
        .answering(200, recurringPrice("eur", "4900", "month"));

    PlanPriceDerivationHandler.DerivedPrice derived = handlerBackedBy(fake)
        .resolvePrice(PLAN_KEY, PRICE_ID, "", true);

    assertAll(
        () -> assertEquals(0, derived.getDisplayPrice().compareTo(new BigDecimal("49.0000"))),
        () -> assertEquals("49.00", derived.getDisplayPrice().toPlainString()),
        () -> assertEquals("EUR", derived.getCurrencyCode(),
            "the provider sends lower case; the column stores ISO upper case"),
        () -> assertEquals("month", derived.getBillingInterval()),
        () -> assertEquals("/v1/prices/" + PRICE_ID, fake.requestedPaths.get(0)));
  }

  @Test
  @DisplayName("a zero-decimal currency: 4900 JPY is 4900, not 49")
  void zeroDecimalCurrencyIsNotShiftedAtAll() {
    RecordingStripeApiClient fake = new RecordingStripeApiClient()
        .answering(200, recurringPrice("jpy", "4900", "month"));

    PlanPriceDerivationHandler.DerivedPrice derived = handlerBackedBy(fake)
        .resolvePrice(PLAN_KEY, PRICE_ID, "", true);

    assertAll(
        () -> assertEquals(0, derived.getDisplayPrice().compareTo(new BigDecimal("4900"))),
        () -> assertEquals("4900", derived.getDisplayPrice().toPlainString(),
            "dividing by 100 would have charged a hundredth of the intended price"),
        () -> assertEquals("JPY", derived.getCurrencyCode()));
  }

  @Test
  @DisplayName("a three-decimal currency: 4900 KWD is 4.900 — the case a DECIMAL(20,2) destroys")
  void threeDecimalCurrencyShiftsByThree() {
    // Kept as its own spec because it is the one that cannot be recovered after the fact: with a
    // two-decimal column, 4.900 rounds to 4.90 on the way in and the exact price is simply gone.
    RecordingStripeApiClient fake = new RecordingStripeApiClient()
        .answering(200, recurringPrice("kwd", "4900", "month"));

    PlanPriceDerivationHandler.DerivedPrice derived = handlerBackedBy(fake)
        .resolvePrice(PLAN_KEY, PRICE_ID, "", true);

    assertAll(
        () -> assertEquals(0, derived.getDisplayPrice().compareTo(new BigDecimal("4.9000"))),
        () -> assertEquals("4.900", derived.getDisplayPrice().toPlainString()),
        () -> assertEquals("KWD", derived.getCurrencyCode()));
  }

  @Test
  @DisplayName("unit_amount_decimal is preferred, and kept exactly")
  void fractionalMinorUnitsSurviveExactly() {
    // The provider rounds `unit_amount` to a whole minor unit; `unit_amount_decimal` is the real
    // figure. Reading the rounded one would quietly misprice every fractional per-seat price.
    RecordingStripeApiClient fake = new RecordingStripeApiClient().answering(200,
        "{\"type\":\"recurring\",\"active\":true,\"currency\":\"eur\",\"unit_amount\":5000,"
            + "\"unit_amount_decimal\":\"4999.5\","
            + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}");

    PlanPriceDerivationHandler.DerivedPrice derived = handlerBackedBy(fake)
        .resolvePrice(PLAN_KEY, PRICE_ID, "", true);

    assertAll(
        () -> assertEquals(0, derived.getDisplayPrice().compareTo(new BigDecimal("49.995"))),
        () -> assertEquals("49.995", derived.getDisplayPrice().toPlainString(),
            "the rounded unit_amount would have given 50.00"));
  }

  // --- the refusals -------------------------------------------------------

  @Test
  @DisplayName("a missing price id at the provider is ETGO_PlanPriceNotFound")
  void resourceMissingIsNotFound() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(404, "{\"error\":{\"code\":\"resource_missing\",\"message\":\"No such\"}}"));

      assertRefusedWith("ETGO_PlanPriceNotFound",
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));
    }
  }

  @Test
  @DisplayName("the price id is substituted into the message the user reads")
  void theMessageNamesThePriceThatWasRefused() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(404, "{\"error\":{\"code\":\"resource_missing\"}}"));

      OBException thrown = assertThrows(OBException.class,
          () -> handler.resolvePrice(PLAN_KEY, "price_typo", "", true));

      assertAll(() -> assertTrue(thrown.getMessage().contains("price_typo"),
          "an error that does not name the id is useless on a window with several plans: "
              + thrown.getMessage()),
          () -> assertTrue(!thrown.getMessage().contains("@price@"),
              "the placeholder must be substituted, not shown: " + thrown.getMessage()));
    }
  }

  @Test
  @DisplayName("a 404 that is NOT resource_missing is a plain rejection")
  void otherFourOhFoursAreRejections() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(404, "{\"error\":{\"code\":\"url_invalid\"}}"));

      assertRefusedWith("ETGO_PlanPriceRejected",
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));
    }
  }

  @Test
  @DisplayName("401 and 403 are ETGO_PlanPriceUnauthorized — a credentials problem, not a typo")
  void authenticationFailuresAreUnauthorized() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler unauthenticated = handlerBackedBy(new RecordingStripeApiClient()
          .answering(401, "{\"error\":{\"code\":\"api_key_expired\"}}"));
      PlanPriceDerivationHandler forbidden = handlerBackedBy(new RecordingStripeApiClient()
          .answering(403, "{\"error\":{\"code\":\"insufficient_permissions\"}}"));

      assertAll(
          () -> assertRefusedWith("ETGO_PlanPriceUnauthorized",
              () -> unauthenticated.resolvePrice(PLAN_KEY, PRICE_ID, "", true)),
          () -> assertRefusedWith("ETGO_PlanPriceUnauthorized",
              () -> forbidden.resolvePrice(PLAN_KEY, PRICE_ID, "", true)));
    }
  }

  @Test
  @DisplayName("any other 4xx is ETGO_PlanPriceRejected")
  void otherClientErrorsAreRejected() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(400, "{\"error\":{\"code\":\"parameter_invalid_empty\"}}"));

      assertRefusedWith("ETGO_PlanPriceRejected",
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));
    }
  }

  @Test
  @DisplayName("a transport failure is ETGO_PlanPriceUnreachable, with the cause kept")
  void transportFailureIsUnreachable() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      StripeTransportException transportFailure = new StripeTransportException("read timed out");
      PlanPriceDerivationHandler handler = handlerBackedBy(
          new RecordingStripeApiClient().failingWith(transportFailure));

      OBException thrown = assertThrows(OBException.class,
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));

      assertAll(
          () -> assertTrue(thrown.getMessage().contains("[ETGO_PlanPriceUnreachable]"),
              thrown.getMessage()),
          () -> assertSame(transportFailure, thrown.getCause(),
              "the socket-level reason must survive into the log"));
    }
  }

  @Test
  @DisplayName("THE distinction: a network blip and a bad price id are different messages")
  void unreachableAndNotFoundAreNeverTheSameMessage() {
    // The single property the whole StripeResponse / StripeTransportException split exists to
    // deliver. Every other spec in this class checks one branch; this one checks that two
    // branches stayed apart. Collapse them and the user's correct reaction to an outage — wait
    // and retry — is replaced by editing a price id that was never wrong.
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler unreachable = handlerBackedBy(new RecordingStripeApiClient()
          .failingWith(new StripeTransportException("connect timed out")));
      PlanPriceDerivationHandler missing = handlerBackedBy(new RecordingStripeApiClient()
          .answering(404, "{\"error\":{\"code\":\"resource_missing\"}}"));

      OBException outage = assertThrows(OBException.class,
          () -> unreachable.resolvePrice(PLAN_KEY, PRICE_ID, "", true));
      OBException badId = assertThrows(OBException.class,
          () -> missing.resolvePrice(PLAN_KEY, PRICE_ID, "", true));

      assertAll(
          () -> assertNotEquals(outage.getMessage(), badId.getMessage(),
              "an outage must not read as a bad price id"),
          () -> assertTrue(outage.getMessage().contains("[ETGO_PlanPriceUnreachable]"),
              outage.getMessage()),
          () -> assertTrue(badId.getMessage().contains("[ETGO_PlanPriceNotFound]"),
              badId.getMessage()));
    }
  }

  @Test
  @DisplayName("a one-off price cannot back a plan")
  void oneOffPricesAreRefused() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(200, "{\"type\":\"one_time\",\"active\":true,\"currency\":\"eur\","
              + "\"unit_amount\":4900}"));

      assertRefusedWith("ETGO_PlanPriceNotRecurring",
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));
    }
  }

  @Test
  @DisplayName("a price claiming to be recurring with no recurring block is refused too")
  void recurringWithoutARecurringBlockIsRefused() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(200, "{\"type\":\"recurring\",\"active\":true,\"currency\":\"eur\","
              + "\"unit_amount\":4900}"));

      assertRefusedWith("ETGO_PlanPriceNotRecurring",
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));
    }
  }

  @Test
  @DisplayName("an archived price cannot back an ACTIVE plan")
  void archivedPriceIsRefusedForAnActivePlan() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(200, "{\"type\":\"recurring\",\"active\":false,\"currency\":\"eur\","
              + "\"unit_amount\":4900,"
              + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}"));

      assertRefusedWith("ETGO_PlanPriceArchived",
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));
    }
  }

  @Test
  @DisplayName("but an archived price is fine on an inactive plan — that is a retired pair")
  void archivedPriceIsAllowedOnAnInactivePlan() {
    // Refusing here would make a plan retired alongside its price impossible to save at all,
    // which is a worse outcome than the state the rule is guarding against.
    RecordingStripeApiClient fake = new RecordingStripeApiClient()
        .answering(200, "{\"type\":\"recurring\",\"active\":false,\"currency\":\"eur\","
            + "\"unit_amount\":4900,"
            + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}");

    PlanPriceDerivationHandler.DerivedPrice derived = handlerBackedBy(fake)
        .resolvePrice(PLAN_KEY, PRICE_ID, "", false);

    assertEquals(0, derived.getDisplayPrice().compareTo(new BigDecimal("49.00")));
  }

  @Test
  @DisplayName("an interval count other than 1 is not supported")
  void intervalCountsOtherThanOneAreRefused() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(200, "{\"type\":\"recurring\",\"active\":true,\"currency\":\"eur\","
              + "\"unit_amount\":4900,"
              + "\"recurring\":{\"interval\":\"month\",\"interval_count\":3}}"));

      OBException thrown = assertThrows(OBException.class,
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "", true));

      assertAll(
          () -> assertTrue(thrown.getMessage().contains("[ETGO_PlanIntervalCountUnsupported]"),
              thrown.getMessage()),
          () -> assertTrue(thrown.getMessage().contains("every 3 month"),
              "the message must say what the price actually does: " + thrown.getMessage()));
    }
  }

  @Test
  @DisplayName("a declared interval that contradicts the price is refused, naming both")
  void declaredIntervalMustMatchTheProvider() {
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      givenAMessageCatalog(messages);
      PlanPriceDerivationHandler handler = handlerBackedBy(new RecordingStripeApiClient()
          .answering(200, recurringPrice("eur", "4900", "month")));

      OBException thrown = assertThrows(OBException.class,
          () -> handler.resolvePrice(PLAN_KEY, PRICE_ID, "year", true));

      assertAll(
          () -> assertTrue(thrown.getMessage().contains("[ETGO_PlanIntervalMismatch]"),
              thrown.getMessage()),
          () -> assertTrue(thrown.getMessage().contains("provider month"), thrown.getMessage()),
          () -> assertTrue(thrown.getMessage().contains("plan year"), thrown.getMessage()));
    }
  }

  @Test
  @DisplayName("a blank billing interval is derived from the price instead of being refused")
  void blankIntervalIsDerived() {
    RecordingStripeApiClient fake = new RecordingStripeApiClient()
        .answering(200, recurringPrice("eur", "120000", "year"));

    PlanPriceDerivationHandler.DerivedPrice derived = handlerBackedBy(fake)
        .resolvePrice(PLAN_KEY, PRICE_ID, "   ", true);

    assertEquals("year", derived.getBillingInterval());
  }

  @Test
  @DisplayName("a matching interval passes whatever its case")
  void matchingIntervalIsAccepted() {
    RecordingStripeApiClient fake = new RecordingStripeApiClient()
        .answering(200, recurringPrice("eur", "4900", "month"));

    PlanPriceDerivationHandler.DerivedPrice derived = handlerBackedBy(fake)
        .resolvePrice(PLAN_KEY, PRICE_ID, "Month", true);

    assertEquals("Month", derived.getBillingInterval(), "what the plan declared is kept as typed");
  }

  // --- the observer wiring ------------------------------------------------

  /**
   * The static observed-entity cache is production state. Leaving a mock in it silently disables
   * the observer for the rest of the JVM, because {@code isValidEvent} matches by reference
   * identity — the same trap documented at length on {@code BillingResourceEventHandlerTest}.
   */
  @BeforeEach
  void clearTheObservedEntityCacheBefore() throws Exception {
    setObservedEntityCache(null);
  }

  @AfterEach
  void clearTheObservedEntityCacheAfter() throws Exception {
    setObservedEntityCache(null);
  }

  @AfterAll
  static void nothingMockedEscapesThisClass() throws Exception {
    assertNull(observedEntityCache().get(null),
        "a leaked mock entity silently disables this observer for every later test in the JVM");
  }

  private static Field observedEntityCache() throws Exception {
    Field cache = PlanPriceDerivationHandler.class.getDeclaredField("entities");
    cache.setAccessible(true);
    return cache;
  }

  private static void setObservedEntityCache(Entity[] value) throws Exception {
    observedEntityCache().set(null, value);
  }

  /**
   * A plan row being flushed: the property mocks, the current and previous state, and the
   * recording of everything the observer writes back through {@code setCurrentState}.
   */
  private static final class FlushingPlan {
    private final Entity entity = mock(Entity.class);
    private final Map<String, Property> properties = new HashMap<>();
    private final Map<String, Object> current = new HashMap<>();
    private final Map<String, Object> previous = new HashMap<>();
    private final Plan plan = mock(Plan.class);

    FlushingPlan() {
      when(plan.getEntity()).thenReturn(entity);
      when(plan.getSearchKey()).thenReturn(PLAN_KEY);
      for (String name : new String[] { Plan.PROPERTY_PROVIDERPRICEID, Plan.PROPERTY_DISPLAYPRICE,
          Plan.PROPERTY_CURRENCYCODE, Plan.PROPERTY_BILLINGINTERVAL, Plan.PROPERTY_PRICESYNCEDAT,
          Plan.PROPERTY_ACTIVE }) {
        Property property = mock(Property.class);
        when(property.getName()).thenReturn(name);
        properties.put(name, property);
        when(entity.getProperty(name)).thenReturn(property);
      }
      current.put(Plan.PROPERTY_ACTIVE, Boolean.TRUE);
    }

    FlushingPlan withCurrent(String property, Object value) {
      current.put(property, value);
      return this;
    }

    FlushingPlan withPrevious(String property, Object value) {
      previous.put(property, value);
      return this;
    }

    EntityNewEvent asInsert() {
      EntityNewEvent event = mock(EntityNewEvent.class);
      wire(event);
      return event;
    }

    EntityUpdateEvent asUpdate() {
      EntityUpdateEvent event = mock(EntityUpdateEvent.class);
      wire(event);
      when(event.getPreviousState(any(Property.class)))
          .thenAnswer(invocation -> previous.get(nameOf(invocation.getArgument(0))));
      return event;
    }

    private void wire(EntityPersistenceEvent event) {
      when(event.getTargetInstance()).thenReturn(plan);
      when(event.getCurrentState(any(Property.class)))
          .thenAnswer(invocation -> current.get(nameOf(invocation.getArgument(0))));
      doAnswer(invocation -> {
        current.put(nameOf(invocation.getArgument(0)), invocation.getArgument(1));
        return null;
      }).when(event).setCurrentState(any(Property.class), any());
    }

    private static String nameOf(Object property) {
      return ((Property) property).getName();
    }

    Object stateOf(String property) {
      return current.get(property);
    }
  }

  private static void givenTriggersEnabled(MockedStatic<TriggerHandler> triggerHandler) {
    TriggerHandler handler = mock(TriggerHandler.class);
    triggerHandler.when(TriggerHandler::getInstance).thenReturn(handler);
    when(handler.isDisabled()).thenReturn(false);
  }

  /** Stubs the model so the handler observes the entity this plan reports as its own. */
  private static void givenObservedEntity(MockedStatic<ModelProvider> modelProvider,
      FlushingPlan flushing) {
    ModelProvider provider = mock(ModelProvider.class);
    modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(Plan.ENTITY_NAME)).thenReturn(flushing.entity);
  }

  @Test
  @DisplayName("observes ETGO_PLAN by its DAL entity name")
  void observesThePlanEntity() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
      ModelProvider provider = mock(ModelProvider.class);
      Entity entity = mock(Entity.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntity(Plan.ENTITY_NAME)).thenReturn(entity);

      Entity[] observed = new PlanPriceDerivationHandler().getObservedEntities();

      assertAll(() -> assertEquals(1, observed.length),
          () -> assertSame(entity, observed[0],
              "a wrong entity name deploys cleanly and silently never fires"));
    }
  }

  @Test
  @DisplayName("a plan with no provider price id costs ZERO provider calls and is cleared")
  void aLegacyPlanNeverCallsTheProvider() {
    // The grandfathered plan: it predates provider billing and has no relationship with the
    // provider at all. Calling anyway would make it unsaveable during any provider outage, for a
    // question the provider cannot answer.
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class)) {
      FlushingPlan flushing = new FlushingPlan()
          .withCurrent(Plan.PROPERTY_PROVIDERPRICEID, "   ")
          .withCurrent(Plan.PROPERTY_DISPLAYPRICE, new BigDecimal("49.00"))
          .withCurrent(Plan.PROPERTY_CURRENCYCODE, "EUR")
          .withCurrent(Plan.PROPERTY_PRICESYNCEDAT, new Date());
      givenObservedEntity(modelProvider, flushing);
      givenTriggersEnabled(triggerHandler);
      RecordingStripeApiClient fake = new RecordingStripeApiClient();

      handlerBackedBy(fake).onNew(flushing.asInsert());

      assertAll(() -> assertEquals(0, fake.callCount(), "the provider must not be consulted"),
          () -> assertNull(flushing.stateOf(Plan.PROPERTY_DISPLAYPRICE)),
          () -> assertNull(flushing.stateOf(Plan.PROPERTY_CURRENCYCODE)),
          () -> assertNull(flushing.stateOf(Plan.PROPERTY_PRICESYNCEDAT)));
    }
  }

  @Test
  @DisplayName("an edit that leaves the price id alone costs ZERO calls and reverts a hand-typed price")
  void anUnrelatedEditNeverCallsTheProviderAndUndoesHandTyping() {
    // Two guarantees in one branch. Without it, renaming a plan or toggling ISACTIVE would call
    // the provider, so the Plans window would stop working for the duration of any outage — for
    // edits that have nothing to do with pricing. And because the stored values are re-asserted
    // from the previous state, a displayPrice someone typed over in the window is silently put
    // back, which is the "never hand-typed" guarantee without a round trip.
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class)) {
      FlushingPlan flushing = new FlushingPlan()
          .withPrevious(Plan.PROPERTY_PROVIDERPRICEID, PRICE_ID)
          .withPrevious(Plan.PROPERTY_DISPLAYPRICE, new BigDecimal("49.00"))
          .withPrevious(Plan.PROPERTY_CURRENCYCODE, "EUR")
          .withPrevious(Plan.PROPERTY_BILLINGINTERVAL, "month")
          .withCurrent(Plan.PROPERTY_PROVIDERPRICEID, PRICE_ID)
          .withCurrent(Plan.PROPERTY_DISPLAYPRICE, new BigDecimal("1.00"))
          .withCurrent(Plan.PROPERTY_CURRENCYCODE, "USD")
          .withCurrent(Plan.PROPERTY_BILLINGINTERVAL, "year");
      givenObservedEntity(modelProvider, flushing);
      givenTriggersEnabled(triggerHandler);
      RecordingStripeApiClient fake = new RecordingStripeApiClient();

      handlerBackedBy(fake).onUpdate(flushing.asUpdate());

      assertAll(() -> assertEquals(0, fake.callCount(),
          "an unrelated edit must not depend on the provider being up"),
          () -> assertEquals(0, ((BigDecimal) flushing.stateOf(Plan.PROPERTY_DISPLAYPRICE))
              .compareTo(new BigDecimal("49.00")), "the hand-typed price must be reverted"),
          () -> assertEquals("EUR", flushing.stateOf(Plan.PROPERTY_CURRENCYCODE)),
          () -> assertEquals("month", flushing.stateOf(Plan.PROPERTY_BILLINGINTERVAL)));
    }
  }

  @Test
  @DisplayName("a changed price id is re-derived and stamped")
  void aChangedPriceIdIsReDerived() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class)) {
      FlushingPlan flushing = new FlushingPlan()
          .withPrevious(Plan.PROPERTY_PROVIDERPRICEID, "price_old")
          .withCurrent(Plan.PROPERTY_PROVIDERPRICEID, PRICE_ID);
      givenObservedEntity(modelProvider, flushing);
      givenTriggersEnabled(triggerHandler);
      RecordingStripeApiClient fake = new RecordingStripeApiClient()
          .answering(200, recurringPrice("eur", "12000", "month"));

      handlerBackedBy(fake).onUpdate(flushing.asUpdate());

      assertAll(() -> assertEquals(1, fake.callCount()),
          () -> assertEquals("/v1/prices/" + PRICE_ID, fake.requestedPaths.get(0)),
          () -> assertEquals(0, ((BigDecimal) flushing.stateOf(Plan.PROPERTY_DISPLAYPRICE))
              .compareTo(new BigDecimal("120.00"))),
          () -> assertEquals("EUR", flushing.stateOf(Plan.PROPERTY_CURRENCYCODE)),
          () -> assertEquals("month", flushing.stateOf(Plan.PROPERTY_BILLINGINTERVAL)),
          () -> assertNotNull(flushing.stateOf(Plan.PROPERTY_PRICESYNCEDAT),
              "a successful derivation must stamp when it happened"));
    }
  }

  @Test
  @DisplayName("an event for another entity is ignored")
  void ignoresAnEventForAnotherEntity() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class)) {
      FlushingPlan flushing = new FlushingPlan()
          .withCurrent(Plan.PROPERTY_PROVIDERPRICEID, PRICE_ID);
      ModelProvider provider = mock(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntity(Plan.ENTITY_NAME)).thenReturn(mock(Entity.class));
      givenTriggersEnabled(triggerHandler);
      RecordingStripeApiClient fake = new RecordingStripeApiClient();

      handlerBackedBy(fake).onNew(flushing.asInsert());

      assertEquals(0, fake.callCount());
    }
  }

  @Test
  @DisplayName("nothing is derived while data is being imported")
  void doesNotDeriveDuringAnImport() {
    // An import writes rows in an order the observer cannot judge, and every row would cost a
    // provider round trip.
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class)) {
      FlushingPlan flushing = new FlushingPlan()
          .withCurrent(Plan.PROPERTY_PROVIDERPRICEID, PRICE_ID);
      givenObservedEntity(modelProvider, flushing);
      TriggerHandler handler = mock(TriggerHandler.class);
      triggerHandler.when(TriggerHandler::getInstance).thenReturn(handler);
      when(handler.isDisabled()).thenReturn(true);
      RecordingStripeApiClient fake = new RecordingStripeApiClient();

      handlerBackedBy(fake).onNew(flushing.asInsert());

      assertEquals(0, fake.callCount());
    }
  }
}
