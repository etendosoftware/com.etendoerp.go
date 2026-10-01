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
 * What {@link NeoExtensionDispatcher#dispatch(NeoExtensionRequest)} hands back.
 *
 * <p>{@code customization} is returned, not only used, because a pre phase and its post phase must
 * run on the <b>same instance</b>: handlers carry per-request state between {@code handle} and
 * {@code afterHandle} (see {@code InvoiceLineHandler}), and both resolvers hand out a fresh
 * {@code @Dependent} reference on every call. The caller therefore feeds this instance back into
 * {@link NeoExtensionRequest#post(NeoHandler)} instead of letting the dispatcher resolve
 * twice.</p>
 *
 * @param customization the resolved customization, or {@code null} when none was
 * @param response      the response it produced, or {@code null} when it declined or did not run
 * @param trace         the trace recorded for this dispatch
 */
public record NeoExtensionResult(NeoHandler customization, NeoResponse response,
    NeoExtensionTrace trace) {

  /** @return what the dispatch did */
  public NeoExtensionOutcome outcome() {
    return trace.outcome();
  }
}
