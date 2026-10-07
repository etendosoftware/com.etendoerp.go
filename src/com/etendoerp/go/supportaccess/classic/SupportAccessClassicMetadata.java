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
package com.etendoerp.go.supportaccess.classic;

/**
 * ETP-5351 — ids and names of the Classic "Support · Companies" window and its two processes, as
 * declared in this module's sourcedata, and the names of the "Access as Support" parameters.
 *
 * <p>{@code EnsureSupportAccessSeedScript} repeats the window and process ids on purpose (module
 * scripts never import from {@code src/}); keep both in sync.</p>
 */
public final class SupportAccessClassicMetadata {

  /** {@code AD_Window_ID} of "Support · Companies". */
  public static final String WINDOW_ID = "9010058657404BEDA7795C3AAA712FC8";
  /** {@code OBUIAPP_Process_ID} of "Access as Support". */
  public static final String ISSUE_PROCESS_ID = "417F4143D1EB4AE39A5F89E33675001E";
  /** {@code OBUIAPP_Process_ID} of "Close Support Session". */
  public static final String REVOKE_PROCESS_ID = "1081A92E729541DEBDB6BE46157A93CA";

  /** Column name of the "Reason" parameter of "Access as Support". */
  public static final String PARAM_REASON = "reason";
  /** Column name of the "Duration" parameter of "Access as Support". */
  public static final String PARAM_DURATION = "duration_minutes";

  /**
   * The durations, in minutes, of the {@code ETGO_SupportSessionDuration} list reference, in
   * ascending order. Keep in sync with its {@code AD_REF_LIST} values.
   */
  private static final int[] DURATION_OPTIONS = { 30, 60, 120, 240, 480 };

  private SupportAccessClassicMetadata() {
  }

  /**
   * The durations offered by the "Duration" parameter.
   *
   * @return a copy of the options, in minutes, ascending
   */
  public static int[] durationOptions() {
    return DURATION_OPTIONS.clone();
  }
}
