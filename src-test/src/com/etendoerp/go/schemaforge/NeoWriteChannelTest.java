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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The telemetry channel label of the write running on this thread (ETP-5791): MCP vs REST from
 * the {@link NeoWriteChannel#enter} mark, batch vs single from {@link NeoBatchScope}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoWriteChannel
 */
class NeoWriteChannelTest {

  @AfterEach
  void clearThread() {
    NeoWriteChannel.restore(null);
    while (NeoBatchScope.current() != null) {
      NeoBatchScope.close(null);
    }
  }

  @Test
  void anUnmarkedSingleWriteIsRest() {
    assertEquals(NeoWriteChannel.REST, NeoWriteChannel.current());
  }

  @Test
  void aMarkedSingleWriteIsMcpUntilTheMarkIsRestored() {
    String previous = NeoWriteChannel.enter(NeoWriteChannel.MCP);
    assertEquals(NeoWriteChannel.MCP, NeoWriteChannel.current());

    NeoWriteChannel.restore(previous);
    assertEquals(NeoWriteChannel.REST, NeoWriteChannel.current());
  }

  @Test
  void anOperationOfARestBatchIsRestBatch() {
    NeoBatchScope previous = NeoBatchScope.open(false);
    try {
      assertEquals(NeoWriteChannel.REST_BATCH, NeoWriteChannel.current());
    } finally {
      NeoBatchScope.close(previous);
    }
  }

  @Test
  void anOperationOfAnMcpBatchIsMcpBatch() {
    String previousChannel = NeoWriteChannel.enter(NeoWriteChannel.MCP);
    NeoBatchScope previous = NeoBatchScope.open(false);
    try {
      assertEquals(NeoWriteChannel.MCP_BATCH, NeoWriteChannel.current());
    } finally {
      NeoBatchScope.close(previous);
      NeoWriteChannel.restore(previousChannel);
    }
  }

  /** A nested mark (MCP job row inside an MCP call) restores the outer one, not "no mark". */
  @Test
  void restoreReturnsToTheOuterMark() {
    String outer = NeoWriteChannel.enter(NeoWriteChannel.MCP);
    String inner = NeoWriteChannel.enter(NeoWriteChannel.REST);
    assertEquals(NeoWriteChannel.REST, NeoWriteChannel.current());

    NeoWriteChannel.restore(inner);
    assertEquals(NeoWriteChannel.MCP, NeoWriteChannel.current());
    NeoWriteChannel.restore(outer);
    assertEquals(NeoWriteChannel.REST, NeoWriteChannel.current());
  }
}
