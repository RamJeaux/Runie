package com.runie.creatures;

import java.util.Collections;
import java.util.List;

/**
 * Immutable, style-agnostic creature-line definition, loaded from the bundled
 * {@code com/runie/creatures.json} resource (generated from runie_roster_v3.md + build_bestiary.py).
 * Art frames live under the asset path convention of architecture §5 and are
 * resolved by AssetLoader (Epic 6) — this class carries data only.
 */
public class CreatureDefinition
{
	/** One evolution stage (1..3). Prestige keeps the stage-3 look + aura (§5.1). */
	public static class Stage
	{
		private int stage;
		private int minLevel;
		private String title;
		private String description;

		public int getStage()
		{
			return stage;
		}

		public int getMinLevel()
		{
			return minLevel;
		}

		public String getTitle()
		{
			return title;
		}

		/** Per-stage codex lore (OSRS-lineage-tied); shown on the creature detail page. */
		public String getDescription()
		{
			return description;
		}
	}

	private String id;
	private String name;
	private Rarity rarity;
	private String archetype;
	private List<Stage> stages;
	private String prestigeTitle;
	private boolean goldenAuraAt120;
	private String lore;
	private String goldenLore;

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public Rarity getRarity()
	{
		return rarity;
	}

	public String getArchetype()
	{
		return archetype;
	}

	public List<Stage> getStages()
	{
		return stages == null ? Collections.emptyList() : Collections.unmodifiableList(stages);
	}

	public String getPrestigeTitle()
	{
		return prestigeTitle;
	}

	/**
	 * Universal mastery signal (roster contract): every line's aura turns
	 * golden at Lv. 120. Kept as explicit per-line data (not an implicit
	 * constant) so the roster JSON remains the single source of truth.
	 */
	public boolean isGoldenAuraAt120()
	{
		return goldenAuraAt120;
	}

	public String getLore()
	{
		return lore;
	}

	/** Codex lore for the golden (Lv. 120) mastery form — the final entry in the evolution codex. */
	public String getGoldenLore()
	{
		return goldenLore;
	}

	/**
	 * Evolution stage (1..3) for a creature level. Prestiged creatures keep the
	 * stage-3 form regardless of (reset) level — the ProgressionService (Epic 4)
	 * layers prestige/aura presentation on top of this.
	 */
	public int stageForLevel(int level, boolean prestiged)
	{
		if (prestiged)
		{
			return stages.size(); // stage 3 look is kept after prestige
		}
		int current = 1;
		for (Stage s : stages)
		{
			if (level >= s.minLevel)
			{
				current = s.stage;
			}
		}
		return current;
	}

	/** Basic structural validation at registry load time. */
	void validate()
	{
		if (id == null || id.isEmpty() || !id.equals(id.toLowerCase()))
		{
			throw new IllegalStateException("creature id missing or not lowercase: " + id);
		}
		if (name == null || name.isEmpty())
		{
			throw new IllegalStateException(id + ": name missing");
		}
		if (rarity == null)
		{
			throw new IllegalStateException(id + ": rarity missing");
		}
		if (stages == null || stages.size() != 3)
		{
			throw new IllegalStateException(id + ": expected exactly 3 evolution stages");
		}
		int prevLevel = 0;
		for (int i = 0; i < stages.size(); i++)
		{
			Stage s = stages.get(i);
			if (s.stage != i + 1)
			{
				throw new IllegalStateException(id + ": stages out of order");
			}
			if (s.minLevel <= prevLevel && i > 0 || s.title == null)
			{
				throw new IllegalStateException(id + ": bad stage " + s.stage);
			}
			if (s.description == null || s.description.isEmpty())
			{
				throw new IllegalStateException(id + ": stage " + s.stage + " missing codex description");
			}
			prevLevel = s.minLevel;
		}
		if (stages.get(0).minLevel != 1)
		{
			throw new IllegalStateException(id + ": stage 1 must unlock at level 1");
		}
		if (prestigeTitle == null || prestigeTitle.isEmpty())
		{
			throw new IllegalStateException(id + ": prestige title missing");
		}
		if (!goldenAuraAt120)
		{
			throw new IllegalStateException(id + ": goldenAuraAt120 flag missing (roster contract: all lines gold at 120)");
		}
		if (goldenLore == null || goldenLore.isEmpty())
		{
			throw new IllegalStateException(id + ": goldenLore missing (codex contract: every line has a Lv120 entry)");
		}
	}
}
