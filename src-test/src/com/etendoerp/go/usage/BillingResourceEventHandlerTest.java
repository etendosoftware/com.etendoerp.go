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

package com.etendoerp.go.usage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

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
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.TriggerHandler;

import com.etendoerp.go.schemaforge.data.BillingResource;

/**
 * Unit specs for {@link BillingResourceEventHandler} (ETP-5050, acceptance criterion #6).
 *
 * <p><b>Why this class exists.</b> {@link UsageResourceValidator} was fully implemented and fully
 * tested from the start — and none of it ran, because nothing called it. Every rejection it can
 * produce was reachable only by the nightly job, which is exactly the situation validation was
 * meant to prevent. The gap was not in the validation logic but in the WIRING, so these tests are
 * about the wiring and nothing else: which entity is observed, and that both persistence events
 * reach the validator.
 *
 * <p>The observed-entity lookup is the fragile half. If the entity name is wrong, CDI still
 * deploys the observer, the save still succeeds, and nothing anywhere reports a problem — the
 * validation simply never fires, which is indistinguishable from the bug being back. So it is
 * asserted against {@link BillingResource#ENTITY_NAME} rather than a retyped string literal.
 *
 * <p>{@code TriggerHandler} is stubbed because {@code isValidEvent} consults it first: event
 * handlers are disabled during data import, and without the stub that check reaches a real
 * singleton that has no thread state in a unit-test JVM.
 */
class BillingResourceEventHandlerTest {

  /**
   * {@link BillingResourceEventHandler} caches its observed entities in a {@code static} field —
   * the standard Openbravo observer idiom, since the model does not change at run time. Calling
   * {@code getObservedEntities()} under a mocked {@code ModelProvider} therefore writes a MOCK
   * entity into production state that outlives this class.
   *
   * <p><b>The {@code @AfterEach} is the load-bearing one, and it guards the whole JVM, not this
   * class.</b> {@code EntityPersistenceEventObserver.isValidEvent} matches by REFERENCE IDENTITY
   * ({@code entity == targetEntity}), so a leaked mock never equals the real entity: the observer
   * silently stops firing for every later test in the same JVM. Nothing throws and nothing logs —
   * saves simply stop being validated — so the symptom surfaces as unrelated integration tests
   * failing with "nothing happened", a long way from the cause. This exact leak broke four
   * save-time tests in {@code UsageAggregationServiceIntegrationTest} once already.
   */
  @BeforeEach
  void clearTheObservedEntityCacheBefore() throws Exception {
    setObservedEntityCache(null);
  }

  @AfterEach
  void clearTheObservedEntityCacheAfter() throws Exception {
    setObservedEntityCache(null);
  }

  /**
   * States the contract this class owes the rest of the suite, rather than leaving it implied by
   * the {@code @AfterEach}: nothing mocked escapes into production state. If a future refactor
   * moves the clearing around, this fails here — loudly, in the class responsible — instead of as
   * a silent no-op in whatever runs next.
   */
  @AfterAll
  static void nothingMockedEscapesThisClass() throws Exception {
    assertNull(readObservedEntityCache(),
        "this class must not leave a mock entity in BillingResourceEventHandler's static cache;"
            + " isValidEvent matches by reference identity, so a leaked mock silently disables"
            + " the observer for the rest of the JVM");
  }

  private static Field observedEntityCache() throws Exception {
    Field cache = BillingResourceEventHandler.class.getDeclaredField("entities");
    cache.setAccessible(true);
    return cache;
  }

  private static void setObservedEntityCache(Entity[] value) throws Exception {
    observedEntityCache().set(null, value);
  }

  private static Object readObservedEntityCache() throws Exception {
    return observedEntityCache().get(null);
  }

  /**
   * Stubs the model so the handler observes {@code entity}, and the trigger handler so events are
   * not treated as an import. Returns the entity the event will report as its target.
   */
  private static Entity givenObservedEntity(MockedStatic<ModelProvider> modelProvider) {
    ModelProvider provider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(BillingResource.ENTITY_NAME)).thenReturn(entity);
    return entity;
  }

  private static void givenTriggersEnabled(MockedStatic<TriggerHandler> triggerHandler) {
    TriggerHandler handler = mock(TriggerHandler.class);
    triggerHandler.when(TriggerHandler::getInstance).thenReturn(handler);
    when(handler.isDisabled()).thenReturn(false);
  }

  /** A resource whose entity is the one observed, so {@code isValidEvent} accepts the event. */
  private static BillingResource targetResource(Entity entity) {
    BillingResource resource = mock(BillingResource.class);
    when(resource.getEntity()).thenReturn(entity);
    when(resource.getSearchKey()).thenReturn("TEST_RESOURCE");
    when(resource.getCountingMode()).thenReturn(UsageResourceValidator.MODE_STRATEGY);
    when(resource.getStrategyQualifier()).thenReturn("no-such-counter");
    return resource;
  }

  @Test
  @DisplayName("observes ETGO_BILLING_RESOURCE by its DAL entity name")
  void observesTheBillingResourceEntity() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
      Entity entity = givenObservedEntity(modelProvider);

      Entity[] observed = new BillingResourceEventHandler().getObservedEntities();

      assertAll(() -> assertNotNull(observed),
          () -> assertEquals(1, observed.length),
          () -> assertSame(entity, observed[0],
              "a wrong entity name would deploy cleanly and silently never fire"));
    }
  }

  /**
   * The assertions a rejection owes the USER, as opposed to the ones it owes the transaction.
   *
   * <p>Aborting the save was never the missing half — the old code aborted it too. What was
   * missing is that the reason reached the screen: an {@code IllegalArgumentException} out of an
   * observer is swallowed by the UI layer, so the record simply did not save and nothing was
   * said, which is worse than no validation at all because the user cannot tell what is wrong.
   * {@code OBException} is the house pattern for a validating observer in this module
   * ({@code AssetSearchKeyUniqueHandler} does the same) and is what gets rendered.
   *
   * <p>So the type alone is not enough either: a wrapper that dropped or reworded the reason
   * would satisfy "an OBException was thrown" while showing the user nothing useful. The message
   * is therefore pinned to the cause's, and the cause is pinned to the original
   * {@code IllegalArgumentException} — the validator still throws that deliberately, because it
   * is also called outside a request, so this translation is a UI-boundary concern and the
   * original must survive it intact.
   */
  private static void assertTheReasonReachesTheUser(Executable save) {
    OBException thrown = assertThrows(OBException.class, save,
        "an IllegalArgumentException out of an observer aborts the save silently; only an"
            + " OBException reaches the screen");
    Throwable cause = thrown.getCause();
    assertAll(
        () -> assertNotNull(cause, "the original failure must be chained, not discarded"),
        () -> assertInstanceOf(IllegalArgumentException.class, cause,
            "the validator's own type must survive as the cause"),
        () -> assertEquals(cause.getMessage(), thrown.getMessage(),
            "the wrapping must not reword or truncate the reason"),
        () -> assertTrue(thrown.getMessage().contains("no-such-counter"),
            "and the reason must name what is actually wrong: " + thrown.getMessage()));
  }

  /**
   * A new catalog row naming an undeployed qualifier is refused at save, and the user is told
   * why. The exception is what aborts the transaction, so it must propagate out of the observer
   * rather than be logged — and it must be the type the UI renders.
   */
  @Test
  void rejectsAnInvalidResourceOnInsertAndTellsTheUserWhy() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class);
        MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
      Entity entity = givenObservedEntity(modelProvider);
      givenTriggersEnabled(triggerHandler);
      lookup.when(() -> UsageCounterLookup.isDeployed("no-such-counter")).thenReturn(false);
      lookup.when(UsageCounterLookup::deployedQualifiers).thenReturn("(none deployed)");

      // The resource is built BEFORE the event is stubbed: building it stubs a mock of its own,
      // and Mockito rejects a when(...) that starts while another is still open.
      BillingResource resource = targetResource(entity);
      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(resource);

      assertTheReasonReachesTheUser(() -> new BillingResourceEventHandler().onNew(event));
    }
  }

  /**
   * And on update, which is the path that actually matters in practice: a row is far more often
   * broken by editing a working one than by creating a bad one outright.
   */
  @Test
  void rejectsAnInvalidResourceOnUpdateAndTellsTheUserWhy() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class);
        MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
      Entity entity = givenObservedEntity(modelProvider);
      givenTriggersEnabled(triggerHandler);
      lookup.when(() -> UsageCounterLookup.isDeployed("no-such-counter")).thenReturn(false);
      lookup.when(UsageCounterLookup::deployedQualifiers).thenReturn("(none deployed)");

      BillingResource resource = targetResource(entity);
      EntityUpdateEvent event = mock(EntityUpdateEvent.class);
      when(event.getTargetInstance()).thenReturn(resource);

      assertTheReasonReachesTheUser(() -> new BillingResourceEventHandler().onUpdate(event));
    }
  }

  /**
   * THE SECOND HALF OF THE SAME DEFECT, and the one no human spots by reading the string.
   *
   * <p>Openbravo treats '@' as its message-parameter delimiter — {@code OBMessageUtils} parses
   * {@code @CODE@} style placeholders out of an error before rendering it — so a message that
   * contains one is read as a placeholder and can reach the user blank or mangled. Our message
   * was full of them, because it named the CDI annotations the way a developer writes them.
   * The advice is the same without the at-signs; the rendering is not.
   *
   * <p>This is asserted at the OBSERVER, not only at the validator, because the observer is the
   * only path on which it matters: the backfill prints to a log, where an '@' is harmless. It
   * will come back the moment someone rewrites the message to mention the annotations
   * "properly", and nothing else in the suite would notice.
   */
  @Test
  void theMessageShownToTheUserContainsNoAtSign() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class);
        MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
      Entity entity = givenObservedEntity(modelProvider);
      givenTriggersEnabled(triggerHandler);
      lookup.when(() -> UsageCounterLookup.isDeployed("no-such-counter")).thenReturn(false);
      lookup.when(UsageCounterLookup::deployedQualifiers).thenReturn("active-users");

      BillingResource resource = targetResource(entity);
      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(resource);

      OBException thrown = assertThrows(OBException.class,
          () -> new BillingResourceEventHandler().onNew(event));

      assertAll(
          () -> assertFalse(thrown.getMessage().contains("@"),
              "Openbravo parses '@' as a message parameter, so an at-sign here can reach the"
                  + " user blank: " + thrown.getMessage()),
          () -> assertTrue(thrown.getMessage().contains("Named"),
              "the advice about the annotation survives without the at-sign: "
                  + thrown.getMessage()));
    }
  }

  /**
   * An event for some other entity is ignored. Without this guard the observer would validate
   * every saved object as though it were a billing resource.
   */
  @Test
  void ignoresAnEventForAnotherEntity() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class);
        MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
      givenObservedEntity(modelProvider);
      givenTriggersEnabled(triggerHandler);

      Entity otherEntity = mock(Entity.class);
      BillingResource unrelated = mock(BillingResource.class);
      when(unrelated.getEntity()).thenReturn(otherEntity);
      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(unrelated);

      new BillingResourceEventHandler().onNew(event);

      lookup.verify(() -> UsageCounterLookup.isDeployed(org.mockito.ArgumentMatchers.anyString()),
          never());
    }
  }

  /**
   * During a data import the trigger handler disables event handlers, and validation must go with
   * them: an import writes rows in an order the observer cannot judge, and failing it halfway
   * would leave the catalog worse than not validating at all.
   */
  @Test
  void doesNotValidateWhileDataIsBeingImported() {
    try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
        MockedStatic<TriggerHandler> triggerHandler = mockStatic(TriggerHandler.class);
        MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
      Entity entity = givenObservedEntity(modelProvider);
      TriggerHandler handler = mock(TriggerHandler.class);
      triggerHandler.when(TriggerHandler::getInstance).thenReturn(handler);
      when(handler.isDisabled()).thenReturn(true);

      BillingResource resource = targetResource(entity);
      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(resource);

      new BillingResourceEventHandler().onNew(event);

      lookup.verify(() -> UsageCounterLookup.isDeployed(org.mockito.ArgumentMatchers.anyString()),
          never());
    }
  }
}
