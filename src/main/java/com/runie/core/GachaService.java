package com.runie.core;

import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import com.runie.creatures.Rarity;
import com.runie.state.CreatureInstance;
import com.runie.state.RunieAccountState;
import com.runie.state.RunieStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Hatches: odds, pity, first-copy protection, duplicates, starters
 * (architecture §9 — the tier-selection algorithm §9.2 is implemented exactly).
 *
 * <p>Semantics locked to {@code runie_sim.py} Section B, updated for the Egg
 * rework:
 * <ul>
 *   <li><b>Cost</b>: {@link Economy#HATCH_COST_EGGS} egg, checked+deducted via
 *       {@link EggService#spendEggs} BEFORE any roll — an insufficient balance
 *       rejects the hatch with no state change.</li>
 *   <li><b>Pity checks use {@code counter == N-1} before the pull} — the
 *       guarantee lands ON the 10th / 30th pull. Pity rerolls sample the
 *       CONDITIONAL base distribution ({@code BASE[minTier..GM]} renormalized),
 *       so an Elite-pity pull can still land Master/GM.</li>
 *   <li><b>GM soft pity</b> swaps the whole distribution at ≥{@code GM_SOFT_AT}
 *       (weight moved from Common only); the hard guarantee at
 *       {@code GM_HARD_AT} outranks everything.</li>
 *   <li><b>Higher-tier hits reset lower counters</b>; counters persist in state
 *       with no expiry.</li>
 *   <li><b>First-copy protection</b>: within the first
 *       {@code FIRST_COPY_PROTECTION_PULLS} lifetime pulls, a duplicate is
 *       rerolled ONCE, uniformly among the same tier's unowned creatures; if the
 *       tier is fully owned the dup stands.</li>
 *   <li><b>Duplicates → Stars (rework part 3)</b>: NO currency refund. Each
 *       duplicate adds +1 star (capped at {@link Economy#MAX_STARS}) and rolls
 *       a Shiny chance at the rate of the creature's star tier BEFORE this
 *       duplicate ({@link Economy#shinyRateForStars}); once shiny, rolling
 *       stops for good. At max stars and not yet shiny, further dupes keep
 *       rolling at the post-Pink 1.0% rate.</li>
 *   <li><b>Starter hatches</b>: while {@code starterPullsRemaining > 0} a hatch
 *       is free (a {@code HATCH_COST_EGGS} credit is issued just before the
 *       uniform spend, keeping the ledger uniform — §9.3); the 3rd starter
 *       hatch forces Uncommon+ via the conditional distribution. All three fall
 *       inside the first-copy window by construction.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class GachaService
{
	/** UI seam (Epic 6/7): hatch-reveal overlay + panel subscribe here. */
	public interface PullListener
	{
		void onPull(PullResult result);
	}

	/** Recent-pull log length (local UI list only; counters are the real state). */
	static final int PULL_HISTORY_TAIL_MAX = 50;

	private final EggService eggService;
	private final CreatureRegistry registry;
	private final RunieStateStore stateStore;
	private final RunieClock clock;
	private final RunieRandom random;
	private final List<PullListener> listeners = new CopyOnWriteArrayList<>();

	@Inject
	public GachaService(EggService eggService, CreatureRegistry registry,
		RunieStateStore stateStore, RunieClock clock, RunieRandom random)
	{
		this.eggService = eggService;
		this.registry = registry;
		this.stateStore = stateStore;
		this.clock = clock;
		this.random = random;
	}

	public void addListener(PullListener l)
	{
		listeners.add(l);
	}

	public void removeListener(PullListener l)
	{
		listeners.remove(l);
	}

	public int getStarterPullsRemaining()
	{
		return stateStore.hasState() ? stateStore.getState().gacha.starterPullsRemaining : 0;
	}

	/** True when the next hatch can be made (starter hatch or affordable). */
	public boolean canPull()
	{
		if (!stateStore.hasState() || stateStore.isWritesDisabled())
		{
			return false;
		}
		return getStarterPullsRemaining() > 0 || eggService.getEggBalance() >= Economy.HATCH_COST_EGGS;
	}

	/** Pity meters for the panel (Epic 7). */
	public PullResult.PitySnapshot pitySnapshot()
	{
		RunieAccountState.Gacha g = stateStore.getState().gacha;
		return new PullResult.PitySnapshot(g.rarePity, g.elitePity, g.gmPity, g.totalPulls, g.starterPullsRemaining);
	}

	/**
	 * One hatch (architecture §9.2, normative). Starter hatches are consumed
	 * automatically while any remain.
	 *
	 * @throws IllegalStateException if no state is loaded, writes are disabled,
	 *         or the egg balance is insufficient (nothing is mutated in that case)
	 */
	public PullResult pull()
	{
		if (!stateStore.hasState() || stateStore.isWritesDisabled())
		{
			throw new IllegalStateException("Runie state not ready for hatches");
		}
		RunieAccountState s = stateStore.getState();
		RunieAccountState.Gacha g = s.gacha;

		// 0) spend (starter hatches are free: credit exactly one hatch cost first
		//    so the ledger stays uniform — §9.3)
		boolean starter = g.starterPullsRemaining > 0;
		Rarity forceMinRarity = null;
		if (starter)
		{
			eggService.creditEggs(Economy.HATCH_COST_EGGS, EggSource.STARTER);
			if (g.starterPullsRemaining == 1)
			{
				forceMinRarity = Rarity.UNCOMMON; // starter #3: guaranteed Uncommon+
			}
		}
		if (!eggService.spendEggs(Economy.HATCH_COST_EGGS, "hatch"))
		{
			throw new IllegalStateException("insufficient Eggs for a Hatch");
		}
		if (starter)
		{
			g.starterPullsRemaining--;
		}

		// 1) tier resolution (order matters — §9.2)
		int tier = resolveTier(g, forceMinRarity);
		Rarity rarity = Rarity.values()[tier];

		// 2) counter updates — higher-tier hits reset lower counters; no expiry
		g.rarePity = tier >= Rarity.RARE.tierIndex() ? 0 : g.rarePity + 1;
		g.elitePity = tier >= Rarity.ELITE.tierIndex() ? 0 : g.elitePity + 1;
		g.gmPity = tier == Rarity.GRANDMASTER.tierIndex() ? 0 : g.gmPity + 1;

		// 3) creature selection — uniform within tier
		List<CreatureDefinition> pool = registry.byRarity(rarity);
		CreatureDefinition c = pool.get(random.nextInt(pool.size()));

		// 4) first-copy protection (pulls 1–20, ONE reroll, same tier, unowned only)
		boolean rerolled = false;
		if (g.totalPulls < Economy.FIRST_COPY_PROTECTION_PULLS && s.creatures.owned.containsKey(c.getId()))
		{
			List<CreatureDefinition> unowned = new ArrayList<>();
			for (CreatureDefinition d : pool)
			{
				if (!s.creatures.owned.containsKey(d.getId()))
				{
					unowned.add(d);
				}
			}
			if (!unowned.isEmpty())
			{
				c = unowned.get(random.nextInt(unowned.size()));
				rerolled = true;
			}
		}
		g.totalPulls++;

		// 5) resolve: new copy vs duplicate → +1 star and a shiny roll (rework
		//    parts 3+4). No currency refund exists anymore.
		long now = clock.nowMs();
		boolean dup = s.creatures.owned.containsKey(c.getId());
		int starsAfter = 0;
		boolean becameShiny = false;
		boolean shinyNow = false;
		if (dup)
		{
			CreatureInstance inst = s.creatures.owned.get(c.getId());
			inst.copiesPulled++;
			// shiny roll at the rate of the tier BEFORE this dupe; stops once shiny
			if (!inst.shiny && random.nextDouble() < Economy.shinyRateForStars(inst.stars))
			{
				inst.shiny = true;
				becameShiny = true;
			}
			if (inst.stars < Economy.MAX_STARS)
			{
				inst.stars++;
			}
			starsAfter = inst.stars;
			shinyNow = inst.shiny;
		}
		else
		{
			s.creatures.owned.put(c.getId(), CreatureInstance.fresh(now));
			if (s.creatures.active == null)
			{
				s.creatures.active = c.getId(); // auto-activate the very first creature
			}
		}

		// recent-pull log (local UI tail, capped)
		RunieAccountState.PullRecord rec = new RunieAccountState.PullRecord();
		rec.n = g.totalPulls;
		rec.creatureId = c.getId();
		rec.rarity = rarity.name();
		rec.dup = dup;
		rec.tsMs = now;
		g.pullHistoryTail.add(rec);
		while (g.pullHistoryTail.size() > PULL_HISTORY_TAIL_MAX)
		{
			g.pullHistoryTail.remove(0);
		}

		stateStore.markDirty();
		stateStore.flushAsync(); // immediate flush at pull end (§4.4)

		PullResult result = new PullResult(c.getId(), rarity, !dup, rerolled, starsAfter,
			becameShiny, shinyNow, starter,
			new PullResult.PitySnapshot(g.rarePity, g.elitePity, g.gmPity, g.totalPulls, g.starterPullsRemaining));
		for (PullListener l : listeners)
		{
			l.onPull(result);
		}
		return result;
	}

	/** Tier resolution (§9.2 step 1). Package-private for direct statistical tests. */
	int resolveTier(RunieAccountState.Gacha g, Rarity forceMinRarity)
	{
		if (g.gmPity >= Economy.GM_HARD_AT - 1)
		{
			// hard guarantee: this is the 300th GM-less pull — outranks everything
			return Rarity.GRANDMASTER.tierIndex();
		}
		double[] dist = g.gmPity >= Economy.GM_SOFT_AT ? Economy.GM_SOFT_ODDS : Economy.BASE_ODDS;
		int tier = sampleTier(dist, random);
		if (g.elitePity == Economy.ELITE_PITY_N - 1 && tier < Rarity.ELITE.tierIndex())
		{
			tier = sampleConditional(Economy.BASE_ODDS, Rarity.ELITE.tierIndex(), random); // can still land M/GM
		}
		if (g.rarePity == Economy.RARE_PITY_N - 1 && tier < Rarity.RARE.tierIndex())
		{
			tier = sampleConditional(Economy.BASE_ODDS, Rarity.RARE.tierIndex(), random);
		}
		if (forceMinRarity != null && tier < forceMinRarity.tierIndex())
		{
			tier = sampleConditional(Economy.BASE_ODDS, forceMinRarity.tierIndex(), random);
		}
		return tier;
	}

	/** Cumulative-sum inverse sampling over a full distribution. */
	static int sampleTier(double[] dist, RunieRandom rng)
	{
		double r = rng.nextDouble();
		double cum = 0;
		for (int i = 0; i < dist.length; i++)
		{
			cum += dist[i];
			if (r < cum)
			{
				return i;
			}
		}
		return dist.length - 1; // fp-edge fallback
	}

	/** Sampling over {@code dist[minTier..]} renormalized (pity/force rerolls). */
	static int sampleConditional(double[] dist, int minTier, RunieRandom rng)
	{
		double sum = 0;
		for (int i = minTier; i < dist.length; i++)
		{
			sum += dist[i];
		}
		double r = rng.nextDouble() * sum;
		double cum = 0;
		for (int i = minTier; i < dist.length; i++)
		{
			cum += dist[i];
			if (r < cum)
			{
				return i;
			}
		}
		return dist.length - 1;
	}
}
