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
package com.etendoerp.go.supportaccess;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;

/**
 * ETP-5351 (T4) — turns a redeemed support pass into a GO session:
 * {@code POST /sws/go/session/support-handoff}.
 *
 * <p>The session belongs to the technical support account, carries {@code AUTH_METHOD='support'}
 * and the {@code ETGO_SUPPORT_ACCESS_ID} mark, is already inside the tenant as its "Soporte
 * Etendo" user with the client admin role, and lives exactly the duration the operator asked for
 * (idle timeout 30 minutes, never past that). It does not go through the account-ownership check
 * of {@code /session/environment}: the pass is the credential.</p>
 *
 * <p>The caller owns the transaction: a failure after the pass was consumed must roll back, so the
 * pass is not burnt by a request that never produced a session.</p>
 */
public class SupportHandoffService {

  private static final Logger log = LogManager.getLogger(SupportHandoffService.class);

  private final SupportAccessService accessService;
  private final SupportUserProvisioner provisioner;
  private final GoSessionService sessionService;

  /**
   * Creates the service.
   *
   * @param accessService  redeems the pass and records the session start
   * @param provisioner    re-asserts the support user (it may have been deactivated meanwhile)
   * @param sessionService creates the GO session
   */
  public SupportHandoffService(SupportAccessService accessService,
      SupportUserProvisioner provisioner, GoSessionService sessionService) {
    this.accessService = accessService;
    this.provisioner = provisioner;
    this.sessionService = sessionService;
  }

  /**
   * Redeems the pass and opens the support session.
   *
   * @param ticket    the plaintext pass
   * @param userAgent the browser's user agent, may be null
   * @param ip        the browser's IP, stored hashed; may be null
   * @return the issued session (plaintext cookies, CSRF) and the access it belongs to
   * @throws SupportTicketException when the pass is invalid, expired or already used
   * @throws SupportAccessException when the technical account or the tenant admin role is missing
   */
  public SupportHandoff open(String ticket, String userAgent, String ip) {
    SupportAccessRecord access = accessService.redeem(ticket);
    if (!accessService.isSupportAccountReady()) {
      throw new SupportAccessException(SupportAccessException.CODE_SUPPORT_ACCOUNT_MISSING,
          "The technical support account is missing or inactive");
    }
    SupportUserContext supportUser = provisioner.findOrCreate(access.getTargetClientId());
    if (!StringUtils.equals(supportUser.getRoleId(), access.getRoleId())) {
      log.warn("Support access {}: the tenant admin role changed since the pass was issued ({} -> {})",
          access.getId(), access.getRoleId(), supportUser.getRoleId());
    }

    GoSessionRecord environment = new GoSessionRecord();
    environment.setUserId(supportUser.getUserId());
    environment.setRoleId(supportUser.getRoleId());
    environment.setCtxClientId(supportUser.getClientId());
    environment.setCtxOrgId(supportUser.getOrgId());
    environment.setWarehouseId(supportUser.getWarehouseId());
    environment.setSupportAccessId(access.getId());

    IssuedGoSession issued = sessionService.create(SupportAccessGuard.SUPPORT_ACCOUNT_ID,
        SupportAccessGuard.AUTH_METHOD_SUPPORT, userAgent, SupportAccessService.hashIp(ip),
        SupportAccessService.sessionLifetime(access), environment);
    accessService.markStarted(access.getId(), issued.getRecord().getId());
    log.info("Support access {} started session {} in client {}", access.getId(),
        issued.getRecord().getId(), supportUser.getClientId());
    return new SupportHandoff(issued, access);
  }

  /**
   * Retires the session a browser still holds when it redeems a pass: the new cookies replace it,
   * so nobody could use it any more, and a live orphan is only a liability. A support session
   * left that way also frees its tenant ({@link SupportEndReason#LOGOUT}).
   *
   * @param rawSessionToken the {@code __Host-go_session} value the request carried, may be null
   */
  public void retireBrowserSession(String rawSessionToken) {
    GoSessionRecord previous = sessionService.resolve(rawSessionToken);
    if (previous == null) {
      return;
    }
    sessionService.revoke(previous);
    accessService.closeOnLogout(previous);
  }

  /** The outcome of a successful handoff. */
  public static final class SupportHandoff {
    private final IssuedGoSession session;
    private final SupportAccessRecord access;

    /**
     * Creates the outcome.
     *
     * @param session the issued session
     * @param access  the redeemed access
     */
    public SupportHandoff(IssuedGoSession session, SupportAccessRecord access) {
      this.session = session;
      this.access = access;
    }

    /** @return the issued session: cookies and CSRF to send, record already persisted */
    public IssuedGoSession getSession() {
      return session;
    }

    /** @return the redeemed access */
    public SupportAccessRecord getAccess() {
      return access;
    }
  }
}
