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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.ENTITY;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.RECORD_ID;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.instant;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.tokenFor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * Cases where {@link NeoRecordVersion} cannot decide, and must therefore answer "not stale"
 * (ETP-5073 / DOC-04).
 *
 * <p>Split from the comparison cases into its own top-level class rather than a {@code @Nested}
 * one: this Sonar version does not recognise nested test classes and flags the outer class as
 * having no tests (java:S2187, blocker). The two groups also need genuinely different DAL setup —
 * these tests assert the DAL is never touched at all, so they must NOT stub getInstance.
 *
 * <p>Answering "not stale" for an undecidable input is deliberate: core's own check still runs
 * behind this one, so a "don't know" degrades to core's answer instead of to a fabricated
 * conflict — and a fabricated conflict blocks a legitimate save with an explanation the user
 * cannot act on.
 */
class NeoRecordVersionUndecidableTest {


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
