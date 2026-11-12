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

package com.etendoerp.go.accounts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.payment.AccountDemoExpiry;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-6223 — reads and edits when an account's demo expires.
 *
 * <p>The edited date is {@code ETGO_ACCOUNT.DEMO_EXPIRES_AT} ({@link AccountDemoExpiry}); empty, the
 * demo keeps the standard trial ({@code etendo.go.demo.trial.days} from its start). The lifecycle
 * snapshot reads it, so the access check, the trial notices, the trial bar and HubSpot all follow
 * the edited date without knowing about it.
 *
 * <p>An account "has a demo" when it owns an active environment that the lifecycle sees as a demo
 * not yet replaced by a productive one: editing the expiry of an account with none would change
 * nothing, so it is refused.
 */
public class DemoExpiryService {

  private static final String OPERATION = "demo expiry";

  /** What {@link #update} did. */
  public enum Outcome {
    UPDATED,
    ACCOUNT_NOT_FOUND,
    /** The account owns no demo whose expiry the date could move. */
    NO_DEMO
  }

  /**
   * The outcome of {@link #update}.
   *
   * @param demoExpiresAt the day the account's demo now expires; null unless {@code UPDATED}
   */
  public record Result(Outcome outcome, LocalDate demoExpiresAt) {
  }

  private final TenantEnvironmentLifecycleService lifecycle;
  private final AccountDemoExpiry demoExpiry;
  private final EnvironmentAccessPolicy policy = new EnvironmentAccessPolicy();

  /** Creates the service on the runtime lifecycle. */
  public DemoExpiryService() {
    this(new TenantEnvironmentLifecycleService(), new AccountDemoExpiry());
  }

  DemoExpiryService(TenantEnvironmentLifecycleService lifecycle, AccountDemoExpiry demoExpiry) {
    this.lifecycle = lifecycle;
    this.demoExpiry = demoExpiry;
  }

  /**
   * Sets, or clears, the day an account's demo expires.
   *
   * @param accountId the account's id
   * @param expiresOn the new expiry day; null goes back to the standard trial
   * @return the outcome, with the day the demo now expires
   */
  public Result update(String accountId, LocalDate expiresOn) {
    return SystemContext.call(OPERATION, () -> {
      Account account = OBDal.getInstance().get(Account.class, accountId);
      if (account == null || !Boolean.TRUE.equals(account.isActive())) {
        return new Result(Outcome.ACCOUNT_NOT_FOUND, null);
      }
      List<String> demos = demoClientIds(account);
      if (demos.isEmpty()) {
        return new Result(Outcome.NO_DEMO, null);
      }
      account.setDemoExpiresAt(expiresOn == null ? null : AccountDemoExpiry.toStored(expiresOn));
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
      // Read back through the lifecycle, as the access check will: the date it reports is the one
      // that is enforced.
      LocalDate effective = expiryOf(demos.get(0));
      OBDal.getInstance().commitAndClose();
      return new Result(Outcome.UPDATED, effective);
    });
  }

  /**
   * The day a demo environment expires: its edited date, or the end of its standard trial.
   *
   * @param clientId the environment's {@code AD_Client_ID}
   * @return the expiry day, or null when the environment is not a running demo (not created yet,
   *     productive, or replaced by a productive one)
   */
  public LocalDate expiryOf(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return null;
    }
    return SystemContext.call(OPERATION, () -> {
      TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = lifecycle.resolve(clientId);
      if (!isDemo(snapshot)) {
        return null;
      }
      Instant endsAt = policy.trialExpiresAt(snapshot.toPolicyEnvironment(),
          lifecycle.configuration());
      return endsAt == null ? null : AccountDemoExpiry.expiryDateOf(endsAt);
    });
  }

  private List<String> demoClientIds(Account account) {
    List<String> demos = new ArrayList<>();
    for (String clientId : demoExpiry.findOwnedClientIds(account.getEmail())) {
      if (isDemo(lifecycle.resolve(clientId))) {
        demos.add(clientId);
      }
    }
    return demos;
  }

  private static boolean isDemo(TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot) {
    return snapshot != null
        && snapshot.getType() == EnvironmentAccessPolicy.EnvironmentType.DEMO
        && !snapshot.isAssociatedWithProductive()
        && snapshot.getTrialStartedAt() != null;
  }
}
