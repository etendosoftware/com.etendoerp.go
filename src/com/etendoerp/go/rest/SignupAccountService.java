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

package com.etendoerp.go.rest;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingStreamWriter;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * Accounts created through the signup API (ETP-5847): passwordless, with no confirmation mail, and
 * with their demo environment created in the background. When the environment is ready the owner
 * gets a one-time login link — mailed, unless the caller turned mails off, in which case the
 * caller asks for the link (and, if it wants, for the mail) later.
 *
 * <p><b>The environment is the app's own onboarding.</b> The worker runs
 * {@link EtendoGoJwtServlet#provisionSignupEnvironment}, the {@code POST /onboarding} pipeline
 * fed from the company data stored as the account's onboarding draft, so an environment created
 * here is indistinguishable from one created in the app. A failure leaves the account
 * {@code failed} with the reason, and {@link #retry} runs it again: the onboarding resumes a
 * half-built client by its provisioning name.
 *
 * <p>Public because the signup API lives in its own package; the DAL helpers stay package-private.
 */
public class SignupAccountService {

  private static final Logger log = LogManager.getLogger(SignupAccountService.class);

  /** Same validity as a company invitation: a link someone may open days after it was sent. */
  static final long LOGIN_LINK_TTL_SECONDS = 7 * 24 * 60 * 60L;

  private static final String OPERATION = "signup account";
  private static final int WORKERS = 2;

  private static ExecutorService sharedExecutor;
  private static EtendoGoJwtServlet sharedProvisioner;

  /** An account to create, already validated by the signup API. */
  public record NewAccount(String email, String name, String onboardingDraft, String channel,
      boolean sendEmails, String source, boolean termsAccepted) {
  }

  /** What the signup API reports about an account it created. */
  public record Status(String accountId, String email, String status, String error) {
  }

  /** Why no login link was issued. */
  public enum LinkRefusal {
    /** No account the signup API created has that id. */
    NOT_FOUND,
    /** The environment is still being created, or its creation failed. */
    NOT_READY,
    /** The server has no public app URL ({@code etendo.go.app.baseUrl}) to build the link on. */
    NOT_CONFIGURED
  }

  /** A login link, or the reason there is none. */
  public record LoginLink(String link, Date expiresAt, boolean emailSent, LinkRefusal refusal) {
    static LoginLink refused(LinkRefusal refusal) {
      return new LoginLink(null, null, false, refusal);
    }
  }

  /** Outcome of {@link #retry}. */
  public enum RetryOutcome {
    NOT_FOUND,
    /** Only a failed creation is retried; a running or finished one is left alone. */
    NOT_FAILED,
    STARTED
  }

  private final TransactionalAuthEmailSender emailSender;
  private final Consumer<Runnable> worker;
  private final Supplier<EtendoGoJwtServlet> provisioner;

  /** Creates the service on the runtime email sender and the shared background workers. */
  public SignupAccountService() {
    this(new TransactionalAuthEmailSender(), task -> executor().execute(task),
        SignupAccountService::provisioner);
  }

  SignupAccountService(TransactionalAuthEmailSender emailSender, Consumer<Runnable> worker,
      Supplier<EtendoGoJwtServlet> provisioner) {
    this.emailSender = emailSender;
    this.worker = worker;
    this.provisioner = provisioner;
  }

  /**
   * Creates the account and starts creating its environment in the background.
   *
   * @param request the validated signup
   * @return the new account's id
   */
  public String create(NewAccount request) {
    String accountId = SystemContext.call(OPERATION, () -> {
      try {
        return SignupAccountDalHelper.createSignupAccount(request.email(), request.name(),
            request.onboardingDraft(), new SignupAccountDalHelper.SignupData(request.channel(),
                request.sendEmails(), request.source(),
                request.termsAccepted() ? new Date() : null)).getId();
      } catch (RuntimeException e) {
        EtendoGoDalHelper.rollbackDalChanges("signup account creation", e, log);
        throw e;
      }
    });
    worker.accept(() -> provisionInBackground(accountId));
    return accountId;
  }

  /**
   * The account's signup state.
   *
   * @param accountId the account the signup created
   * @return its state, or {@code null} when the signup API did not create it
   */
  public Status status(String accountId) {
    return SystemContext.call(OPERATION, () -> {
      Account account = SignupAccountDalHelper.findSignupAccount(accountId);
      return account == null ? null
          : new Status(account.getId(), account.getEmail(), SignupAccountDalHelper.status(account),
              SignupAccountDalHelper.error(account));
    });
  }

  /**
   * Runs a failed environment creation again.
   *
   * @param accountId the account the signup created
   * @return whether it started, or why it did not
   */
  public RetryOutcome retry(String accountId) {
    RetryOutcome outcome = SystemContext.call(OPERATION, () -> {
      Account account = SignupAccountDalHelper.findSignupAccount(accountId);
      if (account == null) {
        return RetryOutcome.NOT_FOUND;
      }
      if (!SignupAccountDalHelper.STATUS_FAILED.equals(SignupAccountDalHelper.status(account))) {
        return RetryOutcome.NOT_FAILED;
      }
      SignupAccountDalHelper.markProvisioning(account);
      return RetryOutcome.STARTED;
    });
    if (outcome == RetryOutcome.STARTED) {
      worker.accept(() -> provisionInBackground(accountId));
    }
    return outcome;
  }

  /**
   * Issues a fresh one-time login link for an account whose environment is ready, replacing any
   * earlier one, and optionally mails it in the ready message.
   *
   * @param accountId the account the signup created
   * @param sendEmail whether to also mail the link in the ready message
   * @param language the mail's language, or {@code null} for the default
   * @return the link with its expiry and whether the mail went out, or why none was issued
   */
  public LoginLink issueLoginLink(String accountId, boolean sendEmail, String language) {
    return SystemContext.call(OPERATION, () -> {
      Account account = SignupAccountDalHelper.findSignupAccount(accountId);
      if (account == null) {
        return LoginLink.refused(LinkRefusal.NOT_FOUND);
      }
      if (!SignupAccountDalHelper.STATUS_READY.equals(SignupAccountDalHelper.status(account))) {
        return LoginLink.refused(LinkRefusal.NOT_READY);
      }
      String token = AccountRegistrationService.generateSecureUrlToken();
      String link = EtendoGoAuthLinkBuilder.loginLink(token);
      if (link == null) {
        log.warn("Signup login link not issued: the public app base URL is not configured");
        return LoginLink.refused(LinkRefusal.NOT_CONFIGURED);
      }
      String clientId = SignupAccountDalHelper.environment(account);
      Date expiresAt = Date.from(Instant.now().plusSeconds(LOGIN_LINK_TTL_SECONDS));
      SignupAccountDalHelper.storeLoginLink(account,
          AccountRegistrationService.hashAuthToken(token), expiresAt);
      boolean emailSent = sendEmail && emailSender.sendEnvironmentReady(
          EtendoGoJwtDalHelper.findActiveAccountById(accountId), clientId,
          StringUtils.trimToNull(language), link);
      return new LoginLink(link, expiresAt, emailSent, null);
    });
  }

  /**
   * The background half of a signup: creates the environment, records the outcome, and mails the
   * login link when the caller asked for mails. Never throws: the worker has no caller to tell, so
   * every failure ends as a {@code failed} account with its reason.
   */
  void provisionInBackground(String accountId) {
    try {
      Pending pending = SystemContext.call(OPERATION, () -> Pending.of(
          SignupAccountDalHelper.findSignupAccount(accountId)));
      if (pending == null) {
        log.warn("Signup account {} vanished before its environment was created", accountId);
        return;
      }
      EtendoGoJwtServlet.SignupProvisioningResult result = provision(accountId, pending);
      SystemContext.run(OPERATION, () -> recordOutcome(accountId, result));
      if (result.created() && pending.sendEmails()) {
        LoginLink link = issueLoginLink(accountId, true, pending.language());
        if (!link.emailSent()) {
          log.warn("Signup account {} is ready but its login link mail was not sent ({})",
              accountId, link.refusal());
        }
      }
    } catch (RuntimeException e) {
      log.error("Signup environment creation failed for account {}", accountId, e);
      recordFailureQuietly(accountId, e.getMessage());
    } finally {
      releaseWorkerSession();
    }
  }

  /** Nobody reads the progress a background signup streams, so it goes nowhere. */
  private EtendoGoJwtServlet.SignupProvisioningResult provision(String accountId,
      Pending pending) {
    try (PrintWriter discarded = new PrintWriter(Writer.nullWriter())) {
      return provisioner.get().provisionSignupEnvironment(accountId, pending.email(),
          pending.name(), pending.form(), new OnboardingStreamWriter(discarded));
    }
  }

  private static void recordOutcome(String accountId,
      EtendoGoJwtServlet.SignupProvisioningResult result) {
    Account account = SignupAccountDalHelper.findSignupAccount(accountId);
    if (account == null) {
      return;
    }
    if (result.created()) {
      SignupAccountDalHelper.markReady(account, result.clientId());
    } else {
      log.warn("Signup environment for account {} was not created: {}", accountId,
          result.failure());
      SignupAccountDalHelper.markFailed(account, result.failure());
    }
  }

  private static void recordFailureQuietly(String accountId, String reason) {
    try {
      SystemContext.run(OPERATION, () -> {
        OBDal.getInstance().rollbackAndClose();
        Account account = SignupAccountDalHelper.findSignupAccount(accountId);
        if (account != null) {
          SignupAccountDalHelper.markFailed(account, reason);
        }
      });
    } catch (RuntimeException e) {
      log.error("Could not record the failed signup of account {}", accountId, e);
    }
  }

  /**
   * A worker thread is not a request: nothing closes its DAL session or clears its context for
   * it, and the next task on the same thread would inherit both.
   */
  private static void releaseWorkerSession() {
    try {
      if (SessionHandler.isSessionHandlerPresent()) {
        OBDal.getInstance().commitAndClose();
        SessionHandler.deleteSessionHandler();
      }
    } catch (RuntimeException e) {
      log.warn("Could not release the signup worker's DAL session", e);
    } finally {
      OBContext.setOBContext((OBContext) null);
    }
  }

  /** What the worker needs from the account, read before the long provisioning starts. */
  private record Pending(String email, String name, JSONObject form, boolean sendEmails,
      String language) {
    static Pending of(Account account) {
      if (account == null) {
        return null;
      }
      String draft = account.getOnboardingDraft();
      if (StringUtils.isBlank(draft)) {
        throw new IllegalStateException("The account has no company data to create from");
      }
      try {
        JSONObject form = new JSONObject(draft).getJSONObject("form");
        return new Pending(account.getEmail(), account.getName(), form,
            SignupAccountDalHelper.sendsEmails(account),
            StringUtils.trimToNull(form.optString("language", "")));
      } catch (JSONException e) {
        throw new IllegalStateException("The account's company data is not valid JSON", e);
      }
    }
  }

  private static synchronized ExecutorService executor() {
    if (sharedExecutor == null) {
      sharedExecutor = Executors.newFixedThreadPool(WORKERS, task -> {
        Thread thread = new Thread(task, "etgo-signup-provisioning");
        thread.setDaemon(true);
        return thread;
      });
    }
    return sharedExecutor;
  }

  /** One servlet instance runs every background provisioning, as one serves every request. */
  private static synchronized EtendoGoJwtServlet provisioner() {
    if (sharedProvisioner == null) {
      sharedProvisioner = new EtendoGoJwtServlet();
    }
    return sharedProvisioner;
  }
}
