package com.etendoerp.go.mcp;

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
      // Set OBContext using the same method as NeoServlet.authenticateJwt
      String effectiveOrg = orgId != null ? orgId : DEFAULT_ORG;
      OBContext context = SecureWebServicesUtils.createContext(
          userId, roleId, effectiveOrg, warehouseId, clientId);
      OBContext.setOBContext(context);

      T result = callable.call();

      // Commit and close the Hibernate session on success
      OBDal.getInstance().commitAndClose();

      return result;
    } catch (Exception e) {
      // Rollback on failure
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
}
