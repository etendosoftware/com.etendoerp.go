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

import org.apache.commons.lang3.StringUtils;

/**
 * The operation a customization is being dispatched for.
 *
 * <p>Deliberately richer than {@link NeoEndpointType}: that enum collapses create, read, update and
 * delete into a single {@code CRUD} member, and "which write was this" is exactly what a trace has
 * to answer. The mapping from a {@link NeoContext} is total, so a surface is never absent.</p>
 */
public enum NeoExtensionSurface {

  CREATE,
  READ,
  UPDATE,
  DELETE,
  DEFAULTS,
  ACTION,
  SELECTOR,
  CALLOUT,
  EVALUATE_DISPLAY,

  /** Neither the endpoint type nor the HTTP method identified the operation. */
  UNKNOWN;

  /**
   * Derive the surface from a request context.
   *
   * @param context the context about to be handed to a customization; may be {@code null}
   * @return the matching surface, never {@code null}
   */
  public static NeoExtensionSurface of(NeoContext context) {
    if (context == null) {
      return UNKNOWN;
    }
    NeoEndpointType endpointType = context.getEndpointType();
    if (endpointType != null) {
      switch (endpointType) {
        case DEFAULTS:
          return DEFAULTS;
        case ACTION:
          return ACTION;
        case SELECTOR:
          return SELECTOR;
        case CALLOUT:
          return CALLOUT;
        case EVALUATE_DISPLAY:
          return EVALUATE_DISPLAY;
        case CRUD:
        default:
          break;
      }
    }
    return ofHttpMethod(context.getHttpMethod());
  }

  /** The CRUD reading of an HTTP verb. */
  private static NeoExtensionSurface ofHttpMethod(String httpMethod) {
    if (StringUtils.equalsIgnoreCase(httpMethod, "POST")) {
      return CREATE;
    }
    if (StringUtils.equalsIgnoreCase(httpMethod, "PUT")
        || StringUtils.equalsIgnoreCase(httpMethod, "PATCH")) {
      return UPDATE;
    }
    if (StringUtils.equalsIgnoreCase(httpMethod, "DELETE")) {
      return DELETE;
    }
    if (StringUtils.equalsIgnoreCase(httpMethod, "GET")) {
      return READ;
    }
    return UNKNOWN;
  }
}
