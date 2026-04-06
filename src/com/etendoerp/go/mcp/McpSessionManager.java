package com.etendoerp.go.mcp;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Callable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Manages OBContext and Hibernate session lifecycle for MCP tool calls.
 * <p>
 * MCP uses long-lived SSE connections where multiple tool calls happen over time.
 * Each tool call MUST have its own OBContext and Hibernate session to prevent
 * cross-contamination between calls. This class wraps tool execution with proper
 * setup/teardown following the same pattern as NeoServlet's authenticateJwt.
 */
public class McpSessionManager {

  private static final Logger log = LogManager.getLogger(McpSessionManager.class);

  private static final String DEFAULT_ORG = "0";

  private McpSessionManager() {
    // utility class
  }

  /**
   * Execute a callable within a properly scoped OBContext and Hibernate session.
   * <p>
   * Sets up OBContext using SecureWebServicesUtils.createContext (same as NeoServlet),
   * executes the callable, then commits and closes the Hibernate session on success
   * or rolls back on failure. The previous OBContext is always restored in the finally
   * block to avoid leaking state across tool calls.
   *
   * @param userId      Etendo AD_User_ID (from OAuth2 token)
   * @param roleId      Etendo AD_Role_ID (from OAuth2 token)
   * @param clientId    Etendo AD_Client_ID (from OAuth2 token)
   * @param orgId       Etendo AD_Org_ID (defaults to "0" if null)
   * @param warehouseId Etendo M_Warehouse_ID (can be null)
   * @param callable    the work to execute
   * @param <T>         return type
   * @return the result of the callable
   * @throws Exception if the callable throws
   */
  public static <T> T executeInContext(String userId, String roleId,
      String clientId, String orgId, String warehouseId,
      Callable<T> callable) throws Exception {

    OBContext previousContext = OBContext.getOBContext();
    try {
      // Resolve org: if "0" (wildcard), find the first transactional org for this role
      String effectiveOrg = orgId != null ? orgId : DEFAULT_ORG;
      if (DEFAULT_ORG.equals(effectiveOrg)) {
        String resolved = resolveDefaultOrg(roleId);
        if (resolved != null) {
          effectiveOrg = resolved;
          log.debug("Resolved default org for role {}: {}", roleId, effectiveOrg);
        }
      }

      // Resolve client: if "0" (System), get the client from the role
      // Tables with access level "Organization" reject clientId=0
      String effectiveClient = clientId;
      if ("0".equals(clientId)) {
        String resolvedClient = resolveClientFromRole(roleId);
        if (resolvedClient != null) {
          effectiveClient = resolvedClient;
          log.debug("Resolved client from role {}: {}", roleId, effectiveClient);
        }
      }

      // Set OBContext using the same method as NeoServlet.authenticateJwt
      OBContext context = SecureWebServicesUtils.createContext(
          userId, roleId, effectiveOrg, warehouseId, effectiveClient);
      OBContext.setOBContext(context);

      T result = callable.call();

      // Flush but do NOT close — DalRequestFilter owns the session lifecycle
      OBDal.getInstance().flush();

      return result;
    } catch (Exception e) {
      // Rollback on failure but do NOT close the session
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (Exception rollbackEx) {
        log.error("Error during rollback", rollbackEx);
      }
      throw e;
    } finally {
      // Always restore previous context to prevent cross-call leakage
      if (previousContext != null) {
        OBContext.setOBContext(previousContext);
      }
    }
  }

  /**
   * Convenience overload that defaults orgId to "0" and warehouseId to null.
   *
   * @param userId   Etendo AD_User_ID
   * @param roleId   Etendo AD_Role_ID
   * @param clientId Etendo AD_Client_ID
   * @param callable the work to execute
   * @param <T>      return type
   * @return the result of the callable
   * @throws Exception if the callable throws
   */
  public static <T> T executeInContext(String userId, String roleId,
      String clientId, Callable<T> callable) throws Exception {
    return executeInContext(userId, roleId, clientId, DEFAULT_ORG, null, callable);
  }

  /**
   * Void-returning variant for tool calls that don't produce a result.
   *
   * @param userId      Etendo AD_User_ID
   * @param roleId      Etendo AD_Role_ID
   * @param clientId    Etendo AD_Client_ID
   * @param orgId       Etendo AD_Org_ID (defaults to "0" if null)
   * @param warehouseId Etendo M_Warehouse_ID (can be null)
   * @param action      the work to execute
   * @throws Exception if the action throws
   */
  public static void runInContext(String userId, String roleId,
      String clientId, String orgId, String warehouseId,
      Runnable action) throws Exception {
    executeInContext(userId, roleId, clientId, orgId, warehouseId, () -> {
      action.run();
      return null;
    });
  }

  /**
   * Void-returning variant with default org and warehouse.
   *
   * @param userId   Etendo AD_User_ID
   * @param roleId   Etendo AD_Role_ID
   * @param clientId Etendo AD_Client_ID
   * @param action   the work to execute
   * @throws Exception if the action throws
   */
  public static void runInContext(String userId, String roleId,
      String clientId, Runnable action) throws Exception {
    runInContext(userId, roleId, clientId, DEFAULT_ORG, null, action);
  }

  /**
   * Resolve the AD_Client_ID from the role record.
   * When the OAuth2 token stores clientId="0" (System), we need the role's actual client
   * because tables with access level "Organization" reject client 0.
   */
  private static String resolveClientFromRole(String roleId) {
    try {
      return OBDal.getInstance().getSession().doReturningWork(connection -> {
        String sql = "SELECT ad_client_id FROM ad_role WHERE ad_role_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
          ps.setString(1, roleId);
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              String cid = rs.getString(1);
              return "0".equals(cid) ? null : cid;
            }
          }
        }
        return null;
      });
    } catch (Exception e) {
      log.warn("Failed to resolve client from role {}: {}", roleId, e.getMessage());
      return null;
    }
  }

  /**
   * Resolve the first transactional organization accessible by the given role.
   * Uses the Hibernate session's JDBC connection (same pattern as OAuth2Filter).
   * Returns null if no transactional org is found (falls back to org "0").
   */
  private static String resolveDefaultOrg(String roleId) {
    try {
      return OBDal.getInstance().getSession().doReturningWork(connection -> {
        String sql =
            "SELECT ra.ad_org_id FROM ad_role_orgaccess ra "
            + "JOIN ad_org o ON ra.ad_org_id = o.ad_org_id "
            + "JOIN ad_orgtype ot ON o.ad_orgtype_id = ot.ad_orgtype_id "
            + "WHERE ra.ad_role_id = ? AND ra.isactive = 'Y' AND o.isactive = 'Y' "
            + "AND ot.istransactionsallowed = 'Y' "
            + "ORDER BY o.name LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
          ps.setString(1, roleId);
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              return rs.getString(1);
            }
          }
        }
        return null;
      });
    } catch (Exception e) {
      log.warn("Failed to resolve default org for role {}: {}", roleId, e.getMessage());
      return null;
    }
  }
}
