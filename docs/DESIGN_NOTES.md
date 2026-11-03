================================================================================
CAIRN — BRAND NOTE FOR DESIGNERS
================================================================================
Version:    0.1 (draft)
Date:       2026-11-03
Status:     Working note. Nothing here is final until v1 ships.
Owner:      Project maintainer (see MAINTAINERS — TODO: create that file)
Audience:   Future designers, contributors, anyone touching the wordmark,
            icon, app UI colors, or store assets.

If you change anything in this file, add a line to the "Change log" at
the bottom. Do not silently edit the spec.


--------------------------------------------------------------------------------
0. ONE-PARAGRAPH BRIEF
--------------------------------------------------------------------------------
Cairn is an open-source mobile app that lets a group of people share
positions, pins, routes, areas and short messages over a LoRa mesh
(Meshtastic radios), with no server, no account, and no internet. The
audience is hikers, volunteer search-and-rescue crews, event staff, and
festival-goers — people who are outdoors, in bad light, on cheap phones,
possibly in an emergency.

The brand should say: quiet, serious, outdoors, trustworthy, not a
startup, not a crypto project, not a hospital app. It should survive
being printed on a laminated card stapled to a trailhead, and it should
survive being a 16×16 favicon.


--------------------------------------------------------------------------------
1. NAME
--------------------------------------------------------------------------------
Spelling:      Cairn
Pronunciation: /kɛrn/ — one syllable, rhymes with "fern".
Meaning:       A cairn is a man-made pile of stones used as a trail
               marker, summit marker, or memorial. Gaelic "carn".
               The metaphor is exact: cairns are the original
               no-signal, no-server wayfinding markers.

Capitalization rule (commit to this; do not mix forms in one document):
  - "Cairn" capitalized: start of a sentence, README H1, app display
    name, wordmark.
  - "cairn" lowercase: repo name, package names, binary name, env vars,
    domains. Anything a machine reads.
  - Never "CAIRN" in prose. All-caps is only acceptable as a logo
    treatment, not as text.
  - Plural: do not write "cairns" for multiple instances. Write
    "Cairn instances" or "the apps".

Do not stylize the spelling. No "Kairn", "Cayrn", "Cairnn", "C'airn",
"C@irn". The Reddit-style letter-drop does not work here: "cairn" has
no spare letters, every one is load-bearing. The wit lives in the
typography (see §4), not the orthography.


--------------------------------------------------------------------------------
2. COLOR SYSTEM
--------------------------------------------------------------------------------
Three colors. That is the whole palette. Do not add a fourth without
a written reason in this file.

  Name          Hex        Role
  ----------    --------   --------------------------------------------
  Slate stone   #3D4A52    Primary. Backgrounds, body text, icon tile.
  Trail orange  #D9631E    Accent ONLY. Stone stack, pins, emergency,
                           active states, the dot of the "i".
  Bone          #F4F1EA    Light-mode background, text on slate.

Rules:
  - Trail orange is an ACCENT. Never use it as a large background area.
    It is for small areas: pins, icons, active state, the stone stack.
  - Bone, not white (#FFFFFF). Pure white looks like every other SaaS
    app and glares in sunlight. Bone is warmer and easier on the eye
    outdoors.
  - Slate, not black. Pure black is harsh on OLED and looks cheap.
  - Light mode primary: bone background, slate text, orange accents.
  - Dark mode primary: slate background, bone text, orange accents.
  - Do not introduce gradients. Every LoRa/mapping project on GitHub
    uses a blue-to-purple gradient; it makes them indistinguishable.

Accessibility — run these checks before shipping any screen:
  [ ] #F4F1EA on #3D4A52 — expect ~9:1. Good for body text.
  [ ] #D9631E on #F4F1EA — expect ~3.2:1. Large text and icons only,
      NOT body text. If you need orange body text, darken it first and
      record the new hex in this file.
  [ ] Emergency state must be distinguishable from a normal pin WITHOUT
      color. Use a different shape or an icon. About 8% of men are
      colorblind, and SAR users may be one of them.

The three colors, as OKLCH-ish descriptions (for designers working in
modern tools):
  Slate stone  — very dark, slightly cool grey-blue
  Trail orange — mid-saturation, warm, slightly earthy (not neon)
  Bone         — very light, very slightly warm off-white


--------------------------------------------------------------------------------
3. TYPOGRAPHY
--------------------------------------------------------------------------------
Primary typeface:  Space Grotesk (SIL OFL, on Google Fonts)
  - Use Medium (weight 500) for the wordmark and headings.
  - Use Regular (400) for body text where a second weight is needed.
  - Space Grotesk is geometric with a small amount of quirk (the "r"
    and "a" have personality). It is recognizable without shouting.
  - Do not push tracking past -3%; the "a" and "i" will collide.

Secondary typeface (for UI text, docs, long body copy):
  - Inter, OR Atkinson Hyperlegible, OR IBM Plex Sans.
  - Pick ONE and commit. Do not mix.
  - Suggested default: Inter. Atkinson Hyperlegible if the project
    decides to lean hard into the accessibility angle (it was designed
    by the Braille Institute for maximum glyph disambiguation, which
    matters for a safety-critical app).
  - IBM Plex Sans if you also want a matching Mono for code blocks and
    the terminal wordmark; Plex Mono is excellent.

Not on the list, on purpose:
  - Anything with a mountain or topographic gimmick.
  - Rounded-cute fonts. Wrong audience.
  - Anything under a license that cannot be shipped to contributors in
    docs/. Every font above is SIL OFL or equivalent.


--------------------------------------------------------------------------------
4. THE WORDMARK
--------------------------------------------------------------------------------
Word:        Cairn
Case:        Capital C, rest lowercase. ("Cairn", not "CAIRN", not "cairn"
             for the wordmark itself — lowercase "cairn" is allowed as an
             alternate modern treatment ONLY if a designer argues for it
             in writing in this file.)
Font:        Space Grotesk Medium (500)
Tracking:    -1% to -2%. Do not exceed -3%.
Weight:      Medium. Regular is too thin under the stone stack.
Colors:      Word in slate #3D4A52. Stone stack in trail orange #D9631E.
             On slate backgrounds: word in bone #F4F1EA, stack stays orange.

THE IDEA (the whole brand, in one sentence):
  The dot over the "i" is replaced by a small stack of three stones —
  a cairn — in trail orange. Nothing else. No mountain, no radio waves,
  no map pin. The stones are the story.

--------------------------------------------------------------------------------
4a. THE STONE STACK — EXACT SPEC
--------------------------------------------------------------------------------
Three pebbles, stacked vertically, forming a small cairn in the space
where the dot of the "i" would normally sit.

Sizing (relative to the normal dot diameter D):
  Stone 1 (bottom): width ~1.0 D, sits where the dot's bottom edge
                    would be. Widest.
  Stone 2 (middle): width ~0.75 D, centered with a tiny horizontal
                    jitter (±5% of D) so it does not look machine-stacked.
  Stone 3 (top):    width ~0.5 D, offset ~15% of D to one side (pick
                    RIGHT; humans stack slightly off-center).
  Total stack height: ~1.5–1.6 D.
  Total stack width:  ~1.0 D. MUST NOT be wider than the normal dot,
                     or it starts to look like a snowman.

Shape:
  Rounded pebbles, not perfect circles, not sharp polygons.
  Corner radius ~2–3% of stone width.

Color:
  All three stones in trail orange #D9631E. Do not mix slate and orange
  within the stack — that reads as two objects instead of one cairn.

Stem of the "i":
  Slightly thicker than Space Grotesk default — use Medium weight for
  the whole wordmark. A hairline stem under a stone stack looks like a
  lollipop.

--------------------------------------------------------------------------------
4b. SMALL-SIZE FALLBACK
--------------------------------------------------------------------------------
At 16×16 px, three stones collapse into an orange smudge. Ship a 16px
variant where the stack is replaced by a SINGLE orange dot. Nobody will
notice the swap; the favicon stays crisp.

Test before shipping: render the wordmark at 100%, zoom out until
"Cairn" is ~24 px tall on screen, and squint. If the three stones still
read as a small stack, ship. If they smudge, drop stone 3 and use a
TWO-stone stack (still a real cairn, just a smaller marker) — it
survives smaller sizes better.

--------------------------------------------------------------------------------
4c. THE CIRCLED ALTERNATIVE (do not use without discussion)
--------------------------------------------------------------------------------
Replacing the ENTIRE "i" (stem + dot) with a tall thin stone stack is a
bigger visual pun. It works at 128px+ (app-store hero, splash screen)
but:
  - At 32px it becomes an unreadable column.
  - Next to body text containing a real "i", it looks like a typo.
  - Some viewers find the "hole" where the "i" used to be unstable.
Use it ONLY for splash screens and hero images. Never for the primary
wordmark. The primary wordmark is the three-stone dot (§4a).

--------------------------------------------------------------------------------
4d. CLEAR SPACE AND MINIMUM SIZE
--------------------------------------------------------------------------------
Clear space: at least the cap-height of the "C" on all four sides.
            Non-negotiable. This is what makes a wordmark look designed
            rather than dropped onto a page.
Minimum size (full stack version): 24 px cap height.
Minimum size (single-dot fallback): 16 px.


--------------------------------------------------------------------------------
5. THE ICON
--------------------------------------------------------------------------------
The app icon is NOT the wordmark crammed into a square. It is:

  Slate background (#3D4A52), centered three-stone cairn in trail
  orange (#D9631E). No letter. No wordmark. Just the stack.

At 16×16: single orange dot on slate. The fallback from §4b.

Deliverables the project needs:
  cairn-icon-512.png   — square, full stack, slate background
  cairn-icon-256.png
  cairn-icon-128.png
  cairn-icon-64.png
  cairn-icon-32.png    — verify the three stones still read
  cairn-icon-16.png    — single orange dot fallback
  cairn-wordmark-light.svg — slate word, orange stack, bone background
  cairn-wordmark-dark.svg  — bone word, orange stack, slate background
  cairn-social-1280x640.png — slate background, centered wordmark,
                              tagline underneath in bone at 32px

The stack alone is recognizable at 32px, on-theme, and does not try to
cram a word into a square. The wordmark is for places with horizontal
room; the stack is for places without.


--------------------------------------------------------------------------------
6. TAGLINE AND VOICE
--------------------------------------------------------------------------------
Tagline (use verbatim, everywhere):
  "A shared trail marker for groups with no signal."

GitHub description (the literal version, for search):
  "Serverless group map over a LoRa mesh. No server, no account, works
  offline."

README first lines:
  # Cairn
  **Serverless group map over a LoRa mesh.** Positions, pins, routes and
  short messages, shared phone-to-phone via Meshtastic radios. No
  server, no account, fully offline once the map tiles are on the phone.
  *Pronounced "kern" — a cairn is a pile of stones used as a trail
  marker.*

Voice:
  - Plain. Short sentences. No buzzwords.
  - Say what it does. Say what it does not do. Both are useful.
  - Never "revolutionary", "leverages", "platform", "seamless".
  - Emergency-adjacent copy must be calm, not alarmist. The emergency
    BUTTON is orange and prominent; the WORDING around it is quiet and
    factual.


--------------------------------------------------------------------------------
7. WHAT NOT TO DO
--------------------------------------------------------------------------------
  [ ] No gradients. (Repeated because everyone ignores it.)
  [ ] No stylized radio waves.
  [ ] No map pins as the logo.
  [ ] No mountain silhouettes.
  [ ] No thin line-art. It vanishes at small sizes on cheap phones in
      sunlight — exactly where users will be.
  [ ] No more than two colors in the mark itself. Slate + orange. Done.
  [ ] No pure white (#FFFFFF) backgrounds. Use bone (#F4F1EA).
  [ ] No pure black (#000000) text. Use slate (#3D4A52).
  [ ] No emoji in the GitHub description. At most one emoji anywhere,
      and only if it is the cairn glyph.
  [ ] No spelling stylization of "Cairn" (see §1).


--------------------------------------------------------------------------------
8. DELIVERABLES CHECKLIST (before v1 ships)
--------------------------------------------------------------------------------
  [ ] All icon sizes listed in §5 exported and reviewed at 100%.
  [ ] Light and dark wordmark SVGs.
  [ ] Social preview image (1280×640) uploaded to GitHub
      (Settings → General → Social preview).
  [ ] The 16px favicon squint test passed.
  [ ] The 24px wordmark squint test passed.
  [ ] Contrast checks in §2 passed (record results in this file).
  [ ] Emergency state distinguishable without color (shape/icon, not
      just red-vs-orange).
  [ ] All fonts and license files noted in docs/ for contributors.
  [ ] This file reviewed and marked "Version 1.0 (final)".


--------------------------------------------------------------------------------
9. OPEN QUESTIONS FOR DESIGNERS
--------------------------------------------------------------------------------
  - Space Grotesk Medium vs Regular for the wordmark — test both under
    the stone stack before deciding.
  - Inter vs Atkinson Hyperlegible for the app UI. Atkinson is more
    principled for a safety app; Inter is more familiar. Pick one and
    argue for it in §3.
  - Does the stone stack need to be redrawn as a bespoke glyph, or is a
    geometric construction from three rounded rectangles enough? Try
    the geometric version first — it will probably be enough.
  - Small-size fallback: single dot vs two-stone stack at 16px. Test
    both on a real phone in sunlight.


--------------------------------------------------------------------------------
10. CHANGE LOG
--------------------------------------------------------------------------------
  2026-11-03  v0.1  Initial note. Wordmark spec (§4), colors (§2),
                    typography (§3), icon (§5), voice (§6). Written
                    after the Slate / Trail orange / Bone palette and
                    the Space Grotesk wordmark were agreed. Stone-stack
                    spec still being validated at 16px.
================================================================================
