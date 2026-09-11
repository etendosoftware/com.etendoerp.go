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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link McpConfigCache}'s contract: memoization, invalidation, key isolation and how a
 * failing loader surfaces.
 *
 * <p>What is deliberately NOT tested here is expiry by elapsed time. Guava's TTLs are read off a
 * real clock, so asserting them would mean sleeping for minutes or injecting a {@code Ticker} that
 * only exists for the test. The TTL values themselves are configuration, and the behaviour that
 * matters for correctness — that an edit is visible immediately — is delivered by invalidation, not
 * by expiry. That is what is covered.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpConfigCache")
class McpConfigCacheTest {

  private static final String ID = "AAAA0000BBBB1111CCCC2222DDDD3333";
  private static final String OTHER_ID = "9999888877776666555544443333222";

  @BeforeEach
  void clean() {
    McpConfigCache.invalidateAll();
  }

  @Nested
  @DisplayName("memoization")
  class Memoization {

    @Test
    @DisplayName("the loader runs once per key, then the value is served from cache")
    void loaderRunsOnce() {
      AtomicInteger calls = new AtomicInteger();
      for (int i = 0; i < 5; i++) {
        assertEquals("parsed", McpConfigCache.parsedConfig(ID, () -> {
          calls.incrementAndGet();
          return "parsed";
        }));
      }
      assertEquals(1, calls.get(), "parsing should not be repeated for a warm key");
    }

    @Test
    @DisplayName("the two caches are independent stores, not one keyed namespace")
    void cachesAreSeparate() {
      McpConfigCache.parsedConfig(ID, () -> "config");
      assertEquals("hierarchy", McpConfigCache.resolvedHierarchy(ID, () -> "hierarchy"),
          "same id in the other cache must not read the first cache's value");
    }

    @Test
    @DisplayName("distinct keys never share a value")
    void keysAreIsolated() {
      assertEquals("first", McpConfigCache.parsedConfig(ID, () -> "first"));
      assertEquals("second", McpConfigCache.parsedConfig(OTHER_ID, () -> "second"));
      assertEquals("first", McpConfigCache.parsedConfig(ID, () -> "reloaded"));
    }

    @Test
    @DisplayName("a null key computes directly instead of sharing one slot")
    void nullKeyIsNotCached() {
      AtomicInteger calls = new AtomicInteger();
      for (int i = 0; i < 3; i++) {
        McpConfigCache.parsedConfig(null, () -> "x" + calls.incrementAndGet());
      }
      assertEquals(3, calls.get(),
          "a null key must not let two records share a cache entry");
    }
  }

  @Nested
  @DisplayName("invalidation")
  class Invalidation {

    @Test
    @DisplayName("invalidating a record forces the next read to reload it")
    void invalidateReloads() {
      assertEquals("before", McpConfigCache.parsedConfig(ID, () -> "before"));
      McpConfigCache.invalidateConfig(ID);
      assertEquals("after", McpConfigCache.parsedConfig(ID, () -> "after"),
          "this is what makes an edit in the window visible on the next call");
    }

    @Test
    @DisplayName("invalidating one record leaves the others warm")
    void invalidateIsTargeted() {
      McpConfigCache.parsedConfig(ID, () -> "one");
      McpConfigCache.parsedConfig(OTHER_ID, () -> "two");
      McpConfigCache.invalidateConfig(ID);
      assertEquals("two", McpConfigCache.parsedConfig(OTHER_ID, () -> "reloaded"));
    }

    @Test
    @DisplayName("invalidateAll clears both caches — the spec-edit path")
    void invalidateAllClearsBoth() {
      McpConfigCache.parsedConfig(ID, () -> "config");
      McpConfigCache.resolvedHierarchy(ID, () -> "hierarchy");
      McpConfigCache.invalidateAll();
      assertEquals("fresh-config", McpConfigCache.parsedConfig(ID, () -> "fresh-config"));
      assertEquals("fresh-hierarchy", McpConfigCache.resolvedHierarchy(ID, () -> "fresh-hierarchy"));
    }

    @Test
    @DisplayName("invalidating an unknown or null id is a no-op, not a failure")
    void invalidateUnknownIsSafe() {
      McpConfigCache.invalidateConfig(null);
      McpConfigCache.invalidateConfig("never-cached");
    }
  }

  @Nested
  @DisplayName("loader failure")
  class LoaderFailure {

    @Test
    @DisplayName("a runtime exception surfaces unwrapped, as if the caller had computed it")
    void runtimeExceptionPropagates() {
      IllegalStateException thrown = assertThrows(IllegalStateException.class,
          () -> McpConfigCache.parsedConfig(ID, () -> {
            throw new IllegalStateException("bad json");
          }));
      assertTrue(thrown.getMessage().contains("bad json"),
          "a cache must not change how errors read");
    }

    @Test
    @DisplayName("a failed load is not cached — the next read retries")
    void failureIsNotCached() {
      assertThrows(IllegalStateException.class,
          () -> McpConfigCache.parsedConfig(ID, () -> {
            throw new IllegalStateException("transient");
          }));
      assertEquals("recovered", McpConfigCache.parsedConfig(ID, () -> "recovered"));
    }

    @Test
    @DisplayName("a checked exception is wrapped, never swallowed into a null value")
    void checkedExceptionWrapped() {
      assertThrows(IllegalStateException.class,
          () -> McpConfigCache.parsedConfig(ID, () -> {
            throw new java.io.IOException("io");
          }));
    }
  }
}
