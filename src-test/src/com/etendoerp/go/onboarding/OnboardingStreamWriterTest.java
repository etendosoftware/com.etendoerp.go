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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

import org.junit.Test;

/** ETP-5548: the onboarding stream remembers the result line a failing step wrote. */
public class OnboardingStreamWriterTest {

  @Test
  public void aStreamWithoutResultReportsNone() {
    OnboardingStreamWriter writer = new OnboardingStreamWriter(new PrintWriter(new StringWriter()));

    assertFalse(writer.hasResult());
    assertNull(writer.getResultMessage());
    assertNull(writer.getResultCode());
  }

  @Test
  public void keepsTheFirstResultWrittenThroughAnySink() {
    StringWriter body = new StringWriter();
    OnboardingStreamWriter writer = new OnboardingStreamWriter(new PrintWriter(body));

    // The chain builds a fresh sink per step; the record lives in the writer they share.
    new NdjsonOnboardingProgressSink(writer).result(false, "Name already in use", "NAME_CODE");
    new NdjsonOnboardingProgressSink(writer).result(false, "Generic follow-up", null);

    assertTrue(writer.hasResult());
    assertFalse(writer.isResultSuccess());
    assertEquals("Name already in use", writer.getResultMessage());
    assertEquals("NAME_CODE", writer.getResultCode());
    assertTrue("The line still reaches the client", body.toString().contains("Name already in use"));
  }

  @Test
  public void progressLinesAreNotResults() {
    OnboardingStreamWriter writer = new OnboardingStreamWriter(new PrintWriter(new StringWriter()));

    new NdjsonOnboardingProgressSink(writer).progress("client", "error", "failed");

    assertFalse(writer.hasResult());
  }

  @Test
  public void reportsADisconnectedClientOfTheWrappedWriter() {
    PrintWriter broken = new PrintWriter(new Writer() {
      @Override
      public void write(char[] buffer, int offset, int length) throws java.io.IOException {
        throw new java.io.IOException("Broken pipe");
      }

      @Override
      public void flush() throws java.io.IOException {
        throw new java.io.IOException("Broken pipe");
      }

      @Override
      public void close() {
        // Nothing to release.
      }
    });
    OnboardingStreamWriter writer = new OnboardingStreamWriter(broken);

    writer.println("{\"type\":\"result\"}");

    assertTrue("A broken pipe swallowed by the wrapped writer must stay visible",
        writer.checkError());
  }
}
