import argparse
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from ms import config as config_mod
from ms import db, discovery, scan as scan_mod, server as server_mod
from ms.progress import Progress, SyncPhase


def _progress_printer() -> Progress:
    """Throttled `\r[phase] i/n rel` reporter to stderr (every 100 files or phase change)."""
    last_phase: SyncPhase | None = None
    last_done = 0

    def cb(phase: SyncPhase, done: int, total: int, rel: str) -> None:
        nonlocal last_phase, last_done
        if phase is not last_phase or done - last_done >= 100 or done == total:
            print(f"\r[{phase.name}] {done}/{total} {rel}", end="", file=sys.stderr, flush=True)
            last_phase = phase
            last_done = done

    return cb


def _args() -> argparse.ArgumentParser:
    # --db must be accepted both before and after the subcommand (the tests
    # pass it after, e.g. `ms scan --path X --db Y`), so it lives on a shared
    # parent parser inherited by the main parser and every subparser.
    parent = argparse.ArgumentParser(add_help=False)
    parent.add_argument("--db", default=None, help="override DB path")
    p = argparse.ArgumentParser(prog="ms", parents=[parent])
    sub = p.add_subparsers(dest="cmd", required=True)
    scan_p = sub.add_parser("scan", parents=[parent])
    scan_p.add_argument("--path", required=True)
    scan_p.add_argument("--hash", action="store_true",
                        help="hash every file into the manifest (use after restructuring the library)")
    serve_p = sub.add_parser("serve", parents=[parent])
    serve_p.add_argument("--path", required=True)
    serve_p.add_argument("--port", type=int, default=None)
    log_p = sub.add_parser("log", parents=[parent])
    log_p.add_argument("--limit", type=int, default=50)
    verify_p = sub.add_parser("verify", parents=[parent])
    verify_p.add_argument("--path", required=True)
    return p


def main(argv: list[str] | None = None) -> int:
    args = _args().parse_args(argv)
    cfg = config_mod.load_config()
    db_path = Path(args.db) if args.db else Path(cfg["db_path"])

    if args.cmd == "scan":
        root = Path(args.path)
        conn = db.init_db(db_path)
        head_before = db.journal_head(conn)
        scan_mod.scan(root, conn, "laptop", time.time_ns(), progress=_progress_printer(), hash_files=args.hash)
        print(file=sys.stderr)  # newline after the \r progress line
        for op in db.journal_since(conn, head_before):
            ts = datetime.fromtimestamp(op["ts_ns"] / 1e9, tz=timezone.utc).isoformat(timespec="seconds")
            print(f"{ts}  laptop  {op['op']:<6} {op['path']}")
        conn.close()
        return 0

    if args.cmd == "log":
        conn = db.init_db(db_path)
        for op in db.journal_since(conn, 0)[-args.limit:]:
            ts = datetime.fromtimestamp(op["ts_ns"] / 1e9, tz=timezone.utc).isoformat(timespec="seconds")
            print(f"{ts}  {op['device']:<8} {op['op']:<6} {op['path']}")
        conn.close()
        return 0

    if args.cmd == "verify":
        root = Path(args.path)
        conn = db.init_db(db_path)
        # NOTE: no re-scan here — scan() would re-hash changed files and update
        # the stored manifest, defeating tamper detection. Verify is a pure
        # re-hash of the tree against the stored manifest.
        mismatches = []
        verified = 0
        unverified = 0
        for (rel, size, mtime_ns, sha, _l) in db.manifest_all(conn):
            if sha is None:
                unverified += 1  # lazy scan: never hashed -> not a mismatch
                continue
            p = root / rel
            if not p.is_file():
                mismatches.append(rel)
                continue
            from ms.hashing import sha256_file
            if sha256_file(p) != sha:
                mismatches.append(rel)
            else:
                verified += 1
        if mismatches:
            for rel in mismatches:
                print(f"MISMATCH  {rel}")
        else:
            print(f"OK: {verified} verified, {unverified} unverified")
        conn.close()
        return 0

    if args.cmd == "serve":
        root = Path(args.path)
        port = args.port or cfg["port"]
        conn = db.init_db(db_path)
        scan_mod.scan(root, conn, "laptop", time.time_ns(), progress=_progress_printer())
        print(file=sys.stderr)  # newline after the \r progress line
        conn.close()
        srv = server_mod.SyncServer(root, db_path, "laptop", port=port, apk_path=cfg["apk_path"])
        print(f"music-syncer serving {root} on :{srv.port} — Ctrl-C to stop")
        with discovery.advertise("laptop", srv.port):
            try:
                srv.serve_forever()
            except KeyboardInterrupt:
                print("\nstopped")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())