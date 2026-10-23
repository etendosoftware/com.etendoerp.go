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

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * @covers com.etendoerp.go.schemaforge.BatchRowGrouper
 *
 * <p>An async batch commits one <i>row</i> at a time; a row is an operation plus everything it
 * references, so a product and its price, or a document and its lines, are never split.</p>
 */
public class BatchRowGrouperTest {

  private static JSONObject op(String id, String parentRef, String body) throws Exception {
    JSONObject op = new JSONObject().put("id", id).put("spec", "s").put("entity", "e");
    if (parentRef != null) {
      op.put("parentRef", parentRef);
    }
    op.put("body", new JSONObject(body));
    return op;
  }

  @Test
  public void unrelatedOperationsAreOneRowEach() throws Exception {
    JSONArray ops = new JSONArray().put(op("a", null, "{}")).put(op("b", null, "{}"))
        .put(op("c", null, "{}"));
    assertEquals("[[0], [1], [2]]", BatchRowGrouper.group(ops).toString());
  }

  @Test
  public void parentRefKeepsAChildWithItsParent() throws Exception {
    JSONArray ops = new JSONArray().put(op("p1", null, "{}")).put(op("p1.price", "p1", "{}"))
        .put(op("p2", null, "{}")).put(op("p2.price", "p2", "{}"));
    assertEquals("[[0, 1], [2, 3]]", BatchRowGrouper.group(ops).toString());
  }

  @Test
  public void refStringNestedInTheBodyLinksOperations() throws Exception {
    JSONArray ops = new JSONArray().put(op("bp", null, "{}")).put(op("other", null, "{}"))
        .put(op("inv", null, "{\"partner\":\"$ref:bp\",\"lines\":[{\"x\":{\"y\":\"$ref:other\"}}]}"));
    assertEquals("[[0, 1, 2]]", BatchRowGrouper.group(ops).toString());
  }

  @Test
  public void interleavedRowsKeepRequestOrderInsideEachRow() throws Exception {
    JSONArray ops = new JSONArray().put(op("a", null, "{}")).put(op("b", null, "{}"))
        .put(op("a.child", "a", "{}")).put(op("b.child", "b", "{}"));
    List<List<Integer>> rows = BatchRowGrouper.group(ops);
    assertEquals("[[0, 2], [1, 3]]", rows.toString());
  }

  @Test
  public void aReferenceToAnUnknownIdLinksNothing() throws Exception {
    JSONArray ops = new JSONArray().put(op("a", null, "{\"x\":\"$ref:ghost\"}"))
        .put(op("b", null, "{}"));
    assertEquals("[[0], [1]]", BatchRowGrouper.group(ops).toString());
  }

  @Test
  public void aDuplicateIdJoinsTheRowOfTheFirstSoTheBatchReportsIt() throws Exception {
    JSONArray ops = new JSONArray().put(op("a", null, "{}")).put(op("z", null, "{}"))
        .put(op("a", null, "{}"));
    assertEquals("[[0, 2], [1]]", BatchRowGrouper.group(ops).toString());
  }
}
