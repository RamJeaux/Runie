package com.runie.ui;

import com.runie.core.Economy;
import com.runie.core.PullResult;
import com.runie.creatures.CreatureDefinition;
import com.runie.creatures.Rarity;
import com.runie.overlay.RuniePullOverlay;
import com.runie.state.RunieAccountState;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;

/**
 * Hatch tab (architecture §8 tab 3): the Hatch Egg button (1 egg / starter,
 * danger confirm §7.3) with the egg balance and a progress bar toward the next
 * egg (banked XP / current tier cost), published odds table (the six base
 * percentages, verbatim), pity meters (Rare 10 / Elite 30 / GM 300 with the
 * soft-pity marker), and the recent-hatches list.
 */
class PullPanel extends JPanel
{
	private final UiContext ctx;
	private final JLabel starterBanner = new JLabel("", SwingConstants.CENTER);
	private final JButton pullButton = new JButton();
	private final JLabel eggBalanceLabel = new JLabel("", SwingConstants.CENTER);
	private final JProgressBar eggProgressBar = pityBar();
	private final JButton skipButton = new JButton("Skip reveal");
	private final JLabel lastResult = new JLabel(" ", SwingConstants.CENTER);
	private final JProgressBar rareBar = pityBar();
	private final JProgressBar eliteBar = pityBar();
	private final JProgressBar gmBar = pityBar();
	private final JPanel recentList = new JPanel();

	PullPanel(UiContext ctx)
	{
		this.ctx = ctx;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		starterBanner.setAlignmentX(CENTER_ALIGNMENT);
		starterBanner.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		starterBanner.setForeground(ColorScheme.BRAND_ORANGE);
		add(starterBanner);
		add(gap(4));

		eggBalanceLabel.setAlignmentX(CENTER_ALIGNMENT);
		eggBalanceLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		eggBalanceLabel.setForeground(new Color(120, 220, 120));
		add(eggBalanceLabel);
		add(gap(3));

		pullButton.setAlignmentX(CENTER_ALIGNMENT);
		pullButton.setFocusable(false);
		pullButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		pullButton.setIcon(new javax.swing.ImageIcon(EggIcon.scaled(16)));
		pullButton.addActionListener(e -> doPull());
		add(pullButton);
		add(gap(3));

		JLabel nextEgg = new JLabel("Next egg", SwingConstants.CENTER);
		nextEgg.setAlignmentX(CENTER_ALIGNMENT);
		nextEgg.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		nextEgg.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(nextEgg);
		eggProgressBar.setAlignmentX(CENTER_ALIGNMENT);
		add(eggProgressBar);
		add(gap(2));

		skipButton.setAlignmentX(CENTER_ALIGNMENT);
		skipButton.setFocusable(false);
		skipButton.setVisible(false);
		skipButton.addActionListener(e -> ctx.pullOverlay.skip());
		add(skipButton);

		lastResult.setAlignmentX(CENTER_ALIGNMENT);
		lastResult.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		lastResult.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(lastResult);
		add(gap(8));

		// published odds — the six base percentages, verbatim (§8)
		add(sectionLabel("Odds per Hatch"));
		for (Rarity r : Rarity.values())
		{
			JLabel row = new JLabel(String.format("%s  %.1f%%", r.getDisplayName(), r.baseOdds() * 100),
				SwingConstants.LEFT);
			row.setAlignmentX(CENTER_ALIGNMENT);
			row.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			row.setForeground(RuniePullOverlay.rarityColor(r));
			add(row);
		}
		add(gap(8));

		add(sectionLabel("Pity"));
		add(pityRow("Rare+", rareBar));
		add(pityRow("Elite+", eliteBar));
		add(pityRow("GM (soft @150)", gmBar));
		add(gap(8));

		add(sectionLabel("Recent Hatches"));
		recentList.setLayout(new BoxLayout(recentList, BoxLayout.Y_AXIS));
		recentList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recentList.setAlignmentX(CENTER_ALIGNMENT);
		add(recentList);

		refresh();
	}

	void refresh()
	{
		int starters = ctx.gachaService.getStarterPullsRemaining();
		starterBanner.setText(starters > 0 ? starters + " welcome Hatch" + (starters > 1 ? "es" : "") + "!" : "");
		starterBanner.setVisible(starters > 0);
		pullButton.setText(starters > 0
			? "Hatch Egg (free starter)"
			: "Hatch Egg  (" + Economy.HATCH_COST_EGGS + " Egg)");
		int eggs = ctx.eggService.getEggBalance();
		eggBalanceLabel.setText(eggs + (eggs == 1 ? " Egg" : " Eggs"));
		long banked = ctx.eggService.getBankedXp();
		long cost = ctx.eggService.getNextEggCostXp();
		eggProgressBar.setMaximum(1000);
		eggProgressBar.setValue((int) (1000L * banked / Math.max(1L, cost)));
		eggProgressBar.setString(String.format("%,d / %,d XP", banked, cost));
		eggProgressBar.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		pullButton.setEnabled(ctx.gachaService.canPull());
		skipButton.setVisible(ctx.pullOverlay.isPresenting());

		if (ctx.stateStore.hasState())
		{
			PullResult.PitySnapshot pity = ctx.gachaService.pitySnapshot();
			setPity(rareBar, pity.rarePity, Economy.RARE_PITY_N);
			setPity(eliteBar, pity.elitePity, Economy.ELITE_PITY_N);
			setPity(gmBar, pity.gmPity, Economy.GM_HARD_AT);

			recentList.removeAll();
			List<RunieAccountState.PullRecord> tail = ctx.stateStore.getState().gacha.pullHistoryTail;
			int shown = 0;
			for (int i = tail.size() - 1; i >= 0 && shown < 6; i--, shown++)
			{
				RunieAccountState.PullRecord rec = tail.get(i);
				CreatureDefinition def = ctx.registry.byId(rec.creatureId);
				Rarity rarity = Rarity.valueOf(rec.rarity);
				JLabel row = new JLabel("#" + rec.n + "  " + (def != null ? def.getName() : rec.creatureId)
					+ (rec.dup ? "  (dup)" : "  NEW"), SwingConstants.LEFT);
				row.setAlignmentX(CENTER_ALIGNMENT);
				row.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
				row.setForeground(RuniePullOverlay.rarityColor(rarity));
				recentList.add(row);
			}
			if (!tail.isEmpty())
			{
				RunieAccountState.PullRecord last = tail.get(tail.size() - 1);
				CreatureDefinition def = ctx.registry.byId(last.creatureId);
				lastResult.setText("Last: " + (def != null ? def.getName() : last.creatureId)
					+ (last.dup ? " (duplicate)" : " - NEW!"));
			}
		}
		revalidate();
		repaint();
	}

	private void doPull()
	{
		if (!ctx.gachaService.canPull())
		{
			return;
		}
		// §7.3 in-combat / wilderness confirmation, initiated only from Swing
		if (ctx.dangerSupplier.getAsBoolean() && ctx.config.confirmDangerPulls())
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"You look busy or in danger - Hatch anyway?", "Runie",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION)
			{
				return;
			}
		}
		pullButton.setEnabled(false);
		ctx.commandRunner.accept(() ->
		{
			try
			{
				ctx.gachaService.pull(); // listeners drive overlay + panel refresh
			}
			catch (IllegalStateException ex)
			{
				// balance raced away between the check and the command — refresh only
			}
			SwingUtilities.invokeLater(this::refresh);
		});
	}

	private static JProgressBar pityBar()
	{
		JProgressBar bar = new JProgressBar();
		bar.setStringPainted(true);
		bar.setMaximumSize(new Dimension(210, 15));
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		return bar;
	}

	private static void setPity(JProgressBar bar, int value, int max)
	{
		bar.setMaximum(max);
		bar.setValue(Math.min(value, max));
		bar.setString(value + " / " + max);
		bar.setForeground(value >= max * 3 / 4 ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_COMPLETE_COLOR);
	}

	private JPanel pityRow(String label, JProgressBar bar)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(CENTER_ALIGNMENT);
		JLabel l = new JLabel(label);
		l.setAlignmentX(CENTER_ALIGNMENT);
		l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		bar.setAlignmentX(CENTER_ALIGNMENT);
		row.add(l);
		row.add(bar);
		row.add(Box.createRigidArea(new Dimension(0, 3)));
		return row;
	}

	private JLabel sectionLabel(String text)
	{
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setAlignmentX(CENTER_ALIGNMENT);
		l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		l.setForeground(Color.WHITE);
		return l;
	}

	private java.awt.Component gap(int h)
	{
		return Box.createRigidArea(new Dimension(0, h));
	}
}
