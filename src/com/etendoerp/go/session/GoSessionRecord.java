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
package com.etendoerp.go.session;

import java.time.Instant;

/**
 * In-memory representation of a row in {@code ETGO_GO_SESSION} (ETP-4575).
 *
 * <p>Only hashes of the opaque credentials are ever held here — never the plaintext session or
 * refresh token, which exist solely in the browser cookie.
 */
public class GoSessionRecord {

  private String id;
  private String accountId;
  private String sessionTokenHash;
  private String csrfToken;
  private String refreshTokenHash;
  private String authMethod;
  private Instant expiresAt;
  private Instant absoluteExpiresAt;
  private boolean revoked;
  private String rotatedFromId;

  // Selected environment context (populated on environment switch).
  private String userId;
  private String roleId;
  private String ctxClientId;
  private String ctxOrgId;
  private String warehouseId;

  // Optional binding / audit.
  private String userAgent;
  private String ipHash;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getAccountId() {
    return accountId;
  }

  public void setAccountId(String accountId) {
    this.accountId = accountId;
  }

  public String getSessionTokenHash() {
    return sessionTokenHash;
  }

  public void setSessionTokenHash(String sessionTokenHash) {
    this.sessionTokenHash = sessionTokenHash;
  }

  public String getCsrfToken() {
    return csrfToken;
  }

  public void setCsrfToken(String csrfToken) {
    this.csrfToken = csrfToken;
  }

  public String getRefreshTokenHash() {
    return refreshTokenHash;
  }

  public void setRefreshTokenHash(String refreshTokenHash) {
    this.refreshTokenHash = refreshTokenHash;
  }

  public String getAuthMethod() {
    return authMethod;
  }

  public void setAuthMethod(String authMethod) {
    this.authMethod = authMethod;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getAbsoluteExpiresAt() {
    return absoluteExpiresAt;
  }

  public void setAbsoluteExpiresAt(Instant absoluteExpiresAt) {
    this.absoluteExpiresAt = absoluteExpiresAt;
  }

  public boolean isRevoked() {
    return revoked;
  }

  public void setRevoked(boolean revoked) {
    this.revoked = revoked;
  }

  public String getRotatedFromId() {
    return rotatedFromId;
  }

  public void setRotatedFromId(String rotatedFromId) {
    this.rotatedFromId = rotatedFromId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getRoleId() {
    return roleId;
  }

  public void setRoleId(String roleId) {
    this.roleId = roleId;
  }

  public String getCtxClientId() {
    return ctxClientId;
  }

  public void setCtxClientId(String ctxClientId) {
    this.ctxClientId = ctxClientId;
  }

  public String getCtxOrgId() {
    return ctxOrgId;
  }

  public void setCtxOrgId(String ctxOrgId) {
    this.ctxOrgId = ctxOrgId;
  }

  public String getWarehouseId() {
    return warehouseId;
  }

  public void setWarehouseId(String warehouseId) {
    this.warehouseId = warehouseId;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public String getIpHash() {
    return ipHash;
  }

  public void setIpHash(String ipHash) {
    this.ipHash = ipHash;
  }
}
