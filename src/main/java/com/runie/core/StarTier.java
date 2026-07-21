package com.runie.core;

import java.awt.Color;

/**
 * Duplicate-star color tiers (gameplay rework part 3). Each duplicate hatch of
 * an owned creature adds one star; stars group into five-star color tiers:
 * Bronze (1–5) → Silver (6–10) → Gold (11–15) → Red (16–20) → Pink (21–25).
 * Progression caps at {@link Economy#MAX_STARS} (5 Pink).
 */
public enum StarTier
{
	BRONZE("Bronze", new Color(205, 127, 50)),
	SILVER("Silver", new Color(198, 202, 212)),
	GOLD("Gold", new Color(255, 200, 46)),
	RED("Red", new Color(232, 68, 58)),
	PINK("Pink", new Color(255, 122, 190));

	private final String displayName;
	private final Color color;

	StarTier(String displayName, Color color)
	{
		this.displayName = displayName;
		this.color = color;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public Color getColor()
	{
		return color;
	}

	/**
	 * Color tier for a star count. Returns null for 0 stars (no tier yet);
	 * counts beyond {@link Economy#MAX_STARS} clamp to PINK.
	 */
	public static StarTier tierFor(int stars)
	{
		if (stars <= 0)
		{
			return null;
		}
		int clamped = Math.min(stars, Economy.MAX_STARS);
		return values()[(clamped - 1) / Economy.STARS_PER_TIER];
	}

	/**
	 * Stars shown WITHIN the current tier (1–5). 0 for 0 stars; counts beyond
	 * the cap clamp to 5 (full Pink).
	 */
	public static int starsInTier(int stars)
	{
		if (stars <= 0)
		{
			return 0;
		}
		int clamped = Math.min(stars, Economy.MAX_STARS);
		return ((clamped - 1) % Economy.STARS_PER_TIER) + 1;
	}
}
