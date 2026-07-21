package com.runie.core;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** OSRS XP table anchors + levelForXp binary search (architecture Appendix A, §12.1). */
public class XpTableTest
{
	@Test
	public void canonicalAnchors()
	{
		assertEquals(0, XpTable.xpForLevel(1));
		assertEquals(83, XpTable.xpForLevel(2));
		assertEquals(6_517_253, XpTable.xpForLevel(92));       // half of 99
		assertEquals(13_034_431, XpTable.xpForLevel(99));
		assertEquals(104_273_167, XpTable.xpForLevel(120));
	}

	@Test
	public void levelForXpBoundaries()
	{
		assertEquals(1, XpTable.levelForXp(0, 99));
		assertEquals(1, XpTable.levelForXp(82, 99));
		assertEquals(2, XpTable.levelForXp(83, 99));
		assertEquals(98, XpTable.levelForXp(13_034_430, 99));
		assertEquals(99, XpTable.levelForXp(13_034_431, 99));
	}

	@Test
	public void capsAt99AndAt120ByPrestigeFlag()
	{
		// un-prestiged cap
		assertEquals(99, XpTable.levelForXp(200_000_000L, 99));
		// prestiged cap
		assertEquals(119, XpTable.levelForXp(104_273_166L, 120));
		assertEquals(120, XpTable.levelForXp(104_273_167L, 120));
		assertEquals(120, XpTable.levelForXp(2_000_000_000L, 120));
	}

	@Test
	public void tableIsStrictlyIncreasing()
	{
		for (int level = 2; level <= XpTable.MAX_VIRTUAL_LEVEL; level++)
		{
			if (XpTable.xpForLevel(level) <= XpTable.xpForLevel(level - 1))
			{
				throw new AssertionError("table not increasing at level " + level);
			}
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNegativeXp()
	{
		XpTable.levelForXp(-1, 99);
	}
}
