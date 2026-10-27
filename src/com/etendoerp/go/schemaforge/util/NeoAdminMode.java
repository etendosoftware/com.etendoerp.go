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

import org.openbravo.dal.core.OBContext;

/**
 * Enters admin mode without leaking it when entering fails.
 *
 * <p>{@link OBContext#setAdminMode(boolean)} pushes its stack entry before it can fail (it builds
 * the admin context when the thread has none). A caller's {@code try/finally} is not entered yet
 * at that point, so without the pop below the entry stays on the thread and every later request
 * or test on it runs in admin mode.
 */
public final class NeoAdminMode {

  private NeoAdminMode() {
  }

  /**
   * Same as {@code OBContext.setAdminMode(true)}, but pops the entry again if it fails. Pair it
   * with {@code OBContext.restorePreviousMode()} in a {@code finally}, exactly as before.
   */
  public static void enter() {
    try {
      OBContext.setAdminMode(true);
    } catch (RuntimeException e) {
      OBContext.restorePreviousMode();
      throw e;
    }
  }
}
