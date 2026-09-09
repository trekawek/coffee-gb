import importlib.util
import json
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "performance_costs", Path(__file__).parents[1] / "report-performance-costs.py")
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


def fixture(**extra):
    data = {"schema": "coffee-gb-performance-v1", "id": "opaque_001", "profile": "CGB",
            "speed": 1, "ticks": 1000, "nominal_ticks_per_second": 1000,
            "diagnostics_enabled": True, "execution_ticks": {"EPOCH": 1000},
            "span_histogram": [0] * 65}
    data["span_histogram"][10] = 100
    data.update(extra)
    return data


class PerformanceCostReportTest(unittest.TestCase):
    def test_full_epoch_exposure_does_not_hide_replayed_or_rejected_ppu_work(self):
        item = REPORT.summarize_record(fixture(
            subsystem_ticks={"PPU_REPLAY": 3000, "STAT_REPLAY": 1000},
            rejected_lines=400, direct_lines=0, longest_rejected_line_run=400))
        self.assertEqual(1, item["metrics"]["epoch_share"])
        self.assertEqual(3, item["metrics"]["ppu_replay_per_tick"])
        self.assertEqual({"ppu_replay", "stat_replay", "line_rejection", "long_rejected_line_run"},
                         set(item["signatures"]))

    def test_halt_work_does_not_turn_low_epoch_share_into_scalar_cost(self):
        histogram = [0] * 65
        histogram[1], histogram[50] = 50, 19
        item = REPORT.summarize_record(fixture(
            execution_ticks={"SCALAR": 50, "EPOCH": 50, "HALT": 900},
            span_histogram=histogram))
        self.assertEqual(["halt_transfer_dominated"], item["signatures"])
        self.assertEqual(0.05, item["metrics"]["scalar_share"])

    def test_small_epochs_and_scalar_streaks_are_independent(self):
        histogram = [0] * 65
        histogram[1], histogram[2] = 100, 450
        item = REPORT.summarize_record(fixture(
            execution_ticks={"SCALAR": 100, "EPOCH": 900},
            span_histogram=histogram, longest_scalar_run=1))
        self.assertEqual(["small_spans"], item["signatures"])
        self.assertEqual(1, item["metrics"]["small_span_tick_share"])

    def test_exact_dma_replay_keeps_its_independent_ppu_quiet_coverage(self):
        item = REPORT.summarize_record(fixture(
            subsystem_ticks={"DMA_REPLAY": 900, "PPU_QUIET_DURING_DMA": 800}))
        self.assertEqual(["dma_replay"], item["signatures"])
        self.assertEqual(0.9, item["metrics"]["dma_replay_per_tick"])
        self.assertEqual(0.8, item["metrics"]["ppu_quiet_during_dma_per_tick"])

    def test_partial_window_does_not_extend_sustained_scalar_window_streak(self):
        item = REPORT.summarize_record(fixture(
            ticks=1500, execution_ticks={"SCALAR": 1400, "EPOCH": 100},
            longest_scalar_run=900,
            windows=[{"ticks": 1000, "scalar": 900}, {"ticks": 500, "scalar": 500}]))
        self.assertNotIn("sustained_scalar_windows", item["signatures"])
        self.assertIn("long_scalar_run", item["signatures"])
        self.assertEqual(1, item["metrics"]["full_windows_retained"])
        item = REPORT.summarize_record(fixture(
            ticks=2000, execution_ticks={"SCALAR": 1400, "EPOCH": 600},
            windows=[{"ticks": 1000, "scalar": 700}, {"ticks": 1000, "scalar": 700}]))
        self.assertIn("sustained_scalar_windows", item["signatures"])

    def test_repeats_preserve_ranges_and_do_not_export_private_or_unknown_fields(self):
        private = "/private/library/unreported-title.gb"
        first = fixture(rom=private, source=private, mapper_policy=private,
                        subsystem_ticks={"PPU_REPLAY": 1000, private: 500},
                        blockers={"DMA": 5, private: 100},
                        rejection_combinations=[{"reasons": ["DMA", "STAT"], "count": 2},
                                                {"reasons": ["DMA", private], "count": 3}])
        second = fixture(subsystem_ticks={"PPU_REPLAY": 2000})
        report = REPORT.aggregate([first, second, fixture(id=private)])
        self.assertNotIn(private, json.dumps(report))
        self.assertNotIn(private, REPORT.markdown(report))
        self.assertEqual(1, report["ignored_records"])
        workload = report["workloads"][0]
        self.assertEqual(2, workload["runs"])
        self.assertEqual({"median": 1.5, "min": 1, "max": 2},
                         workload["metrics"]["ppu_replay_per_tick"])
        self.assertEqual(["DMA", "STAT"], workload["combinations"][0]["reasons"])

    def test_jsonl_errors_and_missing_diagnostics_are_not_reported_as_zero_cost(self):
        reports = REPORT.parse_reports(json.dumps(fixture(diagnostics_enabled=False)) +
                                       "\nprivate invalid input\n" + json.dumps(fixture(id="opaque_002")))
        report = REPORT.aggregate(reports)
        self.assertEqual(1, report["ignored_records"])
        self.assertEqual(["diagnostics_missing"], report["workloads"][0]["signatures"])
        self.assertEqual({}, report["workloads"][0]["metrics"])

    def test_unbounded_endpoint_reason_remains_distinct_from_serial_event_boundaries(self):
        report = REPORT.aggregate([fixture(
            blockers={"SERIAL": 7, "SERIAL_UNBOUNDED_ENDPOINT": 4},
            rejection_combinations=[{"reasons": ["SERIAL", "SERIAL_UNBOUNDED_ENDPOINT"],
                                     "count": 4}, {"reasons": ["SERIAL"], "count": 3}])])
        self.assertEqual(["SERIAL", "SERIAL_UNBOUNDED_ENDPOINT"],
                         [entry["reason"] for entry in report["workloads"][0]["blockers"]])
        self.assertEqual(["SERIAL", "SERIAL_UNBOUNDED_ENDPOINT"],
                         report["workloads"][0]["combinations"][0]["reasons"])

    def test_older_snapshots_do_not_turn_unavailable_counters_into_measured_zero(self):
        old = fixture(subsystem_ticks={"PPU_REPLAY": 0})
        current = fixture(subsystem_ticks={"PPU_REPLAY": 0, "DMA_REPLAY": 800})
        unavailable = REPORT.summarize_record(old)
        self.assertNotIn("dma_replay_per_tick", unavailable["metrics"])
        self.assertEqual(0, unavailable["metrics"]["ppu_replay_per_tick"])
        report = REPORT.aggregate([old, current])
        workload = report["workloads"][0]
        self.assertEqual({"median": 0.8, "min": 0.8, "max": 0.8},
                         workload["metrics"]["dma_replay_per_tick"])
        self.assertEqual(1, workload["metric_runs"]["dma_replay_per_tick"])
        self.assertEqual(2, workload["metric_runs"]["ppu_replay_per_tick"])
        self.assertIn("missing_subsystem_counter_DMA_REPLAY", workload["warnings"])

    def test_batched_dma_copy_stays_distinct_from_dot_replay_and_older_snapshots(self):
        old = REPORT.summarize_record(fixture(subsystem_ticks={"DMA_REPLAY": 900}))
        current = REPORT.summarize_record(fixture(subsystem_ticks={
            "DMA_REPLAY": 100, "DMA_BATCHED_COPY": 800, "PPU_QUIET_DURING_DMA": 800}))
        self.assertEqual(["dma_replay"], old["signatures"])
        self.assertNotIn("dma_batched_copy_per_tick", old["metrics"])
        self.assertEqual(["dma_batched_copy"], current["signatures"])
        self.assertEqual(0.1, current["metrics"]["dma_replay_per_tick"])
        self.assertEqual(0.8, current["metrics"]["dma_batched_copy_per_tick"])
        self.assertEqual(0.8, current["metrics"]["ppu_quiet_during_dma_per_tick"])

    def test_quiet_ppu_suffix_is_coverage_and_absent_older_counter_is_unavailable(self):
        old = REPORT.summarize_record(fixture(subsystem_ticks={"PPU_REPLAY": 1000}))
        current = REPORT.summarize_record(fixture(subsystem_ticks={
            "PPU_REPLAY": 100, "STAT_REPLAY": 100, "PPU_QUIET_AFTER_REPLAY": 900}))
        self.assertNotIn("ppu_quiet_after_replay_per_tick", old["metrics"])
        self.assertIn("missing_subsystem_counter_PPU_QUIET_AFTER_REPLAY", old["warnings"])
        self.assertEqual(0.9, current["metrics"]["ppu_quiet_after_replay_per_tick"])
        self.assertEqual(0.1, current["metrics"]["ppu_replay_per_tick"])
        self.assertEqual(["no_large_signature"], current["signatures"])

    def test_quiet_steady_coverage_does_not_invent_cost_or_zero_for_old_snapshots(self):
        old = fixture(subsystem_ticks={"PPU_REPLAY": 100})
        current = fixture(subsystem_ticks={"PPU_REPLAY": 100, "PPU_QUIET_STEADY": 900})
        report = REPORT.aggregate([old, current])
        workload = report["workloads"][0]
        self.assertNotIn("ppu_quiet_steady_per_tick", REPORT.summarize_record(old)["metrics"])
        self.assertIn("missing_subsystem_counter_PPU_QUIET_STEADY", workload["warnings"])
        self.assertEqual({"median": 0.9, "min": 0.9, "max": 0.9},
                         workload["metrics"]["ppu_quiet_steady_per_tick"])
        self.assertEqual(1, workload["metric_runs"]["ppu_quiet_steady_per_tick"])
        self.assertEqual(["no_large_signature"], workload["signatures"])


if __name__ == "__main__":
    unittest.main()
