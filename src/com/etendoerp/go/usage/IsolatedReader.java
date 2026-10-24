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

package com.etendoerp.go.usage;

import java.util.function.Supplier;

/**
 * ETP-5051 — how a quota report runs its reads: in their own session in production
 * ({@link IsolatedDalRead#call}), inline in a unit test ({@link #INLINE}). The seam the report
 * callers inject so a test needs no second thread and no database.
 */
@FunctionalInterface
public interface IsolatedReader {

  /** Runs the read on the caller's thread and session: for tests only. */
  IsolatedReader INLINE = IsolatedReader::inline;

  /** The production reader: a hop to a worker with its own DAL session. */
  IsolatedReader DAL = IsolatedDalRead::call;

  /**
   * Runs one read and returns its result.
   *
   * @param read the read
   * @param <T> the result type
   * @return what {@code read} returned
   */
  <T> T call(Supplier<T> read);

  /**
   * Runs {@code read} on the calling thread.
   *
   * @param read the read
   * @param <T> the result type
   * @return what {@code read} returned
   */
  static <T> T inline(Supplier<T> read) {
    return read.get();
  }
}
