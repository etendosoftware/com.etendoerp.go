/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.onboarding;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.PrintWriter;

import org.junit.Test;

/**
 * Specs for {@link NdjsonSinkWriter}, which replays the NDJSON onboarding stream onto an
 * {@link OnboardingProgressSink} so a paid purchase provisioned with no browser attached
 * (ETP-5048) reports through the very same run.
 *
 * @covers com.etendoerp.go.onboarding.NdjsonSinkWriter
 */
public class NdjsonSinkWriterTest {

  private final OnboardingProgressSink sink = mock(OnboardingProgressSink.class);

  @Test
  public void progressAndResultLinesAreReplayedInOrder() {
    PrintWriter writer = new PrintWriter(new NdjsonSinkWriter(sink));

    writer.write("{\"type\":\"progress\",\"step\":\"dataset\",\"status\":\"running\","
        + "\"message\":\"Loading\"}\r\n");
    writer.write("{\"type\":\"result\",\"success\":false,\"message\":\"Boom\","
        + "\"code\":\"PROVISIONING_FAILED\"}\n");
    writer.flush();

    verify(sink).progress("dataset", "running", "Loading");
    verify(sink).result(false, "Boom", "PROVISIONING_FAILED");
  }

  @Test
  public void aLineSplitAcrossWritesIsDispatchedOnceWhenItEnds() {
    NdjsonSinkWriter writer = new NdjsonSinkWriter(sink);

    char[] head = "{\"type\":\"result\",\"succ".toCharArray();
    char[] tail = "ess\":true,\"message\":\"Done\",\"code\":null}\n".toCharArray();

    writer.write(head, 0, head.length);
    verifyNoInteractions(sink);
    writer.write(tail, 0, tail.length);

    verify(sink).result(true, "Done", null);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void keepaliveBlanksNonJsonAndUnknownTypesAreSkipped() {
    PrintWriter writer = new PrintWriter(new NdjsonSinkWriter(sink));

    writer.write("\n   \nnot json\n{\"type\":\"heartbeat\"}\n");
    writer.flush();

    verifyNoInteractions(sink);
  }

  @Test
  public void closingDispatchesAnUnterminatedLastLine() {
    NdjsonSinkWriter writer = new NdjsonSinkWriter(sink);
    String last = "{\"type\":\"result\",\"success\":true,\"message\":\"Done\"}";

    writer.write(last.toCharArray(), 0, last.length());
    verifyNoInteractions(sink);
    writer.close();

    verify(sink).result(true, "Done", null);
  }
}
