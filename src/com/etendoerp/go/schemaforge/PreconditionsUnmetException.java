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

import java.util.Collections;
import java.util.List;

/**
 * Thrown (or used to build a structured response) when a NEO Headless process
 * execution is rejected because one or more declared preconditions are not met
 * on the target record. Carries the list of NEO field (DAL property) names whose
 * precondition is unmet so the servlet can return a structured 400 response that
 * the UI can highlight or explain per field.
 *
 * <p>Preconditions are declared as data on {@code ETGO_SF_ENTITY.preconditions}
 * (keyed by AD_Process_ID) and evaluated generically — no process-specific
 * branches live in Java.</p>
 */
public class PreconditionsUnmetException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String ERROR_CODE = "PRECONDITIONS_UNMET";

  private final List<String> missing;

  /**
   * Creates a new exception carrying the names of the NEO fields whose precondition
   * is unmet.
   *
   * @param missing DAL property names of the unmet preconditions; {@code null} is
   *                treated as empty
   */
  public PreconditionsUnmetException(List<String> missing) {
    super(ERROR_CODE + ": " + (missing == null ? "[]" : missing.toString()));
    this.missing = missing == null ? Collections.emptyList()
        : Collections.unmodifiableList(missing);
  }

  public List<String> getMissing() {
    return missing;
  }
}
