#!/usr/bin/env python3
"""Offline boundary and rollback tests; subprocess patch preparation is replaced with fixtures."""

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


qualifier = load('qualify-runtime-patches')
verifier = load('verify-telegram-gateway')
preparer = load('patch-telegram-delivery')
REVIEWED = json.loads(Path(__file__).with_name('runtime-patch-specs.json').read_text())
RECEIPTS = ['telegram-delivery-patch.json', 'glm-thinking-patch.json', 'glm-token-field-patch.json',
            'memory-admission-patch.json']


def digest(content):
    return hashlib.sha256(content).hexdigest()


class RuntimePatchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / 'source'; self.source.mkdir()
        self.package = self.root / 'package'; self.package.mkdir()
        self.output = self.root / 'qualification'
        self.state = self.root / 'state'; (self.state / 'operations').mkdir(parents=True)
        self.write_json(self.state / 'openclaw.json', {'gateway': {'port': 18479}})
        self.version = '2026.9.3'
        self.specs = copy.deepcopy(REVIEWED)
        self.after = {}
        self.prepare_fixture(self.version)
        self.listener = subprocess.CompletedProcess([], 1, b'', b'')
        self.corrupt_candidate = False
        self.forge_auth_receipt = False
        self.fail_auth_tests = False

    def tearDown(self):
        self.temporary.cleanup()

    def write_json(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value)); path.chmod(0o600)

    def items(self):
        spec = self.specs[self.version]
        return [spec['token'], spec['memory'], *spec['thinking'], *spec['delivery']] + (
            [spec['authReprobe']] if 'authReprobe' in spec else [])

    def receipt_names(self):
        return RECEIPTS + (['auth-reprobe-patch.json'] if 'authReprobe' in self.specs[self.version] else [])

    def prepare_fixture(self, version):
        self.version = version
        self.write_json(self.package / 'package.json', {'version': version})
        for item in self.items():
            name = item['path']
            original = ('original:' + name).encode()
            modified = ('reviewed-patched:' + name).encode()
            self.after[name] = modified
            target = self.package / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if item.get('before'):
                target.write_bytes(original); item['before'] = digest(original)
            item['after'] = digest(modified)
        self.write_json(self.source / 'runtime-patch-specs.json', self.specs)
        for name in self.receipt_names():
            self.write_json(self.state / 'operations' / name, {'previousReceipt': name})

    def runner(self, argv, output):
        script = Path(argv[1]).name
        spec = self.specs[self.version]
        if script == 'patch-telegram-delivery.py':
            directory = Path(argv[-1]); directory.mkdir()
            for item in spec['delivery']:
                (directory / Path(item['path']).name).write_bytes(self.after[item['path']])
            self.write_json(directory / 'patch-receipt.json', {'version': self.version, 'files': [
                {'name': Path(item['path']).name, 'afterSha256': item['after']} for item in spec['delivery']]})
            output.write_text('{}')
        elif script in {'patch-glm-token-field.mjs', 'patch-memory-admission.mjs', 'patch-auth-reprobe.mjs'}:
            key = {'patch-glm-token-field.mjs': 'token', 'patch-memory-admission.mjs': 'memory',
                   'patch-auth-reprobe.mjs': 'authReprobe'}[script]
            content = self.after[spec[key]['path']]
            if self.corrupt_candidate and key == 'memory': content += b'changed'
            Path(argv[-1]).write_bytes(content)
            receipt = {'version': self.version, 'relativePath': spec[key]['path'],
                       'beforeSha256': spec[key]['before'], 'afterSha256': spec[key]['after'],
                       'inferenceRequests': 0}
            if key == 'authReprobe' and self.forge_auth_receipt:
                receipt['relativePath'] = '../unreviewed'
            self.write_json(output, receipt)
        elif script == 'patch-glm-thinking.mjs':
            directory = Path(argv[-1]); (directory / 'dist').mkdir(parents=True)
            for item in spec['thinking']:
                (directory / item['path']).write_bytes(self.after[item['path']])
            self.write_json(directory / 'glm-thinking-patch.json', {'schemaVersion': 1, 'version': self.version,
                            'files': [{'relativePath': item['path'], 'beforeSha256': item['before'],
                                       'afterSha256': item['after']} for item in spec['thinking']]})
            output.write_text('{}')
        else:
            self.assertIn(script, {'test-telegram-delivery-retry.mjs', 'test-auth-reprobe.mjs'})
            if script == 'test-auth-reprobe.mjs' and self.fail_auth_tests:
                raise ValueError('offline auth reprobe qualification failed')
            output.write_text('synthetic offline tests passed')

    def apply(self, **kwargs):
        with patch.object(qualifier, 'SOURCE', self.source), patch.object(qualifier, 'run', self.runner), \
                patch.object(qualifier.subprocess, 'run', return_value=self.listener):
            return qualifier.qualify(kwargs.get('package', self.package), kwargs.get('output', self.output),
                                     kwargs.get('state', self.state))

    def snapshot(self):
        paths = [self.package / item['path'] for item in self.items()]
        paths += [self.state / 'operations' / name for name in self.receipt_names()]
        return {path: path.read_bytes() if path.exists() else None for path in paths}

    def assert_snapshot(self, expected):
        for path, before in expected.items():
            self.assertEqual(path.read_bytes() if path.exists() else None, before, str(path))

    def test_offline_qualification_does_not_install(self):
        before = self.snapshot()
        result = self.apply(state=None)
        self.assertTrue(result['offlineQualified']); self.assertFalse(result['installed'])
        self.assertEqual(result['inferenceRequests'], 0)
        self.assert_snapshot(before)

    def test_validated_installation_writes_all_files_and_release_receipts(self):
        result = self.apply()
        self.assertTrue(result['installed'])
        for item in self.items():
            self.assertEqual((self.package / item['path']).read_bytes(), self.after[item['path']])
        for name in self.receipt_names():
            self.assertEqual(json.loads((self.state / 'operations' / name).read_text())['version'], self.version)
        self.assertEqual(len(list((self.output / 'previous').iterdir())),
                         sum(bool(item.get('before')) for item in self.items()) + len(self.receipt_names()))

    def test_auth_source_drift_blocks_before_any_preparation(self):
        (self.package / self.specs[self.version]['authReprobe']['path']).write_bytes(b'unknown-auth-source')
        with self.assertRaisesRegex(ValueError, 'unreviewed'):
            self.apply()
        self.assertFalse(self.output.exists())

    def test_auth_receipt_drift_prevents_activation(self):
        self.forge_auth_receipt = True
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, 'auth reprobe receipt'):
            self.apply()
        self.assert_snapshot(before)

    def test_auth_native_test_failure_prevents_activation(self):
        self.fail_auth_tests = True
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, 'offline auth reprobe'):
            self.apply()
        self.assert_snapshot(before)

    def test_source_pin_drift_fails_before_preparation(self):
        (self.package / self.items()[0]['path']).write_bytes(b'unknown-source')
        with self.assertRaisesRegex(ValueError, 'unreviewed'):
            self.apply()
        self.assertFalse(self.output.exists())

    def test_candidate_pin_drift_prevents_any_activation(self):
        self.corrupt_candidate = True
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, 'candidate differs'):
            self.apply()
        self.assert_snapshot(before)

    def test_unknown_release_fails_closed(self):
        self.write_json(self.package / 'package.json', {'version': '2099.1.1'})
        with self.assertRaisesRegex(ValueError, 'unreviewed'):
            self.apply()
        self.assertFalse(self.output.exists())

    def test_active_or_uninspectable_listener_blocks_activation(self):
        before = self.snapshot()
        for code, output in [(0, b'listener'), (2, b''), (1, b'unexpected')]:
            self.listener = subprocess.CompletedProcess([], code, output, b'private-error')
            with self.assertRaisesRegex(ValueError, 'Gateway must be stopped'):
                self.apply()
            self.assert_snapshot(before)
            self.assertFalse(self.output.exists())

    def test_invalid_port_cannot_bypass_stop_check(self):
        self.write_json(self.state / 'openclaw.json', {'gateway': {'port': True}})
        with self.assertRaisesRegex(ValueError, 'invalid Gateway port'):
            self.apply()

    def test_runtime_and_output_trees_must_be_disjoint(self):
        with self.assertRaisesRegex(ValueError, 'separate'):
            self.apply(output=self.package / 'generated')
        with self.assertRaisesRegex(ValueError, 'separate'):
            self.apply(output=self.root)

    def test_existing_unreviewed_helper_cannot_be_overwritten(self):
        helper = self.package / self.specs[self.version]['delivery'][-1]['path']
        helper.write_bytes(b'preexisting')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.apply()
        self.assertEqual(helper.read_bytes(), b'preexisting')

    def test_leaf_and_parent_symlinks_are_rejected(self):
        item = self.items()[0]
        target = self.package / item['path']
        external = self.root / 'outside'; external.write_bytes(target.read_bytes())
        target.unlink(); target.symlink_to(external)
        with self.assertRaisesRegex(ValueError, 'redirected'):
            self.apply()
        target.unlink(); target.write_bytes(external.read_bytes())
        dist = self.package / 'dist'; shutil.move(str(dist), str(self.root / 'outside-dist'))
        dist.symlink_to(self.root / 'outside-dist', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'redirected'):
            self.apply()

    def test_new_helper_symlink_is_rejected_without_touching_external_file(self):
        external = self.root / 'owner-data'; external.write_bytes(b'keep unchanged')
        helper = self.package / self.specs[self.version]['delivery'][-1]['path']
        helper.symlink_to(external)
        with self.assertRaisesRegex(ValueError, 'redirected'):
            self.apply()
        self.assertEqual(external.read_bytes(), b'keep unchanged')

    def test_reviewed_spec_path_cannot_escape_package(self):
        self.specs[self.version]['delivery'][-1]['path'] = '../outside'
        self.write_json(self.source / 'runtime-patch-specs.json', self.specs)
        with self.assertRaisesRegex(ValueError, 'redirected'):
            self.apply()
        self.assertFalse((self.root / 'outside').exists())

    def test_receipt_symlink_cannot_overwrite_external_file(self):
        external = self.root / 'owner-data'; external.write_bytes(b'keep unchanged')
        target = self.state / 'operations' / RECEIPTS[0]
        target.unlink(); target.symlink_to(external)
        with self.assertRaisesRegex(ValueError, 'redirected'):
            self.apply()
        self.assertEqual(external.read_bytes(), b'keep unchanged')

    def test_operations_directory_symlink_rejected_before_preparation(self):
        operations = self.state / 'operations'
        shutil.move(str(operations), str(self.root / 'outside-operations'))
        operations.symlink_to(self.root / 'outside-operations', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'redirected'):
            self.apply()
        self.assertFalse(self.output.exists())

    def test_mid_install_failure_restores_originals_and_removes_new_helper(self):
        before = self.snapshot()
        fail_target = self.state / 'operations' / RECEIPTS[1]
        replace = os.replace
        failed = False
        def inject(source, destination):
            nonlocal failed
            if Path(destination) == fail_target and not failed:
                failed = True
                raise OSError('injected install interruption')
            return replace(source, destination)
        with patch.object(qualifier.os, 'replace', side_effect=inject):
            with self.assertRaises(OSError):
                self.apply()
        self.assertTrue(failed)
        self.assert_snapshot(before)
        self.assertFalse((self.output / 'qualification.json').exists())

    def test_final_success_receipt_failure_rolls_back_activation(self):
        before = self.snapshot()
        write = Path.write_text
        def inject(path, text, *args, **kwargs):
            if path == self.output / 'qualification.json':
                raise OSError('injected final receipt failure')
            return write(path, text, *args, **kwargs)
        with patch.object(Path, 'write_text', inject):
            with self.assertRaises(OSError):
                self.apply()
        self.assert_snapshot(before)

    def test_auth_receipt_write_failure_rolls_back_all_patches(self):
        before = self.snapshot()
        target = self.state / 'operations/auth-reprobe-patch.json'
        replace = os.replace
        def inject(source, destination):
            if Path(destination) == target:
                raise OSError('injected auth receipt failure')
            return replace(source, destination)
        with patch.object(qualifier.os, 'replace', side_effect=inject), self.assertRaises(OSError):
            self.apply()
        self.assert_snapshot(before)

    def test_auth_verifier_rejects_missing_forged_or_foreign_receipt(self):
        self.apply()
        path = self.state / 'operations/auth-reprobe-patch.json'
        original = json.loads(path.read_text())
        with patch.object(verifier, 'RUNTIME_PATCH_SPECS', self.specs):
            verifier.auth_reprobe_patch_policy(self.state, self.package, self.version)
            for field, value in [('version', '2026.9.2'), ('relativePath', '../private'),
                                 ('beforeSha256', '0' * 64), ('afterSha256', '0' * 64),
                                 ('inferenceRequests', True), ('inferenceRequests', 1)]:
                self.write_json(path, {**original, field: value})
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'reviewed patch'):
                    verifier.auth_reprobe_patch_policy(self.state, self.package, self.version)
            path.unlink()
            with self.assertRaisesRegex(ValueError, 'private file'):
                verifier.auth_reprobe_patch_policy(self.state, self.package, self.version)

    def test_auth_verifier_uses_pinned_bytes_instead_of_forged_matching_hash(self):
        self.apply()
        target = self.package / self.specs[self.version]['authReprobe']['path']
        target.write_bytes(b'forged-auth-runtime')
        path = self.state / 'operations/auth-reprobe-patch.json'
        receipt = json.loads(path.read_text())
        self.write_json(path, {**receipt, 'afterSha256': digest(b'forged-auth-runtime')})
        with patch.object(verifier, 'RUNTIME_PATCH_SPECS', self.specs), self.assertRaisesRegex(ValueError, 'reviewed patch'):
            verifier.auth_reprobe_patch_policy(self.state, self.package, self.version)

    def test_runtime_verifier_rejects_forged_receipt_matching_unreviewed_bytes(self):
        self.apply()
        owner = '12345'
        self.write_json(self.state / 'operations/telegram-delivery-policy.json', {
            'schemaVersion': 1, 'ownerId': owner, 'accountId': 'default', 'allowAmbiguousReplay': True,
            'maxAttempts': 1008, 'maxAgeMs': 604800000})
        with patch.object(verifier, 'RUNTIME_PATCH_SPECS', self.specs):
            verifier.runtime_policy(self.state, self.package, owner)
            token = self.specs[self.version]['token']
            (self.package / token['path']).write_bytes(b'forged-runtime')
            self.write_json(self.state / 'operations/glm-token-field-patch.json', {
                'version': self.version, 'relativePath': token['path'], 'afterSha256': digest(b'forged-runtime')})
            with self.assertRaisesRegex(ValueError, 'reviewed runtime patch bytes drifted'):
                verifier.runtime_policy(self.state, self.package, owner)

    def test_legacy_release_remains_supported(self):
        shutil.rmtree(self.package); self.package.mkdir()
        self.prepare_fixture('2026.9.2')
        result = self.apply()
        self.assertEqual(result['version'], '2026.9.2')
        self.assertTrue(result['installed'])
        self.assertEqual(len(self.receipt_names()), 4)
        self.assertNotIn('authReprobe', self.specs['2026.9.2'])
        with patch.object(verifier, 'RUNTIME_PATCH_SPECS', self.specs):
            verifier.auth_reprobe_patch_policy(self.state, self.package, '2026.9.2')

    def test_standalone_delivery_prepare_cannot_target_runtime(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            preparer.prepare(self.package, self.package / 'dist')


if __name__ == '__main__':
    unittest.main()
