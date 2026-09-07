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
package com.etendoerp.go.mcp;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one place every {@code MCP_CONFIG} section is registered.
 *
 * <p><b>Why a bootstrap class rather than a static block per section.</b> {@link McpEntityConfig}
 * reports an unknown section as an error — that is what stops a misspelled key from reading as
 * "unconfigured" — which means every section must be registered <i>before</i> the first payload is
 * read, not merely before its own feature runs. A static block inside
 * {@link McpParentSection} would only fire once something touched that class, so a
 * {@code neo_discover} call arriving first would report a perfectly valid {@code parent} section as
 * unknown. Listing the sections here, and having {@code McpEntityConfig} call
 * {@link #ensureRegistered()} before it parses anything, removes that ordering hazard.</p>
 *
 * <p>The base still knows nothing about any section's semantics: it knows this class exists and
 * that calling it makes the registry complete. Adding a section is one line here plus its own
 * class — no change to the resolver, the cache or the model.</p>
 */
final class McpConfigSections {

  private static final AtomicBoolean REGISTERED = new AtomicBoolean();

  private McpConfigSections() {
  }

  /**
   * Register every section, once per JVM.
   *
   * <p>Idempotent and safe to call on every read: after the first call this is a single volatile
   * read. Registration itself is idempotent too, so a race between two first-callers cannot
   * produce a duplicate-name failure.</p>
   */
  static void ensureRegistered() {
    if (REGISTERED.get()) {
      return;
    }
    McpEntityConfig.register(McpParentSection.declaration());
    REGISTERED.set(true);
  }

  /** Undo the bootstrap so a test can register its own sections. Tests only. */
  static void resetForTests() {
    REGISTERED.set(false);
    McpEntityConfig.clearRegistrations();
  }
}
