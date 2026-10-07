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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

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
   * One action contract per registered flow, by action name, in offer order (ETP-5576, MCP-8):
   * what the source handler adds to its {@link NeoHandler#actionContracts()} so that
   * {@code neo_schema(view:"actions")} lists the follow-up actions next to the AD buttons. Before,
   * an agent found them only inside the {@code followUp} annotation of a record. Built from the
   * flow's {@link FollowUpTarget} and its creator's {@link TargetCreator#inputParams()} — nothing
   * here names a document.
   *
   * <p>The catalogue is per entity, not per record: whether a given record can run the action is
   * {@code followUp.<key>.needed}, and the action itself answers {@code FOLLOW_UP_*} otherwise.
   */
  Map<String, NeoActionContract> actionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    for (FollowUpFlow flow : flows.get()) {
      FollowUpTarget target = flow.target();
      contracts.put(target.getActionName(), NeoActionContract.write(target.getActionName(),
          "Creates a draft " + target.getEntity() + " (spec " + target.getSpec() + ") with the "
              + "quantities of this record still pending, and links each new line to its source "
              + "line. Only when the record's " + FollowUpDocumentService.FIELD_FOLLOW_UP + "."
              + target.getKey() + ".needed is true (read it with neo_get); otherwise it answers "
              + "a FOLLOW_UP_* error naming the reason. Returns 201 with the new document's id, "
              + "documentNo, spec, entity and lineCount; the document stays in draft.",
          flow.creator().inputParams().toArray(new NeoActionContract.Param[0])));
    }
    return contracts;
  }

  /**
   * The response keys {@link #annotate} adds to every GET record: {@code followUp} when at least
   * one flow is registered, nothing otherwise. Declared through
   * {@link NeoHandler#responseEnrichedFields()} so an MCP {@code fields:[…]} projection does not
   * report the key as unknown while the same response carries it.
   */
  Set<String> responseFields() {
    return flows.get().isEmpty()
        ? Collections.emptySet() : Set.of(FollowUpDocumentService.FIELD_FOLLOW_UP);
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
