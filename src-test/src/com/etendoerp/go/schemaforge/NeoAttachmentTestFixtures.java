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

import java.nio.charset.StandardCharsets;

/** Shared byte fixtures for the attachment tests: just enough header to pass a signature check. */
final class NeoAttachmentTestFixtures {

  /** A RIFF container whose form type is WEBP: what the WEBP signature looks for. */
  static final byte[] WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 "
      .getBytes(StandardCharsets.ISO_8859_1);

  private NeoAttachmentTestFixtures() {
  }
}
