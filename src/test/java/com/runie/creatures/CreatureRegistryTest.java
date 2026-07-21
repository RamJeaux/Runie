package com.runie.creatures;

import com.google.gson.Gson;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Bundled v3 roster loads, matches the locked 10/10/8/6/4/2 shape, and thresholds match runie_roster_v3.md. */
public class CreatureRegistryTest
{
	private CreatureRegistry registry;

	@Before
	public void setUp() throws IOException
	{
		registry = new CreatureRegistry(new Gson());
		registry.load();
	}

	@Test
	public void loadsAll40ValidLines()
	{
		assertTrue(registry.isLoaded());
		assertEquals(40, registry.size());
	}

	@Test
	public void rosterShapeIsLocked()
	{
		assertEquals(10, registry.byRarity(Rarity.COMMON).size());
		assertEquals(10, registry.byRarity(Rarity.UNCOMMON).size());
		assertEquals(8, registry.byRarity(Rarity.RARE).size());
		assertEquals(6, registry.byRarity(Rarity.ELITE).size());
		assertEquals(4, registry.byRarity(Rarity.MASTER).size());
		assertEquals(2, registry.byRarity(Rarity.GRANDMASTER).size());
	}

	@Test
	public void stageThresholdsVaryByTierPerRoster()
	{
		// Commons evolve at 75, Uncommons at 78, Rare+ at 80
		assertEquals(75, registry.byId("grubnak").getStages().get(2).getMinLevel());
		assertEquals(78, registry.byId("ratterbone").getStages().get(2).getMinLevel());
		assertEquals(80, registry.byId("cindermaw").getStages().get(2).getMinLevel());
		assertEquals(80, registry.byId("malgrave").getStages().get(2).getMinLevel());
	}

	@Test
	public void everyLineFollowsTierThresholdContract()
	{
		// roster contract: stage 2 @ 40 for ALL; stage 3 @ 75 (Common) /
		// 78 (Uncommon) / 80 (Rare, Elite, Master, Grandmaster)
		for (CreatureDefinition def : registry.all())
		{
			assertEquals(def.getId() + ": stage 1", 1, def.getStages().get(0).getMinLevel());
			assertEquals(def.getId() + ": stage 2", 40, def.getStages().get(1).getMinLevel());
			int expected;
			switch (def.getRarity())
			{
				case COMMON:
					expected = 75;
					break;
				case UNCOMMON:
					expected = 78;
					break;
				default:
					expected = 80;
					break;
			}
			assertEquals(def.getId() + ": stage 3", expected, def.getStages().get(2).getMinLevel());
		}
	}

	@Test
	public void everyLineCarriesTheGoldenAuraAt120Flag()
	{
		for (CreatureDefinition def : registry.all())
		{
			assertTrue(def.getId() + ": goldenAuraAt120", def.isGoldenAuraAt120());
		}
	}

	@Test
	public void stageForLevelHonorsThresholdsAndPrestige()
	{
		CreatureDefinition grubnak = registry.byId("grubnak");
		assertEquals(1, grubnak.stageForLevel(1, false));
		assertEquals(1, grubnak.stageForLevel(39, false));
		assertEquals(2, grubnak.stageForLevel(40, false));
		assertEquals(2, grubnak.stageForLevel(74, false));
		assertEquals(3, grubnak.stageForLevel(75, false));
		assertEquals(3, grubnak.stageForLevel(99, false));
		// prestige keeps the stage-3 look even at (reset) level 1
		assertEquals(3, grubnak.stageForLevel(1, true));

		CreatureDefinition cindermaw = registry.byId("cindermaw");
		assertEquals(2, cindermaw.stageForLevel(79, false));
		assertEquals(3, cindermaw.stageForLevel(80, false));
	}

	@Test
	public void everyLineHasTitlesAndLore()
	{
		for (CreatureDefinition def : registry.all())
		{
			assertNotNull(def.getId(), def.getName());
			assertNotNull(def.getId(), def.getArchetype());
			assertNotNull(def.getId(), def.getPrestigeTitle());
			assertNotNull(def.getId(), def.getLore());
			assertEquals(3, def.getStages().size());
			for (CreatureDefinition.Stage s : def.getStages())
			{
				assertNotNull(def.getId(), s.getTitle());
			}
		}
	}

	/** Codex contract: every line carries a per-stage description + a golden (Lv120) lore entry. */
	@Test
	public void everyLineHasFullCodexLore()
	{
		for (CreatureDefinition def : registry.all())
		{
			assertNotNull(def.getId() + ": goldenLore", def.getGoldenLore());
			assertTrue(def.getId() + ": goldenLore too short", def.getGoldenLore().length() >= 20);
			for (CreatureDefinition.Stage s : def.getStages())
			{
				assertNotNull(def.getId() + " s" + s.getStage() + ": description", s.getDescription());
				assertTrue(def.getId() + " s" + s.getStage() + ": description too short",
					s.getDescription().length() >= 20);
			}
		}
	}

	/**
	 * Compliance guard (Plugin Hub / Fan Content Policy): codex lore must not leak
	 * trademarked proper nouns or Jagex-coined species words — the lineage is evoked
	 * with generic terms only. Fails the build if a banned token slips into any line.
	 */
	@Test
	public void codexLoreIsComplianceSafe()
	{
		String[] banned = {
			"graardor", "bandos", "scurrius", "sarachnis", "scorpia", "cerberus", "kalphite",
			"k'ril", "tsutsaroth", "vorkath", "jormungand", "callisto", "zamorak", "saradomin",
			"guthix", "armadyl", "zulrah", "zygomite", "kurask", "nechryael", "nechryarch",
			"dagannoth", "tzhaar", "tztok", " jad", "ourg", "vyre", "zogre", "karuulm",
			"runescape", "jagex", "gielinor", "morytania", "varrock", "lumbridge", "karamja"
		};
		for (CreatureDefinition def : registry.all())
		{
			StringBuilder sb = new StringBuilder();
			sb.append(' ').append(def.getGoldenLore().toLowerCase()).append(' ');
			sb.append(def.getLore().toLowerCase()).append(' ');
			for (CreatureDefinition.Stage s : def.getStages())
			{
				sb.append(s.getDescription().toLowerCase()).append(' ');
			}
			String blob = sb.toString();
			for (String b : banned)
			{
				assertTrue(def.getId() + ": banned term in lore -> " + b, !blob.contains(b));
			}
		}
	}

	@Test
	public void capstonesAreCorrect()
	{
		assertEquals(Rarity.MASTER, registry.byId("vaelgrim").getRarity());
		// the two v3 Grandmasters carry their full boss-apex display names
		assertEquals(Rarity.GRANDMASTER, registry.byId("malgrave").getRarity());
		assertEquals("Malgrave, the Hollow Crown", registry.byId("malgrave").getName());
		assertEquals("Malgrave, the Undethroned", registry.byId("malgrave").getPrestigeTitle());
		assertEquals(Rarity.GRANDMASTER, registry.byId("ythrax").getRarity());
		assertEquals("Ythrax, the World-Coil", registry.byId("ythrax").getName());
	}

	@Test
	public void skitterfangIsRenamedSkitterscour()
	{
		assertTrue(registry.contains("skitterscour"));
		assertEquals("Skitterscour", registry.byId("skitterscour").getName());
		assertEquals(false, registry.contains("skitterfang"));
	}
}
