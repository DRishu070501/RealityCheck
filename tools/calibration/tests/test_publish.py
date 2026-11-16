"""Publishing keeps only rounded, generic patterns. No real data."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish import check_names, publish  # noqa: E402

PRIVATE = {
    "leadDays": {"median": 17.04, "deciles": [4.9] * 10},
    "manufacturingDays": {"median": 11.38, "deciles": [4.8] * 10},
    "parking": {"share": 0.47, "daysDeciles": [0.5] * 10},
    "byPacketType": {"1": {"pieces": 40, "leadMedianDays": 7.2, "manufacturingMedianDays": 6.7},
                     "3": {"pieces": 60, "leadMedianDays": 26.7, "manufacturingMedianDays": 20.0}},
    "typicalRoutes": {
        "1": {"pieces": 40, "stages": [{"stage": "Inward", "share": 1.0}, {"stage": "Secret Dept X", "share": 1.0},
                                       {"stage": "Recut", "share": 0.98}]},
        "3": {"pieces": 60, "stages": [{"stage": "Inward", "share": 1.0}, {"stage": "Parking", "share": 0.52}]},
    },
    "stages": {"Recut": {"dwellHoursDeciles": [5.14] * 10, "caratYield": 0.8912},
               "Secret Dept X": {"dwellHoursDeciles": [1.0] * 10}},
    "capacity": {"inwardPerDayMedian": 491, "busyDayOutput": {"Recut": 1965}, "typicalDayOutput": {"Recut": 1199}},
}


def test_publishes_rounded_generic_patterns_only():
    out = publish(PRIVATE)
    text = json.dumps(out)
    assert "Secret Dept X" not in text  # an ungrouped stage name never leaks
    assert out["leadDays"]["median"] == 17.0
    assert out["parking"]["share"] == 0.45
    tops = next(r for r in out["routes"] if r["code"] == "TOPS")
    assert [s["stage"] for s in tops["stages"]] == ["INTAKE", "RECUT"]
    assert tops["share"] == 0.4 and tops["leadMedianDays"] == 7.0
    recut = out["stages"]["RECUT"]
    assert recut["caratYield"] == 0.89 and recut["dwellHoursDeciles"][0] == 5
    assert recut["busyDayOutputPerInward"] == 4.0 and recut["typicalDayLoad"] == 0.6


def test_name_check_finds_real_names_case_insensitively():
    assert check_names('{"name": "recut"}', ["North Dispatch", "Recut"]) == ["Recut"]
    assert check_names('{"name": "Polishing"}', ["North Dispatch"]) == []
