#!/usr/bin/env python3
"""One isolated main-agent response-contract check; no image generation or Telegram send."""
import argparse
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
# The owner's main chat runs on Opus first; check the route that actually answers them.
DEFAULT_MODEL = 'anthropic/claude-opus-5-5'


def require(condition, message):
    # Explicit checks: `python -O` strips assert statements.
    if not condition:
        raise RuntimeError(message)


def cleanup(key, terminal, receipt):
    """Each cleanup step is attempted and recorded; an unconfirmed delete fails the smoke."""
    errors = []
    if not terminal:
        try:
            rpc('chat.abort', {'sessionKey': key})
        except Exception as error:
            errors.append('abort:' + type(error).__name__)
    try:
        rpc('sessions.delete', {'key': key, 'agentId': 'main', 'deleteTranscript': True,
                                'emitLifecycleHooks': False})
    except Exception as error:
        errors.append('delete:' + type(error).__name__)
    try:
        receipt['syntheticSessionDeleted'] = rpc_helpers.session_entry(key) is None
    except Exception as error:
        receipt['syntheticSessionDeleted'] = False
        errors.append('lookup:' + type(error).__name__)
    if errors:
        receipt['cleanupErrors'] = errors
    if not receipt['syntheticSessionDeleted']:
        receipt['ok'] = False
        receipt.setdefault('errorType', 'CleanupUnconfirmed')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', default=DEFAULT_MODEL)
    args = parser.parse_args(argv)
    os.umask(0o077)
    directory = Path(tempfile.mkdtemp(prefix='response-contract-smoke-', dir=rpc_helpers.STATE / 'operations'))
    key = 'agent:main:dashboard:response-contract-smoke-' + uuid.uuid4().hex
    created = False
    terminal = False
    receipt = {'imageGenerated': False, 'telegramDelivered': False, 'ownerConversationModified': False,
               'requestedModel': args.model}
    prompt = '''This is an isolated acceptance test, not a real photo request. Do not call tools or generate/send media.
Use the current workspace's working instructions to decide actions for these synthetic scenarios.
Return only a JSON object with exactly these fields:
progress_tool: the tool for visible progress in a message-tool-only Telegram turn;
progress_final: the boolean final argument for that progress;
image_wait_tool: the tool used solely to wait after image_generate async=true/status=started, or null if no tool;
image_accepted_turn_end: the normal final text after progress is already delivered and background image is accepted;
completion_tool: the tool used to deliver the ready image in message-tool-only mode;
completion_structured_attachments: boolean whether generated attachments must be included;
terminal_send_final: boolean final argument for the last completed reply;
handoff_send_final: boolean final argument for the LAST user-facing acknowledgement in the CURRENT turn AFTER an authorized continuation has already been scheduled successfully; no work or tools remain in this turn, the remaining task will run in a separate later turn;
tools_after_final_send: boolean whether to update progress_card after a confirmed final=true send;
regenerate_on_send_failure: boolean whether to regenerate a completed image to repair its failed send.
This test must remain tool-free. Do not write memory.'''
    try:
        rpc('sessions.create', {'key': key, 'agentId': 'main', 'model': args.model})
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
            'completion_structured_attachments': True, 'regenerate_on_send_failure': False,
            'terminal_send_final': True, 'handoff_send_final': True, 'tools_after_final_send': False}
        details = result.get('terminalReceipt', {})
        require(actual == expected, 'Response contract mismatch')
        require(details.get('successfulToolNames') == [], 'Unexpected tool use')
        require(details.get('rerouted') is False, 'Unexpected model reroute')
        receipt.update(ok=True, model=details.get('effective'), checks=len(expected), actual=actual)
    except Exception as error:
        receipt.update(ok=False, errorType=type(error).__name__)
    finally:
        if created:
            cleanup(key, terminal, receipt)
        (directory / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2))
    print(json.dumps({'receiptPath': str(directory / 'receipt.json'), **receipt}, ensure_ascii=False))
    return 0 if receipt.get('ok') else 1

if __name__ == '__main__':
    raise SystemExit(main())
