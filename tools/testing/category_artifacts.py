"""Bounded diagnostics owned exclusively by the category test runner."""
from pathlib import Path
import os
import re
import shutil
import signal
import subprocess
import threading
import time

LOG_PART_BYTES = 2 * 1024 * 1024
MAX_COMPLETED_RUNS = 0
MAX_KEPT_RUNS = 2
MAX_RETAINED_BYTES = 100 * 1024 * 1024
RUN_NAME = re.compile(r'\d{8}T\d{6}Z-[0-9a-f]{8}')


class RollingLog:
    """Keep only the current and previous chunk, never an unbounded stdout file."""
    def __init__(self, path, part_bytes=LOG_PART_BYTES):
        self.path = Path(path)
        self.previous = self.path.with_suffix(self.path.suffix + '.1')
        self.part_bytes = part_bytes
        self.size = 0
        self.stream = self.path.open('wb')

    def write(self, data):
        while data:
            if self.size == self.part_bytes:
                self.stream.close()
                self.path.replace(self.previous)
                self.stream = self.path.open('wb')
                self.size = 0
            chunk = data[:self.part_bytes - self.size]
            self.stream.write(chunk)
            self.size += len(chunk)
            data = data[len(chunk):]
        self.stream.flush()

    def close(self):
        self.stream.close()


def stop_process_tree(process):
    # Keep the runner's lock until its Maven process has been reaped. On Unix,
    # the separate process group also contains Surefire's JVM and test children.
    if os.name == 'posix':
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    else:
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        if os.name == 'posix':
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        else:
            process.kill()
        process.wait()
    if os.name == 'posix':
        # A test grandchild can outlive a terminated Maven parent. It still
        # belongs to this runner's process group, so do not leave it running.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def run_logged(command, cwd, log_path, temporary=None, timeout=None, idle_timeout=None):
    if timeout is not None and timeout <= 0:
        raise TimeoutError('Validation time budget exhausted before launch')
    log = RollingLog(log_path)
    environment = None if temporary is None else {
        **os.environ, **{name: str(temporary) for name in ('TMPDIR', 'TMP', 'TEMP')}}
    try:
        with subprocess.Popen(command, cwd=cwd, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT,
                              env=environment,
                              start_new_session=(os.name == 'posix')) as process:
            started = last_output = time.monotonic()
            done = threading.Event()
            expired = []

            def watch():
                while not done.wait(0.1):
                    now = time.monotonic()
                    if timeout is not None and now - started >= timeout:
                        expired.append('total validation time budget')
                    elif idle_timeout is not None and now - last_output >= idle_timeout:
                        expired.append('no-output timeout')
                    if expired:
                        stop_process_tree(process)
                        return

            watcher = threading.Thread(target=watch, daemon=True)
            watcher.start()
            try:
                while chunk := process.stdout.read1(65536):
                    last_output = time.monotonic()
                    log.write(chunk)
                code = process.wait()
            except BaseException:
                done.set()
                watcher.join()
                stop_process_tree(process)
                raise
            finally:
                done.set()
                watcher.join()
            if expired:
                raise TimeoutError(f"Stopped Maven process tree: {expired[0]} exceeded; "
                                   'validation is incomplete, not passed. Inspect the retained tail before retrying.')
            return code
    finally:
        log.close()


def size_bytes(directory):
    return sum(p.stat().st_size for p in directory.rglob('*')
               if p.is_file() and not p.is_symlink())


def compact_run(directory, failed):
    """Call only after JVM exit and after extracting counts/skips/failures to JSON."""
    for lane in ('ordinary', 'guards'):
        for suffix in ('reports', 'tmp'):
            path = directory / f'{lane}-{suffix}'
            if path.is_symlink():
                path.unlink()
            elif path.exists():
                shutil.rmtree(path)
        if not failed:
            for suffix in ('.log', '.log.1'):
                (directory / (lane + suffix)).unlink(missing_ok=True)
        else:
            # Also bound files left by an interrupted/older unbounded runner.
            current = directory / (lane + '.log')
            previous = directory / (lane + '.log.1')
            if any(p.exists() and p.stat().st_size > LOG_PART_BYTES for p in (previous, current)):
                tail = b''
                for path in (previous, current):
                    if path.exists():
                        with path.open('rb') as stream:
                            stream.seek(max(0, path.stat().st_size - 2 * LOG_PART_BYTES))
                            tail = (tail + stream.read())[-2 * LOG_PART_BYTES:]
                current.write_bytes(tail[-LOG_PART_BYTES:])
                if len(tail) > LOG_PART_BYTES:
                    previous.write_bytes(tail[:-LOG_PART_BYTES])
                else:
                    previous.unlink(missing_ok=True)
    (directory / 'includes.txt').unlink(missing_ok=True)


def prune_runs(base, active=None, max_runs=MAX_COMPLETED_RUNS, max_bytes=MAX_RETAINED_BYTES):
    """Prune only recognized runner directories, never arbitrary target contents."""
    if base.is_symlink():
        raise ValueError(f'Refusing to prune a symlinked artifact directory: {base}')
    if not base.exists():
        return
    runs = sorted((p for p in base.iterdir()
                   if RUN_NAME.fullmatch(p.name) and p.is_dir() and not p.is_symlink()
                   and (p / 'plan.json').is_file() and not (p / 'plan.json').is_symlink()
                   and p != active), reverse=True)
    kept = used = 0
    for directory in runs:
        size = size_bytes(directory)
        marker = directory / 'keep-diagnostics'
        limit = MAX_KEPT_RUNS if marker.is_file() and not marker.is_symlink() else max_runs
        if kept < limit and used + size <= max_bytes:
            kept += 1
            used += size
        else:
            shutil.rmtree(directory)


def acknowledge_run(root, run_id):
    """Delete only the named runner-owned result, serialized with Maven execution."""
    if not RUN_NAME.fullmatch(run_id):
        raise ValueError('Use the exact run ID, not a path, with --acknowledge')
    target = root / 'target'
    target.mkdir(exist_ok=True)
    lock = target / 'category-tests.lock'
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise ValueError('Category runner is active or has a stale lock; do not clean up until its processes have exited')
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(str(os.getpid()) + '\n')
        base = target / 'category-tests'
        directory = base / run_id
        if base.is_symlink() or directory.is_symlink():
            raise ValueError('Refusing to acknowledge symlinked diagnostics')
        if not directory.exists():
            return  # A repeated acknowledgment is harmless.
        plan = directory / 'plan.json'
        if not plan.is_file() or plan.is_symlink():
            raise ValueError('Refusing to delete an unrecognized diagnostics directory')
        shutil.rmtree(directory)
        if not any(base.iterdir()):
            base.rmdir()
    finally:
        lock.unlink()
