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
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.ENTITY;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.RECORD_ID;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.instant;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.tokenFor;

import java.util.Calendar;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.Traceable;
import org.openbravo.dal.service.OBDal;

/**
 * The version comparison itself (ETP-5073 / DOC-04).
 *
 * <p>Semantics are copied from core's {@code areDatesEqual(d1, d2, true, false)}, which zeroes
 * milliseconds on both sides and compares the instants. Copying rather than inventing a tolerance
 * is the point: looser reports conflicts that are not there, stricter misses real ones.
 */
class NeoRecordVersionComparisonTest {


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
    NeoRecordVersionFixtures.TraceableRecord traceable = mock(NeoRecordVersionFixtures.TraceableRecord.class);
    when(traceable.getUpdated()).thenReturn(stored);
    when(obDal.get(anyString(), any())).thenReturn(traceable);
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
