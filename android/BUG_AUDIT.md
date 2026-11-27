# Android and live-site audit

Audited on 2026-11-27 against the checked-in `public/` and `src/` website, the deployed site at `https://monster-hopper.pages.dev`, its configured API at `https://monster-hopper-api.vercel.app`, the Android project, and the current GitHub Actions workflow.

## Website and deployment findings

- The live site is a single-page dashboard with a separate guide, a public server browser, local attempted-server tracking, “No Boss” skip, private-server invite opening, interval controls, and an opt-in Auto 15 Min Start schedule. The schedule runs five-minute sessions at IST quarter-hour boundaries. The live API's `/api/servers` returned real Roblox server records (HTTP 206 partial results during Roblox cooldown), including Job IDs, `playing`, capacity, ping and FPS.
- The deployed Pages site and Vercel API were reachable during the audit. The Vercel API serves the server endpoint; health/status routes observed in earlier deployment checks were not available there. The website's displayed API metadata and README deployment notes are stale relative to the live Pages/Vercel arrangement.
- Checked-in website code is a Node/Express application with direct Roblox server fetches, validation, selection, launch-agent and hop-limit modules. Its Windows local agent is a website launch mechanism; it cannot be used by Android. The Android app therefore uses Roblox's public servers endpoint directly and uses Android intents for best-effort app handoff.
- The website and Android use different history semantics: the website expires attempted IDs 15 minutes after a successful launch; the live schedule separately runs at quarter-hour boundaries. Android keeps attempted IDs until the next local quarter-hour reset and implements the opt-in scheduled sessions at IST quarter-hours.

## Bugs found and fixes in this Android pass

- API throttling could interpret Roblox's absolute `x-ratelimit-reset` timestamp as a relative delay. It is now converted from epoch time; `x-ratelimit-reset-after` supports fractional seconds.
- Auto-Hop selected only from the current API page. It now scans up to two real pages (up to 200 instances), globally sorts by player count, and excludes full, malformed, queued, failed, skipped, and configured visited instances.
- The hop interval setter could persist the prior value. Preset and custom intervals now persist the selected value (5–120 seconds).
- Quarter-hour history expiration and IST scheduled-session rules are extracted into pure Java helpers with boundary tests.
- Auto-Hop now exposes pause/resume, skip, refresh, configurable failure limits, countdown state, and a bounded local activity history. The draggable overlay has a compact expanded control panel and persists its position.
- Join history distinguishes requested, failed, and skipped handoffs. It does not claim that Roblox joined a server; the Android intent result cannot confirm Roblox's eventual session state.
- Private-server links/codes are validated and opened using Roblox's share-link URI. The UI reports an open request and handles a missing Roblox package.
- Schedule recovery was corrected so restarting a scheduled service does not silently turn the schedule into manual Auto-Hop. Manual Auto-Hop recovery follows the saved preference.

## Limits and verification notes

- Roblox's public API may return partial data or rate-limit requests. Results are real records only; Android does not fabricate rows. Pagination and retry behavior remain subject to Roblox's cursor and cooldown responses.
- Android cannot reliably prove that a deep link reached or joined the intended Roblox instance. Background activity launches can also be restricted by Android; the foreground service/notification and optional overlay provide user-visible controls.
- The available workspace has no emulator, ADB, or browser automation, so device UI, actual overlay permission flows, Roblox app handoff, and crash-free interactive use cannot be claimed as tested here.
- `.github/workflows/android.yml` builds a debug APK on pushes to the current feature branch when Android files change and uploads it as `Monster-Hopper-APK`.
