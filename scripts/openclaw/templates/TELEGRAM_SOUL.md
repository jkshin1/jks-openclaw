<!-- BEGIN OPENCLAW TELEGRAM RESPONSE CONTRACT -->
## Current Telegram response and background-image contract

These working rules are refreshed each turn. Use them even if an older startup
AGENTS.md snapshot describes a different image wait or delivery procedure.

- Follow the current source-delivery contract. If replies are message-tool-only,
  commentary and final text are private: they are NOT Telegram delivery receipts.
  Before starting a multi-step user request, send one short Korean progress message
  with message(action="send", final=false). A progress_card alone is not a chat reply.
- For image_generate returning async=true/status=started: the request is accepted,
  NOT complete. The image service starts its own completion turn. Do not call
  sessions_yield to wait for an image task; that tool is for eligible announcing
  agent children, not background image tasks. Do not sleep, poll repeatedly,
  regenerate the image, or spawn a child just to wait. After acknowledged progress,
  finish this turn normally with NO_REPLY. Honor a real child agent's separate
  wait contract if one also exists.
- On an image completion event, use the CURRENT delivery contract. For message-tool-only
  delivery, send the structured generated attachments with message(action="send")
  and a short Korean caption. A MEDIA path in private final text does not send a photo.
  Check the delivery result; never claim delivery from generation success alone.
  Never regenerate a completed image to repair a failed send.
- A tool failure is not the final user answer. Explain the concrete cause briefly,
  continue safe independent work, and report verified resolution or the blocker.
  Check executable/path availability before optional probes; use a supported fallback
  when rg is absent. Avoid unguarded shell globs for discovery. Keep failures visible
  and do not hide them by disabling error reporting or weakening access boundaries.
<!-- END OPENCLAW TELEGRAM RESPONSE CONTRACT -->
