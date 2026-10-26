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

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * Specs for {@link BillingReconciliationScheduleStartup}: the System process request of the
 * billing reconciliation is planted only while the {@code billing-reconciliation-schedule} flag is
 * on, idempotently, at the configured interval.
 *
 * <p>The real scheduler and the flag provider are kept out by overriding the two package-visible
 * seams the class offers; the DAL is mocked.
 *
 * @covers com.etendoerp.go.startup.BillingReconciliationScheduleStartup
 */
public class BillingReconciliationScheduleStartupTest {

  private static final String SYSTEM = "0";

  private boolean flagOn;
  private boolean requestExists;
  private int registrations;
  private int ensureCalls;

  private OBDal dal;
  private OBProvider provider;
  private Process process;
  private ProcessRequest planted;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBProvider> providerStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<GoRuntimeProperties> properties;
  private int configuredInterval = 60;

  private final BillingReconciliationScheduleStartup startup =
      new BillingReconciliationScheduleStartup() {
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
    when(dal.get(Process.class, BillingReconciliationScheduleStartup.PROCESS_ID))
        .thenReturn(process);
    when(dal.get(Client.class, SYSTEM)).thenReturn(mock(Client.class));
    when(dal.get(Organization.class, SYSTEM)).thenReturn(mock(Organization.class));
    when(dal.get(User.class, "100")).thenReturn(mock(User.class));
    properties.when(() -> GoRuntimeProperties.readInt(anyString(), anyString(), anyInt()))
        .thenAnswer(call -> "etendo.go.billing.reconcile.interval.minutes".equals(
            call.getArgument(0)) ? configuredInterval : call.getArgument(2, Integer.class));
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
    verify(dal, never()).save(any());
  }

  @Test
  public void withTheFlagOnTheFixedSystemRequestIsPlantedAndRegistered() {
    flagOn = true;

    startup.runPass();

    verify(planted).setId(BillingReconciliationScheduleStartup.REQUEST_ID);
    verify(planted).setProcess(process);
    verify(planted).setStatus("SCH");
    verify(planted).setTiming("S");
    verify(planted).setFrequency("2");
    verify(planted).setIntervalInMinutes(60L);
    verify(planted).setActive(true);
    verify(dal).save(planted);
    verify(dal).commitAndClose();
    assertEquals(1, registrations);
  }

  @Test
  public void theIntervalComesFromConfiguration() {
    flagOn = true;
    configuredInterval = 30;

    startup.runPass();

    verify(planted).setIntervalInMinutes(30L);
  }

  @Test
  public void anActiveRequestIsNotPlantedTwice() {
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
    when(dal.get(ProcessRequest.class, BillingReconciliationScheduleStartup.REQUEST_ID))
        .thenReturn(unscheduled);

    startup.runPass();

    verify(provider, never()).get(ProcessRequest.class);
    verify(dal, never()).save(any());
    verify(dal, never()).commitAndClose();
    assertEquals(0, registrations);
  }

  @Test
  public void aMissingProcessDefinitionIsSkipped() {
    when(dal.get(Process.class, BillingReconciliationScheduleStartup.PROCESS_ID)).thenReturn(null);

    assertFalse(startup.ensureScheduled());

    verify(dal, never()).save(any());
  }

  @Test
  public void ensureScheduledReportsWhetherItPlantedTheRequest() {
    assertTrue(startup.ensureScheduled());
    requestExists = true;
    assertFalse(startup.ensureScheduled());
  }
}
