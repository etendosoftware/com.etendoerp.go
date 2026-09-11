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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code McpWriteRequestSupport#buildStaleRecordError} (ETP-5073 / DOC-04): the 409
 * envelope an MCP agent receives when its {@code updated} no longer matches the stored row.
 *
 * <p>The discriminator is what these tests are really about. A duplicate key is also a 409, and its
 * remedy is the OPPOSITE of this one (send different values vs. re-read and reapply), so an agent
 * that keys off the status alone — or off a shared {@code conflict} code — retries the wrong thing
 * forever. Hence the explicit assertion that the code is {@code stale_record} and NOT
 * {@code conflict}.
 */
class McpWriteRequestSupportStaleRecordTest {

  @Test
  @DisplayName("the stale-record envelope is a 409 carrying its own discriminator")
  void staleRecordEnvelopeShape() throws Exception {
    JSONObject envelope = McpWriteRequestSupport.buildStaleRecordError();

    assertEquals(409, envelope.getInt(McpConstants.KEY_STATUS));
    assertEquals(McpConstants.STATUS_CONFLICT, envelope.getInt(McpConstants.KEY_STATUS));
    assertEquals(McpConstants.ERROR_STALE_RECORD, envelope.getString(McpConstants.KEY_ERROR));
    assertEquals("stale_record", envelope.getString(McpConstants.KEY_ERROR));
    assertNotEquals(McpConstants.ERROR_CONFLICT, envelope.getString(McpConstants.KEY_ERROR));
  }

  /**
   * {@code detail} says what happened, {@code hint} says what to do about it. Both must be there
   * and non-empty: an agent that gets a bare code cannot recover, and the whole point of the
   * envelope is that the FIRST retry succeeds.
   */
  @Test
  @DisplayName("detail and hint are present, non-empty, and name the remedy")
  void staleRecordEnvelopeGuidance() throws Exception {
    JSONObject envelope = McpWriteRequestSupport.buildStaleRecordError();

    String detail = envelope.getString(McpConstants.KEY_DETAIL);
    String hint = envelope.getString(McpConstants.KEY_HINT);
    assertFalse(detail.trim().isEmpty());
    assertFalse(hint.trim().isEmpty());
    // The remedy is re-reading, and the tool that does it has to be named — an agent told only
    // "conflict" has no next move.
    assertTrue(hint.contains("neo_get"));
    assertTrue(hint.contains(McpConstants.PARAM_UPDATED));
    // Nothing was written: an agent must not go looking for a partially updated record.
    assertTrue(detail.contains("nothing was written"));
    assertEquals(McpConstants.SEE_ALSO_WRITING, envelope.getString(McpConstants.KEY_SEE_ALSO));
  }
}
