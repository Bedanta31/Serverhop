# Monster Hopper

Monster Hopper is a Node.js and Express website for browsing public Catch a Monster servers. The app serves its frontend and API from the same process. It requires no database, secret key, Roblox cookie, or account token.

## Requirements

- Node.js 18 or newer
- npm (included with Node.js)

The `package-lock.json` file pins the dependency tree for repeatable installs. Railway detects the Node.js app from `package.json` and starts it with the existing `npm start` script.

## Run locally

From the project directory:

```bash
npm ci
npm start
```

Open `http://127.0.0.1:3000`. Locally, the app binds to `127.0.0.1` by default. Set `PORT` to use another port. `HOST` can override the bind address when needed.

For Join or Auto-Hop during local development, open a second terminal in the project directory and run `npm run agent`. Keep it running while using the site.

Run the automated tests with:

```bash
npm test
```

Local development uses relative API paths, so the frontend talks to the same local Express server at `http://127.0.0.1:3000`. During development, verify the localhost version before uploading, pushing, or deploying changes.

## Local verification notes

- `GET /api/health` confirms the backend is online and reports the configured Catch a Monster Place ID.
- `GET /api/hop-limit` reports the local backend's in-memory daily hop counter. It uses a 50-hop limit and resets at 00:00 UTC.
- `GET /api/servers` fetches Roblox public servers, filters invalid or full servers, deduplicates by Job ID, and sorts by the numeric player count. Roblox's public response supplies the authoritative count in `playing`; `players` is a player-token array and is not a count. If both are missing or invalid, the count is kept unknown, shown as `Unknown`, and sorted after confirmed counts. The `ping` and `fps` fields are the remote server metrics reported by Roblox; unavailable values remain unknown rather than being replaced with zero.
- `POST /api/join` reserves a daily hop on the API. The browser then asks the local Windows launch agent to open Roblox and confirms the result with `POST /api/join/complete`. A successful launch request is not proof that Roblox joined the target server.
- Manual and automatic launch attempts obey the daily UTC hop limit. The browser also persists the current UTC day's count in `localStorage`, so reloading the page does not reset the display.
- After a launch request is successfully initiated, the website waits 5 seconds before refreshing the server list.
- The server list loads automatically when the website opens. Auto-Hop uses a 15-second minimum interval, measured from the start of one launch attempt to the next.
- Auto-Hop avoids repeated attempted server IDs. A successful manual or automatic launch starts a persisted 15-minute skip-history reset deadline; each later successful hop restarts that deadline. The reset clears attempted/visited tracking only, not the UTC daily hop counter. Stop does not cancel skip-history expiry.
- The header and favicon use `public/cosmic-anubis-hopper-logo.png`, copied from the supplied Monster Hopper logo image.
- The bottom-right profile watermark links to the configured Roblox profile. Its avatar is obtained from Roblox's public user-headshot thumbnail API and cached by the local backend; a pending/unavailable thumbnail uses a text fallback and is retried periodically.

## GitHub Pages frontend and Railway API

The frontend can be hosted as static files on GitHub Pages while the API runs on Railway. `public/runtime-config.js` points to the Railway public API URL, with no trailing slash:

```js
window.MONSTER_HOPPER_CONFIG = {
  apiBaseUrl: 'https://serverhop-production.up.railway.app'
};
```

In Railway, set `MONSTER_HOPPER_ALLOWED_ORIGINS` to the exact GitHub Pages origin (scheme and hostname, without a trailing slash). For this repository's default project site, that origin is `https://bedanta31.github.io` (the `/Serverhop/` path is not part of the origin); use your configured custom-domain origin instead if you have one. Set the same origin in the Windows launch-agent terminal. The API rejects browser origins that are not configured. Railway provides `PORT` and `RAILWAY_ENVIRONMENT` automatically; `npm start` uses those values and binds the hosted backend to `0.0.0.0`. Do not set `HOST` to a public value on the local launch agent.

The site uses relative asset paths so it works at the root or under a GitHub Pages project path. The API URL is only configured in `runtime-config.js`; no browser OS guess or Railway OS value is used to decide whether Roblox can launch.

To publish the frontend, set the repository's **Settings → Pages → Build and deployment → Source** to **GitHub Actions**. The `Publish GitHub Pages` workflow packages only `public/` and is triggered manually from **Actions → Publish GitHub Pages → Run workflow**; it does not publish on push. Select the branch you want to publish when starting the workflow.

## GitHub upload

1. Create a repository in your GitHub account. Choose whether it is public or private.
2. From this project directory, initialize Git if needed and upload the project. Replace the placeholder with the repository URL GitHub gives you:

   ```bash
   git init
   git add .
   git commit -m "Prepare Monster Hopper for deployment"
   git branch -M main
   git remote add origin <your-github-repository-url>
   git push -u origin main
   ```

3. Check the staged files before pushing. `.gitignore` excludes dependencies, environment files, logs, local session data, and common private key files. Never add Roblox cookies, account credentials, API tokens, or other secrets to the repository.

## Deploy on Railway

1. Open Railway and connect your GitHub account.
2. Create a Railway project using **Deploy from GitHub Repo**.
3. Select the Monster Hopper repository.
4. Set `MONSTER_HOPPER_ALLOWED_ORIGINS` to the exact GitHub Pages origin. Railway supplies `PORT` and `RAILWAY_ENVIRONMENT` automatically; no secrets or Roblox credentials are required.
5. Deploy, then inspect the build and runtime logs. The start command is `npm start`; no Dockerfile or custom build command is needed.
6. In Railway's networking settings, generate a public domain for the service.
7. Open the generated URL to verify the website, then open `<your-generated-url>/api/health` to verify the health endpoint. It returns a JSON success response and the Catch a Monster Place ID.

The server uses Railway's `PORT` and binds to `0.0.0.0` when `RAILWAY_ENVIRONMENT` is present. Outside Railway, it defaults to the safer local-only address `127.0.0.1`. An explicitly configured `HOST` takes precedence.

## Roblox and hosted operation

Public server discovery calls Roblox's public server listing API from the Node server. Requests time out after 12 seconds. Its in-memory cache and rate-limit cooldown reset when the service restarts; Roblox rate limits are respected and are not bypassed.

Attempted-server history and its 15-minute reset deadline are stored in the visitor's browser `localStorage`, not on the server. An expired deadline is applied on page reload or when its timer fires. It is not shared between visitors, browsers, or devices. No database or persistent Railway volume is needed for the website's essential state.

The API host never launches Roblox. Railway cannot open Roblox Player on your PC, and its Linux OS is not used to infer the visitor's OS. Launches require the separate local agent to be running on the visitor's Windows 11 PC, with Roblox Player installed. The agent reports its own OS and Roblox installation status; the website does not trust `navigator.platform` or the API server's OS.

To use Join or Auto-Hop, install Node.js 18 or newer on the Windows 11 PC, open the Monster Hopper project folder, run `npm ci` once, then run `npm run agent` and leave that process open while using the website. The agent listens only on `127.0.0.1:3210`. Its status JSON is available at both `http://127.0.0.1:3210/` and `http://127.0.0.1:3210/api/status`; `POST /api/join` is the launch endpoint. Opening the host without a path now returns the same status JSON rather than Express's `Cannot GET /` response.

Configure the exact frontend origin on the Windows PC before starting the agent. In PowerShell, replace the example with the origin shown in the browser address bar (scheme and host only, no path):

```powershell
$env:MONSTER_HOPPER_ALLOWED_ORIGINS = 'https://bedanta31.github.io'
npm run agent
```

For a custom domain, use that exact origin instead. For local development, the agent also allows `http://127.0.0.1:3000` and `http://localhost:3000`. Configure the same GitHub Pages origin on Railway for the website API. Browsers may require granting local-network access to the page; if the agent status URL works but the page reports `Failed to fetch`, check both that browser permission and the exact-origin CORS preflight. The agent does not allow unconfigured origins. If the agent is absent, Roblox Player is not installed, or the PC is not Windows 11, the page reports that state and does not count a hop as launched.

The browser/API handshake first reserves a daily hop, sends the validated Place ID and server Job ID to the local agent, then commits the reservation only when the agent reports that the Windows launch request was accepted. Failed launch requests release the reservation; abandoned reservations expire after two minutes. This remains a launch request only, not proof Roblox joined the server. The 50-hop UTC limit, 15-second minimum Auto-Hop interval, and 5-second post-launch refresh remain in effect.

## Existing API routes

- `GET /api/health` reports service health.
- `GET /api/status` reports server cache and quarter-hour status.
- `GET /api/servers` returns public server results, using the existing cache and cooldown behavior.
- `POST /api/servers/refresh` requests a server-list refresh.
- `POST /api/join` validates the server selection and reserves a daily hop for the local agent.
- `POST /api/join/complete` commits or releases the reservation based on the local launch result.

The local agent exposes `GET http://127.0.0.1:3210/`, `GET http://127.0.0.1:3210/api/status`, and `POST http://127.0.0.1:3210/api/join`. It binds to loopback and allows browser requests only from configured origins.

For local development, leave `apiBaseUrl` empty to use the same-origin API.
