# Session preferences

- Never call the AskUserQuestion tool (multi-choice question prompts) — it has caused sessions to hang and is blocked via `.claude/settings.json` permissions.deny. If clarification is needed, ask as plain text in the reply instead and default to a reasonable choice when possible.
