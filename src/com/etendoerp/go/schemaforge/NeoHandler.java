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

/**
 * Interface for NEO Headless hook handlers.
 * Implementations are discovered via CDI using the Java_Qualifier
 * registered on ETGO_SF_Entity.
 *
 * Use {@code @Named("qualifierValue")} on your implementation to match
 * the Java_Qualifier configured on the entity record.
 *
 * Return a NeoResponse to take over the response, or null to continue
 * with default DataSourceServlet behavior.
 */
public interface NeoHandler {

  /**
   * Handle the request.
   * Return a NeoResponse to produce the full response, or null to
   * fall through to default DataSourceServlet handling.
   *
   * @param context the NeoContext carrying request metadata, entity info and OB session
   * @return a {@link NeoResponse} to send to the client, or {@code null} to fall through
   */
  NeoResponse handle(NeoContext context);

  /**
   * Post-hook: called AFTER the default service executed.
   * The context's previousResult contains the service result.
   * Return a NeoResponse to replace it, or null to keep the original.
   *
   * @param context the NeoContext whose {@code previousResult} holds the default service output
   * @return a {@link NeoResponse} to replace the default result, or {@code null} to keep it
   */
  default NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
