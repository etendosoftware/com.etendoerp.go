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

import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;

/**
 * A {@link UsageResourceCounter} deployed the way a real implementer would deploy one, used by
 * {@link UsageAggregationServiceIntegrationTest} to prove the strategy extension point works
 * end to end.
 *
 * <p><b>This is a top-level class on purpose, not a nested class inside the test.</b> The test
 * runs under Arquillian, which builds the bean archive by importing the compiled class
 * directories (see {@code WeldBaseTest.createTestArchive()}); module test sources are compiled
 * into {@code <core>/src-test/build/classes}, which is one of the imported directories, so this
 * class is discovered exactly like a production bean. A {@code public static} nested class would
 * also be a legal managed bean and its {@code Outer$Nested.class} file would be imported too, but
 * it would ride on Arquillian's handling of the test class that encloses it. Keeping the counter
 * standalone means the thing under test is a plain class in a bean archive — which is precisely
 * the claim the test is supposed to prove — with nothing about the test's own lifecycle mixed in.
 *
 * <p><b>Annotated {@code @Named} and nothing else.</b> Adding {@code @ApplicationScoped}, the
 * natural instinct, would make it silently invisible: Weld would serve it through a client proxy
 * whose subclass does not carry the non-{@code @Inherited} qualifier, and the lookup — which
 * matches on the CDI bean name — would skip it. The same trap regressed before in ETP-4244 for
 * {@code NeoHandler}. If this class is ever "tidied up" with a scope annotation, the integration
 * test that depends on it should fail, and that failure is correct.
 */
@Named(UsageTestCounter.QUALIFIER)
public class UsageTestCounter implements UsageResourceCounter {

  /** The {@code ETGO_BILLING_RESOURCE.Strategy_Qualifier} a catalog row points at. */
  public static final String QUALIFIER = "etp5050-test-usage-counter";

  /**
   * The tenant the fabricated count is attributed to. Must be a real client, because the usage
   * row carries a foreign key to it. This is {@code OBBaseTest.TEST_CLIENT_ID}, spelled out here
   * because that constant is {@code protected} and out of reach from a non-test class; the test
   * asserts the two are still equal, so the duplication cannot rot silently.
   */
  public static final String MEASURED_CLIENT_ID = "23C59575B9CF467C9620760EB255B389";

  /** A value no real count would produce, so a passing assertion cannot be a coincidence. */
  public static final long QUANTITY = 4242L;

  @Override
  public List<DailyCount> count(UsageCountRequest request) {
    List<DailyCount> counts = new ArrayList<>();
    counts.add(new DailyCount(MEASURED_CLIENT_ID, request.getFrom(), QUANTITY));
    return counts;
  }
}
