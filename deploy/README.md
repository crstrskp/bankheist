# Deploy to heist.tjorne.dk

This repository has its own Compose project. Run its commands from the cloned Bank Heist directory. The game joins Caddy's existing Docker network; the leaderboard uses a separate internal network. Neither app publishes a host port. Your existing Compose project continues to manage Caddy and your other sites.

Prerequisites: Docker with the Compose v2 plugin, the existing Caddy container, and the DNS A record for `heist.tjorne.dk` pointing at your VPS. Caddy already serves your other sites on ports 80/443. Java and Maven run inside the build container; you don't need to install them on the host.

## 1. Confirm the `default` network

The supplied `.env.example` uses your requested network name, `default`:

```sh
docker network inspect default --format '{{.Name}}'
```

If this prints `default`, continue. If it reports that the network does not exist, `default` was likely the label inside your existing Compose file. Compose often prefixes that label with its project name, for example `websites_default`. Find the actual name on Caddy:

```sh
docker ps --format 'table {{.Names}}\t{{.Image}}'
docker inspect YOUR_CADDY_CONTAINER --format '{{range $name, $config := .NetworkSettings.Networks}}{{println $name}}{{end}}'
```

Use the actual shared network name as `CADDY_NETWORK` in `.env`. Do not create a new disconnected network just to make the name match. No other part of your existing Compose file is needed.

## 2. Configure Bank Heist

From your cloned repository, on the first deployment:

```sh
cp .env.example .env
openssl rand -hex 24
nano .env
```

Keep `CADDY_NETWORK=default` (or use the actual name found above) and `TEACHER_KEY` to the generated value. Leave `ALARM_ENABLED=true`, or set it to `false` for the alarm-free lesson. Enter your teacher key in the browser's teacher controls. `.env` is ignored by Git and excluded from Docker's build context; keep it on the VPS. Preserve your existing `.env` on later updates.

Validate and start:

```sh
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

The first build downloads dependencies and runs all Java tests. Both services should become healthy. Check the game through the Caddy container (the standard Caddy image provides `wget`):

```sh
docker exec YOUR_CADDY_CONTAINER wget -qO- http://bankheist-game:7070/health
```

Expected: `{"status":"ok"}`. If your custom Caddy image has no `wget`, skip this diagnostic and use the HTTPS check below. If the hostname cannot be resolved, check that Caddy and `bankheist-game` share the exact Docker network configured in `.env`.

## 3. Add the Caddy entry

Append this block to the **existing host-side Caddyfile mounted into your Caddy container**, alongside the other sites:

```caddyfile
heist.tjorne.dk {
    reverse_proxy bankheist-game:7070
}
```

The same block is in [Caddyfile.snippet](Caddyfile.snippet). Do not replace your whole Caddyfile with the snippet.

Validate and reload the full file. These commands assume the usual container path `/etc/caddy/Caddyfile`; substitute your actual path if your mount differs:

```sh
docker exec YOUR_CADDY_CONTAINER caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
# Only reload after validation succeeds:
docker exec YOUR_CADDY_CONTAINER caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
```

Check the mounted configuration path if needed:

```sh
docker inspect YOUR_CADDY_CONTAINER --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}'
```

Caddy handles HTTPS for the hostname and proxies WebSockets through the same route; there is no separate `/ws/game` block or Upgrade header configuration. The browser already selects `wss://` when loaded over HTTPS. A Caddy reload can disconnect existing WebSockets; the browser reconnects automatically.

If Caddy sees old content after editing a single-file bind mount, inspect the file inside the container with `docker exec YOUR_CADDY_CONTAINER cat /etc/caddy/Caddyfile`. An editor can replace the host file's inode; in that case restart the Caddy service from its existing Compose project to remount it. Normally a reload is sufficient.

## 4. Check the deployment

```sh
curl -fsS https://heist.tjorne.dk/health
docker compose logs --tail=50 bankheist-game leaderboard
```

Open **https://heist.tjorne.dk** in two browsers, join, enter your teacher key, create a match and start a round. Check the live bags, individual escapes, and **Inspect webhooks** → `DELIVERED` after the round ends.

To read the internal leaderboard, copy a real match ID from `/state` or the webhook history:

```sh
docker compose exec bankheist-game curl -fsS 'http://leaderboard:7071/leaderboard?matchId=PASTE_MATCH_ID'
```

If Caddy returns 502, check `docker compose ps`, the logs, and the shared network. If HTTPS does not come up, check the DNS record and Caddy logs with `docker logs --tail=100 YOUR_CADDY_CONTAINER`.

## Later updates

Commit and push on your development machine, then run these **from the Bank Heist clone on the VPS**:

```sh
git pull --ff-only
docker compose up -d --build --wait
```

Caddy needs no reload for ordinary application updates. When you change `.env`, `docker compose up -d --wait` recreates affected containers with the new settings; `docker compose restart` alone does not load changed environment settings.

All scores, registrations, pending webhooks, and received events are in memory. Recreating either service clears its state. Deploy between matches and refresh/rejoin afterwards. For an intentional complete reset of this Bank Heist project:

```sh
docker compose restart
```

For the receiver-failure teaching exercise, use `docker compose stop leaderboard` and `docker compose start leaderboard`. Stopping/starting the same container still restarts Java, so the receiver loses its in-memory scores; replay previous events if you need to rebuild them.

This remains the trusted-classroom app: joining the eight player slots is public, while teacher controls require the configured key. The webhook receiver is accessible only on the Bank Heist backend network.

References: [Docker external networks](https://docs.docker.com/reference/compose-file/networks/), [Caddy reverse proxy and WebSockets](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy), [Caddy validate/reload](https://caddyserver.com/docs/command-line).
