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
package com.etendoerp.go.roles;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Priority;
import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.client.kernel.event.TransactionCompletedEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.roles.overlap.ActiveTemplateInheritance;
import com.etendoerp.go.roles.overlap.GrantCandidate;
import com.etendoerp.go.roles.overlap.OverlapReconciliationCore;
import com.etendoerp.go.roles.overlap.OverlapWinner;
import com.etendoerp.go.roles.overlap.TemplateRemovalTracker;

/**
 * ETP-4906 (Task B6, REDESIGNED 2026-08-16) — widens ETP-4852's cross-template
 * {@code AD_Window_Access} overlap-corruption fix beyond the single, actively-composed role
 * {@link UserRoleCompositionService} manages in one {@code assignTemplateRoles} call. See that
 * class's own class javadoc for the full root-cause write-up; summarized here only as far as
 * needed to explain why THIS class exists and why it works this way.
 *
 * <p><b>This is the SECOND design for this class — the first (a reactive, correction-based
 * {@code EntityPersistenceEventObserver} that tried to fix a corrupted row's client/organization
 * AFTER Hibernate decided to write it, via {@code EntityPersistenceEvent#setCurrentState}) does
 * NOT work, confirmed both by reading core source and by a live reproduction against a real
 * running Etendo instance (see ETP-4906's plan doc, "B6 Findings — Root Cause"). Two structural
 * reasons, traced into {@code OBInterceptor}/{@code SecurityChecker}:</b>
 * <ol>
 *   <li>{@code SecurityChecker.checkWriteAccess(Object)} reads the client id via a LIVE getter
 *   call on the actual Java entity instance ({@code ((ClientEnabled) obj).getClient().getId()})
 *   — never Hibernate's {@code currentState[]} dirty-check array a {@code setCurrentState} call
 *   corrects. Correcting {@code currentState[]} therefore never changes what the check reads.</li>
 *   <li>Independently fatal even if (1) were fixed: {@code OBInterceptor.onFlushDirty()}/{@code
 *   onSave()} both call {@code doEvent()} — which runs {@code checkWriteAccess()} — BEFORE they
 *   ever invoke the CDI listener chain an {@code EntityPersistenceEventObserver} hooks into. The
 *   security check always runs before any observer gets a chance to react, for both the UPDATE
 *   and CREATE paths — a reactive "correct after the write starts" observer cannot win that
 *   race, structurally, no matter what it corrects or how.</li>
 * </ol>
 *
 * <p><b>This design is a PREVENTION strategy instead — the only kind proven to work</b> (it is
 * exactly what {@link UserRoleCompositionService#preventWindowAccessOverlapCorruption(Role,
 * Role)} already does for the ONE role it actively manages): proactively DELETE a dependent
 * role's conflicting {@code AD_Window_Access} row BEFORE core's propagation ever tries to UPDATE
 * it, forcing core onto the safe CREATE path instead (per {@code SecurityChecker.checkWriteAccess}
 * itself: a brand-new row saved under {@code copyRoleAccess}'s own local {@code
 * OBContext.setAdminMode(false)}/{@code (true)} bypass sequencing never observes the corrupted
 * client at all, because the CREATE path's own security check runs while that bypass — or lack of
 * one — is still consistent, unlike the UPDATE path's check, which fires at a LATER flush after
 * any protective bypass has already been popped).
 *
 * <p><b>The gap this closes, beyond {@code UserRoleCompositionService}'s own two helpers.</b>
 * {@code preventWindowAccessOverlapCorruption}/{@code reconcileWindowAccessAfterComposition} only
 * ever look at the ONE {@code personalRole} passed into a given {@code assignTemplateRoles} call.
 * Core's own propagation (({@code RoleInheritanceManager#propagateNewAccess}, triggered by {@code
 * InheritedAccessEnabledEventHandler#onSave} whenever a NEW {@code AD_Window_Access} row is
 * granted directly on a template role), however, is NOT scoped to that one role — it iterates
 * EVERY role whose {@code AD_Role_Inheritance} points {@code InheritFrom} at whichever template
 * just gained the new grant. So any OTHER already-existing role that also inherits from that
 * template — reachable from ANY entry point, including a raw Etendo Classic UI edit to the
 * template's own Window Access tab, with ZERO {@code UserRoleCompositionService} code in the call
 * stack (live-reproduced, see the plan doc) — gets swept into the exact same corrupting write,
 * with zero protection from those two role-scoped helpers.
 *
 * <p><b>Why an event observer instead of widening the two service-layer helpers.</b> The
 * corruption is triggered by a write to the TEMPLATE's own {@code AD_Window_Access} — a write that
 * may never go through {@code assignTemplateRoles} (or any code in this module) at all. The only
 * mechanism that can defend every entry point uniformly is one that watches the TEMPLATE side's
 * own persistence events directly — the same extension point {@code ContactNameSyncHandler}
 * already uses elsewhere in this module. No core patch: a plain module-level {@link
 * EntityPersistenceEventObserver}.
 *
 * <p><b>Why {@code @Priority} matters here.</b> Core's own propagation trigger, {@code
 * InheritedAccessEnabledEventHandler#onSave}/{@code #onUpdate}, is ALSO a CDI observer of the
 * exact same {@code EntityNewEvent}/{@code EntityUpdateEvent} this class observes (both fire
 * through {@code PersistenceEventOBInterceptor}, which fans a single Hibernate interceptor
 * callback out to every registered {@code EntityPersistenceEventObserver} via a synchronous CDI
 * event). This class MUST run its own delete-before-write BEFORE core's handler starts
 * propagating, or the race is lost. There is no earlier module-level extension point available
 * (core's {@code preFlush}/{@code postFlush} interceptor hooks are never forwarded to CDI
 * observers — see {@code PersistenceEventOBInterceptor}, which only fires {@code EntityNewEvent}/
 * {@code EntityUpdateEvent}/{@code EntityDeleteEvent}/{@code TransactionBeginEvent}/{@code
 * TransactionCompletedEvent}), so ordering the SAME event via {@code @Priority} (CDI 2.0 spec
 * §10.4.2 — observers carrying a priority are notified, in ascending priority order, before any
 * unprioritized observer; Weld 3.1, in use here, implements this) is the only lever available.
 * {@code InheritedAccessEnabledEventHandler} declares no priority, so any priority value on this
 * class's observer methods is enough to guarantee it runs first.
 *
 * <p><b>What it actually does</b> (mirrors {@code preventWindowAccessOverlapCorruption}'s own
 * proven mechanism, generalized). Observes save AND update events for {@code AD_Window_Access}.
 * Whenever the row belongs to a TEMPLATE role (the exact trigger {@code
 * InheritedAccessEnabledEventHandler} itself checks for before propagating): for every OTHER role
 * currently, actively inheriting from that template, looks up that role's own active {@code
 * AD_Window_Access} row for the SAME window; if one exists and is not ALREADY sourced from this
 * same template (i.e. it is either manually granted or inherited from a DIFFERENT template — the
 * exact precondition core's own {@code RoleInheritanceManager#handleAccess}/{@code isPrecedent}
 * treats as "needs overriding", which is what routes it onto the corrupting UPDATE path), deletes
 * it — under the SAME {@code OBContext.setAdminMode(false)} bypass core's own {@code
 * deleteRoleAccess} uses for removing a cross-client-owned inherited row — BEFORE control returns
 * to core's own propagation logic. With no existing row left to find, core's {@code handleAccess}
 * takes the safe CREATE path for that dependent role and window, exactly as it already does for
 * the one row {@code preventWindowAccessOverlapCorruption} defends.
 *
 * <p>Deliberately narrow: only reacts when the SAVED/UPDATED row's OWNING role is itself a
 * template (the one signal core's own event handler uses to decide whether to propagate at all —
 * see {@code InheritedAccessEnabledEventHandler#doAction}), and only ever deletes an OTHER role's
 * row for the SAME window as the template row that triggered the event — never touches the
 * template's own row. {@code guardDependentsOf}/{@code guardNewInheritance}/{@code
 * guardRemovedInheritance} themselves never widen/narrow any grant level and never run at all for
 * a template-owned row's grant LEVEL change alone — their only job is forcing core onto the safe
 * CREATE path so it re-derives the level from the correct source. (As of the fourth trigger below,
 * a SEPARATE method, {@code widenInheritedAccessLevelIfNeeded}, does decide levels for the row
 * that CREATE path produces — see that section for why this is a distinct concern from ownership
 * correction.) {@code UserRoleCompositionService#reconcileWindowAccessAfterComposition} remains
 * the most-permissive-wins union authority for the role it is actively composing; this class's job
 * is making sure core never gets the chance to corrupt a BYSTANDER role's ownership fields OR
 * silently under-resolve its access level, for every entry point {@code
 * UserRoleCompositionService} does not see.
 *
 * <p><b>A second, symmetric trigger: a role gaining a brand-new {@code AD_Role_Inheritance} from
 * an already-overlapping template.</b> Empirically found while re-verifying this redesign (not
 * merely theorized): this environment's REAL Finance/Sales system templates now genuinely overlap
 * on a real window (drifted since ETP-4852/ETP-4878 was written — both templates currently grant
 * window {@code 143}), so simply adding both real templates' {@code AD_Role_Inheritance} rows to
 * ANY role that never goes through {@code assignTemplateRoles} — exactly what a bystander-role
 * test (or a raw Classic UI "add inheritance" edit) does — ALSO drives core's {@code
 * RoleInheritanceEventHandler#onSave} → {@code RoleInheritanceManager#applyNewInheritance} into
 * the identical corrupting-UPDATE risk this class defends against on the {@code AD_Window_Access}
 * side, just via a different entity. This is the SAME underlying core bug (undifferentiated
 * {@code getSkippedProperties()}), reachable through a second door — so this class also observes
 * {@code RoleInheritance} save events (there is no meaningful update path to guard: core's own
 * {@code RoleInheritanceEventHandler#onUpdate} unconditionally rejects editing an existing
 * inheritance row) and applies the exact same delete-before-write logic, scoped to the ONE role
 * gaining the ONE new inheritance, before core's own (also unprioritized) handler propagates the
 * newly-inherited template's entire grant set onto it.
 *
 * <p><b>A third trigger, on the REMOVE side: a role LOSING an existing {@code
 * AD_Role_Inheritance}.</b> Live-confirmed gap (ETP-4906, Task B6 follow-up, 2026-08-16): the two
 * triggers above only defend a role GAINING access. Removing one of two overlapping templates
 * from an already-composed role reproduces the identical {@code OBSecurityException}, from a
 * THIRD, previously-unguarded door. Traced into core: {@code RoleInheritanceEventHandler#onDelete}
 * (unprioritized, same {@code EntityDeleteEvent} this class now also observes) calls {@code
 * RoleInheritanceManager#applyRemoveInheritance}, which recalculates the dependent role's access
 * against EVERY remaining template it still inherits from via the SAME {@code
 * calculateAccesses}/{@code handleAccess} path {@code applyNewInheritance} uses — for any window a
 * REMAINING template grants, {@code isPrecedent} treats the dependent's existing row as needing
 * override whenever its current {@code inheritedFrom} is no longer in the updated (post-removal)
 * template list, which is exactly true for a row still sourced from the template being removed —
 * driving the exact same corrupting {@code updateRoleAccess} blind copy this class already guards
 * against on the ADD side. This class also observes {@code RoleInheritance} DELETE events: for
 * every OTHER template the dependent still actively inherits from, for every window that template
 * grants, if the dependent's own existing row for that window is not ALREADY sourced from that
 * exact remaining template, it is deleted BEFORE core's own (unprioritized) {@code
 * RoleInheritanceEventHandler#onDelete} propagates — forcing the same safe CREATE path. A row
 * whose window is granted by NO remaining template is deliberately left untouched here: core's own
 * {@code deleteRoleAccess} cleanup step removes it via a normal, non-corrupting entity delete
 * (no cross-client field copy involved), so there is nothing to prevent for that case.
 *
 * <p><b>A fourth trigger: most-permissive-wins is not enforced outside {@code
 * UserRoleCompositionService}.</b> Live-confirmed gap (ETP-4906, Task B6 4th round, 2026-08-16),
 * DIFFERENT in kind from the three triggers above — not a crash, a silently WRONG result. The
 * three triggers above only ever decide whether core takes the safe CREATE path instead of the
 * corrupting UPDATE path for ownership ({@code client}/{@code organization}); NONE of them decide
 * which access LEVEL (full vs. read-only) the CREATE path should use when 2+ actively-inherited
 * templates grant the same window — that decision is made ONLY by {@code
 * UserRoleCompositionService#reconcileWindowAccessAfterComposition}, which runs EXCLUSIVELY inside
 * {@code assignTemplateRoles}. Live-reproduced: a role with only a full-access template
 * ({@code ClassicTemplateTest2Broad}) on a window, then gaining an inheritance from a read-only
 * template ({@code ClassicTemplateTest1Read}) on the SAME window via Etendo Classic (not through
 * this module's own webhook) ends up READ-ONLY, when most-permissive-wins requires it stay FULL —
 * no exception, no log, just wrong data.
 *
 * <p>The fix reuses the exact extension point {@link #correctInheritedOwnership(EntityNewEvent,
 * WindowAccess)} already proved: it fires on {@code EntityNewEvent} for EVERY freshly-created
 * inherited {@code AD_Window_Access} row on a non-template role — precisely the row the CREATE
 * path (forced by one of the three triggers above, or a plain first-time grant) produces — and
 * {@code event.setCurrentState(Property, Object)} already reliably corrects such a row's fields
 * before the security check runs. {@link #widenInheritedAccessLevelIfNeeded(EntityNewEvent,
 * WindowAccess)} runs right alongside it (same {@code onSave} branch): before the row is
 * persisted, it looks up {@code access.getRole()}'s OTHER currently-active template inheritances
 * ({@link #findActiveTemplatesFor(Role, String)}, the same query pattern {@code
 * findOtherActiveTemplates} already used for the REMOVE-path trigger — and the same source-of-
 * truth choice {@code UserRoleCompositionService#mostPermissiveWindowAccess} makes: the templates'
 * OWN current grants, not whatever level core's propagation happened to leave behind) and, if ANY
 * of them grants the SAME window at a MORE permissive level ({@code isEditableField() == true})
 * than the row about to be created, corrects it via {@code event.setCurrentState(
 * editableFieldProperty, true)} — the same mechanism, applied to a different field. Deliberately
 * a SEPARATE method from {@code correctInheritedOwnership}, not a merge into it: ownership
 * correction is unconditional (a row is either wrong or not), while level widening requires an
 * extra query across the role's OTHER template inheritances and a strictly one-directional rule
 * (never narrows — matches the existing rule: a full grant, once resolved, always wins) that reads
 * more clearly kept apart. This generalizes {@code reconcileWindowAccessAfterComposition}'s own
 * most-permissive-wins union to ANY newly-created inherited row, not only ones created via {@code
 * assignTemplateRoles}.
 *
 * <p><b>InheritedFrom bookkeeping — a fifth, immediately-following gap in the fourth trigger's own
 * first fix.</b> Live-confirmed gap (ETP-4906, Task B6 5th round, 2026-08-16), found by the human
 * continuing self-review with the REMOVE direction on the SAME scenario the fourth trigger's fix
 * addressed. The fourth trigger's first implementation widened {@code editableField} correctly but
 * never touched {@code inheritedFrom} on the same row — so a row widened to full because ANOTHER
 * template justifies it kept pointing {@code InheritedFrom} at whichever template the CREATE path
 * happened to source the row from originally (which, by construction, does NOT itself grant full
 * access — that mismatch is exactly why widening was needed in the first place). Confirmed via a
 * direct read-only {@code psql} query: {@code isreadwrite='Y'} (correct, widened) while {@code
 * inherited_from} still pointed at the read-only template, not the full one. This breaks REMOVAL
 * specifically: BOTH core's own {@code RoleInheritanceManager} re-derivation AND this class's own
 * third-trigger {@link #guardRemovedInheritance(RoleInheritance)} decide whether a row needs
 * re-evaluating when a {@code RoleInheritance} is removed by checking whether that row's {@code
 * InheritedFrom} matches the template being removed — a row whose {@code InheritedFrom} lies about
 * which template is actually responsible for its value is invisible to that check for the ONE
 * removal that should affect it, and stays stuck at "full" forever.
 *
 * <p>The fix: {@link #widenInheritedAccessLevelIfNeeded(EntityNewEvent, WindowAccess)} now also
 * repoints {@code inheritedFrom} to the SAME template it already resolved as justifying the
 * widened value ({@link #findActiveTemplateGrantingFullAccess(Role, Window)} — the fourth
 * trigger's own helper, changed from a boolean "does one exist" check into one that also returns
 * WHICH template), via the identical {@code event.setCurrentState(Property, Object)} mechanism
 * already used for {@code editableField} on this same row and for ownership on {@code
 * correctInheritedOwnership}'s row. This keeps {@code InheritedFrom} an accurate "who is currently
 * responsible for this row's effective value" pointer at all times, so a later removal of THAT
 * exact template's inheritance correctly triggers re-derivation in both mechanisms above, cascading
 * to whatever template remains. Only runs in the branch that already widens (i.e. only when {@code
 * editableField} was actually {@code false} and gets flipped) — a row that is ALREADY full when
 * CREATE-sourced never needs repointing, because the template CREATE sourced it from must itself
 * grant full access for that to be true, so {@code InheritedFrom} is already correct in that case;
 * see {@link #widenInheritedAccessLevelIfNeeded(EntityNewEvent, WindowAccess)}'s own early-return
 * comment for the full reasoning. Tie-break when 2+ other active templates are equally responsible:
 * see {@link #findActiveTemplateGrantingFullAccess(Role, Window)}'s own javadoc (highest {@code
 * AD_Role_Inheritance.SeqNo}, mirroring core's own {@code
 * RoleInheritanceManager#propagateDeletedAccess} heuristic).
 *
 * <p><b>A sixth trigger — the REMOVE-side fix itself corrupts a role composed from 3+ overlapping
 * templates.</b> Live-confirmed BLOCKER (ETP-4906, Task B6, 6th round, 2026-08-17), found by the
 * human on the REAL {@code SFAssignUserRoles} webhook (not a raw Classic edit — the first bug in
 * this whole B6 sequence to break the actual production flow this ticket exists to deliver): a
 * real multi-role account composed from ALL 4 system templates (Finance/Sales/Purchasing/
 * Inventory) had Finance unchecked and saved, crashing with {@code ConstraintViolationException:
 * duplicate key value violates unique constraint "ad_window_access_un_key"}. All five rounds
 * above, and every JUnit test written for them, only ever exercised a role composed from EXACTLY
 * 2 templates (so removing one always left exactly ONE remaining template) — this is the first
 * real exercise of 3+ overlapping templates, where removing one leaves 2+ REMAINING templates
 * that themselves ALSO overlap on the same window, and it breaks by a DIFFERENT, deeper mechanism
 * than any of the five gaps above.
 *
 * <p><b>Root cause, traced into {@code RoleInheritanceManager#applyRemoveInheritance}/{@code
 * calculateAccesses}.</b> Unlike {@code applyNewInheritance} (ADD-path — {@code inheritanceList}
 * passed to {@code calculateAccesses} contains exactly ONE template: the one just added, so its
 * outer loop runs exactly once per window, structurally incapable of this race), {@code
 * applyRemoveInheritance} passes {@code calculateAccesses} the FULL list of every REMAINING
 * template, and its outer loop walks ALL of them, ascending by {@code AD_Role_Inheritance.SeqNo},
 * calling {@code handleAccess} once per (remaining template, window-that-template-grants) pair —
 * with NO flush between passes ({@code calculateAccesses(..., doFlush=false)} on this call site).
 * When {@link #guardRemovedInheritance(RoleInheritance)}'s OLD implementation deleted a dependent's
 * existing row for a window (forcing core onto the "safe" CREATE path, exactly like the ADD-side
 * triggers above), and that SAME window happens to be granted by 2+ of the REMAINING templates —
 * only possible with 3+ templates total — core's outer loop processes EACH of those remaining
 * templates' passes independently: the FIRST pass's {@code getAccess()} query finds nothing (the
 * row was deleted) and calls {@code copyRoleAccess} → {@code OBDal.save()}, scheduling an INSERT;
 * the SECOND pass's {@code getAccess()} query, run moments later in the SAME {@code
 * calculateAccesses} call, does NOT see that still-pending, not-yet-flushed INSERT (Openbravo's DAL
 * sets every session to {@code FlushMode.COMMIT} — see {@link #deleteForcingCreatePath}'s own
 * javadoc for the proof of this same limitation on the delete side) — so it ALSO finds nothing and
 * ALSO calls {@code copyRoleAccess}, scheduling a SECOND INSERT for the IDENTICAL {@code
 * (AD_Role_ID, AD_Window_ID)} unique key. Both execute in the same JDBC batch at the eventual
 * flush; the second one violates the unique constraint — exactly the human's stack trace. This is
 * NOT a race in the "sometimes" sense — given 2+ remaining templates overlapping on a window whose
 * existing row needed clearing, it is deterministic, every time.
 *
 * <p>Empirically, this ALSO explains why the old per-remaining-template delete loop's own log
 * output (the human's server log) showed criss-crossing "widen and repoint InheritedFrom" lines
 * across many windows and three different templates before the eventual crash: the OLD
 * implementation iterated remaining templates one at a time and deleted a dependent's row
 * whenever it was not ALREADY sourced from whichever remaining template was currently being
 * examined — a blanket, per-template-pass heuristic that fires far more often than core's own
 * precedence algorithm would ever actually need an update, generating unnecessary churn on top of
 * the genuine duplicate-INSERT risk above.
 *
 * <p><b>The fix.</b> {@link #guardRemovedInheritance(RoleInheritance)} no longer deletes a
 * dependent's row once per remaining template that does not already own it. It instead computes,
 * ONCE per window across ALL remaining templates together, TWO independent things: (1) the single
 * template that becomes {@code InheritedFrom} — ALWAYS the remaining template with the
 * numerically highest {@code AD_Role_Inheritance.SeqNo} among every remaining template granting
 * that window, regardless of its own access level; and (2) the access level — most-permissive-
 * wins across EVERY remaining template granting that window, independent of which one was chosen
 * for (1). When the existing row does not already match both, it is corrected IN PLACE via {@link
 * #repointInPlace(WindowAccess, Role, Window, Role, boolean, Role)}: a bulk HQL UPDATE on the SAME
 * row (same primary key), never a delete+recreate.
 *
 * <p><b>Why {@code InheritedFrom} must track core's own SeqNo precedence, not most-permissive-
 * wins — confirmed empirically, the hard way.</b> An earlier version of this fix picked whichever
 * remaining template grants FULL access as the winner (generalizing {@link
 * #findActiveTemplateGrantingFullAccess(Role, Window)}'s own ADD-side tie-break) — reasonable-
 * looking, but WRONG: reproduced live as the identical {@code OBSecurityException: Client (0) ...
 * is not present in ClientList} this whole class exists to prevent, on a window granted by 3
 * remaining templates where the most-permissive one did NOT have the highest {@code SeqNo}.
 * Root cause: core's own {@code calculateAccesses} (unlike the ADD-side call sites) walks EVERY
 * remaining template in ONE call, ascending by {@code SeqNo}, and {@code isPrecedent} compares
 * ONLY list index (== {@code SeqNo} order) — it has no concept of access level at all. Repointing
 * to anything other than the actual highest-{@code SeqNo} grantor leaves a LOWER-index template's
 * name in {@code InheritedFrom}; core's own LATER pass over the genuinely highest-{@code SeqNo}
 * template then finds {@code isPrecedent(current, new) == true} (current's index is lower) and
 * blindly overrides the row via {@code updateRoleAccess} → {@code DalUtil.copyToTarget} — the
 * exact corrupting copy this class exists to prevent, just reached one hop later than the
 * duplicate-INSERT race above. Because the computed winner is now ALWAYS the actual highest-
 * {@code SeqNo} grantor, core's own {@code isPrecedent} check can never find a reason to touch
 * this row during its OWN recalculation — every one of core's per-template passes over it
 * resolves to {@code ACCESS_NOT_CHANGED}, so core never attempts a competing write, and neither
 * the duplicate-INSERT race above NOR this ownership-corruption variant can occur, no matter how
 * many remaining templates overlap on the same window. See {@link #repointInPlace(WindowAccess,
 * Role, Window, Role, boolean, Role)}'s own javadoc for why this method does both the ownership
 * AND most-permissive-level correction itself (no later CREATE event is left for {@link
 * #correctInheritedOwnership}/{@link #widenInheritedAccessLevelIfNeeded} to react to).
 *
 * <p><b>Scope: REMOVE-side only.</b> The three ADD-side triggers ({@link
 * #guardNewInheritance(RoleInheritance)}, {@link #guardDependentsOf(WindowAccess,
 * PropagationTrigger)}) are NOT vulnerable to this race and were deliberately left unchanged:
 * {@code applyNewInheritance}'s and
 * {@code propagateNewAccess}'s own {@code inheritanceList}/iteration are always scoped to exactly
 * ONE template (the one new inheritance, or the one template whose own access just changed) per
 * call, so at most ONE {@code copyRoleAccess} attempt is ever made for a given (dependent, window)
 * pair per event — structurally incapable of the "2+ competing passes in one calculateAccesses
 * call" mechanism above, confirmed by re-reading both call sites, not merely assumed.
 *
 * <p><b>Known residual gap, NOT closed here (documented, not silently ignored).</b> If the
 * dependent has NO existing row at all for a window 2+ remaining templates both grant (i.e. a
 * brand-new window neither the dependent nor any of its OTHER already-composed templates ever
 * granted before this specific removal), {@link #guardRemovedInheritance(RoleInheritance)} still
 * has nothing to repoint and falls back to core's own natural CREATE, which could in principle hit
 * the identical duplicate-INSERT race for that one window. Considered acceptable residual risk for
 * now: a role that has been composed for any length of time (like every real account this bug was
 * found on) will already have a materialized row for every window any of its active templates
 * grants, making this specific sub-case very unlikely in practice — see the class's own {@code
 * guardRemovedInheritance} method body for the exact guard.
 *
 * <p><b>A seventh trigger - the ADD-path fix (rounds 1-2) had its own blind spot: core cannot
 * always SEE a pre-existing, already-correct row before it propagates.</b> Live-confirmed
 * BLOCKER (ETP-4906, Task B6, 7th round, 2026-08-17), found via {@code
 * UserRoleCompositionServiceOverlapReverificationTest}. Both ADD-side triggers ({@link
 * #guardNewInheritance(RoleInheritance)}, {@link #guardDependentsOf(WindowAccess,
 * PropagationTrigger)}) originally skipped deleting a dependent's row whenever it was ALREADY
 * sourced from the SAME template that just propagated, reasoning that core's own {@code
 * handleAccess}/{@code isPrecedent} would independently reach {@code ACCESS_NOT_CHANGED} and
 * leave it alone. That reasoning silently assumed core's own {@code AccessTypeInjector#findAccess}
 * lookup CAN see the row - it cannot whenever the dependent's client is outside the CALLING
 * {@code OBContext}'s own readable-clients list (the row-level filter core's query builds is not
 * admin-mode-gated, exactly like {@link #crossClientCriteria(Class)}'s own javadoc documents for
 * OUR queries). When blind, {@code handleAccess} ALWAYS evaluates {@code access == null} and takes
 * the CREATE branch regardless of whether a correct row already exists, so ANY pre-existing row -
 * no matter how correct - risks a duplicate-INSERT the instant core's blind {@code copyRoleAccess}
 * reaches it.
 *
 * <p>The fix: {@link #clearConflictingAccessUnconditionally(Role, Window, Role)} deletes a
 * dependent's conflicting row UNCONDITIONALLY, even when already correctly sourced - forcing core
 * onto its own safe CREATE path every time, never relying on core being able to see (and thus
 * skip) an already-correct row. Safe for the {@code onSave}-triggered callers ({@link
 * #guardNewInheritance(RoleInheritance)}, and {@link #guardDependentsOf(WindowAccess,
 * PropagationTrigger)}'s {@code NEW_GRANT} case) because both feed a core propagation method with
 * a guaranteed CREATE fallback when no row is found: {@code propagateNewAccess} to {@code
 * handleAccess} to {@code copyRoleAccess}.
 *
 * <p><b>The seventh trigger's own gap, found in REVIEW - {@link #guardDependentsOf(WindowAccess,
 * PropagationTrigger)} is ALSO invoked from {@code onUpdate}, where core has NO create
 * fallback.</b> Live-confirmed BLOCKER (ETP-4906, REVIEW round, finding "[B7]", 2026-08-17 -
 * traced via static core-source analysis; the plan doc's "[B7]" section has the full trace,
 * including whether it was independently live-reproduced by the fix below). {@link
 * #guardDependentsOf(WindowAccess, PropagationTrigger)} is called from BOTH {@link
 * #onSave(EntityNewEvent)} (a template GAINS a brand-new window grant - core propagates via
 * {@code propagateNewAccess}, which DOES have the CREATE fallback above) AND {@link
 * #onUpdate(EntityUpdateEvent)} (an admin edits a template's OWN EXISTING access level - core
 * propagates via a DIFFERENT method, {@code propagateUpdatedAccess}, which has NO create fallback
 * at all: {@code findInheritedAccess} either finds a dependent's row already sourced from this
 * exact template and updates it, or does nothing). The seventh trigger's own fix made {@code
 * clearConflictingAccessUnconditionally} delete unconditionally for BOTH callers uniformly,
 * without checking that both downstream core paths actually have a create fallback - on the
 * {@code onUpdate} route, deleting a dependent's already-correctly-sourced row therefore
 * permanently loses it: core's own propagation, the one path that is supposed to reconcile the
 * level change, has no branch left that recreates a missing row. No exception, no useful log - a
 * completely routine "admin edits a template's access level in Classic" action would silently
 * delete every correctly-inheriting dependent's access to that window.
 *
 * <p>The fix: {@link #guardDependentsOf(WindowAccess, PropagationTrigger)} now takes a {@link
 * PropagationTrigger} telling it which core path its caller feeds. {@code NEW_GRANT} ({@code
 * onSave}) keeps the unconditional {@link #clearConflictingAccessUnconditionally(Role, Window,
 * Role)} behavior above - safe, since {@code propagateNewAccess} always recreates. {@code
 * UPDATED_GRANT} ({@code onUpdate}) instead calls {@link #repointIfAlreadySourcedFromTemplate(
 * Role, Window, Role, WindowAccess)}: a dependent row NOT sourced from the template whose access
 * just changed is left untouched - core's own {@code propagateUpdatedAccess} would not have
 * touched it either, exactly matching core's own scope; a row ALREADY sourced from that exact
 * template - the ONLY case {@code propagateUpdatedAccess} would act on - is corrected IN PLACE via
 * the same bulk-HQL-UPDATE technique {@link #repointInPlace(WindowAccess, Role, Window, Role,
 * boolean, Role)} already uses for the sixth trigger, rather than deleted, so the dependent's row
 * is guaranteed to survive regardless of what core's own (possibly-blind) propagation does
 * afterward.
 *
 * <p><b>[BUG-2] The seventh trigger's OWN fix had a most-permissive-wins gap of its own, found
 * by QA's final coverage pass (ETP-4906, 2026-08-18).</b> {@link #repointIfAlreadySourcedFromTemplate(
 * Role, Window, Role, WindowAccess)} — the B7 fix above — only ever compared the dependent's
 * existing row against the ONE template whose own row just changed, then copied that template's
 * new value onto the row directly. It never surveyed whether some OTHER template {@code
 * dependent} still actively inherits from also grants {@code window} full access, unlike the
 * ADD-path ({@link #widenInheritedAccessLevelIfNeeded(EntityNewEvent, WindowAccess)}) and
 * REMOVE-path ({@link #collectWindowGrantors(List)}) triggers, which both already re-derive a
 * window's level from the FULL set of currently active grantors before writing anything.
 * Empirically reproduced live (QA's throwaway JUnit probe, first attempt, no flakiness): a
 * dependent inheriting FULL access to the same window from two templates A and B, both actively
 * granting it — downgrading B's own access via a routine Etendo Classic admin edit dragged the
 * dependent's row down to read-only too, even though A still actively granted it full. The fix:
 * {@link #repointIfAlreadySourcedFromTemplate(Role, Window, Role, WindowAccess)} now surveys
 * every OTHER actively-inherited template via {@link #findActiveTemplateGrantingFullAccess(Role,
 * Window, Role)} before applying anything, and repoints {@code InheritedFrom} to whichever
 * template actually justifies the final (most-permissive) value — see that method's own javadoc
 * for the full before/after.
 */
public class WindowAccessOverlapCorruptionGuard extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(WindowAccessOverlapCorruptionGuard.class);

  /**
   * Any priority value makes Weld notify this observer before {@code
   * InheritedAccessEnabledEventHandler}, which declares none — see the class javadoc's
   * "Why {@code @Priority} matters here" section. The literal value is otherwise arbitrary.
   */
  private static final int RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS = 1;

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(WindowAccess.ENTITY_NAME),
          ModelProvider.getInstance().getEntity(RoleInheritance.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  /**
   * Defends BOTH corrupting triggers on the SAVE path — see the class javadoc's two mechanism
   * sections. A NEW {@code AD_Window_Access} row on a template ({@code propagateNewAccess}) and a
   * NEW {@code AD_Role_Inheritance} row on any role ({@code applyNewInheritance}) are the two
   * places core's own propagation can start a corrupting UPDATE against a role this class never
   * even knows is at risk; both are guarded here, before core's own (unprioritized) handlers run.
   */
  public void onSave(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof WindowAccess) {
      WindowAccess access = (WindowAccess) target;
      Role role = access.getRole();
      if (role != null && Boolean.TRUE.equals(role.isTemplate())) {
        guardDependentsOf(access, PropagationTrigger.NEW_GRANT);
      } else {
        correctInheritedOwnership(event, access);
        widenInheritedAccessLevelIfNeeded(event, access);
      }
    } else if (target instanceof RoleInheritance) {
      guardNewInheritance((RoleInheritance) target);
    }
  }

  /**
   * Defends the {@code propagateUpdatedAccess} trigger too — belt-and-braces, but with a DIFFERENT
   * safe strategy than {@link #onSave(EntityNewEvent)}'s own {@code NEW_GRANT} trigger (see the
   * class javadoc's "The seventh trigger's own gap, found in REVIEW" section, finding "[B7]"):
   * core's own {@code propagateUpdatedAccess} (triggered here) has NO create fallback — unlike
   * {@code propagateNewAccess} ({@link #onSave(EntityNewEvent)}'s own trigger), it never recreates
   * a dependent's row if none is found, it only updates one it CAN find. {@link
   * #guardDependentsOf(WindowAccess, PropagationTrigger)} is passed {@code UPDATED_GRANT}
   * specifically so it never unconditionally deletes here — see that method's own javadoc.
   * {@code RoleInheritance} has no meaningful update path to guard — core's own {@code
   * RoleInheritanceEventHandler#onUpdate} unconditionally rejects it.
   */
  public void onUpdate(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof WindowAccess) {
      guardDependentsOf((WindowAccess) target, PropagationTrigger.UPDATED_GRANT);
    }
  }

  /**
   * Defends the THIRD, REMOVE-side corruption trigger — see the class javadoc's "A third trigger,
   * on the REMOVE side" section. {@code RoleInheritance} has the only meaningful delete path to
   * guard here; {@code WindowAccess} deletes are not a corruption vector this class needs to react
   * to (a manually- or template-deleted {@code AD_Window_Access} row is a plain entity delete on
   * the OWNING role's own row, never a cross-role blind-copy write).
   */
  public void onDelete(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityDeleteEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof RoleInheritance) {
      guardRemovedInheritance((RoleInheritance) target);
    }
  }

  /**
   * Cleanup counterpart to {@link com.etendoerp.go.roles.overlap.TemplateRemovalTracker} — see
   * that class's own javadoc for why the marker it holds is deliberately NOT cleared inside
   * {@link #guardRemovedInheritance(RoleInheritance)} itself.
   * {@code TransactionCompletedEvent} fires once per transaction, on BOTH commit and rollback (see
   * {@code OBInterceptor#afterTransactionCompletion(Transaction)}, this event's own {@code @see}
   * reference) — always strictly after every flush the transaction could have triggered, so this is
   * a safe, simple point to reset the marker for the next transaction on this thread.
   */
  public void onTransactionComplete(@Observes TransactionCompletedEvent event) {
    TemplateRemovalTracker.clear();
  }

  /**
   * Corrects {@code access}'s {@code client}/{@code organization} to match its OWNING role's own
   * values, for a freshly-{@code onSave}d row that is NOT a template's own (i.e. a dependent
   * role's newly-propagated, {@code inheritedFrom != null} row — {@code copyRoleAccess}'s CREATE
   * output). Generalizes {@code UserRoleCompositionService#reconcileWindowAccessAfterComposition}'s
   * OWN ownership-pinning (currently scoped to the ONE role that class is actively composing) to
   * EVERY dependent role's freshly-created row, matching the ticket's own invariant: "client/
   * organization must always match the role's own, never a template's" — verified live: without
   * this, {@code copyRoleAccess} (see {@code RoleInheritanceManager}) copies EVERY field from the
   * template's own access row via {@code DalUtil.copy}, {@code client}/{@code organization}
   * included, and only ever corrects {@code role} afterward via {@code AccessTypeInjector#setParent}
   * — so a bystander role's brand-new inherited row silently ends up owned by the TEMPLATE's own
   * client (typically system client {@code "0"}) unless something fixes it, exactly like the row
   * {@link #deleteForcingCreatePath} just cleared away.
   *
   * <p><b>Why this MUST use {@code event.setCurrentState(Property, Object)}, not a plain {@code
   * access.setClient(...)} setter call — confirmed empirically, the hard way.</b> A first attempt
   * here called the entity's own setters directly, reasoning (correctly) that {@code
   * OBInterceptor}'s security check for a NEW entity fires synchronously, before this observer, so
   * there was no check left to out-race. That reasoning was right but incomplete: {@code
   * PersistenceEventOBInterceptor#sendNewEvent} builds {@code EntityNewEvent} from the {@code
   * Object[] state} array Hibernate itself already extracted from the entity BEFORE dispatching to
   * this listener chain — the eventual INSERT's bound values come from THAT array, not from
   * re-reading the entity's fields at execution time. A plain setter call only mutates the JAVA
   * OBJECT; it never touches Hibernate's own already-captured {@code state[]}, so the row still
   * got physically inserted with the template's own client/organization regardless of the setter
   * call (reproduced live: the "Corrected..." log line fired, confirming the setter WAS called,
   * yet the persisted row's client was still the template's). {@code
   * EntityPersistenceEvent#setCurrentState(Property, Object)} is the API specifically designed to
   * reach the bound values instead (see its own javadoc) — the SAME mechanism the class's first,
   * abandoned design already used correctly for this exact reason, just never generalized past the
   * one role it was reactively (and, for THAT use case, ineffectively) trying to fix.
   */
  private void correctInheritedOwnership(EntityNewEvent event, WindowAccess access) {
    if (access.getInheritedFrom() == null) {
      // Manually-granted row, never template-derived — ownership is whatever the grantor set,
      // not this class's business.
      return;
    }
    Role owner = access.getRole();
    if (owner == null) {
      return;
    }
    Entity waEntity = windowAccessEntity();
    Property clientProperty = waEntity.getProperty(WindowAccess.PROPERTY_CLIENT);
    Property organizationProperty = waEntity.getProperty(WindowAccess.PROPERTY_ORGANIZATION);

    boolean clientWrong = owner.getClient() != null
        && !sameId(owner.getClient(), event.getCurrentState(clientProperty));
    boolean organizationWrong = owner.getOrganization() != null
        && !sameId(owner.getOrganization(), event.getCurrentState(organizationProperty));
    if (!clientWrong && !organizationWrong) {
      return;
    }
    if (clientWrong) {
      event.setCurrentState(clientProperty, owner.getClient());
    }
    if (organizationWrong) {
      event.setCurrentState(organizationProperty, owner.getOrganization());
    }
    log.info(
        "Corrected AD_Window_Access ownership on role {} window {}: pinned client/organization "
            + "back to the role's own (template-derived row, inherited from {})",
        owner.getId(), access.getWindow() != null ? access.getWindow().getId() : null,
        access.getInheritedFrom().getId());
  }

  private static Entity windowAccessEntity() {
    return ModelProvider.getInstance().getEntity(WindowAccess.ENTITY_NAME);
  }

  /**
   * The FOURTH corruption trigger — see the class javadoc's "A fourth trigger: most-permissive-
   * wins is not enforced outside {@code UserRoleCompositionService}" section. For the SAME
   * freshly-created inherited row {@link #correctInheritedOwnership} just fixed ownership on,
   * widens its access level to full whenever ANY OTHER template {@code access.getRole()} is
   * currently, actively inheriting from ALSO grants the same window full ("&#x2713;") access —
   * mirroring {@code UserRoleCompositionService#reconcileWindowAccessAfterComposition}'s own
   * most-permissive-wins union, just applied to any newly-created inherited row instead of only
   * ones created via {@code assignTemplateRoles}. Never narrows (matches the existing rule: a
   * full grant, once resolved, always wins) — this method only ever flips {@code false} to
   * {@code true}, never the other way.
   */
  private void widenInheritedAccessLevelIfNeeded(EntityNewEvent event, WindowAccess access) {
    if (access.getInheritedFrom() == null) {
      // Manually-granted row, never template-derived — not this class's business, same guard
      // correctInheritedOwnership already applies.
      return;
    }
    Role owner = access.getRole();
    Window window = access.getWindow();
    if (owner == null || window == null) {
      return;
    }
    Entity waEntity = windowAccessEntity();
    Property editableFieldProperty = waEntity.getProperty(WindowAccess.PROPERTY_EDITABLEFIELD);
    if (Boolean.TRUE.equals(event.getCurrentState(editableFieldProperty))) {
      // Already the most permissive value possible — nothing to widen. Note this does NOT mean
      // InheritedFrom needs checking here too: whichever template CREATE sourced this row from
      // already grants full access itself (that is the only way editableField could already be
      // true), so InheritedFrom already names a template that genuinely justifies the current
      // value — no correction needed. See the class javadoc's "InheritedFrom bookkeeping" section
      // for why that stops being true once this method actually widens the value below.
      return;
    }
    Role justifyingTemplate = findActiveTemplateGrantingFullAccess(owner, window);
    if (justifyingTemplate == null) {
      return;
    }
    event.setCurrentState(editableFieldProperty, true);
    // Repoint InheritedFrom to the template that actually justifies the now-widened value — see
    // the class javadoc's "InheritedFrom bookkeeping" section for why leaving it pointed at the
    // originally CREATE-sourced template (which does NOT grant full access — that is exactly why
    // widening was needed) breaks a later removal of the justifying template.
    Property inheritedFromProperty = waEntity.getProperty(WindowAccess.PROPERTY_INHERITEDFROM);
    Role originalSource = access.getInheritedFrom();
    event.setCurrentState(inheritedFromProperty, justifyingTemplate);
    log.info(
        "Widened AD_Window_Access on role {} window {} to full and repointed InheritedFrom from "
            + "{} to {}: another currently-inherited template already grants this window full "
            + "access (most-permissive-wins, mirrors "
            + "UserRoleCompositionService#reconcileWindowAccessAfterComposition, applied outside "
            + "assignTemplateRoles). Repointing keeps InheritedFrom an accurate 'who is currently "
            + "responsible for this row's value' so a LATER removal of {}'s inheritance correctly "
            + "re-triggers re-derivation instead of leaving the row stuck at full.",
        owner.getId(), window.getId(), originalSource.getId(), justifyingTemplate.getId(),
        justifyingTemplate.getId());
  }

  /**
   * The single OTHER template {@code dependent} is currently, actively inheriting from that
   * grants {@code window} full ("&#x2713;") access — read fresh from each template's own {@code
   * AD_Window_Access} rows, mirroring {@code UserRoleCompositionService#mostPermissiveWindowAccess}
   * (same source-of-truth choice: the templates' own current grants, not whatever single row
   * core's per-window propagation happened to leave behind). Returns {@code null} if none does.
   *
   * <p><b>Tie-break when 2+ OTHER active templates both grant full access.</b> {@link
   * #findActiveTemplatesFor(Role, String)} returns templates ordered by their {@code
   * AD_Role_Inheritance.SeqNo} DESCENDING, so the first match this method finds is the
   * highest-sequence-number one — deliberately mirroring the exact tie-break core's own {@code
   * RoleInheritanceManager#propagateDeletedAccess} already uses when it has to pick ONE surviving
   * template to re-source a row from after a removal ("retrieve the list of templates, ordered by
   * sequence number descending, to update the access with the first one available"). Picking the
   * SAME tie-break core itself uses means this method's choice of "the" justifying template stays
   * consistent with whatever core would independently re-derive if the row were deleted and
   * recreated from scratch — not a novel rule invented for this method.
   *
   * <p>Also reused by {@link #repointIfAlreadySourcedFromTemplate(Role, Window, Role,
   * WindowAccess)} (the BUG-2 fix, ETP-4906 QA final coverage pass, 2026-08-18) via the {@code
   * excludedTemplate} overload below, so the {@code onUpdate}/{@code UPDATED_GRANT} trigger
   * surveys every OTHER actively-inherited template before applying a downgrade — the same
   * most-permissive-wins pattern this method already gives the ADD-path trigger.
   */
  private Role findActiveTemplateGrantingFullAccess(Role dependent, Window window) {
    return findActiveTemplateGrantingFullAccess(dependent, window, null);
  }

  /**
   * Overload of {@link #findActiveTemplateGrantingFullAccess(Role, Window)} that skips one
   * specific template — used by {@link #repointIfAlreadySourcedFromTemplate(Role, Window, Role,
   * WindowAccess)} to survey every OTHER actively-inherited template ({@code excludedTemplate} is
   * the one whose own row just changed and is being handled separately, from the caller's
   * already-updated in-memory value). Same SeqNo-descending tie-break as the no-exclusion
   * overload — see that method's own javadoc.
   */
  private Role findActiveTemplateGrantingFullAccess(Role dependent, Window window,
      Role excludedTemplate) {
    for (Role template : findActiveTemplatesFor(dependent, null)) {
      if (excludedTemplate != null && sameId(template, excludedTemplate)) {
        continue;
      }
      WindowAccess templateAccess = findActiveWindowAccess(template, window);
      if (templateAccess != null && Boolean.TRUE.equals(templateAccess.isEditableField())) {
        return template;
      }
    }
    return null;
  }

  /**
   * If {@code inheritance} points a role at a template, proactively clears that role's OWN
   * conflicting active access for every window the NEW template also grants — the same mechanism
   * as {@link UserRoleCompositionService#preventWindowAccessOverlapCorruption(Role, Role)}, just
   * running for ANY role gaining ANY new inheritance, not only ones routed through {@code
   * assignTemplateRoles}. See the class javadoc's "A second, symmetric trigger" section.
   */
  private void guardNewInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role template = inheritance.getInheritFrom();
    if (dependent == null || template == null || !Boolean.TRUE.equals(template.isTemplate())) {
      return;
    }
    for (WindowAccess templateGrant : findActiveWindowAccess(template)) {
      Window window = templateGrant.getWindow();
      if (window == null) {
        continue;
      }
      clearConflictingAccessUnconditionally(dependent, window, template);
    }
  }

  /**
   * If {@code inheritance} points a role away from a template it is about to stop inheriting from,
   * proactively clears that role's OWN conflicting active access for every window ANY OTHER
   * template it still actively inherits from also grants — the same mechanism as {@link
   * #guardNewInheritance(RoleInheritance)}, just triggered by the OPPOSITE event and iterating
   * every REMAINING template instead of the one NEW one, because {@code
   * RoleInheritanceManager#applyRemoveInheritance} recalculates against ALL of them, not just one.
   * See the class javadoc's "A third trigger, on the REMOVE side" section.
   *
   * <p>Deliberately does NOT special-case "only rows currently sourced from the template being
   * removed": for a remaining template's window, if the dependent's existing row is not already
   * sourced from THAT remaining template, core's own {@code isPrecedent} will treat it as needing
   * override regardless of what it is currently sourced from (manually granted, or inherited from
   * a DIFFERENT still-active template). (Historical note: as of round 7, neither {@link
   * #guardNewInheritance(RoleInheritance)} nor {@link #guardDependentsOf(WindowAccess,
   * PropagationTrigger)}'s {@code NEW_GRANT} case skip an already-correctly-sourced row either any
   * more — see the class javadoc's "A seventh trigger" section — so this method's own choice not
   * to special-case is now consistent with both of those, not merely "sufficient and correct on
   * its own" as it was originally written; {@code UPDATED_GRANT}'s {@link
   * #repointIfAlreadySourcedFromTemplate(Role, Window, Role, WindowAccess)} is the one exception,
   * and deliberately so — see its own javadoc.)
   */
  private void guardRemovedInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role removedTemplate = inheritance.getInheritFrom();
    if (dependent == null || removedTemplate == null) {
      return;
    }
    // Marks removedTemplate as "being removed" for the REST of this transaction, not just this
    // method call — see TemplateRemovalTracker's own javadoc for why the marker must outlive
    // this method's own stack frame (a race this class hit empirically, see that javadoc).
    TemplateRemovalTracker.markRemoved(removedTemplate.getId());

    // ONE winner per window, computed ONCE across ALL remaining templates — see the class
    // javadoc's "A sixth trigger" section for why this replaces the old per-remaining-template
    // delete loop, and its "Why InheritedFrom must track core's own SeqNo precedence" paragraph
    // for why the winner (InheritedFrom target) and the level (most-permissive-wins) are computed
    // SEPARATELY, not both from the same "prefer full access" pick — see {@link
    // #collectWindowGrantors(List)} and {@link #repointWindowIfNeeded(Role, Window,
    // WindowGrantors)} for the two halves of that computation.
    List<Role> remainingTemplates = findOtherActiveTemplates(dependent, inheritance);
    WindowGrantors grantors = collectWindowGrantors(remainingTemplates);

    boolean anyCorrected = false;
    for (Window window : grantors.windowsById.values()) {
      anyCorrected |= repointWindowIfNeeded(dependent, window, grantors);
    }

    if (anyCorrected) {
      // Mirrors deleteForcingCreatePath's own OBDal.refresh(dependent) call — see that method's
      // own javadoc for why a plain collection-remove is not enough on its own. Empirically
      // required here too (ETP-4906, Task B6, 6th round): without it, the CALLER's own pending
      // deletion of `inheritance` (still in-flight in the SAME flush that invoked this observer)
      // can hit Hibernate's "deleted object would be re-saved by cascade" check — `dependent`'s
      // own AD_Role_Inheritance collection, if already loaded/cached in this session, still holds
      // a stale in-memory reference to the about-to-be-deleted row until something forces it to
      // reload; refreshing `dependent` here (a Role we already loaded and are actively mutating
      // access for) is the same safe, already-proven lever, just applied once per removal instead
      // of once per corrected window.
      OBDal.getInstance().refresh(dependent);
    }
  }

  /**
   * Per-window candidates {@link #collectWindowGrantors(List)} builds and {@link
   * #repointWindowIfNeeded(Role, Window, WindowGrantors)} feeds into {@link
   * OverlapReconciliationCore#computeWinner(List)} — extracted purely to keep {@link
   * #guardRemovedInheritance(RoleInheritance)}'s own cognitive complexity and per-loop
   * break/continue count within the SonarQube gate; carries no behavior of its own. The
   * winner/level decision itself now lives in {@link OverlapReconciliationCore}, shared with
   * {@code ProcessAccessOverlapCorruptionGuard} (ETP-4830 item 7) — this class only builds the
   * per-window {@link GrantCandidate} lists (in the SAME SeqNo-descending order {@link
   * #findActiveTemplatesFor(Role, String)} already returns) and resolves the winner's {@code
   * templateId} back to a {@code Role}.
   */
  private static final class WindowGrantors {
    private final Map<String, Window> windowsById = new LinkedHashMap<>();
    private final Map<String, Role> templatesById = new LinkedHashMap<>();
    private final Map<String, List<GrantCandidate>> candidatesByWindowId = new LinkedHashMap<>();
  }

  /**
   * Builds, across ALL of {@code remainingTemplates} in one pass, the per-window {@link
   * GrantCandidate} lists {@link #repointWindowIfNeeded(Role, Window, WindowGrantors)} feeds
   * into {@link OverlapReconciliationCore#computeWinner(List)}. {@code remainingTemplates} is
   * already SeqNo DESCENDING ({@link #findActiveTemplatesFor(Role, String)}), and that relative
   * order is preserved per window here — {@code computeWinner} relies on the first candidate in
   * each window's list being the highest-SeqNo remaining grantor.
   */
  private WindowGrantors collectWindowGrantors(List<Role> remainingTemplates) {
    WindowGrantors grantors = new WindowGrantors();
    for (Role remainingTemplate : remainingTemplates) {
      grantors.templatesById.putIfAbsent(remainingTemplate.getId(), remainingTemplate);
      for (WindowAccess templateGrant : findActiveWindowAccess(remainingTemplate)) {
        Window window = templateGrant.getWindow();
        if (window == null) {
          continue;
        }
        grantors.windowsById.putIfAbsent(window.getId(), window);
        grantors.candidatesByWindowId
            .computeIfAbsent(window.getId(), key -> new ArrayList<>())
            .add(new GrantCandidate(remainingTemplate.getId(),
                Boolean.TRUE.equals(templateGrant.isEditableField())));
      }
    }
    return grantors;
  }

  /**
   * Corrects {@code dependent}'s existing row for {@code window} in place when it does not
   * already match {@link OverlapReconciliationCore#computeWinner(List)}'s verdict for {@code
   * grantors.candidatesByWindowId.get(windowId)}; returns whether a correction was made (so the
   * caller knows whether {@code dependent} needs refreshing afterward). See {@code
   * OverlapReconciliationCore}'s own javadoc, and the class javadoc's "A sixth trigger" section
   * and "Why InheritedFrom must track core's own SeqNo precedence" paragraph, for why the winner
   * (WHO becomes {@code InheritedFrom}) and the level (HOW MUCH access) are two independent
   * decisions computed together there.
   */
  private boolean repointWindowIfNeeded(Role dependent, Window window, WindowGrantors grantors) {
    String windowId = window.getId();
    OverlapWinner winner = OverlapReconciliationCore.computeWinner(
        grantors.candidatesByWindowId.get(windowId));
    if (winner == null) {
      return false;
    }
    Role winnerRole = grantors.templatesById.get(winner.getWinnerTemplateId());

    WindowAccess existing = findActiveWindowAccess(dependent, window);
    if (existing == null) {
      // No existing row to correct in place — same residual, pre-existing, theoretical gap as
      // before this refactor. See the class javadoc's "A sixth trigger" section.
      return false;
    }
    Role existingSource = existing.getInheritedFrom();
    boolean sourceCorrect = existingSource != null && sameId(existingSource, winnerRole);
    boolean levelCorrect = Boolean.valueOf(winner.isWinnerLevel()).equals(existing.isEditableField());
    if (sourceCorrect && levelCorrect) {
      return false;
    }
    repointInPlace(existing, dependent, window, winnerRole, winner.isWinnerLevel(), existingSource);
    return true;
  }

  /**
   * Returns every OTHER template {@code dependent} still actively inherits from, excluding {@code
   * excludedInheritance} (the {@code RoleInheritance} row about to be deleted) by id — mirrors
   * core's own {@code RoleInheritanceManager#getUpdatedRoleInheritancesList(RoleInheritance,
   * boolean)}, which excludes the same way rather than relying on the row's DB-visible state (the
   * row may still be physically present at this point in the flush).
   */
  private List<Role> findOtherActiveTemplates(Role dependent, RoleInheritance excludedInheritance) {
    return findActiveTemplatesFor(dependent, excludedInheritance.getId());
  }

  /**
   * Every ACTIVE template {@code dependent} currently inherits from, optionally excluding one
   * {@code AD_Role_Inheritance} row by id (pass {@code null} to include all of them). Shared by
   * {@link #findOtherActiveTemplates(Role, RoleInheritance)} (REMOVE-path: exclude the
   * about-to-be-deleted row; order does not matter there — every remaining template is processed
   * regardless of order) and {@link #findActiveTemplateGrantingFullAccess(Role, Window)}
   * (level-widening: no exclusion; order DOES matter there — see that method's own javadoc for the
   * tie-break this ordering exists to support) — same query pattern {@code
   * UserRoleCompositionService#reconcileWindowAccessAfterComposition} relies on for its own
   * most-permissive-wins resolution, reused rather than re-derived. Excludes by id, not by
   * DB-visible state, mirroring core's own {@code
   * RoleInheritanceManager#getUpdatedRoleInheritancesList(RoleInheritance, boolean)} (the row may
   * still be physically present at this point in the flush). Ordered by {@code
   * AD_Role_Inheritance.SeqNo} DESCENDING — mirrors core's own {@code
   * RoleInheritanceManager#getRoleInheritancesList(Role, Role, boolean)} call from {@code
   * propagateDeletedAccess} (also descending), which is itself the tie-break authority {@link
   * #findActiveTemplateGrantingFullAccess(Role, Window)} deliberately reuses.
   *
   * <p><b>ALSO excludes every template {@link com.etendoerp.go.roles.overlap.TemplateRemovalTracker
   * #isBeingRemoved(String)} currently reports</b> — see that class's own javadoc for the exact
   * race this closes (a template's {@code RoleInheritance} row is still
   * DB-visible as {@code active=true} here, mid-flush, even though it is being deleted in the SAME
   * flush this query runs in). {@code excludedInheritanceId} alone is not enough for THIS
   * exclusion: that parameter excludes one specific {@code AD_Role_Inheritance} row by id, known
   * only to {@link #findOtherActiveTemplates(Role, RoleInheritance)}'s own REMOVE-path caller;
   * {@link #findActiveTemplateGrantingFullAccess(Role, Window)} has no such id available (it is
   * reached from a completely different event — an unrelated {@code AD_Window_Access} CREATE —
   * that carries no reference back to whichever {@code RoleInheritance} deletion, if any, is
   * concurrently in-flight in the same transaction).
   */
  private List<Role> findActiveTemplatesFor(Role dependent, String excludedInheritanceId) {
    return ActiveTemplateInheritance.findActiveTemplatesFor(dependent, excludedInheritanceId);
  }

  /**
   * Which core propagation method will run AFTER {@link #guardDependentsOf(WindowAccess,
   * PropagationTrigger)} returns, for the SAME {@code AD_Window_Access} event — determines whether
   * it is safe to unconditionally delete a dependent's conflicting row. See the class javadoc's
   * "The seventh trigger's own gap, found in REVIEW" section (finding "[B7]") for the full
   * root-cause write-up.
   */
  private enum PropagationTrigger {
    /**
     * Fed by {@link #onSave(EntityNewEvent)} — a template GAINED a brand-new window grant. Core
     * propagates via {@code RoleInheritanceManager#propagateNewAccess}, which ALWAYS falls back to
     * {@code copyRoleAccess} (a CREATE) when it finds no existing row for a dependent — so
     * unconditionally deleting a dependent's conflicting row first is always safe here.
     */
    NEW_GRANT,
    /**
     * Fed by {@link #onUpdate(EntityUpdateEvent)} — a template's OWN EXISTING window grant had its
     * access level changed. Core propagates via {@code RoleInheritanceManager
     * #propagateUpdatedAccess}, which has NO create fallback: it only ever UPDATEs a dependent's
     * row it can find via {@code findInheritedAccess}, and silently does nothing otherwise.
     * Unconditionally deleting here would permanently lose the dependent's access with nothing
     * left to restore it.
     */
    UPDATED_GRANT
  }

  /**
   * If {@code templateAccess} belongs to a template role, proactively reconciles every OTHER
   * role's conflicting active access for the same window BEFORE returning control to core's own
   * propagation (which fires next, via the SAME CDI event, per this class's {@code @Priority}) —
   * using a DIFFERENT strategy depending on {@code trigger}, since the two core propagation
   * methods this guards behave asymmetrically (see {@link PropagationTrigger}'s own javadoc and
   * the class javadoc's "[B7]" section).
   */
  private void guardDependentsOf(WindowAccess templateAccess, PropagationTrigger trigger) {
    Role role = templateAccess.getRole();
    if (role == null || !Boolean.TRUE.equals(role.isTemplate())) {
      // Not a template's own row — nothing to propagate, core will not react to this one either.
      return;
    }
    Window window = templateAccess.getWindow();
    if (window == null) {
      return;
    }
    for (Role dependent : findActiveDependentRoles(role)) {
      if (trigger == PropagationTrigger.NEW_GRANT) {
        clearConflictingAccessUnconditionally(dependent, window, role);
      } else {
        repointIfAlreadySourcedFromTemplate(dependent, window, role, templateAccess);
      }
    }
  }

  /**
   * The {@code onUpdate}/{@code UPDATED_GRANT} counterpart to {@link
   * #clearConflictingAccessUnconditionally(Role, Window, Role)} — see {@link PropagationTrigger}'s
   * own javadoc and the class javadoc's "[B7]" section for why deleting is NOT safe on this
   * trigger. Restricted to the only case core's own {@code propagateUpdatedAccess} actually acts
   * on: {@code dependent}'s existing row for {@code window} is ALREADY sourced from {@code
   * grantingTemplate}. For that case, corrects the row's {@code editableField} IN PLACE to match
   * {@code templateAccess}'s own (already-updated, pre-flush) value directly — via the same
   * bulk-HQL-UPDATE technique {@link #repointInPlace(WindowAccess, Role, Window, Role, boolean,
   * Role)} already uses for the sixth trigger — rather than deleting it, so the dependent's row is
   * guaranteed to survive regardless of whether core's own {@code propagateUpdatedAccess} manages
   * to find and update it afterward.
   *
   * <p>A row NOT sourced from {@code grantingTemplate} (manually granted, or sourced from a
   * DIFFERENT template) is left entirely untouched: core's own {@code findInheritedAccess} only
   * ever matches a dependent's row already sourced from the SAME template whose grant just
   * changed, so it would not have acted on this row either — nothing to prevent, nothing to
   * correct.
   *
   * <p><b>[BUG-2 fix, ETP-4906 QA final coverage pass, 2026-08-18] Never blindly trusts {@code
   * grantingTemplate}'s new value in isolation.</b> The original version of this method copied
   * {@code templateAccess}'s new {@code editableField} onto the dependent's row unconditionally
   * whenever the row was sourced from {@code grantingTemplate} — silently violating
   * most-permissive-wins whenever {@code dependent} ALSO actively inherits from a DIFFERENT
   * template that still grants {@code window} full access: downgrading {@code grantingTemplate}'s
   * own access (a routine Etendo Classic admin action) would drag the dependent's row down too,
   * even though the other template still justified full access. Empirically reproduced live (QA's
   * throwaway probe, see the plan doc's "QA Findings — Final Coverage Pass" section) — first
   * attempt, no flakiness. Fixed by surveying every OTHER actively-inherited template via {@link
   * #findActiveTemplateGrantingFullAccess(Role, Window, Role)} BEFORE applying anything — the
   * exact same most-permissive-wins survey {@link #widenInheritedAccessLevelIfNeeded(EntityNewEvent,
   * WindowAccess)} (ADD path) and {@link #collectWindowGrantors(List)} (REMOVE path) already run,
   * just applied to the one trigger that skipped it. The final level is the MAX of {@code
   * grantingTemplate}'s own new value and every other active grantor's current value; {@code
   * InheritedFrom} is repointed to whichever template actually justifies that final value —
   * {@code grantingTemplate} itself when its own new value already suffices, otherwise the
   * OTHER still-active full grantor — mirroring {@code widenInheritedAccessLevelIfNeeded}'s own
   * "repoint to whoever actually justifies the value" rule so a LATER removal of either template
   * correctly re-triggers re-derivation instead of leaving the row pointed at a template that no
   * longer backs its own value.
   */
  private void repointIfAlreadySourcedFromTemplate(Role dependent, Window window,
      Role grantingTemplate, WindowAccess templateAccess) {
    WindowAccess existing = findActiveWindowAccess(dependent, window);
    if (existing == null) {
      // No existing row — core's own propagateUpdatedAccess would find nothing here either and
      // do nothing; consistent, nothing for this mechanism to do.
      return;
    }
    Role existingSource = existing.getInheritedFrom();
    if (existingSource == null || !sameId(existingSource, grantingTemplate)) {
      // Not sourced from THIS template — out of scope for core's own propagateUpdatedAccess too,
      // see this method's own javadoc.
      return;
    }
    // grantingTemplate's own NEW value is read directly from the caller's already-updated
    // (pre-flush, in-memory) WindowAccess entity, not re-queried — see this method's own javadoc.
    boolean grantingTemplateNewLevel = Boolean.TRUE.equals(templateAccess.isEditableField());
    // Survey every OTHER actively-inherited template before trusting grantingTemplate alone —
    // the BUG-2 fix. excludedTemplate=grantingTemplate: its own value is already known above.
    Role otherJustifyingTemplate =
        grantingTemplateNewLevel ? null
            : findActiveTemplateGrantingFullAccess(dependent, window, grantingTemplate);

    boolean finalLevel = grantingTemplateNewLevel || otherJustifyingTemplate != null;
    // otherJustifyingTemplate is only ever non-null when grantingTemplateNewLevel is false (see
    // above), so this already covers both cases: grantingTemplate's own new value still suffices,
    // or some other active template is the one that now justifies the final value.
    Role winner = otherJustifyingTemplate != null ? otherJustifyingTemplate : grantingTemplate;

    boolean sourceCorrect = sameId(existingSource, winner);
    boolean levelCorrect = Boolean.valueOf(finalLevel).equals(existing.isEditableField());
    if (sourceCorrect && levelCorrect) {
      // Already matches the correctly-surveyed value/source — nothing to correct.
      return;
    }
    repointInPlace(existing, dependent, window, winner, finalLevel, existingSource);
  }

  /**
   * Shared by both {@code NEW_GRANT}-safe ADD-side triggers ({@link
   * #guardDependentsOf(WindowAccess, PropagationTrigger)}'s {@code NEW_GRANT} case and {@link
   * #guardNewInheritance(RoleInheritance)}) — NOT called for {@link
   * #guardDependentsOf(WindowAccess, PropagationTrigger)}'s {@code UPDATED_GRANT} case, which uses
   * {@link #repointIfAlreadySourcedFromTemplate(Role, Window, Role, WindowAccess)} instead (see
   * that method's own javadoc and {@link PropagationTrigger}'s javadoc for why). If {@code
   * dependent} has an active {@code AD_Window_Access} row for {@code window}, deletes it via
   * {@link #deleteForcingCreatePath} UNCONDITIONALLY — even when the row is ALREADY correctly
   * sourced from {@code grantingTemplate}. See the class javadoc's "A seventh trigger" section for
   * the full root-cause write-up; summarized here.
   *
   * <p><b>Why "already correct" is no longer a reason to skip — found empirically (ETP-4906,
   * Task B6, 7th round, 2026-08-17).</b> Both prior ADD-side implementations (rounds 1-2) treated
   * "the existing row is already sourced from the SAME template that just gained the grant" as a
   * safe no-op, reasoning that core's own {@code RoleInheritanceManager#handleAccess}/{@code
   * isPrecedent} would independently reach the identical conclusion (its own {@code
   * ACCESS_NOT_CHANGED} branch) and leave the row alone. That reasoning assumed core's own {@code
   * getAccess()}/{@code AccessTypeInjector#findAccess} lookup can actually SEE the row — it
   * cannot, whenever the dependent's client is not in the ambient {@code OBContext}'s own
   * readable-clients list: {@code findAccess}'s generated query filters by {@code AD_Client_ID in
   * (...)} using the CALLING context's readable clients (confirmed via SQL trace: a role
   * belonging to a tenant client not in that list is invisible to this query, full stop — the
   * row-level filter is not admin-mode-gated, exactly like {@link #crossClientCriteria(Class)}'s
   * own javadoc already documents for OUR OWN queries). When blind, {@code handleAccess} ALWAYS
   * evaluates {@code access == null} and takes the CREATE branch — REGARDLESS of whether a
   * correctly-sourced row already exists — so ANY pre-existing row for that (role, window), no
   * matter how correct, is a duplicate-INSERT collision waiting to happen the instant core's own
   * propagation reaches it. Live-reproduced: {@code
   * UserRoleCompositionServiceOverlapReverificationTest#testRealMatrixOverlapSalesAndPurchasingOnProductCategoryStaysReadOnly}
   * — a bystander role ({@code F238CDA0}, "Personal – CompositionUser") already had a correctly-
   * sourced, correctly-leveled row for the template's newly-granted window; the OLD "skip when
   * already correct" branch left it in place; core's own blind {@code copyRoleAccess} then tried
   * to INSERT a second row for the identical {@code (AD_Role_ID, AD_Window_ID)} key and crashed
   * with the same {@code ad_window_access_un_key} violation this whole class exists to prevent.
   *
   * <p><b>Why deletion (not {@link #repointInPlace}) is the correct lever here — a DIFFERENT
   * conclusion from {@link #guardRemovedInheritance(RoleInheritance)}'s own "never delete, always
   * repoint in place" rule, for a DIFFERENTLY-SHAPED bug.</b> {@code guardRemovedInheritance}'s
   * duplicate-INSERT race (the "sixth trigger") is about core's {@code calculateAccesses} walking
   * 2+ REMAINING TEMPLATES in ONE call with no flush between passes — repointing in place removes
   * the underlying trigger entirely, because core's OWN {@code isPrecedent} check, once it can
   * see a row sourced from the highest-{@code SeqNo} template, correctly resolves to
   * ACCESS_NOT_CHANGED and never attempts a competing write. That reasoning requires core to be
   * ABLE to see the row. Here, core's blindness is the root cause itself — repointing a row's
   * fields in place changes nothing about whether the row PHYSICALLY EXISTS, so core's blind
   * {@code copyRoleAccess} would still attempt an INSERT against the identical key regardless of
   * what values the surviving row holds. The ONLY way to guarantee core's own (blind) CREATE
   * lands on an empty slot is to ensure no row exists at all before returning control to it —
   * exactly what {@link #deleteForcingCreatePath} already does for the "needs correction" case.
   * Applying it unconditionally simply closes the gap left by the old "already correct" shortcut.
   *
   * <p>Trades a small amount of churn (a correctly-sourced row is deleted and recreated with a
   * fresh id/audit columns instead of being left untouched) for guaranteed safety — acceptable
   * given the alternative is a 500 error on the real {@code SFAssignUserRoles} webhook. The
   * recreated row is corrected right back to the exact same values by {@link
   * #correctInheritedOwnership(EntityNewEvent, WindowAccess)}/{@link
   * #widenInheritedAccessLevelIfNeeded(EntityNewEvent, WindowAccess)}, which already run on
   * EVERY freshly-created inherited row regardless of how it was triggered.
   */
  private void clearConflictingAccessUnconditionally(Role dependent, Window window,
      Role grantingTemplate) {
    WindowAccess existing = findActiveWindowAccess(dependent, window);
    if (existing == null) {
      // No conflicting row at all — core will safely CREATE one, nothing to prevent.
      return;
    }
    deleteForcingCreatePath(existing, dependent, window, grantingTemplate,
        existing.getInheritedFrom());
  }

  /**
   * Removes {@code existing} — the dependent role's OWN conflicting row — so core's subsequent
   * {@code handleAccess}/{@code findInheritedAccess} lookup for (role={@code dependent}, window=
   * {@code window}) finds nothing and takes the CREATE path instead of the corrupting UPDATE path.
   * Same GOAL as {@code UserRoleCompositionService#preventWindowAccessOverlapCorruption}, but a
   * DIFFERENT mechanism was required to reach it from this nested position — see below.
   *
   * <p><b>Why this is a direct bulk HQL {@code DELETE}, not {@code OBDal.remove()} +
   * {@code OBDal.flush()} (what {@code preventWindowAccessOverlapCorruption} itself safely does).
   * </b> This method runs NESTED inside a Hibernate {@code Session.flush()} that is already in
   * progress (this class's {@code onSave}/{@code onUpdate} fire from mid-flush — see the class
   * javadoc), and Openbravo's DAL layer sets every session's flush mode to {@code
   * FlushMode.COMMIT} (see {@code SessionHandler#setDefaultFlushMode}/its call site) — meaning
   * queries NEVER auto-flush pending entity-level changes, unlike the {@code FlushMode.AUTO}
   * default most Hibernate apps run under. Two consequences, both confirmed empirically while
   * verifying this redesign:
   * <ol>
   *   <li>{@code OBDal.remove(existing)} WITHOUT an explicit {@code flush()} is invisible to
   *   core's subsequent {@code findAccess}/{@code getAccessList} HQL queries — {@code
   *   FlushMode.COMMIT} means nothing auto-flushes before them. Core's query still finds the
   *   "existing" row, so {@code handleAccess} never reaches the CREATE path at all — worse, when
   *   the OUTER flush eventually executes OUR scheduled delete, the row is gone with NOTHING core
   *   ever created to replace it (reproduced live: every window this method needed to clear ended
   *   up simply absent from the dependent role afterward, confirmed against the actual DB rows).
   *   </li>
   *   <li>Calling {@code OBDal.flush()} explicitly from here to force visibility is a REENTRANT
   *   {@code Session.flush()} call — one flush() invoked from inside another, still-in-progress
   *   one on the SAME session. This corrupts the OUTER flush's own in-progress action-queue
   *   bookkeeping: reproduced live as a {@code StaleStateException} ("actual row count: 0;
   *   expected: 1") on an UPDATE for a row that demonstrably still existed moments earlier in the
   *   very same flush cycle.</li>
   * </ol>
   * A direct {@code session.createQuery("delete from " + WindowAccess.ENTITY_NAME + " ...")
   * .executeUpdate()} sidesteps BOTH: a bulk HQL DML statement is executed as a single SQL
   * statement immediately, on the current connection/transaction — it does not go through the
   * session's flush/action-queue machinery at all (no {@code EntityDeleteEvent} fires either, so
   * core's own {@code InheritedAccessEnabledEventHandler}'s "NotDeleteInheritedAccess" delete
   * check — which exists to protect exactly this {@code inheritedFrom != null} case from a
   * NORMAL entity-level delete — never runs, and does not need to: we WANT this specific case
   * deleted regardless of {@code inheritedFrom}). The row is gone from the DB immediately,
   * visible to any subsequent SELECT on the same transaction (including core's), with no
   * reentrant flush anywhere.
   *
   * <p><b>Also refreshes {@code dependent} (the OWNING role) — {@code OBDal.refresh}, NOT {@code
   * evict}.</b> {@code dependent.getADWindowAccessList()} is frequently ALREADY loaded and cached
   * in this session by the time this method runs — typically because an EARLIER, separate
   * top-level flush already force-initialized it (core's own {@code
   * WindowAccessInjector#setParent} calls {@code role.getADWindowAccessList().add(...)} for every
   * row it ever creates, which lazily loads the collection the first time). That cached Java list
   * still holds a reference to {@code existing} even after the bulk delete above — a raw SQL
   * statement does not know or care about Hibernate's separate collection-snapshot bookkeeping.
   * THREE outcomes were tried empirically here, in order:
   * <ol>
   *   <li>Leave the stale reference alone: the next time core touches this SAME collection (e.g.
   *   appending its own newly-created replacement row for a different, non-conflicting window),
   *   Hibernate re-examines the whole collection and finds a member with no valid snapshot —
   *   {@code OBInterceptor} logs "detected as not new... but it does not have a current state in
   *   the database" and still schedules an UPDATE for it, which then fails with {@code
   *   StaleStateException} ("actual row count: 0") once the row's absence surfaces at SQL
   *   execution time.</li>
   *   <li>Remove {@code existing} from the collection explicitly: Hibernate's own orphan-removal
   *   cascade (this collection mapping cascades deletes) detects the missing element against its
   *   loaded snapshot and schedules its OWN {@code session.delete()} for it — which DOES run
   *   through {@code OBInterceptor.onDelete}/{@code SecurityChecker.checkDeleteAllowed},
   *   reproduced live as the exact {@code OBSecurityException} this whole class exists to prevent
   *   in the first place, just relocated from an update to a delete.</li>
   *   <li>Fully {@code evict(dependent)} (detach the whole entity, not just the collection):
   *   avoids both of the above, but ALSO strips {@code dependent} of its live Hibernate session —
   *   the very next time core's {@code WindowAccessInjector#setParent} calls {@code
   *   role.getADWindowAccessList().add(...)} to register ITS OWN newly-created replacement row,
   *   the (now fully detached) collection cannot lazily re-initialize itself at all, reproduced
   *   live as {@code LazyInitializationException}: "could not initialize proxy - no Session".</li>
   * </ol>
   * {@code OBDal.refresh(dependent)} is the one operation that does what is actually needed:
   * {@code dependent} stays ATTACHED/managed (so a subsequent lazy collection access still has a
   * live session to reload through — no {@code LazyInitializationException}), while its cached
   * collection snapshot is discarded and will be re-fetched from the database on next access —
   * correctly excluding {@code existing}, which the bulk delete above already removed there, with
   * no stale reference left for Hibernate to misinterpret as a pending update OR an orphan needing
   * its own cascade delete. This does not interfere with core's own CREATE for the replacement row
   * either way — {@code copyRoleAccess} persists the new {@code WindowAccess} via a DIRECT {@code
   * OBDal.save(newAccess)} call, never via cascade from the parent collection; {@code
   * WindowAccessInjector#setParent}'s {@code .add(...)} call is only ever a convenience for
   * keeping the in-memory list accurate for the REST of this same request, not what actually
   * persists the row.
   */
  private void deleteForcingCreatePath(WindowAccess existing, Role dependent, Window window,
      Role template, Role previousSource) {
    OBContext.setAdminMode(false);
    try {
      OBDal.getInstance().getSession()
          .createQuery("delete from " + WindowAccess.ENTITY_NAME + " where id = :id")
          .setParameter("id", existing.getId())
          .executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
    dependent.getADWindowAccessList().remove(existing);
    OBDal.getInstance().refresh(dependent);
    OBDal.getInstance().getSession().evict(existing);
    log.info(
        "Prevented cross-template AD_Window_Access overlap corruption: cleared role {} window {} "
            + "access (previously {}) before template {}'s own grant propagates, forcing core "
            + "onto the safe CREATE path",
        dependent.getId(), window.getId(),
        previousSource != null ? "inherited from " + previousSource.getId() : "manually granted",
        template.getId());
  }

  /**
   * Originally the SIXTH-trigger fix's own mechanism (see the class javadoc's "A sixth trigger"
   * section for the full root-cause write-up) — now ALSO reused by the seventh trigger's own B7
   * fix, {@link #repointIfAlreadySourcedFromTemplate(Role, Window, Role, WindowAccess)} (see the
   * class javadoc's "The seventh trigger's own gap, found in REVIEW" section). Corrects {@code
   * existing}'s {@code inheritedFrom} and {@code editableField} to the pre-computed {@code
   * winner}/{@code winnerLevel} DIRECTLY, IN PLACE — the SAME row, SAME primary key — instead of
   * {@link #deleteForcingCreatePath} deleting it and relying on core's OWN propagation to recreate
   * it. Deliberately does NOT reuse {@code deleteForcingCreatePath}: for the sixth trigger,
   * deleting would reopen the exact duplicate-INSERT race this method exists to close (see the
   * class javadoc) whenever 2+ remaining templates both grant {@code window} — core's {@code
   * RoleInheritanceManager#applyRemoveInheritance} walks EVERY remaining template's own grants in
   * ONE {@code calculateAccesses} call, and with {@code existing} deleted, each remaining template
   * covering the same window independently finds no row and issues its OWN {@code copyRoleAccess}
   * INSERT, none of them aware of the others' still-unflushed one ({@code FlushMode.COMMIT} never
   * auto-flushes a query into visibility — see {@link #deleteForcingCreatePath}'s own javadoc for
   * the proof of that same limitation on the delete side); for the seventh trigger's B7 fix,
   * deleting would reopen the ORIGINAL "[B7]" bug this method's caller exists to close — core's
   * {@code propagateUpdatedAccess} has no create fallback at all, so a deleted row here is simply
   * gone forever.
   *
   * <p>Same bulk-DML-bypasses-the-session technique as {@link #deleteForcingCreatePath} (an
   * {@code executeUpdate()} HQL bulk UPDATE, not an entity-level setter + flush), for the identical
   * reason: this runs nested inside an already-in-progress flush under {@code FlushMode.COMMIT},
   * so an entity-level {@code existing.setInheritedFrom(winner)} would only mutate the in-memory
   * Java object — never Hibernate's own dirty-check bookkeeping in a way guaranteed to survive
   * being invoked mid-flush from an unrelated entity's delete-event callback — and a reentrant
   * {@code OBDal.flush()} to force it through is independently unsafe (see {@link
   * #deleteForcingCreatePath}'s javadoc, point 2). A bulk UPDATE writes the SQL immediately on the
   * current connection, visible to every subsequent SELECT in this transaction (including core's
   * own {@code getAccess()} lookups during {@code calculateAccesses}), with zero risk to the outer
   * flush's action queue.
   *
   * <p>Because {@code existing}'s {@code inheritedFrom} is corrected to {@code winner} — by
   * construction ALWAYS the remaining template with the numerically HIGHEST {@code
   * AD_Role_Inheritance.SeqNo} among every remaining template granting {@code window}, regardless
   * of that template's own access level (see the class javadoc's "Why InheritedFrom must track
   * core's own SeqNo precedence" paragraph for why a most-permissive-based pick here is NOT safe
   * — {@code winnerLevel} is where most-permissive-wins is actually applied, decoupled from this)
   * — core's OWN {@code
   * isPrecedent(inheritanceInheritFromIdList, currentInheritedFromId, newInheritedFromId)} check
   * can never find {@code winner}'s index smaller than any OTHER remaining template's index (its
   * index is, by construction, the largest among templates granting this window), so every one of
   * core's own per-template passes over this window resolves to {@code ACCESS_NOT_CHANGED} —
   * core touches this row ZERO times during its own recalculation (sixth-trigger case) or, for the
   * seventh-trigger's B7 case, core's {@code propagateUpdatedAccess}'s own {@code updateRoleAccess}
   * call against this SAME row afterward is a same-value, idempotent no-op. Either way, no {@code
   * EntityNewEvent}/{@code EntityUpdateEvent} is left for {@link #correctInheritedOwnership}/{@link
   * #widenInheritedAccessLevelIfNeeded} to react to — this method does both jobs itself, up front,
   * in one step.
   *
   * <p>{@code client}/{@code organization} are deliberately NOT touched here — {@code existing}
   * already belongs to {@code dependent} (it is an update to an ALREADY-correctly-owned row, never
   * a copy from a template's own row), so there is nothing to re-pin, unlike {@link
   * #correctInheritedOwnership}'s CREATE-path concern.
   *
   * <p><b>Refreshes {@code existing} afterward — {@code OBDal.refresh}, NOT {@code
   * session.evict()} — found empirically (ETP-4906, REVIEW round, "[B7]" fix, 2026-08-17).</b> An
   * earlier version of the seventh-trigger's B7 fix reused {@code session.evict(existing)} here
   * unchanged (the same call the sixth trigger's own {@code onDelete}-triggered caller uses safely)
   * — reproduced live as {@code HibernateException: Don't change the reference to a collection with
   * delete-orphan enabled : ADWindowAccess.aDTabAccessList} at the OUTER flush, specifically on the
   * B7 fix's own {@code onUpdate}-triggered path, never on the sixth trigger's {@code onDelete}
   * path. Root cause (confirmed by bisection — the crash disappeared when the {@code evict} call
   * was removed, and reappeared only once {@code repointInPlace} was called again from the {@code
   * onUpdate} trigger): {@code Interceptor#onFlushDirty} (which is how {@code onUpdate}'s {@code
   * EntityUpdateEvent} reaches this class) fires FROM WITHIN Hibernate's own {@code
   * AbstractFlushingEventListener#flushEntities} loop — the SAME loop that walks every OTHER
   * managed entity's collection-valued properties for reachability, {@code existing} included, in
   * the SAME pass. Evicting {@code existing} mid-loop rips it out of the persistence context while
   * Hibernate's own flush-time bookkeeping still expects to examine it, corrupting the collection
   * tracking. {@code Interceptor#onSave} (how the sixth trigger's sibling ADD-side triggers and
   * {@code deleteForcingCreatePath} reach this class) fires synchronously at {@code save()}/{@code
   * delete()} call time, OUTSIDE that loop entirely — evicting there never collides with it, which
   * is why the identical {@code evict()} call is safe on those paths but not here. {@code
   * OBDal.refresh(existing)} avoids the collision entirely: it re-syncs {@code existing}'s scalar
   * fields from the DB (reflecting the bulk UPDATE above, visible on the same connection/
   * transaction) while keeping it ATTACHED and managed, so the flush's own collection-reachability
   * bookkeeping for it stays consistent throughout — confirmed safe for BOTH callers by re-running
   * the full existing regression suite in this class after the change.
   */
  private void repointInPlace(WindowAccess existing, Role dependent, Window window, Role winner,
      boolean winnerLevel, Role previousSource) {
    // updatedBy is an entity reference (AD_User), not a plain id string — binding a String here
    // throws ClassCastException from Hibernate's EntityType#nullSafeSet (confirmed empirically
    // while verifying this fix: the first attempt bound the raw user id string and failed this
    // way immediately). Falls back to leaving updatedBy/updated untouched (rather than guessing a
    // system user) when no user is available on the context, mirroring how
    // deleteForcingCreatePath's own bulk DELETE never touches audit columns on the row it removes
    // either — this bulk UPDATE is the same kind of trusted, event-bypassing correction.
    org.openbravo.model.ad.access.User currentUser = OBContext.getOBContext() != null
        ? OBContext.getOBContext().getUser()
        : null;
    OBContext.setAdminMode(false);
    try {
      StringBuilder hql = new StringBuilder("update ").append(WindowAccess.ENTITY_NAME)
          .append(" set inheritedFrom = :winner, editableField = :level");
      if (currentUser != null) {
        hql.append(", updated = :updated, updatedBy = :updatedBy");
      }
      hql.append(" where id = :id");
      org.hibernate.query.Query<?> query = OBDal.getInstance().getSession()
          .createQuery(hql.toString());
      query.setParameter("winner", winner);
      query.setParameter("level", winnerLevel);
      if (currentUser != null) {
        query.setParameter("updated", new Date());
        query.setParameter("updatedBy", currentUser);
      }
      query.setParameter("id", existing.getId());
      query.executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().refresh(existing);
    log.info(
        "Prevented cross-template AD_Window_Access overlap corruption (multi-remaining-template "
            + "removal case): repointed role {} window {} in place from {} to {} (editableField={}) "
            + "without deleting the row — avoids core's own calculateAccesses independently "
            + "re-creating this window from 2+ remaining templates within the same flush",
        dependent.getId(), window.getId(),
        previousSource != null ? previousSource.getId() : "manually granted", winner.getId(),
        winnerLevel);
  }

  /**
   * Disables {@code OBCriteria}'s implicit client/organization filtering — REQUIRED for every
   * query in this class. {@code OBCriteria#initialize()} adds a {@code Restrictions.in(...
   * readableClients/readableOrganizations)} filter UNCONDITIONALLY, regardless of {@code
   * OBContext.isInAdministratorMode()} (only the separate {@code checkReadable} ACCESS check is
   * admin-mode-gated — the row-level filter itself is not). A template role is typically system
   * client {@code "0"} while its dependents are real tenant clients, so without this every query
   * here would silently return zero rows whenever the ambient {@code OBContext}'s role does not
   * happen to have both the template's AND the dependent's client in its own readable-clients list
   * — exactly the failure mode this class hit empirically while verifying this redesign against a
   * role composed from real templates it did not itself create the {@code OBContext} for.
   */
  private static <T extends BaseOBObject> OBCriteria<T> crossClientCriteria(Class<T> clazz) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(clazz);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria;
  }

  @SuppressWarnings("unchecked")
  private List<Role> findActiveDependentRoles(Role template) {
    OBCriteria<RoleInheritance> criteria = crossClientCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, template));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    List<Role> dependents = new ArrayList<>();
    Set<String> seenRoleIds = new LinkedHashSet<>();
    for (RoleInheritance inheritance : (List<RoleInheritance>) criteria.list()) {
      Role dependent = inheritance.getRole();
      if (dependent != null && seenRoleIds.add(dependent.getId())) {
        dependents.add(dependent);
      }
    }
    return dependents;
  }

  private WindowAccess findActiveWindowAccess(Role role, Window window) {
    OBCriteria<WindowAccess> criteria = crossClientCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW, window));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (WindowAccess) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private List<WindowAccess> findActiveWindowAccess(Role role) {
    OBCriteria<WindowAccess> criteria = crossClientCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    return criteria.list();
  }

  private static boolean sameId(BaseOBObject a, BaseOBObject b) {
    if (a == null || b == null) {
      return false;
    }
    String idA = (String) a.getId();
    String idB = (String) b.getId();
    return idA != null && idA.equals(idB);
  }

  /**
   * Overload for comparing against an {@code EntityPersistenceEvent#getCurrentState(Property)}
   * result, which is declared {@code Object} — defensively checks the runtime type rather than
   * casting, matching the same pattern this class's own predecessor design used for exactly this
   * comparison.
   */
  private static boolean sameId(BaseOBObject expected, Object actualState) {
    if (!(actualState instanceof BaseOBObject)) {
      return false;
    }
    return sameId(expected, (BaseOBObject) actualState);
  }
}
