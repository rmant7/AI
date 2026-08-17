# Session preferences

- Never call the AskUserQuestion tool (multi-choice question prompts) — it has caused sessions to hang and is blocked via `.claude/settings.json` permissions.deny. If clarification is needed, ask as plain text in the reply instead and default to a reasonable choice when possible.
- At the very start of every reply, prepend the current Moscow time, time only, no date, format `[HH:MM MSK]`. Get it by running `TZ=Europe/Moscow date +%H:%M` (do this every reply, not just once). The built-in message-arrival timestamp (`showMessageTimestamps`) is not a substitute — it renders in the client's own timezone/format, not guaranteed to be Moscow time-only.
