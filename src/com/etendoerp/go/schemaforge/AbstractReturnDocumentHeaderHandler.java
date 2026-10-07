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

import javax.inject.Inject;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * Shared base for the two return-document header handlers ({@link ReturnMaterialReceiptHeaderHandler}
 * and {@link ReturnToVendorShipmentHeaderHandler}) — holds the {@code postingService} injection
 * point and the {@code handle()} wiring around it (ETP-5378).
 *
 * <p>This injection-and-wiring shape was byte-identical between the two concrete handlers and kept
 * tripping SonarQube's duplicated-lines-on-new-code gate across two prior remediation attempts —
 * see {@link NeoHandlerUtils#delegateToPostingServiceOrElse}'s javadoc for the first two (moving
 * the guard clause's body out of {@code handle()}, then removing the private wrapper method around
 * it). Both attempts still left this exact field/setter/{@code handle()} shape typed out in each
 * subclass, which is what CPD kept matching. Hoisting it here removes the last copy: CDI/Weld
 * resolves {@code @Inject} fields declared anywhere in the class hierarchy, so each concrete
 * subclass still gets its own {@code postingService} instance without redeclaring the field.
 */
public abstract class AbstractReturnDocumentHeaderHandler implements NeoHandler {

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    mirrorAccountingDate(context);
    return NeoHandlerUtils.delegateToPostingServiceOrElse(context, postingService, this::continueHandling);
  }

  /**
   * Mirrors the document's single visible date field into its hidden accounting-date field
   * before the default CRUD path persists it. Each subclass supplies its own field-name
   * constants and calls the shared {@link NeoHandlerUtils#mirrorFieldValue}.
   */
  protected abstract void mirrorAccountingDate(NeoContext context);

  /**
   * Everything {@link #handle(NeoContext)} used to do after its posting-delegation guard
   * clause, now the continuation {@link NeoHandlerUtils#delegateToPostingServiceOrElse} calls
   * when posting/unpost did not apply — see that method's javadoc for why the guard clause
   * itself had to move out of the concrete classes entirely, not just its body.
   */
  protected abstract NeoResponse continueHandling(NeoContext context);
}
