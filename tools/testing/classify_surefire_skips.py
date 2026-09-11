#!/usr/bin/env python3
"""Classify skipped surefire test cases against an explicit release skip policy.

    classify_surefire_skips.py --reports DIR --policy FILE [--capabilities gl=true,s2_bk2=false,...] [--json OUT] [--check-evidence]

Every skipped ``class#method`` identity in the surefire XML must match exactly one policy rule by exact
identity (no wildcards). A rule with ``allowed_when_absent: null`` is an opt-in test whose skip is always
optional. A rule naming an input is allowed to skip ONLY when the runner declares that input absent via
``--capabilities name=false``; if the runner declares it present (``name=true``) and the test still skipped,
that is a required test that did not run and the run fails. An input that the rule names but the caller
did not declare fails closed. Exit 0: all skips classified and allowed. Exit 1: unclassified, required-skipped,
or (with --check-evidence) stale rules. Exit 2: usage, unreadable reports, or an invalid policy.
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CATEGORIES = {"gl-context", "optional-fixture", "opt-in-benchmark", "opt-in-gate", "opt-in-diagnostic",
              "opt-in-capture", "scenario-assumption"}


class PolicyError(ValueError):
    pass


def load_policy(path):
    try:
        policy = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PolicyError(f"cannot read policy {path}: {exc}")
    if policy.get("version") != 1 or not isinstance(policy.get("rules"), list):
        raise PolicyError("policy must have version 1 and a rules list")
    inputs = policy.get("inputs") or {}
    seen = set()
    for rule in policy["rules"]:
        for key in ("match", "category", "evidence"):
            if not isinstance(rule.get(key), str) or not rule[key]:
                raise PolicyError(f"rule missing {key}: {rule}")
        ident = rule["match"]
        if "#" not in ident or "*" in ident or "?" in ident:
            raise PolicyError(f"rule match must be an exact class#method identity: {ident}")
        if ident in seen:
            raise PolicyError(f"duplicate rule for {ident}")
        seen.add(ident)
        if rule["category"] not in CATEGORIES:
            raise PolicyError(f"unknown category {rule['category']} for {ident}")
        if "allowed_when_absent" not in rule:
            raise PolicyError(f"rule {ident} must state allowed_when_absent explicitly (an input name or null)")
        absent = rule["allowed_when_absent"]
        if absent is not None and (not isinstance(absent, str) or absent not in inputs):
            raise PolicyError(f"rule {ident} names undeclared input {absent}")
        if rule["category"] == "gl-context" and absent != "gl":
            raise PolicyError(f"gl-context rule {ident} must use allowed_when_absent \"gl\" (never null)")
    return policy


def parse_capabilities(text):
    caps = {}
    for item in (text or "").split(","):
        item = item.strip()
        if not item:
            continue
        if "=" not in item:
            raise PolicyError(f"capability must be name=true|false: {item}")
        name, value = item.split("=", 1)
        name = name.strip(); value = value.strip().lower()
        if not name:
            raise PolicyError(f"capability name must not be empty: {item}")
        if value not in ("true", "false"):
            raise PolicyError(f"capability {name} must be true or false")
        if name in caps:
            raise PolicyError(f"capability {name} declared twice")
        caps[name] = value == "true"
    return caps


def read_skips(reports_dir):
    reports = Path(reports_dir)
    files = sorted(reports.glob("TEST-*.xml"))
    if not reports.is_dir() or not files:
        raise PolicyError(f"no surefire TEST-*.xml reports under {reports}")
    skipped = {}
    for report in files:
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as exc:
            raise PolicyError(f"unreadable report {report}: {exc}")
        for case in root.iter("testcase"):
            node = case.find("skipped")
            if node is None:
                continue
            ident = f"{case.attrib.get('classname', root.attrib.get('name', report.stem))}#{case.attrib.get('name', '<unnamed>')}"
            message = (node.attrib.get("message") or node.text or "").strip().splitlines()
            skipped[ident] = (message[0][:200] if message else "")
    return skipped


def classify(skipped, policy, capabilities):
    rules = {rule["match"]: rule for rule in policy["rules"]}
    result = {"allowed": [], "unclassified": [], "required_skipped": [], "undeclared_input": []}
    for ident in sorted(skipped):
        rule = rules.get(ident)
        entry = {"identity": ident, "message": skipped[ident]}
        if rule is None:
            result["unclassified"].append(entry)
            continue
        entry.update({"category": rule["category"], "evidence": rule["evidence"]})
        absent = rule.get("allowed_when_absent")
        if absent is None:
            result["allowed"].append(entry)
        elif absent not in capabilities:
            entry["input"] = absent
            result["undeclared_input"].append(entry)
        elif capabilities[absent]:
            entry["input"] = absent
            result["required_skipped"].append(entry)
        else:
            entry["input"] = absent
            result["allowed"].append(entry)
    return result


def stale_rules(policy, root):
    stale = []
    for rule in policy["rules"]:
        source = rule["evidence"].split(":", 1)[0]
        if not (Path(root) / source).is_file():
            stale.append({"identity": rule["match"], "evidence": rule["evidence"]})
    return stale


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reports", required=True, help="surefire report directory (TEST-*.xml)")
    parser.add_argument("--policy", required=True, help="release skip policy JSON")
    parser.add_argument("--capabilities", default="", help="comma list name=true|false of inputs the runner provides")
    parser.add_argument("--json", help="write the classification result as JSON to this path")
    parser.add_argument("--check-evidence", action="store_true", help="fail if a rule cites a source file that no longer exists")
    parser.add_argument("--root", default=".", help="repository root for --check-evidence (default: cwd)")
    args = parser.parse_args(argv)
    try:
        policy = load_policy(args.policy)
        capabilities = parse_capabilities(args.capabilities)
        skipped = read_skips(args.reports)
    except PolicyError as exc:
        print(f"classify_surefire_skips: {exc}", file=sys.stderr)
        return 2
    result = classify(skipped, policy, capabilities)
    result["stale_rules"] = stale_rules(policy, args.root) if args.check_evidence else []
    result["capabilities"] = capabilities
    result["counts"] = {key: len(value) for key, value in result.items() if isinstance(value, list)}
    result["counts"]["skipped"] = len(skipped)
    if args.json:
        Path(args.json).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for key, label in (("unclassified", "UNCLASSIFIED skip (no policy rule)"),
                       ("required_skipped", "REQUIRED test skipped although its input is declared present"),
                       ("undeclared_input", "skip depends on an input the runner did not declare (fail closed)"),
                       ("stale_rules", "STALE policy rule (evidence source missing)")):
        for entry in result[key]:
            print(f"{label}: {entry['identity']}" + (f" [{entry.get('input')}]" if entry.get("input") else "")
                  + (f" -- {entry['message']}" if entry.get("message") else ""), file=sys.stderr)
    counts = result["counts"]
    print(f"Release skip classification: skipped={counts['skipped']} allowed={counts['allowed']} "
          f"unclassified={counts['unclassified']} required_skipped={counts['required_skipped']} "
          f"undeclared_input={counts['undeclared_input']} stale_rules={counts['stale_rules']} "
          f"capabilities={','.join(f'{k}={str(v).lower()}' for k, v in sorted(capabilities.items())) or '-'}")
    failed = counts["unclassified"] or counts["required_skipped"] or counts["undeclared_input"] or counts["stale_rules"]
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
