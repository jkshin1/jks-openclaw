#!/usr/bin/env python3
"""Offline qualification for the exact OpenClaw 2026.9.3 Sol candidate."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("patch-gpt6-sol.py")
SPEC = importlib.util.spec_from_file_location("patch_gpt6_sol", SCRIPT)
PATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCH)
PACKAGE = Path.home() / ".local/openclaw-2026.8.1/lib/node_modules/openclaw"
STAGED_ORIGINAL_ROOT = Path(os.environ.get(
    "OPENCLAW_SOL_PATCH_ORIGINAL_DIR",
    str(Path.home() / ".openclaw-personaledge/operations/sol-default-yd7ln61f/"
        "runtime-candidate-final/original"),
))


def reverse_candidate(relative, source):
    """Reconstruct the reviewed original only from an exact candidate hash."""
    if relative == "dist/extensions/openai/openclaw.plugin.json":
        anchor = '          {\n            "id": "gpt-5.6-sol"'
        inserted = PATCH.rendered_model("          ") + ",\n"
        return PATCH.replace_once(source, inserted + anchor, anchor)
    if relative == "dist/thinking-policy-DdkggmZ1.mjs":
        anchor = '\t\t\t{\n\t\t\t\t"id": "gpt-5.6-sol"'
        lines = json.dumps(PATCH.SOL_MODEL, ensure_ascii=False, indent=2).splitlines()
        inserted = "\n".join("\t\t\t" + ("\t" * ((len(line) - len(line.lstrip())) // 2))
                             + line.lstrip() for line in lines) + ",\n"
        return PATCH.replace_once(source, inserted + anchor, anchor)
    if relative == "dist/model-route-contract-pNgq17sM.mjs":
        source = PATCH.replace_once(
            source,
            'const OPENAI_DUAL_ROUTE_MODEL_IDS = [\n\tOPENAI_GPT_6_ASTRA_MODEL_ID,\n\tOPENAI_GPT_6_SOL_MODEL_ID,',
            'const OPENAI_DUAL_ROUTE_MODEL_IDS = [\n\tOPENAI_GPT_6_ASTRA_MODEL_ID,',
        )
        return PATCH.replace_once(source,
                                  '\nconst OPENAI_GPT_6_SOL_MODEL_ID = "gpt-6-sol";', "")
    if relative == "dist/openai-chatgpt-provider-BB-cMiBP.mjs":
        return PATCH.replace_once(source,
                                  'if (lower === "gpt-6-astra" || lower === "gpt-6-sol") {',
                                  'if (lower === "gpt-6-astra") {')
    raise ValueError("unreviewed runtime path")


def original_fixture_bytes(relative):
    expected = PATCH.FILES[relative]
    if STAGED_ORIGINAL_ROOT.is_dir():
        staged = STAGED_ORIGINAL_ROOT / relative
        if staged.is_symlink() or not staged.is_file():
            raise ValueError("staged original is missing or redirected: " + relative)
        data = staged.read_bytes()
    else:
        installed = PACKAGE / relative
        if installed.is_symlink() or not installed.is_file():
            raise ValueError("runtime fixture is missing or redirected: " + relative)
        data = installed.read_bytes()
        if PATCH.digest(data) == PATCH.AFTER_SHA256[relative]:
            data = reverse_candidate(relative, data.decode("utf-8")).encode("utf-8")
    if PATCH.digest(data) != expected:
        raise ValueError("original fixture differs from reviewed OpenClaw source: " + relative)
    return data


def qualified_package():
    if not PACKAGE.is_dir():
        return False
    try:
        return json.loads((PACKAGE / "package.json").read_text())["version"] == PATCH.VERSION
    except (OSError, ValueError, KeyError):
        return False


class GatewayStopGateTest(unittest.TestCase):
    """Runs without the retired 2026.9.3 package: the gate precedes any runtime write."""

    def test_install_requires_a_stopped_and_inspectable_gateway(self):
        state = Path(tempfile.mkdtemp()) / "state"
        state.mkdir()
        self.addCleanup(__import__("shutil").rmtree, state.parent)
        (state / "openclaw.json").write_text(json.dumps({"gateway": {"port": 18789}}))
        def lsof(code, output=b""):
            return lambda *a, **k: subprocess.CompletedProcess(a, code, output, b"")
        PATCH.require_gateway_stopped(state, lsof(1))
        for runner in (lsof(0, b"node 123 LISTEN"), lsof(2), lsof(1, b"node")):
            with self.assertRaisesRegex(ValueError, "Gateway must be stopped"):
                PATCH.require_gateway_stopped(state, runner)
        with patch.object(PATCH, "require_gateway_stopped", side_effect=ValueError("Gateway must be stopped")), \
                patch.object(PATCH, "apply") as apply, \
                patch.object(sys, "argv", ["patch", "--package", "/p", "--output", "/o", "--install"]):
            with self.assertRaises(ValueError):
                PATCH.main()
        apply.assert_not_called()



@unittest.skipUnless(qualified_package(), "exact installed OpenClaw 2026.9.3 package unavailable")
class InstalledSourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="openclaw-sol-patch-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def copy_package_files(self):
        package = self.root / "package"
        package.mkdir()
        (package / "package.json").write_text(json.dumps({"version": PATCH.VERSION}))
        for relative in PATCH.FILES:
            target = package / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(original_fixture_bytes(relative))
        return package

    def test_candidate_preserves_original_and_has_reviewed_sol_route(self):
        package = self.copy_package_files()
        output = self.root / "candidate"
        receipt = PATCH.prepare(package, output)
        self.assertFalse(receipt["installed"])
        self.assertEqual(receipt["modelRequests"], 0)
        self.assertEqual(len(receipt["files"]), len(PATCH.FILES))
        self.assertEqual({item["path"]: item["afterSha256"] for item in receipt["files"]},
                         PATCH.AFTER_SHA256)
        for relative, expected in PATCH.FILES.items():
            self.assertEqual(PATCH.digest((package / relative).read_bytes()), expected)
            self.assertEqual((output / "original" / relative).read_bytes(),
                             (package / relative).read_bytes())
            self.assertEqual(PATCH.digest((output / "candidate" / relative).read_bytes()),
                             PATCH.AFTER_SHA256[relative])

        manifest = json.loads((output / "candidate/dist/extensions/openai/openclaw.plugin.json").read_text())
        models = manifest["modelCatalog"]["providers"]["openai"]["models"]
        self.assertEqual([model["id"] for model in models[:3]],
                         ["gpt-6-astra", "gpt-6-sol", "gpt-5.6-sol"])
        self.assertEqual(models[1], PATCH.SOL_MODEL)

        node = shutil.which("node") or "/opt/homebrew/opt/node/bin/node"
        for relative in PATCH.FILES:
            if relative.endswith(".mjs"):
                subprocess.run([node, "--check", str(output / "candidate" / relative)],
                               check=True, capture_output=True, timeout=15)
        route = output / "candidate/dist/model-route-contract-pNgq17sM.mjs"
        script = (
            "import { pathToFileURL } from 'node:url'; "
            "const m = await import(pathToFileURL(process.argv[1])); "
            "if (!m.v('gpt-6-sol') || !m.t.includes('gpt-6-sol') || "
            "!m._.includes('gpt-6-sol')) process.exit(1);"
        )
        subprocess.run([node, "--input-type=module", "-e", script, str(route)],
                       check=True, capture_output=True, timeout=15)

        # Import the candidate ChatGPT provider against the installed package's
        # unchanged helpers. The pure resolver makes no Gateway or model request.
        candidate_dist = output / "candidate/dist"
        for original in (PACKAGE / "dist").glob("*.mjs"):
            helper = candidate_dist / original.name
            if not helper.exists():
                helper.symlink_to(original)
        provider = candidate_dist / "openai-chatgpt-provider-BB-cMiBP.mjs"
        resolution = (
            "const m = await import(process.argv[1]); "
            "const p = m.n(); "
            "const x = p.resolveDynamicModel({modelId:'gpt-6-sol',provider:'openai',"
            "providerConfig:{},modelRegistry:{find:()=>undefined}}); "
            "console.log(JSON.stringify({id:x?.id,provider:x?.provider,api:x?.api,"
            "baseUrl:x?.baseUrl,reasoning:x?.reasoning,input:x?.input,"
            "contextWindow:x?.contextWindow}));"
        )
        resolved = subprocess.run([node, "--input-type=module", "-e", resolution,
                                   str(provider)], check=True, capture_output=True,
                                  text=True, timeout=30)
        self.assertEqual(json.loads(resolved.stdout), {
            "id": "gpt-6-sol", "provider": "openai", "api": "openai-chatgpt-responses",
            "baseUrl": "https://chatgpt.com/backend-api/codex", "reasoning": True,
            "input": ["text", "image"], "contextWindow": 1050000,
        })

        # Keep OpenAI's public provider unchanged and verify that its Codex
        # branch reaches the patched ChatGPT resolver through relative imports.
        openai_provider = candidate_dist / "openai-provider-CN0XOFsL.mjs"
        openai_provider.unlink()
        shutil.copy2(PACKAGE / "dist/openai-provider-CN0XOFsL.mjs", openai_provider)
        delegated = (
            "const m = await import(process.argv[1]); "
            "const x = m.t().resolveDynamicModel({modelId:'gpt-6-sol',provider:'openai',"
            "agentRuntimeId:'codex',providerConfig:{},modelRegistry:{find:()=>undefined}}); "
            "console.log(JSON.stringify({id:x?.id,api:x?.api,baseUrl:x?.baseUrl,"
            "contextWindow:x?.contextWindow}));"
        )
        result = subprocess.run([node, "--input-type=module", "-e", delegated,
                                 str(openai_provider)], check=True, capture_output=True,
                                text=True, timeout=30)
        self.assertEqual(json.loads(result.stdout), {
            "id": "gpt-6-sol", "api": "openai-chatgpt-responses",
            "baseUrl": "https://chatgpt.com/backend-api/codex", "contextWindow": 1050000,
        })

    def test_refuses_drift_before_writing_candidate(self):
        package = self.copy_package_files()
        target = package / "dist/openai-chatgpt-provider-BB-cMiBP.mjs"
        target.write_bytes(target.read_bytes() + b"\n")
        output = self.root / "candidate"
        with self.assertRaisesRegex(ValueError, "unreviewed or already patched"):
            PATCH.prepare(package, output)
        self.assertFalse(output.exists())

    def test_refuses_redirected_source_and_overlapping_output(self):
        package = self.copy_package_files()
        target = package / "dist/openai-chatgpt-provider-BB-cMiBP.mjs"
        target.unlink()
        outside = self.root / "outside-source"
        outside.write_bytes(original_fixture_bytes("dist/openai-chatgpt-provider-BB-cMiBP.mjs"))
        target.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "missing or redirected"):
            PATCH.prepare(package, self.root / "candidate")
        with self.assertRaisesRegex(ValueError, "outside the runtime package"):
            PATCH.prepare(package, package / "bad-candidate")

    def test_reviewed_candidate_can_reconstruct_original_fixture(self):
        for relative, expected in PATCH.FILES.items():
            original = original_fixture_bytes(relative)
            candidate = PATCH.patch_text(relative, original.decode("utf-8"))
            self.assertEqual(PATCH.digest(candidate.encode("utf-8")),
                             PATCH.AFTER_SHA256[relative])
            restored = reverse_candidate(relative, candidate)
            self.assertEqual(PATCH.digest(restored.encode("utf-8")), expected)

    def test_installs_and_rolls_back_only_reviewed_bytes_in_temp_package(self):
        package = self.copy_package_files()
        output = self.root / "candidate"
        PATCH.prepare(package, output)
        installed = PATCH.apply(package, output)
        self.assertTrue(installed["installed"])
        self.assertTrue(json.loads((output / "patch-receipt.json").read_text())["installed"])
        for relative, expected in PATCH.AFTER_SHA256.items():
            self.assertEqual(PATCH.digest((package / relative).read_bytes()), expected)

        rolled_back = PATCH.apply(package, output, rollback=True)
        self.assertFalse(rolled_back["installed"])
        self.assertFalse(json.loads((output / "patch-receipt.json").read_text())["installed"])
        for relative, expected in PATCH.FILES.items():
            self.assertEqual(PATCH.digest((package / relative).read_bytes()), expected)

    def test_install_refuses_runtime_drift_before_any_write(self):
        package = self.copy_package_files()
        output = self.root / "candidate"
        PATCH.prepare(package, output)
        target = package / "dist/openai-chatgpt-provider-BB-cMiBP.mjs"
        target.write_bytes(target.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "runtime source drift"):
            PATCH.apply(package, output)
        for relative, expected in PATCH.FILES.items():
            if package / relative != target:
                self.assertEqual(PATCH.digest((package / relative).read_bytes()), expected)
        self.assertFalse(json.loads((output / "patch-receipt.json").read_text())["installed"])

    def test_rollback_recovers_interrupted_mixed_install(self):
        package = self.copy_package_files()
        output = self.root / "candidate"
        PATCH.prepare(package, output)
        first = next(iter(PATCH.FILES))
        shutil.copy2(output / "candidate" / first, package / first)
        self.assertFalse(json.loads((output / "patch-receipt.json").read_text())["installed"])
        PATCH.apply(package, output, rollback=True)
        for relative, expected in PATCH.FILES.items():
            self.assertEqual(PATCH.digest((package / relative).read_bytes()), expected)


if __name__ == "__main__":
    unittest.main()
