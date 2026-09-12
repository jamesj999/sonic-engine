#!/usr/bin/env python3
"""Plan and run ordinary Maven test categories without changing CI/release suites."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import math
import uuid
import xml.etree.ElementTree as ET

from category_artifacts import compact_run, prune_runs, run_logged, acknowledge_run
from category_control import DEFAULT_MINUTES, IDLE_SECONDS, preflight, check_repeat, record_attempt, record_outcome, is_broad

ROOT = Path(__file__).resolve().parents[2]
TEST_ROOT = Path('src/test/java')
PACKAGE = 'com/openggf/'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
ROM_PROPERTIES = {
    '69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b': 'sonic1.rom.path',
    '8bca5dcef1af3e00098666fd892dc1c2a76333f9': 'sonic2.rom.path',
    'cfbf98c36c776677290a872547ac47c53d2761d6': 's3k.rom.path',
}


def policy(root=ROOT):
    data = json.loads((root / 'tools/testing/test-categories.json').read_text())
    valid = set(data['categories'])
    for category, _ in data['owners']:
        if category not in valid:
            raise ValueError(f'Unknown owner category: {category}')
    for category, pattern in (data['name_owners'] | data['fallback_name_owners']).items():
        if category not in valid:
            raise ValueError(f'Unknown name category: {category}')
        re.compile(pattern)
    for rule in data['change_rules']:
        if not rule['categories'] or not set(rule['categories']) <= valid:
            raise ValueError(f'Invalid change rule: {rule}')
    return data


def ant_match(path, pattern):
    """Surefire's Ant-style paths: **/ can also match zero directories."""
    escaped = re.escape(pattern)
    escaped = escaped.replace(r'\*\*/', '(?:.*/)?')
    escaped = escaped.replace(r'\*\*', '.*').replace(r'\*', '[^/]*').replace(r'\?', '[^/]')
    return re.fullmatch(escaped, path) is not None


def inventory(root, data):
    pom = ET.parse(root / 'pom.xml')
    config = pom.find("m:build/m:plugins/m:plugin[m:artifactId='maven-surefire-plugin']/m:configuration", NS)
    if config is None:
        raise ValueError('Cannot find ordinary Surefire configuration')
    # Read existing exclusions: never bring trace/native/manual classes into the ordinary lane.
    excludes = [e.text for e in config.findall('m:excludes/m:exclude', NS)]
    defaults = ['**/Test*.java', '**/*Test.java', '**/*Tests.java', '**/*TestCase.java']
    includes = [e.text for e in config.findall('m:includes/m:include', NS)] or defaults
    result = {}
    for file in sorted((root / TEST_ROOT).rglob('*.java')):
        path = file.relative_to(root / TEST_ROOT).as_posix()
        if any(ant_match(path, p) for p in includes) and not any(ant_match(path, p) for p in excludes):
            result[path] = owners(path, data)
    if not result:
        raise ValueError('Ordinary test inventory is empty')
    return result


def owners(path, data):
    relative = path.removeprefix(PACKAGE)
    found = set()
    for category, prefixes in data['owners']:
        if relative.startswith(tuple(prefixes)):
            found.add(category)
            break
    for category, pattern in data['name_owners'].items():
        if re.search(pattern, Path(path).stem):
            found.add(category)
    if not found:
        for category, pattern in data['fallback_name_owners'].items():
            if re.search(pattern, Path(path).stem):
                found.add(category)
    return found or {'common'}


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root).decode().strip()


def changed_files(root, base):
    # Validate the ref before using it in a diff; include staged, unstaged, deleted,
    # renamed (both paths), and untracked files, as well as committed branch work.
    base_sha = git(root, 'rev-parse', '--verify', f'{base}^{{commit}}')
    raw = subprocess.check_output(['git', 'diff', '--name-only', '--no-renames', '-z', base_sha, '--'], cwd=root)
    raw += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=root)
    return base_sha, sorted(set(p for p in raw.decode().split('\0') if p))


def select_changes(paths, data):
    categories = {'common'}
    reasons = []
    full = False
    for path in paths:
        if path.startswith('src/test/java/') and path.endswith('.java') and re.fullmatch(r'(Test.+|.+Tests?|.+TestCase)\.java', Path(path).name):
            selected = owners(path.removeprefix('src/test/java/'), data)
            reason = 'test ownership'
            if selected == {'common'}:
                selected = set(data['categories'])
                reason = 'shared test infrastructure: full ordinary suite'
                full = True
        else:
            # Multiple matching rules add coverage; ordering cannot narrow a rule.
            selected = set()
            for rule in data['change_rules']:
                if path.startswith(tuple(rule['prefixes'])):
                    selected.update(rule['categories'])
            reason = 'subsystem rule'
            if not selected:
                selected = set(data['categories'])
                reason = 'shared or unclassified change: full ordinary suite'
                full = True
        categories.update(selected)
        reasons.append({'path': path, 'categories': sorted(selected), 'reason': reason})
    return categories, full, reasons


def make_plan(root, data, base, requested, guards=False):
    tests = inventory(root, data)
    base_sha, paths = changed_files(root, base) if base else (None, [])
    selected, full, reasons = select_changes(paths, data) if base else ({'common'}, False, [])
    if 'all' in requested:
        selected = set(data['categories'])
        full = True
    else:
        selected.update(requested)
    if not selected <= set(data['categories']):
        raise ValueError('Unknown category')
    chosen = sorted(p for p, labels in tests.items() if labels & selected)
    if not chosen:
        raise ValueError('No tests selected')
    return {'schema': 1, 'head': git(root, 'rev-parse', 'HEAD'), 'base': base_sha,
            'mode': 'change-based' if base else 'focused', 'full': full,
            'categories': sorted(selected), 'reasons': reasons, 'tests': chosen,
            'inventory_count': len(tests), 'guards': bool(base or full or guards),
            'scope': 'ordinary suite and guards; explicit trace/native profiles are separate'}


def rom_args(root):
    found = {}
    for path in sorted(root.glob('*.gen')):
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha1').hexdigest()
        if digest in ROM_PROPERTIES:
            found.setdefault(ROM_PROPERTIES[digest], str(path.resolve()))
    return [f'-D{key}={value}' for key, value in sorted(found.items())]


def summarize(reports):
    totals = Counter(tests=0, failures=0, errors=0, skipped=0)
    skipped = []
    failures = []
    skipped_count = failed_count = 0
    files = sorted(reports.glob('TEST-*.xml'))
    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
        for case in suite.findall('testcase'):
            skip = case.find('skipped')
            if skip is not None:
                skipped_count += 1
                if len(skipped) < 1000:
                    skipped.append({'class': (case.get('classname') or '')[:512],
                                    'test': (case.get('name') or '')[:512],
                                    'reason': skip.get('message', skip.text or '')[:4096]})
            for kind in ('failure', 'error'):
                failure = case.find(kind)
                if failure is not None:
                    failed_count += 1
                    if len(failures) < 1000:
                        failures.append({'class': (case.get('classname') or '')[:512],
                                         'test': (case.get('name') or '')[:512], 'kind': kind,
                                         'type': failure.get('type', '')[:512],
                                         'message': failure.get('message', '')[:2048],
                                         'detail': (failure.text or '')[:8192]})
    return {'reports': len(files), **totals, 'skipped_cases': skipped,
            'failed_cases': failures, 'skipped_cases_omitted': skipped_count - len(skipped),
            'failed_cases_omitted': failed_count - len(failures)}


def tree_state(root):
    digest = hashlib.sha256()
    digest.update(git(root, 'rev-parse', 'HEAD').encode())
    digest.update(subprocess.check_output(['git', 'diff', '--binary', 'HEAD', '--'], cwd=root))
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=root)
    for name in sorted(filter(None, untracked.decode().split('\0'))):
        digest.update(name.encode())
        digest.update((root / name).read_bytes())
    return digest.hexdigest()


def run_plan(root, plan, max_minutes=DEFAULT_MINUTES, repeat_reason=None, keep_diagnostics=False):
    target = root / 'target'
    target.mkdir(exist_ok=True)
    lock = target / 'category-tests.lock'
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise ValueError(f'Category runner already owns {lock}; inspect its PID before removing a stale lock')
    run = None
    status = 'incomplete'
    results = []
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(str(os.getpid()) + '\n')
        base = target / 'category-tests'
        check_repeat(target, plan, repeat_reason)
        prune_runs(base)
        run = base / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8])
        run.mkdir(parents=True)
        if keep_diagnostics:
            (run / 'keep-diagnostics').touch()
        print(f'Bounded diagnostics: {run}', flush=True)
        print(f"Categories: {', '.join(plan['categories'])}; "
              f"{len(plan['tests'])}/{plan['inventory_count']} candidate classes; "
              f"guards={'on' if plan['guards'] else 'off'}", flush=True)
        initial_state = tree_state(root)
        plan['working_tree_fingerprint'] = initial_state
        plan['max_minutes'] = max_minutes
        plan['repeat_reason'] = repeat_reason
        (run / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
        includes = run / 'includes.txt'
        includes.write_text('\n'.join(plan['tests']) + '\n')
        record_attempt(target, run, plan, repeat_reason)
        deadline = time.monotonic() + max_minutes * 60
        results = []
        failed = False
        for lane in (('ordinary', 'guards') if plan['guards'] else ('ordinary',)):
            reports = run / f'{lane}-reports'
            temporary = run / f'{lane}-tmp'
            temporary.mkdir()
            command = ['mvn', '-Dmse=off', 'test', '-B',
                       f'-Dopenggf.surefire.reports={reports}',
                       f'-Dopenggf.test.tmpdir={temporary}']
            if lane == 'guards':
                command.append('-Pguards')
            elif not plan['full']:
                command.append(f'-Dsurefire.includesFile={includes}')
            command.extend(rom_args(root))
            (run / f'{lane}-command.json').write_text(json.dumps(command, indent=2) + '\n')
            print(f'Running {lane}; rolling output: {run / (lane + ".log")}', flush=True)
            try:
                lane_started = time.monotonic()
                exit_code = run_logged(command, root, run / f'{lane}.log', temporary,
                                       timeout=deadline - lane_started, idle_timeout=IDLE_SECONDS)
            except TimeoutError as error:
                summary = summarize(reports)
                summary.update(lane=lane, status='incomplete', reason=str(error),
                               elapsed_seconds=round(time.monotonic() - lane_started, 2))
                results.append(summary)
                (run / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
                raise
            finally:
                # run_logged reaps Maven before returning/raising. Only this run's
                # temporary files are removed, never another run's target/test-tmp.
                shutil.rmtree(temporary)
            summary = summarize(reports)
            summary.update(lane=lane, exit_code=exit_code, elapsed_seconds=round(time.monotonic() - lane_started, 2))
            results.append(summary)
            (run / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
            print(json.dumps({k: v for k, v in summary.items()
                              if k not in ('skipped_cases', 'failed_cases')}), flush=True)
            if tree_state(root) != initial_state:
                raise ValueError('Working tree changed during validation; stop before another lane')
            if not summary['reports'] or summary['tests'] == summary['skipped']:
                status = 'failed'
                return 1
            failed |= bool(exit_code or summary['failures'] or summary['errors'])
        status = 'failed' if failed else 'passed'
        print('Selected checks ' + status + '. Inspect results.json for skips/failures; '
              'a category pass is not full-suite certification.')
        return int(failed)
    finally:
        try:
            if run is not None:
                (run / 'status.json').write_text(json.dumps({'status': status}) + '\n')
                record_outcome(target, run, status, results)
                compact_run(run, failed=(status != 'passed' or keep_diagnostics))
                # Current results remain only until consumed. The next launch removes
                # abandoned results; explicit opt-in retention is separately bounded.
                prune_runs(run.parent, active=None if keep_diagnostics else run)
                print(f'After inspecting results, delete diagnostics: python3 tools/testing/run_categories.py '
                      f'--acknowledge {run.name}', flush=True)
        finally:
            lock.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list', action='store_true', help='list categories and candidate class counts')
    parser.add_argument('--base', help='compare the working tree against this integration/base commit')
    parser.add_argument('--category', action='append', default=[], help='add a category (repeatable), or all')
    parser.add_argument('--guards', action='store_true', help='also run guards during focused development (automatic with --base or all)')
    parser.add_argument('--run', action='store_true', help='execute the plan; otherwise print JSON only')
    parser.add_argument('--preflight', action='store_true', help='check Java and guard tools without running tests')
    parser.add_argument('--max-minutes', type=float, default=DEFAULT_MINUTES, help='total Maven budget across lanes (default: 40 minutes)')
    parser.add_argument('--repeat-reason', help='record why another broad run is necessary; does not skip checks')
    parser.add_argument('--acknowledge', metavar='RUN_ID', help='delete inspected results without running tests')
    parser.add_argument('--keep-diagnostics', action='store_true', help='explicitly retain bounded diagnostics across runs until acknowledged')
    parser.add_argument('--json', action='store_true', help='print the complete dry-run plan, including every class')
    args = parser.parse_args(argv)
    try:
        if not math.isfinite(args.max_minutes) or args.max_minutes <= 0:
            parser.error('--max-minutes must be a positive finite number')
        if args.repeat_reason is not None and not 10 <= len(args.repeat_reason.strip()) <= 2000:
            parser.error('--repeat-reason must explain the reason in 10–2000 characters')
        if args.acknowledge:
            if args.run or args.preflight or args.base or args.category or args.guards or args.list or args.json or args.keep_diagnostics or args.repeat_reason:
                parser.error('--acknowledge must be used alone')
            acknowledge_run(ROOT, args.acknowledge)
            print('Diagnostics deleted; broad-attempt receipt preserved.')
            return 0
        if args.keep_diagnostics and not args.run:
            parser.error('--keep-diagnostics requires --run')
        data = policy()
        if args.list:
            tests = inventory(ROOT, data)
            for category, description in data['categories'].items():
                print(f'{category:12} {sum(category in labels for labels in tests.values()):4}  {description}')
            return 0
        if not args.base and not args.category:
            parser.error('use --base for change-based checks or --category for focused development')
        if set(args.category) - (set(data['categories']) | {'all'}):
            parser.error('unknown category; use --list')
        plan = make_plan(ROOT, data, args.base, args.category, args.guards)
        if args.run or args.preflight:
            if is_broad(plan):
                print('BROAD selection: allow tens of minutes; the September normalization run '
                      'took ~24 minutes ordinary + ~10 minutes guards. This is context, not an ETA.', flush=True)
            print(f'Time limit: {args.max_minutes:g} minutes total Maven time; '
                  '10 minutes without output. Timeout means incomplete; no automatic retry.', flush=True)
            if sys.platform == 'darwin':
                print('Native tests need macOS display/service access. Launch in the known working '
                      'native environment; tool preflight does not prove GLFW access.', flush=True)
            preflight(ROOT, plan)
            if args.preflight and not args.run:
                print('Tool prerequisites passed; no tests executed.')
                return 0
            return run_plan(ROOT, plan, args.max_minutes, args.repeat_reason, args.keep_diagnostics)
        display = plan if args.json else {
            **{k: v for k, v in plan.items() if k not in ('tests', 'reasons')},
            'selected_classes': len(plan['tests']), 'reasons': plan['reasons'][:50],
            'additional_changed_paths': max(0, len(plan['reasons']) - 50)}
        print(json.dumps(display, indent=2) + '\n')
        return 0
    except KeyboardInterrupt:
        print('Category validation interrupted; Maven stopped and temporary output cleaned.', file=sys.stderr)
        return 130
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        print(f'Category validation failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
