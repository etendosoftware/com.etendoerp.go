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

/**
 * Thrown by {@link NeoFieldFilter#filterCreateRequest(org.codehaus.jettison.json.JSONObject)}
 * when a client-supplied POST body tries to write a field that is read-only in
 * {@code ETGO_SF_FIELD} and carries no AD-configured default value.
 *
 * <p>Root cause fixed by IMP-28: before this exception existed, such a field was silently
 * dropped from the body — the request returned 200 and the caller's value was discarded
 * without any indication. An agent that had just been told (correctly, post-IMP-28-clause-1)
 * that this field is read-only should never send it in the first place; if it does anyway,
 * the honest response is a rejection, not a silent no-op.
 *
 * @see MissingRequiredFieldsException the sibling exception this mirrors for the
 *     "required field missing" case
 */
public class ReadOnlyFieldRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String ERROR_CODE = "READ_ONLY_FIELD_REJECTED";

  private final String fieldName;

  public ReadOnlyFieldRejectedException(String fieldName) {
    super(ERROR_CODE + ": " + fieldName);
    this.fieldName = fieldName;
  }

  public String getFieldName() {
    return fieldName;
  }
}
