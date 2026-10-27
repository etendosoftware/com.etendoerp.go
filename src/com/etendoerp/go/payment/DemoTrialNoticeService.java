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

package com.etendoerp.go.payment;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * One pass of the demo trial notices (ETP-5453): checks every candidate environment and sends the
 * notice each one is owed.
 *
 * <p>Best effort, environment by environment. A failure is logged and counted, never thrown, so
 * one broken tenant cannot stop the others; each send commits on its own, so a later failure
 * cannot roll back a notice already delivered. A failed notice is not recorded as sent, so the
 * next run finds it owed again and retries it.</p>
 */
public class DemoTrialNoticeService {

  private static final Logger log = LogManager.getLogger(DemoTrialNoticeService.class);

  /** Delivers the notice owed by one environment. */
  interface Sender {
    /**
     * Sends the owed notice for an environment.
     *
     * @param clientId the environment's {@code AD_Client_ID}
     * @return the email service's response
     */
    NeoResponse send(String clientId);

    /** Discards whatever a failed environment left in the session, so the next one starts clean. */
    default void discardPendingChanges() {
    }
  }

  private final DemoTrialNoticeSource source;
  private final Sender sender;

  /** Creates the service backed by the DAL and the transactional email service. */
  public DemoTrialNoticeService() {
    this(new DalDemoTrialNoticeSource(), new TransactionalSender(new TransactionalEmailService()));
  }

  DemoTrialNoticeService(DemoTrialNoticeSource source, Sender sender) {
    this.source = source;
    this.sender = sender;
  }

  /**
   * Runs one pass.
   *
   * @param now the evaluation instant
   * @return what the pass did
   */
  public Result run(Instant now) {
    Result result = new Result();
    List<String> candidates = source.findCandidateClientIds();
    for (String clientId : candidates) {
      result.checked++;
      try {
        if (source.keepFrozenTrial(clientId, now)) {
          result.frozen++;
        } else if (!source.findOwedNotice(clientId, now).isPresent()) {
          result.notOwed++;
        } else {
          tally(result, clientId, sender.send(clientId));
        }
      } catch (RuntimeException e) {
        log.warn("Demo trial notice for client {} failed; it is retried on the next run",
            clientId, e);
        result.failures.put(clientId, String.valueOf(e.getMessage()));
        sender.discardPendingChanges();
      }
    }
    log.info("Demo trial notices: {}", result);
    return result;
  }

  private static void tally(Result result, String clientId, NeoResponse response) {
    String status = statusOf(response);
    if (TransactionalEmailService.STATUS_SENT.equals(status)) {
      result.sent++;
    } else if (TransactionalEmailService.STATUS_DUPLICATE.equals(status)) {
      result.alreadySent++;
    } else {
      String reason = describe(response, status);
      log.warn("Demo trial notice for client {} was not delivered ({}); it is retried on the next "
          + "run", clientId, reason);
      result.failures.put(clientId, reason);
    }
  }

  private static String statusOf(NeoResponse response) {
    JSONObject data = dataOf(response);
    return data == null ? null : data.optString("status", null);
  }

  private static String describe(NeoResponse response, String status) {
    if (response == null) {
      return "no response";
    }
    JSONObject data = dataOf(response);
    String message = data == null ? null : data.optString("message", null);
    return "HTTP " + response.getHttpStatus() + " " + status
        + (message == null ? "" : " - " + message);
  }

  private static JSONObject dataOf(NeoResponse response) {
    if (response == null || response.getBody() == null) {
      return null;
    }
    JSONObject wrapper = response.getBody().optJSONObject("response");
    return wrapper == null ? null : wrapper.optJSONObject("data");
  }

  /** Sends through the transactional email service and commits each send on its own. */
  static final class TransactionalSender implements Sender {
    private final TransactionalEmailService emailService;

    TransactionalSender(TransactionalEmailService emailService) {
      this.emailService = emailService;
    }

    @Override
    public NeoResponse send(String clientId) {
      try {
        JSONObject body = new JSONObject();
        body.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
        body.put(EmailContractCommandSupport.FIELD_CLIENT_ID, clientId);
        body.put(EmailContractCommandSupport.FIELD_TENANT_ID, clientId);
        NeoResponse response = emailService.send(DemoTrialNotice.CONTRACT_NAME, body);
        OBDal.getInstance().flush();
        OBDal.getInstance().commitAndClose();
        return response;
      } catch (JSONException e) {
        throw new OBException("Could not build the demo trial notice command", e);
      }
    }

    @Override
    public void discardPendingChanges() {
      OBDal.getInstance().rollbackAndClose();
    }
  }

  /** Counters of one pass. */
  public static final class Result {
    private int checked;
    private int notOwed;
    private int sent;
    private int alreadySent;
    private int frozen;
    private final Map<String, String> failures = new LinkedHashMap<>();

    public int getChecked() {
      return checked;
    }

    public int getNotOwed() {
      return notOwed;
    }

    public int getSent() {
      return sent;
    }

    public int getAlreadySent() {
      return alreadySent;
    }

    /** @return environments whose trial is frozen, owed no notice */
    public int getFrozen() {
      return frozen;
    }

    public int getFailed() {
      return failures.size();
    }

    /** @return the reason each failed environment gave, by {@code AD_Client_ID} */
    public Map<String, String> getFailures() {
      return Collections.unmodifiableMap(failures);
    }

    @Override
    public String toString() {
      return checked + " environment(s) checked: " + sent + " notice(s) sent, " + alreadySent
          + " already sent, " + notOwed + " with nothing owed, " + frozen + " frozen, "
          + failures.size() + " failed";
    }
  }
}
