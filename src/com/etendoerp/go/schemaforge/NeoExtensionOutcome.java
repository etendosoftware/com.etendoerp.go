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

/**
 * What a dispatch actually did.
 *
 * <p>The whole point of the pilot is that today these outcomes are indistinguishable from outside:
 * an entity with no customization, a customization that ran and declined, and a resolution that
 * failed all reach the caller as the same {@code null} and the same generic-CRUD response. Keeping
 * them apart here is the precondition for every later step.</p>
 */
public enum NeoExtensionOutcome {

  /** A customization was resolved, ran, and returned a response that the caller will use. */
  RESOLVED_AND_RAN,

  /** A customization was resolved and ran, and deliberately declined by returning {@code null}. */
  RESOLVED_RETURNED_NULL,

  /**
   * No customization is deployed under the qualifier — or, on a post phase, none was resolved in
   * the matching pre phase. Distinct from {@link #RESOLVED_RETURNED_NULL}: nothing ran.
   */
  NO_CUSTOMIZATION,

  /**
   * A customization was resolved and threw. The throwable is rethrown untouched; this outcome
   * exists so the trace does not simply stop mid-dispatch.
   */
  FAILED
}
