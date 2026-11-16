package com.etendoerp.go.modulescript;

import java.sql.PreparedStatement;
import java.util.List;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

import com.etendoerp.go.modulescript.EnsureSupportAccessSeedScript.TrlTable;
import com.etendoerp.go.modulescript.EnsureSupportAccessSeedScript.Translation;

/**
 * ETP-6209 — the Spanish names of the document statuses this module adds to the order status list
 * ({@code AD_Reference FF80818130217A350130218D802B0011}): {@code ETGO_CI} "Cerrado - Factura
 * creada" (its {@code es_ES} row kept the English name until now) and {@code ETGO_AC} "Aceptado",
 * the quotation a customer accepted from the portal.
 *
 * <p>The module's base language is en_US and it ships no translation module, so
 * {@code export.database} never exports its {@code _TRL} rows: they are written here, on every
 * {@code update.database}, the way {@link EnsureSupportAccessSeedScript} writes the support
 * window's. Each language is written only when it is a system language, and a translation edited
 * by a user (updated by someone other than System and marked as translated) is never overwritten.
 * The backend reads these names through {@code AD_Ref_List_Trl} (MCP list labels, the email status
 * sentence); the SPA has its own copy in its locale files.</p>
 *
 * <p>Self-contained apart from the {@code _TRL} SQL it shares with
 * {@link EnsureSupportAccessSeedScript}: no import from this module's {@code src/} tree.</p>
 */
public class EnsureDocumentStatusTranslationsScript extends ModuleScript {

  /** {@code AD_Ref_List_ID} of {@code ETGO_CI} "Closed - Invoice Created". */
  static final String INVOICE_CREATED_ID = "359305FF926B499EA3E6D64C86039B18";
  /** {@code AD_Ref_List_ID} of {@code ETGO_AC} "Accepted". */
  static final String ACCEPTED_ID = "7BD5D4F5C69F426C879D5F22823C6C3D";

  /** The Spanish variants that get the texts, when they are system languages. */
  static final List<String> SPANISH_LANGUAGES = List.of("es_ES", "es_AR");

  static final Translation[] SPANISH_TEXTS = {
      new Translation(TrlTable.REF_LIST, INVOICE_CREATED_ID, "Cerrado - Factura creada"),
      new Translation(TrlTable.REF_LIST, ACCEPTED_ID, "Aceptado"),
  };

  @Override
  public void execute() {
    try {
      ConnectionProvider cp = getConnectionProvider();
      for (String language : SPANISH_LANGUAGES) {
        for (Translation translation : SPANISH_TEXTS) {
          write(cp, translation, language);
        }
      }
    } catch (Exception e) {
      handleError(e);
    }
  }

  private static void write(ConnectionProvider cp, Translation translation, String language)
      throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(translation.table.insertSql)) {
      ps.setString(1, translation.text);
      ps.setString(2, language);
      ps.setString(3, translation.id);
      ps.executeUpdate();
    }
    try (PreparedStatement ps = cp.getPreparedStatement(translation.table.updateSql)) {
      ps.setString(1, translation.text);
      ps.setString(2, translation.id);
      ps.setString(3, language);
      ps.setString(4, translation.text);
      ps.executeUpdate();
    }
  }
}
