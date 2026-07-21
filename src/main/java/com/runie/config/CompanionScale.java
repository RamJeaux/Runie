package com.runie.config;

/** Render size of the on-screen companion. */
public enum CompanionScale
{
	SMALL("Small", 96),
	MEDIUM("Medium", 128),
	LARGE("Large", 160);

	private final String displayName;
	private final int pixels;

	CompanionScale(String displayName, int pixels)
	{
		this.displayName = displayName;
		this.pixels = pixels;
	}

	public int getPixels()
	{
		return pixels;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
