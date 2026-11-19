# Public deployment

One small Linux server runs the same Docker Compose stack as a laptop, with Caddy in front for HTTPS. Visitors
get the seeded demonstration factory. They can change it, and it is restored every night. The reset is not
offered in the app, and the AI analyst's spending is capped per day.

```
internet ──443/80──► caddy ──► frontend (nginx) ──/api──► backend ──► db
                    (HTTPS)      no published ports on frontend, backend or db
```

| Concern | How |
|---|---|
| HTTPS | Caddy obtains and renews a Let's Encrypt certificate for the domain. With no domain, `tls internal` gives a self-signed one (browsers warn). |
| Exposure | Only 80 and 443 are open. The overlay removes the published ports of `db`, `backend` and `frontend`. API docs (Swagger) are off. |
| Reset | `REALITYCHECK_DEMO_RESET_ENABLED=false`, so neither the endpoint nor the Settings control exists. `REALITYCHECK_DEMO_RESET_CRON` restores the demonstration on a schedule inside the backend. Nothing is exposed for it, and it refuses to run if the database holds any factory that is not the demonstration. |
| AI cost | `AI_PROVIDER=template` costs nothing. With Claude (`anthropic` and a key), `AI_DAILY_BUDGET_USD` caps the spend per UTC day and `AI_REQUESTS_PER_MINUTE` limits the rate. Past the cap, the built-in analyst answers and says why. |
| Secrets | `deploy/public.env` lives only on the server (git ignores it). The API key reaches only the backend container. |

## What you need

- A server with 2 vCPU, 4 GB of memory and 20 GB of disk, running Ubuntu 24.04 (any provider).
- Optionally, a domain name with an `A` record pointing at the server's IP address.
- Ports 22, 80 and 443 open in the provider's firewall.

## Steps

```sh
# On the server, as a user with sudo
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker

git clone <repository-url> realitycheck && cd realitycheck
cp deploy/public.env.example deploy/public.env
nano deploy/public.env        # SITE_ADDRESS, a long POSTGRES_PASSWORD, and the AI choice

docker compose -f docker-compose.yml -f deploy/docker-compose.public.yml --env-file deploy/public.env \
  up -d --build --wait
```

The first start builds the images (a few minutes) and seeds the demonstration. Open `https://<SITE_ADDRESS>`.

For an IP-only link, set `SITE_ADDRESS` to the IP and `TLS_DIRECTIVE=tls internal`.

## Check it

```sh
curl -fsS https://<SITE_ADDRESS>/api/system/info        # demo.resetEnabled false; ai as chosen
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<SITE_ADDRESS>/api/demo/reset   # 404
cd frontend && E2E_BASE_URL=https://<SITE_ADDRESS> npx playwright test                  # from a laptop
```

For a self-signed certificate, add `E2E_IGNORE_HTTPS_ERRORS=1`.

The same overlay was rehearsed locally with `SITE_ADDRESS=localhost` and `tls internal`. All 74 browser tests
passed over HTTPS, the reset endpoint and Swagger answered 404, and the backend could not be reached directly.

## Operate

| Task | Command |
|---|---|
| Logs | `docker compose -f docker-compose.yml -f deploy/docker-compose.public.yml --env-file deploy/public.env logs -f backend` |
| Update to the latest code | `git pull`, then the `up -d --build --wait` command above |
| Reset now | `docker compose … down -v`, then the `up -d --build --wait` command: an empty database is seeded at start |
| Today's AI spend | Sum of `input_tokens` and `output_tokens` in `simulation_insight` since midnight UTC, at the prices in `ai.md` |
| Stop | `docker compose … down` (keeps data); add `-v` to erase it |
