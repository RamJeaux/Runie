package com.runie.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Runie side panel (architecture §8): header with the Egg balance + today's
 * accrual, tabs for Collection (grid ↔ detail card) and Hatch (Hatch Egg).
 *
 * <p><b>Scrolling:</b> the RuneLite side panel is a fixed ~225px-wide, height-
 * constrained strip. Every tab's body is therefore wrapped in a vertical
 * {@link JScrollPane} (horizontal scrolling off) whose view tracks the viewport
 * width — content reflows to the panel width and takes its natural height, so
 * the full 42-creature grid is reachable by scrolling instead of being
 * squeezed/clipped to the visible area.
 *
 * <p>All data access is read-only service views; commands go through
 * {@link UiContext#commandRunner} (client thread in production). External
 * events (pulls, level-ups, config changes) call {@link #refreshLater()},
 * which marshals onto the EDT.
 */
public class RuniePanel extends PluginPanel
{
	/** Scroll step for one mouse-wheel/arrow unit, in px. */
	private static final int SCROLL_UNIT_PX = 16;

	private final UiContext ctx;
	private final java.util.concurrent.atomic.AtomicBoolean refreshQueued =
		new java.util.concurrent.atomic.AtomicBoolean();
	private final JLabel eggsLabel = balanceLabel(new Color(120, 220, 120));
	private final JLabel todayLabel = balanceLabel(ColorScheme.LIGHT_GRAY_COLOR);
	private final JLabel warnLabel = new JLabel("", SwingConstants.CENTER);

	private final CollectionGridPanel collectionGrid;
	private final CreatureDetailPanel detailPanel;
	private final PullPanel pullPanel;
	private final CardLayout collectionCards = new CardLayout();
	private final JPanel collectionHolder = new JPanel(collectionCards);

	public RuniePanel(UiContext ctx)
	{
		super(false);
		this.ctx = ctx;
		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		// header: title + balance + no-active-companion warning (fixed, never scrolls)
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Runie", SwingConstants.CENTER);
		title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.NORTH);

		JPanel balances = new JPanel(new java.awt.GridLayout(2, 1, 0, 2));
		balances.setBackground(ColorScheme.DARK_GRAY_COLOR);
		balances.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		balances.add(eggsLabel);
		balances.add(todayLabel);
		header.add(balances, BorderLayout.CENTER);

		warnLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		warnLabel.setForeground(new Color(255, 170, 80));
		header.add(warnLabel, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);

		// tabs — each tab body scrolls vertically
		collectionGrid = new CollectionGridPanel(ctx, this::showDetail);
		detailPanel = new CreatureDetailPanel(ctx, this::showCollection, this::refreshLater);
		pullPanel = new PullPanel(ctx);

		collectionHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		collectionHolder.add(collectionGrid, "grid");
		collectionHolder.add(detailPanel, "detail");

		JTabbedPane tabs = new JTabbedPane();
		tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); // single row at 242px
		tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabs.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		tabs.addTab("Collection", vScroll(collectionHolder, 2));
		tabs.addTab("Hatch", vScroll(pullPanel, 2));
		add(tabs, BorderLayout.CENTER);

		refresh();
	}

	/** Full refresh — must run on the EDT. */
	public void refresh()
	{
		int eggs = ctx.eggService.getEggBalance();
		eggsLabel.setText(eggs + (eggs == 1 ? " Egg" : " Eggs"));
		todayLabel.setText(String.format("%d egg%s today - next: %,d / %,d XP",
			ctx.eggService.getEggsEarnedToday(), ctx.eggService.getEggsEarnedToday() == 1 ? "" : "s",
			ctx.eggService.getBankedXp(), ctx.eggService.getNextEggCostXp()));
		boolean noActive = ctx.stateStore.hasState()
			&& !ctx.progressionService.getActiveCreatureId().isPresent()
			&& ctx.progressionService.ownedCount() > 0
			&& ctx.config.warnNoActiveCreature();
		warnLabel.setText(noActive ? "No active companion - XP feeds no one!" : " ");
		collectionGrid.refresh();
		detailPanel.refresh();
		pullPanel.refresh();
	}

	/**
	 * Thread-safe refresh entry point for service listeners (client thread).
	 *
	 * <p>Coalescing: this is now also called for every validated XP delta (the
	 * same-delta panel sync — see {@code RuniePlugin.startUp}), which can burst
	 * many times per client tick. A pending flag collapses any burst into one
	 * queued EDT refresh; the flag clears BEFORE {@link #refresh()} reads state,
	 * so a delta landing mid-refresh always queues one more pass — the panel can
	 * lag by at most one EDT hop, never miss the final value.
	 */
	public void refreshLater()
	{
		if (refreshQueued.compareAndSet(false, true))
		{
			SwingUtilities.invokeLater(() ->
			{
				refreshQueued.set(false);
				refresh();
			});
		}
	}

	/** Header egg-balance text (test seam — headless sync tests read this). */
	String headerEggsText()
	{
		return eggsLabel.getText();
	}

	/** Header today/banked-XP text (test seam — headless sync tests read this). */
	String headerTodayText()
	{
		return todayLabel.getText();
	}

	public void showDetail(String creatureId)
	{
		detailPanel.showCreature(creatureId);
		collectionCards.show(collectionHolder, "detail");
		scrollToTop(collectionHolder);
	}

	public void showCollection()
	{
		collectionCards.show(collectionHolder, "grid");
		scrollToTop(collectionHolder);
	}

	/** Test/mockup access to the detail card. */
	public CreatureDetailPanel detailPanel()
	{
		return detailPanel;
	}

	/**
	 * Wrap tab content in a vertical-only scroll pane: vertical bar as needed,
	 * horizontal bar never; the {@link ScrollBody} view tracks the viewport
	 * width so the content reflows to the panel width and keeps its natural
	 * height (no forced aspect ratio, no clipping).
	 */
	private static JScrollPane vScroll(JComponent content, int hPad)
	{
		ScrollBody body = new ScrollBody(content, hPad);
		JScrollPane scroll = new JScrollPane(body,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(SCROLL_UNIT_PX);
		scroll.getVerticalScrollBar().setPreferredSize(
			new Dimension(7, 0)); // slim, RuneLite-like scrollbar in the narrow panel
		return scroll;
	}

	/** Jump the enclosing viewport back to the top (grid ↔ detail swaps). */
	private static void scrollToTop(JComponent inner)
	{
		JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, inner);
		if (scroll != null)
		{
			scroll.getVerticalScrollBar().setValue(0);
		}
	}

	/**
	 * Scroll-pane view that tracks the viewport WIDTH (never scrolls
	 * horizontally) while exposing the content's natural preferred HEIGHT
	 * (scrolls vertically when taller than the viewport). Content is anchored
	 * NORTH so short tabs don't get stretched vertically.
	 */
	private static final class ScrollBody extends JPanel implements Scrollable
	{
		ScrollBody(JComponent content, int hPad)
		{
			super(new BorderLayout());
			setBackground(ColorScheme.DARK_GRAY_COLOR);
			setBorder(BorderFactory.createEmptyBorder(6, hPad, 6, hPad));
			add(content, BorderLayout.NORTH);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return SCROLL_UNIT_PX;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(visibleRect.height - SCROLL_UNIT_PX, SCROLL_UNIT_PX);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true; // reflow to the panel width — horizontal scroll never appears
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false; // natural height — vertical scrollbar appears as needed
		}
	}

	private static JLabel balanceLabel(Color color)
	{
		JLabel l = new JLabel("", SwingConstants.CENTER);
		l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		l.setForeground(color);
		return l;
	}
}
