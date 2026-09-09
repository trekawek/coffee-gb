import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "performance_matrix", Path(__file__).parents[1] / "compare-performance-matrix.py")
MATRIX = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MATRIX)
MANIFEST = Path(__file__).parents[1] / "performance-matrix.json"


def small_manifest():
    manifest = MATRIX.load_manifest(MANIFEST)
    manifest["cases"] = [{"scenario": "CPU", "profile": "DMG"}]
    manifest["protocol"]["measured_ticks"] = 1000
    return manifest


def measurements():
    rows = []
    for trial in range(3):
        for variant, duration in (("baseline", 200), ("candidate", 100), ("diagnostics", 120)):
            rows.append(dict(zip(MATRIX.COLUMNS, (
                variant, str(trial), "CPU", "DMG", "1000", str(duration),
                # Same measured window agrees across variants; windows need not agree.
                str(477 if trial == 1 else 478), "500" if variant == "baseline" else "750", "100"))))
    return rows


class PerformanceMatrixTest(unittest.TestCase):
    def test_default_inventory_preserves_the_original_39_cases(self):
        manifest = MATRIX.load_manifest(MANIFEST)
        self.assertEqual("coverage-39-v1", manifest["id"])
        pairs = [(case["scenario"], case["profile"]) for case in manifest["cases"]]
        self.assertEqual(39, len(pairs))
        self.assertEqual(17, len({scenario for scenario, _ in pairs}))
        self.assertEqual([("CPU", "DMG"), ("CPU", "CGB"), ("CPU", "CGB_X2"),
                          ("CPU", "CGB0"), ("CPU", "CGB0_COMPAT"), ("CPU", "SGB")], pairs[:6])
        self.assertEqual(("SPEED_SWITCH", "CGB"), pairs[-1])
        self.assertEqual(2097152, manifest["protocol"]["warmup_ticks"])
        self.assertEqual(8388608, manifest["protocol"]["measured_ticks"])
        self.assertEqual(80571, manifest["protocol"]["shuffle_seed"])

    def test_frame_check_compares_matching_windows_and_diagnostics_work(self):
        report = MATRIX.summarize(measurements(), small_manifest())
        self.assertTrue(report["all_frames_and_diagnostic_accounting_match"])
        self.assertFalse(report["timing_accepted"])
        self.assertEqual(2, report["per_case"][0]["median_speedup"])
        self.assertAlmostEqual(0.2, report["per_case"][0]["median_enabled_overhead"])

    def test_polling_followup_covers_every_known_profile_without_changing_protocol(self):
        original = MATRIX.load_manifest(MANIFEST)
        followup = MATRIX.load_manifest(MANIFEST.with_name("performance-polling-matrix.json"))
        self.assertEqual("polling-60-v1", followup["id"])
        self.assertEqual(original["protocol"], followup["protocol"])
        expected_scenarios = {"IF_POLL", "IE_POLL", "DIV_POLL", "TIMA_POLL", "JOYP_POLL", "NR52_POLL"}
        expected_profiles = {"DMG", "MGB", "CGB", "CGB_X2", "CGB0", "CGB0_X2",
                             "CGB_COMPAT", "CGB0_COMPAT", "SGB", "SGB2"}
        self.assertEqual({(scenario, profile) for scenario in expected_scenarios
                          for profile in expected_profiles},
                         {(case["scenario"], case["profile"]) for case in followup["cases"]})
        self.assertEqual(39, len(original["cases"]))

    def test_lyc_poll_followup_covers_every_known_profile_without_changing_existing_manifests(self):
        original = MATRIX.load_manifest(MANIFEST)
        polling = MATRIX.load_manifest(MANIFEST.with_name("performance-polling-matrix.json"))
        profile_cost = MATRIX.load_manifest(MANIFEST.with_name("performance-profile-cost-matrix.json"))
        retained = MATRIX.load_manifest(MANIFEST.with_name("performance-retained-fence-cost-matrix.json"))
        followup = MATRIX.load_manifest(MANIFEST.with_name("performance-lyc-polling-cost-matrix.json"))
        self.assertEqual("lyc-poll-10-v1", followup["id"])
        self.assertEqual(original["protocol"], followup["protocol"])
        expected_profiles = {"DMG", "MGB", "CGB", "CGB_X2", "CGB0", "CGB0_X2",
                             "CGB_COMPAT", "CGB0_COMPAT", "SGB", "SGB2"}
        self.assertEqual({("LYC_POLL", profile) for profile in expected_profiles},
                         {(case["scenario"], case["profile"]) for case in followup["cases"]})
        self.assertEqual(39, len(original["cases"]))
        self.assertEqual(60, len(polling["cases"]))
        self.assertEqual(15, len(profile_cost["cases"]))
        self.assertEqual(12, len(retained["cases"]))

    def test_missing_triplet_member_or_trial_fails_closed(self):
        for rows in (measurements()[:-1], measurements()[:-3]):
            with self.subTest(count=len(rows)), self.assertRaisesRegex(ValueError, "Incomplete"):
                MATRIX.summarize(rows, small_manifest())

    def test_duplicate_and_undeclared_case_fail_closed(self):
        rows = measurements()
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            MATRIX.summarize(rows + [rows[0]], small_manifest())
        rows[0]["profile"] = "SGB"
        with self.assertRaisesRegex(ValueError, "outside"):
            MATRIX.summarize(rows, small_manifest())

    def test_timing_is_not_reported_when_frames_differ(self):
        rows = measurements()
        rows[1]["frames"] = "479"
        with self.assertRaisesRegex(ValueError, "Frame mismatch"):
            MATRIX.summarize(rows, small_manifest())

    def test_enabled_diagnostics_must_preserve_each_execution_counter(self):
        for metric in ("epoch_ticks", "bulk_ticks"):
            rows = measurements()
            rows[2][metric] = "99"
            with self.subTest(metric=metric), self.assertRaisesRegex(ValueError, "Diagnostics changed"):
                MATRIX.summarize(rows, small_manifest())

    def test_wrong_budget_zero_duration_and_invalid_work_fail_closed(self):
        for metric, value in (("ticks", "999"), ("host_ns", "0"), ("frames", "-1"),
                              ("epoch_ticks", "1001")):
            rows = measurements()
            rows[1][metric] = value
            with self.subTest(metric=metric), self.assertRaises(ValueError):
                MATRIX.summarize(rows, small_manifest())

    def test_manifest_rejects_unimplemented_policies_and_duplicate_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            for mutate in (lambda m: m["protocol"].update(bootstrap="FAST_FORWARD"),
                           lambda m: m["cases"].append(copy.deepcopy(m["cases"][0]))):
                manifest = small_manifest()
                mutate(manifest)
                path.write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    MATRIX.load_manifest(path)

    def test_epoch_and_bulk_counts_must_fit_as_disjoint_work(self):
        rows = measurements()
        rows[1]["epoch_ticks"], rows[1]["bulk_ticks"] = "750", "300"
        with self.assertRaisesRegex(ValueError, "Disjoint execution"):
            MATRIX.summarize(rows, small_manifest())

    def test_code_inventory_excludes_external_resources_and_detects_class_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = root / "classes"
            classes.mkdir()
            (classes / "Example.class").write_bytes(b"authored fixture class placeholder")
            resource = classes / "unrelated.bin"
            resource.write_bytes(b"excluded fixture resource")
            cp = root / "classpath"
            cp.write_text(str(classes))
            _, before = MATRIX.inventory_classpath(cp)
            resource.write_bytes(b"different excluded fixture resource")
            self.assertEqual(before, MATRIX.inventory_classpath(cp)[1])
            self.assertNotIn("unrelated.bin", json.dumps(before))
            (classes / "Example.class").write_bytes(b"different authored class placeholder")
            self.assertNotEqual(before, MATRIX.inventory_classpath(cp)[1])

    def test_post_run_edits_cannot_be_summarized_as_the_same_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            MATRIX.write_json(root / "manifest.json", small_manifest())
            (root / "results.tsv").write_text("not a completed raw result")
            MATRIX.write_json(root / "metadata.json", {
                "status": "complete", "manifest_sha256": MATRIX.sha256(root / "manifest.json"),
                "results_sha256": "0" * 64})
            with self.assertRaisesRegex(ValueError, "Raw measurements changed"):
                MATRIX.summarize_output(root)


if __name__ == "__main__":
    unittest.main()
