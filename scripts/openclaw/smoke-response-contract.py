#!/usr/bin/env python3
"""One isolated main-agent response-contract check; no image generation or Telegram send."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import uuid

spec = importlib.util.spec_from_file_location('rpc_helpers', Path(__file__).with_name('smoke-glm-thinking.py'))
rpc_helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rpc_helpers)
rpc = rpc_helpers.rpc

def main():
    os.umask(0o077)
    directory = Path(tempfile.mkdtemp(prefix='response-contract-smoke-', dir=rpc_helpers.STATE / 'operations'))
    key = 'agent:main:dashboard:response-contract-smoke-' + uuid.uuid4().hex
    created = False
    terminal = False
    receipt = {'imageGenerated': False, 'telegramDelivered': False, 'ownerConversationModified': False}
    prompt = '''This is an isolated acceptance test, not a real photo request. Do not call tools or generate/send media.
Use the current workspace's working instructions to decide actions for these synthetic scenarios.
Return only a JSON object with exactly these fields:
progress_tool: the tool for visible progress in a message-tool-only Telegram turn;
progress_final: the boolean final argument for that progress;
image_wait_tool: the tool used solely to wait after image_generate async=true/status=started, or null if no tool;
image_accepted_turn_end: the normal final text after progress is already delivered and background image is accepted;
completion_tool: the tool used to deliver the ready image in message-tool-only mode;
completion_structured_attachments: boolean whether generated attachments must be included;
regenerate_on_send_failure: boolean whether to regenerate a completed image to repair its failed send.
This test must remain tool-free. Do not write memory.'''
    try:
        rpc('sessions.create', {'key': key, 'agentId': 'main', 'model': 'openai/gpt-6-astra'})
        created = True
        started = rpc('agent', {'agentId': 'main', 'sessionKey': key, 'message': prompt,
            'deliver': False, 'disableMessageTool': True, 'timeout': 180,
            'idempotencyKey': 'response-contract-' + uuid.uuid4().hex})
        run_id = started['runId']
        receipt['runId'] = run_id
        deadline = time.monotonic() + 210
        while time.monotonic() < deadline:
            result = rpc('agent.wait', {'runId': run_id, 'timeoutMs': 30000})
            if result.get('endedAt') or result.get('status') != 'timeout':
                terminal = True
                break
        (directory / 'terminal.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        if not terminal or result.get('status') != 'ok':
            raise RuntimeError('Synthetic model run did not complete successfully')
        raw = result.get('terminalReply', {}).get('text', '').strip()
        actual = json.loads(raw)
        expected = {'progress_tool': 'message', 'progress_final': False, 'image_wait_tool': None,
            'image_accepted_turn_end': 'NO_REPLY', 'completion_tool': 'message',
            'completion_structured_attachments': True, 'regenerate_on_send_failure': False}
        details = result.get('terminalReceipt', {})
        assert actual == expected, 'Response contract mismatch'
        assert details.get('successfulToolNames') == [], 'Unexpected tool use'
        assert details.get('rerouted') is False, 'Unexpected model reroute'
        receipt.update(ok=True, model=details.get('effective'), checks=len(expected), actual=actual)
    except Exception as error:
        receipt.update(ok=False, errorType=type(error).__name__)
    finally:
        if created:
            if not terminal:
                rpc('chat.abort', {'sessionKey': key})
            rpc('sessions.delete', {'key': key, 'agentId': 'main', 'deleteTranscript': True,
                                   'emitLifecycleHooks': False})
            receipt['syntheticSessionDeleted'] = rpc_helpers.session_entry(key) is None
        (directory / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2))
    print(json.dumps({'receiptPath': str(directory / 'receipt.json'), **receipt}, ensure_ascii=False))
    return 0 if receipt.get('ok') else 1

if __name__ == '__main__':
    raise SystemExit(main())
