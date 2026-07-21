package com.runie.assets;

import java.awt.image.BufferedImage;

/**
 * A pre-decoded, pre-scaled frame sequence (architecture §6.1). The frame index
 * is a pure function of elapsed milliseconds — never of client ticks — so
 * animation speed is independent of client framerate (§6.2).
 */
public final class AnimationClip
{
	private final BufferedImage[] frames;
	private final int perFrameMs;
	private final boolean loop;

	public AnimationClip(BufferedImage[] frames, int fps, boolean loop)
	{
		if (frames == null || frames.length == 0)
		{
			throw new IllegalArgumentException("clip needs at least one frame");
		}
		if (fps <= 0)
		{
			throw new IllegalArgumentException("fps must be positive: " + fps);
		}
		this.frames = frames;
		this.perFrameMs = 1000 / fps;
		this.loop = loop;
	}

	/** Frame index for {@code elapsedMs} since clip start (pure arithmetic). */
	public int frameIndexAt(long elapsedMs)
	{
		if (elapsedMs < 0)
		{
			elapsedMs = 0;
		}
		int i = (int) (elapsedMs / perFrameMs);
		return loop ? i % frames.length : Math.min(i, frames.length - 1);
	}

	public BufferedImage frameAt(long elapsedMs)
	{
		return frames[frameIndexAt(elapsedMs)];
	}

	/** Direct frame access by index (clamped) — used by ping-pong playback. */
	public BufferedImage frame(int index)
	{
		return frames[Math.max(0, Math.min(index, frames.length - 1))];
	}

	public boolean isFinished(long elapsedMs)
	{
		return !loop && elapsedMs >= (long) perFrameMs * frames.length;
	}

	public int frameCount()
	{
		return frames.length;
	}

	public int getPerFrameMs()
	{
		return perFrameMs;
	}

	public boolean isLoop()
	{
		return loop;
	}

	public BufferedImage firstFrame()
	{
		return frames[0];
	}

	public int getWidth()
	{
		return frames[0].getWidth();
	}

	public int getHeight()
	{
		return frames[0].getHeight();
	}
}
