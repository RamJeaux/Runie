package com.runie.core;

import com.runie.state.RunieStateStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;

/**
 * The ONLY producer of trusted XP deltas (architecture §3 — THE critical
 * algorithm, implemented exactly per §3.2).
 *
 * <p>Why: there is no ExperienceChanged event; XP arrives via StatChanged, and on
 * login the client fires a burst of StatChanged carrying the account's entire
 * lifetime XP. A naive {@code delta = newXp - lastKnownXp} against a zeroed (or
 * stale, cross-account) baseline would grant a lifetime of XP as one delta —
 * economy-fatal (§16.1). Three layers prevent that:
 *
 * <ol>
 *   <li><b>Per-skill −1 sentinels</b>: the first StatChanged per skill after any
 *       (re)login is snapshot-only. Sentinels re-arm on LOGGING_IN / HOPPING /
 *       CONNECTION_LOST / LOGIN_SCREEN.</li>
 *   <li><b>2-tick post-LOGGED_IN grace window</b>: ALL StatChanged during grace
 *       are snapshot-only (covers late login bursts). We always prefer losing a
 *       crumb to granting a windfall.</li>
 *   <li><b>Plausibility ceiling + negative-delta re-snapshot</b>: implausible
 *       spikes (&gt;5M) and non-positive deltas refresh the snapshot and grant
 *       nothing — never a grant, never a "refund".</li>
 * </ol>
 *
 * <p>Account binding: every StatChanged re-verifies {@code getAccountHash()}.
 * Hash −1 → ignored entirely. A hash change swaps the state file BEFORE any
 * delta can be attributed and drops all snapshots — snapshots are never carried
 * across accounts (§16.2).
 */
@Slf4j
@Singleton
public class XpDeltaService
{
	static final int GRACE_TICKS = 2;                        // ~1.2s after LOGGED_IN
	static final int MAX_PLAUSIBLE_EVENT_XP = 5_000_000;     // > any legit single grant (lamps/quests)

	public interface XpDeltaListener
	{
		void onXpGained(Skill skill, int delta, int newTotalXp);
	}

	private final AccountContext accountContext;
	private final RunieStateStore stateStore;
	private final List<XpDeltaListener> listeners = new CopyOnWriteArrayList<>();

	/** Per-skill last-seen XP; -1 = sentinel: "no snapshot, nothing is trusted". */
	private final int[] cachedXp = new int[Skill.values().length];
	/** -1 = not logged in / grace not started; 0 = warmed up; >0 = counting down. */
	private int graceTicksRemaining = -1;
	/**
	 * True while the sentinels are armed by a (re)login-ish state, i.e. the NEXT
	 * LOGGED_IN is a genuine (re)connect and must start the grace window.
	 *
	 * <p>Why this exists: RuneLite fires {@code GameStateChanged(LOGGED_IN)}
	 * again after every {@code LOADING} (region crossing / teleport), not just
	 * after logins. Unconditionally re-arming the grace window there swallowed
	 * genuine in-session deltas for {@value #GRACE_TICKS} ticks on EVERY region
	 * load — real ongoing gains got dropped even though the sentinels were still
	 * warm. The settle window may only guard the initial post-login snapshot,
	 * never ongoing accrual, so grace re-arms ONLY when a reset state
	 * (LOGGING_IN / HOPPING / CONNECTION_LOST / LOGIN_SCREEN) preceded this
	 * LOGGED_IN. Every real (re)connect passes through one of those states
	 * first, so the login-dump guarantee is unchanged.
	 */
	private boolean awaitingLoginGrace = true;
	private long trackedAccountHash = AccountContext.NO_ACCOUNT;

	@Inject
	public XpDeltaService(AccountContext accountContext, RunieStateStore stateStore)
	{
		this.accountContext = accountContext;
		this.stateStore = stateStore;
		Arrays.fill(cachedXp, -1);
	}

	public void addListener(XpDeltaListener l)
	{
		listeners.add(l);
	}

	public void removeListener(XpDeltaListener l)
	{
		listeners.remove(l);
	}

	/** UI hint ("syncing…"): true once the post-login grace window has elapsed. */
	public boolean isWarmedUp()
	{
		return graceTicksRemaining == 0;
	}

	public void handleGameStateChanged(GameState gs)
	{
		switch (gs)
		{
			case LOGGING_IN:
			case HOPPING:
			case CONNECTION_LOST:
			case LOGIN_SCREEN:
				Arrays.fill(cachedXp, -1);                   // ARM SENTINELS — nothing is trusted
				graceTicksRemaining = -1;
				awaitingLoginGrace = true;
				break;
			case LOGGED_IN:
				// Start the post-login grace window ONLY on a genuine (re)connect.
				// LOGGED_IN also fires after every region LOADING mid-session; the
				// settle window must never swallow ongoing in-session gains there.
				if (awaitingLoginGrace)
				{
					graceTicksRemaining = GRACE_TICKS;
					awaitingLoginGrace = false;
				}
				break;
			default:
				break;
		}
	}

	public void handleGameTick()
	{
		if (graceTicksRemaining > 0)
		{
			graceTicksRemaining--;
		}
	}

	public void handleStatChanged(StatChanged e)
	{
		long hash = accountContext.accountHash();
		if (hash == AccountContext.NO_ACCOUNT)
		{
			return;                                          // no account context: ignore entirely
		}
		if (hash != trackedAccountHash)
		{
			// Account switched under us: swap the state file BEFORE any attribution,
			// and NEVER carry snapshots across accounts.
			trackedAccountHash = hash;
			stateStore.switchAccount(hash);
			Arrays.fill(cachedXp, -1);
			graceTicksRemaining = GRACE_TICKS;
			awaitingLoginGrace = false;                      // this IS the (re)bind; grace just started
		}

		int idx = e.getSkill().ordinal();
		int newXp = e.getXp();
		int oldXp = cachedXp[idx];
		cachedXp[idx] = newXp;                               // ALWAYS refresh the snapshot first

		if (oldXp == -1)
		{
			return;                                          // Layer 1: first event per skill post-(re)login = snapshot-only
		}
		if (graceTicksRemaining != 0)
		{
			return;                                          // Layer 2: whole grace window = snapshot-only
		}
		int delta = newXp - oldXp;
		if (delta <= 0)
		{
			return;                                          // Layer 3a: negative/zero → re-snapshot; never grant, never refund
		}
		if (delta > MAX_PLAUSIBLE_EVENT_XP)
		{
			// Layer 3b: implausible spike → re-snapshot + log, grant nothing
			log.warn("Runie: implausible xp delta {} in {}; re-snapshotting", delta, e.getSkill());
			return;
		}
		for (XpDeltaListener l : listeners)
		{
			l.onXpGained(e.getSkill(), delta, newXp);
		}
	}

	/** Plugin shutDown(): drop all snapshots and listeners for a clean re-enable. */
	public void reset()
	{
		Arrays.fill(cachedXp, -1);
		graceTicksRemaining = -1;
		awaitingLoginGrace = true;
		trackedAccountHash = AccountContext.NO_ACCOUNT;
		listeners.clear();
	}
}
