package com.runie.creatures;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the 42 creature-line definitions from the bundled
 * {@code com/runie/creatures.json} resource at plugin startup (architecture §2).
 * Immutable after {@link #load()}; read-only lookups thereafter.
 */
@Slf4j
@Singleton
public class CreatureRegistry
{
	static final String RESOURCE_PATH = "/com/runie/creatures.json";

	/** Locked v3 roster shape: 10C / 10U / 8R / 6E / 4M / 2GM = 40 lines. */
	private static final int[] EXPECTED_PER_TIER = {10, 10, 8, 6, 4, 2};

	private final Gson gson;

	private final Map<String, CreatureDefinition> byId = new LinkedHashMap<>();
	private final Map<Rarity, List<CreatureDefinition>> byRarity = new EnumMap<>(Rarity.class);
	private volatile boolean loaded;

	@Inject
	public CreatureRegistry(Gson gson)
	{
		this.gson = gson;
	}

	/** Loads and validates the bundled roster. Called from RuniePlugin.startUp(). */
	public synchronized void load() throws IOException
	{
		if (loaded)
		{
			return;
		}
		try (InputStream in = CreatureRegistry.class.getResourceAsStream(RESOURCE_PATH))
		{
			if (in == null)
			{
				throw new IOException("bundled creature roster missing: " + RESOURCE_PATH);
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				Type listType = new TypeToken<List<CreatureDefinition>>()
				{
				}.getType();
				List<CreatureDefinition> defs = gson.fromJson(reader, listType);
				index(defs);
			}
		}
		loaded = true;
		log.debug("Runie creature registry loaded: {} lines", byId.size());
	}

	private void index(List<CreatureDefinition> defs)
	{
		if (defs == null || defs.isEmpty())
		{
			throw new IllegalStateException("creature roster is empty");
		}
		for (Rarity r : Rarity.values())
		{
			byRarity.put(r, new ArrayList<>());
		}
		for (CreatureDefinition def : defs)
		{
			def.validate();
			if (byId.putIfAbsent(def.getId(), def) != null)
			{
				throw new IllegalStateException("duplicate creature id: " + def.getId());
			}
			byRarity.get(def.getRarity()).add(def);
		}
		for (Rarity r : Rarity.values())
		{
			int expected = EXPECTED_PER_TIER[r.tierIndex()];
			int actual = byRarity.get(r).size();
			if (actual != expected)
			{
				throw new IllegalStateException(
					"roster shape mismatch for " + r + ": expected " + expected + ", got " + actual);
			}
		}
	}

	public boolean isLoaded()
	{
		return loaded;
	}

	public CreatureDefinition byId(String id)
	{
		return byId.get(id);
	}

	public boolean contains(String id)
	{
		return byId.containsKey(id);
	}

	/** All definitions, roster order. */
	public List<CreatureDefinition> all()
	{
		return Collections.unmodifiableList(new ArrayList<>(byId.values()));
	}

	/** Tier pool for Summon creature selection (uniform within tier — §9.2). */
	public List<CreatureDefinition> byRarity(Rarity rarity)
	{
		return Collections.unmodifiableList(byRarity.getOrDefault(rarity, Collections.emptyList()));
	}

	public int size()
	{
		return byId.size();
	}
}
