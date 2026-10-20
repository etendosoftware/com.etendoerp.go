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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for the parameter handover of {@link NeoListAggregateQuery}. The grouped HQL itself
 * is covered by NeoListAggregateQueryIntegrationTest.
 *
 * @covers com.etendoerp.go.schemaforge.NeoListAggregateQuery
 */
public class NeoListAggregateQueryTest {

  private static NeoContext ctx() {
    return NeoContext.builder().specName("s").entityName("e").httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD).build();
  }

  private static Map<String, String> params() {
    Map<String, String> p = new HashMap<>();
    p.put("_startRow", "0");
    p.put("_endRow", "100");
    p.put("_sortBy", "name");
    p.put("_summary", "sum");
    p.put("_distinct", "x");
    p.put("_targetRecordId", "abc");
    p.put("criteria", "{}");
    return p;
  }

  @Test
  public void ofIsNullWhenNothingWasRemembered() {
    assertNull(NeoListAggregateQuery.of(ctx()));
    assertNull(NeoListAggregateQuery.of(null));
  }

  @Test
  public void rememberIgnoresNullContextOrParams() {
    NeoListAggregateQuery.remember(null, params());
    NeoContext c = ctx();
    NeoListAggregateQuery.remember(c, null);
    assertNull(NeoListAggregateQuery.of(c));
  }

  @Test
  public void rememberThenOfRoundTripsAndDropsNonFilterParams() {
    NeoContext c = ctx();
    NeoListAggregateQuery.remember(c, params());

    NeoListAggregateQuery q = NeoListAggregateQuery.of(c);
    assertNotNull(q);
    Map<String, String> filter = q.filterParams();
    assertFalse(filter.containsKey("_sortBy"));
    assertFalse(filter.containsKey("_summary"));
    assertFalse(filter.containsKey("_distinct"));
    assertFalse(filter.containsKey("_targetRecordId"));
    assertEquals("0", filter.get("_startRow"));
    assertEquals("100", filter.get("_endRow"));
    assertEquals("{}", filter.get("criteria"));
  }

  @Test
  public void storedCopyIsImmutableAndDetachedFromTheCallersMap() {
    NeoContext c = ctx();
    Map<String, String> source = params();
    NeoListAggregateQuery.remember(c, source);

    source.put("criteria", "changed");
    source.remove("_startRow");
    Map<String, String> filter = NeoListAggregateQuery.of(c).filterParams();
    assertEquals("{}", filter.get("criteria"));
    assertEquals("0", filter.get("_startRow"));
    try {
      filter.put("k", "v");
      fail("the stored map must be unmodifiable");
    } catch (UnsupportedOperationException expected) {
      // expected
    }
  }

  @Test
  public void rememberDoesNotMutateTheCallersParams() {
    Map<String, String> source = params();
    NeoListAggregateQuery.remember(ctx(), source);
    assertTrue(source.containsKey("_sortBy"));
  }
}
