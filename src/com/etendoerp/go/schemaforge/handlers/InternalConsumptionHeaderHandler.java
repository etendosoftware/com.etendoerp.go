/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import javax.inject.Inject;
import javax.inject.Named;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * {@code NeoHandler} for the {@code internalConsumption} entity (Internal Consumption header,
 * {@code M_Internal_Consumption}). Delegates {@code post}/{@code unpost} actions to
 * {@link DocumentPostingService} (ETP-5445) — without this, those actions fall through to NEO's
 * generic AD-button-column lookup, which finds no such button on {@code M_Internal_Consumption}
 * and answers "Action not found: post". Same shape as {@link InventoryHandler} (ETP-5360).
 *
 * <p>Every other request returns {@code null}, so default CRUD handling is unchanged.</p>
 *
 * <p>{@code @Named} only — never a normal CDI scope. {@code lookupHandler()} reads the
 * {@code @Named} annotation off the concrete handler class; a normal-scoped bean would be a
 * Weld client proxy whose subclass does not carry the (non-{@code @Inherited}) {@code @Named},
 * so the handler would be silently skipped. {@code @Named}-only defaults to {@code @Dependent}
 * (no proxy).</p>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'internal-consumption'} on the ETGO_SF_ENTITY record
 * for the {@code internalConsumption} entity in the internal-consumption spec (set through the
 * window's {@code decisions.json} + push-to-neo, then {@code export.database}).</p>
 */
@Named("internal-consumption")
public class InternalConsumptionHeaderHandler implements NeoHandler {

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    return postingService != null ? postingService.handleAction(context) : null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
