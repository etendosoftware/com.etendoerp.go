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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.common;

import org.codehaus.jettison.json.JSONException;

/**
 * The client sent JSON text that {@link BoundedJsonParser} refused or could not parse.
 *
 * <p>It is a {@link JSONException}, so a {@code catch (JSONException)} that already answers a
 * malformed body with a 400 keeps doing so. A caller whose {@code JSONException} catch means
 * "server error" catches this subclass first: a malformed body is always the client's fault.</p>
 */
public class MalformedJsonException extends JSONException {

  private static final long serialVersionUID = 1L;

  public MalformedJsonException(String message) {
    super(message);
  }
}
