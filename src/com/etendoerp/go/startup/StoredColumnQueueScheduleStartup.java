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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.startup;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.SessionInfo;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessContext;

/**
 * Startup self-healer that guarantees the async stored-computed-column queue drain is scheduled.
 *
 * <p>Stored computed columns with {@code Refresh_Mode='Q'} (e.g. {@code M_Product.EM_ETGO_Stock})
 * enqueue their recomputations into {@code AD_STOREDCOLUMN_DIRTY}. The queue is drained by the
 * background process {@code StoredColumnQueueProcessor}, which — as of EPL-1807 — treats a run made
 * as System ({@code AD_CLIENT_ID='0'}) as a cross-client catch-all that drains every client's
 * partition. Without a scheduled {@link ProcessRequest} nothing ever drains the queue, so those
 * columns would stay stale on new installs.</p>
 *
 * <p>The base module ships no {@code AD_PROCESS_REQUEST.xml} for this process (a Process Request is
 * instance data, not model data), and its roles do not go through onboarding, so on a clean install
 * nothing schedules the drain. This {@link ApplicationInitializer} closes that gap PREVENTIVELY for
 * new installs and CORRECTIVELY for existing ones: on every application startup it inserts — once,
 * idempotently — a single System ({@code client '0'}, {@code org '0'}) Process Request for the queue
 * processor. Because the processor is client-position-independent, this ONE System request covers
 * all existing and future clients — no per-client requests are needed.</p>
 *
 * <p>Idempotency is decided PER PROCESS, not per row id: if any active {@code SCH} request already
 * exists for the queue processor (regardless of its id or client), nothing is inserted. The row is
 * created in state {@code SCH} with a valid {@code ob_context}, and — in the SAME boot — the request
 * is registered with {@link OBScheduler} so the drain starts firing without waiting for a restart
 * (the persisted row is inserted after {@code OBScheduler.initialize()} has already run, so relying
 * on the next startup would leave the queue undrained until then). The registration is idempotent:
 * {@code OBScheduler.schedule(...)} skips the Quartz job if it already exists and no-ops when the
 * scheduler is in standby, so it is safe even if {@code OBScheduler.initialize()} also picks the row
 * up on a later boot. Like {@code NeoAccessStartup}, this initializer only ever INSERTs a Process
 * Request row (and asks the scheduler to register it); it never touches {@code AD_PROCESS}, so it
 * cannot destabilize startup — any scheduler error is caught and the row simply waits for the next
 * boot's {@code OBScheduler.initialize()}.</p>
 */
@ApplicationScoped
@ComponentProvider.Qualifier(StoredColumnQueueScheduleStartup.QUALIFIER)
public class StoredColumnQueueScheduleStartup extends SessionAwareStartup {

  static final String QUALIFIER = "com.etendoerp.go.startup.StoredColumnQueueScheduleStartup";

  private static final Logger log = LogManager.getLogger(StoredColumnQueueScheduleStartup.class);

  /** AD_PROCESS_ID of {@code org.openbravo.erpCommon.ad_process.StoredColumnQueueProcessor}. */
  private static final String QUEUE_PROCESSOR_PROCESS_ID = "D35DC63A8838412890AEE01D31CD70A3";
  /** Fixed PK of the System Process Request this initializer owns (deterministic across installs). */
  private static final String REQUEST_ID = "98E3901D0ABB49A2AC4B961228BF165E";

  private static final String SYSTEM_CLIENT = "0";
  private static final String ORG_ZERO = "0";
  /** System Administrator user; matches the queue processor's expected run identity. */
  private static final String SYSTEM_USER = "100";
  private static final String LANGUAGE = "en_US";

  private static final String STATUS_SCHEDULED = org.openbravo.scheduling.Process.SCHEDULED;
  private static final String CHANNEL_SCHEDULER = "Process Scheduler";
  /** Timing option "Scheduled" (recurring on a fixed schedule). */
  private static final String TIMING_SCHEDULED = "S";
  /** Frequency "Minutely". */
  private static final String FREQUENCY_MINUTELY = "2";
  /** Drain every 5 minutes. */
  private static final Long INTERVAL_MINUTES = 5L;

  /** Poll interval while waiting for the scheduler to leave standby. */
  private static final long SCHEDULER_POLL_MS = 500L;
  /** Hard cap waiting for the scheduler to become active (it starts a few seconds after the app). */
  private static final long SCHEDULER_WAIT_TIMEOUT_MS = 120_000L;

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String name() {
    return "StoredColumnQueueScheduleStartup";
  }

  @Override
  protected void runPass() {
    ensureScheduled();
  }

  /**
   * Visible for testing: the synchronous idempotent schedule pass, decoupled from the startup thread
   * and the {@link SessionInfo} wait.
   */
  void ensureScheduled() {
    boolean inserted = false;
    OBContext.setAdminMode(true);
    try {
      if (activeScheduledRequestExists()) {
        log.debug("StoredColumnQueueScheduleStartup: an active SCH request for the queue processor"
            + " already exists; nothing to schedule.");
        return;
      }

      Process process = OBDal.getInstance().get(Process.class, QUEUE_PROCESSOR_PROCESS_ID);
      if (process == null) {
        log.warn("StoredColumnQueueScheduleStartup: queue processor process {} not found; the"
            + " StoredColumnQueueProcessor may not be installed yet. Skipping.",
            QUEUE_PROCESSOR_PROCESS_ID);
        return;
      }

      insertSystemRequest(process);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      inserted = true;
      log.info("StoredColumnQueueScheduleStartup: inserted the System stored-column queue drain"
          + " request (every {} min).", INTERVAL_MINUTES);
    } finally {
      OBContext.restorePreviousMode();
    }

    // Register the freshly inserted request with the scheduler in THIS boot (the row was committed
    // after OBScheduler.initialize() already ran, so it would otherwise stay idle until a restart).
    // Runs outside the OBContext admin block: the scheduler uses its own JDBC connection and reads
    // the committed row.
    if (inserted) {
      registerWithScheduler(REQUEST_ID);
    }
  }

  /**
   * Registers the request with {@link OBScheduler} so its Quartz trigger fires without a restart.
   * Idempotent and self-contained: {@code OBScheduler.schedule(...)} skips an already-existing job.
   *
   * <p>The scheduler starts a few seconds AFTER the application initializers run, so this thread can
   * win the race and reach {@code schedule(...)} while Quartz is still in standby — where it silently
   * no-ops ("no scheduler instances are active"). To actually register in this boot we first wait
   * (bounded) for {@link OBScheduler#isSchedulingAllowed()} to become true. If it never does, the
   * persisted {@code SCH} row is left for the next boot's {@code OBScheduler.initialize()}.</p>
   *
   * <p>Package-visible so unit tests can override it and keep their assertions off the real
   * scheduler.</p>
   */
  void registerWithScheduler(String requestId) {
    try {
      OBScheduler scheduler = OBScheduler.getInstance();
      if (!waitForSchedulingAllowed(scheduler)) {
        log.info("StoredColumnQueueScheduleStartup: scheduler still inactive after {} ms; leaving the"
            + " queue drain for OBScheduler.initialize() on the next startup.",
            SCHEDULER_WAIT_TIMEOUT_MS);
        return;
      }
      ProcessBundle bundle = ProcessBundle.request(requestId, systemVars(), scheduler.getConnection());
      scheduler.schedule(requestId, bundle);
      log.info("StoredColumnQueueScheduleStartup: registered the queue drain with the scheduler;"
          + " it will start firing without a restart.");
    } catch (Exception e) {
      log.warn("StoredColumnQueueScheduleStartup: could not register the queue drain with the"
          + " scheduler now; OBScheduler will pick it up on the next startup.", e);
    }
  }

  /**
   * Waits (bounded) for the Quartz scheduler to leave standby so {@code schedule(...)} will actually
   * register the trigger instead of no-oping. Returns {@code true} as soon as scheduling is allowed,
   * {@code false} if the timeout elapses or the thread is interrupted first.
   */
  private boolean waitForSchedulingAllowed(OBScheduler scheduler) {
    long deadline = System.currentTimeMillis() + SCHEDULER_WAIT_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (schedulingAllowedQuietly(scheduler)) {
        return true;
      }
      try {
        Thread.sleep(SCHEDULER_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return schedulingAllowedQuietly(scheduler);
  }

  /** {@link OBScheduler#isSchedulingAllowed()} swallowing its checked exception (treated as "not yet"). */
  private boolean schedulingAllowedQuietly(OBScheduler scheduler) {
    try {
      return scheduler.isSchedulingAllowed();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Idempotency is decided per process: any active {@code SCH} request for the queue processor —
   * regardless of its id or owning client — means the drain is already scheduled.
   */
  private boolean activeScheduledRequestExists() {
    OBCriteria<ProcessRequest> requests = OBDal.getInstance().createCriteria(ProcessRequest.class);
    requests.add(Restrictions.eq(ProcessRequest.PROPERTY_PROCESS + ".id", QUEUE_PROCESSOR_PROCESS_ID));
    requests.add(Restrictions.eq(ProcessRequest.PROPERTY_STATUS, STATUS_SCHEDULED));
    requests.add(Restrictions.eq(ProcessRequest.PROPERTY_ACTIVE, true));
    requests.setFilterOnReadableClients(false);
    requests.setMaxResults(1);
    List<ProcessRequest> found = requests.list();
    return !found.isEmpty();
  }

  private void insertSystemRequest(Process process) {
    Organization orgZero = OBDal.getInstance().get(Organization.class, ORG_ZERO);
    User systemUser = OBDal.getInstance().get(User.class, SYSTEM_USER);

    ProcessRequest request = OBProvider.getInstance().get(ProcessRequest.class);
    request.setNewOBObject(true);
    request.setId(REQUEST_ID);
    request.setClient(OBDal.getInstance().get(
        org.openbravo.model.ad.system.Client.class, SYSTEM_CLIENT));
    request.setOrganization(orgZero);
    request.setActive(true);
    request.setProcess(process);
    request.setUserContact(systemUser);
    request.setSecurityBasedOnRole(true);
    request.setOpenbravoContext(buildSystemContext());
    request.setStatus(STATUS_SCHEDULED);
    request.setChannel(CHANNEL_SCHEDULER);
    request.setTiming(TIMING_SCHEDULED);
    request.setFrequency(FREQUENCY_MINUTELY);
    request.setIntervalInMinutes(INTERVAL_MINUTES);

    Date now = new Date();
    request.setStartDate(now);
    request.setStartTime(new Timestamp(now.getTime()));

    // Button flags: this row is not a manual action, and it is not a process group.
    request.setScheduleProcess(false);
    request.setRescheduleProcess(false);
    request.setUnscheduleProcess(false);
    request.setGroup(false);

    OBDal.getInstance().save(request);
  }

  /**
   * Builds the {@code ob_context} exactly as the scheduler expects: a serialized {@link ProcessContext}
   * for the System user, running as client '0' / org '0' with role security. {@code OBScheduler}
   * rebuilds the run's {@link VariablesSecureApp} from this on startup.
   */
  private String buildSystemContext() {
    return new ProcessContext(systemVars(), SYSTEM_CLIENT, ORG_ZERO, true).toString();
  }

  /** The System run identity (user 100, client '0', org '0', System Administrator role '0'). */
  private VariablesSecureApp systemVars() {
    return new VariablesSecureApp(SYSTEM_USER, SYSTEM_CLIENT, ORG_ZERO, SYSTEM_CLIENT, LANGUAGE);
  }
}
