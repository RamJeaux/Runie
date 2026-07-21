# Runie

A cozy, collectible creature companion for [RuneLite](https://runelite.net). Runie turns your normal Old School RuneScape play into a lighthearted local metagame: earn eggs just by playing, hatch them to discover one of 40 original creatures, and raise your companion as it levels and evolves alongside your own XP.

Runie is free, fully offline, cosmetic-only, and gives no gameplay advantage of any kind.

---

> **Created using intellectual property belonging to Jagex Limited under the terms of Jagex's Fan Content Policy. This content is not endorsed by or affiliated with Jagex.**
>
> This is the attribution wording required by [Jagex's Fan Content Policy](https://legal.jagex.com/docs/policies/fan-content-policy) (§8.1). Runie is an unofficial fan project. It is not made, endorsed, sponsored, or approved by Jagex Ltd. "RuneScape" and "Old School RuneScape" are trademarks of Jagex Ltd, used here only to describe compatibility.

---

## Features

- **40 original creatures** — each a hand-made, stylized creature inspired by a real OSRS species, with a 3-stage evolution line that follows that species' family, ending in an original apex form.
- **Evolution codex** — every creature has its own lore, written for each evolution stage and tied to its OSRS-inspired lineage. Read a stage once your companion has grown into it; earlier stages and the golden form each have their own entry.
- **On-screen companion** — your active creature keeps you company while you play, rendered as a RuneLite overlay with a hand-built breathing idle animation.
- **XP-mirrored leveling** — your companion grows as you gain XP through completely normal play. Nothing is automated and nothing plays the game for you.
- **Hatch with fully published odds** — spend eggs (earned only through play) to hatch a new creature. Every chance-to-find is published in full inside the plugin, and a visible pity counter guarantees progress. No real money is involved, ever.
- **Duplicates make a creature shine** — hatching a creature you already own upgrades it through star tiers (up to five stars) and carries a small, rising chance to turn it into a rare **Shiny** variant, so a duplicate is never a total loss.
- **Prestige & golden aura** — take a maxed companion to level 120 for a golden aura. Evolving and mastering creatures also grants eggs. Purely cosmetic, like everything else in Runie.

## How it works — earning, hatching & rates

Everything below is the plugin's actual configuration, and every rate is also shown transparently inside Runie. There is no daily cap and no real money anywhere in the loop.

### Earning eggs

You earn **eggs** automatically as you gain normal OSRS XP. The XP cost rises through the day and resets daily (UTC) — there is no cap, so dedicated play is never shut off, just gently curved:

| Egg earned that day | XP required |
|---|---|
| 1st – 3rd | 50,000 each |
| 4th – 5th | 100,000 each |
| 6th – 8th | 250,000 each |
| 9th onward | 500,000 each |

Evolving and mastering creatures grant bonus eggs on top of XP:

- **+1 egg** each time a creature evolves a stage (once per stage, ever).
- **+5 eggs** the first time a creature reaches the golden **Level 120** mastery (once per creature) — a large reward for a serious grind.

### Hatching

Each hatch costs **1 egg** and reveals a creature. New creatures join your collection; a creature you already own becomes a duplicate (see Stars & Shiny). Your **first 3 hatches are guaranteed new** creatures, so you always start with a small collection before duplicates can appear.

**Drop rates per hatch:**

| Rarity | Chance |
|---|---|
| Common | 63% |
| Uncommon | 23% |
| Rare | 9% |
| Elite | 3.5% |
| Master | 1.1% |
| Grandmaster | 0.4% |

**Pity (guaranteed progress, shown as a neutral counter in-plugin):**

- **Rare or better** at least once every **10** hatches.
- **Elite or better** at least once every **30** hatches.
- **Grandmaster** odds triple (0.4% → 1.2%) after **150** hatches without one, and a Grandmaster is **guaranteed by the 300th**.

### Duplicates — Stars & Shiny

Duplicates are never wasted. Each duplicate adds one **star**, climbing through five colored tiers (five stars each, 25 total):

**Bronze → Silver → Gold → Red → Pink.**

Each duplicate also rolls a rare chance to turn that creature **Shiny** — a permanent shimmer and a ✦ tag (purely cosmetic). The chance scales with the creature's current star tier:

| Current tier | Shiny chance per duplicate |
|---|---|
| Bronze (1–5★) | 0.10% |
| Silver (6–10★) | 0.20% |
| Gold (11–15★) | 0.35% |
| Red (16–20★) | 0.50% |
| Pink (21–25★) | 0.75% |
| Maxed (25★) | 1.00% |

Once a creature is maxed at 25 stars, further duplicates keep rolling the 1.00% Shiny chance.

## Original art — no Jagex assets

**All creature art in Runie is 100% original.** Every sprite and animation was drawn from scratch for this plugin in its own cute/retro-pixel style. The creatures are *inspired by* Old School RuneScape's bestiary — they are meant to be recognizable as loving homages — but **nothing is copied**: **no Jagex sprites, models, textures, sounds, cache data, or other game assets are bundled in, extracted for, or redistributed by this plugin.** Creature names are original coinages; no OSRS boss or unique NPC names are used.

The plugin icon (`icon.png` at the repo root, within the Plugin Hub's 48×72 px limit) is original Runie art.

## No monetization. Nothing to buy, ever.

- Runie contains **no real-money purchases, no microtransactions, no donations-for-content, no ads, and no NFTs** — and never will.
- **Eggs** are earned exclusively through your own normal OSRS play. There is no way to buy, trade, or transfer them.
- Hatching is a free cosmetic unlock: nothing of real-world value changes hands, **every drop rate and pity threshold is published transparently in the plugin**, and duplicates always advance a creature's stars (and its Shiny chance) — so no hatch is ever wasted.

## No automation. No advantage.

Runie never interacts with the game on your behalf: it sends no input, adds no menu entries, alters no interfaces or click zones, and provides no combat, skilling, or economic assistance. It is a purely visual companion layered on top of your client, designed to comply with [Jagex's Third-Party Client Guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1).

## Privacy — local only

All Runie data (your collection, eggs, levels, settings) is stored **locally on your computer as JSON**. The plugin makes **zero network requests** and collects **no telemetry, analytics, or personal data**. Nothing ever leaves your machine.

## Installation

Once available on the Plugin Hub:

1. Open RuneLite and click the wrench icon (Configuration).
2. Click the **Plugin Hub** button at the top of the plugin list.
3. Search for **"Runie"** and click **Install**.

## Building from source

Requirements: JDK 11 and a clone of this repository.

```bash
git clone https://github.com/ramjeaux/runie.git
cd runie
./gradlew build
```

To run a development client with the plugin loaded, run the `RuniePluginTest` class from your IDE (IntelliJ IDEA Community Edition recommended), or:

```bash
./gradlew run
```

## License

This project's code is licensed under the [BSD 2-Clause License](LICENSE).

The bundled creature artwork is original work created for the Runie project (AI-assisted) and is distributed with the plugin for use within RuneLite. The Fan Content attribution above applies to the project as a whole.

## Credits

- **Art:** Runie project — original art (AI-assisted) — all 40 creature lines, evolutions, and apex forms
- **Code:** Serpent Sith
- Built on [RuneLite](https://github.com/runelite/runelite), the open-source OSRS client
- Thanks to the RuneLite team and Plugin Hub reviewers
- Old School RuneScape is created and owned by Jagex Ltd — this plugin exists because their world is worth celebrating

## Disclaimer

Runie is a fan-made project provided "as is", without warranty of any kind. It is **not affiliated with, endorsed by, sponsored by, or approved by Jagex Ltd or the RuneLite developers**. Use of RuneLite and its plugins is subject to Jagex's Third-Party Client Guidelines; you are responsible for your own account.
