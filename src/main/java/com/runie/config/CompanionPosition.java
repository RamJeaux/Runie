package com.runie.config;

/**
 * Default snap position for the companion overlay. This only seeds the overlay's
 * initial placement; free dragging + persistence remain OverlayManager's job
 * (architecture §7.1) — we never store coordinates ourselves.
 */
public enum CompanionPosition
{
	BOTTOM_RIGHT("Bottom right"),
	BOTTOM_LEFT("Bottom left"),
	TOP_RIGHT("Top right"),
	TOP_LEFT("Top left");

	private final String displayName;

	CompanionPosition(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
