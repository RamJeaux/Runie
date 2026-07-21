package com.runie.core;

import com.runie.state.RunieAccountState;
import com.runie.state.RunieStateStore;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Egg accrual — the plugin's single currency (gameplay rework part 1;
 * constants in {@link Economy}).
 *
 * <p>XP banks toward the next egg; the k-th egg earned in a UTC day costs
 * {@link Economy#eggCostXp}(k) XP (50k / 50k / 50k / 100k / 100k / 250k /
 * 250k / 250k, then a flat 500k forever that day). There is <b>no daily
 * cap</b> — the ladder itself is the throttle. The per-day egg counter and the
 * tier position reset at UTC day rollover (same day-key mechanism the old
 * daily cap used); banked XP is a continuous running counter that carries
 * across the boundary RAW (1:1) — it is never reset, scaled, or converted at
 * rollover. A small morning windfall (yesterday's banked XP settling against
 * the cheaper restarted ladder on the next gain) is intended.
 *
 * <p><b>No grant on initial snapshot / negative delta by construction</b>: this
 * service only ever sees positive, trusted deltas from {@link XpDeltaService}.
 *
 * <p>Non-accrual credits (starter grants, evolution rewards) go through
 * {@link #creditEggs} — outside the ladder by design.
 */
@Slf4j
@Singleton
public class EggService implements XpDeltaService.XpDeltaListener
{
	private final RunieStateStore stateStore;
	private final RunieClock clock;

	// cheap per-tick day-boundary guard (§13): cache the last computed UTC day
	private long cachedEpochDay = Long.MIN_VALUE;
	private String cachedDayString;

	@Inject
	public EggService(RunieStateStore stateStore, RunieClock clock)
	{
		this.stateStore = stateStore;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Egg accrual
	// ------------------------------------------------------------------

	@Override
	public void onXpGained(Skill skill, int delta, int newTotalXp)
	{
		if (delta <= 0 || !ready())
		{
			return; // defensive: XpDeltaService already guarantees positive deltas
		}
		rolloverDayIfNeeded();
		RunieAccountState.Eggs e = eggs();

		e.xpBanked += delta;
		long cost;
		while (e.xpBanked >= (cost = Economy.eggCostXp(e.earnedToday + 1)))
		{
			e.xpBanked -= cost;
			e.earnedToday++;
			e.balance++;
			e.lifetimeEarned++;
		}
		stateStore.markDirty();
	}

	/**
	 * UTC day rollover (checked on GameTick and before every accrual/grant).
	 * Resets ONLY the per-day egg counter (tier ladder restarts at 50k). Banked
	 * XP carries raw, 1:1 — it is a continuous running counter, never reset or
	 * converted at the boundary.
	 */
	public void rolloverDayIfNeeded()
	{
		if (!ready())
		{
			return;
		}
		RunieAccountState.Eggs e = eggs();
		String today = utcDate(clock.nowMs());
		if (e.currentDayUtc == null)
		{
			e.currentDayUtc = today;   // first touch of a fresh state — nothing to settle
			stateStore.markDirty();
			return;
		}
		if (today.equals(e.currentDayUtc))
		{
			return;
		}

		long daysElapsed;
		try
		{
			daysElapsed = ChronoUnit.DAYS.between(LocalDate.parse(e.currentDayUtc), LocalDate.parse(today));
		}
		catch (Exception ex)
		{
			log.warn("Runie: unparseable stored day '{}'; re-anchoring to today", e.currentDayUtc);
			daysElapsed = 1;
		}
		if (daysElapsed <= 0)
		{
			// Guarded clock makes this unreachable; defend anyway — days never regress.
			return;
		}

		// banked XP carries RAW (1:1) — only the daily ordinal/tier position resets
		e.earnedToday = 0;
		e.currentDayUtc = today;
		stateStore.markDirty();
	}

	// ------------------------------------------------------------------
	// Balances / UI views
	// ------------------------------------------------------------------

	public int getEggBalance()
	{
		return ready() ? eggs().balance : 0;
	}

	/** Eggs earned so far today (the tier ladder position k). */
	public int getEggsEarnedToday()
	{
		return ready() ? eggs().earnedToday : 0;
	}

	/** XP banked toward the next egg (UI progress-bar numerator). */
	public long getBankedXp()
	{
		return ready() ? eggs().xpBanked : 0L;
	}

	/** XP cost of the next egg at today's ladder position (progress-bar denominator). */
	public long getNextEggCostXp()
	{
		return ready() ? Economy.eggCostXp(eggs().earnedToday + 1) : Economy.eggCostXp(1);
	}

	// ------------------------------------------------------------------
	// Spending / crediting (GachaService + ProgressionService entry points)
	// ------------------------------------------------------------------

	public boolean spendEggs(int amount, String reason)
	{
		if (amount <= 0 || !readyWritable())
		{
			return false;
		}
		RunieAccountState.Eggs e = eggs();
		if (e.balance < amount)
		{
			return false;
		}
		e.balance -= amount;
		stateStore.markDirty();
		log.debug("Runie: spent {} egg(s) ({})", amount, reason);
		return true;
	}

	/** Non-accrual egg credit (starter grants / evolution rewards) — outside the ladder. */
	public void creditEggs(int amount, EggSource source)
	{
		if (amount <= 0 || !readyWritable())
		{
			return;
		}
		RunieAccountState.Eggs e = eggs();
		e.balance += amount;
		e.lifetimeEarned += amount;
		stateStore.markDirty();
		log.debug("Runie: credited {} egg(s) ({})", amount, source);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private boolean ready()
	{
		return stateStore.hasState();
	}

	private boolean readyWritable()
	{
		return stateStore.hasState() && !stateStore.isWritesDisabled();
	}

	private RunieAccountState.Eggs eggs()
	{
		return stateStore.getState().eggs;
	}

	private String utcDate(long nowMs)
	{
		long epochDay = Math.floorDiv(nowMs, 86_400_000L);
		if (epochDay != cachedEpochDay)
		{
			cachedEpochDay = epochDay;
			cachedDayString = LocalDate.ofEpochDay(epochDay).toString();
		}
		return cachedDayString;
	}
}
