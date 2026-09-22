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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.weld.WeldUtils;

import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoHandler;

/**
 * Resolves a {@link NeoHandler} from the {@link NeoExtension} annotation on its own class, so a
 * customization can declare the {@code (spec, entity)} it serves in source instead of in
 * {@code ETGO_SF_ENTITY.Java_Qualifier}.
 *
 * <p>This is the <b>first</b> of the two resolution mechanisms. A caller asks here, and only on a
 * miss falls back to the qualifier path — see {@code NeoExtensionDispatcher.resolve}. While no
 * class carries the annotation this index is empty and every lookup misses, which is exactly why
 * the change is additive.</p>
 *
 * <h2>Why this matches on bean metadata, and must keep doing so</h2>
 * <p>The annotation is read off {@link Bean#getBeanClass()} — CDI's record of the class that
 * <i>declared</i> the bean — never off the class of a resolved instance. Reading it off an
 * instance would silently skip every normal-scoped bean: Weld hands out a client proxy, the proxy
 * is a generated subclass, and neither {@code @Named} nor {@link NeoExtension} is
 * {@code @Inherited}, so the annotation is simply not there to find. That defect is live today in
 * {@code NeoServletSupport.lookupHandler} (see {@link NeoHandlerResolutionCache}); rebuilding it
 * here would hand the new mechanism the old mechanism's worst property. {@link NeoHandlerLookup}
 * avoids it the same way, by matching {@link Bean#getName()}.</p>
 *
 * <h2>Lazy, with invalidation</h2>
 * <p>The map is built on first use and never at startup. There is no startup observer in this
 * module, an eager build would collide with Weld/servlet initialisation ordering, and an empty map
 * read during that window would say "nothing is annotated" — failing open on precisely the
 * question this index answers. Unlike {@link NeoHandlerResolutionCache}, the unit built is the
 * whole map rather than one key: the conflict detection below needs to see every annotated class
 * anyway, so a per-key build would either miss conflicts or rescan for each one.</p>
 *
 * <p>What is memoised is the {@link Bean}, never a reference obtained from it, so every caller
 * still gets its own instance and a {@code @Dependent} customization keeps its per-request
 * lifecycle.</p>
 *
 * <h2>Conflicts are logged, never fatal</h2>
 * <p>Two annotated classes claiming one pair, or an annotated pair whose row still points a
 * {@code Java_Qualifier} at a different class, are reported at {@code ERROR} naming both sides and
 * the disputed pair. They do not fail the build and do not fail the request: a gradual migration
 * passes through exactly these states, and a hard failure would block every entity on one
 * transient mistake. {@code make extension-parity} is the offline place to catch them early.</p>
 *
 * <p>A build that throws — CDI absent, or not yet started — memoises nothing and resolves to
 * {@code null}, so the caller simply falls back to the qualifier path exactly as it did before
 * this class existed. A transient CDI failure is never frozen into an empty index.</p>
 */
public final class NeoExtensionIndex {

  private static final Logger log = LogManager.getLogger(NeoExtensionIndex.class);

  private static final Object BUILD_LOCK = new Object();

  /** {@code (spec, entity) -> the annotated bean}; {@code null} until the first build. */
  private static volatile Map<Key, Bean<?>> index;

  private NeoExtensionIndex() {
  }

  /**
   * Resolve the customization annotated for this {@code (spec, entity)}.
   *
   * @param specName  the spec being served, as {@code ETGO_SF_SPEC.Name} spells it
   * @param entity    the entity being served, as {@code ETGO_SF_ENTITY.Name} spells it
   * @param qualifier the {@code Java_Qualifier} the row carries, used only to report a
   *                  disagreement; may be blank
   * @return the annotated customization, or {@code null} when none claims the pair — which is the
   *         caller's signal to fall back to the qualifier
   */
  public static NeoHandler resolve(String specName, String entity, String qualifier) {
    if (StringUtils.isBlank(specName) || StringUtils.isBlank(entity)) {
      return null;
    }
    try {
      BeanManager bm = WeldUtils.getStaticInstanceBeanManager();
      Bean<?> bean = index(bm).get(Key.of(specName, entity));
      if (bean == null) {
        return null;
      }
      warnOnQualifierDisagreement(bean, specName, entity, qualifier);
      return (NeoHandler) bm.getReference(bean, NeoHandler.class, bm.createCreationalContext(bean));
    } catch (RuntimeException e) {
      // Never harden a CDI hiccup into an answer: the caller falls back to the qualifier path,
      // which is what it did before this index existed.
      log.debug("@NeoExtension lookup failed for {}/{}: {}", specName, entity, e.getMessage());
      return null;
    }
  }

  /**
   * Drop the index so the next resolution rescans CDI.
   *
   * <p>For tests, and for a caller that deliberately wants a rebuild. Nothing in normal operation
   * needs it: what is indexed is a deployment fact, and a redeploy replaces this class along with
   * its static state.</p>
   */
  public static void invalidate() {
    synchronized (BUILD_LOCK) {
      index = null;
    }
    log.debug("Invalidated the @NeoExtension index");
  }

  /** Double-checked lazy build; a build that throws leaves the field null and propagates. */
  private static Map<Key, Bean<?>> index(BeanManager bm) {
    Map<Key, Bean<?>> local = index;
    if (local != null) {
      return local;
    }
    synchronized (BUILD_LOCK) {
      local = index;
      if (local == null) {
        local = build(bm);
        index = local;
      }
      return local;
    }
  }

  /**
   * Scan the deployed {@link NeoHandler} beans and index those carrying {@link NeoExtension}.
   *
   * <p>Beans are visited in bean-class-name order so that, when two of them claim the same pair,
   * which one wins is stable across deployments rather than dependent on CDI's set iteration
   * order. The conflict is logged either way; a deterministic winner only keeps the symptom
   * reproducible while somebody fixes it.</p>
   */
  private static Map<Key, Bean<?>> build(BeanManager bm) {
    List<Bean<?>> beans = new ArrayList<>(bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL));
    beans.sort(Comparator.comparing(bean -> bean.getBeanClass().getName()));

    Map<Key, Bean<?>> built = new HashMap<>();
    for (Bean<?> bean : beans) {
      // getBeanClass(), not the class of an instance: see the class javadoc on the proxy trap.
      Class<?> beanClass = bean.getBeanClass();
      NeoExtension annotation = beanClass.getAnnotation(NeoExtension.class);
      if (annotation == null) {
        continue;
      }
      if (StringUtils.isBlank(annotation.spec()) || StringUtils.isBlank(annotation.entity())) {
        log.error("(warn) @NeoExtension on {} declares a blank spec or entity and binds nothing",
            beanClass.getName());
        continue;
      }
      Key key = Key.of(annotation.spec(), annotation.entity());
      Bean<?> previous = built.putIfAbsent(key, bean);
      if (previous != null) {
        log.error(
            "(warn) @NeoExtension conflict on spec={} entity={}: {} and {} both claim it; "
                + "keeping {}",
            annotation.spec(), annotation.entity(), previous.getBeanClass().getName(),
            beanClass.getName(), previous.getBeanClass().getName());
      }
    }
    log.info("(debug) @NeoExtension index built: annotated={} of {} deployed NeoHandler beans",
        built.size(), beans.size());
    return built;
  }

  /**
   * Report a row that still points somewhere else.
   *
   * <p>The comparison is against the annotated class's own {@code @Named} value, not against the
   * class the qualifier actually resolves to: resolving it would mean instantiating a second
   * customization purely to produce a log line. So the line names the annotated class and the
   * qualifier the row carries, and says the two disagree — enough to find the row, without a side
   * effect on the request that found it.</p>
   */
  private static void warnOnQualifierDisagreement(Bean<?> bean, String specName, String entity,
      String qualifier) {
    if (StringUtils.isBlank(qualifier)) {
      return;
    }
    Named named = bean.getBeanClass().getAnnotation(Named.class);
    String ownName = named == null ? null : named.value();
    if (qualifier.equals(ownName)) {
      return;
    }
    log.error(
        "(warn) @NeoExtension conflict on spec={} entity={}: {} claims it by annotation while the "
            + "row still carries Java_Qualifier={} (the class's own @Named is {}); the annotation "
            + "wins",
        specName, entity, bean.getBeanClass().getName(), qualifier,
        ownName == null ? "absent" : ownName);
  }

  /**
   * The index key.
   *
   * <p>Case-folded because the spec lookup that produced the name is itself case-insensitive
   * ({@code NeoServletSupport.findSpec} matches with {@code ilike}), so a case difference between
   * the row and the annotation must not read as "not annotated". Two classes whose pairs differ
   * only in case therefore collide, and are correctly reported as a conflict.</p>
   */
  private record Key(String spec, String entity) {

    static Key of(String spec, String entity) {
      return new Key(spec.toLowerCase(Locale.ROOT), entity.toLowerCase(Locale.ROOT));
    }
  }
}
