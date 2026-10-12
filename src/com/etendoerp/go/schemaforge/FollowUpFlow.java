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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openbravo.base.structure.BaseOBObject;

/**
 * One "create the follow-up document for what is still pending" flow (ETP-5576): WHICH follow-up
 * ({@link FollowUpTarget} — annotation key, action name, target spec/entity), WHAT is missing
 * ({@link PendingResolver}) and HOW to create it ({@link TargetCreator}). Immutable, and a pure
 * composition: it adds no rule of its own, it only delegates.
 *
 * <p>The shared layer — {@link FollowUpSupport}, {@link FollowUpActionHandler},
 * {@link FollowUpDocumentService} — works only against this class, never against a resolver or
 * creator directly, and never names an entity.
 *
 * <p><b>Binding.</b> A flow is not a CDI bean. The header handler of the source entity — the
 * entity's own customization — lists its flows, one line each, and hands the list to a
 * {@link FollowUpSupport}:
 * <pre>
 *   FollowUpFlow.of(FollowUpTarget.GOODS_SHIPMENT,
 *       new InvoicePendingResolver(Direction.SALES, this::isStandardInvoiceDocType),
 *       new InOutFollowUpCreator(Direction.SALES, InvoiceInOutMapping::map,
 *           InvoiceInOutMapping.linker(Direction.SALES)))
 * </pre>
 * A source with two follow-ups (an order: shipment AND invoice) lists two flows with different
 * {@link FollowUpTarget#getKey() keys} and action names.
 */
final class FollowUpFlow {

  private final FollowUpTarget target;
  private final PendingResolver resolver;
  private final TargetCreator creator;

  private FollowUpFlow(FollowUpTarget target, PendingResolver resolver, TargetCreator creator) {
    this.target = Objects.requireNonNull(target, "target");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.creator = Objects.requireNonNull(creator, "creator");
  }

  /**
   * @param target the follow-up produced; its key and action name must be unique per handler
   * @param resolver what is missing, per source
   * @param creator how the target is built from the pending lines
   */
  static FollowUpFlow of(FollowUpTarget target, PendingResolver resolver, TargetCreator creator) {
    return new FollowUpFlow(target, resolver, creator);
  }

  /** The follow-up this flow produces. */
  FollowUpTarget target() {
    return target;
  }

  PendingResolver resolver() {
    return resolver;
  }

  TargetCreator creator() {
    return creator;
  }

  /** {@link PendingResolver#sourceEntity()} — for the tenant guard only. */
  Class<? extends BaseOBObject> sourceEntity() {
    return resolver.sourceEntity();
  }

  /** {@link PendingResolver#loadSources}. */
  Map<String, PendingResolver.Source> loadSources(Collection<String> sourceIds) {
    return resolver.loadSources(sourceIds);
  }

  /** {@link PendingResolver#lockSource}. */
  void lockSource(String sourceId) {
    resolver.lockSource(sourceId);
  }

  /** {@link TargetCreator#createTarget}. */
  TargetCreator.Result createTarget(String sourceId,
      List<PendingResolver.SourceLine> pendingLines, FollowUpInputs inputs) {
    return creator.createTarget(sourceId, pendingLines, inputs);
  }
}
