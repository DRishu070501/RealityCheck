"""The importer on a small fake extract (about 200 movement rows). No real data."""
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve()
sys.path.insert(0, str(HERE.parents[1]))
from load import Api, NameLeak, load  # noqa: E402
from plan import ROUTES, build, kept  # noqa: E402

NAMES_FILE = HERE.parents[3] / "local" / "names.txt"

# Fake department ids: one per grouping stage, plus an unrouted one ("Other own") and an END department.
STAGE_DEPTS = {"Inward": 1, "Imaging": 2, "Lab": 3, "Quality check": 4, "Planning": 5, "Parking": 6,
               "Laser cutting": 7, "Job work": 8, "Manufacturing": 9, "Recut": 10, "Dispatch waiting": 11}
OTHER, END = 12, 13
GROUPING = {d: {"stage": s, "kind": "WORKING"} for s, d in STAGE_DEPTS.items()}
GROUPING[OTHER] = {"stage": "Other own", "kind": "WORKING"}
GROUPING[END] = {"stage": "End", "kind": "END"}


def write_extract(tmp: Path) -> None:
    rng = np.random.default_rng(7)
    t0 = pd.Timestamp("2026-10-01 10:00")
    routes, pieces, history = [], [], []
    move_id = 0
    for lot in range(20):
        root = 1000 + lot
        start = t0 + pd.Timedelta(hours=int(rng.integers(0, 240)))
        packet_type = 3 if lot % 2 else 5
        path = ["Inward", "Imaging", "Lab", "Quality check", "Planning", "Laser cutting", "Recut"]
        if packet_type == 3:
            path = path[:5] + ["Parking", "Laser cutting", "Manufacturing", "Recut", "Dispatch waiting"]
        if lot % 5 == 0:
            path.insert(3, "Imaging")  # a return to an earlier stage
            path.insert(5, OTHER)  # time in an unrouted department
        t = start
        for stage in path:
            move_id += 1
            dept = stage if isinstance(stage, int) else STAGE_DEPTS[stage]
            routes.append((move_id, root, None, dept, t, None))
            t += pd.Timedelta(hours=int(rng.integers(1, 30)))
        move_id += 1
        routes.append((move_id, root, None, END, t, None))
        for k in range(2):
            piece = root * 10 + k
            pieces.append((piece, root, packet_type, start))
            history.append((len(history) + 1, piece, 792, None, 1, t + pd.Timedelta(hours=1), 1.0, 1.0, 9, 0))
    pd.DataFrame(routes, columns=["id", "packetId", "fromDept", "toDept", "createdDate", "receivedOn"]) \
        .assign(cts=1.0).to_csv(tmp / "route_movements.csv", index=False)
    pd.DataFrame(pieces, columns=["id", "root", "packetType", "lotCreation"]).to_csv(
        tmp / "dispatched_with_root.csv", index=False)
    pd.DataFrame(history, columns=["id", "packetId", "actionId", "deptId", "status", "createdDate", "issueCts",
                                   "returnCts", "userId", "isRepairing"]).to_csv(tmp / "history.csv", index=False)
    pd.DataFrame([], columns=["id", "packetId", "fromDept", "toDept", "fromUser", "toUser", "actionId",
                              "createdDate", "receivedOn", "cts"]).to_csv(tmp / "worker_moves.csv", index=False)


@pytest.fixture
def plan(tmp_path):
    write_extract(tmp_path)
    assert len(pd.read_csv(tmp_path / "route_movements.csv")) <= 200
    return build(tmp_path, GROUPING)


def test_lots_become_coded_orders_and_pieces_become_batches(plan):
    assert [o["orderNumber"] for o in plan["orders"]] == [f"L{i:04d}" for i in range(1, 21)]
    assert sum(len(o["batches"]) for o in plan["orders"]) == 40
    text = json.dumps(plan)
    assert "1000" not in text and "10000" not in text  # no packet or lot identifiers
    assert plan["orders"] == sorted(plan["orders"], key=lambda o: o["placedAt"])


def test_each_batch_visits_its_route_in_order_without_gaps(plan):
    steps = {r["code"]: [s["stageCode"] for s in r["steps"]] for r in plan["routes"]}
    for order in plan["orders"]:
        for batch in order["batches"]:
            visits = batch["visits"]
            codes = [v["stage"] for v in visits]
            route = steps[batch["route"]]
            assert codes[0] == route[0] and codes[-1] == route[-1]
            assert [route.index(c) for c in codes] == sorted(route.index(c) for c in codes)
            assert len(set(codes)) == len(codes)
            assert visits[0]["start"] == order["placedAt"]
            for a, b in zip(visits, visits[1:]):
                assert a["end"] == b["start"]
            assert all(v["start"] <= v["end"] for v in visits)


def test_routes_follow_packet_type_and_measure_optional_steps(plan):
    assert {r["code"] for r in plan["routes"]} == set(ROUTES)
    full = next(r for r in plan["routes"] if r["code"] == "FULL")
    share = {s["stageCode"]: s["visitPercent"] for s in full["steps"]}
    assert share["POLISHING"] == 99 and share["PARKING"] == 99  # every full piece, capped below 100
    tops = next(r for r in plan["routes"] if r["code"] == "TOPS")
    assert "POLISHING" not in {s["stageCode"] for s in tops["steps"]}  # never visited: dropped
    assert tops["steps"][-1]["stageCode"] == "RECUT"


def test_working_stages_get_a_pool_and_holding_stages_a_delay(plan):
    stages = {s["code"]: s for s in plan["stages"]}
    assert stages["PARKING"]["kind"] == "PARKING" and len(stages["PARKING"]["delayDeciles"]) == 10
    assert stages["JOB_WORK"]["kind"] == "JOB_WORK"
    assert stages["SAWING"]["pool"] == "SAWING_CREW" and len(stages["SAWING"]["handlingDeciles"]) == 10
    for s in plan["stages"]:
        deciles = s.get("delayDeciles") or s.get("handlingDeciles")
        assert deciles == sorted(deciles) and min(deciles) >= 0
    assert all(p["capacity"] >= 1 for p in plan["pools"])


def test_sampling_keeps_whole_lots_and_is_repeatable(tmp_path):
    write_extract(tmp_path)
    half = build(tmp_path, GROUPING, sample_percent=50)
    again = build(tmp_path, GROUPING, sample_percent=50)
    assert half == again
    assert 0 < len(half["orders"]) < 20
    assert all(len(o["batches"]) == 2 for o in half["orders"])
    assert kept(1000, 100) and not kept(1000, 0)


class FakeServer:
    """Answers the importer's requests as RealityCheck would, and records every body."""

    def __init__(self):
        self.bodies: list[str] = []
        self.calls: list[tuple[str, str]] = []
        self.orders: dict[str, int] = {}

    def __call__(self, method, path, body):
        self.calls.append((method, path))
        if body is not None:
            self.bodies.append(body.decode())
        payload = json.loads(body) if body else {}
        if path == "/api/workflows":
            return 201, {"id": "w1", "stages": [{"id": f"s-{s['code']}", "code": s["code"]}
                                                 for s in payload["stages"]]}
        if path == "/api/orders":
            order_id = f"o{len(self.orders) + 1}"
            self.orders[order_id] = len(payload["batches"])
            return 201, {"id": order_id}
        if method == "GET" and path.startswith("/api/orders/"):
            order_id = path.rsplit("/", 1)[1]
            return 200, {"batches": [{"id": f"{order_id}-b{n}", "batchNumber": n}
                                     for n in range(self.orders[order_id], 0, -1)]}
        return 201, {"id": f"id{len(self.calls)}"}


def test_loads_configuration_orders_and_two_events_per_visit(plan):
    server = FakeServer()
    factory_id = load(plan, Api("http://local", send=server), workers=2)

    assert factory_id == "id1"
    visits = sum(len(b["visits"]) for o in plan["orders"] for b in o["batches"])
    assert sum(1 for c in server.calls if c == ("POST", "/api/events")) == 2 * visits
    assert ("PUT", "/api/workflows/w1/configuration") in server.calls
    completed = [json.loads(b) for b in server.bodies if '"STAGE_COMPLETED"' in b]
    assert sum(1 for c in completed if "nextStageId" not in c) == 40  # each batch finishes once
    assert all(c["stoneQuantity"] == 1 for c in completed)


def test_refuses_to_send_a_real_name():
    server = FakeServer()
    api = Api("http://local", names=["Secret Polishing House"], send=server)
    with pytest.raises(NameLeak):
        api.call("POST", "/api/factories", {"name": "secret polishing house"})
    assert server.calls == []


@pytest.mark.skipif(not NAMES_FILE.exists(), reason="local/names.txt is not present (CI)")
def test_no_local_name_reaches_the_api(plan):
    names = [n.strip().lower() for n in NAMES_FILE.read_text(encoding="utf-8").splitlines() if len(n.strip()) >= 3]
    server = FakeServer()
    load(plan, Api("http://local", names=names, send=server), workers=2)
    sent = "\n".join(server.bodies).lower()
    assert server.bodies
    assert [n for n in names if n in sent] == []
