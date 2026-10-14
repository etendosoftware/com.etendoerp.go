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

import org.openbravo.base.exception.OBException;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * A business refusal that also carries its {@code AD_MESSAGE} identity, so the error response can
 * publish it in {@code messageKeys} and the SPA can react to the identity rather than the
 * (locale-dependent) prose. See {@link NeoResponse#error(int, OBException)}.
 */
public class MessageKeyedException extends OBException {

  private static final long serialVersionUID = 1L;

  private final String messageKey;

  /** @param messageKey the {@code AD_MESSAGE.VALUE}; the message is its text in the GO locale */
  public MessageKeyedException(String messageKey) {
    super(OBMessageUtils.messageBD(messageKey));
    this.messageKey = messageKey;
  }

  public String getMessageKey() {
    return messageKey;
  }
}
