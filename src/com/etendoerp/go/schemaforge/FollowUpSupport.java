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

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;

/**
 * The one object any header handler — invoice, order, goods movement — holds to offer follow-up
 * documents (ETP-5576). Shared and identity-free: it knows nothing about the flows it is given
 * beyond the {@link FollowUpFlow} composition.
 *
 * <p>Wiring, three lines in the handler:
 * <pre>
 *   private final FollowUpSupport followUp = new FollowUpSupport(this::followUpFlows);
 *   // handle():       NeoHeaderActionRouter.dispatch(context, …, followUp.actionHandler());
 *   // afterHandle():  followUp.annotate(dataArr);   (GET, list and detail)
 * </pre>
 * {@code followUpFlows()} returns the entity's flows in the order the UI should offer
 * them. It is read per request, so it may depend on the handler's injected collaborators.
 */
final class FollowUpSupport {

  private final Supplier<List<FollowUpFlow>> flows;
  private final FollowUpActionHandler actionHandler;

  /** @param flows the handler's flows, in offer order; {@code null} result = none */
  FollowUpSupport(Supplier<List<FollowUpFlow>> flows) {
    this.flows = () -> {
      List<FollowUpFlow> list = flows.get();
      return list != null ? list : Collections.emptyList();
    };
    this.actionHandler = new FollowUpActionHandler(this.flows);
  }

  /** The ACTION handler serving every registered flow's action name. */
  NeoHandler actionHandler() {
    return actionHandler;
  }

  /**
   * Annotates {@code followUp} on every record of a GET page — see
   * {@link FollowUpDocumentService#annotatePage}. No-op when no flow is registered.
   */
  void annotate(JSONArray dataArr) throws JSONException {
    List<FollowUpFlow> registered = flows.get();
    if (!registered.isEmpty()) {
      FollowUpDocumentService.annotatePage(dataArr, registered);
    }
  }
}
