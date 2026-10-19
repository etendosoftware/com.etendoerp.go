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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * One pass of the demo trial notices: only owed notices are sent, and one environment failing
 * never stops the others.
 *
 * @covers com.etendoerp.go.payment.DemoTrialNoticeService
 */
public class DemoTrialNoticeServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");

  @Test
  public void sendsOnlyToEnvironmentsThatOweANotice() throws Exception {
    FakeSource source = new FakeSource("owed", "not-owed").owing("owed");
    FakeSender sender = new FakeSender()
        .answer("owed", TransactionalEmailService.STATUS_SENT, 200);

    DemoTrialNoticeService.Result result = new DemoTrialNoticeService(source, sender).run(NOW);

    assertEquals(Arrays.asList("owed"), sender.sentTo);
    assertEquals(2, result.getChecked());
    assertEquals(1, result.getSent());
    assertEquals(1, result.getNotOwed());
    assertEquals(0, result.getFailed());
  }

  @Test
  public void aNoticeAlreadyDeliveredCountsAsAlreadySentNotAsAFailure() throws Exception {
    FakeSource source = new FakeSource("client-1").owing("client-1");
    FakeSender sender = new FakeSender()
        .answer("client-1", TransactionalEmailService.STATUS_DUPLICATE, 200);

    DemoTrialNoticeService.Result result = new DemoTrialNoticeService(source, sender).run(NOW);

    assertEquals(1, result.getAlreadySent());
    assertEquals(0, result.getSent());
    assertEquals(0, result.getFailed());
  }

  @Test
  public void aFailingEnvironmentIsRecordedAndTheOthersAreStillNotified() throws Exception {
    FakeSource source = new FakeSource("broken", "throttled", "healthy")
        .owing("broken", "throttled", "healthy");
    FakeSender sender = new FakeSender()
        .failing("broken")
        .answer("throttled", TransactionalEmailService.STATUS_THROTTLED, 429)
        .answer("healthy", TransactionalEmailService.STATUS_SENT, 200);

    DemoTrialNoticeService.Result result = new DemoTrialNoticeService(source, sender).run(NOW);

    assertEquals(1, result.getSent());
    assertEquals(2, result.getFailed());
    assertTrue(result.getFailures().get("broken").contains("provider down"));
    assertTrue(result.getFailures().get("throttled").contains("HTTP 429 THROTTLED"));
    assertEquals(1, sender.discards);
    assertTrue(result.toString().contains("2 failed"));
  }

  @Test
  public void aSourceFailureForOneEnvironmentDoesNotStopTheRun() throws Exception {
    FakeSource source = new FakeSource("unreadable", "healthy").owing("healthy")
        .failingOn("unreadable");
    FakeSender sender = new FakeSender().answer("healthy", TransactionalEmailService.STATUS_SENT,
        200);

    DemoTrialNoticeService.Result result = new DemoTrialNoticeService(source, sender).run(NOW);

    assertEquals(Arrays.asList("healthy"), sender.sentTo);
    assertEquals(1, result.getFailed());
    assertEquals(1, result.getSent());
  }

  private static NeoResponse response(int httpStatus, String status) throws JSONException {
    JSONObject data = new JSONObject().put("status", status).put("message", "detail");
    return new NeoResponse(httpStatus,
        new JSONObject().put("response", new JSONObject().put("data", data)));
  }

  private static final class FakeSource implements DemoTrialNoticeSource {
    private final List<String> candidates;
    private final Set<String> owing = new HashSet<>();
    private final Set<String> failing = new HashSet<>();

    FakeSource(String... candidates) {
      this.candidates = Arrays.asList(candidates);
    }

    FakeSource owing(String... clientIds) {
      owing.addAll(Arrays.asList(clientIds));
      return this;
    }

    FakeSource failingOn(String clientId) {
      failing.add(clientId);
      return this;
    }

    @Override
    public List<String> findCandidateClientIds() {
      return candidates;
    }

    @Override
    public Optional<DemoTrialNoticeRecord> findOwedNotice(String clientId, Instant now) {
      if (failing.contains(clientId)) {
        throw new IllegalStateException("cannot read " + clientId);
      }
      if (!owing.contains(clientId)) {
        return Optional.empty();
      }
      return Optional.of(new DemoTrialNoticeRecord(clientId, "Demo", "es_ES", "Owner",
          "owner@example.com", DemoTrialNotice.expired(now, now)));
    }
  }

  private static final class FakeSender implements DemoTrialNoticeService.Sender {
    private final Map<String, NeoResponse> answers = new HashMap<>();
    private final Set<String> failing = new HashSet<>();
    private final List<String> sentTo = new ArrayList<>();
    private int discards;

    FakeSender answer(String clientId, String status, int httpStatus) throws JSONException {
      answers.put(clientId, response(httpStatus, status));
      return this;
    }

    FakeSender failing(String clientId) {
      failing.add(clientId);
      return this;
    }

    @Override
    public NeoResponse send(String clientId) {
      if (failing.contains(clientId)) {
        throw new IllegalStateException("provider down");
      }
      sentTo.add(clientId);
      return answers.get(clientId);
    }

    @Override
    public void discardPendingChanges() {
      discards++;
    }
  }
}
