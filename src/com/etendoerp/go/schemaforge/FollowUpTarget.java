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
 * Describes one follow-up a {@link FollowUpFlow} produces (ETP-5576): the key it is
 * annotated under, the ACTION name that creates it, and the NEO spec/entity of the created
 * document. Immutable.
 *
 * <p>The values are OUTPUT data — written into the GET annotation and the action response so a
 * client can open the created document without guessing. Shared code never branches on them; it
 * only matches an incoming action name against {@link #getActionName()} of the flows the
 * handler registered.
 *
 * <p>The well-known targets are the constants below. A source whose existing action name differs
 * (e.g. an order that already exposes another name) derives its own with
 * {@link #withActionName(String)} instead of copying a constant.
 */
final class FollowUpTarget {

  /** Draft goods shipment (sales goods movement). */
  static final FollowUpTarget GOODS_SHIPMENT =
      new FollowUpTarget("shipment", "createShipment", "goods-shipment", "goodsShipment");

  /** Draft goods receipt (purchase goods movement). */
  static final FollowUpTarget GOODS_RECEIPT =
      new FollowUpTarget("receipt", "createGoodsReceipt", "goods-receipt", "goodsReceipt");

  private final String key;
  private final String actionName;
  private final String spec;
  private final String entity;

  /**
   * @param key stable annotation key, e.g. {@code shipment} → {@code followUp.shipment}
   * @param actionName ACTION served on the source header, e.g. {@code createShipment}
   * @param spec NEO spec of the created document
   * @param entity NEO entity (within {@code spec}) of the created document
   */
  FollowUpTarget(String key, String actionName, String spec, String entity) {
    this.key = key;
    this.actionName = actionName;
    this.spec = spec;
    this.entity = entity;
  }

  /** Same target, served under another ACTION name. */
  FollowUpTarget withActionName(String otherActionName) {
    return new FollowUpTarget(key, otherActionName, spec, entity);
  }

  String getKey() {
    return key;
  }

  String getActionName() {
    return actionName;
  }

  String getSpec() {
    return spec;
  }

  String getEntity() {
    return entity;
  }
}
