#!/usr/bin/env python3
"""Offline installer isolation and source qualification checks; never install Hermes."""

import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "install_hermes_worker_tested", Path(__file__).with_name("install-hermes-worker.py"))
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)


class EnvironmentTests(unittest.TestCase):
    def test_clean_environment_strips_credentials_proxies_and_execution_hooks(self):
        hostile = {
            "HOME": "/synthetic/home", "TMPDIR": "/synthetic/tmp", "LANG": "C", "TERM": "dumb",
            "OPENAI_API_KEY": "fake-openai", "ANTHROPIC_API_KEY": "fake-anthropic",
            "OPENROUTER_API_KEY": "fake-router", "OPENAI_BASE_URL": "https://invalid.example",
            "HTTP_PROXY": "http://proxy.invalid", "HTTPS_PROXY": "http://proxy.invalid",
            "ALL_PROXY": "socks5://proxy.invalid", "NO_PROXY": "localhost",
            "http_proxy": "http://proxy.invalid", "https_proxy": "http://proxy.invalid",
            "PYTHONPATH": "/untrusted/hooks", "PYTHONSTARTUP": "/untrusted/startup.py",
            "NODE_OPTIONS": "--require=/untrusted/hook.js", "LD_PRELOAD": "/untrusted/hook.so",
            "DYLD_INSERT_LIBRARIES": "/untrusted/hook.dylib", "BASH_ENV": "/untrusted/bashrc",
            "HERMES_HOME": "/other-agent", "HERMES_MODEL": "unrequested-model",
            "HERMES_PROVIDER": "paid-api", "CODEX_HOME": "/existing-login",
            "PATH": "/untrusted/bin", "UV_INDEX_URL": "https://untrusted.example/simple",
        }
        with patch.dict(os.environ, hostile, clear=True):
            env = installer.clean_environment(Path("/synthetic/worker"))
        inherited = {key for key, value in env.items() if hostile.get(key) == value}
        self.assertEqual(inherited, {"HOME", "TMPDIR", "LANG", "TERM"})
        self.assertEqual(env["HERMES_HOME"], "/synthetic/worker/profile")
        self.assertEqual(env["CODEX_HOME"], "/synthetic/worker/profile/codex-disabled-import")
        self.assertEqual(env["PYTHONNOUSERSITE"], "1")
        self.assertEqual(env["HERMES_DISABLE_LAZY_INSTALLS"], "1")


class FailureRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve() / "worker"

    def test_failed_install_moves_partial_root_aside_so_retry_is_possible(self):
        calls = []

        def run(command, *, env, log=None, timeout=900):
            calls.append(command[1] if len(command) > 1 else command[0])
            if command[0] == "git" and "rev-parse" in command and len(calls) == 1:
                return installer.COMMIT
            if command[0] == "git" and "status" in command:
                return ""
            if str(command[0]).endswith("uv"):
                raise ValueError("installation command failed; inspect private install.log")
            return ""
        with patch.object(installer, "run", run), patch.object(installer, "PYTHON", Path(sys.executable)), \
                patch.object(installer, "UV", Path("/nonexistent/bin/uv")), \
                patch.object(Path, "is_file", lambda self: True):
            with self.assertRaisesRegex(ValueError, "installation command failed"):
                installer.install(self.root, Path(self.tmp.name))
        self.assertFalse(self.root.exists())
        failed = list(self.root.parent.glob("worker.failed-*"))
        self.assertEqual(len(failed), 1)
        self.assertTrue((failed[0] / "install.log").is_file())

    def configure_fixture(self):
        profile = self.root / "profile"
        (profile / "codex-disabled-import").mkdir(parents=True, mode=0o700)
        (profile / "skills" / "bundled").mkdir(parents=True)
        (profile / "skills" / "bundled" / "SKILL.md").write_text("seeded")
        (profile / "SOUL.md").write_text("seeded soul")
        (profile / "codex-disabled-import" / "config.toml").write_text("# No shared Codex authentication.\n")
        (profile / "auth.json").write_text('{"credential_pool": {}}')
        (self.root / "installation.json").write_text('{"commit": "%s"}' % installer.COMMIT)
        return profile

    def test_failed_configure_restores_startup_files_and_keeps_auth_bytes(self):
        profile = self.configure_fixture()
        before_auth = (profile / "auth.json").read_bytes()
        fake_worker = type("Worker", (), {"validate_auth": staticmethod(lambda auth: None),
                                          "REQUIRED_CONFIG": {"toolsets": []},
                                          "validate_profile": staticmethod(lambda *a: (_ for _ in ()).throw(
                                              ValueError("profile validation failed")))})
        real_spec = importlib.util.spec_from_file_location
        with patch.object(installer.importlib.util, "module_from_spec", lambda spec: fake_worker), \
                patch.object(type(real_spec("x", __file__).loader), "exec_module", lambda self, module: None):
            with self.assertRaisesRegex(ValueError, "profile validation failed"):
                installer.configure(self.root)
        self.assertEqual((profile / "auth.json").read_bytes(), before_auth)
        self.assertEqual((profile / "SOUL.md").read_text(), "seeded soul")
        self.assertEqual((profile / "skills/bundled/SKILL.md").read_text(), "seeded")
        self.assertTrue((profile / "codex-disabled-import/config.toml").is_file())
        self.assertFalse((profile / "config.yaml").exists())
        self.assertFalse((profile / ".no-bundled-skills").exists())
        self.assertFalse((self.root / "startup-backup").exists())
        self.assertFalse((self.root / "configuration.json").exists())


class SourceQualificationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.parent = Path(self.tmp.name).resolve()
        self.source = self.parent / "source"
        self.source.mkdir()
        self.root = self.parent / "installation"
        self.executable = self.parent / "unused-tool"
        self.executable.write_text("not executable and must never be called")
        self.git("init", "--quiet")
        (self.source / "reviewed.txt").write_text("reviewed synthetic source\n")
        self.git("add", "reviewed.txt")
        self.git("-c", "user.name=Synthetic Test", "-c", "user.email=test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Synthetic fixture")
        self.commit = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        result = subprocess.run(["git", "-C", str(self.source), *args], capture_output=True,
                                text=True, timeout=10, check=True)
        return result.stdout

    def attempt_install(self, commit):
        with patch.object(installer, "COMMIT", commit), \
                patch.object(installer, "PYTHON", self.executable), \
                patch.object(installer, "UV", self.executable):
            installer.install(self.root, self.source)

    def test_unreviewed_commit_is_rejected_without_creating_installation(self):
        with self.assertRaisesRegex(ValueError, "reviewed Hermes revision"):
            self.attempt_install("0" * 40)
        self.assertFalse(self.root.exists())

    def test_modified_tracked_source_is_rejected_before_clone_or_dependency_install(self):
        (self.source / "reviewed.txt").write_text("unreviewed modification\n")
        with self.assertRaisesRegex(ValueError, "source has modifications"):
            self.attempt_install(self.commit)
        self.assertFalse(self.root.exists())

    def test_existing_installation_is_preserved(self):
        self.root.mkdir()
        sentinel = self.root / "owner-state.txt"
        sentinel.write_text("keep")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.attempt_install(self.commit)
        self.assertEqual(sentinel.read_text(), "keep")
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["owner-state.txt"])

    def test_redirected_private_directory_is_rejected(self):
        original = self.parent / "original"
        original.mkdir()
        redirect = self.parent / "redirect"
        redirect.symlink_to(original, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "redirected"):
            installer.private_directory(redirect / "profile")
        self.assertFalse((original / "profile").exists())


class ProcessCleanupTests(unittest.TestCase):
    def test_install_command_timeout_terminates_descendants(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            pid_file = root / "child.pid"
            heartbeat = root / "heartbeat.txt"
            child_script = (
                "import pathlib,time\n"
                "target=pathlib.Path(" + repr(str(heartbeat)) + ")\n"
                "while True:\n"
                "    target.write_text(str(time.monotonic_ns()))\n"
                "    time.sleep(0.01)\n"
            )
            script = (
                "import pathlib,subprocess,sys,time; "
                "p=subprocess.Popen([sys.executable,'-c'," + repr(child_script) + "]); "
                "pathlib.Path(sys.argv[1]).write_text(str(p.pid)); time.sleep(60)"
            )
            descendant = None
            try:
                with self.assertRaises((subprocess.TimeoutExpired, ValueError)):
                    installer.run([sys.executable, "-c", script, str(pid_file)],
                                  env=installer.clean_environment(root), timeout=0.8)
                self.assertTrue(pid_file.is_file(), "synthetic child did not start before timeout")
                descendant = int(pid_file.read_text())
                # Observe actual child work rather than using `ps`, which is
                # forbidden in some desktop sandboxes, or counting zombies.
                self.assertTrue(heartbeat.is_file(), "synthetic descendant did not start")
                time.sleep(0.1)
                first = heartbeat.read_bytes()
                time.sleep(0.1)
                self.assertEqual(heartbeat.read_bytes(), first,
                                 "installer timeout left a descendant process running")
            finally:
                if descendant is None and pid_file.is_file():
                    descendant = int(pid_file.read_text())
                if descendant is not None:
                    try:
                        os.kill(descendant, signal.SIGKILL)
                    except ProcessLookupError:
                        pass


if __name__ == "__main__":
    unittest.main()
