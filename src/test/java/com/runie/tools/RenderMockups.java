package com.runie.tools;

import com.google.gson.Gson;
import com.runie.anim.AnimationService;
import com.runie.assets.ArtStyle;
import com.runie.assets.ArtStyleService;
import com.runie.assets.AssetLoader;
import com.runie.core.GachaService;
import com.runie.core.ProgressionService;
import com.runie.core.PullResult;
import com.runie.core.RunieRandom;
import com.runie.core.EggService;
import com.runie.core.XpTable;
import com.runie.creatures.CreatureRegistry;
import com.runie.overlay.AuraRenderer;
import com.runie.overlay.RunieCompanionOverlay;
import com.runie.overlay.RuniePullOverlay;
import com.runie.state.CreatureInstance;
import com.runie.state.RunieStateStore;
import com.runie.state.TestStateStores;
import com.runie.support.FakeClock;
import com.runie.support.TestRunieConfig;
import com.runie.ui.RuniePanel;
import com.runie.ui.UiContext;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.swing.JTabbedPane;

/**
 * Headless render mockups (Epic 6/7 acceptance aid): paints the REAL overlay
 * render(Graphics2D) methods and the REAL Swing panel into offscreen images,
 * over a mock game-screen backdrop, so the slice can be reviewed without a
 * live client. Run: {@code ./gradlew renderMockups}.
 *
 * <p>Not a unit test — a tool class kept in the test source set so it can use
 * the test seams (fake clock, direct executor, temp state store).
 */
public final class RenderMockups
{
	private static final int SCENE_W = 560;
	private static final int SCENE_H = 380;

	private final File outDir;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final FakeClock clock = new FakeClock(1_784_678_400_000L);
	private final TestRunieConfig config = new TestRunieConfig();

	private CreatureRegistry registry;
	private RunieStateStore store;
	private EggService eggService;
	private ProgressionService progression;
	private GachaService gacha;
	private AssetLoader assets;
	private AnimationService anim;
	private RunieCompanionOverlay companionOverlay;
	private RuniePullOverlay pullOverlay;
	private UiContext ctx;

	private RenderMockups(File outDir)
	{
		this.outDir = outDir;
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		File out = new File(args.length > 0 ? args[0] : "runie_slice_shots");
		//noinspection ResultOfMethodCallIgnored
		out.mkdirs();
		RenderMockups r = new RenderMockups(out);
		try
		{
			r.setUp();
			r.renderAll();
		}
		finally
		{
			r.executor.shutdownNow();
		}
		System.out.println("mockups written to " + out.getAbsolutePath());
	}

	private void setUp() throws Exception
	{
		Gson gson = new Gson();
		registry = new CreatureRegistry(gson);
		registry.load();
		store = TestStateStores.create(gson, executor, registry,
			Files.createTempDirectory("runie-mockups"));
		store.switchAccount(4242L);
		eggService = new EggService(store, clock);
		progression = new ProgressionService(store, registry, eggService);
		gacha = new GachaService(eggService, registry, store, clock, RunieRandom.seeded(20260717L));
		assets = new AssetLoader();
		ArtStyleService styles = new ArtStyleService(config, () -> "true", v -> { }); // pre-unlocked for mockups
		anim = new AnimationService(assets, styles, progression, config, clock, Runnable::run);
		companionOverlay = new RunieCompanionOverlay(anim, new AuraRenderer(),
			new com.runie.overlay.ShinyRenderer(), progression, registry, eggService, config, clock);
		pullOverlay = new RuniePullOverlay(registry, progression, assets, styles, config, clock,
			Runnable::run);
		ctx = new UiContext(eggService, gacha, progression,
			registry, assets, styles, store, config, pullOverlay, Runnable::run, () -> false);

		// --- seed a lived-in account: starter pulls + a few paid pulls -------
		while (gacha.getStarterPullsRemaining() > 0)
		{
			gacha.pull();
		}
		store.getState().eggs.balance = 90;
		for (int i = 0; i < 5; i++)
		{
			gacha.pull();
		}
		// own the FULL v3 42-line roster at varied levels so the collection grid
		// review shot shows every named entry (real Grubnak art, placeholder
		// tiles for the lines awaiting the real-art drop-in), with stage
		// 1/2/3 examples in every rarity tier
		String[][] seeded = {
			// Common (10)
			{"grubnak", "34"}, {"skitterscour", "76"}, {"cluckabee", "12"},
			{"woolder", "41"}, {"pindlewick", "55"},
			{"bananip", "77"}, {"grottlecap", "29"}, {"clackerdown", "48"},
			{"barkuff", "62"}, {"bouldergrum", "80"},
			// Uncommon (10)
			{"ratterbone", "79"}, {"wisplume", "40"}, {"glabbertongue", "18"},
			{"stingrilla", "62"}, {"grimshank", "83"}, {"howlrend", "36"},
			{"moankettle", "51"}, {"gillcrest", "78"}, {"knucklemaw", "22"},
			{"tidecoil", "44"},
			// Rare (8)
			{"cindermaw", "81"}, {"grithstone", "44"}, {"emberwyrmling", "23"},
			{"basilyre", "67"}, {"thornkarr", "82"}, {"zalthisk", "39"},
			{"pyrgeist", "58"}, {"dredgemaw", "12"},
			// Elite (6)
			{"duskrend", "85"}, {"talonrend", "47"}, {"nechrallis", "31"},
			{"umbrask", "72"}, {"vornathax", "88"}, {"coilfen", "53"},
			// Master (4)
			{"vaelgrim", "92"}, {"solenfyre", "64"}, {"trivandire", "41"},
			{"abyzarok", "86"},
			// Grandmaster (2)
			{"malgrave", "99"}, {"ythrax", "90"},
		};
		for (String[] s : seeded)
		{
			CreatureInstance inst = store.getState().creatures.owned
				.computeIfAbsent(s[0], k -> CreatureInstance.fresh(clock.nowMs()));
			inst.xp = XpTable.xpForLevel(Integer.parseInt(s[1])) + 500;
			inst.lifetimeXp = inst.xp;
			inst.copiesPulled = Math.max(inst.copiesPulled, 1);
		}
		// Grubnak: the featured companion — active, mid-progress (level 34)
		CreatureInstance grubnak = store.getState().creatures.owned.get("grubnak");
		grubnak.xp = XpTable.xpForLevel(34) + 8_500;
		grubnak.lifetimeXp = grubnak.xp;
		grubnak.copiesPulled = Math.max(grubnak.copiesPulled, 3);
		store.getState().creatures.active = "grubnak";
		// tidy balances + pity for a photogenic panel
		store.getState().eggs.balance = 42;
		store.getState().eggs.earnedToday = 4;
		store.getState().eggs.xpBanked = 37_500;
		store.getState().gacha.rarePity = 6;
		store.getState().gacha.elitePity = 21;
		store.getState().gacha.gmPity = 143;
		anim.refresh();
	}

	private void renderAll() throws Exception
	{
		overlayCompanionShot("overlay_companion.png", ArtStyle.KAWAII, false);
		overlayCompanionShot("overlay_companion_pixel.png", ArtStyle.PIXEL, false);
		panelShot("panel_collection.png", 0, null, 1720, true); // tall: full 42-line grid
		panelShot("panel_collection_scroll.png", 0, null, 560, false); // live-client height: scrollbar visible
		panelShot("panel_detail.png", 0, "grubnak", 800, false);
		panelShot("panel_detail_malgrave.png", 0, "malgrave", 800, false);
		pullRevealShot();
		goldenAuraShot("grubnak", "golden_aura.png");        // real art + composited radiance
		goldenAuraShot("malgrave", "golden_aura_malgrave.png"); // ANY creature: placeholder + same aura
		idleFramesStripShot();
	}

	// ------------------------------------------------------------------
	// 1 + 6: companion overlay over a mock game scene, with a CrT toast
	// ------------------------------------------------------------------

	private void overlayCompanionShot(String file, ArtStyle style, boolean golden) throws Exception
	{
		config.artStyle = style;
		anim.refresh();
		BufferedImage scene = mockScene(golden);
		Graphics2D g = scene.createGraphics();

		// warm the toast poller, then bump eggs by 1 so the toast fires
		BufferedImage scratch = new BufferedImage(200, 220, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = scratch.createGraphics();
		companionOverlay.render(sg);
		sg.dispose();
		store.getState().eggs.balance++;
		clock.advanceMs(40);

		// measure, then draw anchored bottom-right like OverlayPosition.BOTTOM_RIGHT
		BufferedImage probe = new BufferedImage(220, 240, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		Dimension d = companionOverlay.render(pg);
		pg.dispose();
		store.getState().eggs.balance--; // keep balance stable for later shots
		int x = SCENE_W - d.width - 18;
		int y = SCENE_H - d.height - 16;
		g.translate(x, y);
		store.getState().eggs.balance++; // re-fire the toast for the real paint
		companionOverlay.render(g);
		g.dispose();
		save(scene, file);
	}

	// ------------------------------------------------------------------
	// 2 + 3: the real Swing panel (collection grid / detail card)
	// ------------------------------------------------------------------

	private void panelShot(String file, int tab, String detailCreature, int height, boolean composite)
		throws Exception
	{
		config.artStyle = ArtStyle.KAWAII;
		anim.refresh();
		RuniePanel panel = new RuniePanel(ctx);
		if (detailCreature != null)
		{
			panel.showDetail(detailCreature);
		}
		selectTab(panel, tab);
		BufferedImage left = paintComponent(panel, 242, height);
		BufferedImage result = left;
		if (composite)
		{
			// composite Collection + Summon tabs + a detail card side by side
			// (Runie Token balance, Summon button (5 tokens), odds and pity, and
			// a generic family label all visible in one review shot)
			RuniePanel summonView = new RuniePanel(ctx);
			selectTab(summonView, 1);
			BufferedImage mid = paintComponent(summonView, 242, 640);
			RuniePanel detailView = new RuniePanel(ctx);
			detailView.showDetail("pyrgeist"); // "Obsidian Warrior family"
			BufferedImage right = paintComponent(detailView, 242, 640);
			result = new BufferedImage(left.getWidth() + mid.getWidth() + right.getWidth() + 24,
				height, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = result.createGraphics();
			g.setColor(new Color(24, 24, 24));
			g.fillRect(0, 0, result.getWidth(), result.getHeight());
			g.drawImage(left, 0, 0, null);
			g.drawImage(mid, left.getWidth() + 12, 0, null);
			g.drawImage(right, left.getWidth() + mid.getWidth() + 24, 0, null);
			g.dispose();
		}
		save(result, file);
	}

	// ------------------------------------------------------------------
	// 4: pull reveal cinematic, mid-reveal
	// ------------------------------------------------------------------

	private void pullRevealShot() throws Exception
	{
		// present a Grubnak duplicate so the reveal shows real art + the star
		// gain; the overlay render path is exactly what a live hatch drives
		PullResult result = new PullResult("grubnak", com.runie.creatures.Rarity.COMMON,
			false, false, 3, false, false, false, gacha.pitySnapshot());
		pullOverlay.onPull(result);                 // direct executor: presentation ready now
		BufferedImage scene = mockScene(false);
		Graphics2D g = scene.createGraphics();
		// first render anchors the presentation clock; then jump mid-reveal
		BufferedImage warm = new BufferedImage(300, 220, BufferedImage.TYPE_INT_ARGB);
		Graphics2D wg = warm.createGraphics();
		pullOverlay.render(wg);
		wg.dispose();
		clock.advanceMs(2100);                      // glow(1200) + 900ms into the reveal
		g.translate((SCENE_W - 280) / 2, 26);       // OverlayPosition.TOP_CENTER
		pullOverlay.render(g);
		g.dispose();
		pullOverlay.skip();
		pullOverlay.skip();                         // dismiss so later shots are clean
		save(scene, "pull_reveal.png");
	}

	// ------------------------------------------------------------------
	// 5: the COMPOSITED golden aura (Appendix B) — L120 prestige master.
	// Rendered twice: over Grubnak's real art AND over a placeholder-art
	// creature, proving the radiance applies to any of the 42 lines unbaked.
	// ------------------------------------------------------------------

	private void goldenAuraShot(String creatureId, String file) throws Exception
	{
		CreatureInstance star = store.getState().creatures.owned.get(creatureId);
		long savedXp = star.xp;
		int savedPrestige = star.prestigeCount;
		String savedActive = store.getState().creatures.active;
		star.prestigeCount = 1;
		star.xp = XpTable.xpForLevel(120);
		store.getState().creatures.active = creatureId;
		config.artStyle = ArtStyle.KAWAII;
		config.scale = com.runie.config.CompanionScale.LARGE;
		anim.refresh();
		// sample mid-shimmer so the radiance shows near peak alpha (period 2400ms)
		clock.advanceMs(600);

		BufferedImage scene = mockScene(true);
		Graphics2D g = scene.createGraphics();
		BufferedImage probe = new BufferedImage(300, 320, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		Dimension d = companionOverlay.render(pg);
		pg.dispose();
		g.translate(SCENE_W - d.width - 34, SCENE_H - d.height - 22);
		companionOverlay.render(g);
		g.dispose();
		save(scene, file);

		star.xp = savedXp;                          // restore mid-game progression
		star.prestigeCount = savedPrestige;
		store.getState().creatures.active = savedActive;
		config.scale = com.runie.config.CompanionScale.MEDIUM;
		anim.refresh();
	}

	// ------------------------------------------------------------------
	// 7: Grubnak's real pre-rendered kawaii idle sequence as a review strip
	// (played in-game as a ping-pong loop: 0,1,…,N-1,N-2,…,1).
	// ------------------------------------------------------------------

	private void idleFramesStripShot() throws Exception
	{
		com.runie.assets.AnimationClip idle = assets.getIdleClip("grubnak", ArtStyle.KAWAII, 1, 128);
		int cell = 150;
		int cells = idle.frameCount();
		BufferedImage strip = new BufferedImage(cells * cell + 16, cell + 58, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = strip.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(30, 30, 34));
		g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		g.setColor(new Color(230, 230, 230));
		g.drawString("Grubnak kawaii stage-1 idle — " + idle.frameCount() + " frames @ "
			+ AssetLoader.IDLE_FPS + " fps (" + idle.getPerFrameMs()
			+ " ms/frame, ms-driven ping-pong loop)", 10, 18);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		for (int i = 0; i < idle.frameCount(); i++)
		{
			BufferedImage f = idle.frame(i);
			int x = 8 + i * cell;
			g.setColor(new Color(46, 46, 52));
			g.fillRoundRect(x, 28, cell - 8, cell, 10, 10);
			g.drawImage(f, x + (cell - 8 - f.getWidth()) / 2, 28 + cell - f.getHeight() - 6, null);
			g.setColor(new Color(200, 200, 200));
			g.drawString(String.format("frame_%03d", i), x + 8, 28 + cell + 16);
		}
		g.dispose();
		save(strip, "idle_frames_strip.png");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** Simple OSRS-ish backdrop: grass, path, trees, soft vignette. */
	private BufferedImage mockScene(boolean dusk)
	{
		BufferedImage img = new BufferedImage(SCENE_W, SCENE_H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Color grassA = dusk ? new Color(46, 62, 40) : new Color(72, 96, 52);
		Color grassB = dusk ? new Color(38, 52, 34) : new Color(62, 84, 46);
		g.setPaint(new GradientPaint(0, 0, grassA, 0, SCENE_H, grassB));
		g.fillRect(0, 0, SCENE_W, SCENE_H);
		Random rnd = new Random(7);
		for (int i = 0; i < 900; i++)
		{
			int x = rnd.nextInt(SCENE_W);
			int y = rnd.nextInt(SCENE_H);
			int v = rnd.nextInt(18) - 9;
			g.setColor(new Color(clamp(grassB.getRed() + v), clamp(grassB.getGreen() + v + 6),
				clamp(grassB.getBlue() + v)));
			g.fillRect(x, y, 3, 2);
		}
		// dirt path
		g.setColor(dusk ? new Color(84, 70, 48) : new Color(122, 100, 64));
		g.fillPolygon(new int[]{-20, 150, 260, 60}, new int[]{SCENE_H, 120, 120, SCENE_H}, 4);
		g.setColor(new Color(0, 0, 0, 26));
		for (int i = 0; i < 70; i++)
		{
			g.fillRect(30 + rnd.nextInt(200), 130 + rnd.nextInt(SCENE_H - 140), 4, 2);
		}
		// trees
		tree(g, 430, 96, dusk);
		tree(g, 60, 70, dusk);
		tree(g, 330, 46, dusk);
		// soft vignette
		g.setPaint(new GradientPaint(0, SCENE_H - 90, new Color(0, 0, 0, 0),
			0, SCENE_H, new Color(0, 0, 0, 60)));
		g.fillRect(0, SCENE_H - 90, SCENE_W, 90);
		// fake chat-strip so it reads as a client screenshot
		g.setColor(new Color(0, 0, 0, 120));
		g.fillRect(0, SCENE_H - 22, 260, 22);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		g.setColor(new Color(200, 200, 255));
		g.drawString("Welcome to Old School RuneScape.", 8, SCENE_H - 7);
		g.dispose();
		return img;
	}

	private static void tree(Graphics2D g, int x, int y, boolean dusk)
	{
		g.setColor(dusk ? new Color(54, 40, 30) : new Color(80, 58, 38));
		g.fillRect(x - 5, y + 26, 10, 26);
		g.setColor(dusk ? new Color(30, 46, 30) : new Color(44, 70, 40));
		g.fillOval(x - 30, y - 14, 60, 52);
		g.setColor(dusk ? new Color(36, 54, 36) : new Color(52, 82, 46));
		g.fillOval(x - 22, y - 22, 44, 40);
	}

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}

	/** Size + layout an unrealized Swing tree, then printAll into an image. */
	private static BufferedImage paintComponent(Component c, int w, int h)
	{
		c.setSize(w, h);
		layoutTree(c);
		layoutTree(c); // second pass settles scroll/tab children
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(24, 24, 24));
		g.fillRect(0, 0, w, h);
		c.printAll(g);
		g.dispose();
		return img;
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	private static void selectTab(Container root, int index)
	{
		JTabbedPane tabs = findTabs(root);
		if (tabs != null)
		{
			tabs.setSelectedIndex(index);
		}
	}

	private static JTabbedPane findTabs(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JTabbedPane)
			{
				return (JTabbedPane) c;
			}
			if (c instanceof Container)
			{
				JTabbedPane t = findTabs((Container) c);
				if (t != null)
				{
					return t;
				}
			}
		}
		return null;
	}

	private void save(BufferedImage img, String name) throws Exception
	{
		File f = new File(outDir, name);
		ImageIO.write(img, "png", f);
		System.out.println("wrote " + f.getAbsolutePath());
	}
}
