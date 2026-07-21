package com.runie.ui;

import com.runie.core.StarTier;
import com.runie.creatures.CreatureDefinition;
import com.runie.overlay.RuniePullOverlay;
import com.runie.overlay.ShinyRenderer;
import com.runie.state.CreatureInstance;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;

/**
 * Collection grid (architecture §8 tab 2): all 42 creature lines, owned cards
 * with art + level chip + duplicate stars in their tier color (+ shiny ✦ tag
 * and runtime shiny tint), unowned as darkened silhouette + "???", filterable
 * by ownership / rarity / active. Click → detail card.
 */
class CollectionGridPanel extends JPanel
{
	private static final String[] FILTERS = {
		"All", "Owned", "Unowned", "Active", "Common", "Uncommon", "Rare", "Elite", "Master", "Grandmaster",
	};
	private static final int THUMB_PX = 52;

	private final UiContext ctx;
	private final Consumer<String> onSelect;
	private final JComboBox<String> filterBox = new JComboBox<>(FILTERS);
	private final JPanel grid = new JPanel();
	private final JLabel countLabel = new JLabel();
	private final Map<String, BufferedImage> silhouetteCache = new HashMap<>();
	private final Map<String, BufferedImage> shinyThumbCache = new HashMap<>();

	CollectionGridPanel(UiContext ctx, Consumer<String> onSelect)
	{
		this.ctx = ctx;
		this.onSelect = onSelect;
		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBox.setFocusable(false);
		filterBox.addActionListener(e -> rebuild());
		top.add(filterBox, BorderLayout.CENTER);
		countLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		countLabel.setFont(countLabel.getFont().deriveFont(11f));
		top.add(countLabel, BorderLayout.EAST);
		add(top, BorderLayout.NORTH);

		grid.setLayout(new GridLayout(0, 3, 6, 6));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(grid, BorderLayout.CENTER);
	}

	void refresh()
	{
		rebuild();
	}

	private void rebuild()
	{
		grid.removeAll();
		String filter = (String) filterBox.getSelectedItem();
		int owned = 0;
		int shown = 0;
		for (CreatureDefinition def : ctx.registry.all())
		{
			boolean isOwned = ctx.progressionService.isOwned(def.getId());
			if (isOwned)
			{
				owned++;
			}
			if (!matches(filter, def, isOwned))
			{
				continue;
			}
			grid.add(card(def, isOwned));
			shown++;
		}
		// pad the last row so GridLayout keeps card sizes stable
		for (int i = shown % 3; i != 0 && i < 3; i++)
		{
			JPanel pad = new JPanel();
			pad.setBackground(ColorScheme.DARK_GRAY_COLOR);
			grid.add(pad);
		}
		countLabel.setText(owned + "/" + ctx.registry.size());
		grid.revalidate();
		grid.repaint();
	}

	private boolean matches(String filter, CreatureDefinition def, boolean isOwned)
	{
		if (filter == null || "All".equals(filter))
		{
			return true;
		}
		switch (filter)
		{
			case "Owned":
				return isOwned;
			case "Unowned":
				return !isOwned;
			case "Active":
				return ctx.progressionService.getActiveCreatureId()
					.map(a -> a.equals(def.getId())).orElse(false);
			default:
				return def.getRarity().getDisplayName().equals(filter);
		}
	}

	private JPanel card(CreatureDefinition def, boolean isOwned)
	{
		Color rc = RuniePullOverlay.rarityColor(def.getRarity());
		boolean isActive = ctx.progressionService.getActiveCreatureId()
			.map(a -> a.equals(def.getId())).orElse(false);

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(isActive ? ColorScheme.BRAND_ORANGE : rc, isActive ? 2 : 1),
			BorderFactory.createEmptyBorder(3, 2, 3, 2)));

		CreatureInstance inst = isOwned
			? ctx.progressionService.getCreature(def.getId()).orElse(null) : null;
		boolean shiny = inst != null && inst.shiny;
		int stage = isOwned ? Math.max(1, ctx.progressionService.currentStageOf(def.getId())) : 1;
		BufferedImage art = ctx.assetLoader.getThumbnail(def.getId(), ctx.artStyleService.effectiveStyle(), stage, THUMB_PX);
		BufferedImage shown = art;
		if (!isOwned && art != null)
		{
			shown = silhouetteCache.computeIfAbsent(def.getId() + '|' + ctx.artStyleService.effectiveStyle(),
				k -> silhouette(art));
		}
		else if (shiny && art != null)
		{
			// runtime shiny tint (rework part 4) — cached, no per-creature art
			shown = shinyThumbCache.computeIfAbsent(
				def.getId() + '|' + ctx.artStyleService.effectiveStyle() + '|' + stage,
				k -> ShinyRenderer.applyStaticShiny(art));
		}
		JLabel pic = shown != null
			? new JLabel(new javax.swing.ImageIcon(shown))
			: new JLabel("?", SwingConstants.CENTER);
		pic.setAlignmentX(CENTER_ALIGNMENT);
		pic.setPreferredSize(new Dimension(THUMB_PX + 4, THUMB_PX + 4));
		card.add(pic);

		String label = isOwned ? (shiny ? "\u2726 " + def.getName() : def.getName()) : "???";
		JLabel name = new JLabel(label, SwingConstants.CENTER);
		name.setAlignmentX(CENTER_ALIGNMENT);
		name.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		name.setForeground(isOwned ? (shiny ? new Color(160, 235, 255) : Color.WHITE)
			: ColorScheme.LIGHT_GRAY_COLOR);
		card.add(name);

		// duplicate stars in the current tier's color (rework part 3)
		int starCount = inst != null ? inst.stars : 0;
		StarTier tier = StarTier.tierFor(starCount);
		if (tier != null)
		{
			JLabel stars = new JLabel(starString(StarTier.starsInTier(starCount)), SwingConstants.CENTER);
			stars.setAlignmentX(CENTER_ALIGNMENT);
			stars.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
			stars.setForeground(tier.getColor());
			stars.setToolTipText(starCount + " star" + (starCount > 1 ? "s" : "") + " (" + tier.getDisplayName() + ")");
			card.add(stars);
		}

		JLabel sub = new JLabel(isOwned
			? "Lv " + ctx.progressionService.levelOf(def.getId()) + (isActive ? " ★" : "")
			: def.getRarity().getDisplayName(), SwingConstants.CENTER);
		sub.setAlignmentX(CENTER_ALIGNMENT);
		sub.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		sub.setForeground(isActive ? ColorScheme.BRAND_ORANGE : rc);
		card.add(sub);

		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onSelect.accept(def.getId());
			}
		});
		return card;
	}

	private static String starString(int n)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++)
		{
			sb.append('\u2605');
		}
		return sb.toString();
	}

	/** Darkened unowned silhouette (cached; built once on the EDT). */
	private static BufferedImage silhouette(BufferedImage src)
	{
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.setComposite(AlphaComposite.SrcIn.derive(0.92f));
		g.setColor(new Color(18, 18, 22));
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		g.dispose();
		return out;
	}
}
