/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rounding mode stamped on every line's tax block (ETP-6050): {@code L} only when the tax says
 * so, {@code D} for everything else (unknown tax, unknown mode, a lookup that could not answer),
 * one lookup for all the lines, and nothing touched on a line without a tax id.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.InboxTaxModes
 */
class InboxTaxModesTest {

  private InboxFakeLookups lookups;

  @BeforeEach
  void setUp() {
    lookups = new InboxFakeLookups();
  }

  private static JSONArray lines(String json) throws Exception {
    return new JSONArray(json);
  }

  private static String modeOf(JSONArray lines, int position) throws Exception {
    return lines.getJSONObject(position).getJSONObject("tax").optString("docTaxAmount", "<none>");
  }

  @Test
  @DisplayName("a line-based tax is stamped L, a document-based one D, in one lookup for every line")
  void stampsTheModeOfEachTaxWithOneLookup() throws Exception {
    lookups.taxModes.put("TL", "L");
    lookups.taxModes.put("TD", "D");
    JSONArray lines = lines("[{tax:{id:'TL'}},{tax:{id:'TD'}},{tax:{id:'TL'}}]");

    InboxTaxModes.stamp(lines, lookups);

    assertEquals("L", modeOf(lines, 0));
    assertEquals("D", modeOf(lines, 1));
    assertEquals("L", modeOf(lines, 2));
    assertEquals(1, lookups.taxModeAsks.size(), "one ask for the whole document");
    assertEquals(List.of("TL", "TD"), List.copyOf(lookups.taxModeAsks.get(0)),
        "each id once, in line order");
  }

  @Test
  @DisplayName("a tax the lookup does not know, or knows with another value, is D")
  void unknownTaxAndUnknownModeAreDocumentBased() throws Exception {
    lookups.taxModes.put("TX", "X");
    lookups.taxModes.put("Tl", "l");
    JSONArray lines = lines("[{tax:{id:'TX'}},{tax:{id:'NOPE'}},{tax:{id:'Tl'}}]");

    InboxTaxModes.stamp(lines, lookups);

    assertEquals("D", modeOf(lines, 0));
    assertEquals("D", modeOf(lines, 1));
    assertEquals("D", modeOf(lines, 2));
  }

  @Test
  @DisplayName("a lookup that answers nothing (it failed) leaves every tax D")
  void aLookupWithoutAnswerLeavesEveryTaxDocumentBased() throws Exception {
    JSONArray lines = lines("[{tax:{id:'TL'}},{tax:{id:'TD'}}]");

    InboxTaxModes.stamp(lines, lookups);

    assertEquals("D", modeOf(lines, 0));
    assertEquals("D", modeOf(lines, 1));
  }

  @Test
  @DisplayName("a previous stamp is overwritten by the current mode of the tax")
  void aStaleStampIsReplaced() throws Exception {
    lookups.taxModes.put("TL", "L");
    JSONArray lines = lines("[{tax:{id:'TL',docTaxAmount:'D'}},{tax:{id:'TD',docTaxAmount:'L'}}]");

    InboxTaxModes.stamp(lines, lookups);

    assertEquals("L", modeOf(lines, 0));
    assertEquals("D", modeOf(lines, 1));
  }

  @Test
  @DisplayName("lines without a tax block or without an id are left as they are and never asked for")
  void linesWithoutATaxIdAreUntouched() throws Exception {
    JSONArray lines = lines("[{description:'a'},{tax:{state:'pending_vendor'}},{tax:{id:''}},"
        + "{tax:null},{tax:{id:'  '}}]");
    String before = lines.toString();

    InboxTaxModes.stamp(lines, lookups);

    assertEquals(before, lines.toString());
    assertTrue(lookups.taxModeAsks.isEmpty(), "nothing to ask");
  }

  @Test
  @DisplayName("a document with a mix of lines stamps only the ones with a tax id")
  void onlyTheLinesWithATaxIdAreStamped() throws Exception {
    lookups.taxModes.put("TL", "L");
    JSONArray lines = lines("[{description:'a'},{tax:{id:'TL'}},{tax:{state:'unknown'}}]");

    InboxTaxModes.stamp(lines, lookups);

    assertFalse(lines.getJSONObject(0).has("tax"));
    assertEquals("L", modeOf(lines, 1));
    assertFalse(lines.getJSONObject(2).getJSONObject("tax").has("docTaxAmount"));
  }

  @Test
  @DisplayName("no lines, null lines and non-object entries are not an error")
  void absentLinesAreTotal() throws Exception {
    assertDoesNotThrow(() -> InboxTaxModes.stamp(null, lookups));
    assertDoesNotThrow(() -> InboxTaxModes.stamp(new JSONArray(), lookups));
    JSONArray odd = new JSONArray().put("text").put(JSONObject.NULL).put(5);
    assertDoesNotThrow(() -> InboxTaxModes.stamp(odd, lookups));
    assertTrue(lookups.taxModeAsks.isEmpty());
  }

  @Test
  @DisplayName("hostile text in a tax id is passed as data, never interpreted")
  void hostileTextInATaxIdIsOnlyAnId() throws Exception {
    String hostile = "T1' OR '1'='1";
    lookups.taxModes.put(hostile, "L");
    JSONArray lines = new JSONArray().put(new JSONObject().put("tax",
        new JSONObject().put("id", hostile)));

    InboxTaxModes.stamp(lines, lookups);

    assertEquals("L", modeOf(lines, 0));
    assertEquals(List.of(hostile), List.copyOf(lookups.taxModeAsks.get(0)));
  }
}
