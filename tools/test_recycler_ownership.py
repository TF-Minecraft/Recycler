import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location('recycler_ownership', Path(__file__).with_name('recycler-ownership.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OwnershipRollbackTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.operation, self.account, self.old, self.recipient, self.new, self.death = [str(uuid.uuid4()) for _ in range(6)]
        self.original = {'format': 1, 'id': self.operation, 'owner-account': self.account,
                         'owner-character': self.old, 'state': 'pending', 'item-bytes': 'opaque',
                         'future': {'null': None}}
        self.moved = dict(self.original, **{'owner-account': self.recipient, 'owner-character': self.new})
        self.before = json.dumps(self.original).encode()
        self.after = json.dumps(self.moved).encode()
        self.source = self.root / f'character-returns/{self.old}/{self.operation}.json'
        self.target = self.root / f'character-returns/{self.new}/{self.operation}.json'
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(self.after)
        self.journal = self.root / f'ownership/estates/{self.death}.json'
        self.journal.parent.mkdir(parents=True)
        decision = f'EstateDecision[deathId={self.death}, testator={module.reference(self.account, self.old)}, diedAt=Optional.empty, property=Character[ref={module.reference(self.recipient, self.new)}], guildLocks=Frozen[], revision=1]'
        self.plan = {'format': 1, 'kind': 'estate', 'phase': 'moved', 'decision': decision, 'revision': 1,
                     'source': str(self.source.relative_to(self.root)),
                     'target': str(self.target.relative_to(self.root)),
                     'from-account': self.account, 'from-character': self.old,
                     'to-account': self.recipient, 'to-character': self.new,
                     'before': base64.b64encode(self.before).decode(),
                     'after': base64.b64encode(self.after).decode(),
                     'before-hash': module.digest(self.before), 'after-hash': module.digest(self.after)}
        self.journal.write_text(json.dumps(self.header(self.plan)))

    def header(self, plan):
        return {'format': 1, 'death': self.death, 'from-account': self.account,
                'from-character': self.old, self.operation: plan}

    def test_preview_and_apply_preserve_exact_current_and_original_bytes(self):
        report = module.inspect(self.root)
        self.assertEqual('safe offline restore', report['journals'][0]['records'][0]['result'])
        self.assertFalse(self.source.exists())
        backup = self.root.parent / (self.root.name + '-recovery')
        self.addCleanup(lambda: __import__('shutil').rmtree(backup, ignore_errors=True))
        applied = module.inspect(self.root, apply=True, backup=backup)
        self.assertEqual(self.before, self.source.read_bytes())
        self.assertFalse(self.target.exists())
        self.assertEqual(self.after, (backup / self.target.relative_to(self.root)).read_bytes())
        self.assertIn('archived', applied['journals'][0])

    def test_later_delivery_or_gameplay_changes_block_restore(self):
        changed = dict(self.moved, state='delivered', remaining=0)
        self.target.write_text(json.dumps(changed))
        report = module.inspect(self.root)
        self.assertIn('conflict', report['journals'][0]['records'][0]['result'])
        self.assertFalse(self.source.exists())
        self.assertEqual(changed, json.loads(self.target.read_bytes()))
        self.target.unlink()
        self.assertIn('conflict', module.inspect(self.root)['journals'][0]['records'][0]['result'])

    def test_corrupt_payload_wrong_source_and_reference_mismatch_never_publish(self):
        for field, value in [('after-hash', 'broken'), ('source', '../outside'),
                             ('source', 'character-returns/wrong/operation.json'),
                             ('from-account', 'wrong'), ('to-character', 'wrong')]:
            plan = dict(self.plan, **{field: value})
            self.journal.write_text(json.dumps(self.header(plan)))
            report = module.inspect(self.root)
            self.assertIn('error', report['journals'][0])
            self.assertFalse(self.source.exists())
            self.assertEqual(self.after, self.target.read_bytes())
        self.assertRaises(ValueError, module.load, '{"duplicate":1,"duplicate":2}')
        self.assertRaises(ValueError, module.inspect, self.root, apply=True)
        self.assertRaises(FileExistsError, module.inspect, self.root, apply=True, backup=self.root)

    def test_ambiguous_and_unreadable_queues_and_legacy_paths_remain_visible(self):
        ambiguous = self.root / f'character-returns/{self.new}/ambiguous.json'
        ambiguous.write_text(json.dumps(dict(self.moved, id='ambiguous', state='delivering')))
        broken = self.root / f'character-returns/{self.new}/broken.json'
        broken.write_text('broken')
        legacy = self.root / 'escrow/account.json'
        legacy.parent.mkdir()
        legacy.write_text('opaque')
        result = module.inspect(self.root)
        self.assertEqual(2, sum(row.get('needs_review', False) for row in result['queues']))
        self.assertEqual([str(legacy)], result['legacy'])

    def test_ownership_plan_cannot_change_nested_payload_even_with_matching_hash(self):
        after = json.dumps(dict(self.moved, future={'null': 'lost'})).encode()
        plan = dict(self.plan, after=base64.b64encode(after).decode(), **{'after-hash': module.digest(after)})
        self.journal.write_text(json.dumps(self.header(plan)))
        self.assertIn('changes payload', module.inspect(self.root)['journals'][0]['error'])

    def test_migration_round_trip_uses_source_derived_operation_and_preserves_raw_nulls(self):
        self.journal.unlink()
        self.target.unlink()
        source = self.root / 'pending_returns' / (self.account + '-second.json')
        source.parent.mkdir()
        before = b'{"type":"DIAMOND","unknown":{"null":null}}'
        source.write_bytes(before)
        operation = str(uuid.UUID(bytes=__import__('hashlib').md5(str(source.relative_to(self.root)).encode()).digest(), version=3))
        row = dict(self.original, id=operation, **{'legacy-source': str(source.relative_to(self.root)), 'legacy-item': json.loads(before)})
        target = self.root / 'character-returns' / self.old / (operation + '.json')
        target.parent.mkdir()
        after = json.dumps(row).encode()
        target.write_bytes(after)
        plan = dict(self.plan, kind='migration', source=str(source.relative_to(self.root)), target=str(target.relative_to(self.root)),
                    before=base64.b64encode(before).decode(), after=base64.b64encode(after).decode(),
                    **{'before-hash': module.digest(before), 'after-hash': module.digest(after), 'from-account': self.account, 'to-account': self.account, 'to-character': self.old})
        file = self.root / 'ownership/migration' / (operation + '.json')
        file.parent.mkdir()
        file.write_text(json.dumps(plan))
        self.assertEqual('safe offline restore', module.inspect(self.root, kind='migration')['journals'][0]['records'][0]['result'])
        altered = dict(plan, **{'from-account': str(uuid.uuid4())})
        file.write_text(json.dumps(altered))
        self.assertIn('authority', module.inspect(self.root, kind='migration')['journals'][0]['error'])
        file.write_text(json.dumps(plan))
        backup = self.root / 'migration-recovery'
        module.inspect(self.root, kind='migration', apply=True, backup=backup)
        self.assertEqual(before, source.read_bytes())
        self.assertFalse(target.exists())
        self.assertEqual(after, (backup / target.relative_to(self.root)).read_bytes())

    def test_full_journal_preflight_preserves_every_record_if_any_receipt_conflicts(self):
        operation = str(uuid.uuid4())
        second = dict(self.plan, **{'after-hash': 'corrupt'})
        self.journal.write_text(json.dumps(dict(self.header(self.plan), **{operation: second})))
        report = module.inspect(self.root, apply=True, backup=self.root / 'recovery')
        self.assertIn('error', report['journals'][0])
        self.assertFalse(self.source.exists())
        self.assertEqual(self.after, self.target.read_bytes())
        changed = self.header(self.plan)
        changed['from-account'] = str(uuid.uuid4())
        self.journal.write_text(json.dumps(changed))
        self.assertIn('authority', module.inspect(self.root)['journals'][0]['error'])

    def test_unknown_root_queue_file_remains_visible_as_held(self):
        unknown = self.root / 'character-returns/unloaded'
        unknown.write_bytes(b'opaque')
        self.assertTrue(any(row['file'] == str(unknown) and row['needs_review'] for row in module.inspect(self.root)['queues']))

    def test_historical_decision_binds_even_hash_consistent_recipient_changes(self):
        changed_account = str(uuid.uuid4())
        after = json.dumps(dict(self.moved, **{'owner-account': changed_account})).encode()
        plan = dict(self.plan, **{'to-account': changed_account, 'after': base64.b64encode(after).decode(), 'after-hash': module.digest(after)})
        self.journal.write_text(json.dumps(self.header(plan)))
        self.assertIn('captured full decision', module.inspect(self.root)['journals'][0]['error'])
        self.assertFalse(self.source.exists())
        self.assertEqual(self.after, self.target.read_bytes())


if __name__ == '__main__':
    unittest.main()
