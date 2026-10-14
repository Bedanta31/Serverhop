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

Run the automated tests with:

```bash
npm test
```

Local development uses relative API paths, so the frontend talks to the same local Express server at `http://127.0.0.1:3000`. During development, verify the localhost version before uploading, pushing, or deploying changes.

## Local verification notes

- `GET /api/health` confirms the backend is online and reports the configured Catch a Monster Place ID.
- `GET /api/hop-limit` reports the local backend's in-memory daily hop counter. It uses a 50-hop limit and resets at 00:00 UTC.
- `GET /api/servers` fetches Roblox public servers, filters invalid or full servers, deduplicates by Job ID, and sorts by the numeric player count. Roblox's public response supplies the authoritative count in `playing`; `players` is a player-token array and is not a count. If both are missing or invalid, the count is kept unknown, shown as `Unknown`, and sorted after confirmed counts. The `ping` and `fps` fields are the remote server metrics reported by Roblox; unavailable values remain unknown rather than being replaced with zero.
- `POST /api/join` requests a Roblox launch on Windows through the local backend. A successful launch request is not proof that Roblox joined the target server.
- Manual and automatic launch attempts obey the daily UTC hop limit. The browser also persists the current UTC day's count in `localStorage`, so reloading the page does not reset the display.
- After a launch request is successfully initiated, the website waits 5 seconds before refreshing the server list.
- The server list loads automatically when the website opens. Auto-Hop uses a 15-second minimum interval, measured from the start of one launch attempt to the next.
- Auto-Hop avoids repeated attempted server IDs. A successful manual or automatic launch starts a persisted 15-minute skip-history reset deadline; each later successful hop restarts that deadline. The reset clears attempted/visited tracking only, not the UTC daily hop counter. Stop does not cancel skip-history expiry.
- The header and favicon use `public/cosmic-anubis-hopper-logo.png`, copied from the supplied Monster Hopper logo image.
- The bottom-right profile watermark links to the configured Roblox profile. Its avatar is obtained from Roblox's public user-headshot thumbnail API and cached by the local backend; a pending/unavailable thumbnail uses a text fallback and is retried periodically.

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
4. Configure variables only if required. No application variables are required: Railway supplies `PORT` and `RAILWAY_ENVIRONMENT` automatically.
5. Deploy, then inspect the build and runtime logs. The start command is `npm start`; no Dockerfile or custom build command is needed.
6. In Railway's networking settings, generate a public domain for the service.
7. Open the generated URL to verify the website, then open `<your-generated-url>/api/health` to verify the health endpoint. It returns a JSON success response and the Catch a Monster Place ID.

The server uses Railway's `PORT` and binds to `0.0.0.0` when `RAILWAY_ENVIRONMENT` is present. Outside Railway, it defaults to the safer local-only address `127.0.0.1`. An explicitly configured `HOST` takes precedence.

## Roblox and hosted operation

Public server discovery calls Roblox's public server listing API from the Node server. Requests time out after 12 seconds. Its in-memory cache and rate-limit cooldown reset when the service restarts; Roblox rate limits are respected and are not bypassed.

Attempted-server history and its 15-minute reset deadline are stored in the visitor's browser `localStorage`, not on the server. An expired deadline is applied on page reload or when its timer fires. It is not shared between visitors, browsers, or devices. No database or persistent Railway volume is needed for the website's essential state.

Railway runs the backend in the cloud and cannot launch Roblox Player on your personal computer through a `roblox://` URI. The existing launch endpoint only works when the backend runs on a supported Windows machine. In a cloud deployment, the website and server-list API can be public, but Join and Auto-Hop cannot launch Roblox on your PC without a separately designed local agent. The app does not claim that a launch request confirms an in-game join.

## Existing API routes

- `GET /api/health` reports service health.
- `GET /api/status` reports server cache and quarter-hour status.
- `GET /api/servers` returns public server results, using the existing cache and cooldown behavior.
- `POST /api/servers/refresh` requests a server-list refresh.
- `POST /api/join` validates a Place ID and server Job ID before requesting a Roblox launch where supported.

The frontend uses relative API paths, so it works from the local URL and Railway's generated domain without a hardcoded origin.
