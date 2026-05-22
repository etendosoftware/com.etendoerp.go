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
 * Thrown when a NEO Headless create/update request is missing one or more
 * mandatory user-input fields after defaults, session, parent and callout
 * resolution have run. Carries the list of property names so the servlet
 * can return a structured 400 response that the UI can highlight per field.
 */
public class MissingRequiredFieldsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String ERROR_CODE = "MISSING_REQUIRED_FIELDS";

  private final List<String> fields;

  /**
   * Creates a new exception carrying the names of the mandatory fields that were left empty.
   *
   * @param fields DAL property names of the missing fields; {@code null} is treated as empty
   */
  public MissingRequiredFieldsException(List<String> fields) {
    super(ERROR_CODE + ": " + (fields == null ? "[]" : fields.toString()));
    this.fields = fields == null ? Collections.emptyList()
        : Collections.unmodifiableList(fields);
  }

  public List<String> getFields() {
    return fields;
  }
}
