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

import java.util.Set;

/**
 * ETP-6049 — keeps the whole invoice inbox spec behind the {@code invoice-inbox} feature flag, not
 * only its upload, and serves the {@code discard} action. Without the gate
 * {@code GET /sws/neo/invoice-inbox/header} (list and by id) answered regardless of the flag, and
 * so did {@code etendo_list} / {@code etendo_get} over MCP.
 *
 * <p>Bound with {@link NeoExtension}, which {@code NeoExtensionDispatcher} resolves for every
 * surface on every channel: REST single and batch, and the MCP read, whose pre-phase
 * ({@code McpHookExecutor.runReadProvider}) dispatches the {@code READ} surface through the same
 * dispatcher before the generic query is built. The pre-hook of a customization that answers
 * {@code >= 400} short-circuits the request, so a user without the flag sees nothing of the
 * inbox, not even an empty list.</p>
 *
 * <p>{@link InvoiceInboxFlag} stays the one evaluator of the key, and the refusal is the very
 * envelope of the upload endpoint ({@link InvoiceInboxUploadEndpoint#featureDisabled()}). The
 * flag is judged first, on every surface, so it gates the action too. With the flag on, every
 * surface but the action answers {@code null} and the default handling runs unchanged. The upload
 * built-in never reaches this class: it is routed before the spec router and gates itself.</p>
 *
 * <p>The spec is read-only: its CRUD writes stay refused by the method policy. The action
 * {@code POST /sws/neo/invoice-inbox/header/{id}/action/discard} is not gated by those method
 * flags (sub-endpoints are not), so it is served here, by {@link InvoiceInboxDiscard}. It is
 * hidden from MCP ({@link #agentExcludedActions()}): REST and the SPA have it, agents do not, until
 * the confirm action (ETP-6051) arrives and both are declared together
 * (neo-headless.md §4.12.9).</p>
 */

@NeoExtension(spec = "invoice-inbox", entity = "header")
public class InvoiceInboxHeaderHandler implements NeoHandler {

  @Override
  public NeoResponse handle(NeoContext context) {
    // Evaluated at request entry, while the request runs as the account owner.
    if (!InvoiceInboxFlag.isEnabled()) {
      return InvoiceInboxUploadEndpoint.featureDisabled();
    }
    if (context.getEndpointType() != NeoEndpointType.ACTION
        || !InvoiceInboxDiscard.ACTION.equals(context.getFieldName())) {
      return null;
    }
    // A GET on an action means "list": there is nothing to list for discard.
    return "POST".equals(context.getHttpMethod())
        ? InvoiceInboxDiscard.discard(context.getRecordId())
        : NeoResponse.error(405, "The discard action only supports POST");
  }

  @Override
  public boolean servesActions() {
    return true;
  }

  /** Discard is irreversible and ships for people only; see the class comment. */
  @Override
  public Set<String> agentExcludedActions() {
    return Set.of(InvoiceInboxDiscard.ACTION);
  }
}
