/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.modulescript;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Creates the document sequence used for Business Partner search keys.
 *
 * <p>This module script ensures that a document sequence exists for each client,
 * excluding the System client.</p>
 */
public class CreateBusinessPartnerSequence extends ModuleScript {

  private static final String SEQUENCE_NAME = "BusinessPartner_ID";

  private static final String SELECT_CLIENTS_WITHOUT_SEQUENCE =
      "SELECT cl.ad_client_id AS client "
          + "FROM ad_client cl "
          + "WHERE cl.ad_client_id <> '0' "
          + "  AND cl.isactive = 'Y' "
          + "  AND NOT EXISTS ( "
          + "    SELECT 1 "
          + "    FROM ad_sequence s "
          + "    WHERE s.ad_client_id = cl.ad_client_id "
          + "      AND s.name = ? "
          + "  )";
  private static final String INSERT_DOCUMENT_NO_SEQUENCE =
      "INSERT INTO AD_Sequence ( "
          + "  AD_Sequence_ID, AD_Client_ID, AD_Org_ID, IsActive, "
          + "  Created, CreatedBy, Updated, UpdatedBy, "
          + "  Name, Description, VFormat, Mask, "
          + "  IsAutoSequence, IncrementNo, "
          + "  StartNo, CurrentNext, CurrentNextSys, "
          + "  IsTableID, Prefix, Suffix, StartNewYear, "
          + "  AD_Table_ID, AD_Column_ID "
          + ") VALUES ( "
          + "  get_uuid(), ?, '0', 'Y', "
          + "  now(), '0', now(), '0', "
          + "  ?, 'Sequence used to generate the Search Key for Business Partners.', "
          + "  NULL, '#######', "
          + "  'Y', 1, "
          + "  1000000, 1000000, 1000000, "
          + "  'N', NULL, NULL, 'N', "
          + "  291, '294937FFC81749289BD9BB28E400D4B2' "
          + ")";

  /**
   * Executes the module script to create missing Business Partner document sequences.
   *
   * <p>Retrieves all applicable clients and inserts a document number sequence
   * using the configured sequence name.</p>
   */
  @Override
  public void execute() {
    try {
      ConnectionProvider cp = getConnectionProvider();
      PreparedStatement selectPs = null;
      ResultSet rs = null;
      try {
        selectPs = cp.getPreparedStatement(SELECT_CLIENTS_WITHOUT_SEQUENCE);
        selectPs.setString(1, SEQUENCE_NAME);
        rs = selectPs.executeQuery();

        while (rs.next()) {
          PreparedStatement insertPs = null;
          try {
            insertPs = cp.getPreparedStatement(INSERT_DOCUMENT_NO_SEQUENCE);
            insertPs.setString(1, rs.getString("client"));
            insertPs.setString(2, SEQUENCE_NAME);
            insertPs.executeUpdate();
          } finally {
            if (insertPs != null) {
              cp.releasePreparedStatement(insertPs);
            }
          }
        }
      } catch (SQLException e) {
        handleError(e);
      } finally {
        if (rs != null) {
          try {
            rs.close();
          } catch (SQLException e) {
            handleError(e);
          }
        }
        if (selectPs != null) {
          cp.releasePreparedStatement(selectPs);
        }
      }
    } catch (Exception e) {
      this.handleError(e);
    }
  }
}
