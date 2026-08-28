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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Calendar;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.Traceable;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.JsonUtils;

/**
 * Unit tests for {@link NeoRecordVersion} (ETP-5073 / DOC-04).
 *
 * <p>The stored {@code updated} is read through {@code OBDal.getInstance().get(...)}, so the DAL
 * is mocked statically — the same {@code mockStatic(OBDal.class)} +
 * {@code when(OBDal::getInstance)} shape the rest of this suite uses (see
 * {@code McpToolRouterSupportTest}).
 *
 * <p>The emphasis is deliberately on the NEGATIVE answers. A missed conflict degrades to core's
 * own check, which still runs behind this one; a FABRICATED conflict does not degrade to anything
 * — it blocks a legitimate save with an explanation the user cannot act on. Every "cannot decide"
 * input therefore has its own case, and so does the millisecond tolerance, which is the one place
 * where a plausible-looking implementation (strict equality) would report a conflict on every
 * single write.
 */
class NeoRecordVersionTest {

  private static final String ENTITY = "Order";
  private static final String RECORD_ID = "95E2A8B50A254B2AAE6774B8C2F28120";

  /**
   * Stand-in for any record a write can target: a {@link BaseOBObject} that carries audit info.
   * Declared here rather than mocking a concrete Etendo entity so the test states exactly the two
   * properties {@code readStoredUpdated} depends on — being a {@code BaseOBObject} (what
   * {@code OBDal.get} returns) and being {@link Traceable} (what exposes {@code getUpdated}).
   */
  abstract static class TraceableRecord extends BaseOBObject implements Traceable {
  }

  /**
   * The {@code updated} token as a client actually receives it: core formats the column with
   * {@code JsonUtils.createDateTimeFormat()} (a pattern with NO millisecond field, so the value is
   * truncated to the second) and then hands it out in XSD form, with a colon in the offset. This is
   * the exact inverse of the {@code convertFromXSDToJavaFormat} + {@code createDateTimeFormat}
   * round-trip {@code NeoRecordVersion} performs, which is why building the token this way keeps
   * the test independent of the host's timezone.
   */
  private static String tokenFor(Date value) {
    return JsonUtils.convertToCorrectXSDFormat(JsonUtils.createDateTimeFormat().format(value));
  }

  /** A fixed instant, with an explicit second and millisecond, in the host's timezone. */
  private static Date instant(int second, int millisecond) {
    Calendar calendar = Calendar.getInstance();
    calendar.set(2026, Calendar.AUGUST, 28, 12, 30, second);
    calendar.set(Calendar.MILLISECOND, millisecond);
    return calendar.getTime();
  }

  /**
   * Cases where {@code isStale} never gets as far as the DAL: the arguments alone make the
   * question unanswerable. {@code verifyNoInteractions} is part of the assertion — the guard must
   * short-circuit, not merely happen to return false after a pointless lookup.
   */
  @Nested
  @DisplayName("undecidable arguments answer false without touching the DAL")
  class UndecidableArguments {

    private MockedStatic<OBDal> obDalMock;

    @BeforeEach
    void setUp() {
      obDalMock = mockStatic(OBDal.class);
    }

    @AfterEach
    void tearDown() {
      obDalMock.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("a blank entity name is not a conflict")
    void blankEntityName(String entityName) {
      assertFalse(NeoRecordVersion.isStale(entityName, RECORD_ID, tokenFor(instant(0, 0))));
      obDalMock.verifyNoInteractions();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("a blank record id is not a conflict")
    void blankRecordId(String recordId) {
      assertFalse(NeoRecordVersion.isStale(ENTITY, recordId, tokenFor(instant(0, 0))));
      obDalMock.verifyNoInteractions();
    }

    /**
     * {@code "null"} is in the list because that is what a caller sends when it stringifies a
     * missing value — JSON {@code null} read through {@code optString} arrives as the four
     * characters, not as a Java null, and treating it as a real token would send it to the parser.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "null" })
    @DisplayName("a blank or literal-null client value is not a conflict")
    void blankClientValue(String clientValue) {
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, clientValue));
      obDalMock.verifyNoInteractions();
    }
  }

  /**
   * Cases that do reach the DAL. Each one stubs what {@code OBDal.get} answers and asserts the
   * decision that follows.
   */
  @Nested
  @DisplayName("comparison against the stored version")
  class StoredComparison {

    private MockedStatic<OBDal> obDalMock;
    private OBDal obDal;

    @BeforeEach
    void setUp() {
      obDal = mock(OBDal.class);
      obDalMock = mockStatic(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    }

    @AfterEach
    void tearDown() {
      obDalMock.close();
    }

    /** Stubs the DAL to return a traceable record whose stored {@code updated} is {@code stored}. */
    private void storedUpdatedIs(Date stored) {
      TraceableRecord record = mock(TraceableRecord.class);
      when(record.getUpdated()).thenReturn(stored);
      when(obDal.get(anyString(), any())).thenReturn(record);
    }

    @Test
    @DisplayName("the same value is not stale")
    void identicalValueIsNotStale() {
      Date stored = instant(15, 0);
      storedUpdatedIs(stored);
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(stored)));
    }

    /**
     * The case the whole tolerance exists for. Postgres keeps sub-second precision on the column,
     * while the value that travels to the client is formatted to the second — so a caller echoing
     * back exactly what it was given still differs from the row by whatever fraction was dropped.
     * Compared strictly, EVERY write would look stale. Mirrors core's
     * {@code JsonToDataConverter#areDatesEqual(d1, d2, true, false)}, which zeroes
     * {@code MILLISECOND} on both sides before comparing.
     */
    @Test
    @DisplayName("a sub-second difference is not stale — the client token is truncated, not rounded")
    void subSecondDifferenceIsNotStale() {
      storedUpdatedIs(instant(15, 750));
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(15, 0))));
    }

    @Test
    @DisplayName("a difference of a whole second is stale")
    void oneSecondDifferenceIsStale() {
      storedUpdatedIs(instant(16, 0));
      assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(15, 0))));
    }

    @Test
    @DisplayName("a difference of minutes is stale")
    void laterStoredValueIsStale() {
      Calendar later = Calendar.getInstance();
      later.setTime(instant(15, 0));
      later.add(Calendar.MINUTE, 7);
      storedUpdatedIs(later.getTime());
      assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(15, 0))));
    }

    /**
     * A token the caller sent in the future is still a mismatch, and still a conflict: the only
     * value that is not a conflict is the one the row actually holds. Guards against a comparison
     * written as "stored is after client" instead of "the two differ".
     */
    @Test
    @DisplayName("a client value ahead of the stored one is stale too")
    void clientValueAheadIsStale() {
      storedUpdatedIs(instant(15, 0));
      assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(16, 0))));
    }

    /**
     * An unparseable token is a malformed request, not a concurrency failure. Answering
     * {@code true} here would send the user to reload a record that was never the problem, and
     * core rejects the bad value on its own terms anyway.
     */
    @ParameterizedTest
    @ValueSource(strings = { "not-a-date", "28/08/2026", "1756395000000", "2026-08-28" })
    @DisplayName("an unparseable client value is not a conflict")
    void unparseableClientValueIsNotStale(String clientValue) {
      storedUpdatedIs(instant(15, 0));
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, clientValue));
    }

    @Test
    @DisplayName("a record with no stored updated is not a conflict")
    void nullStoredUpdatedIsNotStale() {
      storedUpdatedIs(null);
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(15, 0))));
    }

    @Test
    @DisplayName("a record that is not Traceable is not a conflict")
    void nonTraceableRecordIsNotStale() {
      when(obDal.get(anyString(), any())).thenReturn(mock(BaseOBObject.class));
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(15, 0))));
    }

    @Test
    @DisplayName("a record that cannot be found is not a conflict")
    void missingRecordIsNotStale() {
      when(obDal.get(anyString(), any())).thenReturn(null);
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(15, 0))));
    }

    /**
     * An unknown entity name, a closed session or any other DAL failure must not be reported as a
     * conflict: the check is a pre-check, and a "don't know" has to degrade to core's answer rather
     * than to a fabricated 409.
     */
    @Test
    @DisplayName("a DAL failure is not a conflict")
    void dalFailureIsNotStale() {
      when(obDal.get(anyString(), any())).thenThrow(new IllegalStateException("no active session"));
      assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, tokenFor(instant(15, 0))));
    }
  }
}
