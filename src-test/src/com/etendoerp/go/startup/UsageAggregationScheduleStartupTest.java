/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.OBScheduler;

import com.etendoerp.go.common.GoRuntimeProperties;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Specs for {@link UsageAggregationScheduleStartup}: the System process request of the usage
 * aggregation is planted only while the {@code usage-aggregation-schedule} flag is on, once, under
 * its fixed id, at the configured cadence (default two hours).
 *
 * <p>The real scheduler and the flag provider are kept out by overriding the package-visible seams
 * the class offers; the DAL is mocked. Same shape as {@link BillingReconciliationScheduleStartupTest}.
 *
 * @covers com.etendoerp.go.startup.UsageAggregationScheduleStartup
 */
public class UsageAggregationScheduleStartupTest {

  private static final String SYSTEM = "0";
  private static final String INTERVAL_PROPERTY = "etendo.go.usage.schedule.interval.minutes";

  private boolean flagOn;
  private boolean requestExists;
  private int registrations;
  private int ensureCalls;
  private Integer configuredInterval;

  private OBDal dal;
  private OBProvider provider;
  private Process process;
  private ProcessRequest planted;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBProvider> providerStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<GoRuntimeProperties> properties;

  private final UsageAggregationScheduleStartup startup = new UsageAggregationScheduleStartup() {
    @Override
    boolean scheduleEnabled() {
      return flagOn;
    }

    @Override
    boolean activeScheduledRequestExists() {
      return requestExists;
    }

    @Override
    boolean ensureScheduled() {
      ensureCalls++;
      return super.ensureScheduled();
    }

    @Override
    void registerWithScheduler() {
      registrations++;
    }
  };

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    provider = mock(OBProvider.class);
    process = mock(Process.class);
    planted = mock(ProcessRequest.class);
    dalStatic = mockStatic(OBDal.class);
    providerStatic = mockStatic(OBProvider.class);
    contextStatic = mockStatic(OBContext.class);
    properties = mockStatic(GoRuntimeProperties.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    providerStatic.when(OBProvider::getInstance).thenReturn(provider);
    when(provider.get(ProcessRequest.class)).thenReturn(planted);
    when(dal.get(Process.class, UsageAggregationScheduleStartup.PROCESS_ID)).thenReturn(process);
    when(dal.get(Client.class, SYSTEM)).thenReturn(mock(Client.class));
    when(dal.get(Organization.class, SYSTEM)).thenReturn(mock(Organization.class));
    when(dal.get(User.class, "100")).thenReturn(mock(User.class));
    // null = nothing configured: readInt answers with its default, as the real one does.
    properties.when(() -> GoRuntimeProperties.readInt(anyString(), anyString(), anyInt()))
        .thenAnswer(call -> INTERVAL_PROPERTY.equals(call.getArgument(0))
            && configuredInterval != null ? configuredInterval
                : call.getArgument(2, Integer.class));
    properties.when(() -> GoRuntimeProperties.readValue(anyString(), anyString(), anyString()))
        .thenReturn("");
  }

  @After
  public void tearDown() {
    properties.close();
    contextStatic.close();
    providerStatic.close();
    dalStatic.close();
  }

  @Test
  public void withTheFlagOffNothingIsPlanted() {
    flagOn = false;

    startup.runPass();

    assertEquals(0, ensureCalls);
    assertEquals(0, registrations);
    verify(provider, never()).get(ProcessRequest.class);
    verify(dal, never()).save(any());
    verify(dal, never()).commitAndClose();
  }

  @Test
  public void withTheFlagOnTheFixedSystemRequestIsPlantedEveryTwoHoursByDefault() {
    flagOn = true;

    startup.runPass();

    verify(planted).setId(UsageAggregationScheduleStartup.REQUEST_ID);
    verify(planted).setProcess(process);
    verify(planted).setStatus("SCH");
    verify(planted).setTiming("S");
    verify(planted).setFrequency("2");
    verify(planted).setIntervalInMinutes(120L);
    verify(planted).setActive(true);
    verify(dal).save(planted);
    verify(dal).commitAndClose();
    assertEquals(1, registrations);
  }

  @Test
  public void theCadenceComesFromConfiguration() {
    flagOn = true;
    configuredInterval = 30;

    startup.runPass();

    verify(planted).setIntervalInMinutes(30L);
  }

  /** A cadence below the floor would let one run overlap the next; it is raised to 15 minutes. */
  @Test
  public void aCadenceBelowTheFloorIsPlantedAtTheFloor() {
    flagOn = true;
    configuredInterval = 5;

    startup.runPass();

    verify(planted).setIntervalInMinutes(15L);
  }

  @Test
  public void anActiveScheduledRequestIsNotPlantedTwice() {
    flagOn = true;
    requestExists = true;

    startup.runPass();

    assertEquals(1, ensureCalls);
    verify(provider, never()).get(ProcessRequest.class);
    verify(dal, never()).save(any());
    verify(dal, never()).commitAndClose();
    assertEquals(0, registrations);
  }

  /**
   * An operator unscheduled the fixed request (status UNS): it is left alone, never inserted again,
   * which would also fail the startup on a duplicate primary key.
   */
  @Test
  public void anUnscheduledFixedRequestIsLeftAloneAndNeverReinserted() {
    flagOn = true;
    ProcessRequest unscheduled = mock(ProcessRequest.class);
    when(unscheduled.getStatus()).thenReturn("UNS");
    when(dal.get(ProcessRequest.class, UsageAggregationScheduleStartup.REQUEST_ID))
        .thenReturn(unscheduled);

    startup.runPass();

    verify(provider, never()).get(ProcessRequest.class);
    verify(dal, never()).save(any());
    verify(dal, never()).commitAndClose();
    assertEquals(0, registrations);
  }

  @Test
  public void aMissingProcessDefinitionIsSkipped() {
    when(dal.get(Process.class, UsageAggregationScheduleStartup.PROCESS_ID)).thenReturn(null);

    assertFalse(startup.ensureScheduled());

    verify(dal, never()).save(any());
    verify(dal, never()).commitAndClose();
  }

  @Test
  public void ensureScheduledReportsWhetherItPlantedTheRequest() {
    assertTrue(startup.ensureScheduled());
    requestExists = true;
    assertFalse(startup.ensureScheduled());
  }

  /** The gate reads the usage flag, not another schedule's flag, with an environment context. */
  @Test
  public void scheduleEnabledFollowsTheUsageAggregationScheduleFlag() {
    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      flags.when(() -> GoFeatureFlags.isEnabled(
              eq(GoFeatureFlags.FLAG_USAGE_AGGREGATION_SCHEDULE), any(FeatureFlagContext.class)))
          .thenReturn(true);

      assertTrue(new UsageAggregationScheduleStartup().scheduleEnabled());

      flags.when(() -> GoFeatureFlags.isEnabled(
              eq(GoFeatureFlags.FLAG_USAGE_AGGREGATION_SCHEDULE), any(FeatureFlagContext.class)))
          .thenReturn(false);

      assertFalse(new UsageAggregationScheduleStartup().scheduleEnabled());
    }
  }

  /**
   * The request is already committed when the live scheduler is asked to pick it up; a scheduler
   * failure there is swallowed, so it can never break the startup (the next restart picks the row).
   */
  @Test
  public void aSchedulerFailureWhileRegisteringDoesNotBreakTheStartup() {
    try (MockedStatic<OBScheduler> scheduler = mockStatic(OBScheduler.class)) {
      scheduler.when(OBScheduler::getInstance)
          .thenThrow(new IllegalStateException("scheduler down"));

      new UsageAggregationScheduleStartup().registerWithScheduler(); // contract: never throws

      scheduler.verify(OBScheduler::getInstance);
    }
  }
}
