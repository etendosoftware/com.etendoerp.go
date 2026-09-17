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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.QueryTimeoutException;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

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
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
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

  /**
   * A probe that blows up while COMPILING, at {@code createQuery}. Split from
   * {@link #givenAProbeThatFailsWhenRun} because compiling and running are now different code
   * paths reporting different things; a fixture that could only fail at one of them would let
   * the other go untested, which is how the two were flattened into one message to begin with.
   */
  private static void givenAProbeThatFailsToCompile(MockedStatic<OBDal> obDalStatic,
      RuntimeException failure) {
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), eq(Object[].class))).thenThrow(failure);
  }

  /** A probe that compiles cleanly and then blows up while EXECUTING, at {@code list()}. */
  @SuppressWarnings("unchecked")
  private static void givenAProbeThatFailsWhenRun(MockedStatic<OBDal> obDalStatic,
      RuntimeException failure) {
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    Query<Object[]> query = mock(Query.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.setMaxResults(anyInt())).thenReturn(query);
    when(query.setTimeout(anyInt())).thenReturn(query);
    when(query.list()).thenThrow(failure);
  }

  /**
   * Stubs the model so {@code entityName} resolves to an entity reporting that same name, with
   * whatever property behaviour the caller adds. Needed because the at-sign specs have to drive
   * the messages that quote the ENTITY name back, which the fixed-name helpers above cannot do.
   */
  private static void givenEntityNamed(String entityName,
      MockedStatic<ModelProvider> modelProvider, java.util.function.Consumer<Entity> properties) {
    ModelProvider provider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(eq(entityName), anyBoolean())).thenReturn(entity);
    when(entity.getName()).thenReturn(entityName);
    properties.accept(entity);
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
            () -> assertTrue(thrown.getMessage().contains("ApplicationScoped")
                && thrown.getMessage().contains("Named"),
                "and warns about the normal-scope trap, naming both annotations: "
                    + thrown.getMessage()),
            () -> assertFalse(thrown.getMessage().contains("@"),
                "without any at-sign, which Openbravo would parse as a message parameter: "
                    + thrown.getMessage()));
      }
    }
  }

  /**
   * Every message this class can produce has to survive Openbravo's error rendering, and the one
   * character that stops it is invisible to a human reading the string.
   *
   * <p>{@code OBMessageUtils.translateError} treats '@' as the delimiter of a message parameter —
   * its javadoc says it searches "the @ parameters" — so a message containing one is parsed as a
   * placeholder and can reach the user blank. The undeployed-qualifier message hit this in live
   * testing because it named the CDI annotations the way a developer writes them
   * ({@code @Named("...")}, {@code @ApplicationScoped}). The save was refused and the screen said
   * nothing.
   *
   * <p>Pinned across ALL of them rather than only the one that broke: the trap is generic, the
   * next message someone adds is just as exposed, and the failure mode is silence rather than an
   * error anyone would chase back here.
   *
   * <p><b>Two kinds of test, both needed.</b> The {@code noMessage...ContainsAnAtSign} sweeps feed
   * ordinary values and pin that our own FIXED WORDING stays clean — the regression where someone
   * rewrites a message to mention {@code @Named} "properly". The {@code anAtSignIn...} tests feed
   * every dynamic slot a value that genuinely CARRIES an at-sign, and so pin that the
   * {@link UsageMessages#atSafe} guard is actually applied: interpolate a value raw and the
   * assertion fails. An at-sign-free fixture cannot tell those two apart, which is why both exist.
   * Every message with a dynamic slot is covered by the second kind; the rest have no interpolated
   * value to feed.
   *
   * <p>Asserting the CALL instead (a mocked {@code UsageMessages} static) was considered and
   * rejected: it would pin a helper NAME rather than the behaviour, and would break on an inlining
   * that changed nothing a user can see. What reaches the screen is the contract; how it got there
   * is not.
   */
  @Nested
  @DisplayName("no rejection message may contain an at-sign")
  class MessagesSurviveOpenbravoRendering {

    /** Rejects, and its reason contains no '@'. */
    private void assertRejectedWithoutAnAtSign(org.junit.jupiter.api.function.Executable rejection,
        String what) {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, rejection, what + " must be rejected");
      assertFalse(thrown.getMessage().contains("@"),
          what + " produces a message Openbravo would eat: " + thrown.getMessage());
    }

    @Test
    void noMessageTheModeChecksProduceContainsAnAtSign() {
      assertAll(
          () -> assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(null),
              "a null resource"),
          () -> assertRejectedWithoutAnAtSign(
              () -> UsageResourceValidator.validate(resource("X")), "an unknown counting mode"));
    }

    @Test
    void noMessageTheDeclarativeChecksProduceContainsAnAtSign() {
      assertAll(() -> {
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getDateProperty()).thenReturn(DATE_PROPERTY);
        assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(row),
            "a missing counted entity");
      }, () -> {
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn(ENTITY);
        assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(row),
            "a missing date property");
      }, () -> {
        BillingResource row = declarativeRow();
        when(row.getStrategyQualifier()).thenReturn(QUALIFIER);
        assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(row),
            "a declarative row carrying a qualifier");
      }, () -> {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
          givenNoEntityIsFound(modelProvider);
          assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(declarativeRow()),
              "an entity that is not in the model");
        }
      }, () -> {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
          ModelProvider provider = mock(ModelProvider.class);
          Entity entity = mock(Entity.class);
          modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
          when(provider.getEntity(eq(ENTITY), anyBoolean())).thenReturn(entity);
          when(entity.getName()).thenReturn(ENTITY);
          when(entity.hasProperty(DATE_PROPERTY)).thenReturn(false);
          assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(declarativeRow()),
              "a date property that does not exist");
        }
      }, () -> {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
          givenTheEntityHasAPropertyOfType(modelProvider, String.class);
          assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(declarativeRow()),
              "a date property that is not a date");
        }
      }, () -> {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
          givenTheEntityHasADateProperty(modelProvider);
          BillingResource row = declarativeRow();
          when(row.getHQLRestriction()).thenReturn("1=1) or (1=1");
          assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(row),
              "an unsafe HQL restriction");
        }
      });
    }

    @Test
    void noMessageTheStrategyChecksProduceContainsAnAtSign() {
      assertAll(() -> {
        BillingResource row = resource(UsageResourceValidator.MODE_STRATEGY);
        assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(row),
            "a missing qualifier");
      }, () -> {
        BillingResource row = strategyRow();
        when(row.getCountedEntity()).thenReturn(ENTITY);
        assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(row),
            "a strategy row carrying a declarative descriptor");
      }, () -> {
        try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
          lookup.when(() -> UsageCounterLookup.isDeployed(anyString())).thenReturn(false);
          lookup.when(UsageCounterLookup::deployedQualifiers).thenReturn("active-users");
          assertRejectedWithoutAnAtSign(() -> UsageResourceValidator.validate(strategyRow()),
              "an undeployed qualifier");
        }
      });
    }

    /**
     * Everything an at-sign-bearing value owes the reader, asserted for each value fed in.
     *
     * <p>Three assertions, and the third is the one that makes this more than a re-run of
     * {@code UsageMessagesTest}: the value must appear in its SUBSTITUTED form and must NOT appear
     * stripped. A guard that deleted the character instead of replacing it would leave a message
     * naming {@code FOOBAR} — a resource, entity or qualifier that does not exist — and send the
     * reader after a value they never typed. The expected forms are computed here with plain
     * string replacement rather than by calling the production helper, so the assertion cannot
     * become tautological if that helper changes.
     */
    private void assertNeutralised(org.junit.jupiter.api.function.Executable rejection,
        String what, String... rawValues) {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, rejection, what + " must be rejected");
      String message = thrown.getMessage();
      assertAll(
          () -> assertFalse(message.contains("@"),
              what + " leaves an at-sign that can blank the whole message: " + message),
          () -> assertAll(java.util.Arrays.stream(rawValues)
              .map(raw -> (org.junit.jupiter.api.function.Executable) () -> assertAll(
                  () -> assertTrue(message.contains(raw.replace("@", "(at)")),
                      "'" + raw + "' must stay recognisable in " + what + ": " + message),
                  () -> assertFalse(message.contains(raw.replace("@", "")),
                      "'" + raw + "' was stripped rather than replaced in " + what + ", naming a"
                          + " value that does not exist: " + message)))));
    }

    /**
     * The mode-level messages. Only the unknown-mode one interpolates anything — the rest ("a
     * billing resource is required", the blank-field and mutual-exclusion rules) are fixed wording
     * with no dynamic slot to feed, and are covered by the sweeps above.
     */
    @Test
    void anAtSignInTheCountingModeIsNeutralised() {
      assertNeutralised(() -> UsageResourceValidator.validate(resource("MODE@X")),
          "an unknown counting mode", "MODE@X");
    }

    /**
     * The counted entity is free text an administrator types into the catalog, so it is exactly as
     * exposed as the search key — a row naming {@code FOO@BAR} would have produced the blank popup
     * this whole guard exists for.
     */
    @Test
    void anAtSignInTheCountedEntityIsNeutralised() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenNoEntityIsFound(modelProvider);
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn("FOO@BAR");
        when(row.getDateProperty()).thenReturn(DATE_PROPERTY);

        assertNeutralised(() -> UsageResourceValidator.validate(row),
            "an entity that is not in the model", "FOO@BAR");
      }
    }

    /**
     * Both slots of the missing-property message at once: the property the user typed AND the
     * entity name it is reported against. Guarding one and not the other would be the easy miss.
     */
    @Test
    void anAtSignInTheDatePropertyOrItsEntityIsNeutralised() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        givenEntityNamed("FOO@BAR", modelProvider, entity -> when(entity.hasProperty("date@x"))
            .thenReturn(false));
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn("FOO@BAR");
        when(row.getDateProperty()).thenReturn("date@x");

        assertNeutralised(() -> UsageResourceValidator.validate(row),
            "a date property that does not exist", "date@x", "FOO@BAR");
      }
    }

    /** The property exists but is not a simple column: a third message, the same two slots. */
    @Test
    void anAtSignInTheNotASimpleColumnMessageIsNeutralised() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        Property property = mock(Property.class);
        when(property.isPrimitive()).thenReturn(false);
        givenEntityNamed("FOO@BAR", modelProvider, entity -> {
          when(entity.hasProperty("date@x")).thenReturn(true);
          when(entity.getProperty(eq("date@x"), anyBoolean())).thenReturn(property);
        });
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn("FOO@BAR");
        when(row.getDateProperty()).thenReturn("date@x");

        assertNeutralised(() -> UsageResourceValidator.validate(row),
            "a property that is not a simple column", "date@x", "FOO@BAR");
      }
    }

    /** And the fourth: the property is a column, but it does not hold a date. */
    @Test
    void anAtSignInTheNotADateMessageIsNeutralised() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
        Property property = mock(Property.class);
        when(property.isPrimitive()).thenReturn(true);
        when(property.getPrimitiveObjectType()).thenAnswer(invocation -> String.class);
        givenEntityNamed("FOO@BAR", modelProvider, entity -> {
          when(entity.hasProperty("date@x")).thenReturn(true);
          when(entity.getProperty(eq("date@x"), anyBoolean())).thenReturn(property);
        });
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn("FOO@BAR");
        when(row.getDateProperty()).thenReturn("date@x");

        assertNeutralised(() -> UsageResourceValidator.validate(row),
            "a property that is not a date", "date@x", "FOO@BAR");
      }
    }

    /**
     * THE GUARD, exercised with values that genuinely carry at-signs.
     *
     * <p>The sweeps above prove our own fixed wording is clean. They cannot prove the guard is
     * APPLIED, because an at-sign-free fixture produces an at-sign-free message either way. These
     * do: every dynamic slot the message interpolates is fed a value containing an at-sign, so a
     * message that interpolated it raw would fail here.
     *
     * <p>The substituted form is asserted alongside the absence, because deleting the operator's
     * information is not a fix — a qualifier reported as {@code activeusers} when they typed
     * {@code active@users} sends them looking for a typo they did not make.
     */
    @Test
    void anAtSignInTheQualifierOrTheDeployedListIsNeutralisedRatherThanLost() {
      try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class)) {
        lookup.when(() -> UsageCounterLookup.isDeployed(anyString())).thenReturn(false);
        lookup.when(UsageCounterLookup::deployedQualifiers).thenReturn("active@users, stored@docs");

        BillingResource row = resource(UsageResourceValidator.MODE_STRATEGY);
        when(row.getStrategyQualifier()).thenReturn("billing@acme");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validate(row));

        assertAll(
            () -> assertFalse(thrown.getMessage().contains("@"), thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("billing(at)acme"),
                "the qualifier they typed must still be recognisable: " + thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("active(at)users"),
                "and so must the deployed list: " + thrown.getMessage()),
            () -> assertFalse(thrown.getMessage().contains("billingacme"),
                "stripping would name a qualifier they never typed: " + thrown.getMessage()));
      }
    }

    /**
     * The compile message is the most exposed of all: it quotes the composed HQL back verbatim,
     * so any at-sign a fragment carries — an email literal is the obvious one — travels with it,
     * as does whatever Hibernate put in the root cause. All three of its dynamic parts are fed
     * at-signs here.
     */
    @Test
    void anAtSignInTheComposedHqlOrTheRootCauseIsNeutralised() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        givenAProbeThatFailsToCompile(obDalStatic, new IllegalStateException("wrapper",
            new IllegalStateException("could not resolve property: osted@Invoice")));
        BillingResource row = declarativeRow();
        when(row.getHQLRestriction()).thenReturn("e.description = 'billing@acme.com'");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(row));

        assertAll(
            () -> assertFalse(thrown.getMessage().contains("@"), thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("billing(at)acme.com"),
                "the fragment must still be quoted back readably: " + thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("osted(at)Invoice"),
                "and the root cause with it: " + thrown.getMessage()));
      }
    }

    /** And the third probe path, whose root cause is guarded the same way. */
    @Test
    void anAtSignInAnExecutionFailureIsNeutralised() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        givenAProbeThatFailsWhenRun(obDalStatic, new IllegalStateException("wrapper",
            new IllegalStateException("connection to db@host refused")));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(declarativeRow()));

        assertAll(() -> assertFalse(thrown.getMessage().contains("@"), thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("db(at)host"), thrown.getMessage()));
      }
    }


    /**
     * The probe's three failures are on the same road to the user — the save-time observer wraps
     * them in {@code OBException} exactly as it wraps the checks above — so they are exposed to
     * the same trap, and the compile message is the most exposed of all: it quotes the composed
     * HQL back verbatim, so any at-sign a fragment ever carries would travel with it.
     */
    @Test
    void noMessageTheProbeCanProduceContainsAnAtSign() {
      assertAll(() -> {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
            MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
            MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
          givenTheEntityHasADateProperty(modelProvider);
          givenAProbeThatFailsToCompile(obDalStatic, new IllegalStateException("wrapper",
              new IllegalStateException("could not resolve property: osted of: Invoice")));
          assertRejectedWithoutAnAtSign(
              () -> UsageResourceValidator.validateAndProbe(declarativeRow()),
              "a fragment that cannot compile");
        }
      }, () -> {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
            MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
            MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
          givenTheEntityHasADateProperty(modelProvider);
          givenAProbeThatFailsWhenRun(obDalStatic, new QueryTimeoutException("statement timeout",
              new SQLException("canceling statement due to statement timeout"), "select ..."));
          assertRejectedWithoutAnAtSign(
              () -> UsageResourceValidator.validateAndProbe(declarativeRow()),
              "a probe that times out");
        }
      }, () -> {
        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
            MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
            MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
          givenTheEntityHasADateProperty(modelProvider);
          givenAProbeThatFailsWhenRun(obDalStatic,
              new IllegalStateException("wrapper", new IllegalStateException("connection closed")));
          assertRejectedWithoutAnAtSign(
              () -> UsageResourceValidator.validateAndProbe(declarativeRow()),
              "a probe that fails to run");
        }
      });
    }
  }

  /**
   * Specs for {@link UsageResourceValidator#validateAndProbe(BillingResource)}, the entry point
   * the save-time observer calls (acceptance criterion #6).
   *
   * <p>Validation existing as a callable method was never the gap — the gap Alex found is that
   * nothing CALLED it, so every rejection below was reachable only by a nightly job. These tests
   * pin the wired entry point specifically: each of the three malformed rows must be refused
   * BEFORE any query runs, which is also what makes them testable without a database.
   *
   * <p>The probe itself (timing the composed query) is the one part that needs a session, so it
   * is exercised with a stubbed one — enough to pin that a successful probe records its cost and
   * that a failing probe aborts the save rather than storing a resource that cannot run.
   */
  @Nested
  @DisplayName("validateAndProbe — the save-time entry point")
  class ValidateAndProbe {

    @Test
    void refusesAnUnknownEntityWithoutRunningAnyQuery() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        givenNoEntityIsFound(modelProvider);
        BillingResource row = resource(UsageResourceValidator.MODE_DECLARATIVE);
        when(row.getCountedEntity()).thenReturn("C_Invoice");
        when(row.getDateProperty()).thenReturn(DATE_PROPERTY);

        assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(row));
        obDal.verifyNoInteractions();
      }
    }

    @Test
    void refusesAMalformedFragmentWithoutRunningAnyQuery() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        BillingResource row = declarativeRow();
        when(row.getHQLRestriction()).thenReturn("1=1) or (1=1");

        assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(row));
        obDal.verifyNoInteractions();
      }
    }

    @Test
    void refusesAnUndeployedQualifierWithoutRunningAnyQuery() {
      try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        lookup.when(() -> UsageCounterLookup.isDeployed(anyString())).thenReturn(false);
        lookup.when(UsageCounterLookup::deployedQualifiers).thenReturn("(none deployed)");

        assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(strategyRow()));
        obDal.verifyNoInteractions();
      }
    }

    /**
     * A strategy resource has no composed query to time, so it is stamped as validated and the
     * probe is skipped entirely — timing nothing would record a meaningless zero.
     */
    @Test
    void aStrategyRowIsStampedButNotProbed() {
      try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        lookup.when(() -> UsageCounterLookup.isDeployed(QUALIFIER)).thenReturn(true);
        BillingResource row = strategyRow();

        UsageResourceValidator.validateAndProbe(row);

        verify(row).setLastValidated(any(Date.class));
        verify(row, never()).setLastValidationMs(any());
        obDal.verifyNoInteractions();
      }
    }

    /**
     * A well-formed declarative row is probed and its cost recorded. {@code LAST_VALIDATION_MS} is
     * what makes a fragment that would table-scan every tenant nightly visible at configuration
     * time rather than at 02:00, so the stamp is the feature, not bookkeeping.
     */
    @Test
    void aDeclarativeRowIsProbedAndItsCostRecorded() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        givenAProbeThatSucceeds(obDalStatic);
        BillingResource row = declarativeRow();

        UsageResourceValidator.validateAndProbe(row);

        verify(row).setLastValidated(any(Date.class));
        verify(row).setLastValidationMs(any(Long.class));
      }
    }

    /**
     * THE REGRESSION TEST for a defect found in live testing: a user typed {@code e.osted = 'Y'}
     * — a typo, the {@code p} missing — and was told the query "could not be executed, or took
     * longer than 10s ... which is too expensive to schedule nightly". A typo reported as a
     * performance problem, so whoever reads it goes looking for an index that would not have
     * helped. The cause was one {@code catch (RuntimeException)} around both the compile and the
     * execution, which flattened two unrelated failures into one sentence.
     *
     * <p>So the assertion that matters here is the NEGATIVE one: a fragment that cannot parse must
     * name the entity and the offending property and must say nothing whatsoever about cost or
     * timing. A test that merely checked "some explanatory message came back" would have passed
     * against the defect, which is exactly why one did.
     *
     * <p>The failure is stubbed WRAPPED, because that is the shape Hibernate actually throws:
     * only the innermost cause names the property, so the message is worthless unless the
     * validator walks to the bottom of the chain.
     */
    @Test
    void aFragmentThatCannotCompileIsReportedAsATypoNotAsACostProblem() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        givenAProbeThatFailsToCompile(obDalStatic, new IllegalStateException("wrapper",
            new IllegalStateException("could not resolve property: osted of: Invoice")));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(declarativeRow()));

        assertAll(
            () -> assertTrue(
                thrown.getMessage().contains("HQL Restriction is not valid for entity '" + ENTITY),
                thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("could not resolve property: osted"),
                "the innermost cause names the property, so it must survive: "
                    + thrown.getMessage()),
            () -> assertFalse(thrown.getMessage().contains("too expensive"),
                "a typo must not be reported as a performance problem: " + thrown.getMessage()),
            () -> assertFalse(thrown.getMessage().contains("longer than"),
                "nor as a timeout: " + thrown.getMessage()));
      }
    }

    /**
     * The cost guard doing its job, and the only failure that may talk about expense: the fragment
     * parsed, so it is valid — it is simply too slow to run nightly across every tenant. The
     * message has to point at the remedy that actually applies here (narrow it, or index the date
     * property), which is precisely the advice that was wasted when a typo received it.
     */
    @Test
    void aProbeThatTimesOutIsReportedAsACostProblem() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        givenAProbeThatFailsWhenRun(obDalStatic, new QueryTimeoutException("statement timeout",
            new SQLException("canceling statement due to statement timeout"), "select ..."));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(declarativeRow()));

        assertAll(
            () -> assertTrue(thrown.getMessage().contains("too expensive"), thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("add an index"),
                "the remedy must be the one that fits a timeout: " + thrown.getMessage()),
            () -> assertFalse(thrown.getMessage().contains("HQL Restriction is not valid"),
                "a valid but slow fragment must not be called invalid: " + thrown.getMessage()));
      }
    }

    /**
     * The third path, and the reason it exists: something that compiles and then fails at
     * execution for a reason that is neither a typo nor the cost guard — a permission, a missing
     * column, a dead connection. Folding it into either of the other two messages would send the
     * reader after the wrong cause, which is the same mistake in a different direction. The save
     * still aborts: storing the row would leave a catalog entry that looks configured and fails
     * every night thereafter.
     */
    @Test
    void aProbeThatFailsToRunForAnyOtherReasonSaysSoWithoutBlamingTheFragment() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        givenAProbeThatFailsWhenRun(obDalStatic, new IllegalStateException("wrapper",
            new IllegalStateException("connection is closed")));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(declarativeRow()));

        assertAll(
            () -> assertTrue(thrown.getMessage().contains("compiled but could not be run"),
                thrown.getMessage()),
            () -> assertTrue(thrown.getMessage().contains("connection is closed"),
                "the underlying cause must survive: " + thrown.getMessage()),
            () -> assertFalse(thrown.getMessage().contains("too expensive"),
                "and it is not the cost guard: " + thrown.getMessage()));
      }
    }

    /** Admin mode is restored even when the probe blows up. */
    @Test
    void adminModeIsRestoredAfterAFailedProbe() {
      try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenTheEntityHasADateProperty(modelProvider);
        givenAProbeThatFailsToCompile(obDalStatic, new IllegalStateException("boom"));

        assertThrows(IllegalArgumentException.class,
            () -> UsageResourceValidator.validateAndProbe(declarativeRow()));

        obContext.verify(OBContext::restorePreviousMode);
      }
    }

    @SuppressWarnings("unchecked")
    private void givenAProbeThatSucceeds(MockedStatic<OBDal> obDalStatic) {
      OBDal obDal = mock(OBDal.class);
      Session session = mock(Session.class);
      Query<Object[]> query = mock(Query.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.getSession()).thenReturn(session);
      when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      // setMaxResults and setTimeout are both stubbed to chain whether or not the probe currently
      // calls them, so this fixture survives the pending change that drops the LIMIT (it times
      // "first group", not the nightly cost) and adds a timeout (so a pathological fragment is
      // rejected rather than hanging the user's save).
      when(query.setMaxResults(anyInt())).thenReturn(query);
      when(query.setTimeout(anyInt())).thenReturn(query);
      when(query.list()).thenReturn(java.util.Collections.emptyList());
    }

  }


  /** Types a date property must never hold; each would bucket usage by something meaningless. */
  static java.util.stream.Stream<Class<?>> nonDateTypes() {
    return java.util.stream.Stream.of(String.class, Long.class, BigDecimal.class, Boolean.class);
  }
}