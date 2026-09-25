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
package com.etendoerp.go.onboarding.pool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link TenantPoolStore} recording every call, for filler and claim tests. */
public class FakeTenantPoolStore extends TenantPoolStore {

  public final Map<String, String> statusByRow = new LinkedHashMap<>();
  public final Map<String, String> clientByRow = new LinkedHashMap<>();
  public final Map<String, String> errorByRow = new LinkedHashMap<>();
  public final List<String> calls = new ArrayList<>();
  public int ready;
  public int provisioning;
  public int retiredStale;
  public int expired;
  public int commits;
  public int rollbacks;
  public Claim nextClaim;
  public RuntimeException claimFailure;
  private int sequence;

  @Override
  public int countReady(String version) {
    calls.add("countReady:" + version);
    return ready;
  }

  @Override
  public int countProvisioning() {
    calls.add("countProvisioning");
    return provisioning;
  }

  @Override
  public int retireStale(String version, Instant createdBefore) {
    calls.add("retireStale:" + version);
    return retiredStale;
  }

  @Override
  public int expireProvisioning(Instant startedBefore) {
    calls.add("expireProvisioning");
    return expired;
  }

  @Override
  public String insertProvisioning(String version) {
    String id = "ROW" + (++sequence);
    calls.add("insert:" + id);
    statusByRow.put(id, STATUS_PROVISIONING);
    return id;
  }

  @Override
  public void markReady(String poolRowId, String clientId) {
    calls.add("ready:" + poolRowId);
    statusByRow.put(poolRowId, STATUS_READY);
    clientByRow.put(poolRowId, clientId);
  }

  @Override
  public void markFailed(String poolRowId, String clientId, String error) {
    calls.add("failed:" + poolRowId);
    statusByRow.put(poolRowId, STATUS_FAILED);
    clientByRow.put(poolRowId, clientId);
    errorByRow.put(poolRowId, error);
  }

  @Override
  public Claim claimReady(String version) {
    calls.add("claim:" + version);
    if (claimFailure != null) {
      throw claimFailure;
    }
    return nextClaim;
  }

  @Override
  public void commit() {
    calls.add("commit");
    commits++;
  }

  @Override
  public void rollback() {
    calls.add("rollback");
    rollbacks++;
  }
}
