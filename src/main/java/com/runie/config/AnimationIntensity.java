package com.runie.config;

/** How lively the companion is (architecture §6.4). */
public enum AnimationIntensity
{
	FULL("Full"),
	NORMAL("Normal"),
	SUBTLE("Subtle");

	private final String displayName;

	AnimationIntensity(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
