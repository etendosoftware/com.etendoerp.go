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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;

import com.etendoerp.go.schemaforge.data.BillingResource;

/**
 * Unit specs for {@link UsageResourceValidator} (ETP-5050).
 *
 * <p>The validator is pure: it reads a catalog row and either returns or throws. The only
 * collaborators are the two static entry points it consults — {@code ModelProvider}, to prove the
 * named entity and date property actually exist in the runtime model, and
 * {@link UsageCounterLookup}, to prove the named strategy is actually deployed — so both are
 * mocked statically and no database is involved.
 *
 * <p><b>Why these checks matter more than ordinary input validation.</b> Every failure this class
 * prevents is silent. A misspelled {@code Strategy_Qualifier} does not produce an error at 02:00;
 * it produces a resource that counts nothing, which is indistinguishable from a tenant that
 * genuinely used nothing — and the customer is billed accordingly. A date property that is not a
 * date buckets rows by something meaningless. So the tests assert not only that a bad row is
 * rejected but, where the message is the whole point (the deployed-qualifier list), that the
 * message actually names the real options.
 *
 * <p>The mutual-exclusion rules are tested in BOTH directions on purpose: a declarative row
 * carrying a qualifier and a strategy row carrying an entity are each accepted by a validator
 * that only checks "the fields my mode needs are present", and each would mean the row does
 * something other than what its author configured.
 */
class UsageResourceValidatorTest {

  private static final String ENTITY = "Invoice";
  private static final String DATE_PROPERTY = "invoiceDate";
  private static final String QUALIFIER = "active-users";

  /** A catalog row with nothing set; individual tests stub only what they care about. */
  private static BillingResource resource(String mode) {
    BillingResource resource = mock(BillingResource.class);
    when(resource.getCountingMode()).thenReturn(mode);
    when(resource.getSearchKey()).thenReturn("TEST_RESOURCE");
    return resource;
  }

  private static BillingResource declarativeRow() {
    BillingResource resource = resource(UsageResourceValidator.MODE_DECLARATIVE);
    when(resource.getCountedEntity()).thenReturn(ENTITY);
    when(resource.getDateProperty()).thenReturn(DATE_PROPERTY);
    return resource;
  }

  private static BillingResource strategyRow() {
    BillingResource resource = resource(UsageResourceValidator.MODE_STRATEGY);
    when(resource.getStrategyQualifier()).thenReturn(QUALIFIER);
    return resource;
  }

  /** Stubs the runtime model so {@code Invoice.invoiceDate} resolves to a real date column. */
  private static void givenTheEntityHasADateProperty(MockedStatic<ModelProvider> modelProvider) {
    givenTheEntityHasAPropertyOfType(modelProvider, Date.class);
  }

  private static void givenTheEntityHasAPropertyOfType(MockedStatic<ModelProvider> modelProvider,
      Class<?> type) {
    ModelProvider provider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    Property property = mock(Property.class);

    modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(eq(ENTITY), anyBoolean())).thenReturn(entity);
    when(entity.getName()).thenReturn(ENTITY);
    when(entity.hasProperty(DATE_PROPERTY)).thenReturn(true);
    when(entity.getProperty(eq(DATE_PROPERTY), anyBoolean())).thenReturn(property);
    when(property.isPrimitive()).thenReturn(true);
    when(property.getPrimitiveObjectType()).thenAnswer(invocation -> type);
  }

  private static void givenNoEntityIsFound(MockedStatic<ModelProvider> modelProvider) {
    ModelProvider provider = mock(ModelProvider.class);
    modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(anyString(), anyBoolean())).thenReturn(null);
  }

  @Nested
  @DisplayName("the counting mode")
  class CountingMode {

    @Test
    void aNullResourceIsRejected() {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> UsageResourceValidator.validate(null));
      assertTrue(thrown.getMessage().contains("required"));
    }

    /**
     * Anything other than the two known modes is rejected rather than defaulted, because a
     * defaulted mode would count the row with a rule its author did not choose. The blank cases
     * matter too: an unset column must not fall through to declarative.
     */
    @ParameterizedTest(name = "mode = \"{0}\"")
    @ValueSource(strings = { "X", "d", "s", "declarative", " ", "" })
    void anUnknownCountingModeIsRejected(String mode) {
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(resource(mode)));
      assertAll(
          () -> assertTrue(thrown.getMessage().contains("Unknown counting mode"),
              "the message must name the problem: " + thrown.getMessage()),
          () -> assertTrue(
              thrown.getMessage().contains(UsageResourceValidator.MODE_DECLARATIVE)
                  && thrown.getMessage().contains(UsageResourceValidator.MODE_STRATEGY),
              "and list the modes that do exist: " + thrown.getMessage()));
    }

    @Test
    void aNullCountingModeIsRejected() {
      assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(resource(null)));
    }
  }

  @Nested
  @DisplayName("declarative mode")
  class Declarative {

    @Test
    void aWellFormedRowIsAccepted() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        BillingResource row = declarativeRow();
        when(row.getHQLRestriction()).thenReturn("e.posted = 'Y'");

        assertDoesNotThrow(() -> UsageResourceValidator.validate(row));
      }
    }

    @Test
    void aWellFormedRowWithNoRestrictionIsAccepted() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        assertDoesNotThrow(() -> UsageResourceValidator.validate(declarativeRow()));
      }
    }

    @ParameterizedTest(name = "countedEntity = {0}")
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void aMissingCountedEntityIsRejected(String entityName) {
      BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
      when(row.getCountedEntity()).thenReturn(entityName);
      when(row.getDateProperty()).thenReturn(DATE_PROPERTY);

      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(row));
      assertTrue(thrown.getMessage().contains("Counted Entity"), thrown.getMessage());
    }

    @ParameterizedTest(name = "dateProperty = {0}")
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void aMissingDatePropertyIsRejected(String dateProperty) {
      BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
      when(row.getCountedEntity()).thenReturn(ENTITY);
      when(row.getDateProperty()).thenReturn(dateProperty);

      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(row));
      assertTrue(thrown.getMessage().contains("Date Property"), thrown.getMessage());
    }

    /**
     * A declarative row that also carries a qualifier is ambiguous: the job would silently run
     * the declarative rule and ignore the strategy its author clearly meant to use.
     */
    @Test
    void aDeclarativeRowCarryingAStrategyQualifierIsRejected() {
      BillingResource row = declarativeRow();
      when(row.getStrategyQualifier()).thenReturn(QUALIFIER);

      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(row));
      assertAll(
          () -> assertTrue(thrown.getMessage().contains("Strategy Qualifier"),
              thrown.getMessage()),
          () -> assertTrue(thrown.getMessage().contains("empty"), thrown.getMessage()));
    }

    /**
     * The entity name is the DAL name, not the table name — a distinction implementers get wrong
     * often enough that the message says so explicitly.
     */
    @Test
    void anEntityThatDoesNotExistInTheModelIsRejected() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenNoEntityIsFound(modelProvider);
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn("C_Invoice");
        when(row.getDateProperty()).thenReturn(DATE_PROPERTY);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validate(row));
        assertAll(
            () -> assertTrue(thrown.getMessage().contains("C_Invoice"), thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("does not exist"),
                thrown.getMessage()));
      }
    }

    @Test
    void aPropertyThatDoesNotExistOnTheEntityIsRejected() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        ModelProvider provider = mock(ModelProvider.class);
        Entity entity = mock(Entity.class);
        modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
        when(provider.getEntity(eq(ENTITY), anyBoolean())).thenReturn(entity);
        when(entity.getName()).thenReturn(ENTITY);
        when(entity.hasProperty("noSuchDate")).thenReturn(false);

        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn(ENTITY);
        when(row.getDateProperty()).thenReturn("noSuchDate");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validate(row));
        assertTrue(thrown.getMessage().contains("noSuchDate"), thrown.getMessage());
      }
    }

    /**
     * The property exists but holds something that is not a date. Bucketing by it would produce
     * numbers that look plausible and mean nothing, so it is rejected at save time.
     */
    @ParameterizedTest(name = "property type = {0}")
    @org.junit.jupiter.params.provider.MethodSource(
        "com.etendoerp.go.usage.UsageResourceValidatorTest#nonDateTypes")
    void aPropertyThatIsNotADateIsRejected(Class<?> type) {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenTheEntityHasAPropertyOfType(modelProvider, type);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validate(declarativeRow()));
        assertAll(
            () -> assertTrue(thrown.getMessage().contains(DATE_PROPERTY), thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("not a date"), thrown.getMessage()));
      }
    }

    /**
     * {@code java.sql.Timestamp} IS a {@code java.util.Date}, which is what Openbravo actually
     * hands back for a date column. Guards against a type check tightened to exact equality,
     * which would reject every real date column in the model.
     */
    @Test
    void aTimestampPropertyIsAcceptedBecauseItIsADate() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenTheEntityHasAPropertyOfType(modelProvider, Timestamp.class);
        assertDoesNotThrow(() -> UsageResourceValidator.validate(declarativeRow()));
      }
    }

    @Test
    void aPropertyThatIsNotASimpleColumnIsRejected() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        ModelProvider provider = mock(ModelProvider.class);
        Entity entity = mock(Entity.class);
        Property property = mock(Property.class);
        modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
        when(provider.getEntity(eq(ENTITY), anyBoolean())).thenReturn(entity);
        when(entity.getName()).thenReturn(ENTITY);
        when(entity.hasProperty(DATE_PROPERTY)).thenReturn(true);
        when(entity.getProperty(eq(DATE_PROPERTY), anyBoolean())).thenReturn(property);
        when(property.isPrimitive()).thenReturn(false);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validate(declarativeRow()));
        assertTrue(thrown.getMessage().contains("not a simple column"), thrown.getMessage());
      }
    }

    /**
     * The restriction is composed here with the very composition the job will run, so a fragment
     * that could escape its subquery is a save-time error rather than a nightly mis-attribution.
     * {@code UsageQueryComposerTest} owns the exhaustive fragment specs; this asserts only that
     * the validator actually performs the composition.
     */
    @Test
    void anUnsafeHqlRestrictionIsRejectedAtSaveTime() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        BillingResource row = declarativeRow();
        when(row.getHQLRestriction()).thenReturn("1=1) or (1=1");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validate(row));
        assertTrue(thrown.getMessage().contains("parentheses"), thrown.getMessage());
      }
    }
  }

  @Nested
  @DisplayName("strategy mode")
  class Strategy {

    @Test
    void aWellFormedRowWithADeployedCounterIsAccepted() {
      try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
        lookup.when(() -> UsageCounterLookup.isDeployed(QUALIFIER)).thenReturn(true);
        assertDoesNotThrow(() -> UsageResourceValidator.validate(strategyRow()));
      }
    }

    @ParameterizedTest(name = "qualifier = {0}")
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void aMissingQualifierIsRejected(String qualifier) {
      BillingResource row = resource(UsageResourceValidator.MODE_STRATEGY);
      when(row.getStrategyQualifier()).thenReturn(qualifier);

      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(row));
      assertTrue(thrown.getMessage().contains("Strategy Qualifier is required"),
          thrown.getMessage());
    }

    /**
     * The strategy owns its own counting rule, so a declarative descriptor alongside it would be
     * dead configuration that reads as if it were in force.
     */
    @Test
    void aStrategyRowCarryingACountedEntityIsRejected() {
      BillingResource row = strategyRow();
      when(row.getCountedEntity()).thenReturn(ENTITY);

      assertTrue(assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(row)).getMessage().contains("must be empty"));
    }

    @Test
    void aStrategyRowCarryingADatePropertyIsRejected() {
      BillingResource row = strategyRow();
      when(row.getDateProperty()).thenReturn(DATE_PROPERTY);

      assertTrue(assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(row)).getMessage().contains("must be empty"));
    }

    @Test
    void aStrategyRowCarryingAnHqlRestrictionIsRejected() {
      BillingResource row = strategyRow();
      when(row.getHQLRestriction()).thenReturn("e.posted = 'Y'");

      assertTrue(assertThrows(IllegalArgumentException.class,
          () -> UsageResourceValidator.validate(row)).getMessage().contains("must be empty"));
    }

    /**
     * The message is the feature here, not just the rejection. A typo'd qualifier is otherwise
     * indistinguishable from zero usage, and the two traps that produce it — a misspelling and a
     * counter wrongly annotated with a normal scope, which CDI then hides behind a client proxy
     * carrying no {@code @Named} — are both only diagnosable if the error lists what IS deployed.
     */
    @Test
    void anUndeployedQualifierIsRejectedAndTheMessageNamesTheDeployedOnes() {
      try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
        lookup.when(() -> UsageCounterLookup.isDeployed(anyString())).thenReturn(false);
        lookup.when(UsageCounterLookup::deployedQualifiers)
            .thenReturn("active-users, stored-documents");

        BillingResource row = resource(UsageResourceValidator.MODE_STRATEGY);
        when(row.getStrategyQualifier()).thenReturn("activeusers");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validate(row));

        assertAll(
            () -> assertTrue(thrown.getMessage().contains("activeusers"),
                "names the offending qualifier: " + thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("active-users, stored-documents"),
                "and lists the deployed ones: " + thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("@ApplicationScoped"),
                "and warns about the normal-scope trap: " + thrown.getMessage()));
      }
    }
  }

  /** Types a date property must never hold; each would bucket usage by something meaningless. */
  static java.util.stream.Stream<Class<?>> nonDateTypes() {
    return java.util.stream.Stream.of(String.class, Long.class, BigDecimal.class, Boolean.class);
  }
}
