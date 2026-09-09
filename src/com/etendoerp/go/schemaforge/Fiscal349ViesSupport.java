/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.module.bptaxidkey.ViesService;

/**
 * Handles the {@code POST /neo/fiscal349/validate-vies} entity on behalf of
 * {@link Fiscal349BoxesHandler}, which holds one instance of this class ({@code viesSupport})
 * and delegates to it from {@code dispatch(...)}.
 *
 * <p>Extracted from {@link Fiscal349BoxesHandler} (ETP-5027) purely to keep that class's method
 * count under the SonarQube {@code java:S1448} threshold (it had grown to 44 methods). This class
 * is the whole VIES-validation cluster: gate, network phase and persistence for the
 * "validate-vies" verb, and nothing else.</p>
 *
 * <p><b>Why {@code owner} is a per-call parameter, never a stored field.</b> Unlike
 * {@code Fiscal303SubmissionSupport} (the precedent for this extraction pattern),
 * {@code Fiscal349BoxesHandlerTest}'s sibling {@code Fiscal349ViesValidationTest} wraps the
 * handler in a Mockito spy — {@code handler = spy(new Fiscal349BoxesHandler(servlet))} — and
 * stubs/verifies {@code handler.checkVat(...)} and {@code handler.releaseDalConnection()}
 * directly on that spy. Per Mockito's own documented behavior, {@code spy(realObject)} does NOT
 * wrap {@code realObject}: it creates a NEW proxy instance and copies the real object's field
 * state into it, leaving the original orphaned. If this class captured {@code owner} once in ITS
 * OWN constructor (the {@code Fiscal303SubmissionSupport} pattern), that capture would happen
 * BEFORE {@code spy(...)} runs, on the pre-spy, soon-to-be-orphaned handler instance — every
 * {@code owner.checkVat(...)} call from inside this class would then silently bypass every
 * {@code doReturn(...).when(handler).checkVat(...)} stub and every
 * {@code verify(handler).checkVat(...)} assertion, because it would run on an object Mockito is
 * not watching. Threading {@code owner} through as an explicit method parameter instead means it
 * is always resolved fresh, at call time, from whichever object the entry point was actually
 * invoked on (the spy, in tests) — so it can never go stale. Do not "simplify" this back into a
 * stored field.</p>
 */
class Fiscal349ViesSupport {

  private static final String OPERATORS = "operators";

  /**
   * {@code EM_OBTIK_Tax_ID_Key} value for "NOI" (EU intra-community operator). Only partners
   * carrying this key are eligible for a VIES check — the exact gate {@code ViesStatusObserver}
   * applies in the bptaxidkey module.
   */
  private static final String NOI_TAX_ID_KEY = "2";

  /**
   * Maximum partners checked against VIES in a single request. VIES rate-limits per member
   * state ({@code MS_MAX_CONCURRENT_REQ} is a routinely-hit error), and each check can take up
   * to 10 s (5 s connect + 5 s read), so an unbounded batch would both hammer the service and
   * blow the HTTP timeout. Anything beyond the cap simply stays pending and is picked up by the
   * next click.
   */
  static final int VIES_BATCH_CAP = 25;

  /** Bounded parallelism for the VIES calls — high enough to be fast, low enough not to be throttled. */
  private static final int VIES_MAX_THREADS = 4;

  /** Upper bound on the whole VIES phase; anything unfinished by then is reported as pending. */
  private static final long VIES_PHASE_TIMEOUT_SECONDS = 120L;

  /**
   * A partner that passed the eligibility gate and will actually be sent to VIES.
   */
  static final class ViesCandidate {
    final String bpId;
    final String taxId;

    ViesCandidate(String bpId, String taxId) {
      this.bpId  = bpId;
      this.taxId = taxId;
    }
  }

  /**
   * Outcome of the eligibility gate: the partners to send to VIES, plus how many were rejected
   * PERMANENTLY.
   *
   * <p>ETP-5027 (QA F5): {@code notEligible} must stay separate from "still pending". A partner
   * whose tax-id key is not {@code NOI}, or whose tax id is blank, fails the gate on EVERY
   * future click — folding it into a count the UI describes as re-runnable invites an
   * unbreakable loop. Partners the gate could not even read (a row-level DB error) are in
   * NEITHER bucket: they are transient and fall through to {@code stillPending}.
   */
  static final class ViesGateResult {
    final List<ViesCandidate> eligible = new ArrayList<>();
    int notEligible;
  }

  /**
   * Re-runs VIES validation for the operators of a declaration whose NIF-IVA is still pending,
   * and returns the outcome counts backing the "N NIF-IVA con validación VIES pendiente" banner.
   *
   * <p>The operator set is resolved SERVER-SIDE from {@code orgId/year/period} by reusing
   * {@code owner.computeOperators}: the caller never supplies a list of business-partner ids, so
   * no extra authorization check is needed — a user who may read the declaration may re-validate
   * exactly the partners that declaration already exposes to them.
   *
   * <p>Response shape (fixed contract):
   * <pre>{ "validated": N, "valid": N, "invalid": N,
   *   "notEligible": N, "failed": N, "stillPending": N }</pre>
   * {@code validated} is the number of pending operators this call accounted for, and
   * {@code valid + invalid + notEligible + failed + stillPending} always equals it: every
   * pending operator lands in exactly ONE bucket.
   *
   * <ul>
   *   <li>{@code valid} / {@code invalid} — VIES answered conclusively AND the answer was
   *       successfully written back to {@code C_BPartner}. A result that was not persisted is
   *       NOT counted here: the user reloads against the database, not against this response
   *       (ETP-5027, QA F2).</li>
   *   <li>{@code notEligible} — the partner failed the eligibility gate (tax-id key is not
   *       {@code NOI}, or the tax id is blank) or no longer exists. This is a PERMANENT
   *       condition: the same partner fails the same gate on every future click, so the UI must
   *       not invite a re-run for it (ETP-5027, QA F5). Split out of {@code stillPending}
   *       precisely so the copy can say what needs fixing instead of looping.</li>
   *   <li>{@code failed} — VIES answered conclusively but the write-back failed. Transient and
   *       worth retrying, but it must NOT be reported as success.</li>
   *   <li>{@code stillPending} — genuinely inconclusive right now: VIES could not answer
   *       (timeout, or the very common {@code MS_MAX_CONCURRENT_REQ}), or the partner was
   *       deferred past {@link #VIES_BATCH_CAP}. A non-zero value is a NORMAL outcome, never an
   *       error, and a re-run is the right follow-up.</li>
   * </ul>
   */
  JSONObject handleValidateVies(Fiscal349BoxesHandler owner, String orgId, int year, String period)
      throws Exception {
    JSONObject   operators  = owner.computeOperators(orgId, year, period);
    List<String> pendingIds = pendingBpIds(operators.optJSONArray(OPERATORS));
    return validatePendingVies(owner, pendingIds);
  }

  /**
   * The distinct business-partner ids of the operator rows whose VIES status is still pending.
   * The same partner can appear on several rows (one per AEAT key, plus rectificative rows), so
   * the result is de-duplicated — a partner must be checked once, not once per row.
   */
  static List<String> pendingBpIds(JSONArray operators) {
    Set<String> ids = new LinkedHashSet<>();
    if (operators == null) {
      return new ArrayList<>(ids);
    }
    for (int i = 0; i < operators.length(); i++) {
      JSONObject op = operators.optJSONObject(i);
      if (op == null || !Fiscal349BoxesHandler.VIES_PENDING.equals(op.optString("vies"))) {
        continue;
      }
      String bpId = op.optString("bpId");
      if (StringUtils.isNotBlank(bpId)) {
        ids.add(bpId);
      }
    }
    return new ArrayList<>(ids);
  }

  /**
   * Drives the four phases of a validation run, in this order and for this reason:
   * <ol>
   *   <li><b>Read + gate</b> (short DB work) — resolve each partner's taxId/key and drop the
   *       ineligible ones.</li>
   *   <li><b>Release the DAL session</b> — see {@code Fiscal349BoxesHandler#releaseDalConnection()}.
   *       This step is what actually makes phase 3 connection-free; without it the whole network
   *       phase would run with a transaction open and a pooled connection pinned to the request
   *       thread.</li>
   *   <li><b>Call VIES</b> (slow, network) — performed with NO DB connection held.</li>
   *   <li><b>Persist</b> (short DB work, on a fresh session) — write back only the conclusive
   *       results.</li>
   * </ol>
   */
  JSONObject validatePendingVies(Fiscal349BoxesHandler owner, List<String> pendingIds)
      throws Exception {
    List<String> all = pendingIds != null ? pendingIds : new ArrayList<>();
    int total = all.size();

    List<String> batch = total > VIES_BATCH_CAP ? all.subList(0, VIES_BATCH_CAP) : all;

    ViesGateResult      gate     = loadViesCandidates(batch);
    owner.releaseDalConnection();
    Map<String, String> statuses = checkVatInParallel(owner, gate.eligible);
    Set<String>         persisted = persistViesStatuses(statuses);

    // ETP-5027 (QA F2): the conclusive counts are derived from what was actually WRITTEN, not
    // from the in-memory answers. Reporting "20 valid" after a failed UPDATE told the user the
    // job was done and then showed them 20 still-pending badges on the next reload.
    int valid   = 0;
    int invalid = 0;
    int failed  = 0;
    for (Map.Entry<String, String> e : statuses.entrySet()) {
      boolean isValid   = ViesService.STATUS_VALID.equals(e.getValue());
      boolean isInvalid = ViesService.STATUS_INVALID.equals(e.getValue());
      if (!isValid && !isInvalid) {
        continue; // inconclusive — nothing to persist, falls through to stillPending
      }
      if (!persisted.contains(e.getKey())) {
        failed++;
      } else if (isValid) {
        valid++;
      } else {
        invalid++;
      }
    }

    JSONObject result = new JSONObject();
    result.put("validated",    total);
    result.put("valid",        valid);
    result.put("invalid",      invalid);
    result.put("notEligible",  gate.notEligible);
    result.put("failed",       failed);
    // Never negative: notEligible + eligible <= batch <= total, and the conclusive buckets
    // together cannot exceed the eligible set.
    result.put("stillPending", total - valid - invalid - gate.notEligible - failed);
    return result;
  }

  /**
   * Loads {@code (taxId, taxIdKey)} for each candidate and keeps only the partners that pass the
   * SAME eligibility gate {@code ViesStatusObserver} applies in the bptaxidkey module: tax id key
   * {@code '2'} (NOI) AND a non-blank tax id. Go must behave identically to Classic here, so no
   * looser rule (such as "any EU-prefixed tax id") may be substituted. A partner that fails the
   * gate is simply absent from the result and therefore counted as still-pending without ever
   * being sent to VIES.
   *
   * <p>Read with plain JDBC rather than OBDal so this stays a projection of two columns and does
   * not pull entities into the session that the persist phase would then have to fight with.
   *
   * <p>ETP-5027 (QA F2): the per-row work is wrapped in its OWN try/catch. The catch used to sit
   * outside the loop, so a single unreadable row silently discarded every candidate that had not
   * been processed yet — a partial batch reported as a complete run. Only a failure to open the
   * statement at all aborts the phase now, and it leaves the whole batch in {@code stillPending}
   * rather than pretending it was checked.
   */
  ViesGateResult loadViesCandidates(List<String> bpIds) {
    ViesGateResult gate = new ViesGateResult();
    if (bpIds == null || bpIds.isEmpty()) {
      return gate;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT taxid, em_obtik_tax_id_key FROM c_bpartner WHERE c_bpartner_id = ?")) {
      for (String bpId : bpIds) {
        readCandidate(ps, bpId, gate);
      }
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("validate-vies: could not load VIES candidates: " + e.getMessage());
    }
    return gate;
  }

  /**
   * One row of the eligibility gate. A partner that is missing or fails the gate is counted as
   * PERMANENTLY not eligible; a row that could not be READ is counted as neither, so it stays
   * transiently pending and the next click retries it.
   */
  private void readCandidate(PreparedStatement ps, String bpId, ViesGateResult gate) {
    try {
      ps.setString(1, bpId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          gate.notEligible++;
          return;
        }
        String taxId    = rs.getString("taxid");
        String taxIdKey = rs.getString("em_obtik_tax_id_key");
        if (NOI_TAX_ID_KEY.equals(taxIdKey) && StringUtils.isNotBlank(taxId)) {
          gate.eligible.add(new ViesCandidate(bpId, taxId));
        } else {
          gate.notEligible++;
        }
      }
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("validate-vies: could not read VIES candidate " + bpId + ": "
          + e.getMessage());
    }
  }

  /**
   * Calls {@code owner.checkVat} for every candidate with BOUNDED parallelism.
   *
   * <p>Neither a sequential loop nor a naive parallel stream is acceptable here: sequential
   * would need up to 10 s per partner and blow the request timeout, while a parallel stream
   * would fan out on the common pool and trip the VIES per-member-state concurrency limit
   * ({@code MS_MAX_CONCURRENT_REQ}) — which makes the run slower AND less conclusive, since
   * throttled answers come back as pending. A fixed pool of {@link #VIES_MAX_THREADS} is the
   * compromise. {@code checkVat} never throws, so a failure surfaces as {@code P}.
   *
   * <p>{@code owner} is threaded through explicitly (never stored) so that a Mockito
   * {@code spy()} of the calling {@link Fiscal349BoxesHandler} always resolves this callback to
   * the spy — see the class-level javadoc for why a stored field would silently go stale.
   *
   * @return bpId → VIES status ({@code V}/{@code I}/{@code P}); one entry per candidate
   */
  Map<String, String> checkVatInParallel(Fiscal349BoxesHandler owner,
      List<ViesCandidate> candidates) {
    Map<String, String> statuses = new LinkedHashMap<>();
    if (candidates == null || candidates.isEmpty()) {
      return statuses;
    }
    ExecutorService pool =
        Executors.newFixedThreadPool(Math.min(VIES_MAX_THREADS, candidates.size()));
    try {
      List<Callable<String>> tasks = candidates.stream()
          .map(c -> (Callable<String>) () -> owner.checkVat(c.taxId))
          .collect(Collectors.toList());
      List<Future<String>> futures =
          pool.invokeAll(tasks, VIES_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      for (int i = 0; i < candidates.size(); i++) {
        statuses.put(candidates.get(i).bpId, statusOf(futures.get(i)));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      AbstractFiscalHandler.log.warn("validate-vies: VIES phase interrupted");
      for (ViesCandidate c : candidates) {
        statuses.putIfAbsent(c.bpId, ViesService.STATUS_PENDING);
      }
    } finally {
      pool.shutdownNow();
    }
    return statuses;
  }

  /** A task that was cancelled by the phase timeout, or failed, yields "pending". */
  private static String statusOf(Future<String> future) {
    try {
      String status = future.get();
      return status != null ? status : ViesService.STATUS_PENDING;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ViesService.STATUS_PENDING;
    } catch (Exception e) {
      return ViesService.STATUS_PENDING;
    }
  }

  /**
   * Writes the conclusive VIES statuses back to {@code C_BPartner} with raw JDBC.
   *
   * <p>Two deliberate constraints, both matching {@code ContactsLocationAddressHandler}:
   * <ul>
   *   <li><b>JDBC, not OBDal.</b> {@code bp.setOBTIKVIESStatus(...)} would fire
   *       {@code ViesStatusObserver.onUpdate}, which calls {@code checkVat} a SECOND time for
   *       that partner — doubling traffic against a service that already rate-limits us.</li>
   *   <li><b>Only {@code V} and {@code I}.</b> A {@code P} result means nothing was learned;
   *       persisting it is a no-op at best and clobbers a previously known answer at worst.</li>
   * </ul>
   *
   * <p>ETP-5027 (QA F2): the caller needs to know what was actually WRITTEN, so this returns the
   * ids whose UPDATE reported an affected row. A failure no longer disappears into a
   * {@code log.warn} while the response reports success — the caller turns every unpersisted
   * conclusive answer into the {@code failed} count. Per-row errors are isolated so one bad id
   * cannot discard the rest of the batch.
   *
   * @return the bpIds whose status was successfully persisted; never null
   */
  Set<String> persistViesStatuses(Map<String, String> statuses) {
    Set<String> persisted = new LinkedHashSet<>();
    if (statuses == null || statuses.isEmpty()) {
      return persisted;
    }
    Map<String, String> conclusive = statuses.entrySet().stream()
        .filter(e -> ViesService.STATUS_VALID.equals(e.getValue())
            || ViesService.STATUS_INVALID.equals(e.getValue()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
            (a, b) -> a, LinkedHashMap::new));
    if (conclusive.isEmpty()) {
      return persisted;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE c_bpartner SET em_obtik_viesstatus = ? WHERE c_bpartner_id = ?")) {
      for (Map.Entry<String, String> e : conclusive.entrySet()) {
        persistOne(ps, e.getKey(), e.getValue(), persisted);
      }
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("validate-vies: could not persist VIES statuses: " + e.getMessage());
    }
    return persisted;
  }

  /**
   * One write-back. An UPDATE affecting zero rows (the partner disappeared between the gate and
   * the persist phase) counts as NOT persisted, exactly like a thrown error would — in both
   * cases the user's next reload will still show the partner as pending.
   */
  private void persistOne(PreparedStatement ps, String bpId, String status,
      Set<String> persisted) {
    try {
      ps.setString(1, status);
      ps.setString(2, bpId);
      if (ps.executeUpdate() > 0) {
        persisted.add(bpId);
      } else {
        AbstractFiscalHandler.log.warn("validate-vies: VIES status for " + bpId
            + " matched no C_BPartner row");
      }
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("validate-vies: could not persist VIES status for " + bpId
          + ": " + e.getMessage());
    }
  }
}
