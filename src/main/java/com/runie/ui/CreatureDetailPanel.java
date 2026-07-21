package com.runie.ui;

import com.runie.core.AuraState;
import com.runie.core.Economy;
import com.runie.core.PrestigeResult;
import com.runie.core.ProgressionService;
import com.runie.core.StarTier;
import com.runie.core.XpTable;
import com.runie.creatures.CreatureDefinition;
import com.runie.overlay.RuniePullOverlay;
import com.runie.overlay.ShinyRenderer;
import com.runie.state.CreatureInstance;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;

/**
 * Creature detail card (architecture §8): art (with runtime shiny tint), name
 * (+ ✦ shiny tag)/stage title, rarity, duplicate stars in their tier color,
 * level + XP bar to next level, evolution status, aura state (incl. golden at
 * 120), duplicate count, Set Active, and the confirm-gated Prestige button.
 */
class CreatureDetailPanel extends JPanel
{
	private static final int ART_PX = 128;

	private final UiContext ctx;
	private final Runnable onBack;
	private final Runnable onChanged;
	private String creatureId;
	/** Selected codex entry: 0 = auto (follow current stage), 1..3 = stage, 4 = golden (Lv 120). */
	private int codexSel;

	CreatureDetailPanel(UiContext ctx, Runnable onBack, Runnable onChanged)
	{
		this.ctx = ctx;
		this.onBack = onBack;
		this.onChanged = onChanged;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
	}

	void showCreature(String creatureId)
	{
		this.creatureId = creatureId;
		this.codexSel = 0; // reset to auto-follow the current stage each time a creature is opened
		rebuild();
	}

	void refresh()
	{
		if (creatureId != null)
		{
			rebuild();
		}
	}

	private void rebuild()
	{
		removeAll();
		CreatureDefinition def = ctx.registry.byId(creatureId);
		if (def == null)
		{
			revalidate();
			repaint();
			return;
		}
		Optional<CreatureInstance> instOpt = ctx.progressionService.getCreature(creatureId);
		boolean owned = instOpt.isPresent();
		Color rc = RuniePullOverlay.rarityColor(def.getRarity());

		JButton back = new JButton("< Collection");
		back.setFocusable(false);
		back.setAlignmentX(CENTER_ALIGNMENT);
		back.addActionListener(e -> onBack.run());
		add(back);
		add(gap(6));

		// art
		int stage = owned ? Math.max(1, ctx.progressionService.currentStageOf(creatureId)) : 1;
		AuraState aura = owned ? ctx.progressionService.auraOf(creatureId) : AuraState.NONE;
		BufferedImage art = null;
		if (aura == AuraState.GOLDEN)
		{
			com.runie.assets.AnimationClip golden =
				ctx.assetLoader.getGoldenClip(creatureId, ctx.artStyleService.effectiveStyle(), stage, ART_PX);
			if (golden != null)
			{
				art = golden.firstFrame();
			}
		}
		if (art == null)
		{
			art = ctx.assetLoader.getThumbnail(creatureId, ctx.artStyleService.effectiveStyle(), stage, ART_PX);
		}
		boolean shiny = owned && instOpt.get().shiny;
		if (shiny && art != null)
		{
			art = ShinyRenderer.applyStaticShiny(art); // runtime effect, no shiny art files
		}
		if (art != null)
		{
			JLabel pic = new JLabel(new ImageIcon(art));
			pic.setAlignmentX(CENTER_ALIGNMENT);
			pic.setBorder(BorderFactory.createLineBorder(rc, 2));
			add(pic);
			add(gap(6));
		}

		// name + stage title + rarity
		String title = shiny ? "\u2726 " + def.getName() : def.getName();
		String stageTitle = "";
		if (owned)
		{
			CreatureInstance inst = instOpt.get();
			stageTitle = inst.isPrestiged() && def.getPrestigeTitle() != null
				? def.getPrestigeTitle()
				: def.getStages().get(stage - 1).getTitle();
		}
		add(centeredLabel(title, 15, shiny ? new Color(160, 235, 255) : Color.WHITE, true));
		if (!stageTitle.isEmpty() && !stageTitle.equals(title))
		{
			add(centeredLabel(stageTitle, 11, ColorScheme.LIGHT_GRAY_COLOR, false));
		}
		add(centeredLabel(def.getRarity().getDisplayName().toUpperCase(), 11, rc, true));
		if (owned)
		{
			int starCount = instOpt.get().stars;
			StarTier starTier = StarTier.tierFor(starCount);
			if (starTier != null)
			{
				StringBuilder starsText = new StringBuilder();
				for (int i = 0; i < StarTier.starsInTier(starCount); i++)
				{
					starsText.append('\u2605');
				}
				starsText.append("  ").append(starTier.getDisplayName())
					.append(" (").append(starCount).append('/').append(Economy.MAX_STARS).append(')');
				add(centeredLabel(starsText.toString(), 11, starTier.getColor(), true));
			}
		}
		if (def.getArchetype() != null && !def.getArchetype().isEmpty())
		{
			add(centeredLabel(def.getArchetype() + " family", 10, ColorScheme.LIGHT_GRAY_COLOR, false));
		}
		add(gap(6));

		if (!owned)
		{
			add(centeredLabel("Not yet collected", 12, ColorScheme.LIGHT_GRAY_COLOR, false));
			revalidate();
			repaint();
			return;
		}

		CreatureInstance inst = instOpt.get();
		int cap = inst.isPrestiged() ? ProgressionService.PRESTIGE_LEVEL_CAP : ProgressionService.BASE_LEVEL_CAP;
		int level = ctx.progressionService.levelOf(creatureId);

		// level + XP bar to next level
		add(centeredLabel("Level " + level + " / " + cap, 13, Color.WHITE, true));
		JProgressBar xpBar = new JProgressBar(0, 1000);
		xpBar.setStringPainted(true);
		xpBar.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		xpBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		xpBar.setMaximumSize(new Dimension(200, 18));
		xpBar.setAlignmentX(CENTER_ALIGNMENT);
		if (level >= cap)
		{
			xpBar.setValue(1000);
			xpBar.setString("MAX");
		}
		else
		{
			long base = XpTable.xpForLevel(level);
			long next = XpTable.xpForLevel(level + 1);
			long into = inst.xp - base;
			long span = next - base;
			xpBar.setValue((int) (1000 * into / Math.max(1, span)));
			xpBar.setString(String.format("%,d / %,d XP", into, span));
		}
		add(xpBar);
		add(gap(6));

		// stage / evolution status
		String evo;
		if (stage < def.getStages().size() && !inst.isPrestiged())
		{
			evo = "Stage " + stage + "/3 - evolves at Lv " + def.getStages().get(stage).getMinLevel();
		}
		else
		{
			evo = "Stage 3/3 - fully evolved";
		}
		add(centeredLabel(evo, 11, ColorScheme.LIGHT_GRAY_COLOR, false));
		add(gap(8));

		// ---- Evolution Codex: per-stage lore, unlocked stages readable, future stages gated ----
		buildCodex(def, stage, aura == AuraState.GOLDEN);
		add(gap(8));

		// aura + prestige status
		String auraText;
		Color auraColor = ColorScheme.LIGHT_GRAY_COLOR;
		switch (aura)
		{
			case GOLDEN:
				auraText = "GOLDEN aura - fully mastered (Lv 120)";
				auraColor = new Color(255, 214, 84);
				break;
			case PRESTIGE:
				auraText = "Prestige aura (prestiged x" + inst.prestigeCount + ")";
				auraColor = new Color(235, 220, 160);
				break;
			default:
				auraText = inst.isPrestiged() ? "Prestiged" : "No aura";
		}
		add(centeredLabel(auraText, 11, auraColor, aura != AuraState.NONE));
		add(centeredLabel("Copies Hatched: " + inst.copiesPulled
			+ "   Lifetime XP: " + String.format("%,d", inst.lifetimeXp), 10,
			ColorScheme.LIGHT_GRAY_COLOR, false));
		add(gap(8));

		// actions
		boolean isActive = ctx.progressionService.getActiveCreatureId()
			.map(a -> a.equals(creatureId)).orElse(false);
		JButton activeBtn = new JButton(isActive ? "Active companion ★" : "Set active");
		activeBtn.setFocusable(false);
		activeBtn.setEnabled(!isActive);
		activeBtn.setAlignmentX(CENTER_ALIGNMENT);
		activeBtn.addActionListener(e -> ctx.commandRunner.accept(() ->
		{
			ctx.progressionService.setActiveCreature(creatureId);
			onChanged.run();
		}));
		add(activeBtn);
		add(gap(4));

		boolean eligible = !inst.isPrestiged() && level >= ProgressionService.BASE_LEVEL_CAP;
		JButton prestigeBtn = new JButton(inst.isPrestiged()
			? "Prestiged (cap 120)"
			: eligible ? "Prestige to 120..." : "Prestige at Lv 99");
		prestigeBtn.setFocusable(false);
		prestigeBtn.setEnabled(eligible);
		prestigeBtn.setAlignmentX(CENTER_ALIGNMENT);
		prestigeBtn.addActionListener(e -> confirmPrestige(def));
		add(prestigeBtn);

		revalidate();
		repaint();
	}

	/** Two-step typed confirm — prestige is IRREVERSIBLE (§8 tab 1). */
	private void confirmPrestige(CreatureDefinition def)
	{
		String typed = (String) JOptionPane.showInputDialog(this,
			"Prestige " + def.getName() + "?\n\nThis is IRREVERSIBLE: level resets to 1,\n"
				+ "the cap rises to 120, the stage-3 look is kept.\n\nType PRESTIGE to confirm:",
			"Prestige " + def.getName(), JOptionPane.WARNING_MESSAGE, null, null, "");
		if (!"PRESTIGE".equals(typed))
		{
			return;
		}
		ctx.commandRunner.accept(() ->
		{
			PrestigeResult r = ctx.progressionService.prestige(creatureId);
			if (r != PrestigeResult.SUCCESS)
			{
				javax.swing.SwingUtilities.invokeLater(() ->
					JOptionPane.showMessageDialog(this, "Prestige failed: " + r));
			}
			onChanged.run();
		});
	}

	/**
	 * Evolution codex: a selectable list of this line's lore entries — one per
	 * evolution stage plus the golden (Lv 120) mastery form. Entries the player
	 * has reached are readable; entries not yet unlocked are shown locked.
	 * Defaults to the current stage; the selection persists across refreshes
	 * until the creature is reopened.
	 */
	private void buildCodex(CreatureDefinition def, int maxStage, boolean goldenUnlocked)
	{
		int entries = def.getStages().size(); // 3
		int sel = codexSel == 0 ? maxStage : codexSel; // 0 => auto-follow current stage
		if (sel >= 1 && sel <= entries && sel > maxStage)
		{
			sel = maxStage;
		}
		if (sel == 4 && !goldenUnlocked)
		{
			sel = maxStage;
		}
		if (sel < 1)
		{
			sel = 1;
		}

		add(centeredLabel("EVOLUTION CODEX", 10, new Color(150, 160, 175), true));
		add(gap(4));

		// selector row: I / II / III / golden-star
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(CENTER_ALIGNMENT);
		row.setMaximumSize(new Dimension(240, 30));
		String[] roman = {"I", "II", "III"};
		for (int n = 1; n <= entries; n++)
		{
			row.add(codexTab(roman[n - 1], n == sel, n <= maxStage, n));
		}
		row.add(codexTab("✦", sel == 4, goldenUnlocked, 4));
		add(row);
		add(gap(4));

		// selected entry: title + wrapped lore
		String title;
		String desc;
		Color titleColor;
		if (sel == 4)
		{
			title = def.getPrestigeTitle();
			desc = def.getGoldenLore();
			titleColor = new Color(255, 214, 84);
		}
		else
		{
			CreatureDefinition.Stage s = def.getStages().get(sel - 1);
			title = s.getTitle();
			desc = s.getDescription();
			titleColor = Color.WHITE;
		}
		add(centeredLabel(title, 12, titleColor, true));
		add(gap(2));
		add(wrappedLabel(desc));

		// gentle hint about what remains locked
		if (maxStage < entries)
		{
			add(gap(3));
			int nextLvl = def.getStages().get(maxStage).getMinLevel();
			add(centeredLabel("Evolve to Lv " + nextLvl + " to unlock the next entry", 9,
				ColorScheme.LIGHT_GRAY_COLOR, false));
		}
		else if (!goldenUnlocked)
		{
			add(gap(3));
			add(centeredLabel("Reach the golden aura (Lv 120) to unlock the final entry", 9,
				ColorScheme.LIGHT_GRAY_COLOR, false));
		}
	}

	private JButton codexTab(String label, boolean selected, boolean unlocked, int target)
	{
		JButton b = new JButton(unlocked ? label : "🔒"); // lock glyph when not yet unlocked
		b.setFocusable(false);
		b.setMargin(new java.awt.Insets(2, 6, 2, 6));
		b.setFont(new Font(Font.SANS_SERIF, selected ? Font.BOLD : Font.PLAIN, 12));
		b.setEnabled(unlocked);
		b.setForeground(selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		if (selected)
		{
			b.setBorder(BorderFactory.createLineBorder(new Color(255, 214, 84), 1));
		}
		b.addActionListener(e ->
		{
			codexSel = target;
			rebuild();
		});
		return b;
	}

	/** Word-wrapped, centered lore paragraph sized to the plugin panel width. */
	private JLabel wrappedLabel(String text)
	{
		String safe = text == null ? "" : text
			.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		// Wrap width sits comfortably inside the usable area (PluginPanel 225px −
		// panel/tab/scroll insets − the as-needed vertical scrollbar) so the
		// paragraph never runs past the right edge.
		JLabel l = new JLabel("<html><div style='width:176px; text-align:center;'>" + safe + "</div></html>");
		l.setAlignmentX(CENTER_ALIGNMENT);
		l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		l.setForeground(new Color(200, 205, 212));
		// Cap the max to the wrapped preferred size — otherwise BoxLayout stretches
		// the label into a tall block and the short paragraph floats with big gaps.
		l.setMaximumSize(l.getPreferredSize());
		return l;
	}

	private JLabel centeredLabel(String text, int size, Color color, boolean bold)
	{
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setAlignmentX(CENTER_ALIGNMENT);
		l.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
		l.setForeground(color);
		return l;
	}

	private java.awt.Component gap(int h)
	{
		return javax.swing.Box.createRigidArea(new Dimension(0, h));
	}
}
