#!/usr/bin/env python3
"""Inspect character returns and preview/apply guarded offline ownership rollback."""
import argparse
import base64
import hashlib
import json
import os
import re
import stat
from pathlib import Path
import tempfile
import uuid


def digest(data):
    return hashlib.sha256(data).hexdigest()


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON field')
        result[key] = value
    return result


def load(data):
    return json.loads(data, object_pairs_hook=no_duplicates)


def force(folder):
    descriptor = os.open(folder, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def durable(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix='recycler-', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
        force(path.parent)
        for parent in path.parent.parents:
            force(parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def safe(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError('ownership path escapes data root')
    return path


def envelope(row):
    if type(row.get('format')) is not int or row['format'] != 1:
        raise ValueError('unsupported queued ownership format')
    for field in ('id', 'owner-account', 'owner-character'):
        if str(uuid.UUID(row[field])) != row[field]:
            raise ValueError('invalid queued identity')
    if row['state'] not in ('depositing', 'escrow', 'pending', 'delivering', 'held', 'delivered', 'consumed', 'cancelled'):
        raise ValueError('unsupported queued state')


def reference(account, character):
    return f'CharacterRef[ownerId={account}, characterId={character}]'


def captured_decision(journal, plan):
    decision = plan['decision']
    prefix = f"EstateDecision[deathId={journal['death']}, testator={reference(plan['from-account'], plan['from-character'])}, diedAt="
    property_field = f", property=Character[ref={reference(plan['to-account'], plan['to-character'])}], guildLocks="
    suffix = re.search(r', revision=(0|[1-9][0-9]*)]$', decision)
    if not decision.startswith(prefix) or property_field not in decision or suffix is None:
        raise ValueError('estate plan differs from captured full decision')
    if type(plan['revision']) is not int or plan['revision'] != int(suffix.group(1)) or not 0 <= plan['revision'] <= 9223372036854775807:
        raise ValueError('unsupported estate captured revision')


def journals(folder):
    try:
        status = folder.lstat()
    except FileNotFoundError:
        return []
    if not stat.S_ISDIR(status.st_mode):
        raise ValueError('ownership journal path is not a directory')
    # glob silently skips an unreadable directory, which cannot prove no estate exists.
    return [path for path in folder.iterdir() if path.name.endswith('.json')]


def inspect(root, kind='estates', apply=False, backup=None):
    root = Path(root).resolve()
    result = {'journals': [], 'queues': [], 'legacy': []}
    recovery = None
    if apply:
        if backup is None:
            raise ValueError('--backup requires a new recovery directory')
        recovery = Path(backup).resolve()
        recovery.mkdir()  # Existing backups are never overwritten.
        force(recovery.parent)
    for file in sorted(journals(root / 'ownership' / kind), key=lambda path: path.stat().st_mtime_ns, reverse=True):
        report = {'journal': str(file), 'records': []}
        try:
            journal = load(file.read_bytes())
            if type(journal.get('format')) is not int or journal['format'] != 1:
                raise ValueError('unsupported ownership journal format')
            if kind == 'estates' and journal['death'] != file.stem:
                raise ValueError('estate death differs from journal path')
            if kind == 'migration':
                plans = [(file.stem, journal)]
            else:
                plans = [(key, value) for key, value in journal.items()
                         if key not in ('format', 'death', 'from-account', 'from-character')]
            if apply:
                durable(recovery / file.relative_to(root), file.read_bytes())
            actions = []
            for operation, plan in plans:
                if type(plan.get('format')) is not int or plan['format'] != 1 or plan['kind'] != ('estate' if kind == 'estates' else 'migration') or plan['phase'] not in ('planned', 'published', 'moved'):
                    raise ValueError('unsupported ownership intent')
                before = base64.b64decode(plan['before'], validate=True)
                after = base64.b64decode(plan['after'], validate=True)
                if digest(before) != plan['before-hash'] or digest(after) != plan['after-hash']:
                    raise ValueError('ownership payload hash conflict')
                source, target = safe(root, plan['source']), safe(root, plan['target'])
                target_row = load(after)
                envelope(target_row)
                expected_target = root / 'character-returns' / target_row['owner-character'] / (target_row['id'] + '.json')
                if target != expected_target.resolve() or target_row['id'] != operation:
                    raise ValueError('planned target differs from character/operation')
                if kind == 'estates':
                    source_row = load(before)
                    envelope(source_row)
                    if not plan['decision'].strip() or journal['from-account'] != plan['from-account'] or journal['from-character'] != plan['from-character']:
                        raise ValueError('estate full authority differs from journal')
                    captured_decision(journal, plan)
                    expected_source = root / 'character-returns' / source_row['owner-character'] / (operation + '.json')
                    if source != expected_source.resolve() or source_row['id'] != operation:
                        raise ValueError('planned source differs from character/operation')
                    if source_row['owner-account'] != plan['from-account'] or source_row['owner-character'] != plan['from-character']:
                        raise ValueError('source full reference differs')
                    if target_row['owner-account'] != plan['to-account'] or target_row['owner-character'] != plan['to-character']:
                        raise ValueError('recipient full reference differs')
                    expected = dict(source_row, **{'owner-account': plan['to-account'], 'owner-character': plan['to-character']})
                    if expected['state'] == 'escrow':
                        expected['state'] = 'pending'
                    if expected != target_row:
                        raise ValueError('estate plan changes payload or unrelated metadata')
                else:
                    relative = source.relative_to(root)
                    expected_id = str(uuid.UUID(bytes=hashlib.md5(str(relative).encode()).digest(), version=3))
                    if relative.parent not in (Path('escrow'), Path('pending_returns')) or operation != expected_id:
                        raise ValueError('legacy source namespace/operation differs')
                    account = str(uuid.UUID(source.name[:36]))
                    if plan['to-account'] != target_row['owner-account'] or plan['to-character'] != target_row['owner-character']:
                        raise ValueError('legacy fixed recipient differs from payload')
                    if plan['from-account'] != account or target_row['owner-account'] != account or target_row['legacy-source'] != str(relative):
                        raise ValueError('legacy full authority differs')
                    if relative.parent == Path('escrow') and target_row['state'] != 'held':
                        raise ValueError('legacy deposit ambiguity cannot become an automatic return')
                    try:
                        original = load(before)
                    except (ValueError, UnicodeError):
                        original = None
                    if isinstance(original, dict):
                        if original != target_row.get('legacy-item'):
                            raise ValueError('legacy plan changes payload')
                    elif plan['before'] != target_row.get('legacy-bytes'):
                        raise ValueError('legacy plan changes opaque bytes')
                row = {'id': operation, 'phase': plan['phase']}
                # Older JARs delete delivered legacy files; absence cannot prove an unused return.
                source_ok = (source.exists() and digest(source.read_bytes()) == plan['before-hash']) if kind == 'migration' else (not source.exists() or digest(source.read_bytes()) == plan['before-hash'])
                target_ok = not target.exists() or digest(target.read_bytes()) == plan['after-hash']
                published_missing = plan['phase'] != 'planned' and not target.exists()
                migration_progressed = False
                if kind == 'migration':
                    # A missing published copy may have been inherited or delivered.
                    for estate_file in journals(root / 'ownership' / 'estates'):
                        estate = load(estate_file.read_bytes())
                        if not isinstance(estate, dict):
                            raise ValueError('unreadable live estate evidence blocks migration rollback')
                        # Even an exact restored source cannot erase a live estate receipt.
                        if operation in estate:
                            migration_progressed = True
                if source == target or not source_ok or not target_ok or (not source.exists() and not target.exists()) or published_missing or migration_progressed:
                    row['result'] = 'conflict; preserve files and reconcile current progress'
                else:
                    row['result'] = 'safe offline restore'
                    actions.append((source, target, before))
                report['records'].append(row)
            if apply and all(row['result'] == 'safe offline restore' for row in report['records']):
                archive = root / 'ownership' / 'rolled-back' / kind / file.name
                if archive.exists():
                    raise ValueError('rolled-back journal archive exists')
                report['applied'] = []
                for source, target, before in actions:
                    for path in (source, target):
                        if path.exists():
                            durable(recovery / path.relative_to(root), path.read_bytes())
                    if not source.exists():
                        durable(source, before)
                    if target.exists():
                        target.unlink()
                        force(target.parent)
                    report['applied'].append(str(target.relative_to(root)))
                for row in report['records']:
                    row['result'] = 'restored'
                durable(archive, file.read_bytes())
                file.unlink()
                force(file.parent)
                report['archived'] = str(archive)
        except (OSError, ValueError, KeyError, TypeError, AttributeError, UnicodeError) as error:
            report['error'] = str(error)
        result['journals'].append(report)
    queue_root = root / 'character-returns'
    paths = []
    if queue_root.is_dir():
        for folder in queue_root.iterdir():
            paths.extend(folder.iterdir() if folder.is_dir() else [folder])
    for path in sorted(paths):
        try:
            row = load(path.read_bytes())
            envelope(row)
            if path.name != row['id'] + '.json' or path.parent.name != row['owner-character']:
                raise ValueError('queued identity differs from path')
            result['queues'].append({'file': str(path), 'id': row['id'], 'owner-account': row['owner-account'],
                    'owner-character': row['owner-character'], 'state': row['state'], 'remaining': row.get('remaining'),
                    'needs_review': row['state'] in ('depositing', 'delivering', 'held')})
        except (OSError, ValueError, KeyError, TypeError, AttributeError, UnicodeError) as error:
            result['queues'].append({'file': str(path), 'needs_review': True, 'error': str(error)})
    for folder in ('escrow', 'pending_returns'):
        result['legacy'].extend(str(path) for path in sorted((root / folder).glob('*')))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True, help='plugins/Recycler/data')
    parser.add_argument('--kind', choices=('migration', 'estates'), default='estates')
    parser.add_argument('--apply', action='store_true', help='server must be stopped')
    parser.add_argument('--backup', type=Path, help='new current-state recovery directory')
    args = parser.parse_args()
    print(json.dumps(inspect(args.data, args.kind, args.apply, args.backup), indent=2))


if __name__ == '__main__':
    main()
