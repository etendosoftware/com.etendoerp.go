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
import static org.junit.Assert.assertFalse;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.etendoerp.go.payment.EnvironmentAccessPolicy.Configuration;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.EnvironmentType;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.SubscriptionStatus;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService.EnvironmentSnapshot;

/**
 * Who is owed which demo trial notice, at every threshold and boundary.
 *
 * @covers com.etendoerp.go.payment.DemoTrialNoticePlanner
 * @covers com.etendoerp.go.payment.DemoTrialNotice
 */
public class DemoTrialNoticePlannerTest {

  private static final Instant START = Instant.parse("2026-10-01T09:00:00Z");
  private static final Configuration FOURTEEN_DAYS = new Configuration(14, 15);
  private static final Instant END = START.plus(Duration.ofDays(14));
  private static final EnvironmentSnapshot DEMO = new EnvironmentSnapshot(EnvironmentType.DEMO,
      START, SubscriptionStatus.NONE, null, false);

  private final DemoTrialNoticePlanner planner =
      new DemoTrialNoticePlanner(DemoTrialNoticePlanner.DEFAULT_REMINDER_DAYS);

  @Test
  public void nothingIsOwedWhileMoreDaysRemainThanTheLargestThreshold() {
    assertNothingOwed(END.minus(Duration.ofDays(4)));
    // 3 days and one second left reads as 4 in the picker, so it is not the 3-day reminder yet.
    assertNothingOwed(END.minus(Duration.ofDays(3)).minusSeconds(1));
  }

  @Test
  public void threeDayReminderStartsExactlyAtThreeDaysLeft() {
    DemoTrialNotice notice = owed(END.minus(Duration.ofDays(3)));

    assertEquals(DemoTrialNotice.Kind.REMINDER, notice.getKind());
    assertEquals("reminder-3", notice.milestone());
    assertEquals(3, notice.getRemainingDays());
    assertEquals(START, notice.getTrialStartedAt());
    assertEquals(END, notice.getTrialEndsAt());
  }

  @Test
  public void missedRunStillOwesTheThreeDayReminderUnderItsOwnMilestone() {
    DemoTrialNotice notice = owed(END.minus(Duration.ofDays(2)));

    // Same milestone as the day before, so an already delivered reminder is recognised as such.
    assertEquals("reminder-3", notice.milestone());
    assertEquals(2, notice.getRemainingDays());
  }

  @Test
  public void oneDayReminderCoversTheLastDayUpToTheEnd() {
    assertEquals("reminder-1", owed(END.minus(Duration.ofDays(1))).milestone());
    assertEquals("reminder-1", owed(END.minusSeconds(1)).milestone());
    assertEquals(1, owed(END.minusSeconds(1)).getRemainingDays());
  }

  @Test
  public void expiredNoticeIsOwedFromTheEndForAWindowOfDays() {
    DemoTrialNotice atEnd = owed(END);
    assertEquals(DemoTrialNotice.Kind.EXPIRED, atEnd.getKind());
    assertEquals("expired", atEnd.milestone());
    assertEquals(0, atEnd.getRemainingDays());

    Instant windowEnd = END.plus(
        Duration.ofDays(DemoTrialNoticePlanner.EXPIRED_NOTICE_WINDOW_DAYS));
    assertEquals("expired", owed(windowEnd.minusSeconds(1)).milestone());
    // Past the window an old demo is left alone: no surprise email months after it expired.
    assertNothingOwed(windowEnd);
    assertNothingOwed(END.plus(Duration.ofDays(90)));
  }

  @Test
  public void demoAssociatedWithProductiveIsNeverNotified() {
    EnvironmentSnapshot associated = new EnvironmentSnapshot(EnvironmentType.DEMO, START,
        SubscriptionStatus.CURRENT, null, true);

    // The policy cuts it as DEMO_TRIAL_EXPIRED, but its owner just paid: no "trial ended" email.
    assertFalse(planner.plan(associated, END.plusSeconds(1), FOURTEEN_DAYS).isPresent());
    assertFalse(planner.plan(associated, END.minus(Duration.ofDays(1)), FOURTEEN_DAYS)
        .isPresent());
  }

  @Test
  public void productiveAndUnmanagedEnvironmentsAreNeverNotified() {
    EnvironmentSnapshot productive = new EnvironmentSnapshot(EnvironmentType.PRODUCTIVE, null,
        SubscriptionStatus.CURRENT, null, false);

    assertFalse(planner.plan(productive, END, FOURTEEN_DAYS).isPresent());
    assertFalse(planner.plan(null, END, FOURTEEN_DAYS).isPresent());
  }

  @Test
  public void trialRestartedWithANewStartIsNotifiedAgainUnderTheNewStart() {
    Instant restartedAt = END.plus(Duration.ofDays(10));
    EnvironmentSnapshot restarted = new EnvironmentSnapshot(EnvironmentType.DEMO, restartedAt,
        SubscriptionStatus.NONE, null, false);

    DemoTrialNotice notice = planner.plan(restarted,
        restartedAt.plus(Duration.ofDays(11)), FOURTEEN_DAYS).get();

    assertEquals("reminder-3", notice.milestone());
    assertEquals(restartedAt, notice.getTrialStartedAt());
  }

  @Test
  public void trialShorterThanAThresholdOwesItsReminderFromTheStart() {
    Configuration oneDay = new Configuration(1, 15);

    assertEquals("reminder-1", planner.plan(DEMO, START, oneDay).get().milestone());
  }

  @Test
  public void thresholdsAreTakenInAnyOrderAndNonPositiveOnesIgnored() {
    DemoTrialNoticePlanner custom = new DemoTrialNoticePlanner(Arrays.asList(7, 0, -2, 2));

    assertEquals(Arrays.asList(2, 7), custom.getReminderDays());
    assertEquals("reminder-7", custom.plan(DEMO, END.minus(Duration.ofDays(5)), FOURTEEN_DAYS)
        .get().milestone());
    assertEquals("reminder-2", custom.plan(DEMO, END.minus(Duration.ofDays(2)), FOURTEEN_DAYS)
        .get().milestone());
  }

  @Test
  public void configuredThresholdsAreParsedAndAMalformedValueKeepsTheDefaults() {
    List<Integer> defaults = DemoTrialNoticePlanner.DEFAULT_REMINDER_DAYS;

    assertEquals(Arrays.asList(1, 3), DemoTrialNoticePlanner.parseReminderDays("3,1"));
    assertEquals(Arrays.asList(2, 5), DemoTrialNoticePlanner.parseReminderDays(" 5, x, 0, 2 "));
    assertEquals(defaults, DemoTrialNoticePlanner.parseReminderDays(null));
    assertEquals(defaults, DemoTrialNoticePlanner.parseReminderDays("  "));
    assertEquals(defaults, DemoTrialNoticePlanner.parseReminderDays("abc,-1"));
  }

  private DemoTrialNotice owed(Instant now) {
    Optional<DemoTrialNotice> notice = planner.plan(DEMO, now, FOURTEEN_DAYS);
    if (!notice.isPresent()) {
      throw new AssertionError("Expected a notice at " + now);
    }
    return notice.get();
  }

  private void assertNothingOwed(Instant now) {
    assertFalse("No notice expected at " + now,
        planner.plan(DEMO, now, FOURTEEN_DAYS).isPresent());
  }
}
