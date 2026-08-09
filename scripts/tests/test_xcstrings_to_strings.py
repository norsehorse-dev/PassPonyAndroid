#!/usr/bin/env python3
"""Plain-assert unit tests for scripts/xcstrings_to_strings.py.

Run with: python3 scripts/tests/test_xcstrings_to_strings.py
No pytest dependency (none is available in this environment); each
check is a bare assert, and the runner just calls every test_* function
and reports a pass/fail count.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import xcstrings_to_strings as m  # noqa: E402


# --- format-specifier conversion --------------------------------------------


def test_single_non_positional_at_becomes_s():
    assert m.convert_value("Edit %@") == "Edit %1$s"


def test_multiple_non_positional_specs_auto_numbered_in_order():
    assert m.convert_value("Import failed for %@: %@") == "Import failed for %1$s: %2$s"


def test_explicit_positional_specs_preserved():
    assert m.convert_value("%1$@%2$@ · %3$@") == "%1$s%2$s · %3$s"


def test_lld_and_llu_become_d():
    assert m.convert_value("%lld unpushed change") == "%1$d unpushed change"
    assert m.convert_value("Imported %llu key files.") == "Imported %1$d key files."


def test_bare_percent_with_no_format_args_is_not_doubled():
    # A lone literal percent sign with nothing else to format shouldn't
    # be touched -- doubling it unconditionally would corrupt plain text
    # like a discount label ("50% off") that happens to contain '%'.
    assert m.convert_value("50% off") == "50% off"


def test_stray_percent_is_doubled_only_when_a_real_format_arg_exists():
    # Here there IS a real argument (%@), so any other literal percent
    # in the same string must be escaped or Android's formatter throws.
    assert m.convert_value("%@ (100% done)") == "%1$s (100%% done)"


# --- literal escaping ---------------------------------------------------------


def test_apostrophes_and_quotes_are_backslash_escaped():
    assert m.convert_value("Couldn't open \"file\"") == "Couldn\\'t open \\\"file\\\""


def test_xml_metacharacters_are_entity_escaped():
    assert m.convert_value("A & B < C > D") == "A &amp; B &lt; C &gt; D"


# --- key naming ----------------------------------------------------------------


def test_slugify_basic():
    assert m.slugify("Add a pass…") == "xc_add_a_pass"


def test_slugify_pure_punctuation_falls_back_to_str():
    assert m.slugify("%@%@ · %@") == "xc_str"


def test_slugify_is_length_capped():
    long_text = "word " * 30
    slug = m.slugify(long_text)
    assert len(slug) <= 48 + len("xc_")
    assert not slug.endswith("_")


# --- plural / resource rendering ------------------------------------------------


def test_plural_quantities_extracts_present_categories_only():
    node = {
        "variations": {
            "plural": {
                "one": {"stringUnit": {"state": "translated", "value": "%lld change"}},
                "few": {"stringUnit": {"state": "translated", "value": "%lld changes (few)"}},
                "other": {"stringUnit": {"state": "translated", "value": "%lld changes"}},
            }
        }
    }
    result = m.plural_quantities(node)
    assert result == {
        "one": "%lld change",
        "few": "%lld changes (few)",
        "other": "%lld changes",
    }


def test_render_resource_simple_string():
    r = m.Resource("xc_hello", "Hello %1$s", None)
    assert m.render_resource(r) == '    <string name="xc_hello">Hello %1$s</string>'


def test_render_resource_plural_orders_quantities_by_cldr_order():
    r = m.Resource(
        "xc_changes",
        None,
        {"other": "%1$d changes", "one": "%1$d change", "few": "%1$d changes (few)"},
    )
    rendered = m.render_resource(r)
    # CLDR/Android order is zero, one, two, few, many, other -- verify
    # "one" precedes "few" precedes "other" regardless of dict order.
    assert rendered.index('quantity="one"') < rendered.index('quantity="few"')
    assert rendered.index('quantity="few"') < rendered.index('quantity="other"')


# --- localized_value fallback behavior ------------------------------------------


def test_english_falls_back_to_key_text_when_no_en_localization():
    entry = {"localizations": {"de": {"stringUnit": {"state": "translated", "value": "Benutzername"}}}}
    assert m.localized_value(entry, "en", "username") == ("username", None)


def test_missing_language_returns_none():
    entry = {"localizations": {"de": {"stringUnit": {"state": "translated", "value": "Info"}}}}
    assert m.localized_value(entry, "fr", "About") is None


# --- merge_into_english: append-only, idempotent --------------------------------


def test_merge_into_english_appends_new_block_once():
    existing = (
        "<resources>\n"
        '    <string name="hand_picked">Hand picked</string>\n'
        "</resources>\n"
    )
    resources = [m.Resource("xc_new_key", "New value", None)]

    class FakePath:
        """Minimal stand-in so merge_into_english's Path-based API can
        run against an in-memory string without touching real files."""

        def __init__(self, text):
            self._text = text

        def exists(self):
            return True

        def read_text(self, encoding="utf-8"):
            return self._text

    first_pass = m.merge_into_english(FakePath(existing), resources)
    assert "hand_picked" in first_pass  # untouched
    assert "xc_new_key" in first_pass
    assert first_pass.count(m.GENERATED_BLOCK_START) == 1

    # Re-running the merge against its own prior output must replace the
    # block in place, not append a second one.
    second_pass = m.merge_into_english(FakePath(first_pass), resources)
    assert second_pass.count(m.GENERATED_BLOCK_START) == 1
    assert second_pass == first_pass


def test_merge_into_english_leaves_hand_authored_entries_byte_identical():
    existing = (
        "<resources>\n"
        "    <!-- a hand-written comment -->\n"
        '    <string name="foo">Bar &amp; baz</string>\n'
        "</resources>\n"
    )

    class FakePath:
        def __init__(self, text):
            self._text = text

        def exists(self):
            return True

        def read_text(self, encoding="utf-8"):
            return self._text

    result = m.merge_into_english(FakePath(existing), [])
    assert '<string name="foo">Bar &amp; baz</string>' in result
    assert "a hand-written comment" in result


# --- duplicate-name regression (this shipped broken once already) --------------


def test_select_new_english_resources_drops_matched_names():
    resources = [
        m.Resource("settings_language", "Language", None),  # matched: hand-authored name
        m.Resource("xc_welcome_to_passpony", "Welcome to PassPony", None),  # generated
    ]
    kept = m.select_new_english_resources(resources)
    assert [r.name for r in kept] == ["xc_welcome_to_passpony"]


def test_validate_no_duplicate_names_catches_repeated_string_and_plurals():
    xml = (
        "<resources>\n"
        '    <string name="a">One</string>\n'
        '    <string name="b">Two</string>\n'
        '    <string name="a">One again</string>\n'
        '    <plurals name="c"><item quantity="other">x</item></plurals>\n'
        '    <plurals name="c"><item quantity="other">y</item></plurals>\n'
        "</resources>\n"
    )
    assert m.validate_no_duplicate_names(xml) == ["a", "c"]


def test_validate_no_duplicate_names_clean_file_reports_nothing():
    xml = (
        "<resources>\n"
        '    <string name="a">One</string>\n'
        '    <string name="b">Two</string>\n'
        "</resources>\n"
    )
    assert m.validate_no_duplicate_names(xml) == []


# --- name map round-trip ---------------------------------------------------------


def test_name_map_render_is_sorted_and_round_trips():
    mapping = {"z key": "xc_z", "a key": "xc_a"}
    rendered = m.render_name_map(mapping)
    assert rendered.index('"a key"') < rendered.index('"z key"')
    import json

    assert json.loads(rendered) == mapping


def test_generated_header_has_no_double_hyphen():
    # XML forbids "--" anywhere inside a comment body; this regressed
    # once already (the header literally said "hand-edit --"), which
    # made every generated non-English strings.xml fail to parse.
    assert "--" not in m.GENERATED_HEADER.replace("<!--", "").replace("-->", "")


def main() -> int:
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_") and callable(fn)]
    failures = []
    for name, fn in tests:
        try:
            fn()
        except AssertionError as e:
            failures.append((name, str(e)))
            print(f"FAIL {name}: {e}")
        except Exception as e:  # noqa: BLE001
            failures.append((name, repr(e)))
            print(f"ERROR {name}: {e!r}")
        else:
            print(f"ok   {name}")
    print(f"\n{len(tests) - len(failures)}/{len(tests)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
