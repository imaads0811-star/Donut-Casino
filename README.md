# Donut Casino (Fabric 1.21.11, client-side)

Players /pay you 1M-25M -> wheel spins with PUBLISHED odds -> winnings auto-paid via /pay.

## Safety
- Starts OFF. Press K to turn on.
- Only runs on donutsmp.
- Only reacts to server system messages (not player chat), so payments can't be faked by typing in chat.
- Payments under 1M or over 25M are kept as donations (no spin, no refund).
- Max payout cap 600M (= 25M x 24x, never alters a displayed result).
- Every spin is logged in config/donutcasino-log.txt (for disputes).
- /ctest 5m simulates a spin with NO real payment. /codds prints the odds.

## Change the odds
Edit MULT and WEIGHT at the top of DonutCasinoClient.java. The HUD and /codds update automatically.

## Build the jar with GitHub (no installs)
1. Download the 1.21.11 template from https://fabricmc.net/develop/ and unzip it.
2. Copy this project's src/ and .github/ folders into it (replace src/), and keep the template's gradle.properties numbers.
3. Create a free GitHub repo, upload everything, go to Actions -> build -> Run workflow.
4. Download the artifact "donutcasino-jar" -> use the jar WITHOUT "-sources" in its name -> put in mods folder.

## Pictures on the wheel
Edit the ICONS array (same order as MULT) to use any item.
