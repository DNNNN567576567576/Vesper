# Vesper style

Paste this into any chat when you want something in the Vesper look.

Build this in the "Vesper" style: my own aesthetic from my Android app Vesper. Follow it closely.

## Mood
Mainly purple, pixel art, eerie and a bit rotten, moody but strangely cozy. Like an old grimoire left in a damp candlelit crypt that is somehow alive. Mysterious, never cute, never corporate. Dark everywhere; light only comes from glows, embers, candles and moons.

## Palette
- Backgrounds: near-black violet #07030B / #08040C / #06020A, surfaces #120A19 / #1A1026 / #241432, borders #2A1A38 / #39234C.
- Main colours: Primary #7540B8, Lavender #B69AFF, Magenta #D45AAB (accent only, used sparingly).
- Text: #F0E8FA / #E9E0F5, dim #A99AB9, muted #6F6080.
- Rot and decay: mould #1E0A24 / #4A1650 / #7A2A6A, char #0A0508, burnt rim #301426, rot spots: black centre #06020A with magenta-purple rim.
- Embers stay dark and dusky (#3A1430 -> #5A1E48 -> #7A2A5E), never bright. Glows are soft, additive, low alpha.

## Type
- Titles: Jacquard 24 (pixel blackletter), lavender #B69AFF with a hard un-blurred shadow #6E1F5A offset 3,3.
- Reading text: VT323 (old terminal feel).
- Small UI labels / nav: Press Start 2P, tiny and uppercase.
- No modern sans in the visible UI.

## Pixel rendering
- Real chunky pixels: draw decoration into a LOW-RES canvas (about 180 px wide for a phone) and scale it up with no smoothing. Circles are pixel discs, lines are 1-px steps, rings are dotted pixel circles.
- Layers per screen: dark base -> drifting fog blobs -> dark stains -> details (veins, cracks, mould specks, rot spots) -> content -> embers/spores -> a 2x2 dither at ~20% -> a dark vignette.
- Corners: slightly rounded or pixel-notched, never sharp modern cards and never glossy.

## Motion (everything breathes, slowly)
- Fog drifts, stains swell and shrink, the vignette breathes, motes and spores rise, embers flicker, dotted rings turn slowly.
- A "heartbeat" pulse that travels along veins outward from a centre, brightening what it passes.
- Parallax: the background moves slower than the content when scrolling.
- Actions have little rituals: deleting burns a page away with embers and ash, finishing a task snuffs a candle with smoke.
- Slow and calm (seconds, not milliseconds). Nothing bouncy or flashy.

## Recurring motifs
Veins and nerves, a central brain that feeds everything, rot and mould, burned paper with ragged charred edges, pins, candles, moons and moon phases, spores, sigils, stone tablets, grimoire pages.

## Components
- Lists are objects in a world, not cards: torn pinned pages, candles on a ledge, burned-paper plaques, stone tablets.
- The add button is a dark round "sigil": dark disc, thin violet rim, a slowly turning dotted ring, a soft pulsing glow and a lavender pixel plus. Never a big bright block.
- Dialogs: dark panel #140B1C, 2px #3A2450 border, rounded, VT323 text, blackletter title; destructive actions in magenta.
- Icons are hand-made pixel grids in the palette.

## Words
Short, lowercase-friendly, a little poetic: "nothing kept yet.", "keep something...", "Burn this page? Gone for good. No undo." No exclamation marks, no corporate phrasing.

## Rules
- Readability first: decoration lives at the edges, text always sits on clean dark (or pale paper) areas.
- Fully offline: bundle fonts and art, no network.
- Each screen gets its own world (pages, candles, constellation, burned paper, moons) but shares this palette, type, pixel scale and motion, so it all feels like one place.
- Show me 2-3 animated mockups before building anything, and let me pick.
