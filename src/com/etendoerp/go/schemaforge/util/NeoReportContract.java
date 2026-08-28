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

package com.etendoerp.go.schemaforge.util;

import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

/**
 * What a NEO-native report handler accepts: its input parameters and the formats it serves
 * (ETP-4793 / IMP-19).
 *
 * <p>Resolved once by {@link NeoReportCallability#resolveReportContract} and then used by every
 * surface that talks about the report — the {@code generate_*} tool schema, MCP discover, the
 * report router's validation, and the NEO HTTP endpoint. Sharing one object is the point: the
 * defect IMP-19 records is a schema that described one contract while the handler enforced
 * another, and two independent descriptions of the same thing drift by default.</p>
 */
public final class NeoReportContract {

  private final String qualifier;
  private final List<NeoReportParam> parameters;
  private final List<String> formats;

  /**
   * @param qualifier  the handler's {@code Java_Qualifier}
   * @param parameters the declared input parameters; may be empty
   * @param formats    the declared output formats; falls back to JSON when empty
   */
  NeoReportContract(String qualifier, List<NeoReportParam> parameters, List<String> formats) {
    this.qualifier = qualifier;
    this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
    this.formats = formats == null || formats.isEmpty() ? List.of(NeoReportParam.FORMAT_JSON)
        : List.copyOf(formats);
  }

  /**
   * @return the {@code Java_Qualifier} of the handler that declared this contract
   */
  public String getQualifier() {
    return qualifier;
  }

  /**
   * @return the declared input parameters, in declaration order; empty for a report that takes
   *         no inputs
   */
  public List<NeoReportParam> getParameters() {
    return parameters;
  }

  /**
   * @return the output formats the handler actually serves; never empty
   */
  public List<String> getFormats() {
    return formats;
  }

  /**
   * @return the format applied when the caller names none: the first declared format
   */
  public String getDefaultFormat() {
    return formats.get(0);
  }

  /**
   * Reports whether the handler serves the requested format, case-insensitively. A blank
   * request means "whatever you default to" and is always supported.
   *
   * @param format the requested format, possibly blank
   * @return {@code true} when the format is served
   */
  public boolean supportsFormat(String format) {
    if (StringUtils.isBlank(format)) {
      return true;
    }
    return formats.stream().anyMatch(f -> f.equalsIgnoreCase(format.trim()));
  }

  /**
   * Find a declared parameter by the body key it is read from.
   *
   * @param name the parameter name
   * @return the descriptor, or empty when the report declares no such parameter
   */
  public Optional<NeoReportParam> findParameter(String name) {
    return parameters.stream().filter(p -> p.getName().equals(name)).findFirst();
  }

  /**
   * @return the names of every parameter the report cannot run without
   */
  public List<String> getRequiredParameterNames() {
    return parameters.stream().filter(NeoReportParam::isRequired).map(NeoReportParam::getName)
        .toList();
  }
}
