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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import java.util.Calendar;
import java.util.Date;

import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.Traceable;
import org.openbravo.service.json.JsonUtils;

/**
 * Shared fixtures for the {@link NeoRecordVersion} tests (ETP-5073 / DOC-04).
 *
 * <p>Extracted because those tests live in two top-level classes rather than one class with
 * {@code @Nested} groups: this Sonar version does not recognise nested test classes and flags the
 * outer one as having no tests (java:S2187, blocker). The two groups genuinely need different DAL
 * setup — the undecidable-argument cases assert the DAL is never touched, so they must not stub
 * {@code getInstance} — so they cannot simply be merged.
 *
 * <p>The fixtures live here rather than being copied into both: a second copy of a test's notion of
 * "what a token looks like" would drift from the first, and the whole point of these tests is that
 * the token format matches what core actually emits.
 */
final class NeoRecordVersionFixtures {

  static final String ENTITY = "Order";
  static final String RECORD_ID = "95E2A8B50A254B2AAE6774B8C2F28120";

  private NeoRecordVersionFixtures() {
    // fixtures holder — no instances
  }

  /**
   * Stand-in for any record a write can target: a {@link BaseOBObject} that carries audit info.
   * Declared rather than mocking a concrete Etendo entity so the test states exactly the two
   * properties the lookup depends on — being a {@code BaseOBObject} (what {@code OBDal.get}
   * returns) and being {@link Traceable} (what exposes the stored {@code updated}).
   */
  abstract static class TraceableRecord extends BaseOBObject implements Traceable {
  }

  /**
   * The {@code updated} token as a client actually receives it: core formats the column with
   * {@code JsonUtils.createDateTimeFormat()} — a pattern with NO millisecond field, so the value is
   * truncated to the second — and hands it out in XSD form, with a colon in the offset. Building the
   * token through the same helpers keeps these tests independent of the host's timezone.
   */
  static String tokenFor(Date value) {
    return JsonUtils.convertToCorrectXSDFormat(JsonUtils.createDateTimeFormat().format(value));
  }

  /** A fixed instant, with an explicit second and millisecond, in the host's timezone. */
  static Date instant(int second, int millisecond) {
    Calendar calendar = Calendar.getInstance();
    calendar.set(2026, Calendar.AUGUST, 28, 12, 30, second);
    calendar.set(Calendar.MILLISECOND, millisecond);
    return calendar.getTime();
  }
}
