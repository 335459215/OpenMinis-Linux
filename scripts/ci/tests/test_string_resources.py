import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "check_string_refs", Path(__file__).resolve().parents[1] / "check_string_refs.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ResourceMergeTest(unittest.TestCase):
    def test_split_file_duplicate_in_translation_fails_before_gradle(self):
        with tempfile.TemporaryDirectory() as directory:
            values = Path(directory) / "values-zh"
            values.mkdir()
            for filename in ("strings_gateway.xml", "strings_navigation.xml"):
                (values / filename).write_text(
                    '<resources><string name="connections">连接</string></resources>', encoding="utf-8")
            duplicates = checker.duplicate_resources(directory)
            self.assertEqual(1, len(duplicates))
            self.assertEqual(("values-zh", ("string", "connections")), duplicates[0][:2])

    def test_qualifiers_types_and_documentation_do_not_collide(self):
        with tempfile.TemporaryDirectory() as directory:
            for qualifier in ("values", "values-zh", "values-night"):
                values = Path(directory) / qualifier
                values.mkdir()
                (values / "strings.xml").write_text('''<resources>
                    <!-- <string name="connections">old</string> -->
                    <string name="connections">Connections</string>
                    <plurals name="connections"><item quantity="other">%d</item></plurals>
                    <string-array name="items"><item>One</item><item>Two</item></string-array>
                    </resources>''', encoding="utf-8")
            self.assertEqual([], checker.duplicate_resources(directory))


if __name__ == "__main__":
    unittest.main()
