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

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.util.NeoValueEquivalence;

/**
 * The create-response diagnostics about what a caller's own values met on the way to persistence:
 * {@code supersededDefaults} (a callout derived a different value, IMP-45) and
 * {@code adjustedFields} (the entity's pre-hook changed a value the caller sent, ETP-5680).
 *
 * <p>Generic: it names no entity or field. An entity opts out of the callout diagnostic through
 * {@link NeoHandler#governedCreateFields}.</p>
 */
final class McpSupersededReconciler {

  /** Key of the value the caller sent, in a {@code supersededDefaults}/{@code adjustedFields} entry. */
  private static final String KEY_SENT = "sent";

  private McpSupersededReconciler() {
  }

  /**
   * Attach the callout-vs-caller divergences a create left behind (IMP-45).
   *
   * <p>{@code etendo_defaults} tells an agent to use its result as the starting point for
   * {@code etendo_create}, and {@code etendo_create} repeats the advice. Follow it literally and every
   * value handed over becomes a value the caller sent, which ETP-4784 protects from being
   * recomputed by a callout that knows the record's real context. The measured case:
   * {@code etendo_defaults(sales-order/header)} answers {@code paymentTerms: "30 Días"} with no
   * business partner in sight, and the partner chosen a moment later implies {@code "Inmediato"} —
   * an agent that echoed the default has pinned the wrong one, and the 201 says nothing.</p>
   *
   * <p>The value the caller sent still wins. Overriding it would be worse: on this path the server
   * cannot tell an echoed default from a value a human deliberately chose, and silently replacing
   * the second is a harder failure than reporting the first. So this reports, as IMP-18 does for
   * unrecognised names — the write succeeded, and now the caller can see what its own value
   * displaced.</p>
   *
   * @param body       the flattened response body handed to the agent, mutated in place
   * @param superseded field → {sent, callout}, or {@code null}/empty when nothing diverged
   */
  static void reportSupersededDefaults(JSONObject body, JSONObject superseded) {
    if (body == null || superseded == null || superseded.length() == 0) {
      return;
    }
    try {
      body.put("supersededDefaults", superseded);
      body.put("supersededDefaultsHint", "For each field listed, the value you sent was saved as "
          + "sent; \"callout\" is only what a callout would have set from this record's own context. "
          + "You can ignore it when the callout value is 0 or empty, or when the field is governed "
          + "by another rule. If you copied the value from "
          + "etendo_defaults and it differs from a meaningful callout value, it was a generic "
          + "default resolved before this record had its context: send the callout value instead, "
          + "or confirm the one you sent was deliberate.");
    } catch (JSONException ignored) {
      // Same reasoning as McpWriteRequestSupport#reportUnknownFields: the record is written and correct as far as the
      // caller asked; losing a diagnostic must never turn a successful create into a failure.
    }
  }

  /**
   * Snapshot, just before the entity's CREATE pre-hook is dispatched, of the value each field the
   * caller sent holds in the create body (ETP-5680). Taken at that point so the later comparison
   * isolates what the pre-hook itself rewrote from the representation changes made earlier
   * (type coercion, the user-key restore, identifier-to-id resolution). Keys no longer in the
   * body (the parent id, already turned into a foreign key) are not captured.
   *
   * <p>Caveat: values are held by reference, so a pre-hook that mutates a nested
   * {@code JSONObject}/{@code JSONArray} in place is not detected.</p>
   *
   * @param body         the create body about to be handed to the pre-hook
   * @param userProvided the caller's fields, as snapshotted before any defaults were injected
   * @return field -> value as of now, for the caller-sent fields still in {@code body}
   */
  static JSONObject snapshotCallerValues(JSONObject body, JSONObject userProvided) {
    JSONObject snapshot = new JSONObject();
    Iterator<?> keys = userProvided.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      if (body.has(key)) {
        try {
          snapshot.put(key, body.get(key));
        } catch (JSONException ignored) {
          // a missing diagnostic must not break the write
        }
      }
    }
    return snapshot;
  }

  /**
   * Reports what the entity's pre-hook changed in what the caller sent, and reconciles the IMP-45
   * {@code supersededDefaults} with it (ETP-5680).
   *
   * <p>{@code adjustedFields} is defined over the fields the caller sent whose value the pre-hook
   * changed ({@code preHookValues} vs {@code finalBody}), independent of any callout: a rewrite
   * no callout ever proposed anything for (a service's {@code returnable}) is as much a silent
   * change of the caller's value as one that was. Fields the caller did not send are never
   * reported. A key the pre-hook removed reports {@code persisted: null}.</p>
   *
   * <p>The recorded {@code superseded} entries (callout vs sent, recorded before the pre-hook
   * ran) are then reconciled: an entry whose field was adjusted moves to {@code adjustedFields}
   * (never listed twice); one governed by the customization
   * ({@link NeoHandler#governedCreateFields}) and unchanged is dropped; any other stays.</p>
   *
   * @param body          the flattened response body, receives {@code adjustedFields}
   * @param superseded    field -> {sent, callout}; entries removed in place; may be {@code null}
   * @param preHookValues {@link #snapshotCallerValues} taken before the pre-hook dispatch
   * @param finalBody     the create body after the pre-hook, what is handed to persistence
   * @param handler       the entity's customization, or {@code null}
   * @param hookCtx       the context that customization ran under
   */
  static void reconcileSupersededDefaults(JSONObject body, JSONObject superseded,
      JSONObject preHookValues, JSONObject finalBody, NeoHandler handler, NeoContext hookCtx) {
    if (body == null || preHookValues == null || finalBody == null) {
      return;
    }
    JSONObject adjusted = changedValues(preHookValues, finalBody);
    pruneSuperseded(superseded, adjusted, handler, hookCtx);
    if (adjusted.length() > 0) {
      putAdjustedReport(body, adjusted);
    }
  }

  /** field -> {sent, persisted} for every snapshot key whose final value is not the same one. */
  private static JSONObject changedValues(JSONObject preHookValues, JSONObject finalBody) {
    JSONObject adjusted = new JSONObject();
    for (String field : keysOf(preHookValues)) {
      if (!NeoValueEquivalence.same(preHookValues.opt(field), finalBody.opt(field))) {
        putAdjusted(adjusted, field, preHookValues.opt(field), finalBody.opt(field));
      }
    }
    return adjusted;
  }

  /** Removes from {@code superseded}, in place, the adjusted entries and the governed ones. */
  private static void pruneSuperseded(JSONObject superseded, JSONObject adjusted,
      NeoHandler handler, NeoContext hookCtx) {
    if (superseded == null || superseded.length() == 0) {
      return;
    }
    Set<String> governed = governedFields(handler, hookCtx);
    for (String field : keysOf(superseded)) {
      if (adjusted.has(field) || governed.contains(field)) {
        superseded.remove(field);
      }
    }
  }

  private static List<String> keysOf(JSONObject json) {
    List<String> keys = new ArrayList<>();
    Iterator<?> it = json.keys();
    while (it.hasNext()) {
      keys.add((String) it.next());
    }
    return keys;
  }

  private static Set<String> governedFields(NeoHandler handler, NeoContext hookCtx) {
    if (handler == null) {
      return Set.of();
    }
    try {
      Set<String> governed = handler.governedCreateFields(hookCtx);
      return governed != null ? governed : Set.of();
    } catch (RuntimeException e) {
      // A diagnostic opt-out must never turn a successful create into a failure.
      return Set.of();
    }
  }

  private static void putAdjusted(JSONObject adjusted, String field, Object sent,
      Object persisted) {
    try {
      adjusted.put(field, new JSONObject().put(KEY_SENT, sent != null ? sent : JSONObject.NULL)
          .put("persisted", persisted != null ? persisted : JSONObject.NULL));
    } catch (JSONException ignored) {
      // a missing diagnostic must not break the write
    }
  }

  private static void putAdjustedReport(JSONObject body, JSONObject adjusted) {
    try {
      body.put("adjustedFields", adjusted);
      body.put("adjustedFieldsHint", "For each field listed, the server changed the value you "
          + "sent before saving, by a rule of this record; \"persisted\" is what was "
          + "saved. Nothing to retry: send the persisted value next time to avoid the difference.");
    } catch (JSONException ignored) {
      // a missing diagnostic must not break the write
    }
  }
}
