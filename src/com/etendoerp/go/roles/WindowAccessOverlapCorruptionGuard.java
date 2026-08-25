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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

/**
 * ETP-4906 (Task B6, REDESIGNED 2026-08-16) — widens ETP-4852's cross-template
 * {@code AD_Window_Access} overlap-corruption fix beyond the single, actively-composed role
 * {@link UserRoleCompositionService} manages in one {@code assignTemplateRoles} call. See that
 * class's own class javadoc for the full root-cause write-up; summarized here only as far as
 * needed to explain why THIS class exists and why it works this way.
 *
 * <p><b>ETP-4830 base-class extraction (2026-08-24/25).</b> The general mechanism this class
 * relies on — the event-observer/{@code @Priority} strategy, {@code event.setCurrentState}, the
 * bulk-HQL delete/update techniques, the refresh-not-evict collection handling — is now
 * implemented exactly once in {@link AbstractAccessOverlapCorruptionGuard}, shared with {@link
 * ProcessAccessOverlapCorruptionGuard} and {@link ObuiappProcessAccessOverlapCorruptionGuard} (all
 * 3 independently converged on the identical algorithm — see that class's own javadoc for the
 * shared mechanism write-up). This class now only supplies the {@code WindowAccess}/{@code
 * Window}-specific accessors the base class needs. <b>The FULL round-by-round root-cause history
 * below is preserved UNCHANGED</b> — every one of the 7 triggers it documents was live-reproduced
 * against real data and is still defended, method-for-method, by the inherited base-class logic;
 * none of this historical rationale has been removed, only the implementation it describes has
 * moved.
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
 * already uses elsewhere in this module. No core patch: a plain module-level {@code
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
 * <p>The fix reuses the exact extension point {@code correctInheritedOwnership} already proved: it
 * fires on {@code EntityNewEvent} for EVERY freshly-created inherited {@code AD_Window_Access} row
 * on a non-template role — precisely the row the CREATE path (forced by one of the three triggers
 * above, or a plain first-time grant) produces — and {@code event.setCurrentState(Property,
 * Object)} already reliably corrects such a row's fields before the security check runs. {@code
 * widenInheritedAccessLevelIfNeeded} runs right alongside it (same {@code onSave} branch): before
 * the row is persisted, it looks up {@code access.getRole()}'s OTHER currently-active template
 * inheritances (the same query pattern {@code findOtherActiveTemplates} already used for the
 * REMOVE-path trigger — and the same source-of- truth choice {@code
 * UserRoleCompositionService#mostPermissiveWindowAccess} makes: the templates' OWN current grants,
 * not whatever level core's propagation happened to leave behind) and, if ANY of them grants the
 * SAME window at a MORE permissive level ({@code isEditableField() == true}) than the row about to
 * be created, corrects it via {@code event.setCurrentState( editableFieldProperty, true)} — the
 * same mechanism, applied to a different field. Deliberately a SEPARATE method from {@code
 * correctInheritedOwnership}, not a merge into it: ownership correction is unconditional (a row is
 * either wrong or not), while level widening requires an extra query across the role's OTHER
 * template inheritances and a strictly one-directional rule (never narrows — matches the existing
 * rule: a full grant, once resolved, always wins) that reads more clearly kept apart. This
 * generalizes {@code reconcileWindowAccessAfterComposition}'s own most-permissive-wins union to
 * ANY newly-created inherited row, not only ones created via {@code assignTemplateRoles}.
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
 * third-trigger {@code guardRemovedInheritance} decide whether a row needs re-evaluating when a
 * {@code RoleInheritance} is removed by checking whether that row's {@code InheritedFrom} matches
 * the template being removed — a row whose {@code InheritedFrom} lies about which template is
 * actually responsible for its value is invisible to that check for the ONE removal that should
 * affect it, and stays stuck at "full" forever.
 *
 * <p>The fix: {@code widenInheritedAccessLevelIfNeeded} now also repoints {@code inheritedFrom} to
 * the SAME template it already resolved as justifying the widened value ({@code
 * findActiveTemplateGrantingFullAccess} — the fourth trigger's own helper, changed from a boolean
 * "does one exist" check into one that also returns WHICH template), via the identical {@code
 * event.setCurrentState(Property, Object)} mechanism already used for {@code editableField} on
 * this same row and for ownership on {@code correctInheritedOwnership}'s row. This keeps {@code
 * InheritedFrom} an accurate "who is currently responsible for this row's effective value" pointer
 * at all times, so a later removal of THAT exact template's inheritance correctly triggers
 * re-derivation in both mechanisms above, cascading to whatever template remains. Only runs in the
 * branch that already widens (i.e. only when {@code editableField} was actually {@code false} and
 * gets flipped) — a row that is ALREADY full when CREATE-sourced never needs repointing, because
 * the template CREATE sourced it from must itself grant full access for that to be true, so
 * {@code InheritedFrom} is already correct in that case. Tie-break when 2+ other active templates
 * are equally responsible: highest {@code AD_Role_Inheritance.SeqNo}, mirroring core's own {@code
 * RoleInheritanceManager#propagateDeletedAccess} heuristic.
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
 * When {@code guardRemovedInheritance}'s OLD implementation deleted a dependent's existing row for
 * a window (forcing core onto the "safe" CREATE path, exactly like the ADD-side triggers above),
 * and that SAME window happens to be granted by 2+ of the REMAINING templates — only possible with
 * 3+ templates total — core's outer loop processes EACH of those remaining templates' passes
 * independently: the FIRST pass's {@code getAccess()} query finds nothing (the row was deleted)
 * and calls {@code copyRoleAccess} → {@code OBDal.save()}, scheduling an INSERT; the SECOND pass's
 * {@code getAccess()} query, run moments later in the SAME {@code calculateAccesses} call, does
 * NOT see that still-pending, not-yet-flushed INSERT (Openbravo's DAL sets every session to
 * {@code FlushMode.COMMIT} — see {@link AbstractAccessOverlapCorruptionGuard#
 * deleteForcingCreatePath}'s own javadoc for the proof of this same limitation on the delete side)
 * — so it ALSO finds nothing and ALSO calls {@code copyRoleAccess}, scheduling a SECOND INSERT for
 * the IDENTICAL {@code (AD_Role_ID, AD_Window_ID)} unique key. Both execute in the same JDBC batch
 * at the eventual flush; the second one violates the unique constraint — exactly the human's stack
 * trace. This is NOT a race in the "sometimes" sense — given 2+ remaining templates overlapping on
 * a window whose existing row needed clearing, it is deterministic, every time.
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
 * <p><b>The fix.</b> {@code guardRemovedInheritance} no longer deletes a dependent's row once per
 * remaining template that does not already own it. It instead computes, ONCE per window across
 * ALL remaining templates together, TWO independent things: (1) the single template that becomes
 * {@code InheritedFrom} — ALWAYS the remaining template with the numerically highest {@code
 * AD_Role_Inheritance.SeqNo} among every remaining template granting that window, regardless of
 * its own access level; and (2) the access level — most-permissive- wins across EVERY remaining
 * template granting that window, independent of which one was chosen for (1). When the existing
 * row does not already match both, it is corrected IN PLACE via {@link
 * AbstractAccessOverlapCorruptionGuard#repointInPlace}: a bulk HQL UPDATE on the SAME row (same
 * primary key), never a delete+recreate.
 *
 * <p><b>Why {@code InheritedFrom} must track core's own SeqNo precedence, not most-permissive-
 * wins — confirmed empirically, the hard way.</b> An earlier version of this fix picked whichever
 * remaining template grants FULL access as the winner (generalizing {@code
 * findActiveTemplateGrantingFullAccess}'s own ADD-side tie-break) — reasonable- looking, but
 * WRONG: reproduced live as the identical {@code OBSecurityException: Client (0) ... is not
 * present in ClientList} this whole class exists to prevent, on a window granted by 3 remaining
 * templates where the most-permissive one did NOT have the highest {@code SeqNo}. Root cause:
 * core's own {@code calculateAccesses} (unlike the ADD-side call sites) walks EVERY remaining
 * template in ONE call, ascending by {@code SeqNo}, and {@code isPrecedent} compares ONLY list
 * index (== {@code SeqNo} order) — it has no concept of access level at all. Repointing to
 * anything other than the actual highest-{@code SeqNo} grantor leaves a LOWER-index template's
 * name in {@code InheritedFrom}; core's own LATER pass over the genuinely highest-{@code SeqNo}
 * template then finds {@code isPrecedent(current, new) == true} (current's index is lower) and
 * blindly overrides the row via {@code updateRoleAccess} → {@code DalUtil.copyToTarget} — the
 * exact corrupting copy this class exists to prevent, just reached one hop later than the
 * duplicate-INSERT race above. Because the computed winner is now ALWAYS the actual highest-
 * {@code SeqNo} grantor, core's own {@code isPrecedent} check can never find a reason to touch
 * this row during its OWN recalculation — every one of core's per-template passes over it
 * resolves to {@code ACCESS_NOT_CHANGED}, so core never attempts a competing write, and neither
 * the duplicate-INSERT race above NOR this ownership-corruption variant can occur, no matter how
 * many remaining templates overlap on the same window. See {@link
 * AbstractAccessOverlapCorruptionGuard#repointInPlace}'s own javadoc for why this method does both
 * the ownership AND most-permissive-level correction itself (no later CREATE event is left for
 * {@code correctInheritedOwnership}/{@code widenInheritedAccessLevelIfNeeded} to react to).
 *
 * <p><b>Scope: REMOVE-side only.</b> The three ADD-side triggers ({@code guardNewInheritance},
 * {@code guardDependentsOf}) are NOT vulnerable to this race and were deliberately left unchanged:
 * {@code applyNewInheritance}'s and {@code propagateNewAccess}'s own {@code inheritanceList}/
 * iteration are always scoped to exactly ONE template (the one new inheritance, or the one
 * template whose own access just changed) per call, so at most ONE {@code copyRoleAccess} attempt
 * is ever made for a given (dependent, window) pair per event — structurally incapable of the "2+
 * competing passes in one calculateAccesses call" mechanism above, confirmed by re-reading both
 * call sites, not merely assumed.
 *
 * <p><b>Known residual gap, NOT closed here (documented, not silently ignored).</b> If the
 * dependent has NO existing row at all for a window 2+ remaining templates both grant (i.e. a
 * brand-new window neither the dependent nor any of its OTHER already-composed templates ever
 * granted before this specific removal), {@code guardRemovedInheritance} still has nothing to
 * repoint and falls back to core's own natural CREATE, which could in principle hit the identical
 * duplicate-INSERT race for that one window. Considered acceptable residual risk for now: a role
 * that has been composed for any length of time (like every real account this bug was found on)
 * will already have a materialized row for every window any of its active templates grants,
 * making this specific sub-case very unlikely in practice.
 *
 * <p><b>A seventh trigger - the ADD-path fix (rounds 1-2) had its own blind spot: core cannot
 * always SEE a pre-existing, already-correct row before it propagates.</b> Live-confirmed
 * BLOCKER (ETP-4906, Task B6, 7th round, 2026-08-17), found via {@code
 * UserRoleCompositionServiceOverlapReverificationTest}. Both ADD-side triggers ({@code
 * guardNewInheritance}, {@code guardDependentsOf}) originally skipped deleting a dependent's row
 * whenever it was ALREADY sourced from the SAME template that just propagated, reasoning that
 * core's own {@code handleAccess}/{@code isPrecedent} would independently reach {@code
 * ACCESS_NOT_CHANGED} and leave it alone. That reasoning silently assumed core's own {@code
 * AccessTypeInjector#findAccess} lookup CAN see the row - it cannot whenever the dependent's
 * client is outside the CALLING {@code OBContext}'s own readable-clients list (the row-level
 * filter core's query builds is not admin-mode-gated, exactly like {@link
 * com.etendoerp.go.roles.overlap.ActiveTemplateInheritance#crossClientCriteria(Class)}'s own
 * javadoc documents for OUR queries). When blind, {@code handleAccess} ALWAYS evaluates {@code
 * access == null} and takes
 * the CREATE branch regardless of whether a correct row already exists, so ANY pre-existing row -
 * no matter how correct - risks a duplicate-INSERT the instant core's blind {@code copyRoleAccess}
 * reaches it.
 *
 * <p>The fix: {@code clearConflictingAccessUnconditionally} deletes a dependent's conflicting row
 * UNCONDITIONALLY, even when already correctly sourced - forcing core onto its own safe CREATE
 * path every time, never relying on core being able to see (and thus skip) an already-correct
 * row. Safe for the {@code onSave}-triggered callers ({@code guardNewInheritance}, and {@code
 * guardDependentsOf}'s {@code NEW_GRANT} case) because both feed a core propagation method with a
 * guaranteed CREATE fallback when no row is found: {@code propagateNewAccess} to {@code
 * handleAccess} to {@code copyRoleAccess}.
 *
 * <p><b>The seventh trigger's own gap, found in REVIEW - {@code guardDependentsOf} is ALSO
 * invoked from {@code onUpdate}, where core has NO create fallback.</b> Live-confirmed BLOCKER
 * (ETP-4906, REVIEW round, finding "[B7]", 2026-08-17 - traced via static core-source analysis;
 * the plan doc's "[B7]" section has the full trace, including whether it was independently
 * live-reproduced by the fix below). {@code guardDependentsOf} is called from BOTH {@code onSave}
 * (a template GAINS a brand-new window grant - core propagates via {@code propagateNewAccess},
 * which DOES have the CREATE fallback above) AND {@code onUpdate} (an admin edits a template's OWN
 * EXISTING access level - core propagates via a DIFFERENT method, {@code propagateUpdatedAccess},
 * which has NO create fallback at all: {@code findInheritedAccess} either finds a dependent's row
 * already sourced from this exact template and updates it, or does nothing). The seventh
 * trigger's own fix made {@code clearConflictingAccessUnconditionally} delete unconditionally for
 * BOTH callers uniformly, without checking that both downstream core paths actually have a create
 * fallback - on the {@code onUpdate} route, deleting a dependent's already-correctly-sourced row
 * therefore permanently loses it: core's own propagation, the one path that is supposed to
 * reconcile the level change, has no branch left that recreates a missing row. No exception, no
 * useful log - a completely routine "admin edits a template's access level in Classic" action
 * would silently delete every correctly-inheriting dependent's access to that window.
 *
 * <p>The fix: {@code guardDependentsOf} now takes a {@link
 * com.etendoerp.go.roles.overlap.PropagationTrigger} telling it which core path its caller feeds.
 * {@code NEW_GRANT} ({@code onSave}) keeps the unconditional {@code
 * clearConflictingAccessUnconditionally} behavior above - safe, since {@code propagateNewAccess}
 * always recreates. {@code UPDATED_GRANT} ({@code onUpdate}) instead calls {@code
 * repointIfAlreadySourcedFromTemplate}: a dependent row NOT sourced from the template whose access
 * just changed is left untouched - core's own {@code propagateUpdatedAccess} would not have
 * touched it either, exactly matching core's own scope; a row ALREADY sourced from that exact
 * template - the ONLY case {@code propagateUpdatedAccess} would act on - is corrected IN PLACE via
 * the same bulk-HQL-UPDATE technique {@code repointInPlace} already uses for the sixth trigger,
 * rather than deleted, so the dependent's row is guaranteed to survive regardless of what core's
 * own (possibly-blind) propagation does afterward.
 *
 * <p><b>[BUG-2] The seventh trigger's OWN fix had a most-permissive-wins gap of its own, found
 * by QA's final coverage pass (ETP-4906, 2026-08-18).</b> {@code repointIfAlreadySourcedFromTemplate}
 * — the B7 fix above — only ever compared the dependent's existing row against the ONE template
 * whose own row just changed, then copied that template's new value onto the row directly. It
 * never surveyed whether some OTHER template {@code dependent} still actively inherits from also
 * grants {@code window} full access, unlike the ADD-path ({@code
 * widenInheritedAccessLevelIfNeeded}) and REMOVE-path ({@code collectItemGrantors}) triggers,
 * which both already re-derive a window's level from the FULL set of currently active grantors
 * before writing anything. Empirically reproduced live (QA's throwaway JUnit probe, first
 * attempt, no flakiness): a dependent inheriting FULL access to the same window from two templates
 * A and B, both actively granting it — downgrading B's own access via a routine Etendo Classic
 * admin edit dragged the dependent's row down to read-only too, even though A still actively
 * granted it full. The fix: {@code repointIfAlreadySourcedFromTemplate} now surveys every OTHER
 * actively-inherited template via {@code findActiveTemplateGrantingFullAccess} before applying
 * anything, and repoints {@code InheritedFrom} to whichever template actually justifies the final
 * (most-permissive) value.
 *
 * <p><b>ETP-4830 item 7 (2026-08-24, before the base-class extraction above).</b> This class's own
 * {@code findActiveTemplateGrantingFullAccess}/{@code collectWindowGrantors}/{@code
 * repointWindowIfNeeded} winner/level decision was extracted, unchanged, into {@link
 * com.etendoerp.go.roles.overlap.OverlapReconciliationCore#computeWinner(java.util.List)}/{@link
 * com.etendoerp.go.roles.overlap.OverlapReconciliationCore#findJustifyingFullGrant(java.util.List,
 * String)}, and its {@code findActiveTemplatesFor}/{@code findActiveDependentRoles}/{@code sameId}
 * helpers into {@link com.etendoerp.go.roles.overlap.ActiveTemplateInheritance} — shared, not
 * duplicated, by {@link ProcessAccessOverlapCorruptionGuard} and {@link
 * ObuiappProcessAccessOverlapCorruptionGuard}, which extend the identical mechanism to {@code
 * AD_Process_Access} and {@code OBUIAPP_Process_Access}. The base-class extraction above
 * completed that consolidation by also sharing the event-handling method BODIES themselves.
 */
public class WindowAccessOverlapCorruptionGuard
    extends AbstractAccessOverlapCorruptionGuard<WindowAccess, Window> {

  private static final Logger log = LogManager.getLogger(WindowAccessOverlapCorruptionGuard.class);

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected Class<WindowAccess> accessClass() {
    return WindowAccess.class;
  }

  @Override
  protected String accessEntityName() {
    return WindowAccess.ENTITY_NAME;
  }

  @Override
  protected String itemProperty() {
    return WindowAccess.PROPERTY_WINDOW;
  }

  @Override
  protected Role getRole(WindowAccess access) {
    return access.getRole();
  }

  @Override
  protected Window getGrantedItem(WindowAccess access) {
    return access.getWindow();
  }

  @Override
  protected Role getInheritedFrom(WindowAccess access) {
    return access.getInheritedFrom();
  }

  @Override
  protected Boolean getEditableField(WindowAccess access) {
    return access.isEditableField();
  }

  @Override
  protected void removeFromOwnerCollection(Role owner, WindowAccess access) {
    owner.getADWindowAccessList().remove(access);
  }

  @Override
  protected String entityLogLabel() {
    return "AD_Window_Access";
  }

  @Override
  protected String itemLogLabel() {
    return "window";
  }
}
