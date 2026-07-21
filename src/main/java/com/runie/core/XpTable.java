package com.runie.core;

/**
 * OSRS level → XP table, levels 1..126 (virtual), precomputed from the canonical
 * formula {@code xp(L) = floor( sum(l=1..L-1) floor(l + 300 * 2^(l/7)) / 4 )}
 * (architecture Appendix A).
 *
 * <p>Anchors asserted in tests: L2 = 83, L92 = 6,517,253, L99 = 13,034,431,
 * L120 = 104,273,167.
 */
public final class XpTable
{
	public static final int MAX_VIRTUAL_LEVEL = 126;
	public static final int LEVEL_99_XP_ANCHOR_LEVEL = 99;

	/** XP_FOR_LEVEL[L] = total XP required to be level L; index 0 unused. */
	private static final long[] XP_FOR_LEVEL = new long[MAX_VIRTUAL_LEVEL + 1];

	static
	{
		long points = 0;
		for (int level = 1; level <= MAX_VIRTUAL_LEVEL; level++)
		{
			XP_FOR_LEVEL[level] = points / 4;
			points += (long) Math.floor(level + 300.0 * Math.pow(2.0, level / 7.0));
		}
	}

	private XpTable()
	{
	}

	/** Total XP required to be {@code level} (1..126). */
	public static long xpForLevel(int level)
	{
		if (level < 1 || level > MAX_VIRTUAL_LEVEL)
		{
			throw new IllegalArgumentException("level out of range: " + level);
		}
		return XP_FOR_LEVEL[level];
	}

	/**
	 * The level for {@code xp}, capped at {@code cap} (99 for un-prestiged
	 * creatures, 120 prestiged). Binary search over the table.
	 */
	public static int levelForXp(long xp, int cap)
	{
		if (cap < 1 || cap > MAX_VIRTUAL_LEVEL)
		{
			throw new IllegalArgumentException("cap out of range: " + cap);
		}
		if (xp < 0)
		{
			throw new IllegalArgumentException("negative xp: " + xp);
		}

		int lo = 1;
		int hi = cap;
		// greatest level L in [1, cap] with xpForLevel(L) <= xp
		while (lo < hi)
		{
			int mid = (lo + hi + 1) >>> 1;
			if (XP_FOR_LEVEL[mid] <= xp)
			{
				lo = mid;
			}
			else
			{
				hi = mid - 1;
			}
		}
		return lo;
	}
}
