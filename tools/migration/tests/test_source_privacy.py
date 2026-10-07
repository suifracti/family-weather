import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("privacy_gate", Path(__file__).parents[1] / "check_source_privacy.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class SourcePrivacyTest(unittest.TestCase):
    def test_personal_paths_and_drive_identifiers_are_reported_without_values(self):
        inputs = ["/" + "Users/owner/private", "C:" + "\\Users\\owner\\private", "https:" + "//drive.google.com/file/d/PRIVATE_ID/view"]
        for text in inputs:
            with self.subTest(text_kind=inputs.index(text)):
                result = gate.inspect_text(text, "fixture.txt")
                self.assertTrue(result)
                self.assertNotIn(text, str(result))

    def test_private_location_and_live_logs_are_rejected(self):
        self.assertTrue(gate.inspect_text("latitude = 12.3456", "fixture.kt", ["12.3456"]))
        self.assertFalse(gate.inspect_text("latitude = 12.34567", "fixture.kt", ["12.3456"]))
        for member in ["latitude", "longitude", "canonicalLocationId", "message", "lat", "lon"]:
            line = 'println("${site.' + member + '}")'
            self.assertTrue(gate.inspect_text(line, "LiveScenarioVerificationTest.kt"))
        self.assertFalse(gate.inspect_text('println("WORKSITE")', "LiveScenarioVerificationTest.kt"))

    def test_empty_templates_and_placeholder_paths_are_allowed(self):
        text = 'api_key=\n<workspace>/family-weather\nlatitude = PRIVATE_ORCHARD_LATITUDE'
        self.assertFalse(gate.inspect_text(text, "fixture.txt"))

    def test_common_tokens_are_rejected_without_echoing_them(self):
        token = "ghp_" + "A" * 36
        result = gate.inspect_text(token, "fixture.txt")
        self.assertEqual(result[0]["kind"], "credential_token")
        self.assertNotIn(token, str(result))
