# Private import of a real factory

Spec 11, task 11. These scripts put a real factory's October history into a **private, local**
RealityCheck through its public API and backtest the simulation against it. Only rounded errors, with no
dates, are published (in [docs/accuracy.md](../../docs/accuracy.md)). Everything else stays on the machine
that ran it.

| Step | Script | Reads | Writes |
|---|---|---|---|
| 1 | `plan.py` | read-only extracts (see `tools/calibration`), the department grouping | `local/<factory>.plan.json` |
| 2 | `load.py` | the plan | a factory in the local instance, then `local/<factory>.backtest.json` |

The plan replaces lot identifiers with codes (`L0001`, …, in order of arrival) and departments with the
grouping's generic stage names. It sends no people: worker pools are crews per stage. It still holds real
dates, so it lives under the ignored `local/` directory. `plan.py` describes how pieces, routes, capacity
and delays are derived.

## Run

Start a separate stack on other ports, with no demonstration seed, no reset and no AI key, so the import
never shows up in the demonstration app:

```sh
DB_HOST_PORT=15433 BACKEND_HOST_PORT=8081 REALITYCHECK_DEMO_SEED=false REALITYCHECK_DEMO_RESET_ENABLED=false \
AI_PROVIDER=template AI_API_KEY= API_DOCS_ENABLED=false \
docker compose -p rc-private up -d --build --wait db backend

python -I tools/import/plan.py <extract-dir> local/factory-a.grouping.json local/factory-a.plan.json --sample 20
python -I tools/import/load.py local/factory-a.plan.json http://127.0.0.1:8081 local/factory-a.backtest.json \
  --names local/names.txt --fork 2026-10-01 --fork 2026-10-08 --fork 2026-10-15 --fork 2026-10-22
```

With `--names`, every request body is checked against the local name list before it is sent, and the
load stops on the first match. Remove the private stack and its data with
`docker compose -p rc-private down -v`.

## Tests

`python -I -m pytest tools/import/tests` runs on a fake extract of about 200 movement rows. When
`local/names.txt` exists, one test loads the fake plan through a recording server and checks that none of
those names was sent.
