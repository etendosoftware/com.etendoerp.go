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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Named;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.weld.WeldUtils;

/**
 * Unit specs for {@link UsageCounterLookup} (ETP-5050).
 *
 * <p>Following the precedent in {@code NeoServletSupportTest}: a Mockito mock of
 * {@link UsageResourceCounter} does NOT carry a real {@code @Named} annotation, so the fakes below
 * are small REAL classes annotated exactly as an implementer would annotate a production counter.
 * The qualifier the lookup is asked for is then read back off the annotation
 * ({@code FakeUsageCounter.class.getAnnotation(Named.class).value()}) rather than retyped as a
 * literal, so the test cannot drift from the annotation it is supposed to be exercising.
 *
 * <p>{@code WeldUtils} is mocked statically to stand in for the CDI container, which is not
 * running in the unit-test JVM. What is stubbed is deliberately faithful to the production
 * resolution path: the lookup matches on {@link Bean#getName()}, the CDI-standard reading of
 * {@code @Named}, and NOT on the annotation of the concrete class. That difference is the whole
 * point of the design — reading the annotation off {@code bean.getClass()} would silently miss any
 * normal-scoped bean, because Weld serves those through a client proxy whose subclass does not
 * inherit the (non-{@code @Inherited}) {@code @Named}. The same trap regressed before in ETP-4244
 * for {@code NeoHandler}.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class UsageCounterLookupTest {

  /** A real counter annotated exactly as a production implementer would annotate one. */
  @Named("test-usage-counter")
  private static class FakeUsageCounter implements UsageResourceCounter {
    @Override
    public List<DailyCount> count(UsageCountRequest request) {
      return Collections.emptyList();
    }
  }

  /** A second deployed counter, so "lists them all" has something to list. */
  @Named("other-usage-counter")
  private static class OtherFakeUsageCounter implements UsageResourceCounter {
    @Override
    public List<DailyCount> count(UsageCountRequest request) {
      return Collections.emptyList();
    }
  }

  /** The qualifier under test, read off the annotation so the two can never drift apart. */
  private static final String QUALIFIER =
      FakeUsageCounter.class.getAnnotation(Named.class).value();
  private static final String OTHER_QUALIFIER =
      OtherFakeUsageCounter.class.getAnnotation(Named.class).value();

  private BeanManager beanManager;

  /**
   * Registers the given counters with the stubbed container, each behind a {@code Bean} whose
   * {@code getName()} is its {@code @Named} value — which is how CDI itself exposes the
   * annotation, and what the lookup matches on.
   */
  @SuppressWarnings("unchecked")
  private void givenDeployedCounters(MockedStatic<WeldUtils> weld,
      UsageResourceCounter... counters) {
    beanManager = mock(BeanManager.class);
    weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(beanManager);

    Set<Bean<?>> beans = new LinkedHashSet<>();
    for (UsageResourceCounter counter : counters) {
      Named named = counter.getClass().getAnnotation(Named.class);
      Bean<UsageResourceCounter> bean = mock(Bean.class);
      when(bean.getName()).thenReturn(named == null ? null : named.value());
      CreationalContext<UsageResourceCounter> context = mock(CreationalContext.class);
      when(beanManager.createCreationalContext(bean)).thenReturn((CreationalContext) context);
      when(beanManager.getReference(eq(bean), eq(UsageResourceCounter.class), any()))
          .thenReturn(counter);
      beans.add(bean);
    }
    when(beanManager.getBeans(eq(UsageResourceCounter.class), eq(WeldUtils.ANY_LITERAL)))
        .thenReturn(beans);
  }

  @Nested
  @DisplayName("byQualifier")
  class ByQualifier {

    @Test
    void findsTheCounterWhoseNamedValueMatches() {
      FakeUsageCounter counter = new FakeUsageCounter();
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld, counter);
        assertSame(counter, UsageCounterLookup.byQualifier(QUALIFIER));
      }
    }

    @Test
    void picksTheRightOneOutOfSeveralDeployedCounters() {
      FakeUsageCounter wanted = new FakeUsageCounter();
      OtherFakeUsageCounter other = new OtherFakeUsageCounter();
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld, other, wanted);
        assertAll(() -> assertSame(wanted, UsageCounterLookup.byQualifier(QUALIFIER)),
            () -> assertSame(other, UsageCounterLookup.byQualifier(OTHER_QUALIFIER)));
      }
    }

    @Test
    void returnsNullWhenNoDeployedCounterCarriesTheQualifier() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld, new FakeUsageCounter());
        assertNull(UsageCounterLookup.byQualifier("no-such-counter"));
      }
    }

    @Test
    void returnsNullWhenNothingIsDeployedAtAll() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld);
        assertNull(UsageCounterLookup.byQualifier(QUALIFIER));
      }
    }

    /** Matching is exact: a near-miss must not silently resolve to the wrong counter. */
    @ParameterizedTest(name = "qualifier = \"{0}\"")
    @ValueSource(strings = { "TEST-USAGE-COUNTER", "test-usage-counter ", "test_usage_counter",
        "test-usage" })
    void doesNotMatchApproximately(String nearMiss) {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld, new FakeUsageCounter());
        assertNull(UsageCounterLookup.byQualifier(nearMiss));
      }
    }

    /**
     * A blank qualifier is answered without touching the container at all. A declarative resource
     * leaves the column empty, and resolving "" against CDI would be a pointless round trip whose
     * outcome depends on what else happens to be deployed.
     */
    @ParameterizedTest(name = "qualifier = {0}")
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void aBlankQualifierYieldsNullWithoutConsultingTheContainer(String blank) {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        assertNull(UsageCounterLookup.byQualifier(blank));
        weld.verify(WeldUtils::getStaticInstanceBeanManager, never());
      }
    }
  }

  @Nested
  @DisplayName("isDeployed")
  class IsDeployed {

    /**
     * The probe must answer without instantiating the counter: it runs on every save of a catalog
     * row, and constructing an arbitrary implementer's bean as a side effect of validation would
     * be a surprising thing for a save to do.
     */
    @Test
    void isTrueForADeployedQualifierAndDoesNotInstantiateTheBean() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld, new FakeUsageCounter());

        assertTrue(UsageCounterLookup.isDeployed(QUALIFIER));
        verify(beanManager, never()).getReference(any(), any(), any());
      }
    }

    @Test
    void isFalseForAQualifierNothingCarries() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld, new FakeUsageCounter());
        assertFalse(UsageCounterLookup.isDeployed("no-such-counter"));
      }
    }

    @Test
    void isFalseWhenNothingIsDeployedAtAll() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld);
        assertFalse(UsageCounterLookup.isDeployed(QUALIFIER));
      }
    }

    @ParameterizedTest(name = "qualifier = {0}")
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void aBlankQualifierIsNotDeployed(String blank) {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        assertFalse(UsageCounterLookup.isDeployed(blank));
        weld.verify(WeldUtils::getStaticInstanceBeanManager, never());
      }
    }
  }

  @Nested
  @DisplayName("deployedQualifiers")
  class DeployedQualifiers {

    @Test
    void listsEveryDeployedQualifier() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld, new FakeUsageCounter(), new OtherFakeUsageCounter());

        String listed = UsageCounterLookup.deployedQualifiers();

        assertAll(() -> assertTrue(listed.contains(QUALIFIER), listed),
            () -> assertTrue(listed.contains(OTHER_QUALIFIER), listed),
            () -> assertTrue(listed.contains(", "), "separated for a readable message: " + listed));
      }
    }

    /**
     * The empty answer is a sentence, not an empty string: this text is pasted into the
     * save-time error, where "Deployed qualifiers: " followed by nothing reads as a bug in the
     * message rather than as "you have deployed none".
     */
    @Test
    void saysSoExplicitlyWhenNothingIsDeployed() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCounters(weld);
        assertEquals("(none deployed)", UsageCounterLookup.deployedQualifiers());
      }
    }

    /**
     * Listing the options is a diagnostic aid, so it must never be the thing that fails. If the
     * container cannot be reached the method degrades to a placeholder rather than throwing out
     * of the error path it was called from.
     */
    @Test
    void degradesToAPlaceholderWhenTheContainerIsUnavailable() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        weld.when(WeldUtils::getStaticInstanceBeanManager)
            .thenThrow(new IllegalStateException("no CDI container"));
        assertEquals("(unavailable)", UsageCounterLookup.deployedQualifiers());
      }
    }

    /** An unnamed bean contributes nothing rather than a stray "null" in the message. */
    @Test
    void skipsABeanWithNoName() {
      try (MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
        givenDeployedCountersIncludingAnUnnamedOne(weld);
        String listed = UsageCounterLookup.deployedQualifiers();
        assertAll(() -> assertTrue(listed.contains(QUALIFIER), listed),
            () -> assertFalse(listed.contains("null"), listed));
      }
    }

    @SuppressWarnings("unchecked")
    private void givenDeployedCountersIncludingAnUnnamedOne(MockedStatic<WeldUtils> weld) {
      BeanManager bm = mock(BeanManager.class);
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);

      Bean<UsageResourceCounter> named = mock(Bean.class);
      when(named.getName()).thenReturn(QUALIFIER);
      Bean<UsageResourceCounter> unnamed = mock(Bean.class);
      when(unnamed.getName()).thenReturn(null);

      when(bm.getBeans(eq(UsageResourceCounter.class), eq(WeldUtils.ANY_LITERAL)))
          .thenReturn(new LinkedHashSet<>(Arrays.asList(named, unnamed)));
    }
  }
}
