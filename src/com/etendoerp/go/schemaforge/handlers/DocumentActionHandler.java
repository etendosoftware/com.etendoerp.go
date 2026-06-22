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
 * Shared {@code NeoHandler} that exposes the {@code post}/{@code unpost} actions for any
 * document entity that registers {@code JAVA_QUALIFIER = 'document-posting'} on its
 * {@code ETGO_SF_ENTITY} record. All posting logic lives in the reusable
 * {@link DocumentPostingService}; this handler is only the CDI entry point.
 *
 * <p>{@code @Named} only — never a normal CDI scope. {@code lookupHandler()} reads the
 * {@code @Named} annotation off the concrete handler class; a normal-scoped bean would be a
 * Weld client proxy whose subclass does not carry the (non-{@code @Inherited}) {@code @Named},
 * so the handler would be silently skipped. {@code @Named}-only defaults to {@code @Dependent}
 * (no proxy).</p>
 */
@Named("document-posting")
public class DocumentActionHandler implements NeoHandler {

  @Inject
  private DocumentPostingService postingService;

  /**
   * Pre-hook: delegates to {@link DocumentPostingService#handleAction(NeoContext)}. Returns the
   * service's {@link NeoResponse} for {@code post}/{@code unpost} actions (short-circuiting the
   * default CRUD), or {@code null} to fall through to default handling for anything else.
   *
   * @param context
   *     the current NEO request context.
   * @return a {@link NeoResponse} for handled posting actions, otherwise {@code null}.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    return postingService.handleAction(context);
  }

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }
}
