"""Unit tests of the query log analyzer. Run in this directory: python3 -m unittest"""

import csv
import json
import os
import tempfile
import unittest

import normalize
import report
from report import FINISHED, HAS_TEXT, MEMORY_FAILURE, Run, predict, replay

GIB = 1024 ** 3
MEMORY = "EXCEEDED_LOCAL_MEMORY_LIMIT"


def run_replay(records, **options):
    """records: dicts with shape, start, end, peak and optional state, error, text, input."""
    rows, times = [], []
    for r in records:
        flags = (FINISHED if r.get("state", "FINISHED") == "FINISHED" else 0)
        flags |= MEMORY_FAILURE if r.get("error") == MEMORY else 0
        text = r.get("text")
        flags |= HAS_TEXT if text is not None else 0
        digest = (text or "").encode().ljust(16, b"\0")[:16]
        rows.append((r["shape"], digest, r["peak"], r.get("input", -1), flags))
        times.append((r.get("start", r["end"]), r["end"]))
    return replay(lambda row: rows[row], times, options.pop("large_bytes", GIB), **options)


class NormalizeTest(unittest.TestCase):
    def test_time_parsing(self):
        base = normalize.parse_time_ms("2026-11-12T15:35:29Z")
        self.assertEqual(base, 1794497729000)
        self.assertEqual(normalize.parse_time_ms("2026-11-12 15:35:29"), base)
        self.assertEqual(normalize.parse_time_ms("2026-11-12 15:35:29.123+00"), base + 123)
        self.assertEqual(normalize.parse_time_ms("2026-11-12T17:35:29.123456+02:00"), base + 123)
        self.assertEqual(normalize.parse_time_ms("2026-11-12T10:35:29-0500"), base)
        self.assertEqual(normalize.parse_time_ms(1794497729), base)
        self.assertEqual(normalize.parse_time_ms("1794497729.5"), base + 500)
        self.assertEqual(normalize.parse_time_ms(1794497729000), base)
        self.assertEqual(normalize.parse_time_ms("1794497729", unit="ms"), 1794497729)
        self.assertIsNone(normalize.parse_time_ms(""))
        self.assertIsNone(normalize.parse_time_ms(None))
        with self.assertRaises(ValueError):
            normalize.parse_time_ms("yesterday")
        self.assertEqual(normalize.format_time_ms(base + 7), "2026-11-12T15:35:29.007Z")

    def test_mapping(self):
        mapping = normalize.parse_mapping(["query=query_text", "peak_memory_bytes=stats.peak", "end_time=ended"], normalize.FIELDS)
        state_map = normalize.parse_mapping(["succeeded=FINISHED"], upper_keys=True)
        raw = {"query_text": "SELECT 1", "stats": {"peak": "2048"}, "ended": "1794497729000", "state": "succeeded",
               "error_code": "", "catalog": "tpch", "input_bytes": "100.0"}
        record = normalize.normalize_record(raw, mapping, state_map)
        self.assertEqual(record, {"query": "SELECT 1", "end_time": "2026-11-12T15:35:29.000Z", "peak_memory_bytes": 2048,
                                  "state": "FINISHED", "catalog": "tpch", "input_bytes": 100})
        with self.assertRaises(SystemExit):
            normalize.parse_mapping(["unknown=x"], normalize.FIELDS)

    def test_rejects(self):
        good = {"query": "SELECT 1", "end_time": "2026-11-12T15:35:29Z", "peak_memory_bytes": 1, "state": "FAILED"}
        self.assertEqual(normalize.normalize_record(good, {}, {})["state"], "FAILED")
        for change, reason in [({"state": "RUNNING"}, "state RUNNING is not FINISHED or FAILED (use --state-map)"),
                               ({"end_time": ""}, "no end_time"),
                               ({"end_time": "soon"}, "time not readable"),
                               ({"peak_memory_bytes": None}, "no peak_memory_bytes"),
                               ({"query": " "}, "no query text")]:
            with self.assertRaises(normalize.RecordError) as e:
                normalize.normalize_record(dict(good, **change), {}, {})
            self.assertEqual(str(e.exception), reason)

    def test_csv_file(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "log.csv")
            with open(source, "w", newline="") as f:
                writer = csv.writer(f)
                writer.writerow(["sql", "ended", "peak", "state"])
                writer.writerow(["SELECT\n1", "2026-11-12 15:35:29+00", "10", "FINISHED"])
                writer.writerow(["SELECT 2", "", "10", "FINISHED"])
            output, stats = os.path.join(directory, "out.jsonl"), os.path.join(directory, "stats.json")
            normalize.main(["--input", source, "--format", "csv", "--output", output, "--stats", stats,
                            "--map", "query=sql", "--map", "end_time=ended", "--map", "peak_memory_bytes=peak"])
            lines = [json.loads(line) for line in open(output)]
            self.assertEqual(lines, [{"id": 1, "query": "SELECT\n1", "end_time": "2026-11-12T15:35:29.000Z",
                                      "peak_memory_bytes": 10, "state": "FINISHED"}])
            self.assertEqual(json.load(open(stats)), {"input_records": 2, "written": 1, "rejected": {"no end_time": 1}})


class PredictionRuleTest(unittest.TestCase):
    def test_successes_only(self):
        runs = [Run(1, True, False, 100), Run(2, True, False, 300), Run(3, True, False, 200)]
        self.assertEqual(predict(runs), (300, report.BASIS_SUCCESSFUL_RUNS))

    def test_failure_then_success(self):
        runs = [Run(1, False, True, 900), Run(2, True, False, 200)]
        self.assertEqual(predict(runs), (200, report.BASIS_SUCCESSFUL_RUNS))

    def test_success_then_newer_failure(self):
        runs = [Run(1, True, False, 200), Run(2, False, True, 150)]
        self.assertEqual(predict(runs), (400, report.BASIS_MEMORY_FAILURE_GROWTH))
        runs = [Run(1, True, False, 200), Run(2, False, True, 350)]
        self.assertEqual(predict(runs, 1.5), (525, report.BASIS_MEMORY_FAILURE_GROWTH))

    def test_only_failures(self):
        runs = [Run(1, False, True, 100), Run(2, False, True, 101)]
        self.assertEqual(predict(runs), (202, report.BASIS_MEMORY_FAILURE_GROWTH))

    def test_non_memory_failures_ignored(self):
        self.assertEqual(predict([Run(1, False, False, 999)]), (None, None))
        self.assertEqual(predict([Run(1, True, False, 100), Run(2, False, False, 999)]), (100, report.BASIS_SUCCESSFUL_RUNS))
        self.assertEqual(predict([]), (None, None))

    def test_window_of_ten(self):
        # the first run has the largest peak; the 12th query sees only runs 2..11
        records = [{"shape": 0, "end": 0, "peak": 5000}] + [{"shape": 0, "end": t, "peak": 100} for t in range(1, 11)]
        records.append({"shape": 0, "end": 20, "peak": 100})
        result = run_replay(records)
        accuracy = result["accuracy"]["all"].summary()
        self.assertEqual(accuracy["hits"], 11)
        # query 2 predicts 5000 (ratio 50); queries 3..11 see the 5000 run too; query 12 does not
        self.assertEqual(accuracy["too_high"], 10)
        result = run_replay(records, history_runs=11)
        self.assertEqual(result["accuracy"]["all"].summary()["too_high"], 11)

    def test_replay_failure_growth(self):
        records = [{"shape": 0, "end": 1, "peak": 100},
                   {"shape": 0, "end": 2, "peak": 80, "state": "FAILED", "error": MEMORY},
                   {"shape": 0, "end": 3, "peak": 210}]
        result = run_replay(records)
        accuracy = result["accuracy"]["all"]
        self.assertEqual(accuracy.ratios, [200 / 210])
        failures = result["failures"]
        self.assertEqual((failures["total"], failures["earlier_shape"], failures["predicted"], failures["at_least_failed_peak"]),
                         (1, 1, 1, 1))
        self.assertEqual(failures["ratios"], [100 / 80])
        self.assertEqual(result["bases"][report.BASIS_MEMORY_FAILURE_GROWTH], 1)


class RepeatRateTest(unittest.TestCase):
    def test_repeat_rates(self):
        records = [
            {"shape": 0, "end": 1, "peak": 10, "text": "a"},
            {"shape": 0, "end": 2, "peak": 10, "text": "b"},  # same shape, new text
            {"shape": 0, "end": 3, "peak": 2 * GIB, "text": "a"},  # same shape, same text, large
            {"shape": 1, "end": 4, "peak": 2 * GIB, "text": "c", "state": "FAILED", "error": MEMORY},  # new shape
            {"shape": 1, "end": 5, "peak": 10, "text": "c", "state": "FAILED", "error": "USER_CANCELED"},
        ]
        repeats = run_replay(records)["repeats"]
        self.assertEqual((repeats["all"].queries, repeats["all"].shape, repeats["all"].text), (5, 3, 2))
        self.assertEqual((repeats["large"].queries, repeats["large"].shape, repeats["large"].text), (2, 1, 1))
        mf = repeats["memory_failures"]
        self.assertEqual((mf.queries, mf.shape, mf.text), (1, 0, 0))

    def test_no_text(self):
        records = [{"shape": 0, "end": 1, "peak": 10}, {"shape": 0, "end": 2, "peak": 10}]
        repeats = run_replay(records)["repeats"]["all"]
        self.assertEqual((repeats.shape, repeats.text), (1, 0))


class LookAheadTest(unittest.TestCase):
    def test_no_look_ahead(self):
        records = [
            {"shape": 0, "end": 10, "peak": 100},
            {"shape": 0, "start": 20, "end": 100, "peak": 100},  # A: long query
            {"shape": 0, "start": 30, "end": 40, "peak": 900},  # B: starts after A, ends before A
            {"shape": 0, "start": 50, "end": 60, "peak": 900},  # C: sees B, not A
        ]
        result = run_replay(records)
        # A sees only the first run (ratio 1); B does not see A (ratio 100/900); C sees B (ratio 1)
        self.assertEqual(sorted(result["accuracy"]["all"].ratios), sorted([1.0, 100 / 900, 1.0]))

    def test_same_time_not_seen(self):
        records = [{"shape": 0, "end": 10, "peak": 100}, {"shape": 0, "end": 10, "peak": 100}]
        result = run_replay(records)
        self.assertEqual(result["accuracy"]["all"].hits, 0)
        self.assertEqual(result["repeats"]["all"].shape, 0)

    def test_order_of_input_does_not_matter(self):
        records = [{"shape": 0, "end": t, "peak": p} for t, p in [(30, 300), (10, 100), (20, 200)]]
        ratios = sorted(run_replay(records)["accuracy"]["all"].ratios)
        self.assertEqual(ratios, [100 / 200, 200 / 300])


class InputSizeCheckTest(unittest.TestCase):
    def test_similar_input(self):
        runs = [Run(1, True, False, 1, 100), Run(2, True, False, 1, 111), Run(3, True, False, 1, 90), Run(4, True, False, 1, None)]
        self.assertEqual([run.end_ms for run in report.similar_input(runs, 100, 0.10)], [1, 3])

    def test_replay_with_input_check(self):
        records = [
            {"shape": 0, "end": 1, "peak": 10, "input": 100},
            {"shape": 0, "end": 2, "peak": 1000, "input": 10000},  # with check: input 100 is not similar, a miss
            {"shape": 0, "end": 3, "peak": 11, "input": 105},  # without check: 1000 / 11; with check: 10 / 11
            {"shape": 0, "end": 4, "peak": 1000, "input": 50000},  # with check: no similar run, a miss
            {"shape": 0, "end": 5, "peak": 1000},  # no input bytes: not in the check
        ]
        result = run_replay(records)
        without, checked = result["input_check"]["all"]
        self.assertEqual((without.queries, without.hits, checked.hits), (4, 3, 1))
        self.assertEqual(checked.ratios, [10 / 11])
        self.assertEqual(sorted(without.ratios), sorted([10 / 1000, 1000 / 11, 1.0]))


class ReportFileTest(unittest.TestCase):
    def test_report_files(self):
        with tempfile.TemporaryDirectory() as directory:
            records = os.path.join(directory, "normalized.jsonl")
            fingerprints = os.path.join(directory, "fingerprints.jsonl")
            with open(records, "w") as r, open(fingerprints, "w") as f:
                for i, (end, peak) in enumerate([("2026-11-02T10:00:00Z", 100), ("2026-11-02T11:00:00Z", 150),
                                                 ("2026-11-02T12:00:00Z", 120), ("2026-11-02T13:00:00Z", 1)], 1):
                    r.write(json.dumps({"id": i, "query": f"SELECT {i} FROM t", "end_time": end, "peak_memory_bytes": peak,
                                        "state": "FINISHED"}) + "\n")
                    if i < 4:
                        f.write(json.dumps({"id": i, "supported": True, "fingerprint": "f1", "normalizedSql": "select ? from t",
                                            "tables": ["c.s.t"]}) + "\n")
                    else:
                        f.write(json.dumps({"id": i, "supported": False, "error": "statement not supported"}) + "\n")
            report.main(["--records", records, "--fingerprints", fingerprints, "--out", directory])
            text = open(os.path.join(directory, "REPORT.md")).read()
            self.assertIn("- Records with a fingerprint: 3 (75.0%)", text)
            self.assertIn("| statement not supported | 1 |", text)
            shapes = list(csv.DictReader(open(os.path.join(directory, "shapes.csv"))))
            self.assertEqual([(s["fingerprint"], s["runs"], s["min_peak_bytes"], s["median_peak_bytes"], s["max_min_ratio"])
                              for s in shapes], [("f1", "3", "100", "120", "1.50")])
            fingerprinted = [json.loads(line) for line in open(os.path.join(directory, "fingerprinted.jsonl"))]
            self.assertTrue(all("query" not in record for record in fingerprinted))


if __name__ == "__main__":
    unittest.main()
