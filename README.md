# Pantry AI

Android app that tracks your food pantry, with a small language model running **on the phone**.

- **Recipes** — paste a link (reads the site's recipe data) or any messy text; ingredients are extracted and matched.
- **I made this** — removes what you used from the pantry, converting units and sizes (3 garlic cloves from a bulb → 0.3 bulb left; 2 large eggs from medium eggs → 2 medium).
- **Scan receipts** — on-device OCR (ML Kit) + receipt parser for Dutch supermarkets; the AI decodes abbreviations ("HV MLK" → semi-skimmed milk). If a quantity can't be read, the app asks you.
- **Add manually** — type "1 bol knoflook" or "10 large eggs" and it understands.
- **Shopping list** — pick one or more recipes and portions; needs are summed, the pantry is subtracted and amounts are rounded to what you can buy (bulbs, cans, bunches).

## How the "smart" part works

1. A curated food knowledge base (`app/src/main/assets/ingredients.json`, ~230 ingredients) with Dutch + English names and synonyms, piece weights, densities, egg sizes and named units (clove/bulb, can, bunch…). This handles conversions deterministically, so numbers are never hallucinated.
2. Fuzzy matching: plurals, Dutch compounds (scharreleieren → egg), typos (knoflok → garlic), context (1 tsp paprika → paprika powder).
3. On-device LLM (MediaPipe LLM Inference, default Qwen2.5-0.5B-Instruct, ~550 MB, downloaded in-app) for the fuzzy cases: is "X" the same food as "Y"? what is this abbreviated receipt line? where are the ingredients in this text? Every decision is remembered (see AI tab → "What the app has learned") and can be corrected.

## Build

Pushing to `main` builds the APK with GitHub Actions (see `.github/workflows/build.yml`).
Locally: open in Android Studio, or `gradle assembleRelease` with Android SDK 35.
