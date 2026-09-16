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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.mockito.MockedStatic;
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
   * A new catalog row naming an undeployed qualifier is refused at save. The exception is what
   * aborts the transaction, so it must propagate out of the observer rather than be logged.
   */
  @Test
  void rejectsAnInvalidResourceOnInsert() {
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

      assertThrows(IllegalArgumentException.class,
          () -> new BillingResourceEventHandler().onNew(event));
    }
  }

  /**
   * And on update, which is the path that actually matters in practice: a row is far more often
   * broken by editing a working one than by creating a bad one outright.
   */
  @Test
  void rejectsAnInvalidResourceOnUpdate() {
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

      assertThrows(IllegalArgumentException.class,
          () -> new BillingResourceEventHandler().onUpdate(event));
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
