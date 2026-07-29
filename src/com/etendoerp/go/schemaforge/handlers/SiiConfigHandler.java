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

package com.etendoerp.go.schemaforge.handlers;

import javax.inject.Named;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.RectificativeDocTypeFlagService;

/**
 * NeoHandler for the {@code sii-config} spec ({@code siiConfiguration} entity, table
 * {@code aeatsii_config}) that auto-flags the client's rectificative document types and sequences
 * after an SII configuration is saved (ETP-4536).
 *
 * <p>Unlike {@code TbaiConfigSequenceHandler} and {@code VerifactuConfigReadyHandler} — which also
 * carry SIF-specific side effects and merely add the rectificative flagging on top — SII had no
 * handler before this story, so this class exists solely to run the shared
 * {@link RectificativeDocTypeFlagService}. All three SIF handlers delegate to that single service.
 *
 * <p>Best-effort, secondary side effect: the config record has already been saved by the time
 * {@link #afterHandle(NeoContext)} runs. The service runs under admin mode and swallows failures,
 * so it never fails the parent request; it returns a response carrying skipped-document-type
 * warnings when any were produced, or {@code null} to keep the original CRUD response.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (a scope annotation such as
 * {@code @ApplicationScoped} would make this qualifier silently undiscoverable).
 */
@Named("sii-config-rectificative-handler")
public class SiiConfigHandler implements NeoHandler {

  private RectificativeDocTypeFlagService rectificativeService = new RectificativeDocTypeFlagService();

  /** No pre-hook behavior: this handler only reacts after the config record is persisted. */
  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return rectificativeService.applyAfterConfigSave(context);
  }

  /** Package-private seam so unit tests can inject a mocked service. */
  void setRectificativeService(RectificativeDocTypeFlagService rectificativeService) {
    this.rectificativeService = rectificativeService;
  }
}
