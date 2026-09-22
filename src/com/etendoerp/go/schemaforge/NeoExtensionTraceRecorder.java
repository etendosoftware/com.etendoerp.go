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

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-process, per-thread buffer of {@link NeoExtensionTrace} entries.
 *
 * <h2>Why a thread-local, and why opt-in</h2>
 * <p>A dispatch belongs to exactly one request, and a request is served by exactly one thread, so a
 * thread-local is the only place where a trace can be read back without either a shared lock or a
 * plumbing change to every signature between the dispatcher and the assertion. An injectable
 * recorder would have been the alternative, but the three write entry points are reached through
 * {@code static} methods ({@code NeoServletSupport.handleWithHooks}) and through a router that
 * builds its own collaborators, so injecting one would mean changing exactly the call shapes this
 * step must leave untouched.</p>
 *
 * <p>Recording is <b>off unless a thread asks for it</b>. Servlet threads are pooled and live for
 * the whole deployment, so an always-on buffer would grow without bound and never be read — the
 * always-on channel is the INFO log the dispatcher emits, which has no such problem. A test calls
 * {@link #beginRecording()} in its setup, reads {@link #recorded()}, and calls
 * {@link #stopRecording()} in its teardown; without that call this class costs one thread-local
 * read per dispatch and stores nothing.</p>
 */
public final class NeoExtensionTraceRecorder {

  private static final ThreadLocal<List<NeoExtensionTrace>> BUFFER = new ThreadLocal<>();

  private NeoExtensionTraceRecorder() {
  }

  /** Start (or restart, discarding what is buffered) recording on the current thread. */
  public static void beginRecording() {
    BUFFER.set(new ArrayList<>());
  }

  /**
   * Stop recording on the current thread and drop what is buffered.
   *
   * <p>Mandatory in a test teardown: the thread survives the test, so a buffer left behind is
   * state leaking into whatever runs next on it.</p>
   */
  public static void stopRecording() {
    BUFFER.remove();
  }

  /**
   * @return the traces recorded on this thread so far, oldest first; empty when recording was
   *         never started
   */
  public static List<NeoExtensionTrace> recorded() {
    List<NeoExtensionTrace> buffer = BUFFER.get();
    return buffer == null ? Collections.emptyList()
        : Collections.unmodifiableList(new ArrayList<>(buffer));
  }

  /** @return the most recent trace on this thread, or {@code null} when there is none */
  public static NeoExtensionTrace lastRecorded() {
    List<NeoExtensionTrace> buffer = BUFFER.get();
    return buffer == null || buffer.isEmpty() ? null : buffer.get(buffer.size() - 1);
  }

  /** Append a trace, if this thread is recording. */
  static void record(NeoExtensionTrace trace) {
    List<NeoExtensionTrace> buffer = BUFFER.get();
    if (buffer != null) {
      buffer.add(trace);
    }
  }
}
