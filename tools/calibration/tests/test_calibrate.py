"""Calibration on a tiny hand-made extract with known answers. No real data."""
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from calibrate import calibrate  # noqa: E402

INWARD, PARK, WORK, JOB, END = 1, 2, 3, 4, 5
GROUPING = {INWARD: {"stage": "Inward", "kind": "INWARD"}, PARK: {"stage": "Parking", "kind": "PARKING"},
            WORK: {"stage": "Cutting", "kind": "WORKING"}, JOB: {"stage": "Job work", "kind": "JOB_WORK"},
            END: {"stage": "End", "kind": "END"}}


def write(tmp: Path):
    t = pd.Timestamp
    # Piece 10: inward day 0, parked 2 days, cut 1 day, job work 1 day, reaches END at day 4 (dispatch day 6).
    # Piece 20: inward day 0, cut 2 days, then Final Dispatch at day 2 without reaching END.
    route = [
        (1, 10, None, INWARD, t("2026-10-01 00:00"), None),
        (2, 10, INWARD, PARK, t("2026-10-01 00:00"), None),
        (3, 10, PARK, WORK, t("2026-10-03 00:00"), None),
        (4, 10, WORK, JOB, t("2026-10-04 00:00"), None),
        (5, 10, JOB, END, t("2026-10-05 00:00"), None),
        (6, 20, None, INWARD, t("2026-10-01 00:00"), None),
        (7, 20, INWARD, WORK, t("2026-10-01 00:00"), None),
    ]
    moves = pd.DataFrame(route, columns=["id", "packetId", "fromDept", "toDept", "createdDate", "receivedOn"])
    moves.assign(cts=1.0).to_csv(tmp / "route_movements.csv", index=False)
    moves.to_csv(tmp / "movements.csv", index=False)
    pd.DataFrame([(10, 10, 1, t("2026-10-01")), (20, 20, 5, t("2026-10-01"))],
                 columns=["id", "root", "packetType", "lotCreation"]).to_csv(tmp / "dispatched_with_root.csv", index=False)
    pd.DataFrame([
        (1, 10, 792, None, 1, t("2026-10-07"), 1.0, 1.0, 9, 0),
        (2, 20, 792, None, 1, t("2026-10-03"), 0.8, 0.8, 9, 0),
        (3, 10, 100, WORK, 1, t("2026-10-04"), 1.0, 0.9, 9, 0),
    ], columns=["id", "packetId", "actionId", "deptId", "status", "createdDate", "issueCts", "returnCts", "userId",
                "isRepairing"]).to_csv(tmp / "history.csv", index=False)
    pd.DataFrame([(1, 10, WORK, WORK, None, 77, 100, t("2026-10-03 01:00"), None, 1.0),
                  (2, 10, WORK, WORK, 77, None, 128, t("2026-10-03 03:00"), None, 1.0)],
                 columns=["id", "packetId", "fromDept", "toDept", "fromUser", "toUser", "actionId", "createdDate",
                          "receivedOn", "cts"]).to_csv(tmp / "worker_moves.csv", index=False)


def test_lead_time_ends_at_the_first_end_department_or_final_dispatch(tmp_path):
    write(tmp_path)
    c = calibrate(tmp_path, GROUPING)
    assert c["pieces"] == 2
    # Piece 10: 4 days to END (not 6 to dispatch), 2 parked → 2 manufacturing. Piece 20: 2 and 2.
    assert c["leadDays"]["median"] == 3.0
    assert c["manufacturingDays"]["median"] == 2.0
    assert c["parking"]["share"] == 0.5
    assert c["stages"]["Parking"]["dwellHoursDeciles"][0] == 48.0
    assert c["stages"]["Job work"]["kind"] == "JOB_WORK"
    assert c["stages"]["Cutting"]["caratYield"] == 0.9
    assert c["stages"]["Cutting"]["withWorkerHoursDeciles"][0] == 2.0
    assert c["byPacketType"]["1"]["leadMedianDays"] == 4.0
    assert [s["stage"] for s in c["typicalRoutes"]["1"]["stages"]] == ["Parking", "Cutting", "Job work"]  # zero-time stages leave no interval
    assert c["capacity"]["busyDayOutput"]["Parking"] == 1  # Sundays (4 Oct) are not counted
