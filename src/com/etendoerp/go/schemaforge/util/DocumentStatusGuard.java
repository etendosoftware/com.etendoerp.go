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

package com.etendoerp.go.schemaforge.util;

import org.openbravo.base.exception.OBException;

/**
 * Shared precondition of the document-generating actions (ETP-5709): the SOURCE document must be
 * completed. Used by the receipt, shipment and invoice creators; generic by construction — it
 * reads the structural {@code documentStatus} and names no spec or entity.
 */
public final class DocumentStatusGuard {

  /** Document status of a completed document ({@code DocStatus = 'CO'}). */
  public static final String STATUS_COMPLETED = "CO";

  /** Document status of a document under evaluation ({@code DocStatus = 'UE'}), e.g. a quotation. */
  public static final String STATUS_UNDER_EVALUATION = "UE";

  private DocumentStatusGuard() {
  }

  /**
   * Refuses a source that is not completed. Only {@code CO} qualifies: a voided or reversed
   * document must not generate anything, and a closed ({@code CL}) order has no pending quantity
   * left anyway. Each caller supplies its own message (language and wording).
   *
   * @param documentNo     the source document number, for the message
   * @param documentStatus the source's current {@code documentStatus} code
   * @param messageFormat  {@link String#format} pattern with {@code %s} for the document number
   *                       and {@code %s} for its status
   * @throws OBException (surfaced as 400) when the status is not {@code CO}
   */
  public static void requireCompleted(String documentNo, String documentStatus,
      String messageFormat) {
    if (!STATUS_COMPLETED.equals(documentStatus)) {
      throw new OBException(String.format(messageFormat, documentNo, documentStatus));
    }
  }

  /**
   * Refuses a source whose status is not exactly {@code requiredStatus}. Same contract as
   * {@link #requireCompleted} for a precondition other than "completed" (e.g. a quotation is
   * invoiced only while under evaluation).
   *
   * @param documentNo     the source document number, for the message
   * @param documentStatus the source's current {@code documentStatus} code
   * @param requiredStatus the only status that passes
   * @param messageFormat  {@link String#format} pattern with {@code %s} for the document number
   *                       and {@code %s} for its status
   * @throws OBException (surfaced as 400) when the status differs from {@code requiredStatus}
   */
  public static void requireStatus(String documentNo, String documentStatus,
      String requiredStatus, String messageFormat) {
    if (!requiredStatus.equals(documentStatus)) {
      throw new OBException(String.format(messageFormat, documentNo, documentStatus));
    }
  }

  /**
   * Refuses a source whose status is none of {@code allowedStatuses}. Same contract as
   * {@link #requireStatus} for a precondition that more than one status satisfies.
   *
   * @param documentNo      the source document number, for the message
   * @param documentStatus  the source's current {@code documentStatus} code
   * @param messageFormat   {@link String#format} pattern with {@code %s} for the document number
   *                        and {@code %s} for its status
   * @param allowedStatuses the statuses that pass
   * @throws OBException (surfaced as 400) when the status is not among {@code allowedStatuses}
   */
  public static void requireAnyStatus(String documentNo, String documentStatus,
      String messageFormat, String... allowedStatuses) {
    for (String allowed : allowedStatuses) {
      if (allowed.equals(documentStatus)) {
        return;
      }
    }
    throw new OBException(String.format(messageFormat, documentNo, documentStatus));
  }
}
