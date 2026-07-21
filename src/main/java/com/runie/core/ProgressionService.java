package com.runie.core;

import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.CreatureRegistry;
import com.runie.state.CreatureInstance;
import com.runie.state.RunieAccountState;
import com.runie.state.RunieStateStore;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Creature XP mirror, leveling, evolution, prestige + collection accessors
 * (Epic 4; architecture §2 signatures).
 *
 * <p>Registered as a second {@link XpDeltaService.XpDeltaListener} in
 * {@code RuniePlugin.startUp()} — it only ever sees positive, validated player
 * XP deltas, which it mirrors <b>1:1</b> onto the ACTIVE creature. With no
 * active creature the delta is a no-op (no accrual, no banking — the panel
 * warns in Epic 7).
 *
 * <p>Levels come from the OSRS table: {@code XpTable.levelForXp(xp, prestiged ?
 * 120 : 99)}. Evolution stage is {@link CreatureDefinition#stageForLevel}
 * (thresholds ~L40 / ~L70–80 per roster). Prestige (eligible at L99, one tier
 * only, IRREVERSIBLE): resets {@code xp} to 0 (level 1), raises the cap to 120,
 * keeps the final evolved look (stage 3), increments {@code prestigeCount},
 * preserves {@code lifetimeXp}, and layers the prestige aura —
 * {@link AuraState#GOLDEN} at level 120.
 *
 * <p>All mutations go through {@link RunieAccountState} and persist via the
 * debounced {@link RunieStateStore} (prestige flushes immediately, §4.4).
 */
@Slf4j
@Singleton
public class ProgressionService implements XpDeltaService.XpDeltaListener
{
	/** Un-prestiged creature level cap. */
	public static final int BASE_LEVEL_CAP = 99;
	/** Prestiged creature level cap (single prestige tier — §16 risk 10). */
	public static final int PRESTIGE_LEVEL_CAP = 120;

	/** UI seam (Epic 6/7): overlay/panel subscribe for level/stage/prestige signals. */
	public interface ProgressionListener
	{
		default void onLevelUp(String creatureId, int oldLevel, int newLevel)
		{
		}

		/** Stage-change signal (evolution). Fired only on upward crossings. */
		default void onStageChange(String creatureId, int oldStage, int newStage)
		{
		}

		default void onPrestige(String creatureId)
		{
		}

		/**
		 * Active-companion selection changed ({@code activeCreatureId} may be
		 * null = cleared). The plugin uses this to refresh the companion overlay
		 * IMMEDIATELY — deploying a creature must never wait for an unrelated
		 * config change.
		 */
		default void onActiveChanged(String activeCreatureId)
		{
		}
	}

	private final RunieStateStore stateStore;
	private final CreatureRegistry registry;
	private final EggService eggService;
	private final List<ProgressionListener> listeners = new CopyOnWriteArrayList<>();

	@Inject
	public ProgressionService(RunieStateStore stateStore, CreatureRegistry registry, EggService eggService)
	{
		this.stateStore = stateStore;
		this.registry = registry;
		this.eggService = eggService;
	}

	public void addListener(ProgressionListener l)
	{
		listeners.add(l);
	}

	public void removeListener(ProgressionListener l)
	{
		listeners.remove(l);
	}

	// ------------------------------------------------------------------
	// XP mirror (the Epic 4 accrual path)
	// ------------------------------------------------------------------

	@Override
	public void onXpGained(Skill skill, int delta, int newTotalXp)
	{
		if (delta <= 0 || !readyWritable())
		{
			return;
		}
		RunieAccountState s = stateStore.getState();
		String activeId = s.creatures.active;
		if (activeId == null)
		{
			return; // no active creature: XP feeds no one — no accrual, no banking
		}
		CreatureInstance c = s.creatures.owned.get(activeId);
		if (c == null)
		{
			return; // defensive: load-time normalization guarantees active ∈ owned
		}

		int cap = c.isPrestiged() ? PRESTIGE_LEVEL_CAP : BASE_LEVEL_CAP;
		int oldLevel = XpTable.levelForXp(c.xp, cap);
		c.xp += delta;               // 1:1 mirror of the validated player delta
		c.lifetimeXp += delta;
		int newLevel = XpTable.levelForXp(c.xp, cap);
		stateStore.markDirty();

		if (newLevel > oldLevel)
		{
			// golden L120 mastery milestone (same crossing that fires the golden
			// burst VFX in the plugin's onLevelUp listener): +5 eggs, once ever
			if (newLevel >= PRESTIGE_LEVEL_CAP && oldLevel < PRESTIGE_LEVEL_CAP)
			{
				grantGoldenMilestoneReward(activeId, c);
			}
			for (ProgressionListener l : listeners)
			{
				l.onLevelUp(activeId, oldLevel, newLevel);
			}
			CreatureDefinition def = registry.byId(activeId);
			if (def != null)
			{
				int oldStage = def.stageForLevel(oldLevel, c.isPrestiged());
				int newStage = def.stageForLevel(newLevel, c.isPrestiged());
				if (newStage > oldStage)
				{
					grantEvolutionReward(activeId, c, oldStage, newStage);
					for (ProgressionListener l : listeners)
					{
						l.onStageChange(activeId, oldStage, newStage);
					}
				}
			}
		}
	}

	/**
	 * Evolution reward (rework part 2): +1 egg per evolution crossing
	 * (stage 1→2 and 2→3), granted exactly ONCE per crossing ever. The highest
	 * stage already rewarded persists on the creature
	 * ({@code evolutionRewardedStage}), so reloads / replayed events never
	 * double-grant; legacy creatures that pre-date the rework anchor at their
	 * current stage (no retroactive grants for crossings that happened before).
	 */
	private void grantEvolutionReward(String creatureId, CreatureInstance c, int oldStage, int newStage)
	{
		int rewarded = Math.max(c.evolutionRewardedStage, oldStage);
		int crossings = newStage - rewarded;
		if (crossings <= 0)
		{
			return;
		}
		c.evolutionRewardedStage = newStage;
		eggService.creditEggs(crossings * Economy.EVOLUTION_REWARD_EGGS, EggSource.EVOLUTION);
		stateStore.markDirty();
		log.debug("Runie: evolution reward — {} egg(s) for {} reaching stage {}", crossings, creatureId, newStage);
	}

	/**
	 * Golden L120 mastery reward: +{@link Economy#GOLDEN_MILESTONE_REWARD_EGGS}
	 * eggs when a (prestiged) creature reaches level
	 * {@value #PRESTIGE_LEVEL_CAP}, granted exactly ONCE per creature ever. The
	 * persisted {@code goldenRewardGranted} flag is the grant-once watermark:
	 * reloads / replayed crossings never double-grant. Called only on an actual
	 * upward L120 crossing, so a legacy creature already at 120 (whose level can
	 * never cross the cap again) is never retroactively granted.
	 */
	private void grantGoldenMilestoneReward(String creatureId, CreatureInstance c)
	{
		if (c.goldenRewardGranted)
		{
			return; // watermark: already rewarded — re-checks are no-ops
		}
		c.goldenRewardGranted = true;
		eggService.creditEggs(Economy.GOLDEN_MILESTONE_REWARD_EGGS, EggSource.GOLDEN_MILESTONE);
		stateStore.markDirty();
		log.debug("Runie: golden L120 milestone — {} egg(s) for {}", Economy.GOLDEN_MILESTONE_REWARD_EGGS, creatureId);
	}

	// ------------------------------------------------------------------
	// Active companion selection
	// ------------------------------------------------------------------

	public Optional<String> getActiveCreatureId()
	{
		if (!stateStore.hasState())
		{
			return Optional.empty();
		}
		return Optional.ofNullable(stateStore.getState().creatures.active);
	}

	public Optional<CreatureInstance> getActiveCreature()
	{
		return getActiveCreatureId().map(id -> stateStore.getState().creatures.owned.get(id));
	}

	/**
	 * Set the active companion. {@code null} clears the selection (XP then feeds
	 * no one — the panel warns).
	 *
	 * @return true if applied; false when the creature is not owned or state is
	 *         not writable
	 */
	public boolean setActiveCreature(String creatureId)
	{
		if (!readyWritable())
		{
			return false;
		}
		RunieAccountState s = stateStore.getState();
		if (creatureId != null && !s.creatures.owned.containsKey(creatureId))
		{
			return false;
		}
		s.creatures.active = creatureId;
		stateStore.markDirty();
		for (ProgressionListener l : listeners)
		{
			l.onActiveChanged(creatureId);
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Levels / evolution / aura (read views)
	// ------------------------------------------------------------------

	/** OSRS-table level for creature XP, capped 99 / 120 by prestige flag. */
	public int levelForXp(long xp, boolean prestiged)
	{
		return XpTable.levelForXp(xp, prestiged ? PRESTIGE_LEVEL_CAP : BASE_LEVEL_CAP);
	}

	/** Current level of an owned creature (0 if not owned / no state). */
	public int levelOf(String creatureId)
	{
		return getCreature(creatureId).map(c -> levelForXp(c.xp, c.isPrestiged())).orElse(0);
	}

	/** Evolution stage (1..3) for a level; prestiged creatures keep stage 3. */
	public int evolutionStageFor(CreatureDefinition def, int level, boolean prestiged)
	{
		return def.stageForLevel(level, prestiged);
	}

	/** Current evolution stage of an owned creature (0 if not owned). */
	public int currentStageOf(String creatureId)
	{
		Optional<CreatureInstance> inst = getCreature(creatureId);
		if (!inst.isPresent())
		{
			return 0;
		}
		CreatureDefinition def = registry.byId(creatureId);
		if (def == null)
		{
			return 0;
		}
		CreatureInstance c = inst.get();
		return def.stageForLevel(levelForXp(c.xp, c.isPrestiged()), c.isPrestiged());
	}

	/**
	 * Aura layer for an owned creature: NONE until prestiged, PRESTIGE after,
	 * GOLDEN at level {@value #PRESTIGE_LEVEL_CAP} (fully mastered). Derived —
	 * never persisted, cannot desync.
	 */
	public AuraState auraOf(String creatureId)
	{
		Optional<CreatureInstance> inst = getCreature(creatureId);
		if (!inst.isPresent() || !inst.get().isPrestiged())
		{
			return AuraState.NONE;
		}
		CreatureInstance c = inst.get();
		return levelForXp(c.xp, true) >= PRESTIGE_LEVEL_CAP ? AuraState.GOLDEN : AuraState.PRESTIGE;
	}

	// ------------------------------------------------------------------
	// Prestige (confirm-gated by UI; IRREVERSIBLE — no undo API exists)
	// ------------------------------------------------------------------

	/**
	 * Prestige an owned creature at level 99. On success: XP resets to 0
	 * (level 1), the cap rises to 120, the final evolved (stage-3) look is kept,
	 * {@code prestigeCount} increments, {@code lifetimeXp} is preserved, and the
	 * prestige aura activates. One prestige tier only; irreversible.
	 */
	public PrestigeResult prestige(String creatureId)
	{
		if (!readyWritable())
		{
			return PrestigeResult.NOT_READY;
		}
		CreatureInstance c = stateStore.getState().creatures.owned.get(creatureId);
		if (c == null)
		{
			return PrestigeResult.NOT_OWNED;
		}
		if (c.isPrestiged())
		{
			return PrestigeResult.ALREADY_PRESTIGED; // single tier — no second reset path
		}
		if (XpTable.levelForXp(c.xp, BASE_LEVEL_CAP) < BASE_LEVEL_CAP)
		{
			return PrestigeResult.NOT_LEVEL_99;
		}

		c.xp = 0;              // level 1, cap now 120; lifetimeXp untouched
		c.prestigeCount++;
		stateStore.markDirty();
		stateStore.flushAsync(); // immediate flush on prestige (§4.4)
		log.debug("Runie: prestiged {}", creatureId);
		for (ProgressionListener l : listeners)
		{
			l.onPrestige(creatureId);
		}
		return PrestigeResult.SUCCESS;
	}

	// ------------------------------------------------------------------
	// Collection accessors (panel/collection grid read these in Epic 7)
	// ------------------------------------------------------------------

	public boolean isOwned(String creatureId)
	{
		return stateStore.hasState() && stateStore.getState().creatures.owned.containsKey(creatureId);
	}

	public Optional<CreatureInstance> getCreature(String creatureId)
	{
		if (!stateStore.hasState() || creatureId == null)
		{
			return Optional.empty();
		}
		return Optional.ofNullable(stateStore.getState().creatures.owned.get(creatureId));
	}

	/** Owned creature ids, acquisition order (read-only view). */
	public Set<String> ownedCreatureIds()
	{
		if (!stateStore.hasState())
		{
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(stateStore.getState().creatures.owned.keySet());
	}

	public int ownedCount()
	{
		return stateStore.hasState() ? stateStore.getState().creatures.owned.size() : 0;
	}

	// ------------------------------------------------------------------

	private boolean readyWritable()
	{
		return stateStore.hasState() && !stateStore.isWritesDisabled();
	}
}
