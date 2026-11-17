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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Orders the field names a refusal offers so the likely answer survives the cap (ETP-5848).
 * Kept apart from {@link McpRoutingException}, which only builds refusals.
 */
final class McpClosestNames {

  private McpClosestNames() {
  }

  /**
   * The names that share a word with {@code key} first, the rest after, each group in its original
   * order (ETP-5848). {@link McpRoutingException#unknownFilterField} keeps only the first
   * {@link McpConstants#MAX_AVAILABLE_NAMES}, so an alphabetical list cut the likely answer: an agent filtering contacts by {@code commercialName}
   * got twenty names from {@code account} to {@code deliveryMethod}, and the commercial name
   * ({@code name2}) was not among them.
   *
   * @param key   the filter key as the caller spelled it
   * @param names the filterable names
   * @return a new list, closest first
   */
  static List<String> closestFirst(String key, List<String> names) {
    String[] words = StringUtils.splitByCharacterTypeCamelCase(StringUtils.defaultString(key));
    List<String> close = new ArrayList<>();
    List<String> rest = new ArrayList<>();
    for (String name : names) {
      if (sharesAWord(name, words)) {
        close.add(name);
      } else {
        rest.add(name);
      }
    }
    close.addAll(rest);
    return close;
  }

  /** Words of two letters or fewer ("id", "to") match too much to mean anything. */
  private static boolean sharesAWord(String name, String[] words) {
    for (String word : words) {
      if (word.length() > 2 && StringUtils.containsIgnoreCase(name, word)) {
        return true;
      }
    }
    return false;
  }
}
