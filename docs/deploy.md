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

## On AWS (free plan)

The live demonstration runs on an AWS free-plan account: a **t4g.small** (ARM, 2 vCPU, 2 GB) with Ubuntu 24.04
and a 20 GB gp3 disk. A free-plan account comes with $100 of credit for six months and is never charged; it is
locked to the region chosen at sign-up. The security group allows SSH from one address, and HTTP and HTTPS from
anywhere. The server got 2 GB of swap as headroom (`fallocate`, `mkswap`, `swapon`, a line in `/etc/fstab`) and
Docker from get.docker.com; `ship.sh` did the rest. The site name is the server's IP on sslip.io.

All 80 browser tests passed against the public address. With the stack running, the server had about 800 MB of
memory available.

Keep the server running rather than stopping it: an automatic public IP changes on every stop and start, and
the name and certificate follow the IP.

## On Oracle Cloud (Always Free)

Oracle's Always Free tier includes Ampere A1 servers, which are ARM machines. Every image the stack uses is
published for ARM (`linux/arm64`): `postgres`, `maven` and `eclipse-temurin`, `node`, `nginx-unprivileged` and
`caddy`. The frontend's lockfile includes the ARM builds of Rolldown, Tailwind and LightningCSS. The steps
above work unchanged once the server is reachable.

1. **Create the instance.** Go to Compute, Instances, Create instance.
   - Image: **Canonical Ubuntu 24.04**, the aarch64 build.
   - Shape: **VM.Standard.A1.Flex** with **2 OCPU and 12 GB**. Always Free covers up to 4 OCPU and 24 GB in
     total.
   - Boot volume: 50 GB. Always Free covers 200 GB of block storage.
   - Add your SSH public key. Keep the public IPv4 address that Oracle assigns.

   If Oracle reports *out of host capacity*, try another availability domain or try again later.
2. **Open 80 and 443 in Oracle's firewall.** Open the instance's subnet (Networking, Virtual cloud networks),
   then its security list. Add two ingress rules: source `0.0.0.0/0`, TCP, destination ports `80` and `443`.
3. **Open them on the server too.** Oracle's Ubuntu images ship with iptables rules that reject everything
   except SSH:

   ```sh
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```
4. **Choose a name.** With no domain of your own, use the free wildcard DNS of
   [sslip.io](https://sslip.io): an address like `203-0-113-10.sslip.io` resolves to `203.0.113.10`. Caddy can
   get a real Let's Encrypt certificate for it, so browsers show no warning. In `deploy/public.env` set
   `SITE_ADDRESS=203-0-113-10.sslip.io` (your IP, with dashes) and leave `TLS_DIRECTIVE` empty.
5. Install Docker on the server, then either build there as in [Steps](#steps), or ship prebuilt images
   (below), which keeps the build's memory and CPU use off the server.

### Ship prebuilt images instead of building on the server

`tools/deploy/ship.sh` builds the backend and frontend images on your own machine. The defaults target ARM
(`linux/arm64`). Then it copies them, with the compose files and the Caddyfile, to the server, loads them and
starts the stack. The Dockerfiles run their build stages on the builder's own CPU, because the jar and the
static files run anywhere; only the runtime stages are per platform. That means an x86 laptop builds ARM
images without emulation. The server needs no source code and runs no build.

```sh
# On the server, once: Docker, and the env file (it never leaves the server)
curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker $USER   # log out and back in
mkdir -p ~/realitycheck/deploy && nano ~/realitycheck/deploy/public.env  # contents as deploy/public.env.example

# On your machine (Git Bash on Windows), for the first deploy and every update
tools/deploy/ship.sh ubuntu@<server-ip>
```

The archive is about 230 MB (both images with their base layers). The server pulls `postgres` and `caddy`
itself. To run the stack by hand on the server, add the overlay:
`docker compose -f docker-compose.yml -f deploy/docker-compose.public.yml -f deploy/docker-compose.prebuilt.yml --env-file deploy/public.env up -d --wait`.

This route was rehearsed locally with `PLATFORM=linux/amd64`. The images were built, saved, removed, loaded
from the archive and started with the prebuilt overlay; the stack was healthy over HTTPS and the reset
endpoint answered 404. The ARM images were built the same way and inspected as `linux/arm64`. They have not
yet run on an ARM server.

Oracle may reclaim an Always Free instance that stays idle for seven days, measured on CPU, network and
memory use, and a quiet demonstration can look idle. Upgrading the account to Pay As You Go removes the
reclaiming, and usage within the Always Free limits stays free. Otherwise, watch for Oracle's warning email.

## On a Hugging Face Space (paid)

Docker Spaces need a Hugging Face PRO account (about $9 a month); only static Spaces are free.
A Space runs one container, so `deploy/huggingface/` packs PostgreSQL, the backend and nginx into one image.
nginx listens on 7860. The database lives in `/tmp` and is created fresh, and the demonstration seeded, on every
start, so a restart is also a reset. Space pages show the app in a frame on huggingface.co, which is the one
origin allowed to frame it. A free Space sleeps after about two days without visitors, and the next visit
starts it again in about a minute.

```sh
python -I tools/deploy/hf_space.py build      # app.jar and html/ from the normal Dockerfiles -> local/hf-space
python -I tools/deploy/hf_space.py test       # optional: run the Space image on http://127.0.0.1:7860
huggingface-cli login                         # once, with a token that has write access
python -I tools/deploy/hf_space.py upload <user>/realitycheck --claude
```

`--claude` stores the `AI_API_KEY` from `.env` as a Space secret, which reaches only the container, and sets the
$2 daily cap. Without it, the built-in analyst answers. The Space builds the small image itself; no source code
is uploaded, only `app.jar`, the built site and the files in `deploy/huggingface/`.

Before the first upload, the image passed all 80 browser tests, run locally against
`http://127.0.0.1:7860`.

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
