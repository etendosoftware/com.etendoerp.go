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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares, in the source file itself, which {@code (spec, entity)} a {@link NeoHandler} serves.
 *
 * <h2>The problem it replaces</h2>
 * <p>Today the only binding between an entity and its customization is
 * {@code ETGO_SF_ENTITY.Java_Qualifier}: a string column matched against {@code @Named} on the
 * handler. Two things follow from that, and both are costs paid on every refactor:</p>
 * <ul>
 *   <li><b>The binding is invisible from the code.</b> Opening a handler does not tell you which
 *       entities reach it; you have to go and read a database row (or the committed
 *       {@code ETGO_SF_ENTITY.xml}) to find out.</li>
 *   <li><b>A pure code refactor demands a data change.</b> One qualifier can serve several
 *       entities — {@code orderLineHandler} is pointed at by {@code purchase-order/lines},
 *       {@code sales-order/lines} and {@code sales-quotation/quotationLine} — so splitting that
 *       one class into three means inventing three qualifiers, editing three rows and re-running
 *       {@code export.database}.</li>
 * </ul>
 *
 * <p>With this annotation the binding travels with the class. Splitting a handler becomes three
 * new files carrying three different {@code (spec, entity)} pairs, and no row changes at all.</p>
 *
 * <h2>Additive, never a migration</h2>
 * <p>The annotation is looked up <b>first</b>, and {@code Java_Qualifier} remains the fallback,
 * unchanged. While no class carries {@code @NeoExtension} the system behaves exactly as before —
 * that is the acceptance criterion of this step. None of the existing handlers is touched and
 * nobody is forced to migrate; a handler moves to the annotation when someone has a reason to
 * open it anyway.</p>
 *
 * <h2>What it does not do yet</h2>
 * <p>An annotated class still implements {@link NeoHandler}, exactly as today. The richer
 * {@code NeoExtensionHandler} interface is a separate, later step; this annotation only changes
 * <i>how the class is found</i>, never what it can do once found.</p>
 *
 * <h2>Conflicts</h2>
 * <p>Two annotated classes claiming the same pair, or an annotated pair whose row still carries a
 * {@code Java_Qualifier} naming a different class, are logged at {@code ERROR} and never fail the
 * build. A gradual migration passes through exactly those states, and blocking everyone on a
 * transient one would make the migration more expensive than the problem it solves. The offline
 * {@code make extension-parity} target is where a conflict is meant to be noticed early.</p>
 *
 * <h2>Package</h2>
 * <p>It sits next to {@link NeoHandler} rather than in a package of its own because a handler
 * author needs the two together and nothing else: one import line, in the package they already
 * import from. The resolution machinery that reads it lives in {@code schemaforge.util}, where
 * the other resolvers are.</p>
 *
 * @see com.etendoerp.go.schemaforge.util.NeoExtensionIndex
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NeoExtension {

  /**
   * The spec name, exactly as {@code ETGO_SF_SPEC.Name} spells it — kebab-case, e.g.
   * {@code "sales-order"}.
   *
   * @return the spec this class serves
   */
  String spec();

  /**
   * The entity name, exactly as {@code ETGO_SF_ENTITY.Name} spells it — e.g. {@code "lines"} or
   * {@code "quotationLine"}.
   *
   * @return the entity this class serves
   */
  String entity();
}
