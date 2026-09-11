#!/usr/bin/env python3
"""Compare two fresh release-trace runs; changed red evidence requires review.

This consumes existing Maven/XML/trace evidence only. It neither runs gameplay nor
changes assertions. The workflow runs Maven directly and preserves its exit code.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


class EvidenceError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f'duplicate JSON key: {key} in {path}')
            result[key] = value
        return result
    try:
        return json.loads(path.read_text(), object_pairs_hook=unique)
    except (OSError, ValueError) as error:
        raise EvidenceError(f'cannot read {path}: {error}') from error


def git(tree, *args):
    return subprocess.check_output(['git', '-C', str(tree), *args], text=True).strip()


def source_state(tree):
    # Use actual files, including local edits: a dirty working tree must never be
    # represented by its HEAD alone. Fixture hashes remain independent of paths.
    digest = hashlib.sha256()
    inventory = []
    for directory in ('src/main', 'src/test'):
        for path in sorted((tree / directory).rglob('*')):
            if path.is_file():
                relative = path.relative_to(tree).as_posix()
                digest.update(relative.encode() + b'\0')
                digest.update(path.read_bytes())
                if relative.endswith('.java') and '/tests/trace/' in relative:
                    inventory.append(relative)
    digest.update((tree / 'pom.xml').read_bytes())
    fixture_digest = hashlib.sha256()
    for path in sorted((tree / 'src/test/resources/traces').rglob('*')):
        if path.is_file():
            fixture_digest.update(path.relative_to(tree).as_posix().encode() + b'\0')
            fixture_digest.update(path.read_bytes())
    return {'commit': git(tree, 'rev-parse', 'HEAD'), 'source_digest': digest.hexdigest(),
            'fixtures': fixture_digest.hexdigest(), 'test_inventory': inventory}


def clean_checkout(tree):
    # Optional research submodules do not participate in runtime/test sources.
    require(not git(tree, 'status', '--porcelain', '--ignore-submodules=all'),
            'release evidence requires a clean checkout (excluding research submodules)')


def begin(tree, output):
    clean_checkout(tree)
    require(not output.exists(), f'run marker already exists: {output}')
    for directory in ('target/surefire-reports', 'target/trace-reports'):
        path = tree / directory
        require(not path.exists() or not any(path.iterdir()), f'remove/archive prior reports before starting: {path}')
    state = source_state(tree)
    state['started_ns'] = time.time_ns()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(state, indent=2) + '\n')


def completed_summary(log, exit_code):
    require(exit_code in (0, 1), f'Maven exit {exit_code} is not a completed test verdict')
    forbidden = ('The forked VM terminated', 'There was an error in the forked process',
                 'OutOfMemoryError', 'Java heap space', 'COMPILATION ERROR',
                 'No tests were executed', 'No tests matching pattern')
    require(not any(item in log for item in forbidden), 'Maven compilation/fork/selection failure')
    require('[INFO] Finished at:' in log, 'Maven run did not finish')
    matches = re.findall(r'Tests run: ([\d,]+), Failures: ([\d,]+), Errors: ([\d,]+), Skipped: ([\d,]+)', log)
    require(bool(matches), 'Maven produced no final test summary')
    summary = tuple(int(value.replace(',', '')) for value in matches[-1])
    tests, failures, errors, skips = summary
    require(tests > skips and errors == 0, f'invalid release trace summary: {summary}')
    require(exit_code == (1 if failures else 0), 'Maven exit disagrees with assertion failures')
    require(('[INFO] BUILD FAILURE' if exit_code else '[INFO] BUILD SUCCESS') in log,
            'Maven build conclusion disagrees with exit')
    return summary


def normalize(value, tree):
    if isinstance(value, str):
        return value.replace(str(tree), '<CHECKOUT>')
    if isinstance(value, list):
        return [normalize(item, tree) for item in value]
    if isinstance(value, dict):
        return {key: normalize(item, tree) for key, item in value.items()}
    return value


def fresh(path, started_ns):
    require(path.is_file() and not path.is_symlink(), f'missing/nonregular evidence: {path}')
    require(path.stat().st_mtime_ns >= started_ns, f'stale evidence: {path}')


def collect(tree, start, log_path, exit_code):
    clean_checkout(tree)
    marker = read_json(start)
    state = source_state(tree)
    for key, value in state.items():
        require(marker.get(key) == value, f'{key} changed while the trace run was executing')
    started = marker['started_ns']
    fresh(log_path, started)
    totals = completed_summary(log_path.read_text(errors='replace'), exit_code)
    tests = {}
    suite_executions = {}
    for count, failures, errors, skips, classname in re.findall(
            r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: [^\n]*? -- in ([\w.$]+)',
            log_path.read_text(errors='replace')):
        suite_executions.setdefault(classname, []).append([int(count), int(failures), int(errors), int(skips)])
    xml_suites = {}
    xml_totals = [0, 0, 0, 0]
    reports = sorted((tree / 'target/surefire-reports').glob('TEST-*.xml'))
    require(bool(reports), 'no Surefire reports')
    executed_replays = 0
    for path in reports:
        fresh(path, started)
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise EvidenceError(f'malformed XML {path}: {error}') from error
        cases = list(root.iter('testcase'))
        suite_name = root.get('name', '')
        declared = int(root.get('tests', '-1'))
        # Surefire may retain every repeated singleton row while its root
        # counter describes only the final invocation. Reconcile only complete,
        # identical all-pass rows backed by one completed log verdict per row.
        # Canonical evidence then matches the overwritten-single-row variant.
        executions = suite_executions.get(suite_name, [])
        if len(cases) > 1 and len(executions) == len(cases) and all(
                row == [1, 0, 0, 0] for row in executions):
            identities = {(c.get('classname', ''), c.get('name', '')) for c in cases}
            all_passed = all(c.find(tag) is None for c in cases
                             for tag in ('failure', 'error', 'skipped'))
            if len(identities) == 1 and all(next(iter(identities))) and all_passed:
                require(declared in (1, len(cases)), f'incomplete testcase XML: {path}')
                cases = cases[:1]
                declared = 1
        require(declared == len(cases), f'incomplete testcase XML: {path}')
        suite_counts = [len(cases), sum(c.find('failure') is not None for c in cases),
                        sum(c.find('error') is not None for c in cases),
                        sum(c.find('skipped') is not None for c in cases)]
        for attribute, count in zip(('failures', 'errors', 'skipped'), suite_counts[1:]):
            require(root.get(attribute) is None or int(root.get(attribute)) == count,
                    f'XML {attribute} counter disagrees: {path}')
        require(suite_name and suite_name not in xml_suites, f'missing/duplicate XML suite: {path}')
        xml_suites[suite_name] = suite_counts
        for case in cases:
            classname = case.get('classname', '')
            name = case.get('name', '')
            require(classname and name, f'unnamed testcase in {path}')
            identity = classname + '#' + name
            outcome = {'status': 'passed', 'occurrences': 1}
            xml_totals[0] += 1
            for tag, index in (('failure', 1), ('error', 2), ('skipped', 3)):
                entry = case.find(tag)
                if entry is not None:
                    outcome = {'status': tag, 'type': entry.get('type', ''),
                               'message': normalize(entry.get('message') or (entry.text or '').split('\n')[0], tree), 'occurrences': 1}
                    xml_totals[index] += 1
                    break
            require(outcome['status'] != 'error', f'test error: {identity}')
            replay = classname.split('$')[0].endswith(('TraceReplay', 'RunChain'))
            if replay:
                require(outcome['status'] != 'skipped', f'required replay skipped: {identity}')
                executed_replays += 1
            if identity in tests:
                previous = dict(tests[identity])
                previous['occurrences'] = 1
                require(previous == outcome, f'conflicting duplicate testcase: {identity}')
                tests[identity]['occurrences'] += 1
            else:
                tests[identity] = outcome
    # Surefire overwrites a class XML when JUnit launches that class repeatedly.
    # Retain every completed suite verdict. Only repeated singleton all-pass
    # suites can be reconciled; never hide an overwritten failure or missing suite.
    require(suite_executions.keys() == xml_suites.keys(), 'Maven/XML suite inventory disagrees')
    for classname, executions in suite_executions.items():
        require(all(row == xml_suites[classname] for row in executions),
                f'Maven/XML suite verdict disagrees: {classname}')
        if len(executions) > 1:
            require(xml_suites[classname] in ([0, 0, 0, 0], [1, 0, 0, 0]),
                    f'overwritten nonsingleton/nonpassing suite: {classname}')
            xml_totals[0] += (len(executions) - 1) * xml_suites[classname][0]
    require(tuple(xml_totals) == totals, f'Maven/XML totals disagree: {totals} versus {xml_totals}')
    require(executed_replays > 0, 'no executed ROM-backed trace replays')
    traces = {}
    trace_dir = tree / 'target/trace-reports'
    for path in sorted(trace_dir.rglob('*.json')):
        if path.name.endswith('.owner.json'):
            require(path.with_name(path.name.removesuffix('.owner.json')).is_file(), f'orphan owner metadata: {path}')
            continue
        fresh(path, started)
        owner_path = path.with_name(path.name + '.owner.json')
        fresh(owner_path, started)
        owner = read_json(owner_path)
        require(isinstance(owner, dict) and owner.get('logical_key') and
                re.fullmatch(r'[a-f0-9]{64}', owner.get('owner_key', '')), f'invalid trace owner: {path}')
        physical = Path(owner.get('physical_path', ''))
        if not physical.is_absolute():
            physical = tree / physical
        require(physical.resolve() == path.resolve(), f'trace owner points to another report: {path}')
        payload = read_json(path)
        require(isinstance(payload, dict) and 'error' not in payload, f'invalid trace report: {path}')
        # Existing fixture coverage gaps remain visible and must match exactly;
        # missing report files/owners are independently rejected above.
        key = path.relative_to(trace_dir).as_posix()
        traces[key] = {'owner': normalize(owner, tree), 'payload': normalize(payload, tree)}
    require(bool(traces), 'no owned trace JSON reports')
    result = {'schema': 1, **state, 'tests': tests, 'reports': traces, 'summary': list(totals), 'suite_executions': suite_executions}
    validate_manifest(result)
    return result


def validate_manifest(data):
    require(isinstance(data, dict) and data.get('schema') == 1, 'unsupported evidence schema')
    for key in ('commit', 'fixtures', 'test_inventory', 'tests', 'reports'):
        require(bool(data.get(key)), f'empty/missing evidence field: {key}')
    require(isinstance(data['tests'], dict) and isinstance(data['reports'], dict), 'invalid evidence maps')


def compare(base, candidate):
    validate_manifest(base)
    validate_manifest(candidate)
    violations = []
    for key in ('fixtures', 'test_inventory', 'suite_executions'):
        if base.get(key) != candidate.get(key):
            violations.append(f'{key} changed: review scope/fixtures and establish a reviewed baseline')
    for kind in ('tests', 'reports'):
        for key in sorted(base[kind].keys() | candidate[kind].keys()):
            if key not in candidate[kind]:
                violations.append(f'missing {kind}: {key}')
            elif key not in base[kind]:
                violations.append(f'new {kind} requires baseline review: {key}')
            elif base[kind][key] != candidate[kind][key]:
                violations.append(f'changed {kind} requires review: {key}')
    return violations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    start = sub.add_parser('begin')
    start.add_argument('--tree', type=Path, required=True)
    start.add_argument('--output', type=Path, required=True)
    record = sub.add_parser('collect')
    record.add_argument('--tree', type=Path, required=True)
    record.add_argument('--start', type=Path, required=True)
    record.add_argument('--log', type=Path, required=True)
    record.add_argument('--exit-code', type=int, required=True)
    record.add_argument('--output', type=Path, required=True)
    check = sub.add_parser('compare')
    check.add_argument('--baseline', type=Path, required=True)
    check.add_argument('--candidate', type=Path, required=True)
    check.add_argument('--expected-baseline', required=True)
    check.add_argument('--expected-candidate', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'begin':
            begin(args.tree.resolve(), args.output)
        elif args.command == 'collect':
            result = collect(args.tree.resolve(), args.start, args.log, args.exit_code)
            args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
            print(f"Collected {len(result['tests'])} test identities and {len(result['reports'])} owned trace reports at {result['commit']}")
        else:
            baseline = read_json(args.baseline)
            candidate = read_json(args.candidate)
            require(baseline.get('commit') == args.expected_baseline, 'baseline revision does not match the reviewed pin')
            require(candidate.get('commit') == args.expected_candidate, 'candidate revision does not match the release checkout')
            require(baseline.get('commit') != candidate.get('commit'), 'baseline and candidate must be distinct commits')
            violations = compare(baseline, candidate)
            if violations:
                print('\n'.join(violations), file=sys.stderr)
                return 1
            print(f"Release trace evidence unchanged: {len(candidate['tests'])} identities, {len(candidate['reports'])} owned reports; known failures/warnings retained")
        return 0
    except (EvidenceError, OSError, KeyError, TypeError, ValueError, subprocess.CalledProcessError) as error:
        print(f'release trace evidence rejected: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
