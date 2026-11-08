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

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.openbravo.model.ad.domain.Reference;

/**
 * ETP-5848: the values an AD list reference accepts, worded for a refusal, so a caller that missed a
 * list parameter can send one instead of guessing. Split out of {@link NeoProcessService} under
 * Sonar's class-size rule (S1448).
 */
final class NeoListValues {

  private NeoListValues() {
  }

  /**
   * @param list the parameter's list reference, or {@code null} for a parameter that is not a list
   * @return {@code "; one of: CO (Complete), VO (Void)"}, or empty for a non-list or an empty list
   */
  static String oneOf(Reference list) {
    if (list == null) {
      return "";
    }
    Map<String, String> labels = NeoSelectorService.getListLabels((String) list.getId());
    if (labels == null || labels.isEmpty()) {
      return "";
    }
    return new TreeMap<>(labels).entrySet().stream()
        .map(e -> e.getKey() + " (" + e.getValue() + ")")
        .collect(Collectors.joining(", ", "; one of: ", ""));
  }
}
